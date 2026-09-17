package com.smartisan.music.ui.shell

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.smartisan.music.ExternalAudioLaunchRequest
import com.smartisan.music.LocalMusicAppContainer
import com.smartisan.music.R
import com.smartisan.music.data.favorite.FavoriteSongRecord
import com.smartisan.music.data.favorite.FavoriteSongsRepository
import com.smartisan.music.data.favorite.LovedSongsCloudSync
import com.smartisan.music.data.library.LibraryExclusions
import com.smartisan.music.data.library.LibraryExclusionsStore
import com.smartisan.music.data.online.OnlineMediaIdPrefix
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.data.playback.PlaybackStatsRepository
import com.smartisan.music.data.playlist.PlaylistOnlineItemResolver
import com.smartisan.music.data.playlist.PlaylistRepository
import com.smartisan.music.data.playlist.UserPlaylistSummary
import com.smartisan.music.data.settings.ArtistSettings
import com.smartisan.music.data.settings.ArtistSettingsStore
import com.smartisan.music.data.settings.LibraryDisplaySettings
import com.smartisan.music.data.settings.LibraryDisplaySettingsStore
import com.smartisan.music.data.settings.NavigationSettings
import com.smartisan.music.data.settings.NavigationSettingsStore
import com.smartisan.music.data.settings.PlaybackSettings
import com.smartisan.music.data.settings.PlaybackSettingsStore
import com.smartisan.music.data.settings.ThemeMode
import com.smartisan.music.data.settings.ThemeSettingsStore
import com.smartisan.music.playback.LocalPlaybackController
import com.smartisan.music.playback.ProvidePlaybackController
import com.smartisan.music.playback.artworkRequestKey
import com.smartisan.music.playback.withPlaybackRating
import com.smartisan.music.ui.album.AlbumViewMode
import com.smartisan.music.ui.artist.parentTarget
import com.smartisan.music.ui.components.rememberMediaStoreDeleteCoordinator
import com.smartisan.music.ui.library.rememberLibraryMediaState
import com.smartisan.music.ui.listentogether.ListenTogetherSessionWiring
import com.smartisan.music.ui.navigation.MusicDestination
import com.smartisan.music.ui.shell.playback.PlaybackBarHost
import com.smartisan.music.ui.shell.playback.loadArtworkBitmap
import com.smartisan.music.ui.shell.playback.peekArtworkBitmap
import com.smartisan.music.ui.shell.playback.rememberPlaybackBarHost
import kotlinx.coroutines.CoroutineScope

@Composable
fun MusicAppShell(
    modifier: Modifier = Modifier,
    playbackLaunchRequest: Int = 0,
    externalAudioLaunchRequest: ExternalAudioLaunchRequest? = null,
    onExternalAudioLaunchConsumed: (Int) -> Unit = {},
    onStartupReady: () -> Unit = {},
    onThemeModeChange: (ThemeMode) -> Unit = {},
) {
    ProvidePlaybackController {
        MusicAppShellContent(
            playbackLaunchRequest = playbackLaunchRequest,
            externalAudioLaunchRequest = externalAudioLaunchRequest,
            onExternalAudioLaunchConsumed = onExternalAudioLaunchConsumed,
            onStartupReady = onStartupReady,
            onThemeModeChange = onThemeModeChange,
            modifier = modifier,
        )
    }
}

@Composable
private fun MusicAppShellContent(
    playbackLaunchRequest: Int,
    externalAudioLaunchRequest: ExternalAudioLaunchRequest?,
    onExternalAudioLaunchConsumed: (Int) -> Unit,
    onStartupReady: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val controller = LocalPlaybackController.current
    val scope = rememberCoroutineScope()
    // 目的地、编辑态与待确认操作这些临时界面状态的唯一来源，见 MusicShellViewModel。
    val uiState: MusicShellViewModel = viewModel()
    val favoriteRepository =
        remember(appContext) { FavoriteSongsRepository.getInstance(appContext) }
    // 数据层对象统一由应用级容器持有，各页共享同一实例（见 MusicAppContainer）。
    val container = LocalMusicAppContainer.current
    val onlineRepositoryRouter = container.onlineRepositoryRouter
    val lovedSongsCloudSync =
        remember(appContext) { LovedSongsCloudSync.getInstance(appContext) }
    val playlistRepository =
        remember(appContext) { PlaylistRepository.getInstance(appContext) }
    val playbackStatsRepository =
        remember(appContext) { PlaybackStatsRepository.getInstance(appContext) }
    val libraryExclusionsStore = container.libraryExclusionsStore
    val playbackSettingsStore = container.playbackSettingsStore
    val artistSettingsStore = container.artistSettingsStore
    val libraryDisplaySettingsStore = container.libraryDisplaySettingsStore
    val navigationSettingsStore = container.navigationSettingsStore
    val collected = rememberShellCollectedState(container, favoriteRepository, playlistRepository)
    val onlineLovedMediaItems = remember { mutableStateOf(emptyList<MediaItem>()) }
    val onlineLovedRefreshVersion = remember { mutableStateOf(0) }
    val playlistOnlineItemResolver =
        remember(appContext) { PlaylistOnlineItemResolver.getInstance(appContext) }
    val onlinePlaylistMediaItems = remember { mutableStateOf(emptyList<MediaItem>()) }
    val onlinePlaylistMediaIds by
        remember(playlistRepository) {
            playlistRepository.observeMediaIdsWithPrefix(OnlineMediaIdPrefix)
        }.collectAsState(initial = emptyList())
    val navigationLayoutInitialized = remember { mutableStateOf(false) }
    val navigationStateRestored = remember { mutableStateOf(false) }

    val navigationLayout = collected.navigationSettings.layout
    val bottomDestinations =
        remember(navigationLayout, uiState.playlistAddModeActive) {
            navigationLayout.bottomDestinationsEnsuring(
                MusicDestination.Songs.takeIf { uiState.playlistAddModeActive }
            )
        }
    val overflowDestinations = navigationLayout.overflowDestinations

    val libraryRefreshVersion = remember { mutableStateOf(0) }
    val libraryRefreshing = remember { mutableStateOf(false) }
    val playbackBar = rememberPlaybackBarHost(controller)
    val library = rememberLibraryMediaState(libraryRefreshVersion = libraryRefreshVersion.value)
    val libraryItems =
        remember(library.items, uiState.ratingOverrides) {
            library.items.withRatingOverrides(uiState.ratingOverrides)
        }
    val currentOnStartupReady by rememberUpdatedState(onStartupReady)

    val playbackBarComposed = playbackBar.composed
    val playbackBarHeight = 67.dp
    val artworkBitmap by rememberShellArtwork(appContext, playbackBar)

    val currentArtistAlbumViewMode = rememberUpdatedState(collected.artistAlbumViewMode)
    val actions =
        rememberShellActions(
            context = context,
            controller = controller,
            scope = scope,
            uiState = uiState,
            favoriteRepository = favoriteRepository,
            playlistRepository = playlistRepository,
            playbackStatsRepository = playbackStatsRepository,
            libraryExclusionsStore = libraryExclusionsStore,
            onlineRepositoryRouter = onlineRepositoryRouter,
            libraryDisplaySettingsStore = libraryDisplaySettingsStore,
            currentArtistAlbumViewMode = currentArtistAlbumViewMode,
            libraryRefreshVersion = libraryRefreshVersion,
            libraryRefreshing = libraryRefreshing,
            onlineLovedRefreshVersion = onlineLovedRefreshVersion,
        )

    ShellContentEffects(
        controller = controller,
        appContext = appContext,
        uiState = uiState,
        navigationSettingsStore = navigationSettingsStore,
        persistedNavigationSettings = collected.persistedNavigationSettings,
        navigationLayoutInitialized = navigationLayoutInitialized,
        navigationStateRestored = navigationStateRestored,
        onlineLovedRefreshVersion = onlineLovedRefreshVersion,
        onlineLovedMediaItems = onlineLovedMediaItems,
        lovedSongsCloudSync = lovedSongsCloudSync,
        playlistOnlineItemResolver = playlistOnlineItemResolver,
        onlinePlaylistMediaIds = onlinePlaylistMediaIds,
        onlinePlaylistMediaItems = onlinePlaylistMediaItems,
        favoriteIds = collected.favoriteIds,
        libraryLoaded = library.loaded,
        currentOnStartupReady = currentOnStartupReady,
        playbackLaunchRequest = playbackLaunchRequest,
        externalAudioLaunchRequest = externalAudioLaunchRequest,
        unknownSongTitle = collected.unknownSongTitle,
        onExternalAudioLaunchConsumed = onExternalAudioLaunchConsumed,
    )

    ShellBackHandler(uiState)

    ListenTogetherSessionWiring()

    val bottomNavigationHeight =
        dimensionResource(R.dimen.realtabcontent_margin_bottom) +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val realTabContentBottomMargin =
        if (playbackBarComposed) bottomNavigationHeight - 6.dp else bottomNavigationHeight
    val playbackBarOverlayHeight = if (playbackBarComposed) playbackBarHeight else 0.dp
    val hideBottomChrome =
        uiState.currentDestination == MusicDestination.More && uiState.moreSettingsPageActive

    MusicShellChrome(
        modifier = modifier,
        controller = controller,
        scope = scope,
        uiState = uiState,
        playlistRepository = playlistRepository,
        playbackSettings = collected.playbackSettings,
        searchMediaItems = libraryItems,
        hiddenMediaIds = collected.libraryExclusions.hiddenMediaIds,
        libraryRefreshVersion = libraryRefreshVersion.value,
        libraryRefreshing = libraryRefreshing.value,
        artistAlbumViewMode = collected.artistAlbumViewMode,
        artistSettings = collected.artistSettings,
        playlists = collected.playlists,
        playbackBar = playbackBar,
        favoriteIds = collected.favoriteIds,
        artworkBitmap = artworkBitmap,
        playbackBarHeight = playbackBarHeight,
        bottomDestinations = bottomDestinations,
        hideBottomChrome = hideBottomChrome,
        navigationLayout = navigationLayout,
        themeMode = collected.themeMode,
        navigationSettings = collected.navigationSettings,
        albumViewMode = collected.albumViewMode,
        libraryLoaded = library.loaded,
        onlineLovedMediaItems = onlineLovedMediaItems.value,
        onlinePlaylistMediaItems = onlinePlaylistMediaItems.value,
        favoriteRecords = collected.favoriteRecords,
        overflowDestinations = overflowDestinations,
        realTabContentBottomMargin = realTabContentBottomMargin,
        playbackBarOverlayHeight = playbackBarOverlayHeight,
        onThemeModeChange = onThemeModeChange,
        libraryDisplaySettingsStore = libraryDisplaySettingsStore,
        playbackSettingsStore = playbackSettingsStore,
        artistSettingsStore = artistSettingsStore,
        navigationSettingsStore = navigationSettingsStore,
        actions = actions,
        showLibraryTrackActions = { item ->
            uiState.showTrackActions(item, TrackActionSource.Library)
        },
    )
}

private fun List<MediaItem>.withRatingOverrides(
    overrides: Map<String, Int>
): List<MediaItem> {
    if (isEmpty() || overrides.isEmpty()) {
        return this
    }
    return map { item ->
        val score = overrides[item.mediaId] ?: return@map item
        item.withPlaybackRating(score)
    }
}
