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
import androidx.media3.common.MediaItem
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

/** 账号动作三态文案的资源串：成功 → 操作专属串，未登录 → 引导串，其余 → 通用失败串。 */
internal fun accountActionMessageRes(
    status: NeteaseAccountActionStatus,
    successMessageRes: Int,
): Int {
    return when (status) {
        NeteaseAccountActionStatus.Success -> successMessageRes
        NeteaseAccountActionStatus.RequiresLogin ->
            R.string.cloud_music_detail_login_required
        else -> R.string.cloud_music_action_failed
    }
}

/**
 * 「新建歌单并加歌」里加歌结果的三态文案。
 *
 * 加歌失败时歌单本身已经建好，文案必须区别于普通失败串，否则用户会以为歌单没建成。
 */
internal fun createdPlaylistAddMessageRes(status: NeteaseAccountActionStatus): Int {
    return when (status) {
        NeteaseAccountActionStatus.Success -> R.string.cloud_music_created_and_added
        NeteaseAccountActionStatus.RequiresLogin ->
            R.string.cloud_music_detail_login_required
        NeteaseAccountActionStatus.Failed -> R.string.cloud_music_created_add_failed
    }
}

/** 「加入播放队列」的出口：主组合绑定 `LocalPlaybackBrowser` 提供的播放浏览器，单测注入记录式假实现。 */
internal fun interface PlaybackQueueInserter {
    fun addMediaItems(items: List<MediaItem>)
}

/**
 * 云音乐单曲「更多」的可测动作执行体：文案、调用顺序与仓库写入都在这里，[CloudTrackActionsOverlays] 只做接线。
 *
 * 这些分支原先写在 Composable 闭包里（Toast + `rememberCoroutineScope` + 仓库），JVM 单测无法实例化：
 * 「新建歌单的三态提示」与「加入队列的失败提示」两处修复被回滚时没有任何测试会报警。
 *
 * [pendingTrack] 在调用时才求值（点击/确认那一刻的状态）；[queueMediaItem] 可注入是因为
 * `OnlineTrack.toMediaItem()` 会构造 `android.os.Bundle`，JVM 单测里的桩实现调用即抛。
 */
internal class CloudTrackActionsRunner(
    private val repository: OnlineMusicProviderRepository,
    private val pendingTrack: () -> OnlineTrack?,
    private val showMessage: (Int) -> Unit,
    private val onAccountLibraryChanged: () -> Unit,
    private val playbackQueue: PlaybackQueueInserter?,
    private val queueMediaItem: (OnlineTrack) -> MediaItem = { track ->
        track.toMediaItem().withOnlinePlaybackPlaceholderUri()
    },
) {

    /**
     * 新建歌单并加入当前待处理歌曲。
     *
     * 只有拿到歌单对象才加歌，并按**加歌结果**出三态文案：成功 → 「已创建并加入」，
     * 未登录 → 登录引导，失败 → 「歌单已创建，添加歌曲失败」；
     * 建歌单本身失败（或没拿到歌单）时不加歌，走通用三态文案。
     * 加歌成败都要刷新账号歌单列表——歌单确实建好了，刷新放在加歌之后，结果才带上新歌。
     */
    suspend fun createPlaylistAndAddTrack(name: String) {
        val track = pendingTrack() ?: return
        val result = repository.createAccountPlaylist(name)
        val newPlaylist = result.playlist
        if (result.status == NeteaseAccountActionStatus.Success && newPlaylist != null) {
            val addResult =
                repository.addTracksToAccountPlaylist(newPlaylist, listOf(track.trackId))
            onAccountLibraryChanged()
            showMessage(createdPlaylistAddMessageRes(addResult.status))
        } else {
            showMessage(
                accountActionMessageRes(result.status, R.string.cloud_music_created_and_added),
            )
        }
    }

    /** 加入播放队列：待处理歌曲与播放浏览器缺一不可，缺任一个都提示操作失败，不静默丢弃。 */
    fun addPendingTrackToQueue() {
        val track = pendingTrack()
        val queue = playbackQueue
        if (track != null && queue != null) {
            queue.addMediaItems(listOf(queueMediaItem(track)))
            showMessage(R.string.add_to_queue_success)
        } else {
            showMessage(R.string.cloud_music_action_failed)
        }
    }
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

    /** Toast 出口：运行器只发字符串资源 id，解析与展示都留在这里。 */
    fun showToast(messageRes: Int) {
        Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
    }

    val actionRunner =
        CloudTrackActionsRunner(
            repository = repository,
            pendingTrack = { state.pendingTrack },
            showMessage = { messageRes -> showToast(messageRes) },
            onAccountLibraryChanged = onAccountLibraryChanged,
            playbackQueue = playbackBrowser?.let { browser ->
                PlaybackQueueInserter { items -> browser.addMediaItems(items) }
            },
        )

    fun onAddToPlaylist(playlist: OnlineAccountPlaylist) {
        val track = state.pendingTrack ?: return
        state.pickerVisible = false
        scope.launch {
            val result = repository.addTracksToAccountPlaylist(playlist, listOf(track.trackId))
            showToast(
                accountActionMessageRes(result.status, R.string.cloud_music_added_to_playlist),
            )
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
            showToast(
                accountActionMessageRes(result.status, R.string.cloud_music_removed_from_playlist),
            )
            if (result.status == NeteaseAccountActionStatus.Success) {
                onTrackRemoved(track)
                onAccountLibraryChanged()
            }
        }
    }

    fun onCreatePlaylist(name: String) {
        // 弹窗先关：待处理歌曲缺失时也一样，与迁移前的两条 return 路径等价。
        state.createDialogVisible = false
        scope.launch { actionRunner.createPlaylistAndAddTrack(name) }
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
                    state.actionsVisible = false
                    actionRunner.addPendingTrackToQueue()
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
