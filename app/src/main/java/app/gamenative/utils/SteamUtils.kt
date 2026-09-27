package app.gamenative.utils

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.data.DepotInfo
import app.gamenative.data.LaunchInfo
import app.gamenative.data.ManifestInfo
import app.gamenative.data.SteamApp
import app.gamenative.enums.LoginResult
import `in`.dragonbra.javasteam.enums.EDepotFileFlag
import `in`.dragonbra.javasteam.types.DepotManifest
import app.gamenative.enums.Marker
import app.gamenative.enums.SpecialGameSaveMapping
import app.gamenative.enums.SteamRealm
import app.gamenative.events.SteamEvent
import app.gamenative.service.SteamService
import app.gamenative.service.SteamService.Companion.getAppDirName
import app.gamenative.service.SteamService.Companion.getAppInfoOf
import app.gamenative.ui.component.TIMEOUT_SHOW_OFFLINE_OPTION_SECONDS
import app.gamenative.workshop.compatibility.SlayTheSpireModTheSpireCompatibility
import `in`.dragonbra.javasteam.types.KeyValue
import `in`.dragonbra.javasteam.util.HardwareUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.io.path.absolutePathString
import kotlin.io.path.name
import timber.log.Timber
import okhttp3.*
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.io.path.setLastModifiedTime

object SteamUtils {
    internal data class ColdClientLaunchConfig(
        val executablePath: String,
        val exeCommandLine: String,
        val exeRunDirOverride: String? = null,
    )

    /**
     * True when a stored Steam session exists (offline-launch gate).
     * Matches GOG/Epic/Amazon AuthManager.hasStoredCredentials convention.
     */
    fun hasStoredCredentials(): Boolean =
        PrefManager.username.isNotEmpty() && PrefManager.refreshToken.isNotEmpty()

    // fall back at the same moment the banner would offer "Continue Offline".
    const val STEAM_LOGIN_AWAIT_MS: Long = TIMEOUT_SHOW_OFFLINE_OPTION_SECONDS * 1000L

    // disconnect listener ignores non-terminal events on purpose: SteamService.reconnect()
    // emits Disconnected(isTerminal=false) before each retry, and a wifi blip mid-login should
    // resolve via the eventual LogonEnded(Success), not bail the wait.
    suspend fun awaitSteamLogin(timeoutMs: Long = STEAM_LOGIN_AWAIT_MS): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val onLogon: (SteamEvent.LogonEnded) -> Unit = { e ->
            when (e.loginResult) {
                LoginResult.Success -> deferred.complete(true)
                LoginResult.Failed -> deferred.complete(false)
                else -> Unit
            }
        }
        val onDisconnect: (SteamEvent.Disconnected) -> Unit = { e ->
            if (e.isTerminal) deferred.complete(false)
        }
        PluviaApp.events.on<SteamEvent.LogonEnded, Unit>(onLogon)
        PluviaApp.events.on<SteamEvent.Disconnected, Unit>(onDisconnect)
        try {
            // register-then-check: a flag check before registration would race events
            // fired in the gap.
            if (SteamService.isLoggedIn) return true
            return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            PluviaApp.events.off<SteamEvent.LogonEnded, Unit>(onLogon)
            PluviaApp.events.off<SteamEvent.Disconnected, Unit>(onDisconnect)
        }
    }

    fun getDownloadBytes(manifest: ManifestInfo?): Long {
        if (manifest == null) return 0L
        // Cap DownloadSize to installSize due to incorrectly gigantic sizes
        // DL size should always be smaller than installSize.
        val hasSaneDownload = manifest.download > 0L && manifest.download <= manifest.size
        return if (hasSaneDownload) manifest.download else manifest.size
    }

    /**
     * The language [depots] should be filtered by: the requested one when the app ships an
     * installable depot in it, otherwise English, otherwise any language it ships. Used for both the
     * base game and its DLC so a title that omits the container's language still resolves instead of
     * yielding zero depots. Untagged (neutral) depots pass the language filter regardless.
     */
    fun effectiveDepotLanguage(
        depots: Map<Int, DepotInfo>,
        preferredLanguage: String,
        ownedDlc: Map<Int, DepotInfo>?,
        licensedDepotIds: Set<Int>?,
        hasSteamUnlockedBranch: Boolean = false,
    ): String {
        // A depot installs once its language is chosen if it passes every check except language
        // and arch. Arch is left out because it is a per-language preference, not a gate.
        fun DepotInfo.installableInItsLanguage(): Boolean {
            val isDlc = dlcAppId != SteamService.INVALID_APP_ID
            val hasContent = manifests.isNotEmpty() || sharedInstall ||
                (hasSteamUnlockedBranch && encryptedManifests.isNotEmpty())
            val ownedIfDlc = !isDlc || ownedDlc == null || ownedDlc.containsKey(depotId)
            val licensedIfBaseGame = isDlc || systemDefined ||
                licensedDepotIds == null || depotId in licensedDepotIds
            // Mirror the SteamChina realm gate in filterForDownloadableDepots, or we could pick a
            // language only the final pass drops and lose the real fallback.
            return isWindowsCompatible && realm != SteamRealm.SteamChina &&
                hasContent && ownedIfDlc && licensedIfBaseGame
        }

        // Base-game depots only, so an owned in-app DLC's language can't steer the base game.
        val installableBaseGameDepots = depots.values
            .filter { it.dlcAppId == SteamService.INVALID_APP_ID && it.installableInItsLanguage() }
        val availableLanguages = installableBaseGameDepots
            .filter { it.language.isNotEmpty() }
            .mapTo(mutableSetOf()) { it.language }
        val hasNeutralDepot = installableBaseGameDepots.any { it.language.isEmpty() }
        return when {
            preferredLanguage in availableLanguages -> preferredLanguage
            hasNeutralDepot -> preferredLanguage
            "english" in availableLanguages -> "english"
            else -> availableLanguages.firstOrNull() ?: preferredLanguage
        }
    }

    fun getBaseAchievementIconUrl(appId: Int): String = "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/$appId/"

    /**
     * Steam achievement-schema language name for the app's current UI locale. Steam's names are the
     * lowercase English name of the language (german, french, ukrainian, romanian, …) apart from a
     * few proprietary ones, so we special-case those and derive the rest. A name the schema doesn't
     * carry falls back to English per-achievement when it is read.
     */
    fun steamLanguageForAppLocale(locale: Locale = Locale.getDefault()): String {
        return when (locale.language) {
            "ko" -> "koreana"
            // Steam splits Spanish into Castilian ("spanish") and Latin American ("latam").
            "es" -> if (locale.country.isNotEmpty() && !locale.country.equals("ES", true)) "latam" else "spanish"
            "pt" -> if (locale.country.equals("BR", true)) "brazilian" else "portuguese"
            "zh" -> if (locale.country.equals("TW", true) || locale.country.equals("HK", true) ||
                locale.country.equals("MO", true) || locale.script.equals("Hant", true)
            ) {
                "tchinese"
            } else {
                "schinese"
            }
            // substringBefore drops variant suffixes like "Norwegian Bokmål" -> "norwegian".
            else -> locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ENGLISH).substringBefore(' ')
        }
    }

    internal val http = Net.http.newBuilder()
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val sfd by lazy {
        SimpleDateFormat("MMM d - h:mm a", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }
    }

    /**
     * Converts steam time to actual time
     * @return a string in the 'MMM d - h:mm a' format.
     */
    // Note: Mostly correct, has a slight skew when near another minute
    fun fromSteamTime(rtime: Int): String = sfd.format(rtime * 1000L)

    /**
     * Converts steam time from the playtime of a friend into an approximate double representing hours.
     * @return A string representing how many hours were played, ie: 1.5 hrs
     */
    fun formatPlayTime(time: Int): String {
        val hours = time / 60.0
        return if (hours % 1 == 0.0) {
            hours.toInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", time / 60.0)
        }
    }

    // Steam strips all non-ASCII characters from usernames and passwords
    // source: https://github.com/steevp/UpdogFarmer/blob/8f2d185c7260bc2d2c92d66b81f565188f2c1a0e/app/src/main/java/com/steevsapps/idledaddy/LoginActivity.java#L166C9-L168C104
    // more: https://github.com/winauth/winauth/issues/368#issuecomment-224631002
    /**
     * Strips non-ASCII characters from String
     */
    fun removeSpecialChars(s: String): String = s.replace(Regex("[^\\u0000-\\u007F]"), "")

    /**
     * Deletes stale DRM backup files (renamed originals/unpacked executables, steam_api.dll.orig)
     * left behind in an app directory so an update/verify pass starts clean.
     */
    fun clearStaleDrmBackups(appDirPath: String) {
        val root = File(appDirPath)
        if (!root.exists()) return
        var deleted = 0
        root.walkTopDown().maxDepth(10).forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name
            val isBackup = name.endsWith(".original.exe", ignoreCase = true) ||
                name.endsWith(".unpacked.exe", ignoreCase = true) ||
                (name.startsWith("steam_api", ignoreCase = true) && name.endsWith(".dll.orig", ignoreCase = true))
            if (isBackup && file.delete()) deleted++
        }
        if (deleted > 0) {
            Timber.i("Deleted $deleted stale DRM backup file(s) in $appDirPath")
        }
    }

    fun getMachineName(context: Context): String {
        return try {
            // Try different methods to get device name
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                ?: Settings.System.getString(context.contentResolver, "device_name")
                ?: HardwareUtils.getMachineName() // Fallback to machine name if all else fails
        } catch (e: Exception) {
            HardwareUtils.getMachineName() // Return machine name as last resort
        }
    }

    // Set LoginID to a non-zero value if you have another client connected using the same account,
    // the same private ip, and same public ip.
    // source: https://github.com/Longi94/JavaSteam/blob/08690d0aab254b44b0072ed8a4db2f86d757109b/javasteam-samples/src/main/java/in/dragonbra/javasteamsamples/_000_authentication/SampleLogonAuthentication.java#L146C13-L147C56
    /**
     * This ID is unique to the device and app combination
     */
    @SuppressLint("HardwareIds")
    fun getUniqueDeviceId(context: Context): Int {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

        return androidId.hashCode()
    }

    fun getSteamId64(): Long? {
        return SteamService.userSteamId?.convertToUInt64()?.toLong()
            ?: PrefManager.steamUserSteamId64.takeIf { it != 0L }
    }

    fun getSteam3AccountId(): Long? {
        return SteamService.userSteamId?.accountID?.toLong()
            ?: PrefManager.steamUserAccountId.takeIf { it != 0 }?.toLong()
    }

}
