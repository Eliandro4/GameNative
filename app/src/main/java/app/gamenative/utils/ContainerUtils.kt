package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService

/**
 * Parses the app's "<SOURCE>_<id>(<n>)" install-id convention (the same ids used across the
 * library, downloads and storage code) and resolves display names for them. Independent of any
 * runtime/container concept — kept under this name only because that convention predates it.
 */
object ContainerUtils {

    fun extractGameIdFromContainerId(containerId: String): Int {
        // Remove duplicate suffix like (1), (2) if present
        val idWithoutSuffix = if (containerId.contains("(")) {
            containerId.substringBefore("(")
        } else {
            containerId
        }

        // Split by underscores and find the last numeric part
        val parts = idWithoutSuffix.split("_")
        // The last part should be the numeric ID
        val lastPart = parts.lastOrNull() ?: throw IllegalArgumentException("Invalid container ID format: $containerId")

        return try {
            lastPart.toInt()
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Invalid container ID format: $containerId", e)
        }
    }

    fun extractGameSourceFromContainerId(containerId: String): GameSource {
        return when {
            containerId.startsWith("STEAM_") -> GameSource.STEAM
            containerId.startsWith("CUSTOM_GAME_") -> GameSource.CUSTOM_GAME
            containerId.startsWith("GOG_") -> GameSource.GOG
            containerId.startsWith("EPIC_") -> GameSource.EPIC
            containerId.startsWith("AMAZON_") -> GameSource.AMAZON
            // Add other platforms here..
            else -> GameSource.STEAM // default fallback
        }
    }

    /**
     * No per-game runtime config exists to read this preference from any more, so cloud sync is
     * never treated as blocked.
     */
    fun isLocalSavesOnly(context: android.content.Context, appId: String): Boolean = false

    fun supportsKnownConfigAutoApply(gameSource: GameSource): Boolean = when (gameSource) {
        GameSource.STEAM,
        GameSource.GOG,
        GameSource.EPIC,
        GameSource.AMAZON,
        GameSource.CUSTOM_GAME,
        -> true
    }

    fun resolveGameName(containerId: String): String {
        val gameSource = extractGameSourceFromContainerId(containerId)
        val gameId = extractGameIdFromContainerId(containerId)
        return when (gameSource) {
            GameSource.STEAM -> SteamService.getAppInfoOf(gameId)?.name
            GameSource.GOG -> GOGService.getGOGGameOf(gameId.toString())?.title
            GameSource.EPIC -> EpicService.getEpicGameOf(gameId)?.title
            GameSource.AMAZON -> AmazonService.getAmazonGameByAppId(gameId)?.title
            GameSource.CUSTOM_GAME -> null
        } ?: "Unknown"
    }

    fun isAbsoluteWindowsPath(path: String): Boolean =
        Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(path)
}
