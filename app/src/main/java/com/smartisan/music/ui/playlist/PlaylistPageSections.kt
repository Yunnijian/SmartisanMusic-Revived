package com.smartisan.music.ui.playlist

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.smartisan.music.R
import com.smartisan.music.data.playlist.PlaylistCreateResult
import com.smartisan.music.data.playlist.PlaylistRenameResult
import com.smartisan.music.data.playlist.PlaylistRepository
import com.smartisan.music.data.playlist.UserPlaylistDetail
import com.smartisan.music.data.playlist.UserPlaylistSummary
import com.smartisan.music.ui.songs.SongsPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val PlaylistAddModeSlideMillis = 300
private val PlaylistAddModeEasing = Easing { fraction ->
    1f - (1f - fraction) * (1f - fraction)
}

/** 根列表页：进入/退出编辑、重命名与打开详情都由调用方决策。 */
@Composable
internal fun PlaylistRootSection(
    active: Boolean,
    playlists: List<UserPlaylistSummary>,
    selection: PlaylistSelectionController,
    onCreatePlaylist: () -> Unit,
    onRenamePlaylist: (UserPlaylistSummary) -> Unit,
    onOpenPlaylist: (UserPlaylistSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    PlaylistRootPage(
        active = active,
        playlists = playlists,
        editMode = selection.rootEditMode,
        selectedPlaylistIds = selection.selectedPlaylistIds,
        onCreatePlaylist = onCreatePlaylist,
        onRenamePlaylist = onRenamePlaylist,
        onPlaylistClick = { playlist ->
            if (selection.rootEditMode) {
                selection.togglePlaylistSelection(playlist.id)
            } else {
                onOpenPlaylist(playlist)
            }
        },
        onPlaylistSelectionChange = { playlist, selected ->
            selection.setPlaylistSelected(playlist.id, selected)
        },
        modifier = modifier,
    )
}

/** 详情页：拖动排序、删除与批量增删由调用方决策，其余走选择控制器。 */
@Composable
internal fun PlaylistDetailSection(
    active: Boolean,
    target: PlaylistTarget?,
    playlistTarget: PlaylistTarget,
    activePlaylist: UserPlaylistDetail?,
    title: String,
    tracks: List<MediaItem>,
    libraryLoading: Boolean,
    retainedDetailSnapshot: PlaylistDetailSnapshot?,
    selection: PlaylistSelectionController,
    browser: Player?,
    onShuffle: (List<MediaItem>) -> Unit,
    onDeletePlaylist: () -> Unit,
    onRequestDeleteTracks: () -> Unit,
    onReorderTracks: (String, List<String>) -> Unit,
    onPlayTrack: (List<MediaItem>, Int) -> Unit,
    onTrackMoreClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val detailSnapshot =
        if (playlistTarget == target) {
            PlaylistDetailSnapshot(
                playlistId = playlistTarget.playlistId,
                playlist = activePlaylist,
                title = title,
                tracks = tracks,
                libraryLoading = libraryLoading,
            )
        } else {
            retainedDetailSnapshot?.takeIf { snapshot ->
                snapshot.playlistId == playlistTarget.playlistId
            }
                ?: PlaylistDetailSnapshot(
                    playlistId = playlistTarget.playlistId,
                    playlist = activePlaylist,
                    title = playlistTarget.title,
                    tracks = tracks,
                    libraryLoading = libraryLoading,
                )
        }
    PlaylistDetailPage(
        active = active && !selection.addModeVisible,
        playlist = detailSnapshot.playlist,
        title = detailSnapshot.title,
        tracks = detailSnapshot.tracks,
        libraryLoading = detailSnapshot.libraryLoading,
        editMode = selection.detailEditMode,
        selectedTrackIds = selection.selectedTrackIds,
        browser = browser,
        onShuffle = { onShuffle(detailSnapshot.tracks) },
        onDeletePlaylist = onDeletePlaylist,
        onEditModeChange = selection::changeDetailEditMode,
        onAddOrRemoveClick = {
            if (selection.selectedTrackIds.isEmpty()) {
                selection.beginAddMode(target, returnsToRoot = false)
            } else {
                onRequestDeleteTracks()
            }
        },
        onToggleAll = { checked ->
            selection.replaceSelectedTracks(
                if (checked) {
                    detailSnapshot.tracks.map(MediaItem::mediaId)
                } else {
                    emptyList()
                }
            )
        },
        onReorderTracks = { orderedMediaIds ->
            val playlistId = target?.playlistId ?: return@PlaylistDetailPage
            onReorderTracks(playlistId, orderedMediaIds)
        },
        onTrackSelectionChange = selection::setTrackSelected,
        onTrackClick = { item, index ->
            if (selection.detailEditMode) {
                selection.toggleTrackSelection(item.mediaId)
                return@PlaylistDetailPage
            }
            onPlayTrack(detailSnapshot.tracks, index)
        },
        onTrackMoreClick = onTrackMoreClick,
        modifier = modifier,
    )
}

/** 添加模式覆盖层：从底部滑入，确认后把选中的歌曲加入目标歌单并关闭。 */
@Composable
internal fun PlaylistAddModeOverlay(
    selection: PlaylistSelectionController,
    visibleSongs: List<MediaItem>,
    libraryLoaded: Boolean,
    active: Boolean,
    addModeExistingIds: Set<String>,
    playlistRepository: PlaylistRepository,
    scope: CoroutineScope,
    onCloseAddMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = selection.addModeVisible,
        modifier = modifier.fillMaxSize().zIndex(2f),
        enter =
            slideInVertically(
                animationSpec =
                    tween(
                        durationMillis = PlaylistAddModeSlideMillis,
                        easing = PlaylistAddModeEasing,
                    ),
                initialOffsetY = { it },
            ),
        exit =
            slideOutVertically(
                animationSpec =
                    tween(
                        durationMillis = PlaylistAddModeSlideMillis,
                        easing = PlaylistAddModeEasing,
                    ),
                targetOffsetY = { it },
            ),
    ) {
        val currentAddModeTarget = selection.addModeTarget ?: return@AnimatedVisibility
        Column(
            modifier = Modifier.fillMaxSize().background(colorResource(R.color.page_background))
        ) {
            PlaylistAddModeTitleArea(
                target = currentAddModeTarget,
                onConfirm = {
                    val playlistId =
                        selection.addModeTarget?.playlistId ?: return@PlaylistAddModeTitleArea
                    val mediaIds = selection.selectedAddSongIds.toList()
                    if (mediaIds.isEmpty()) {
                        onCloseAddMode()
                        return@PlaylistAddModeTitleArea
                    }
                    scope.launch {
                        playlistRepository.addMediaIds(playlistId, mediaIds)
                        onCloseAddMode()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            SongsPage(
                mediaItems = visibleSongs,
                libraryLoaded = libraryLoaded,
                active = active && selection.addModeVisible,
                editMode = true,
                selectedSongIds = selection.selectedAddSongIds,
                hiddenMediaIds = addModeExistingIds,
                onSongSelectionChange = selection::setAddSongSelected,
                onTrackMoreClick = {},
                onRequestSongDeleteConfirmation = { _, onDismiss ->
                    onDismiss?.invoke()
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

/** 新建/重命名与删除两类弹窗及其落库逻辑。 */
@Composable
internal fun PlaylistPageDialogs(
    context: Context,
    playlistRepository: PlaylistRepository,
    scope: CoroutineScope,
    selection: PlaylistSelectionController,
    target: PlaylistTarget?,
    nameDialogRequest: PlaylistNameDialogRequest?,
    deleteRequest: PlaylistDeleteRequest?,
    visibleSongs: List<MediaItem>,
    libraryLoaded: Boolean,
    onNameDialogRequestChange: (PlaylistNameDialogRequest?) -> Unit,
    onDeleteRequestChange: (PlaylistDeleteRequest?) -> Unit,
    onTargetChange: (PlaylistTarget?) -> Unit,
) {
    PlaylistNameDialogOverlay(
        request = nameDialogRequest,
        onDismiss = {
            onNameDialogRequestChange(null)
        },
        onConfirm = { request, name ->
            scope.launch {
                when (request) {
                    is PlaylistNameDialogRequest.Create -> {
                        when (val result = playlistRepository.createPlaylist(name)) {
                            is PlaylistCreateResult.Success -> {
                                onNameDialogRequestChange(null)
                                val createdTarget =
                                    PlaylistTarget(
                                        playlistId = result.playlistId,
                                        title = name.trim(),
                                    )
                                onTargetChange(createdTarget)
                                selection.exitDetailEdit()
                                if (visibleSongs.isNotEmpty() || !libraryLoaded) {
                                    selection.beginAddMode(createdTarget, returnsToRoot = true)
                                    onTargetChange(null)
                                }
                            }
                            PlaylistCreateResult.DuplicateName -> {
                                Toast.makeText(
                                        context,
                                        R.string.playlist_duplicate_name,
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            }
                            PlaylistCreateResult.EmptyName -> Unit
                        }
                    }
                    is PlaylistNameDialogRequest.Rename -> {
                        when (playlistRepository.renamePlaylist(request.playlistId, name)) {
                            PlaylistRenameResult.Success -> {
                                onNameDialogRequestChange(null)
                                if (target?.playlistId == request.playlistId) {
                                    onTargetChange(target.copy(title = name.trim()))
                                }
                            }
                            PlaylistRenameResult.DuplicateName -> {
                                Toast.makeText(
                                        context,
                                        R.string.playlist_duplicate_name,
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            }
                            PlaylistRenameResult.EmptyName,
                            PlaylistRenameResult.MissingPlaylist -> Unit
                        }
                    }
                }
            }
        },
    )

    PlaylistDeleteDialog(
        request = deleteRequest,
        onDismiss = {
            onDeleteRequestChange(null)
        },
        onConfirm = { request ->
            scope.launch {
                when (request) {
                    PlaylistDeleteRequest.RootSelected -> {
                        playlistRepository.deletePlaylists(selection.selectedPlaylistIds)
                        selection.exitRootEdit()
                    }
                    PlaylistDeleteRequest.DetailPlaylist -> {
                        val playlistId = target?.playlistId
                        if (playlistId != null) {
                            playlistRepository.deletePlaylists(setOf(playlistId))
                        }
                        onTargetChange(null)
                        selection.resetAfterDetailPlaylistDelete()
                    }
                    PlaylistDeleteRequest.DetailTracks -> {
                        val playlistId = target?.playlistId
                        if (playlistId != null) {
                            playlistRepository.removeMediaIds(playlistId, selection.selectedTrackIds)
                        }
                        selection.exitDetailEdit()
                    }
                }
                onDeleteRequestChange(null)
            }
        },
    )
}

/** 顶部标题区：根列表/详情的编辑态入口与返回由调用方决策。 */
@Composable
internal fun PlaylistTitleSection(
    target: PlaylistTarget?,
    detailTitle: String,
    selection: PlaylistSelectionController,
    onRootBack: (() -> Unit)?,
    onDetailBack: () -> Unit,
    onRequestDeleteRootSelected: () -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
            PlaylistTitleArea(
                target = target,
                detailTitle = detailTitle,
                rootEditMode = selection.rootEditMode,
                rootSelectedCount = selection.selectedPlaylistIds.size,
                detailEditMode = selection.detailEditMode,
                onRootEnterEdit = selection::enterRootEdit,
                onRootExitEdit = selection::exitRootEdit,
                onRootDeleteSelected = {
                    if (selection.selectedPlaylistIds.isNotEmpty()) {
                        onRequestDeleteRootSelected()
                    }
                },
                onRootBack = onRootBack,
                onDetailBack = onDetailBack,
                onDetailEnterEdit = {
                    selection.changeDetailEditMode(true)
                },
                onDetailExitEdit = selection::exitDetailEdit,
                onSearchClick = onSearchClick,
                modifier = Modifier.fillMaxWidth(),
            )
}

/** 播放列表页的组合期副作用与返回键处理；顺序与拆分前一致。 */
@Composable
internal fun PlaylistPageEffects(
    active: Boolean,
    onClose: (() -> Unit)?,
    target: PlaylistTarget?,
    selection: PlaylistSelectionController,
    playlists: List<UserPlaylistSummary>,
    activePlaylistId: String?,
    activePlaylist: UserPlaylistDetail?,
    detailTitle: String,
    detailTracks: List<MediaItem>,
    detailLibraryLoading: Boolean,
    addModeVisible: Boolean,
    onTargetChange: (PlaylistTarget?) -> Unit,
    onRetainedDetailSnapshotChange: (PlaylistDetailSnapshot) -> Unit,
    onAddModeActiveChanged: (Boolean) -> Unit,
    onCloseAddMode: () -> Unit,
) {
    LaunchedEffect(activePlaylistId, activePlaylist, playlists) {
        if (
            activePlaylistId != null &&
                activePlaylist == null &&
                playlists.none { it.id == activePlaylistId }
        ) {
            onTargetChange(null)
            selection.resetMissingPlaylist()
        }
    }
    LaunchedEffect(
        activePlaylistId,
        activePlaylist,
        detailTitle,
        detailTracks,
        detailLibraryLoading,
    ) {
        val playlistId = activePlaylistId ?: return@LaunchedEffect
        onRetainedDetailSnapshotChange(
            PlaylistDetailSnapshot(
                playlistId = playlistId,
                playlist = activePlaylist,
                title = detailTitle,
                tracks = detailTracks,
                libraryLoading = detailLibraryLoading,
            )
        )
    }
    LaunchedEffect(addModeVisible) {
        onAddModeActiveChanged(addModeVisible)
    }
    DisposableEffect(Unit) {
        onDispose {
            onAddModeActiveChanged(false)
        }
    }

    BackHandler(enabled = addModeVisible) {
        onCloseAddMode()
    }
    BackHandler(enabled = !addModeVisible && selection.detailEditMode) {
        selection.exitDetailEdit()
    }
    BackHandler(enabled = target == null && selection.rootEditMode) {
        selection.exitRootEdit()
    }
    if (onClose != null) {
        BackHandler(enabled = active && target == null && !selection.rootEditMode && !addModeVisible) {
            onClose()
        }
    }
    BackHandler(enabled = !addModeVisible && !selection.detailEditMode && target != null) {
        onTargetChange(null)
    }
}
