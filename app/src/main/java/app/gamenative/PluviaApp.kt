package app.gamenative

import android.os.Build
import android.os.StrictMode
import app.gamenative.events.EventDispatcher
import timber.log.Timber
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

typealias NavChangedListener = androidx.navigation.NavController.OnDestinationChangedListener

@HiltAndroidApp
class PluviaApp : android.app.Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        preloadSystemLibraries()

        // Allows to find resource streams not closed within GameNative and JavaSteam
        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build(),
            )

            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }

        NetworkMonitor.init(this)

        // Init our custom crash handler.
        CrashHandler.initialize(this)

        // Init our datastore preferences.
        PrefManager.init(this)

        // Initialize GOGConstants
        app.gamenative.service.gog.GOGConstants.init(this)

        // Initialize PostHog Analytics
        com.posthog.PostHog.register("build_flavor", BuildConfig.FLAVOR)

        if (PrefManager.usageAnalyticsEnabled) {
            com.posthog.PostHog.capture(
                event = "\$set",
                properties = mapOf(
                    "\$set" to mapOf("recommendation_enabled" to PrefManager.showRecommendations),
                ),
            )
        }
    }

    companion object {
        @JvmField
        val events: EventDispatcher = EventDispatcher()
        internal var onDestinationChangedListener: NavChangedListener? = null
        private lateinit var instance: PluviaApp

        @Volatile
        var isActivityInForeground: Boolean = true

        fun shutdownEnvironment() {
        }
    }

    /**
     * Some native libraries we dlopen at runtime (libsteamclient.so via SteamBootstrap,
     * the lsfg-vk layer, etc.) depend on `libjpeg.so`, which isn't on every device's
     * dynamic linker search path. Pre-load the system copy here with RTLD_GLOBAL
     * semantics (System.load is global) so all subsequent dlopens find its symbols.
     *
     * Single place for all: runs once in Application.onCreate before any other
     * native lib is loaded by this process. Failures are non-fatal — devices that
     * don't have the file (or have it elsewhere) just fall through.
     */
    private fun preloadSystemLibraries() {
        val is64 = android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val candidates = if (is64) {
            listOf("/system/lib64/libjpeg.so", "/system/lib/libjpeg.so")
        } else {
            listOf("/system/lib/libjpeg.so", "/system/lib64/libjpeg.so")
        }
        for (path in candidates) {
            try {
                System.load(path)
                Timber.i("[PluviaApp]: Preloaded $path")
                return
            } catch (e: Throwable) {
                Timber.w(e, "[PluviaApp]: System.load($path) failed")
            }
        }
        Timber.w("[PluviaApp]: Could not preload system libjpeg.so (none of the candidate paths worked)")
    }
}