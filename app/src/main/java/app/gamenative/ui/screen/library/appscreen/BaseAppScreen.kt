package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.FavoritesManager
import app.gamenative.data.LibraryItem
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.Achievement
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.screen.library.components.toggleFavorite
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.createPinnedShortcut
import kotlinx.coroutines.CancellationException
import kotlin.text.Charsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Abstract base class for AppScreen implementations.
 * This defines the contract that all game source-specific screens must implement.
 */

abstract class BaseAppScreen {
    companion object {
        private val installDialogStates = mutableStateMapOf<String, app.gamenative.ui.component.dialog.state.MessageDialogState>()

        fun showInstallDialog(appId: String, state: app.gamenative.ui.component.dialog.state.MessageDialogState) {
            installDialogStates[appId] = state
        }

        fun hideInstallDialog(appId: String) {
            installDialogStates.remove(appId)
        }

        fun getInstallDialogState(appId: String): app.gamenative.ui.component.dialog.state.MessageDialogState? {
            return installDialogStates[appId]
        }
    }

    /**
     * Get the game display information for rendering the UI.
     * This is called to get all the data needed for the common UI layout.
     */
    @Composable
    abstract fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem,
    ): GameDisplayInfo

    /**
     * Check if the game is installed
     */
    abstract fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean

    /**
     * Check if the game can be downloaded/installed
     */
    abstract fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean

    /**
     * Check if the game is currently downloading
     */
    abstract fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean

    /**
     * Get the current download progress (0.0 to 1.0)
     */
    abstract fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float

    /**
     * Check if there's a partial/incomplete download that can be resumed
     * Default implementation checks if progress is > 0 and < 1, but can be overridden
     * for more accurate detection (e.g., checking for marker files)
     */
    open fun hasPartialDownload(context: Context, libraryItem: LibraryItem): Boolean {
        val progress = getDownloadProgress(context, libraryItem)
        return progress > 0f && progress < 1f
    }

    /**
     * Check if a stale install record remains even though the game is not actually installed
     * (e.g. its files went missing after a storage switch). Such a record blocks reinstall
     * until it is cleaned up, so sources that can detect it should expose a delete action.
     */
    open fun hasLeftoverInstall(context: Context, libraryItem: LibraryItem): Boolean {
        return false
    }

    /**
     * Check if an update is pending (synchronous version, returns false by default)
     * Override isUpdatePendingSuspend for async checks
     */
    open fun isUpdatePending(context: Context, libraryItem: LibraryItem): Boolean {
        return false
    }

    /**
     * Check if an update is pending (suspend version for async checks)
     * Override this if you need to call suspend functions
     */
    open suspend fun isUpdatePendingSuspend(context: Context, libraryItem: LibraryItem): Boolean {
        return isUpdatePending(context, libraryItem)
    }

    /**
     * Handle the play/install button click
     */
    abstract fun onDownloadInstallClick(context: Context, libraryItem: LibraryItem, onClickPlay: (Boolean) -> Unit)

    /**
     * Handle pause/resume download click
     */
    abstract fun onPauseResumeClick(context: Context, libraryItem: LibraryItem)

    /**
     * Handle delete download click
     */
    abstract fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem)

    /**
     * Handle update click
     */
    abstract fun onUpdateClick(context: Context, libraryItem: LibraryItem)

    /**
     * Get the game name for shortcuts and dialogs
     */
    @Composable
    protected fun getGameName(context: Context, libraryItem: LibraryItem): String {
        // Use display info to get the name
        return getGameDisplayInfo(context, libraryItem).name
    }

    protected fun getGameSource(libraryItem: LibraryItem): GameSource {
        return libraryItem.gameSource
    }

    /**
     * Get the game ID for shortcuts depending on app type
     */
    protected fun getGameId(libraryItem: LibraryItem): Int {
        return libraryItem.gameId
    }

    /**
     * Get the icon URL for shortcuts (can be null)
     */
    @Composable
    protected fun getIconUrl(context: Context, libraryItem: LibraryItem): String? {
        return getGameDisplayInfo(context, libraryItem).iconUrl
    }

    /**
     * Get the file extension for exported frontend files (e.g., ".steam", ".game")
     * Must be overridden by subclasses to provide source-specific extension
     */
    abstract fun getExportFileExtension(): String

    /**
     * Get the game install path (non-composable version).
     * Returns the path to the game's installation directory, or null if not installed.
     * Must be implemented by subclasses to provide source-specific path resolution.
     */
    protected abstract fun getInstallPath(context: Context, libraryItem: LibraryItem): String?

    @Composable
    protected open fun getExportContainerOption(
        context: Context,
        libraryItem: LibraryItem,
        exportFrontendLauncher: ActivityResultLauncher<String>,
    ): AppMenuOption? {
        val gameId = getGameId(libraryItem)
        val gameName = getGameName(context, libraryItem)
        val extension = getExportFileExtension()
        return AppMenuOption(
            optionType = AppOptionMenuType.ExportFrontend,
            onClick = {
                val suggested = "${gameName}$extension"
                exportFrontendLauncher.launch(suggested)
            },
        )
    }

    protected open val supportsAchievements: Boolean = false

    /** Null when the fetch failed, so the caller can retry. Empty means the game has none. */
    protected open suspend fun fetchAchievements(libraryItem: LibraryItem): List<Achievement>? = null

    /** Changes once the storefront can answer, retrying a fetch that ran too early. */
    @Composable
    protected open fun achievementsReadyKey(): Any = Unit

    /**
     * Get Create Shortcut menu option. Subclasses can override to customize behavior.
     */
    @Composable
    protected open fun getCreateShortcutOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption? {
        val gameSource = getGameSource(libraryItem)
        val gameId = getGameId(libraryItem)
        val gameName = getGameName(context, libraryItem)
        val iconUrl = getIconUrl(context, libraryItem)

        return AppMenuOption(
            optionType = AppOptionMenuType.CreateShortcut,
            onClick = {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        createPinnedShortcut(
                            context = context,
                            gameId = gameId,
                            label = gameName,
                            gameSource = gameSource,
                            iconUrl = iconUrl,
                        )
                        SnackbarManager.show(context.getString(R.string.base_app_shortcut_created))
                    } catch (e: Exception) {
                        SnackbarManager.show(context.getString(R.string.base_app_shortcut_failed, e.message ?: ""))
                    }
                }
            },
        )
    }

    /**
     * Get source-specific menu options. Subclasses can override to add custom options.
     */
    @Composable
    protected open fun getSourceSpecificMenuOptions(
        context: Context,
        libraryItem: LibraryItem,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
        onClickPlay: (Boolean) -> Unit,
        isInstalled: Boolean,
    ): List<AppMenuOption> {
        return emptyList()
    }

    @Composable
    private fun getFavoriteOption(libraryItem: LibraryItem): AppMenuOption {
        val context = LocalContext.current
        val favorites by FavoritesManager.favorites.collectAsStateWithLifecycle()
        val isFavorite = favorites.contains(libraryItem.appId)
        return AppMenuOption(
            optionType = if (isFavorite) {
                AppOptionMenuType.RemoveFromFavorites
            } else {
                AppOptionMenuType.AddToFavorites
            },
            onClick = {
                toggleFavorite(context, libraryItem.appId, libraryItem.name)
            },
        )
    }

    @Composable
    private fun getSubmitFeedbackOption(context: Context, libraryItem: LibraryItem): AppMenuOption {
        return AppMenuOption(
            optionType = AppOptionMenuType.SubmitFeedback,
            onClick = {
                PluviaApp.events.emit(AndroidEvent.ShowGameFeedback(libraryItem.appId))
            },
        )
    }

    @Composable
    private fun getGetSupportOption(context: Context): AppMenuOption {
        return AppMenuOption(
            optionType = AppOptionMenuType.GetSupport,
            onClick = {
                val browserIntent = Intent(
                    Intent.ACTION_VIEW,
                    ("https://discord.gg/2hKv4VfZfE").toUri(),
                )
                context.startActivity(browserIntent)
            },
        )
    }

    /**
     * Get the game folder path for image fetching.
     * Override this in subclasses to provide source-specific path resolution.
     * Default implementation uses getInstallPath() if the game is installed.
     */
    protected open fun getGameFolderPathForImageFetch(context: Context, libraryItem: LibraryItem): String? {
        // Check if installed and get path
        if (isInstalled(context, libraryItem)) {
            return getInstallPath(context, libraryItem)
        }
        return null
    }

    /**
     * Hook called after images are fetched. Override in subclasses for post-processing
     * (e.g., icon extraction for Custom Games).
     */
    protected open fun onAfterFetchImages(context: Context, libraryItem: LibraryItem, gameFolderPath: String) {
        // Default: no post-processing
    }

    /**
     * Get the options menu items specific to this game source
     */
    @Composable
    fun getOptionsMenu(
        context: Context,
        libraryItem: LibraryItem,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
        onClickPlay: (Boolean) -> Unit,
        onTestGraphics: () -> Unit,
        onPlayWithDiagnostics: () -> Unit,
        onAiDebugRun: () -> Unit,
        exportFrontendLauncher: ActivityResultLauncher<String>,
    ): List<AppMenuOption> {
        val isInstalled = isInstalled(context, libraryItem)
        val menuOptions = mutableListOf<AppMenuOption>()

        if (isInstalled) {
            // Options only available when game is installed
            getCreateShortcutOption(context, libraryItem)?.let { menuOptions.add(it) }
            getExportContainerOption(context, libraryItem, exportFrontendLauncher)?.let { menuOptions.add(it) }
        }

        // Always available options
        if (!libraryItem.isRecommended) {
            menuOptions.add(getFavoriteOption(libraryItem))
        }
        menuOptions.add(getSubmitFeedbackOption(context, libraryItem))
        menuOptions.add(getGetSupportOption(context))

        // Add any source-specific options
        menuOptions.addAll(getSourceSpecificMenuOptions(context, libraryItem, onEditContainer, onBack, onClickPlay, isInstalled))

        return menuOptions
    }

    /**
     * Get the main content composable for this screen.
     * This uses the common UI layout from AppScreenContent.
     */
    @Composable
    fun Content(
        libraryItem: LibraryItem,
        onClickPlay: (Boolean) -> Unit,
        onTestGraphics: () -> Unit,
        onPlayWithDiagnostics: () -> Unit,
        onAiDebugRun: () -> Unit,
        onBack: () -> Unit,
    ) {
        val context = LocalContext.current
        val displayInfoBase = getGameDisplayInfo(context, libraryItem)
        val appId = libraryItem.appId

        // Fetch HLTB stats asynchronously (best-effort)
        var hltbStats by remember(displayInfoBase.name) {
            mutableStateOf<app.gamenative.utils.HltbService.Stats?>(null)
        }
        LaunchedEffect(displayInfoBase.name) {
            if (displayInfoBase.name.isNotBlank()) {
                hltbStats = try {
                    app.gamenative.utils.HltbService.getStats(displayInfoBase.name)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) { null }
            }
        }
        val displayInfo = displayInfoBase.copy(hltbStats = hltbStats)

        // Use composable state for values that change over time
        var isInstalledState by remember(libraryItem.appId) {
            mutableStateOf(isInstalled(context, libraryItem))
        }
        var isValidToDownloadState by remember(libraryItem.appId) {
            mutableStateOf(isValidToDownload(context, libraryItem))
        }
        var isDownloadingState by remember(libraryItem.appId) {
            mutableStateOf(isDownloading(context, libraryItem))
        }
        var downloadProgressState by remember(libraryItem.appId) {
            mutableFloatStateOf(getDownloadProgress(context, libraryItem))
        }
        var isUpdatePendingState by remember(libraryItem.appId) {
            mutableStateOf(false) // Initialize to false, will be updated in LaunchedEffect
        }

        // Calculate hasPartialDownload state
        var hasPartialDownloadState by remember(libraryItem.appId) {
            mutableStateOf(hasPartialDownload(context, libraryItem))
        }
        var hasLeftoverInstallState by remember(libraryItem.appId) {
            mutableStateOf(hasLeftoverInstall(context, libraryItem))
        }
        var achievementsState by remember(libraryItem.appId) {
            mutableStateOf<List<Achievement>?>(null)
        }

        val uiScope = rememberCoroutineScope()

        suspend fun performStateRefresh(includeUpdatePending: Boolean) {
            isInstalledState = isInstalled(context, libraryItem)
            isValidToDownloadState = isValidToDownload(context, libraryItem)
            val currentlyDownloading = isDownloading(context, libraryItem)
            isDownloadingState = currentlyDownloading
            downloadProgressState = getDownloadProgress(context, libraryItem)
            hasPartialDownloadState = hasPartialDownload(context, libraryItem)
            hasLeftoverInstallState = hasLeftoverInstall(context, libraryItem)
            if (includeUpdatePending) {
                isUpdatePendingState = try {
                    isUpdatePendingSuspend(context, libraryItem)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Update check failed for ${libraryItem.appId}")
                    isUpdatePendingState
                }
            }
        }

        fun requestStateRefresh(includeUpdatePending: Boolean) {
            uiScope.launch {
                performStateRefresh(includeUpdatePending)
            }
        }

        LaunchedEffect(libraryItem.appId) {
            performStateRefresh(true)
        }

        val achievementsReadyKey = achievementsReadyKey()
        LaunchedEffect(libraryItem.appId, achievementsReadyKey) {
            if (!supportsAchievements) return@LaunchedEffect
            // Retry so a transient error doesn't silently drop the section.
            repeat(3) { attempt ->
                val result = try {
                    fetchAchievements(libraryItem)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Failed to fetch achievements for ${getGameId(libraryItem)}")
                    null
                }
                if (result != null) {
                    achievementsState = result
                    return@LaunchedEffect
                }
                if (attempt < 2) delay(2000)
            }
        }

        val onEditContainer: () -> Unit = {}

        // Export for Frontend launcher
        val exportFrontendLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
            onResult = { uri ->
                if (uri != null) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            val content = getGameId(libraryItem).toString()
                            outputStream.write(content.toByteArray(Charsets.UTF_8))
                            outputStream.flush()
                        }
                        SnackbarManager.show(context.getString(R.string.base_app_exported))
                    } catch (e: Exception) {
                        SnackbarManager.show(context.getString(R.string.base_app_export_failed, e.message ?: ""))
                    }
                } else {
                    SnackbarManager.show(context.getString(R.string.base_app_export_cancelled))
                }
            },
        )

        val optionsMenu = getOptionsMenu(context, libraryItem, onEditContainer, onBack, onClickPlay, onTestGraphics, onPlayWithDiagnostics, onAiDebugRun, exportFrontendLauncher)

        // Get download info based on game source for progress tracking
        val downloadInfo = when (libraryItem.gameSource) {
            app.gamenative.data.GameSource.STEAM -> app.gamenative.service.SteamService.getAppDownloadInfo(displayInfo.gameId)
            app.gamenative.data.GameSource.EPIC -> app.gamenative.service.epic.EpicService.getDownloadInfo(displayInfo.gameId)
            app.gamenative.data.GameSource.GOG -> app.gamenative.service.gog.GOGService.getDownloadInfo(displayInfo.gameId.toString())
            app.gamenative.data.GameSource.CUSTOM_GAME -> null // Custom games don't support downloads yet
            app.gamenative.data.GameSource.AMAZON -> app.gamenative.service.amazon.AmazonService.getDownloadInfoByAppId(libraryItem.gameId)
        }

        DisposableEffect(libraryItem.appId) {
            val dispose = observeGameState(
                context = context,
                libraryItem = libraryItem,
                onStateChanged = { requestStateRefresh(true) },
                onProgressChanged = { progress ->
                    uiScope.launch {
                        downloadProgressState = progress
                    }
                },
                onHasPartialDownloadChanged = { hasPartial ->
                    hasPartialDownloadState = hasPartial
                },
            )
            onDispose {
                dispose?.invoke()
            }
        }

        // Render the common UI
        app.gamenative.ui.screen.library.AppScreenContent(
            displayInfo = displayInfo,
            downloadDisplayDetails = app.gamenative.ui.data.DownloadDisplayDetails(
                isInstalled = isInstalledState,
                isValidToDownload = isValidToDownloadState,
                isDownloading = isDownloadingState,
                downloadProgress = downloadProgressState,
                hasPartialDownload = hasPartialDownloadState,
                hasLeftoverInstall = hasLeftoverInstallState,
                isUpdatePending = isUpdatePendingState,
            ),
            downloadInfo = downloadInfo,
            onDownloadInstallClick = {
                onDownloadInstallClick(context, libraryItem, onClickPlay)
                uiScope.launch {
                    delay(100)
                    performStateRefresh(true)
                }
            },
            onPauseResumeClick = {
                isDownloadingState = !isDownloadingState
                onPauseResumeClick(context, libraryItem)
            },
            onDeleteDownloadClick = {
                onDeleteDownloadClick(context, libraryItem)
            },
            onUpdateClick = {
                onUpdateClick(context, libraryItem)
                uiScope.launch {
                    performStateRefresh(true)
                }
            },
            onBack = onBack,
            achievements = achievementsState,
            optionsMenu = optionsMenu,
        )

        // Render any additional dialogs
        AdditionalDialogs(libraryItem, onDismiss = {}, onEditContainer = onEditContainer, onBack = onBack)
    }

    /**
     * Observe download/install state changes for this app.
     * Return a lambda that will be invoked to clean up observers.
     */
    protected open fun observeGameState(
        context: Context,
        libraryItem: LibraryItem,
        onStateChanged: () -> Unit,
        onProgressChanged: (Float) -> Unit,
        onHasPartialDownloadChanged: ((Boolean) -> Unit)? = null,
    ): (() -> Unit)? {
        return null
    }

    /**
     * Get additional dialogs to show (e.g., loading, message dialogs).
     * Override this to add source-specific dialogs.
     */
    @Composable
    open fun AdditionalDialogs(
        libraryItem: LibraryItem,
        onDismiss: () -> Unit,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
    ) {
        // Default: no additional dialogs
    }
}
