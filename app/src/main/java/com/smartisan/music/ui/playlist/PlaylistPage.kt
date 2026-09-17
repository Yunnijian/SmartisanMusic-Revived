package com.smartisan.music.ui.playlist

import androidx.annotation.StringRes
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import com.smartisan.music.R
import com.smartisan.music.data.playlist.PlaylistRepository
import com.smartisan.music.data.playlist.UserPlaylistDetail
import com.smartisan.music.playback.LocalPlaybackBrowser
import com.smartisan.music.playback.replaceQueueAndPlay
import com.smartisan.music.playback.replaceQueueAndPlayShuffled
import com.smartisan.music.ui.shell.PageStackTransition
import com.smartisan.music.ui.shell.titlebar.TitleBarShadow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private const val PlaylistAddModeSlideMillis = 300
internal const val PlaylistRootFooterThreshold = 8
private val PlaylistAddModeEasing = Easing { fraction ->
    1f - (1f - fraction) * (1f - fraction)
}

internal data class PlaylistTarget(
    val playlistId: String,
    val title: String,
)

internal data class PlaylistDetailSnapshot(
    val playlistId: String,
    val playlist: UserPlaylistDetail?,
    val title: String,
    val tracks: List<MediaItem>,
    val libraryLoading: Boolean,
)

internal sealed interface PlaylistNameDialogRequest {
    val initialName: String

    data class Create(
        override val initialName: String,
        @param:StringRes val titleRes: Int = R.string.new_playlist,
    ) : PlaylistNameDialogRequest

    data class Rename(
        val playlistId: String,
        override val initialName: String,
    ) : PlaylistNameDialogRequest
}

internal enum class PlaylistDeleteRequest {
    RootSelected,
    DetailPlaylist,
    DetailTracks,
}

@Composable
internal fun PlaylistPage(
    mediaItems: List<MediaItem>,
    libraryLoaded: Boolean,
    active: Boolean,
    hiddenMediaIds: Set<String>,
    onTrackMoreClick: (MediaItem) -> Unit,
    onAddModeActiveChanged: (Boolean) -> Unit,
    onSearchClick: () -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onlineMediaItems: List<MediaItem> = emptyList(),
) {
    val context = LocalContext.current
    val browser = LocalPlaybackBrowser.current
    val scope = rememberCoroutineScope()
    val playlistRepository =
        remember(context.applicationContext) {
            PlaylistRepository.getInstance(context.applicationContext)
        }
    val playlists by playlistRepository.playlists.collectAsState(initial = emptyList())
    val visibleSongs =
        remember(mediaItems, hiddenMediaIds) {
            mediaItems.filterNot { item -> item.mediaId in hiddenMediaIds }
        }
    // 歌单里的在线条目不在本地媒体库中，需并入同一查找表，曲目行才能解析出标题/艺人与可播条目。
    val songsById =
        remember(visibleSongs, onlineMediaItems) {
            (visibleSongs + onlineMediaItems).distinctBy(MediaItem::mediaId)
                .associateBy(MediaItem::mediaId)
        }

    var target by remember { mutableStateOf<PlaylistTarget?>(null) }
    val selection = remember { PlaylistSelectionController() }
    var nameDialogRequest by remember { mutableStateOf<PlaylistNameDialogRequest?>(null) }
    var deleteRequest by remember { mutableStateOf<PlaylistDeleteRequest?>(null) }

    val activePlaylistId = target?.playlistId
    val activePlaylistFlow =
        remember(activePlaylistId, playlistRepository) {
            activePlaylistId?.let(playlistRepository::observePlaylistDetail) ?: flowOf(null)
        }
    val activePlaylist by activePlaylistFlow.collectAsState(initial = null)
    val activeSummary =
        remember(playlists, activePlaylistId) {
            activePlaylistId?.let { id -> playlists.firstOrNull { playlist -> playlist.id == id } }
        }
    val detailTitle = activePlaylist?.name ?: activeSummary?.name ?: target?.title.orEmpty()
    val detailTracks =
        remember(activePlaylist, songsById) {
            activePlaylist?.mediaIds?.mapNotNull(songsById::get).orEmpty()
        }
    val detailPlaylistHasKnownTracks =
        activePlaylist?.mediaIds?.isNotEmpty() == true ||
            (activePlaylist == null && (activeSummary?.songCount ?: 0) > 0)
    val detailLibraryLoading = target != null && !libraryLoaded && detailPlaylistHasKnownTracks
    val addModeExistingIds =
        remember(selection.addModeTarget, activePlaylistId, activePlaylist) {
            if (selection.addModeTarget?.playlistId == activePlaylistId) {
                activePlaylist?.mediaIds?.toSet().orEmpty()
            } else {
                emptySet()
            }
        }
    var retainedDetailSnapshot by remember {
        mutableStateOf<PlaylistDetailSnapshot?>(null)
    }
    val addModeVisible = selection.addModeVisible

    fun closeAddMode() {
        if (selection.closeAddMode()) {
            target = null
        }
    }

    PlaylistPageEffects(
        active = active,
        onClose = onClose,
        target = target,
        selection = selection,
        playlists = playlists,
        activePlaylistId = activePlaylistId,
        activePlaylist = activePlaylist,
        detailTitle = detailTitle,
        detailTracks = detailTracks,
        detailLibraryLoading = detailLibraryLoading,
        addModeVisible = addModeVisible,
        onTargetChange = { next -> target = next },
        onRetainedDetailSnapshotChange = { snapshot -> retainedDetailSnapshot = snapshot },
        onAddModeActiveChanged = onAddModeActiveChanged,
        onCloseAddMode = ::closeAddMode,
    )

    val titleAreaHeight =
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
            dimensionResource(R.dimen.title_bar_height)
    val titleShadowHeight = dimensionResource(R.dimen.title_bar_shadow_height)

    Box(modifier = modifier.fillMaxSize().background(colorResource(R.color.page_background))) {
        Column(modifier = Modifier.fillMaxSize()) {
            PlaylistTitleSection(
                target = target,
                detailTitle = detailTitle,
                selection = selection,
                onRootBack = onClose,
                onDetailBack = {
                    target = null
                    selection.resetDetail()
                },
                onRequestDeleteRootSelected = {
                    deleteRequest = PlaylistDeleteRequest.RootSelected
                },
                onSearchClick = onSearchClick,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                PageStackTransition(
                    secondaryKey = target,
                    modifier = Modifier.fillMaxSize(),
                    label = "playlist transition",
                    primaryContent = {
                        PlaylistRootSection(
                            active = active,
                            playlists = playlists,
                            selection = selection,
                            onCreatePlaylist = {
                                scope.launch {
                                    nameDialogRequest =
                                        PlaylistNameDialogRequest.Create(
                                            initialName =
                                                playlistRepository.suggestNextUntitledName()
                                        )
                                }
                            },
                            onRenamePlaylist = { playlist ->
                                nameDialogRequest =
                                    PlaylistNameDialogRequest.Rename(
                                        playlistId = playlist.id,
                                        initialName = playlist.name,
                                    )
                            },
                            onOpenPlaylist = { playlist ->
                                target =
                                    PlaylistTarget(
                                        playlistId = playlist.id,
                                        title = playlist.name,
                                    )
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    secondaryContent = { playlistTarget ->
                        PlaylistDetailSection(
                            active = active,
                            target = target,
                            playlistTarget = playlistTarget,
                            activePlaylist = activePlaylist,
                            title = detailTitle,
                            tracks = detailTracks,
                            libraryLoading = detailLibraryLoading,
                            retainedDetailSnapshot = retainedDetailSnapshot,
                            selection = selection,
                            browser = browser,
                            onShuffle = { tracks ->
                                browser.replaceQueueAndPlayShuffled(tracks)
                            },
                            onDeletePlaylist = {
                                deleteRequest = PlaylistDeleteRequest.DetailPlaylist
                            },
                            onRequestDeleteTracks = {
                                deleteRequest = PlaylistDeleteRequest.DetailTracks
                            },
                            onReorderTracks = { playlistId, orderedMediaIds ->
                                scope.launch {
                                    playlistRepository.reorderVisibleMediaIds(
                                        playlistId,
                                        orderedMediaIds,
                                    )
                                }
                            },
                            onPlayTrack = { tracks, index ->
                                browser.replaceQueueAndPlay(tracks, index)
                            },
                            onTrackMoreClick = onTrackMoreClick,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                )
            }
        }
        if (!addModeVisible) {
            TitleBarShadow(
                modifier =
                    Modifier.align(Alignment.TopCenter)
                        .offset(y = titleAreaHeight)
                        .fillMaxWidth()
                        .height(titleShadowHeight)
                        .zIndex(1f)
            )
        }
        PlaylistAddModeOverlay(
            selection = selection,
            visibleSongs = visibleSongs,
            libraryLoaded = libraryLoaded,
            active = active,
            addModeExistingIds = addModeExistingIds,
            playlistRepository = playlistRepository,
            scope = scope,
            onCloseAddMode = ::closeAddMode,
        )
    }

    PlaylistPageDialogs(
        context = context,
        playlistRepository = playlistRepository,
        scope = scope,
        selection = selection,
        target = target,
        nameDialogRequest = nameDialogRequest,
        deleteRequest = deleteRequest,
        visibleSongs = visibleSongs,
        libraryLoaded = libraryLoaded,
        onNameDialogRequestChange = { request -> nameDialogRequest = request },
        onDeleteRequestChange = { request -> deleteRequest = request },
        onTargetChange = { next -> target = next },
    )
}

internal fun String.ellipsizeMiddle(maxChars: Int): String {
    if (length <= maxChars) {
        return this
    }
    return take((maxChars - 3).coerceAtLeast(1)) + "..."
}
