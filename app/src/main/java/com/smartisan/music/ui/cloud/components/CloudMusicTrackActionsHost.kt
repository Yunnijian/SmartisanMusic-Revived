package com.smartisan.music.ui.cloud.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.smartisan.music.R
import com.smartisan.music.data.favorite.FavoriteSongsRepository
import com.smartisan.music.data.online.NeteaseAccountActionStatus
import com.smartisan.music.data.online.OnlineAccountPlaylist
import com.smartisan.music.data.online.OnlineMusicProvider
import com.smartisan.music.data.online.OnlineMusicProviderRepository
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.data.online.OnlineTrack
import com.smartisan.music.data.online.onlineTrackIdentityOrNull
import com.smartisan.music.data.online.toMediaItem
import com.smartisan.music.data.online.withOnlinePlaybackPlaceholderUri
import com.smartisan.music.playback.LocalPlaybackBrowser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 单曲「更多」弹层的可见性状态，由列表页持有、[CloudTrackActionsOverlays] 消费。 */
internal class CloudTrackActionsState {
    var pendingTrack by mutableStateOf<OnlineTrack?>(null)
    var actionsVisible by mutableStateOf(false)
    var pickerVisible by mutableStateOf(false)
    var createDialogVisible by mutableStateOf(false)

    fun show(track: OnlineTrack) {
        pendingTrack = track
        actionsVisible = true
    }
}

@Composable
internal fun rememberCloudTrackActionsState(): CloudTrackActionsState {
    return remember { CloudTrackActionsState() }
}

/**
 * 云音乐单曲「更多」的完整动作集：加入歌单 / 添加到播放队列 / 喜欢 / 从歌单移除。
 *
 * 详情页与搜索页共用；歌单增删改成功后回调 [onAccountLibraryChanged]，
 * 「从歌单移除」成功后额外回调 [onTrackRemoved] 让列表即时过滤。
 * [editablePlaylist] 非空时才提供「从歌单移除」。
 */
@Composable
internal fun CloudTrackActionsOverlays(
    state: CloudTrackActionsState,
    repository: OnlineMusicProviderRepository,
    editablePlaylist: OnlineAccountPlaylist?,
    onTrackRemoved: (OnlineTrack) -> Unit,
    onAccountLibraryChanged: () -> Unit,
    onAddedToPlaylist: (OnlineAccountPlaylist) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playbackBrowser = LocalPlaybackBrowser.current
    // 本地收藏为准（与播放条心形一致），喜欢动作同步回写网易云账号。
    val favoriteRepository = remember(context) { FavoriteSongsRepository.getInstance(context) }
    val favoriteIds by favoriteRepository.observeFavoriteIds().collectAsState(initial = emptySet())
    val onlineRouter = remember(context) { OnlineMusicRepositoryRouter.getInstance(context) }

    // 歌单选择器数据：打开时拉取我的歌单，关闭时清空。
    val pickerPlaylists by produceState<List<OnlineAccountPlaylist>>(
        initialValue = emptyList(),
        state.pickerVisible,
    ) {
        value = if (state.pickerVisible) {
            try {
                repository.accountPlaylists().orEmpty()
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    /** 账号动作三态文案：成功 → 操作专属串，未登录 → 引导串，其余 → 通用失败串。 */
    fun accountActionMessage(
        status: NeteaseAccountActionStatus,
        successMessage: String,
    ): String {
        return when (status) {
            NeteaseAccountActionStatus.Success -> successMessage
            NeteaseAccountActionStatus.RequiresLogin ->
                context.getString(R.string.cloud_music_detail_login_required)
            else -> context.getString(R.string.cloud_music_action_failed)
        }
    }

    fun onAddToPlaylist(playlist: OnlineAccountPlaylist) {
        val track = state.pendingTrack ?: return
        state.pickerVisible = false
        scope.launch {
            val result = repository.addTracksToAccountPlaylist(playlist, listOf(track.trackId))
            Toast.makeText(
                context,
                accountActionMessage(
                    result.status,
                    context.getString(R.string.cloud_music_added_to_playlist),
                ),
                Toast.LENGTH_SHORT,
            ).show()
            if (result.status == NeteaseAccountActionStatus.Success) {
                onAccountLibraryChanged()
                onAddedToPlaylist(playlist)
            }
        }
    }

    fun onRemoveFromPlaylist(track: OnlineTrack) {
        val playlist = editablePlaylist ?: return
        scope.launch {
            val result = repository.removeTracksFromAccountPlaylist(
                playlist = playlist,
                trackIds = listOf(track.trackId),
            )
            Toast.makeText(
                context,
                accountActionMessage(
                    result.status,
                    context.getString(R.string.cloud_music_removed_from_playlist),
                ),
                Toast.LENGTH_SHORT,
            ).show()
            if (result.status == NeteaseAccountActionStatus.Success) {
                onTrackRemoved(track)
                onAccountLibraryChanged()
            }
        }
    }

    fun onCreatePlaylist(name: String) {
        val track = state.pendingTrack ?: run {
            state.createDialogVisible = false
            return
        }
        state.createDialogVisible = false
        scope.launch {
            val result = repository.createAccountPlaylist(name)
            val newPlaylist = result.playlist
            if (result.status == NeteaseAccountActionStatus.Success && newPlaylist != null) {
                repository.addTracksToAccountPlaylist(newPlaylist, listOf(track.trackId))
                Toast.makeText(
                    context,
                    context.getString(R.string.cloud_music_created_and_added),
                    Toast.LENGTH_SHORT,
                ).show()
                onAccountLibraryChanged()
            } else {
                Toast.makeText(
                    context,
                    accountActionMessage(
                        result.status,
                        context.getString(R.string.cloud_music_created_and_added),
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val actions = buildList {
        add(
            CloudMusicTrackAction(
                label = stringResource(R.string.cloud_music_add_to_playlist),
                destructive = false,
                onClick = {
                    state.actionsVisible = false
                    state.pickerVisible = true
                },
            ),
        )
        add(
            CloudMusicTrackAction(
                label = stringResource(R.string.cloud_music_add_to_queue),
                destructive = false,
                onClick = {
                    val track = state.pendingTrack
                    state.actionsVisible = false
                    if (track != null) {
                        playbackBrowser?.addMediaItems(
                            listOf(track.toMediaItem().withOnlinePlaybackPlaceholderUri()),
                        )
                        Toast.makeText(
                            context,
                            context.getString(R.string.add_to_queue_success),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            ),
        )
        val pending = state.pendingTrack
        if (pending != null) {
            val liked = pending.mediaId in favoriteIds
            add(
                CloudMusicTrackAction(
                    label = stringResource(
                        if (liked) R.string.cloud_music_action_unlike
                        else R.string.cloud_music_action_like,
                    ),
                    destructive = false,
                    onClick = {
                        state.actionsVisible = false
                        scope.launch {
                            val likedNow = favoriteRepository.toggle(pending.mediaId)
                            val identity = pending.mediaId.onlineTrackIdentityOrNull()
                            if (identity != null &&
                                identity.source == OnlineMusicProvider.Netease.sourceId
                            ) {
                                // 本地收藏为准，回写网易云失败不回滚本地状态。
                                runCatching {
                                    onlineRouter.setTrackLiked(identity, likedNow)
                                }
                            }
                        }
                    },
                ),
            )
        }
        if (editablePlaylist != null) {
            add(
                CloudMusicTrackAction(
                    label = stringResource(R.string.cloud_music_remove_from_playlist),
                    destructive = true,
                    onClick = {
                        val track = state.pendingTrack
                        state.actionsVisible = false
                        if (track != null) {
                            onRemoveFromPlaylist(track)
                        }
                    },
                ),
            )
        }
    }
    CloudMusicTrackActionsOverlay(
        visible = state.actionsVisible,
        trackTitle = state.pendingTrack?.title.orEmpty(),
        actions = actions,
        onDismiss = { state.actionsVisible = false },
        modifier = modifier,
    )
    val filterablePlaylists = pickerPlaylists.filter { playlist ->
        playlist.provider == OnlineMusicProvider.Netease &&
            playlist.isEditable &&
            !playlist.isLikedSongs &&
            playlist.playlistId != editablePlaylist?.playlistId
    }
    CloudMusicPlaylistPickerOverlay(
        visible = state.pickerVisible,
        playlists = filterablePlaylists,
        onPlaylistSelected = { onAddToPlaylist(it) },
        onCreateNewPlaylist = {
            state.pickerVisible = false
            state.createDialogVisible = true
        },
        onDismiss = { state.pickerVisible = false },
        modifier = modifier,
    )
    CloudMusicPlaylistCreateDialog(
        visible = state.createDialogVisible,
        onDismiss = { state.createDialogVisible = false },
        onConfirm = { onCreatePlaylist(it) },
        modifier = modifier,
    )
}
