package app.gamenative.enums

import timber.log.Timber

/**
 * Steam/GOG's own save-location vocabulary (the "root" field of a UFS save-file pattern, or a
 * GOG cloud-save location) — a classification tag independent of any runtime/container concept.
 * Resolving one of these to an actual on-disk path requires a running game's environment, which
 * this app never creates; only the classification and string-parsing helpers survive here.
 */
enum class PathType {
    GameInstall,
    SteamUserData,
    WinMyDocuments,
    WinAppDataLocal,
    WinAppDataLocalLow,
    WinAppDataRoaming,
    WinSavedGames,
    WinProgramData,
    LinuxHome,
    LinuxXdgDataHome,
    LinuxXdgConfigHome,
    MacHome,
    MacAppSupport,
    None,
    Root,
    ;

    val isWindows: Boolean
        get() = when (this) {
            GameInstall,
            SteamUserData,
            WinMyDocuments,
            WinAppDataLocal,
            WinAppDataLocalLow,
            WinAppDataRoaming,
            WinSavedGames,
            WinProgramData,
            Root,
            -> true
            else -> false
        }

    companion object {
        val DEFAULT = SteamUserData

        /**
         * Resolve GOG path variables (<?VARIABLE?>) to Windows environment variables
         * Converts GOG-specific variables like <?INSTALL?> to actual paths or Windows env vars
         * @param location Path template with GOG variables (e.g., "<?INSTALL?>/saves")
         * @param installPath Game install path (for <?INSTALL?> variable)
         * @return Path with GOG variables resolved (may still contain Windows env vars like %LOCALAPPDATA%)
         */
        fun resolveGOGPathVariables(location: String, installPath: String): String {
            var resolved = location

            // Map of GOG variables to their values
            val variableMap = mapOf(
                "INSTALL" to installPath,
                "SAVED_GAMES" to "%USERPROFILE%/Saved Games",
                "APPLICATION_DATA_LOCAL" to "%LOCALAPPDATA%",
                "APPLICATION_DATA_LOCAL_LOW" to "%APPDATA%\\..\\LocalLow",
                "APPLICATION_DATA_ROAMING" to "%APPDATA%",
                "DOCUMENTS" to "%USERPROFILE%\\Documents"
            )

            // Find and replace <?VARIABLE?> patterns
            val pattern = Regex("<\\?(\\w+)\\?>")
            val matches = pattern.findAll(resolved)

            for (match in matches) {
                val variableName = match.groupValues[1]
                val replacement = variableMap[variableName]
                if (replacement != null) {
                    resolved = resolved.replace(match.value, replacement)
                    Timber.d("Resolved GOG variable <?$variableName?> to $replacement")
                } else {
                    Timber.w("Unknown GOG path variable: <?$variableName?>, leaving as-is")
                }
            }

            return resolved
        }

        fun from(keyValue: String?): PathType {
            return when (keyValue?.lowercase()) {
                "%${GameInstall.name.lowercase()}%",
                GameInstall.name.lowercase(),
                -> GameInstall
                "%${SteamUserData.name.lowercase()}%",
                SteamUserData.name.lowercase(),
                "steamuserbasestorage",
                "%steamuserbasestorage%",
                -> SteamUserData
                "%${WinMyDocuments.name.lowercase()}%",
                WinMyDocuments.name.lowercase(),
                "steamclouddocuments",
                "%steamclouddocuments%",
                -> WinMyDocuments
                "%${WinAppDataLocal.name.lowercase()}%",
                WinAppDataLocal.name.lowercase(),
                -> WinAppDataLocal
                "%${WinAppDataLocalLow.name.lowercase()}%",
                WinAppDataLocalLow.name.lowercase(),
                -> WinAppDataLocalLow
                "%${WinAppDataRoaming.name.lowercase()}%",
                WinAppDataRoaming.name.lowercase(),
                -> WinAppDataRoaming
                "%${WinSavedGames.name.lowercase()}%",
                WinSavedGames.name.lowercase(),
                -> WinSavedGames
                "%${WinProgramData.name.lowercase()}%",
                WinProgramData.name.lowercase(),
                -> WinProgramData
                "%${LinuxHome.name.lowercase()}%",
                LinuxHome.name.lowercase(),
                -> LinuxHome
                "%${LinuxXdgDataHome.name.lowercase()}%",
                LinuxXdgDataHome.name.lowercase(),
                -> LinuxXdgDataHome
                "%${LinuxXdgConfigHome.name.lowercase()}%",
                LinuxXdgConfigHome.name.lowercase(),
                -> LinuxXdgConfigHome
                "%${MacHome.name.lowercase()}%",
                MacHome.name.lowercase(),
                -> MacHome
                "%${MacAppSupport.name.lowercase()}%",
                MacAppSupport.name.lowercase(),
                -> MacAppSupport
                "%${Root.name.lowercase()}%",
                Root.name.lowercase(),
                "windowshome",
                "%windowshome%",
                "%root_mod%",
                "root_mod",
                -> Root
                else -> {
                    if (keyValue != null) {
                        Timber.w("Could not identify $keyValue as PathType")
                    }
                    None
                }
            }
        }
    }
}
