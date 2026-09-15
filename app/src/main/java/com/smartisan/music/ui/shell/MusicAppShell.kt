package com.smartisan.music.ui.shell

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.SessionResult
import com.smartisan.music.AppDispatchers
import com.smartisan.music.ExternalAudioLaunchRequest
import com.smartisan.music.R
import com.smartisan.music.data.favorite.FavoriteSongsRepository
import com.smartisan.music.data.favorite.LovedSongsCloudSync
import com.smartisan.music.data.library.LibraryExclusions
import com.smartisan.music.data.library.LibraryExclusionsStore
import com.smartisan.music.data.online.OnlineMusicProvider
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.data.online.onlineTrackIdentityOrNull
import com.smartisan.music.data.playback.PlaybackStatsRepository
import com.smartisan.music.data.playlist.PlaylistRepository
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
import com.smartisan.music.data.settings.restoredDestination
import com.smartisan.music.isExternalAudioLaunchItem
import com.smartisan.music.platform.media.audioMediaItemUri
import com.smartisan.music.playback.LocalPlaybackController
import com.smartisan.music.playback.ProvidePlaybackController
import com.smartisan.music.playback.artworkRequestKey
import com.smartisan.music.playback.await
import com.smartisan.music.playback.deduplicateQueueCandidates
import com.smartisan.music.playback.invalidateLibrary
import com.smartisan.music.playback.refreshLibrary
import com.smartisan.music.playback.removeMediaItemsByMediaIds
import com.smartisan.music.playback.withPlaybackRating
import com.smartisan.music.resolveExternalAudioArtist
import com.smartisan.music.resolveExternalAudioMediaStoreIds
import com.smartisan.music.ui.album.AlbumViewMode
import com.smartisan.music.ui.artist.parentTarget
import com.smartisan.music.ui.components.MediaStoreDeleteItem
import com.smartisan.music.ui.components.rememberMediaStoreDeleteCoordinator
import com.smartisan.music.ui.library.rememberLibraryMediaState
import com.smartisan.music.ui.navigation.MusicDestination
import com.smartisan.music.ui.shell.dialogs.SongDeleteConfirmOverlay
import com.smartisan.music.ui.shell.playback.rememberPlaybackBarHost
import com.smartisan.music.ui.shell.playback.loadArtworkBitmap
import com.smartisan.music.ui.shell.playback.peekArtworkBitmap
import com.smartisan.music.ui.shell.playback.toExternalAudioMediaItem
import com.smartisan.music.ui.shell.tabs.MusicBottomChrome
import com.smartisan.music.ui.shell.tabs.MusicDestinationSurface
import com.smartisan.music.ui.shell.tabs.NavigationEditorOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // 目的地、编辑态与待确认操作这些临时界面状态的唯一来源，见 MusicShellUiState。
    val uiState = remember { MusicShellUiState() }
    val favoriteRepository =
        remember(appContext) { FavoriteSongsRepository.getInstance(appContext) }
    // 与 CloudMusicHost 共用同一 Router 实例，收藏变化才能让云音乐页面读到最新「我喜欢」。
    val onlineRepositoryRouter =
        remember(appContext) { OnlineMusicRepositoryRouter.getInstance(appContext) }
    // 「我喜欢」与网易云账号的收敛策略在数据层，主壳只在进入该页时发起一次。
    val lovedSongsCloudSync =
        remember(appContext) { LovedSongsCloudSync.getInstance(appContext) }
    val playlistRepository =
        remember(appContext) { PlaylistRepository.getInstance(appContext) }
    val playbackStatsRepository =
        remember(appContext) { PlaybackStatsRepository.getInstance(appContext) }
    val libraryExclusionsStore =
        remember(appContext) { LibraryExclusionsStore(appContext) }
    val playbackSettingsStore =
        remember(appContext) { PlaybackSettingsStore(appContext) }
    val artistSettingsStore =
        remember(appContext) { ArtistSettingsStore(appContext) }
    val libraryDisplaySettingsStore =
        remember(appContext) { LibraryDisplaySettingsStore(appContext) }
    val navigationSettingsStore =
        remember(appContext) { NavigationSettingsStore(appContext) }
    val themeSettingsStore =
        remember(appContext) { ThemeSettingsStore(appContext) }
    val favoriteIds by favoriteRepository.observeFavoriteIds().collectAsState(initial = emptySet())
    val libraryExclusions by
        libraryExclusionsStore.exclusions.collectAsState(initial = LibraryExclusions())
    val playbackSettings by
        playbackSettingsStore.settings.collectAsState(initial = PlaybackSettings())
    val artistSettings by artistSettingsStore.settings.collectAsState(initial = ArtistSettings())
    val libraryDisplaySettings by
        libraryDisplaySettingsStore.settings.collectAsState(initial = LibraryDisplaySettings())
    val persistedNavigationSettings: NavigationSettings? by
        navigationSettingsStore.settings.collectAsState(initial = null)
    val navigationSettings = persistedNavigationSettings ?: NavigationSettings()
    val themeMode = remember(themeSettingsStore) { themeSettingsStore.currentMode() }
    val albumViewMode = libraryDisplaySettings.albumViewMode
    val artistAlbumViewMode = libraryDisplaySettings.artistAlbumViewMode
    val unknownSongTitle = stringResource(R.string.unknown_song_title)
    val favoriteRecords by
        favoriteRepository.observeFavorites().collectAsState(initial = emptyList())
    // 「我喜欢的歌曲」中来自网易云账号的部分，仅在进入该页时拉取。
    var onlineLovedMediaItems by remember { mutableStateOf(emptyList<MediaItem>()) }
    // 在线喜欢发生变化时自增，驱动上面的列表重新拉取。
    var onlineLovedRefreshVersion by remember { mutableStateOf(0) }
    val playlists by playlistRepository.playlists.collectAsState(initial = emptyList())
    var navigationLayoutInitialized by remember { mutableStateOf(false) }
    var navigationStateRestored by remember { mutableStateOf(false) }

    val navigationLayout = navigationSettings.layout
    // 加歌模式只临时替换底栏末位，不污染用户保存的导航布局。
    val bottomDestinations =
        remember(navigationLayout, uiState.playlistAddModeActive) {
            navigationLayout.bottomDestinationsEnsuring(
                MusicDestination.Songs.takeIf { uiState.playlistAddModeActive }
            )
        }
    val overflowDestinations = navigationLayout.overflowDestinations

    // 冷启动恢复上次一级板块；详情页和弹窗状态不进入持久化恢复范围。
    LaunchedEffect(persistedNavigationSettings, uiState.currentDestination) {
        val persistedLayout = persistedNavigationSettings?.layout ?: return@LaunchedEffect
        if (!navigationLayoutInitialized) {
            navigationLayoutInitialized = true
            val (restoredDestination, restoredFromMore) =
                persistedNavigationSettings?.restoredDestination()
                    ?: (persistedLayout.bottomDestinations.first() to false)
            uiState.currentDestination = restoredDestination
            uiState.presentedFromMore = restoredFromMore
            navigationStateRestored = true
        } else if (
            uiState.currentDestination != MusicDestination.More &&
                !persistedLayout.isPinned(uiState.currentDestination)
        ) {
            uiState.presentedFromMore = true
        }
    }

    LaunchedEffect(
        uiState.currentDestination,
        uiState.presentedFromMore,
        navigationStateRestored,
    ) {
        if (navigationStateRestored) {
            navigationSettingsStore.setLastDestination(
                uiState.currentDestination,
                uiState.presentedFromMore,
            )
        }
    }

    // 进入「我喜欢的歌曲」时与网易云账号收敛一次：策略（登录判定、差集、只补不删、失败降级）在数据层。
    LaunchedEffect(uiState.currentDestination, onlineLovedRefreshVersion) {
        onlineLovedMediaItems = lovedSongsCloudSync.mediaItemsForLovedSongsPage(
            lovedSongsPageActive = uiState.currentDestination == MusicDestination.LovedSongs,
            localFavoriteMediaIds = favoriteIds,
        )
    }
    var libraryRefreshVersion by remember { mutableStateOf(0) }
    var libraryRefreshing by remember { mutableStateOf(false) }
    val playbackBar = rememberPlaybackBarHost(controller)
    val library = rememberLibraryMediaState(libraryRefreshVersion = libraryRefreshVersion)
    val libraryItems =
        remember(library.items, uiState.ratingOverrides) {
            library.items.withRatingOverrides(uiState.ratingOverrides)
        }
    val currentOnStartupReady by rememberUpdatedState(onStartupReady)
    LaunchedEffect(navigationStateRestored, library.loaded, controller) {
        if (!navigationStateRestored || !library.loaded || controller == null) {
            return@LaunchedEffect
        }
        // 资料库状态发布后再隐藏两个布局帧，让标题栏、专辑网格和首屏封面开始绑定。
        withFrameNanos {}
        withFrameNanos {}
        currentOnStartupReady()
    }
    val playbackBarMediaItem = playbackBar.contentSnapshot.mediaItem
    val artworkRequestKey = playbackBarMediaItem?.artworkRequestKey()
    val artworkBitmap by
        produceState<Bitmap?>(
            initialValue = playbackBarMediaItem?.let(::peekArtworkBitmap),
            artworkRequestKey,
        ) {
            val mediaItem = playbackBar.contentSnapshot.mediaItem
            if (mediaItem == null) {
                value = null
                return@produceState
            }
            value = peekArtworkBitmap(mediaItem) ?: value
            value = loadArtworkBitmap(appContext, mediaItem)
        }
    val playbackBarComposed = playbackBar.composed
    val playbackBarHeight = 67.dp
    fun cleanupDeletedSongs(mediaIds: Set<String>, hideFromLibrary: Boolean) {
        if (mediaIds.isEmpty()) {
            return
        }
        controller.removeMediaItemsByMediaIds(mediaIds)
        scope.launch {
            if (hideFromLibrary) {
                libraryExclusionsStore.hideMediaIds(mediaIds)
            }
            favoriteRepository.removeAll(mediaIds)
            playlistRepository.removeMediaIdsFromAll(mediaIds)
            playbackStatsRepository.deleteByIds(mediaIds)
            runCatching {
                controller?.invalidateLibrary()?.await(context)
            }
            libraryRefreshVersion += 1
        }
    }

    fun reclaimHiddenMediaIds(mediaIds: Set<String>) {
        if (mediaIds.isEmpty()) {
            return
        }
        controller.removeMediaItemsByMediaIds(mediaIds)
    }

    fun enqueueResolvedMediaItems(items: List<MediaItem>) {
        if (items.isEmpty()) {
            return
        }
        if (controller?.repeatMode == Player.REPEAT_MODE_ONE) {
            Toast.makeText(context, R.string.can_not_add_to_queue_single_repeat, Toast.LENGTH_SHORT)
                .show()
        } else {
            val deduplicatedItems = controller?.deduplicateQueueCandidates(items).orEmpty()
            if (deduplicatedItems.isNotEmpty()) {
                controller?.addMediaItems(deduplicatedItems)
                Toast.makeText(context, R.string.add_to_queue_success, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun enqueueMediaItems(items: List<MediaItem>) {
        enqueueResolvedMediaItems(items)
    }

    /** 在线歌曲的喜欢状态回写网易云账号。本地收藏为准，同步失败不回滚本地。 */
    suspend fun syncOnlineLikedState(mediaId: String, liked: Boolean) {
        val identity = mediaId.onlineTrackIdentityOrNull() ?: return
        if (identity.source != OnlineMusicProvider.Netease.sourceId) {
            return
        }
        runCatching { onlineRepositoryRouter.setTrackLiked(identity, liked) }
        // 在线喜欢变了就重拉一次；不在「我喜欢的歌曲」页时自增不会产生请求，仅清空已有快照。
        onlineLovedRefreshVersion += 1
    }

    fun toggleFavorite(mediaItem: MediaItem) {
        if (mediaItem.isExternalAudioLaunchItem()) {
            return
        }
        val mediaId = mediaItem.mediaId.takeIf(String::isNotBlank) ?: return
        scope.launch {
            val likedNow = favoriteRepository.toggle(mediaId)
            syncOnlineLikedState(mediaId, likedNow)
        }
    }

    val showLibraryTrackActions: (MediaItem) -> Unit = { item ->
        uiState.showTrackActions(item, TrackActionSource.Library)
    }
    val toggleArtistAlbumViewMode: () -> Unit = {
        val nextMode =
            if (artistAlbumViewMode == AlbumViewMode.List) {
                AlbumViewMode.Tile
            } else {
                AlbumViewMode.List
            }
        scope.launch {
            libraryDisplaySettingsStore.setArtistAlbumViewMode(nextMode)
        }
    }

    fun removeFavoriteMediaIds(mediaIds: Set<String>) {
        if (mediaIds.isEmpty()) {
            return
        }
        scope.launch {
            if (mediaIds.size == 1) {
                favoriteRepository.remove(mediaIds.first())
            } else {
                favoriteRepository.removeAll(mediaIds)
            }
            mediaIds.forEach { mediaId -> syncOnlineLikedState(mediaId, false) }
        }
    }

    fun refreshLibrary() {
        if (libraryRefreshing) {
            return
        }
        val playbackController = controller
        if (playbackController == null) {
            Toast.makeText(context, R.string.library_refresh_failed, Toast.LENGTH_SHORT).show()
            return
        }
        libraryRefreshing = true
        scope.launch {
            val result = runCatching {
                playbackController.refreshLibrary().await(context)
            }
                .getOrNull()
            libraryRefreshing = false
            if (result?.resultCode == SessionResult.RESULT_SUCCESS) {
                libraryRefreshVersion += 1
                Toast.makeText(context, R.string.library_refresh_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.library_refresh_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val deleteCoordinator =
        rememberMediaStoreDeleteCoordinator(
            onDeleted = { mediaIds ->
                cleanupDeletedSongs(mediaIds, hideFromLibrary = false)
            },
            onNotDeleted = {
                Toast.makeText(context, R.string.playback_delete_failed, Toast.LENGTH_SHORT).show()
            },
        )

    fun requestSystemDeleteMediaIds(mediaIds: Set<String>) {
        if (mediaIds.isEmpty()) {
            return
        }
        val deleteItems = mediaIds.mapNotNull { mediaId ->
            val mediaStoreId = mediaId.trim().toLongOrNull() ?: return@mapNotNull null
            MediaStoreDeleteItem(
                mediaId = mediaId,
                uri = audioMediaItemUri(mediaStoreId),
            )
        }
        val deleteItemIds = deleteItems.mapTo(linkedSetOf(), MediaStoreDeleteItem::mediaId)
        val invalidMediaIds = mediaIds - deleteItemIds
        if (invalidMediaIds.isNotEmpty()) {
            cleanupDeletedSongs(invalidMediaIds, hideFromLibrary = true)
        }
        if (deleteItems.isEmpty()) {
            return
        }
        deleteCoordinator.delete(deleteItems)
    }

    LaunchedEffect(playbackLaunchRequest) {
        if (playbackLaunchRequest > 0) {
            uiState.playbackVisible = true
        }
    }

    LaunchedEffect(uiState.currentDestination) {
        if (uiState.currentDestination != MusicDestination.Songs) {
            uiState.songsEditMode = false
            uiState.selectedSongIds = emptySet()
            uiState.showSongDeleteConfirm = false
            uiState.pendingSongDeleteMediaIds = emptySet()
        }
        if (uiState.currentDestination != MusicDestination.Album) {
            uiState.albumEditMode = false
            uiState.selectedAlbumIds = emptySet()
            uiState.selectedAlbumId = null
            uiState.selectedAlbumTitle = null
        }
        if (uiState.currentDestination != MusicDestination.Artist) {
            uiState.selectedArtistTarget = null
        }
        if (uiState.currentDestination != MusicDestination.Playlist) {
            uiState.playlistAddModeActive = false
        }
        uiState.dismissTrackActions()
    }

    // 返回键的落点交给纯状态机仲裁（见 ShellBackNavigation），这里只做一次委托。
    val backOwner =
        ShellBackNavigationState(
            destination = uiState.currentDestination,
            presentedFromMore = uiState.presentedFromMore,
            songsEditMode = uiState.songsEditMode,
            albumEditMode = uiState.albumEditMode,
            albumDetailOpen = uiState.selectedAlbumId != null,
            artistDetailOpen = uiState.selectedArtistTarget != null,
            artistDetailHasParent = uiState.selectedArtistTarget?.parentTarget() != null,
            visibleOverlay =
                shellBackOverlay(
                    playbackVisible = uiState.playbackVisible,
                    searchVisible = uiState.searchVisible,
                    playlistPickerVisible = uiState.playlistPickerVisible,
                    navigationEditorVisible = uiState.navigationEditorVisible,
                ),
        ).resolveOwner()
    BackHandler(enabled = backOwner.consumedByShell) {
        when (backOwner) {
            ShellBackOwner.CloseAlbumDetail -> uiState.closeAlbumDetail()
            ShellBackOwner.CloseArtistDetail -> uiState.closeArtistDetail()
            ShellBackOwner.ReturnToMore -> uiState.returnToMore()
            else -> Unit
        }
    }

    LaunchedEffect(externalAudioLaunchRequest, controller) {
        val request = externalAudioLaunchRequest ?: return@LaunchedEffect
        uiState.playbackVisible = true
        val playbackController = controller ?: return@LaunchedEffect
        val (artist, mediaStoreIds) =
            withContext(AppDispatchers.IO) {
                request.resolveExternalAudioArtist(appContext) to
                    request.resolveExternalAudioMediaStoreIds(appContext)
            }
        val mediaItem =
            request.toExternalAudioMediaItem(
                fallbackTitle = unknownSongTitle,
                artist = artist,
                mediaStoreId = mediaStoreIds.mediaStoreId,
                albumId = mediaStoreIds.albumId,
            )
        playbackController.setMediaItem(mediaItem)
        playbackController.prepare()
        playbackController.play()
        onExternalAudioLaunchConsumed(request.requestId)
    }

    val bottomNavigationHeight =
        dimensionResource(R.dimen.realtabcontent_margin_bottom) +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val realTabContentBottomMargin =
        if (playbackBarComposed) {
            bottomNavigationHeight - 6.dp
        } else {
            bottomNavigationHeight
        }
    val playbackBarOverlayHeight = if (playbackBarComposed) playbackBarHeight else 0.dp
    val hideBottomChrome =
        uiState.currentDestination == MusicDestination.More && uiState.moreSettingsPageActive

    LaunchedEffect(uiState.currentDestination) {
        if (uiState.currentDestination != MusicDestination.More) {
            uiState.moreSettingsPageActive = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        com.smartisan.music.ui.components.SmartisanDrawableBackground(
            R.drawable.account_background,
            Modifier.fillMaxSize(),
        )
        val titleContentHeight = dimensionResource(R.dimen.title_bar_height)
        val titleShadowHeight = dimensionResource(R.dimen.title_bar_shadow_height)
        val titleAreaHeight =
            WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + titleContentHeight
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
                    mediaItems = libraryItems,
                    onlineLovedMediaItems = onlineLovedMediaItems,
                    favoriteRecords = favoriteRecords,
                    libraryLoaded = library.loaded,
                    uiState = uiState,
                    albumViewMode = albumViewMode,
                    artistAlbumViewMode = artistAlbumViewMode,
                    hiddenMediaIds = libraryExclusions.hiddenMediaIds,
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
                    onToggleArtistAlbumViewMode = toggleArtistAlbumViewMode,
                    onRefreshLibrary = ::refreshLibrary,
                    onRequestAddToQueue = ::enqueueMediaItems,
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
                    onMediaIdsHidden = ::reclaimHiddenMediaIds,
                    onRequestDeleteMediaIds = ::requestSystemDeleteMediaIds,
                    onRemoveFavoriteMediaIds = ::removeFavoriteMediaIds,
                )
            }
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
        MusicBottomChrome(
            uiState = uiState,
            playbackBar = playbackBar,
            favoriteIds = favoriteIds,
            artworkBitmap = artworkBitmap,
            playbackBarHeight = playbackBarHeight,
            destinations = bottomDestinations,
            hideBottomChrome = hideBottomChrome,
            onToggleFavorite = ::toggleFavorite,
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
            onAddToQueue = ::enqueueMediaItems,
            onToggleFavorite = ::toggleFavorite,
            onRequestDelete = uiState::requestSongDeleteConfirmation,
            modifier = Modifier.fillMaxSize().zIndex(2.4f),
        )
        ShellOverlayLayer(
            uiState = uiState,
            scope = scope,
            playlistRepository = playlistRepository,
            playbackSettings = playbackSettings,
            searchMediaItems = libraryItems,
            hiddenMediaIds = libraryExclusions.hiddenMediaIds,
            libraryRefreshVersion = libraryRefreshVersion,
            artistAlbumViewMode = artistAlbumViewMode,
            artistSettings = artistSettings,
            playlists = playlists,
            onRequestAddToQueue = ::enqueueMediaItems,
            onScratchEnabledChange =
                settingWriter(scope, playbackSettingsStore::setScratchEnabled),
            onFavoriteToggle = ::toggleFavorite,
            onLibraryTrackMoreClick = showLibraryTrackActions,
            onToggleArtistAlbumViewMode = toggleArtistAlbumViewMode,
        )
        if (uiState.showSongDeleteConfirm) {
            SongDeleteConfirmOverlay(
                onDismiss = uiState::dismissSongDeleteConfirmation,
                onConfirm = { uiState.confirmSongDelete(::requestSystemDeleteMediaIds) },
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

/**
 * 设置写回的统一形态：仍在调用方的 [CoroutineScope] 里 launch，
 * 与逐个手写 `scope.launch { store.setX(value) }` 等价，只是少掉重复。
 */
private fun <V> settingWriter(
    scope: CoroutineScope,
    write: suspend (V) -> Unit,
): (V) -> Unit = { value -> scope.launch { write(value) } }
