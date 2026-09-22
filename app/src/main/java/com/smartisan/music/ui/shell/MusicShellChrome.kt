package com.smartisan.music.ui.shell

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.smartisan.music.R
import com.smartisan.music.data.favorite.FavoriteSongRecord
import com.smartisan.music.data.playlist.PlaylistRepository
import com.smartisan.music.data.playlist.UserPlaylistSummary
import com.smartisan.music.data.settings.ArtistSettings
import com.smartisan.music.data.settings.ArtistSettingsStore
import com.smartisan.music.data.settings.LibraryDisplaySettingsStore
import com.smartisan.music.data.settings.NavigationSettings
import com.smartisan.music.data.settings.NavigationSettingsStore
import com.smartisan.music.data.settings.PlaybackSettings
import com.smartisan.music.data.settings.PlaybackSettingsStore
import com.smartisan.music.data.settings.ThemeMode
import com.smartisan.music.ui.album.AlbumViewMode
import com.smartisan.music.ui.navigation.MusicDestination
import com.smartisan.music.ui.navigation.NavigationLayout
import com.smartisan.music.ui.shell.dialogs.AlbumDeleteConfirmOverlay
import com.smartisan.music.ui.shell.dialogs.SongDeleteConfirmOverlay
import com.smartisan.music.ui.shell.playback.PlaybackBarHost
import com.smartisan.music.ui.shell.tabs.MusicBottomChrome
import com.smartisan.music.ui.shell.tabs.MusicDestinationSurface
import com.smartisan.music.ui.shell.tabs.NavigationEditorOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 主壳的渲染层：背景 + 目的地栈 + 底栏 + 各整页覆盖层。
 *
 * 从 `MusicAppShellContent` 的 Box 原样搬来：组合顺序、`zIndex` 与各 `visible` 表达式都不变；
 * 需要落库的设置写回仍由主壳注入的 Store 与 [ShellActions] 完成。
 */
@Composable
internal fun MusicShellChrome(
    modifier: Modifier,
    controller: MediaController?,
    scope: CoroutineScope,
    uiState: MusicShellViewModel,
    playlistRepository: PlaylistRepository,
    playbackSettings: PlaybackSettings,
    searchMediaItems: List<MediaItem>,
    hiddenMediaIds: Set<String>,
    libraryRefreshVersion: Int,
    libraryRefreshing: Boolean,
    artistAlbumViewMode: AlbumViewMode,
    artistSettings: ArtistSettings,
    playlists: List<UserPlaylistSummary>,
    playbackBar: PlaybackBarHost,
    favoriteIds: Set<String>,
    artworkBitmap: Bitmap?,
    playbackBarHeight: Dp,
    bottomDestinations: List<MusicDestination>,
    hideBottomChrome: Boolean,
    navigationLayout: NavigationLayout,
    themeMode: ThemeMode,
    navigationSettings: NavigationSettings,
    albumViewMode: AlbumViewMode,
    libraryLoaded: Boolean,
    onlineLovedMediaItems: List<MediaItem>,
    onlinePlaylistMediaItems: List<MediaItem>,
    favoriteRecords: List<FavoriteSongRecord>,
    overflowDestinations: List<MusicDestination>,
    realTabContentBottomMargin: Dp,
    playbackBarOverlayHeight: Dp,
    onThemeModeChange: (ThemeMode) -> Unit,
    libraryDisplaySettingsStore: LibraryDisplaySettingsStore,
    playbackSettingsStore: PlaybackSettingsStore,
    artistSettingsStore: ArtistSettingsStore,
    navigationSettingsStore: NavigationSettingsStore,
    actions: ShellActions,
    showLibraryTrackActions: (MediaItem) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        com.smartisan.music.ui.components.SmartisanDrawableBackground(
            R.drawable.account_background,
            Modifier.fillMaxSize(),
        )
        val titleContentHeight = dimensionResource(R.dimen.title_bar_height)
        val titleShadowHeight = dimensionResource(R.dimen.title_bar_shadow_height)
        val titleAreaHeight =
            WindowInsets.statusBars
                .asPaddingValues()
                .calculateTopPadding() + titleContentHeight
        val destinationSurface: @Composable (MusicDestination, Boolean) -> Unit =
            { destination, fromMore ->
                MusicDestinationSurface(
                    destination = destination,
                    fromMore = fromMore,
                    realTabContentBottomMargin = realTabContentBottomMargin,
                    hideBottomChrome = hideBottomChrome,
                    playbackBarOverlayHeight = playbackBarOverlayHeight,
                    titleAreaHeight = titleAreaHeight,
                    titleShadowHeight = titleShadowHeight,
                    overflowDestinations = overflowDestinations,
                    mediaItems = searchMediaItems,
                    onlineLovedMediaItems = onlineLovedMediaItems,
                    onlinePlaylistMediaItems = onlinePlaylistMediaItems,
                    favoriteRecords = favoriteRecords,
                    libraryLoaded = libraryLoaded,
                    uiState = uiState,
                    albumViewMode = albumViewMode,
                    artistAlbumViewMode = artistAlbumViewMode,
                    hiddenMediaIds = hiddenMediaIds,
                    libraryRefreshVersion = libraryRefreshVersion,
                    libraryRefreshing = libraryRefreshing,
                    playbackSettings = playbackSettings,
                    artistSettings = artistSettings,
                    navigationSettings = navigationSettings,
                    themeMode = themeMode,
                    onToggleAlbumViewMode = {
                        val nextMode =
                            if (albumViewMode == AlbumViewMode.List) {
                                AlbumViewMode.Tile
                            } else {
                                AlbumViewMode.List
                            }
                        scope.launch { libraryDisplaySettingsStore.setAlbumViewMode(nextMode) }
                    },
                    onToggleArtistAlbumViewMode = actions::toggleArtistAlbumViewMode,
                    onRefreshLibrary = actions::refreshLibrary,
                    onRequestAddToQueue = actions::enqueueMediaItems,
                    onScratchEnabledChange =
                        settingWriter(scope, playbackSettingsStore::setScratchEnabled),
                    onHidePlayerAxisEnabledChange =
                        settingWriter(scope, playbackSettingsStore::setHidePlayerAxisEnabled),
                    onPopcornSoundEnabledChange =
                        settingWriter(scope, playbackSettingsStore::setPopcornSoundEnabled),
                    onAudioFxEnabledChange =
                        settingWriter(scope, playbackSettingsStore::setAudioFxEnabled),
                    onAudioFxPresetChange =
                        settingWriter(scope, playbackSettingsStore::setAudioFxPreset),
                    onAudioFxCustomGainDbPointsChange =
                        settingWriter(
                            scope,
                            playbackSettingsStore::setAudioFxCustomGainDbPoints,
                        ),
                    onArtistSeparatorsChange = { separators ->
                        scope.launch {
                            artistSettingsStore.setSeparators(separators)
                        }
                        uiState.clearArtistAndSearchDetail()
                    },
                    onTabPinnedChange = { route, pinned ->
                        scope.launch {
                            navigationSettingsStore.setTabPinned(route, pinned)
                        }
                    },
                    onThemeModeChange = onThemeModeChange,
                    onMediaIdsHidden = actions::reclaimHiddenMediaIds,
                    onRequestDeleteMediaIds = actions::requestSystemDeleteMediaIds,
                    onRemoveFavoriteMediaIds = actions::removeFavoriteMediaIds,
                )
            }
        ShellDestinationStack(uiState, destinationSurface)
        ShellChromeOverlays(
            controller = controller,
            scope = scope,
            uiState = uiState,
            playlistRepository = playlistRepository,
            playbackSettings = playbackSettings,
            searchMediaItems = searchMediaItems,
            hiddenMediaIds = hiddenMediaIds,
            libraryRefreshVersion = libraryRefreshVersion,
            artistAlbumViewMode = artistAlbumViewMode,
            artistSettings = artistSettings,
            playlists = playlists,
            playbackBar = playbackBar,
            favoriteIds = favoriteIds,
            artworkBitmap = artworkBitmap,
            playbackBarHeight = playbackBarHeight,
            bottomDestinations = bottomDestinations,
            hideBottomChrome = hideBottomChrome,
            navigationLayout = navigationLayout,
            playbackSettingsStore = playbackSettingsStore,
            navigationSettingsStore = navigationSettingsStore,
            actions = actions,
            showLibraryTrackActions = showLibraryTrackActions,
        )
    }
}

/** 目的地栈：一级页面与「更多」子页的位移过渡。 */
@Composable
private fun ShellDestinationStack(
    uiState: MusicShellViewModel,
    destinationSurface: @Composable (MusicDestination, Boolean) -> Unit,
) {
    PageStackTransition(
        secondaryKey = uiState.currentDestination.takeIf { uiState.presentedFromMore },
        modifier = Modifier.fillMaxSize(),
        label = "more destination stack",
        // 加歌模式只隐藏壳栏并让页面内联标题，不能切 projectTitles：那会把标题出口换成
        // parent 并拆掉注册，恢复时导航位移已停在终值、与槽位不匹配，标题被平移出裁切框。
        // titleProjectionEnabled 才是覆盖层用的旋钮（moreSettingsPageActive 同），不拆出口。
        projectTitles = true,
        titleProjectionEnabled = !uiState.moreSettingsPageActive && !uiState.playlistAddModeActive,
        primaryContent = {
            destinationSurface(uiState.stackDestination, false)
        },
        secondaryContent = { destination ->
            destinationSurface(destination, true)
        },
    )
}

/** 底栏 + 曲目动作/播放/搜索/加歌单/删除确认/导航编辑等整页覆盖层。 */
@Composable
private fun BoxScope.ShellChromeOverlays(
    controller: MediaController?,
    scope: CoroutineScope,
    uiState: MusicShellViewModel,
    playlistRepository: PlaylistRepository,
    playbackSettings: PlaybackSettings,
    searchMediaItems: List<MediaItem>,
    hiddenMediaIds: Set<String>,
    libraryRefreshVersion: Int,
    artistAlbumViewMode: AlbumViewMode,
    artistSettings: ArtistSettings,
    playlists: List<UserPlaylistSummary>,
    playbackBar: PlaybackBarHost,
    favoriteIds: Set<String>,
    artworkBitmap: Bitmap?,
    playbackBarHeight: Dp,
    bottomDestinations: List<MusicDestination>,
    hideBottomChrome: Boolean,
    navigationLayout: NavigationLayout,
    playbackSettingsStore: PlaybackSettingsStore,
    navigationSettingsStore: NavigationSettingsStore,
    actions: ShellActions,
    showLibraryTrackActions: (MediaItem) -> Unit,
) {
    MusicBottomChrome(
        uiState = uiState,
        playbackBar = playbackBar,
        favoriteIds = favoriteIds,
        artworkBitmap = artworkBitmap,
        playbackBarHeight = playbackBarHeight,
        destinations = bottomDestinations,
        hideBottomChrome = hideBottomChrome,
        onToggleFavorite = actions::toggleFavorite,
        onPrevious = {
            controller?.seekToPrevious()
        },
        onPlayPause = {
            if (playbackBar.snapshot.isPlaybackActive) {
                controller?.pause()
            } else {
                controller?.play()
            }
        },
        onNext = {
            controller?.seekToNext()
        },
    )
    ShellTrackActionsOverlay(
        uiState = uiState,
        favoriteIds = favoriteIds,
        onAddToQueue = actions::enqueueMediaItems,
        onToggleFavorite = actions::toggleFavorite,
        onRequestDelete = uiState::requestSongDeleteConfirmation,
        modifier = Modifier.fillMaxSize().zIndex(2.4f),
    )
    ShellOverlayLayer(
        uiState = uiState,
        scope = scope,
        playlistRepository = playlistRepository,
        playbackSettings = playbackSettings,
        searchMediaItems = searchMediaItems,
        hiddenMediaIds = hiddenMediaIds,
        libraryRefreshVersion = libraryRefreshVersion,
        artistAlbumViewMode = artistAlbumViewMode,
        artistSettings = artistSettings,
        playlists = playlists,
        onRequestAddToQueue = actions::enqueueMediaItems,
        onScratchEnabledChange =
            settingWriter(scope, playbackSettingsStore::setScratchEnabled),
        onFavoriteToggle = actions::toggleFavorite,
        onLibraryTrackMoreClick = showLibraryTrackActions,
        onToggleArtistAlbumViewMode = actions::toggleArtistAlbumViewMode,
    )
    if (uiState.showSongDeleteConfirm) {
        SongDeleteConfirmOverlay(
            onDismiss = uiState::dismissSongDeleteConfirmation,
            onConfirm = { uiState.confirmSongDelete(actions::requestSystemDeleteMediaIds) },
            modifier = Modifier.fillMaxSize().zIndex(2f),
        )
    }
    if (uiState.showAlbumDeleteConfirm) {
        // 专辑页展示的分组来自这里这份曲库快照，删除目标按同一规则重算，选中的专辑与屏幕上一致。
        val unknownAlbumTitle = stringResource(R.string.unknown_album)
        val multipleArtistsTitle = stringResource(R.string.many_artist)
        AlbumDeleteConfirmOverlay(
            onDismiss = uiState::dismissAlbumDeleteConfirmation,
            onConfirm = {
                requestAlbumDelete(
                    viewModel = uiState,
                    mediaItems = searchMediaItems,
                    hiddenMediaIds = hiddenMediaIds,
                    unknownAlbumTitle = unknownAlbumTitle,
                    multipleArtistsTitle = multipleArtistsTitle,
                    artistSettings = artistSettings,
                    requestSystemDeleteMediaIds = actions::requestSystemDeleteMediaIds,
                )
            },
            modifier = Modifier.fillMaxSize().zIndex(2f),
        )
    }
    NavigationEditorOverlay(
        visible = uiState.navigationEditorVisible,
        layout = navigationLayout,
        selectedDestination = uiState.stackDestination,
        onDismissRequest = uiState::hideNavigationEditor,
        onCommit = { layout ->
            uiState.hideNavigationEditor()
            scope.launch {
                navigationSettingsStore.commitLayout(layout)
            }
        },
        modifier = Modifier.fillMaxSize().zIndex(5f),
    )
}

/**
 * 设置写回的统一形态：仍在调用方的 [CoroutineScope] 里 launch，
 * 与逐个手写 `scope.launch { store.setX(value) }` 等价，只是少掉重复。
 */
private fun <V> settingWriter(
    scope: CoroutineScope,
    write: suspend (V) -> Unit,
): (V) -> Unit = { value -> scope.launch { write(value) } }
