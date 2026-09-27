package app.gamenative

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color.TRANSPARENT
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.intercept.Interceptor
import coil.request.CachePolicy
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.events.AndroidEvent
import app.gamenative.service.SteamService
import app.gamenative.service.gog.GOGService
import app.gamenative.service.epic.EpicService
import app.gamenative.ui.PluviaMain
import app.gamenative.ui.enums.Orientation
import app.gamenative.ui.util.LocalSnackbarHostController
import app.gamenative.ui.util.SnackbarHostController
import app.gamenative.utils.AnimatedPngDecoder
import app.gamenative.data.GameSource
import app.gamenative.utils.LocaleHelper
import app.gamenative.ui.util.SnackbarManager
import com.posthog.PostHog
import com.skydoves.landscapist.coil.LocalCoilImageLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.EnumSet
import kotlin.math.abs
import okio.Path.Companion.toOkioPath
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        private var totalIndex = 0

        private var currentOrientationChangeValue: Int = 0
        private var availableOrientations: EnumSet<Orientation> = EnumSet.of(Orientation.UNSPECIFIED)

        fun isHeadset(context: Context): Boolean =
            context.packageManager.hasSystemFeature("android.hardware.vr.headtracking") ||
                Build.MANUFACTURER.equals("Oculus", true) ||
                Build.MANUFACTURER.equals("Meta", true) ||
                Build.MANUFACTURER.equals("Pico", true)

        fun isMetaQuest(): Boolean =
            Build.MANUFACTURER.equals("Oculus", true) ||
                Build.MANUFACTURER.equals("Meta", true) ||
                Build.BRAND.equals("oculus", true)



        @Volatile
        var wasLaunchedViaExternalIntent: Boolean = false
    }

    private val onSetSystemUi: (AndroidEvent.SetSystemUIVisibility) -> Unit = {
        desiredSystemUiVisible = it.visible
        applyImmersiveMode()
    }

    private val onSetAllowedOrientation: (AndroidEvent.SetAllowedOrientation) -> Unit = {
        // Log.d("MainActivity", "Requested allowed orientations of $it")
        availableOrientations = it.orientations
        setOrientationTo(currentOrientationChangeValue, availableOrientations)
    }

    private val onStartOrientator: (AndroidEvent.StartOrientator) -> Unit = {
        // TODO: When rotating the device on login screen:
        //  StrictMode policy violation: android.os.strictmode.LeakedClosableViolation: A resource was acquired at attached stack trace but never released. See java.io.Closeable for information on avoiding resource leaks.
        startOrientator()
    }

    private val onEndProcess: (AndroidEvent.EndProcess) -> Unit = {
        finishAndRemoveTask()
    }



    private var index = totalIndex++

    // Add a property to keep a reference to the orientation sensor listener
    private var orientationSensorListener: OrientationEventListener? = null
    private var desiredSystemUiVisible: Boolean = false

    // Cover-art image loader; held so we can drop its GPU-backed bitmap cache when
    // the library is backgrounded (e.g. while a game is running) to free memory.
    private var appImageLoader: ImageLoader? = null

    private fun releaseImageCaches() {
        appImageLoader?.memoryCache?.clear()
    }

    override fun attachBaseContext(newBase: Context) {
        // Initialize PrefManager to read language setting
        PrefManager.init(newBase)

        // Apply the saved language preference before creating the activity
        val languageCode = PrefManager.appLanguage
        val context = LocaleHelper.applyLanguage(newBase, languageCode)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Full immersive mode - transparent system bars for console-like experience
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        // Apply immersive mode based on user preference
        applyImmersiveMode()

        handleLaunchIntent(intent)

        // startOrientator() // causes memory leak since activity restarted every orientation change
        PluviaApp.events.on<AndroidEvent.SetSystemUIVisibility, Unit>(onSetSystemUi)
        PluviaApp.events.on<AndroidEvent.StartOrientator, Unit>(onStartOrientator)
        PluviaApp.events.on<AndroidEvent.SetAllowedOrientation, Unit>(onSetAllowedOrientation)
        PluviaApp.events.on<AndroidEvent.EndProcess, Unit>(onEndProcess)

        setContent {
            var hasNotificationPermission by remember { mutableStateOf(false) }
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { isGranted ->
                hasNotificationPermission = isGranted
            }

            LaunchedEffect(Unit) {
                if (!BuildConfig.MODERN_XR && !hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            val context = LocalContext.current
            val imageLoader = remember {
                val memoryCache = MemoryCache.Builder(context)
                    .maxSizePercent(0.1)
                    .strongReferencesEnabled(true)
                    .build()

                val diskCache = DiskCache.Builder()
                    .maxSizePercent(0.03)
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .build()

                // val logger = if (BuildConfig.DEBUG) DebugLogger() else null

                ImageLoader.Builder(context)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .memoryCache(memoryCache)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .diskCache(diskCache)
                    .components {
                        // serve cached images when device has no internet
                        add(Interceptor { chain ->
                            val request = if (!NetworkMonitor.hasInternet.value) {
                                chain.request.newBuilder()
                                    .networkCachePolicy(CachePolicy.DISABLED)
                                    .build()
                            } else {
                                chain.request
                            }
                            chain.proceed(request)
                        })
                        add(AnimatedPngDecoder.Factory())
                    }
                    .build()
                    .also { appImageLoader = it }
            }

            val snackbarController = remember { SnackbarHostController() }
            CompositionLocalProvider(
                LocalCoilImageLoader provides imageLoader,
                LocalSnackbarHostController provides snackbarController,
            ) {
                PluviaMain()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent, isNewIntent = true)
    }

    private fun handleLaunchIntent(intent: Intent, isNewIntent: Boolean = false) {
        // recents re-delivers the same intent with this flag — don't re-launch
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) {
            Timber.d("[IntentLaunch]: Ignoring intent re-delivered from recents")
            return
        }
        if (intent.action == Intent.ACTION_VIEW &&
            intent.data?.scheme.equals("gamenative", ignoreCase = true) &&
            intent.data?.host.equals("discord-linked", ignoreCase = true)
        ) {
            val token = intent.data?.getQueryParameter("token").orEmpty()
            val state = intent.data?.getQueryParameter("state").orEmpty()
            // Do not retain the token-bearing link as the Activity's launch intent.
            setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            val expectedNonce = PrefManager.discordOauthNonce
            if (token.isNotEmpty() && expectedNonce.isNotEmpty() && state == expectedNonce) {
                PrefManager.discordOauthNonce = ""
                PrefManager.discordRelayToken = token
                SnackbarManager.show(getString(R.string.debug_report_discord_linked))
            } else if (token.isNotEmpty()) {
                Timber.w("[IntentLaunch]: Rejecting discord-linked token with mismatched state")
            }
            return
        }
        Timber.d("[IntentLaunch]: handleLaunchIntent called with action=${intent.action}, isNewIntent=$isNewIntent")
    
    }

    override fun onDestroy() {
        // emit before super so Compose DisposableEffects (which unregister
        // listeners during super.onDestroy's lifecycle transition) still fire
        if (!isChangingConfigurations) {
            PluviaApp.events.emit(AndroidEvent.ActivityDestroyed)

            // if exit() didn't run (listener already unregistered, race, etc.)
            // force-clear so the app isn't stuck on next launch
            if (SteamService.keepAlive) {
                Timber.w("onDestroy: keepAlive still set after ActivityDestroyed — forcing cleanup")
                PluviaApp.shutdownEnvironment()
            }
        }

        super.onDestroy()

        PluviaApp.events.off<AndroidEvent.SetSystemUIVisibility, Unit>(onSetSystemUi)
        PluviaApp.events.off<AndroidEvent.StartOrientator, Unit>(onStartOrientator)
        PluviaApp.events.off<AndroidEvent.SetAllowedOrientation, Unit>(onSetAllowedOrientation)
        PluviaApp.events.off<AndroidEvent.EndProcess, Unit>(onEndProcess)

        Timber.d(
            "onDestroy - Index: %d, Connected: %b, Logged-In: %b, Changing-Config: %b",
            index,
            SteamService.isConnected,
            SteamService.isLoggedIn,
            isChangingConfigurations,
        )

        if (SteamService.isConnected && !SteamService.isLoggedIn && !isChangingConfigurations && !SteamService.keepAlive) {
            Timber.i("Stopping Steam Service")
            SteamService.stop()
        }

        if (GOGService.isRunning && !isChangingConfigurations) {
            Timber.i("Stopping GOG Service")
            GOGService.stop()
        }

        // Stop EpicService when app is destroyed (unless config change)
        if (EpicService.isRunning && !isChangingConfigurations) {
            Timber.i("Stopping EpicService - app destroyed")
            EpicService.stop()
        }
    }



    override fun onResume() {
        super.onResume()

        // Re-apply immersive mode to ensure fullscreen persists
        if (!desiredSystemUiVisible) {
            applyImmersiveMode()
        }

        // Restart GOG service if it went down
        if (GOGService.hasStoredCredentials(this) && !GOGService.isRunning) {
            Timber.i("GOG service was down on resume - restarting")
            GOGService.start(this)
        }

        // Restart EpicService if it went down and user is authenticated
        if (EpicService.hasStoredCredentials(this) &&
            !EpicService.isRunning
        ) {
            Timber.i("EpicService was down on resume - restarting")
            EpicService.start(this)
        }

        if (PrefManager.usageAnalyticsEnabled) {
            PostHog.capture(event = "app_foregrounded")
        }
    }

    override fun onPause() {
        if (PrefManager.usageAnalyticsEnabled) {
            PostHog.capture(event = "app_backgrounded")
        }
        super.onPause()
    }

    // Add cleanup when app is backgrounded
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // TRIM_MEMORY_UI_HIDDEN fires when the app's UI goes fully hidden; the higher
        // levels fire under system memory pressure. In all these cases free the
        // cover-art cache so the running game has more headroom.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            releaseImageCaches()
        }
    }

    override fun onStop() {
        super.onStop()
        orientationSensorListener?.disable()
        orientationSensorListener = null
        // enable auto-stop behavior if backgrounded
        SteamService.autoStopWhenIdle = true

        if (!isChangingConfigurations) {
            releaseImageCaches()
        }

        Timber.d(
            "onStop - Index: %d, Connected: %b, Logged-In: %b, Changing-Config: %b, Keep Alive: %b, Is Importing: %b",
            index,
            SteamService.isConnected,
            SteamService.isLoggedIn,
            isChangingConfigurations,
            SteamService.keepAlive,
            SteamService.isImporting,
        )
        // stop SteamService only if no downloads or sync are in progress
        if (!isChangingConfigurations &&
            SteamService.isConnected &&
            !SteamService.hasActiveOperations() &&
            !SteamService.isLoginInProgress &&
            !SteamService.keepAlive &&
            !SteamService.isImporting
        ) {
            Timber.i("Stopping SteamService - no active operations")
            SteamService.stop()
        }

        // Stop GOGService if running and no downloads in progress
        if (GOGService.isRunning && !isChangingConfigurations) {
            if(!GOGService.hasActiveOperations()) {
                Timber.i("Stopping GOG Service - no active operations")
                GOGService.stop()
            }
        }

        // Stop EpicService if running, unless there are active downloads or sync operations
        if (EpicService.isRunning && !isChangingConfigurations) {
            if (!EpicService.hasActiveOperations()) {
                Timber.i("Stopping EpicService - no active operations")
                EpicService.stop()
            } else {
                Timber.d("EpicService kept running - has active operations")
            }
        }
    }

    // override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    //     // Log.d("MainActivity$index", "onKeyDown($keyCode):\n$event")
    //     if (keyCode == KeyEvent.KEYCODE_BACK) {
    //         PluviaApp.events.emit(AndroidEvent.BackPressed)
    //         return true
    //     }
    //     return super.onKeyDown(keyCode, event)
    // }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Log.d("MainActivity$index", "dispatchKeyEvent(${event.keyCode}):\n$event")

        var eventDispatched = false

        // TODO: Temp'd removed this.
        //  Idealy, compose handles back presses automaticially in which we can override it in certain composables.
        //  Since LibraryScreen uses its own navigation system, this will need to be re-worked accordingly.
        if (!eventDispatched) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && SteamService.keepAlive) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    eventDispatched = true
                } else if (BuildConfig.MODERN_ANDROID && event.action == KeyEvent.ACTION_UP) {
                    // Modern only: swallow BACK UP so super.dispatchKeyEvent doesn't
                    // forward it to OnBackPressedDispatcher, which would double-fire
                    // XServerScreen's BackHandler and immediately dismiss the quick
                    // menu the DOWN event just opened.
                    // Legacy must NOT swallow UP — master relies on UP falling through
                    // so any KeyEvent consumers (controller code, etc.) see it.
                    eventDispatched = true
                }
            }
        }

        return if (!eventDispatched) super.dispatchKeyEvent(event) else true
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        // Log.d("MainActivity$index", "dispatchGenericMotionEvent(${ev?.deviceId}:${ev?.device?.name}):\n$ev")

        val eventDispatched = false

        return if (!eventDispatched) super.dispatchGenericMotionEvent(ev) else true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Log.d("MainActivity", "Requested orientation: $requestedOrientation => ${Orientation.fromActivityInfoValue(requestedOrientation)}")
    }

    private fun startOrientator() {
        // Log.d("MainActivity$index", "Orientator starting up")

        // create and register the orientation listener
        orientationSensorListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                currentOrientationChangeValue = if (orientation != ORIENTATION_UNKNOWN) {
                    orientation
                } else {
                    currentOrientationChangeValue
                }
                setOrientationTo(currentOrientationChangeValue, availableOrientations)
            }
        }

        // enable if possible
        orientationSensorListener?.takeIf { it.canDetectOrientation() }?.enable()
    }

    /**
     * Apply immersive mode for a full-screen experience.
     * Must be called in multiple lifecycle methods to ensure bars stay hidden.
     */
    private fun applyImmersiveMode() {
        if (desiredSystemUiVisible) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(true)
                window.insetsController?.show(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars(),
                )
            } else {
                @Suppress("DEPRECATION")
                run {
                    window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                }
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Use WindowInsetsController for Android 11+
            window.setDecorFitsSystemWindows(false) // TODO: look into the proper way of doing this
            window.insetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars(),
                )
                // Allow transient bars to appear on swipe from edge
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Legacy approach for older Android versions
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-apply immersive mode when window gains focus to ensure bars stay hidden
        if (hasFocus && !desiredSystemUiVisible) {
            applyImmersiveMode()
        }
    }

    private fun setOrientationTo(orientation: Int, conformTo: EnumSet<Orientation>) {
        if (isHeadset(this)) {
            if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            return
        }
        // Log.d("MainActivity$index", "Setting orientation to conform")

        // reverse direction of orientation
        val adjustedOrientation = 360 - orientation

        // if our available orientations are empty then assume unspecified
        val orientations = conformTo.ifEmpty { EnumSet.of(Orientation.UNSPECIFIED) }

        var inRange = orientations
            .filter { it.angleRanges.any { it.contains(adjustedOrientation) } }
            .toTypedArray()

        if (inRange.isEmpty()) {
            // none of the available orientations conform to the reported orientation
            // so set it to the original orientations in preparation for finding the
            // nearest conforming orientation
            inRange = orientations.toTypedArray()
        }

        // find the nearest orientation to the reported
        val distances = orientations.map {
            it to it.angleRanges.minOf { angleRange ->
                angleRange.minOf { angle ->
                    // since 0 can be represented as 360 and vice versa
                    if (adjustedOrientation == 0 || adjustedOrientation == 360) {
                        minOf(abs(angle), abs(angle - 360))
                    } else {
                        abs(angle - adjustedOrientation)
                    }
                }
            }
        }

        val nearest = distances.minBy { it.second }

        // set the requested orientation to the nearest if it is not already as long as it is nearer than what is currently set
        val currentOrientationDist = distances
            .firstOrNull { it.first.activityInfoValue == requestedOrientation }
            ?.second
            ?: Int.MAX_VALUE

        if (requestedOrientation != nearest.first.activityInfoValue && currentOrientationDist > nearest.second) {
            Timber.d(
                "$adjustedOrientation => currentOrientation(" +
                    "${Orientation.fromActivityInfoValue(requestedOrientation)}) " +
                    "!= nearestOrientation(${nearest.first}) && " +
                    "currentDistance($currentOrientationDist) > nearestDistance(${nearest.second})",
            )

            requestedOrientation = nearest.first.activityInfoValue
        }
    }
}
