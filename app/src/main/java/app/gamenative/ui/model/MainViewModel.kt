package app.gamenative.ui.model

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.data.BootAdRepository
import app.gamenative.di.IAppTheme
import app.gamenative.enums.AppTheme
import app.gamenative.enums.LoginResult
import app.gamenative.events.AndroidEvent
import app.gamenative.events.SteamEvent
import app.gamenative.service.SteamService
import app.gamenative.utils.BootAdView
import app.gamenative.utils.ConversionTracker
import app.gamenative.ui.data.MainState
import app.gamenative.ui.enums.ConnectionState
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.UpdateInfo
import com.materialkolor.PaletteStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class MainViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val appTheme: IAppTheme,
) : ViewModel() {

    companion object {
        private const val KEY_CURRENT_SCREEN_ROUTE = "current_screen_route"
        private const val WARM_PITCH_COOLDOWN_MS = 3 * 24 * 60 * 60 * 1000L
        private const val LOW_RATING_MAX = 3
        private val FAILURE_TAGS = setOf("does_not_open", "no_graphics", "directx_error")
        private const val BOOT_AD_REUSE_WINDOW_MS = 2 * 60 * 1000L

        var gamePlayedThisSession = false
            private set
    }

    private var bootAdShownAtMs = 0L
    private var bootAdHiddenAtMs = 0L
    private var bootAdDismissedAtMs = 0L
    private var bootAdDwellReported = false
    private var bootAwaitingGameWindow = false
    private var pendingWarmPitch: Pair<String, Boolean>? = null

    private fun warmPitchAllowed(): Boolean {
        if (PrefManager.tipped || BuildConfig.GOLD) return false
        return System.currentTimeMillis() - PrefManager.lastWarmPitchTime >= WARM_PITCH_COOLDOWN_MS
    }

    fun onGameFeedbackResolved(context: Context, rating: Int?, tags: Set<String> = emptySet()) {
        val (appId, sessionLongEnough) = pendingWarmPitch ?: return
        pendingWarmPitch = null
        if (rating != null && (rating <= LOW_RATING_MAX || tags.any { it in FAILURE_TAGS })) {
            // Low-rating AI debug offer used to be throttled per-container; that flow no
            // longer exists (no more launching games from this app), so just skip the pitch.
            return
        }
        val trigger = when {
            rating == 5 -> "five_star"
            rating == null && sessionLongEnough -> "long_session"
            else -> return
        }
        if (!warmPitchAllowed()) return
        viewModelScope.launch {
            _uiEvent.send(MainUiEvent.ShowMembershipPitch(appId, trigger))
        }
    }

    sealed class MainUiEvent {
        data object OnBackPressed : MainUiEvent()
        data object OnLoggedOut : MainUiEvent()
        data object LaunchApp : MainUiEvent()
        data class ExternalGameLaunch(val appId: String) : MainUiEvent()
        data class OnLogonEnded(val result: LoginResult) : MainUiEvent()
        data class SteamDisconnected(val isTerminal: Boolean) : MainUiEvent()
        data object ShowDiscordSupportDialog : MainUiEvent()
        data class ShowGameFeedbackDialog(val appId: String) : MainUiEvent()
        data class ShowMembershipPitch(val appId: String, val trigger: String) : MainUiEvent()
        data class ShowDebugReportDialog(val appId: String, val reportDir: String) : MainUiEvent()
        data class ShowAiDebugOffer(val appId: String, val trigger: String) : MainUiEvent()
        data object ServiceReady : MainUiEvent()
    }

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    private val _uiEvent = Channel<MainUiEvent>()
    val uiEvent = _uiEvent.receiveAsFlow()

    private val _offline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> get() = _offline

    fun setOffline(value: Boolean) {
        _offline.value = value
    }

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    fun setUpdateInfo(info: UpdateInfo?) {
        _updateInfo.value = info
    }

    private val onSteamConnected: (SteamEvent.Connected) -> Unit = {
        Timber.i("Received is connected")
        _state.update {
            it.copy(
                isSteamConnected = true,
                connectionState = ConnectionState.CONNECTED,
            )
        }
    }

    private val onSteamDisconnected: (SteamEvent.Disconnected) -> Unit = { event ->
        Timber.i("Received disconnected from Steam (terminal=${event.isTerminal})")
        _state.update {
            it.copy(
                isSteamConnected = false,
                connectionState = if (it.connectionState != ConnectionState.OFFLINE_MODE) {
                    ConnectionState.DISCONNECTED
                } else {
                    it.connectionState // Keep offline mode if user chose it
                },
                connectionMessage = null,
            )
        }
        viewModelScope.launch { _uiEvent.send(MainUiEvent.SteamDisconnected(event.isTerminal)) }
    }

    private val onRemotelyDisconnected: (SteamEvent.RemotelyDisconnected) -> Unit = {
        Timber.i("Received remotely disconnected from Steam")
        _state.update {
            it.copy(
                isSteamConnected = false,
                connectionState = if (it.connectionState != ConnectionState.OFFLINE_MODE) {
                    ConnectionState.DISCONNECTED
                } else {
                    it.connectionState // Keep offline mode if user chose it
                },
                connectionMessage = null,
            )
        }
        viewModelScope.launch { _uiEvent.send(MainUiEvent.SteamDisconnected(isTerminal = false)) }
    }

    private val onLoggingIn: (SteamEvent.LogonStarted) -> Unit = {
        Timber.i("Received logon started")
        _state.update {
            it.copy(
                connectionMessage = null,
                isSteamConnected = true,
            )
        }
    }

    private val onBackPressed: (AndroidEvent.BackPressed) -> Unit = {
        viewModelScope.launch {
            _uiEvent.send(MainUiEvent.OnBackPressed)
        }
    }

    private val onLogonEnded: (SteamEvent.LogonEnded) -> Unit = { event ->
        Timber.tag("MainViewModel").i("Received logon ended")
        viewModelScope.launch {
            _uiEvent.send(MainUiEvent.OnLogonEnded(event.loginResult))
        }
        // Update connection state based on login result
        when (event.loginResult) {
            LoginResult.Success -> {
                _state.update {
                    it.copy(
                        connectionMessage = null,
                        connectionTimeoutSeconds = 0,
                    )
                }
            }

            LoginResult.Failed -> {
                _state.update {
                    it.copy(
                        connectionMessage = event.message, // null falls back to UI string resource
                    )
                }
            }

            else -> {
                // DeviceAuth, DeviceConfirm, EmailAuth, InProgress - keep connecting state
            }
        }
    }

    private val onLoggedOut: (SteamEvent.LoggedOut) -> Unit = {
        Timber.tag("MainViewModel").i("Received logged out")
        viewModelScope.launch {
            _uiEvent.send(MainUiEvent.OnLoggedOut)
        }
        // Session expired or user logged out - must re-authenticate
        _state.update {
            it.copy(
                connectionState = ConnectionState.LOGGED_OUT,
                connectionMessage = null,
                isSteamConnected = false,
            )
        }
    }

    private val onExternalGameLaunch: (AndroidEvent.ExternalGameLaunch) -> Unit = {
        Timber.tag("MainViewModel").i("Received external game launch event for app ${it.appId}")
        viewModelScope.launch {
            Timber.tag("MainViewModel").i("Sending ExternalGameLaunch UI event for app ${it.appId}")
            _uiEvent.send(MainUiEvent.ExternalGameLaunch(it.appId))
        }
    }

    private val onServiceReady: (AndroidEvent.ServiceReady) -> Unit = {
        viewModelScope.launch {
            _uiEvent.send(MainUiEvent.ServiceReady)
        }
    }

    private val onSetBootingSplashText: (AndroidEvent.SetBootingSplashText) -> Unit = {
        setBootingSplashText(it.text)
        setShowBootingSplash(true)
    }

    private val onClearBootingSplash: (AndroidEvent.ClearBootingSplash) -> Unit = {
        bootingSplashTimeoutJob?.cancel()
        bootingSplashTimeoutJob = null
        bootAwaitingGameWindow = false
        setShowBootingSplash(false)
    }

    private var bootingSplashTimeoutJob: Job? = null
    private var connectionTimeoutJob: Job? = null

    init {
        // Restore persisted screen from SavedStateHandle if available
        val persistedRoute = savedStateHandle.get<String>(KEY_CURRENT_SCREEN_ROUTE)
        val restoredScreen = when (persistedRoute) {
            PluviaScreen.Home.route -> PluviaScreen.Home
            PluviaScreen.Settings.route -> PluviaScreen.Settings
            PluviaScreen.Chat.route -> PluviaScreen.Chat
            else -> null
        }

        // Determine initial connection state based on service state
        val initialConnectionState = when {
            SteamService.isConnected -> ConnectionState.CONNECTED
            else -> ConnectionState.CONNECTING
        }

        _state.update {
            it.copy(
                isSteamConnected = SteamService.isConnected,
                hasCrashedLastStart = PrefManager.recentlyCrashed,
                launchedAppId = "",
                currentScreen = restoredScreen,
                connectionState = initialConnectionState,
            )
        }

        // Register event handlers
        PluviaApp.events.on<AndroidEvent.BackPressed, Unit>(onBackPressed)
        PluviaApp.events.on<AndroidEvent.ExternalGameLaunch, Unit>(onExternalGameLaunch)
        PluviaApp.events.on<AndroidEvent.SetBootingSplashText, Unit>(onSetBootingSplashText)
        PluviaApp.events.on<AndroidEvent.ClearBootingSplash, Unit>(onClearBootingSplash)
        PluviaApp.events.on<SteamEvent.Connected, Unit>(onSteamConnected)
        PluviaApp.events.on<SteamEvent.Disconnected, Unit>(onSteamDisconnected)
        PluviaApp.events.on<SteamEvent.RemotelyDisconnected, Unit>(onRemotelyDisconnected)
        PluviaApp.events.on<SteamEvent.LogonStarted, Unit>(onLoggingIn)
        PluviaApp.events.on<SteamEvent.LogonEnded, Unit>(onLogonEnded)
        PluviaApp.events.on<SteamEvent.LoggedOut, Unit>(onLoggedOut)
        PluviaApp.events.on<AndroidEvent.ServiceReady, Unit>(onServiceReady)

        // Collect theme preferences
        viewModelScope.launch {
            appTheme.themeFlow.collect { value ->
                _state.update { it.copy(appTheme = value) }
            }
        }

        viewModelScope.launch {
            appTheme.paletteFlow.collect { value ->
                _state.update { it.copy(paletteStyle = value) }
            }
        }
    }

    override fun onCleared() {
        PluviaApp.events.off<AndroidEvent.BackPressed, Unit>(onBackPressed)
        PluviaApp.events.off<AndroidEvent.ExternalGameLaunch, Unit>(onExternalGameLaunch)
        PluviaApp.events.off<AndroidEvent.SetBootingSplashText, Unit>(onSetBootingSplashText)
        PluviaApp.events.off<AndroidEvent.ClearBootingSplash, Unit>(onClearBootingSplash)
        PluviaApp.events.off<SteamEvent.Connected, Unit>(onSteamConnected)
        PluviaApp.events.off<SteamEvent.Disconnected, Unit>(onSteamDisconnected)
        PluviaApp.events.off<SteamEvent.RemotelyDisconnected, Unit>(onRemotelyDisconnected)
        PluviaApp.events.off<SteamEvent.LogonStarted, Unit>(onLoggingIn)
        PluviaApp.events.off<SteamEvent.LogonEnded, Unit>(onLogonEnded)
        PluviaApp.events.off<SteamEvent.LoggedOut, Unit>(onLoggedOut)
        PluviaApp.events.off<AndroidEvent.ServiceReady, Unit>(onServiceReady)
        connectionTimeoutJob?.cancel()
    }

    fun setTheme(value: AppTheme) {
        appTheme.currentTheme = value
    }

    fun setPalette(value: PaletteStyle) {
        appTheme.currentPalette = value
    }

    fun setAnnoyingDialogShown(value: Boolean) {
        _state.update { it.copy(annoyingDialogShown = value) }
    }

    fun setLoadingDialogVisible(value: Boolean) {
        _state.update { it.copy(loadingDialogVisible = value) }
    }

    fun setLoadingDialogProgress(value: Float) {
        _state.update { it.copy(loadingDialogProgress = value) }
    }

    fun setLoadingDialogMessage(value: String) {
        _state.update { it.copy(loadingDialogMessage = value) }
    }

    fun setHasLaunched(value: Boolean) {
        _state.update { it.copy(hasLaunched = value) }
    }

    fun setShowBootingSplash(value: Boolean) {
        val wasShowing = _state.value.showBootingSplash
        if (value && !wasShowing) {
            // The splash hides and re-shows between boot phases; a quick re-show is the same
            // impression. Re-querying here would record extra shows and null the ad mid-boot
            // once the daily cap is crossed, unmounting the sponsor card while the splash is up.
            val held = _state.value.bootAd
            val heldAllowed = held != null &&
                (if (held.sponsored) PrefManager.bootScreenAdsEnabled else PrefManager.bootScreenRecommendationsEnabled)
            val reuse = heldAllowed && System.currentTimeMillis() - bootAdHiddenAtMs < BOOT_AD_REUSE_WINDOW_MS
            val dismissed = System.currentTimeMillis() - bootAdDismissedAtMs < BOOT_AD_REUSE_WINDOW_MS
            val ad = if (reuse || dismissed) {
                held
            } else {
                BootAdRepository.pickBootCard()?.also {
                    bootAdShownAtMs = System.currentTimeMillis()
                    bootAdDwellReported = false
                    BootAdView.begin(it)
                    if (it.sponsored) BootAdRepository.recordShown(it.campaignId) else BootAdRepository.noteShown(it.campaignId)
                }
            }
            Timber.tag("BootAdTrace").i("show: wasShowing=false held=%s reuse=%s ad=%s", held != null, reuse, ad?.campaignId)
            _state.update { it.copy(showBootingSplash = true, bootAd = ad) }
            // Resolve after publishing so an instant cache hit can't race the state write.
            if (ad != null && !ad.sponsored && !reuse) {
                viewModelScope.launch(Dispatchers.IO) {
                    val upgraded = BootAdRepository.resolveHouseTrailer(ad) ?: return@launch
                    _state.update { s -> if (s.bootAd?.campaignId == upgraded.campaignId) s.copy(bootAd = upgraded) else s }
                }
            }
        } else if (!value && wasShowing) {
            Timber.tag("BootAdTrace").i("hide: ad=%s", _state.value.bootAd?.campaignId)
            bootAdHiddenAtMs = System.currentTimeMillis()
            _state.value.bootAd?.let { ad ->
                if (!bootAdDwellReported) {
                    bootAdDwellReported = true
                    val dwellSeconds = (System.currentTimeMillis() - bootAdShownAtMs) / 1000L
                    if (ad.sponsored) {
                        ConversionTracker.bootAdShown(campaignId = ad.campaignId, dwellSeconds = dwellSeconds)
                    } else {
                        ConversionTracker.bootRecShown(campaignId = ad.campaignId, dwellSeconds = dwellSeconds)
                    }
                }
            }
            // bootAd stays in state so the exit fade keeps rendering it; the next show replaces it.
            _state.update { it.copy(showBootingSplash = false) }
        } else {
            _state.update { it.copy(showBootingSplash = value) }
        }
    }

    fun setBootingSplashText(value: String) {
        _state.update { it.copy(bootingSplashText = value) }
    }

    fun setBootingSplashHeroImageUrl(url: String) {
        _state.update { it.copy(bootingSplashHeroImageUrl = url) }
    }

    // Connection state management

    /**
     * Called when starting a reconnection attempt.
     * Sets state to CONNECTING and starts a timeout counter.
     */
    fun startConnecting(message: String? = null) {
        connectionTimeoutJob?.cancel()
        _state.update {
            it.copy(
                connectionState = ConnectionState.CONNECTING,
                connectionMessage = message,
                connectionTimeoutSeconds = 0,
            )
        }

        // Start timeout counter
        connectionTimeoutJob = viewModelScope.launch {
            var seconds = 0
            while (seconds < 30 && _state.value.connectionState == ConnectionState.CONNECTING) {
                delay(1000)
                seconds++
                _state.update { it.copy(connectionTimeoutSeconds = seconds) }
            }
        }
    }

    /**
     * Called when user chooses to continue in offline mode.
     * Stops reconnection attempts and allows app to function offline.
     */
    fun continueOffline() {
        connectionTimeoutJob?.cancel()
        _state.update {
            it.copy(
                connectionState = ConnectionState.OFFLINE_MODE,
                connectionMessage = null,
                connectionTimeoutSeconds = 0,
            )
        }
    }

    /**
     * Called when user wants to retry connection.
     * Resets offline mode and triggers reconnection.
     */
    fun retryConnection() {
        if (_state.value.connectionState == ConnectionState.OFFLINE_MODE ||
            _state.value.connectionState == ConnectionState.DISCONNECTED
        ) {
            startConnecting()
        }
    }

    fun setCurrentScreen(currentScreen: String?) {
        // Route matching accounts for query params and path params in templates
        // e.g., "home?offline={offline}" should match Home, "chat/{id}" should match Chat
        val screen = when {
            currentScreen == null -> PluviaScreen.LoginUser
            currentScreen == PluviaScreen.LoginUser.route -> PluviaScreen.LoginUser
            currentScreen.startsWith(PluviaScreen.Home.route) -> PluviaScreen.Home
            currentScreen == PluviaScreen.Settings.route -> PluviaScreen.Settings
            currentScreen.startsWith("chat") -> PluviaScreen.Chat
            else -> PluviaScreen.LoginUser
        }

        setCurrentScreen(screen)
    }

    fun setCurrentScreen(value: PluviaScreen) {
        _state.update { it.copy(currentScreen = value) }
        savedStateHandle[KEY_CURRENT_SCREEN_ROUTE] = value.route
    }

    /**
     * Gets the persisted route from SavedStateHandle
     *
     * Returns the route the user was on before process death, or null if:
     * - No route was persisted
     * - The persisted route is LoginUser (not meaningful to restore)
     * - The persisted route is Chat (dynamic IDs require special handling)
     *
     * Navigation decisions should be made by the caller based on the current
     * NavController destination, not by tracking internal flags.
     *
     * TODO: reconsider this approach when merging GOG and Epic
     */
    fun getPersistedRoute(): String? {
        val persistedRoute = savedStateHandle.get<String>(KEY_CURRENT_SCREEN_ROUTE)
        return when {
            persistedRoute == null -> null
            persistedRoute == PluviaScreen.LoginUser.route -> null
            persistedRoute.startsWith("chat") -> null
            else -> persistedRoute
        }
    }

    fun clearPersistedRoute() {
        savedStateHandle[KEY_CURRENT_SCREEN_ROUTE] = PluviaScreen.LoginUser.route
    }

    fun setHasCrashedLastStart(value: Boolean) {
        if (value.not()) {
            PrefManager.recentlyCrashed = false
        }
        _state.update { it.copy(hasCrashedLastStart = value) }
    }

    fun setScreen() {
        _state.update { it.copy(resettedScreen = it.currentScreen) }
    }

    fun setLaunchedAppId(value: String) {
        // A dismissed card stays gone for the rest of that boot only.
        bootAdDismissedAtMs = 0L
        _state.update { it.copy(launchedAppId = value) }
    }

    fun setBootToContainer(value: Boolean) {
        _state.update { it.copy(bootToContainer = value) }
    }

    fun setTestGraphics(value: Boolean) {
        _state.update { it.copy(testGraphics = value) }
    }

    fun setDiagnostics(value: Boolean) {
        _state.update { it.copy(diagnostics = value) }
    }

    fun setDebugRun(value: Boolean) {
        _state.update { it.copy(debugRun = value) }
    }

    fun onGameLaunchError(error: String) {
        viewModelScope.launch {
            // Hide the splash screen if it's still showing
            bootAwaitingGameWindow = false
            bootingSplashTimeoutJob?.cancel()
            bootingSplashTimeoutJob = null
            setShowBootingSplash(false)
            // See onClearBootingSplash's kdoc — broadcast so MainActivity's own instance clears
            // too when this call is actually running on ImmersiveXrActivity's separate instance.
            PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)

            // You could also show an error dialog here if needed
            Timber.tag("MainViewModel").e("Game launch error: $error")
        }
    }

    /** The card's close control: drop the boot card for this boot, optionally turning the channel off. */
    fun dismissBootAd(optOut: Boolean) {
        val ad = _state.value.bootAd ?: return
        val now = System.currentTimeMillis()
        val dwellMs = now - bootAdShownAtMs
        Timber.tag("BootAdTrace").i("dismiss: ad=%s optOut=%s dwellMs=%d", ad.campaignId, optOut, dwellMs)
        ConversionTracker.track(
            "boot_ad_dismissed",
            mapOf(
                "campaign_id" to ad.campaignId,
                "sponsored" to ad.sponsored,
                "template" to ad.template,
                "opt_out" to optOut,
                "dwell_ms" to dwellMs,
            ),
        )
        if (optOut) {
            if (ad.sponsored) PrefManager.bootScreenAdsEnabled = false else PrefManager.bootScreenRecommendationsEnabled = false
            ConversionTracker.track(
                "boot_ad_opted_out",
                mapOf(
                    "campaign_id" to ad.campaignId,
                    "sponsored" to ad.sponsored,
                    "\$set" to mapOf((if (ad.sponsored) "boot_ads_enabled" else "boot_recs_enabled") to false),
                ),
            )
        }
        if (!bootAdDwellReported) {
            bootAdDwellReported = true
            val dwellSeconds = dwellMs / 1000L
            if (ad.sponsored) {
                ConversionTracker.bootAdShown(campaignId = ad.campaignId, dwellSeconds = dwellSeconds)
            } else {
                ConversionTracker.bootRecShown(campaignId = ad.campaignId, dwellSeconds = dwellSeconds)
            }
        }
        bootAdDismissedAtMs = now
        _state.update { it.copy(bootAd = null) }
    }

    /** The splash's back button: hide the splash and close the guest the way a blocked session does. */
    fun abortBoot() {
        viewModelScope.launch {
            Timber.tag("MainViewModel").i("Boot aborted from the splash")
            bootAwaitingGameWindow = false
            bootingSplashTimeoutJob?.cancel()
            bootingSplashTimeoutJob = null
            setShowBootingSplash(false)
            PluviaApp.events.emit(AndroidEvent.ClearBootingSplash)
            PluviaApp.events.emit(SteamEvent.ForceCloseApp)
        }
    }

}
