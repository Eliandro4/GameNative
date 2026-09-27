package app.gamenative.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import app.gamenative.BuildConfig
import app.gamenative.Constants
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.enums.AppTheme
import app.gamenative.enums.LoginResult
import app.gamenative.events.AndroidEvent
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.SteamService
import com.posthog.PostHog
import app.gamenative.ui.component.ConnectionStatusBanner
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.ui.component.dialog.LoadingDialog
import app.gamenative.ui.component.dialog.MessageDialog
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.enums.ConnectionState
import app.gamenative.ui.enums.DialogType
import app.gamenative.ui.enums.Orientation
import app.gamenative.ui.model.MainViewModel
import app.gamenative.ui.screen.HomeScreen
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.ui.screen.login.UserLoginScreen
import app.gamenative.ui.screen.settings.SettingsScreen
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.LocalSnackbarHostController
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.PlatformAuthUtils
import app.gamenative.utils.SteamUtils
import app.gamenative.utils.UpdateChecker
import app.gamenative.utils.UpdateInfo
import app.gamenative.utils.UpdateInstaller
import java.util.EnumSet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

private const val SNACKBAR_SHOW_TIMEOUT_MS = 15_000L

private const val LAUNCH_PITCH_COOLDOWN_MS = 5 * 24 * 60 * 60 * 1000L

private fun NavHostController.navigateFromLoginIfNeeded(
    targetRoute: String,
    logTag: String = "PluviaMain",
) {
    val currentRoute = currentDestination?.route
    if (currentRoute == PluviaScreen.LoginUser.route) {
        Timber.tag(logTag).i("Navigating from LoginUser to $targetRoute")
        navigate(targetRoute) {
            popUpTo(PluviaScreen.LoginUser.route) {
                inclusive = true
            }
        }
    }
}

private fun trackMembershipPrompt(event: String, trigger: String) {
    if (PrefManager.usageAnalyticsEnabled) {
        PostHog.capture(
            event = event,
            properties = mapOf("trigger" to trigger),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PluviaMain(
    viewModel: MainViewModel = hiltViewModel(),
    navController: NavHostController = rememberNavController(),
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    val state by viewModel.state.collectAsStateWithLifecycle()

    var msgDialogState by rememberSaveable(stateSaver = MessageDialogState.Saver) {
        mutableStateOf(MessageDialogState(false))
    }
    val setMessageDialogState: (MessageDialogState) -> Unit = { msgDialogState = it }
    var membershipPitchTrigger by rememberSaveable { mutableStateOf("launch") }

    var hasBack by rememberSaveable { mutableStateOf(navController.previousBackStackEntry?.destination?.route != null) }

    var isConnecting by rememberSaveable { mutableStateOf(false) }

    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    // Track if connection banner was dismissed by user
    var connectionBannerDismissed by rememberSaveable { mutableStateOf(false) }

    // suppress CONNECTING banner during first attempt; DISCONNECTED always shows
    var initialConnectDone by rememberSaveable { mutableStateOf(SteamService.isConnected) }

    // Track previous connection state to detect actual changes (not just recomposition)
    val previousConnectionState = remember { mutableStateOf(state.connectionState) }

    // Reset dismissed state only when connection state actually changes
    LaunchedEffect(state.connectionState) {
        if (previousConnectionState.value != state.connectionState) {
            connectionBannerDismissed = false
            previousConnectionState.value = state.connectionState
        }
        // first attempt resolved (connected or failed)
        if (state.connectionState != ConnectionState.CONNECTING) {
            initialConnectDone = true
        }
    }

    // Check for updates on app start
    LaunchedEffect(Unit) {
        if (BuildConfig.MODERN_ANDROID || BuildConfig.XR_BUILD) return@LaunchedEffect
        val checkedUpdateInfo = UpdateChecker.checkForUpdate(context)
        if (checkedUpdateInfo != null) {
            val appVersionCode = BuildConfig.VERSION_CODE
            val serverVersionCode = checkedUpdateInfo.versionCode
            Timber.i("Update check: app versionCode=$appVersionCode, server versionCode=$serverVersionCode")
            if (appVersionCode < serverVersionCode) {
                updateInfo = checkedUpdateInfo
                viewModel.setUpdateInfo(checkedUpdateInfo)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                MainViewModel.MainUiEvent.OnBackPressed -> {
                    if (hasBack) {
                        navController.popBackStack()
                    }
                }

                MainViewModel.MainUiEvent.OnLoggedOut -> {
                    // Clear persisted route so next login starts fresh from Home
                    viewModel.clearPersistedRoute()
                    // Pop stack and go back to login
                    navController.popBackStack(
                        route = PluviaScreen.LoginUser.route,
                        inclusive = false,
                        saveState = false,
                    )
                }

                is MainViewModel.MainUiEvent.OnLogonEnded -> {
                    when (event.result) {
                        LoginResult.Success -> {
                            val currentRoute = navController.currentDestination?.route
                            val targetRoute = viewModel.getPersistedRoute() ?: PluviaScreen.Home.route
                            if (currentRoute == PluviaScreen.LoginUser.route) {
                                navController.navigateFromLoginIfNeeded(targetRoute, "LogonEnded")
                            } else if (currentRoute == PluviaScreen.Home.route + "?offline={offline}") {
                                val isCurrentlyOffline = navController.currentBackStackEntry
                                    ?.arguments?.getBoolean("offline") ?: false
                                if (isCurrentlyOffline) {
                                    navController.navigate(PluviaScreen.Home.route + "?offline=false") {
                                        popUpTo(PluviaScreen.Home.route + "?offline={offline}") {
                                            inclusive = true
                                        }
                                    }
                                }
                            }
                        }

                        LoginResult.Failed -> {
                            Timber.i("Login failed: ${event.result}")
                        }

                        else -> {
                            Timber.i("Received non-result: ${event.result}")
                        }
                    }
                }

                MainViewModel.MainUiEvent.ShowDiscordSupportDialog -> {
                    msgDialogState = MessageDialogState(
                        visible = true,
                        type = DialogType.DISCORD,
                        title = context.getString(R.string.main_discord_support_title),
                        message = context.getString(R.string.main_discord_support_message),
                        confirmBtnText = context.getString(R.string.main_open_discord),
                        dismissBtnText = context.getString(R.string.close),
                    )
                }

                is MainViewModel.MainUiEvent.ShowMembershipPitch -> {
                    val gameName = ContainerUtils.resolveGameName(event.appId)
                    PrefManager.lastWarmPitchTime = System.currentTimeMillis()
                    membershipPitchTrigger = event.trigger
                    trackMembershipPrompt("membership_prompt_shown", event.trigger)
                    msgDialogState = MessageDialogState(
                        visible = true,
                        type = DialogType.SUPPORT,
                        title = if (event.trigger == "five_star") {
                            context.getString(R.string.pitch_five_star_title, gameName)
                        } else {
                            context.getString(R.string.pitch_session_title, gameName)
                        },
                        message = context.getString(R.string.main_thank_you_message),
                        confirmBtnText = context.getString(R.string.main_join_kofi),
                        dismissBtnText = context.getString(R.string.close),
                    )
                }

                // The remaining events (LaunchApp, ExternalGameLaunch, ServiceReady,
                // SteamDisconnected, ShowGameFeedbackDialog, ShowDebugReportDialog,
                // ShowAiDebugOffer) existed to support in-app game launching, which this
                // app no longer does. Nothing to do here.
                else -> {}
            }
        }
    }

    LaunchedEffect(navController) {
        Timber.i("navController changed")

        if (!state.hasLaunched) {
            viewModel.setHasLaunched(true)

            Timber.i("Creating on destination changed listener")

            PluviaApp.onDestinationChangedListener = NavController.OnDestinationChangedListener { _, destination, _ ->
                Timber.i("onDestinationChanged to ${destination.route}")
                // in order not to trigger the screen changed launch effect
                viewModel.setCurrentScreen(destination.route)
            }
            PluviaApp.events.emit(AndroidEvent.StartOrientator)
        } else {
            PluviaApp.onDestinationChangedListener?.let {
                navController.removeOnDestinationChangedListener(it)
            }
        }

        PluviaApp.onDestinationChangedListener?.let {
            navController.addOnDestinationChangedListener(it)
        }
    }

    // TODO merge to VM?
    LaunchedEffect(state.currentScreen) {
        // do the following each time we navigate to a new screen
        if (state.resettedScreen != state.currentScreen) {
            viewModel.setScreen()
            // Hide or show status bar based on if in game or not
            val shouldShowStatusBar = !PrefManager.hideStatusBarWhenNotInGame
            PluviaApp.events.emit(AndroidEvent.SetSystemUIVisibility(shouldShowStatusBar))

            // reset available orientations
            PluviaApp.events.emit(AndroidEvent.SetAllowedOrientation(EnumSet.of(Orientation.UNSPECIFIED)))

            // find out if back is available
            hasBack = navController.previousBackStackEntry?.destination?.route != null
        }
    }

    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Only attempt reconnection if not already connected/connecting and not in offline mode
            val shouldAttemptReconnect = !state.isSteamConnected &&
                !isConnecting &&
                !SteamService.keepAlive

            if (shouldAttemptReconnect) {
                Timber.d("[PluviaMain]: Steam not connected - attempting reconnection")
                isConnecting = true
                viewModel.startConnecting()
                context.startForegroundService(Intent(context, SteamService::class.java))
            }

            // Start GOGService if user has GOG
            if (GOGService.hasStoredCredentials(context) && !GOGService.isRunning) {
                Timber.tag("GOG").d("[PluviaMain]: Starting GOGService for logged-in user")
                GOGService.start(context)
            } else {
                Timber.tag("GOG").d("GOG SERVICE Not going to start: ${GOGService.isRunning}")
            }

            // Start EpicService if user has Epic credentials
            if (EpicService.hasStoredCredentials(context) && !EpicService.isRunning) {
                Timber.d("[PluviaMain]: Starting EpicService for logged-in user")
                EpicService.start(context)
            }

            // Start AmazonService if user has Amazon credentials
            if (AmazonService.hasStoredCredentials(context) && !AmazonService.isRunning) {
                Timber.d("[PluviaMain]: Starting AmazonService for logged-in user")
                AmazonService.start(context)
            }

            // Handle navigation when already logged in (e.g., app resumed with active session)
            // Only navigate if currently on LoginUser screen to avoid disrupting user's current view
            if (PlatformAuthUtils.isSignedInToAnyPlatform(context) && !SteamService.keepAlive) {
                val baseRoute = viewModel.getPersistedRoute() ?: PluviaScreen.Home.route
                val targetRoute = if (SteamService.isLoggedIn) {
                    baseRoute
                } else {
                    // Non-Steam platforms: ensure offline param for Home
                    if (baseRoute.startsWith(PluviaScreen.Home.route)) {
                        PluviaScreen.Home.route + "?offline=true"
                    } else {
                        baseRoute
                    }
                }
                navController.navigateFromLoginIfNeeded(targetRoute, "ResumeSession")
            }
        }
    }

    // Listen for connection state changes - reset local isConnecting flag
    LaunchedEffect(state.isSteamConnected) {
        if (state.isSteamConnected) {
            isConnecting = false
        }
    }

    val onDismissRequest: (() -> Unit)?
    val onDismissClick: (() -> Unit)?
    val onConfirmClick: (() -> Unit)?
    val onActionClick: (() -> Unit)? = null
    when (msgDialogState.type) {
        DialogType.DISCORD -> {
            onConfirmClick = {
                setMessageDialogState(MessageDialogState(false))
                uriHandler.openUri("https://discord.gg/2hKv4VfZfE")
            }
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.SUPPORT -> {
            onConfirmClick = {
                uriHandler.openUri(Constants.Misc.KO_FI_LINK)
                trackMembershipPrompt("membership_prompt_clicked", membershipPitchTrigger)
                msgDialogState = MessageDialogState(visible = false)
            }
            onDismissRequest = {
                msgDialogState = MessageDialogState(visible = false)
            }
            onDismissClick = {
                msgDialogState = MessageDialogState(visible = false)
            }
        }

        DialogType.APP_UPDATE -> {
            onConfirmClick = {
                setMessageDialogState(MessageDialogState(false))
                val currentUpdateInfo = viewModel.updateInfo.value
                if (currentUpdateInfo != null) {
                    scope.launch {
                        viewModel.setLoadingDialogVisible(true)
                        viewModel.setLoadingDialogMessage("Downloading update...")
                        viewModel.setLoadingDialogProgress(0f)

                        val success = UpdateInstaller.downloadAndInstall(
                            context = context,
                            downloadUrl = currentUpdateInfo.downloadUrl,
                            versionName = currentUpdateInfo.versionName,
                            onProgress = { progress ->
                                viewModel.setLoadingDialogProgress(progress)
                            },
                        )

                        viewModel.setLoadingDialogVisible(false)
                        if (!success) {
                            msgDialogState = MessageDialogState(
                                visible = true,
                                type = DialogType.SYNC_FAIL,
                                title = context.getString(R.string.main_update_failed_title),
                                message = context.getString(R.string.main_update_failed_message),
                                dismissBtnText = context.getString(R.string.ok),
                            )
                        }
                    }
                }
            }
            onDismissClick = {
                setMessageDialogState(MessageDialogState(false))
            }
            onDismissRequest = {
                setMessageDialogState(MessageDialogState(false))
            }
        }

        DialogType.CRASH -> {
            onConfirmClick = null
            onDismissClick = null
            onDismissRequest = {
                viewModel.setHasCrashedLastStart(false)
                setMessageDialogState(MessageDialogState(false))
            }
        }

        else -> {
            onDismissRequest = null
            onDismissClick = null
            onConfirmClick = null
        }
    }

    val snackbarController = LocalSnackbarHostController.current
    var exitSnackbarVisible by remember { mutableStateOf(false) }

    LaunchedEffect(snackbarController) {
        SnackbarManager.messages.collect { message ->
            if (
                withTimeoutOrNull(SNACKBAR_SHOW_TIMEOUT_MS) {
                    snackbarController.hostState.showSnackbar(message)
                } == null
            ) {
                Timber.w("[Snackbar]: Display timed out before dismissal")
            }
            // snackbar dismissed (timeout or new message) — reset exit flag
            exitSnackbarVisible = false
        }
    }

    BackHandler(enabled = state.loadingDialogVisible) {
        // TODO: Make loading operations cancellable so Back can exit safely.
    }

    PluviaTheme(
        isDark = when (state.appTheme) {
            AppTheme.AUTO -> isSystemInDarkTheme()
            AppTheme.DAY -> false
            AppTheme.NIGHT -> true
            AppTheme.AMOLED -> true
        },
        isAmoled = (state.appTheme == AppTheme.AMOLED),
        style = state.paletteStyle,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LoadingDialog(
                visible = state.loadingDialogVisible,
                progress = state.loadingDialogProgress,
                message = state.loadingDialogMessage,
            )

            MessageDialog(
                visible = msgDialogState.visible,
                onDismissRequest = onDismissRequest,
                onConfirmClick = onConfirmClick,
                confirmBtnText = msgDialogState.confirmBtnText,
                onDismissClick = onDismissClick,
                dismissBtnText = msgDialogState.dismissBtnText,
                onActionClick = onActionClick,
                actionBtnText = msgDialogState.actionBtnText,
                icon = msgDialogState.type.icon,
                title = msgDialogState.title,
                message = msgDialogState.message,
            )

            // Connection status banner (overlay) - dismissible so users can access navigation
            if (state.currentScreen != PluviaScreen.LoginUser && !connectionBannerDismissed && initialConnectDone && !state.isSteamConnected &&
                SteamUtils.hasStoredCredentials()) {
                Box(modifier = Modifier.zIndex(5f)) {
                    ConnectionStatusBanner(
                        connectionState = state.connectionState,
                        connectionMessage = state.connectionMessage,
                        timeoutSeconds = state.connectionTimeoutSeconds,
                        onContinueOffline = {
                            viewModel.continueOffline()
                        },
                        onRetry = {
                            viewModel.retryConnection()
                            context.startForegroundService(Intent(context, SteamService::class.java))
                        },
                        onDismiss = {
                            connectionBannerDismissed = true
                        },
                    )
                }
            }

            val startDestination = rememberSaveable {
                when {
                    SteamService.isLoggedIn -> PluviaScreen.Home.route + "?offline=false"
                    // skip login screen if any service has stored credentials
                    SteamUtils.hasStoredCredentials() ||
                        GOGService.hasStoredCredentials(context) ||
                        EpicService.hasStoredCredentials(context) ||
                        AmazonService.hasStoredCredentials(context) ->
                        PluviaScreen.Home.route + "?offline=true"
                    else -> PluviaScreen.LoginUser.route
                }
            }

            NavHost(
                navController = navController,
                startDestination = startDestination,
            ) {
                /** Login **/
                composable(route = PluviaScreen.LoginUser.route) {
                    UserLoginScreen(
                        connectionState = state.connectionState,
                        onRetryConnection = viewModel::retryConnection,
                        onContinueOffline = {
                            navController.navigate(PluviaScreen.Home.route + "?offline=true")
                        },
                        onPlatformSignedIn = {
                            navController.navigate(PluviaScreen.Home.route + "?offline=true") {
                                popUpTo(PluviaScreen.LoginUser.route) { inclusive = true }
                            }
                        },
                    )
                }
                /** Library, Downloads **/
                composable(
                    route = PluviaScreen.Home.route + "?offline={offline}",
                    deepLinks = listOf(navDeepLink { uriPattern = "pluvia://home" }),
                    arguments = listOf(
                        navArgument("offline") {
                            type = NavType.BoolType
                            defaultValue = false // default when the query param isn’t present
                        },
                    ),
                ) { backStackEntry ->
                    val isOffline = backStackEntry.arguments?.getBoolean("offline") ?: false

                    // Show update/crash/support dialogs when Home is first displayed
                    // Skip when offline with Steam credentials (avoid flash when Steam reconnects)
                    LaunchedEffect(Unit) {
                        val shouldShowDialogs = !isOffline || !SteamUtils.hasStoredCredentials()

                        if (shouldShowDialogs && !state.annoyingDialogShown) {
                            val currentUpdateInfo = updateInfo
                            if (currentUpdateInfo != null) {
                                viewModel.setAnnoyingDialogShown(true)
                                msgDialogState = MessageDialogState(
                                    visible = true,
                                    type = DialogType.APP_UPDATE,
                                    title = context.getString(R.string.main_update_available_title),
                                    message = context.getString(
                                        R.string.main_update_available_message,
                                        currentUpdateInfo.versionName,
                                        currentUpdateInfo.releaseNotes?.let { "\n\n$it" } ?: "",
                                    ),
                                    confirmBtnText = context.getString(R.string.main_update_button),
                                    dismissBtnText = context.getString(R.string.main_later_button),
                                )
                            } else if (state.hasCrashedLastStart) {
                                viewModel.setAnnoyingDialogShown(true)
                                msgDialogState = MessageDialogState(
                                    visible = true,
                                    type = DialogType.CRASH,
                                    title = context.getString(R.string.main_recent_crash_title),
                                    message = context.getString(R.string.main_recent_crash_message),
                                    confirmBtnText = context.getString(R.string.ok),
                                )
                            } else if (!(PrefManager.tipped || BuildConfig.GOLD) &&
                                PrefManager.hasAttemptedGameLaunch &&
                                !MainViewModel.gamePlayedThisSession &&
                                System.currentTimeMillis() - PrefManager.lastLaunchPitchTime >= LAUNCH_PITCH_COOLDOWN_MS
                            ) {
                                viewModel.setAnnoyingDialogShown(true)
                                PrefManager.lastLaunchPitchTime = System.currentTimeMillis()
                                membershipPitchTrigger = "launch"
                                trackMembershipPrompt("membership_prompt_shown", "launch")
                                msgDialogState = MessageDialogState(
                                    visible = true,
                                    type = DialogType.SUPPORT,
                                    title = context.getString(R.string.main_thank_you_title),
                                    message = context.getString(R.string.main_thank_you_message),
                                    confirmBtnText = context.getString(R.string.main_join_kofi),
                                    dismissBtnText = context.getString(R.string.close),
                                )
                            }
                        }
                    }

                    HomeScreen(
                        // Play/launch actions are no-ops: this app no longer boots games in-app,
                        // it only downloads/manages them. Actual downloader UI lives in the
                        // library/app-detail screens, not here.
                        onClickPlay = { _, _ -> },
                        onTestGraphics = { },
                        onPlayWithDiagnostics = { },
                        onAiDebugRun = { },
                        onClickExit = {
                            if (!PrefManager.warnBeforeExit) {
                                PluviaApp.events.emit(AndroidEvent.EndProcess)
                            } else if (exitSnackbarVisible) {
                                PluviaApp.events.emit(AndroidEvent.EndProcess)
                            } else {
                                exitSnackbarVisible = true
                                SnackbarManager.show(context.getString(R.string.back_press_exit_warning))
                            }
                        },
                        onChat = {
                            navController.navigate(PluviaScreen.Chat.route(it))
                        },
                        onNavigateRoute = {
                            navController.navigate(it)
                        },
                        onLogout = {
                            SteamService.logOut()
                        },
                        onGoOnline = {
                            navController.navigate(
                                if (!SteamService.isLoggedIn) PluviaScreen.LoginUser.route
                                else PluviaScreen.Home.route
                            )
                        },
                        isOffline = isOffline,
                        isSteamConnected = state.isSteamConnected,
                    )
                }

                /** Settings **/
                composable(route = PluviaScreen.Settings.route) {
                    SettingsScreen(
                        appTheme = state.appTheme,
                        paletteStyle = state.paletteStyle,
                        onAppTheme = viewModel::setTheme,
                        onPaletteStyle = viewModel::setPalette,
                        onBack = { navController.navigateUp() },
                    )
                }
            }

            if (snackbarController.rootOwnsHost) {
                SnackbarHost(
                    hostState = snackbarController.hostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                        .padding(bottom = 16.dp),
                ) { data ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shadowElevation = 4.dp,
                        ) {
                            Text(
                                text = data.visuals.message,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
