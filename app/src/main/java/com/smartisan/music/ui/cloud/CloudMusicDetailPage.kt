package com.smartisan.music.ui.cloud

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smartisan.music.R
import com.smartisan.music.data.online.NeteaseAccountActionStatus
import com.smartisan.music.data.online.NeteaseAuthStore
import com.smartisan.music.data.online.OnlineAccountPlaylist
import com.smartisan.music.data.online.OnlineMusicProvider
import com.smartisan.music.data.online.OnlineTrack
import com.smartisan.music.data.online.toMediaItem
import com.smartisan.music.data.online.withOnlinePlaybackPlaceholderUri
import com.smartisan.music.playback.LocalPlaybackBrowser
import com.smartisan.music.playback.replaceQueueAndPlay
import com.smartisan.music.playback.replaceQueueAndPlayShuffled
import com.smartisan.music.ui.cloud.components.*
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import kotlinx.coroutines.launch

/**
 * 云音乐详情页：歌单 / 专辑 / 艺人 / 电台四种分发。
 *
 * 与作者新版框架的 [com.smartisan.music.ui.album.AlbumDetailPage] 组织方式一致：
 * - 数据来自宿主级 [CloudMusicDataStore.detail] 槽，按 [CloudDetailTarget] 缓存，
 *   关掉详情再打开同一 target 直接复用；移除/重试等写操作调 reload 作废；
 * - [LazyColumn] 垂直滚动，顶部 header（返回 + 封面 + 标题 + 播放全部/随机），下方歌曲列表；
 * - 艺人页额外渲染专辑横滑列表与简介文本段落；
 * - 播放入口走 [LocalPlaybackBrowser.replaceQueueAndPlay]，在线占位 URI 由播放服务解析。
 */
@Composable
internal fun CloudMusicDetailPage(
    data: CloudMusicDataStore,
    authStore: NeteaseAuthStore,
    active: Boolean,
    playbackBarOverlayHeight: Dp,
    target: CloudDetailTarget,
    onBack: () -> Unit,
    onAccountLibraryChanged: () -> Unit,
    onOpenArtistAlbums: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val playbackBrowser = LocalPlaybackBrowser.current
    val repository = data.repository
    val detailSlot = data.detail

    // 数据在宿主级槽位里：关掉详情再打开同一 target 直接复用，不重新联网。
    LaunchedEffect(detailSlot, target, active) {
        if (active) {
            detailSlot.ensureLoaded(target)
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trackActionsState = rememberCloudTrackActionsState()

    // ── 歌单管理本地状态（全部本页内管理，不改宿主） ──
    var deleteConfirmVisible by remember { mutableStateOf(false) }

    // ── 账号歌单编辑态：多选 + 批量移除（对齐旧版 editMode / selectedMediaIds） ──
    var editMode by remember { mutableStateOf(false) }
    var selectedMediaIds by remember { mutableStateOf(emptySet<String>()) }
    var removeInFlight by remember { mutableStateOf(false) }

    // 编辑态的返回键优先退出编辑，而不是离开详情页（对齐旧版 BackHandler）。
    BackHandler(enabled = editMode) {
        editMode = false
        selectedMediaIds = emptySet()
    }
    // 换 target 时编辑态不残留到下一个详情。
    LaunchedEffect(target) {
        editMode = false
        selectedMediaIds = emptySet()
    }

    fun accountPlaylistFor(target: CloudDetailTarget.Playlist): OnlineAccountPlaylist {
        return OnlineAccountPlaylist(
            provider = OnlineMusicProvider.Netease,
            playlistId = target.id,
            title = target.title,
            trackCount = 0,
            isEditable = true,
        )
    }

    fun removeSelectedFromAccountPlaylist() {
        val playlistTarget = target as? CloudDetailTarget.Playlist ?: return
        if (removeInFlight || selectedMediaIds.isEmpty()) return
        val bundle = (detailSlot.state(target) as? CloudSlotState.Success)?.data ?: return
        val trackIds = bundle.tracks
            .filter { track -> track.mediaId in selectedMediaIds }
            .map { track -> track.trackId }
            .distinct()
        if (trackIds.isEmpty()) {
            selectedMediaIds = emptySet()
            return
        }
        removeInFlight = true
        scope.launch {
            val status = cloudRunSuspendCatching {
                repository.removeTracksFromAccountPlaylist(
                    playlist = accountPlaylistFor(playlistTarget),
                    trackIds = trackIds,
                )
            }.getOrNull()?.status ?: NeteaseAccountActionStatus.Failed
            removeInFlight = false
            val message = when (status) {
                NeteaseAccountActionStatus.Success ->
                    context.getString(R.string.cloud_music_removed_from_playlist)
                NeteaseAccountActionStatus.RequiresLogin ->
                    context.getString(R.string.cloud_music_detail_login_required)
                else -> context.getString(R.string.cloud_music_action_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            if (status == NeteaseAccountActionStatus.Success) {
                editMode = false
                selectedMediaIds = emptySet()
                detailSlot.reload(target)
                onAccountLibraryChanged()
            }
        }
    }

    fun onDeletePlaylistConfirm() {
        deleteConfirmVisible = false
        val playlistTarget = target as? CloudDetailTarget.Playlist ?: return
        scope.launch {
            val result = repository.deleteAccountPlaylist(accountPlaylistFor(playlistTarget))
            val message = when (result.status) {
                NeteaseAccountActionStatus.Success ->
                    context.getString(R.string.cloud_music_deleted_playlist)
                NeteaseAccountActionStatus.RequiresLogin ->
                    context.getString(R.string.cloud_music_detail_login_required)
                else -> context.getString(R.string.cloud_music_action_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            if (result.status == NeteaseAccountActionStatus.Success) {
                onAccountLibraryChanged()
                onBack()
            }
        }
    }

    Column(modifier.fillMaxSize().background(CloudPageBackgroundColor)) {
        // 返回栏常驻在滚动区外：loading/error/empty 态同样可返回，滚到底也不会随 header 滚走。
        CloudMusicDetailTopBar(onBack = onBack)
        Box(Modifier.fillMaxWidth().weight(1f)) {
        when (val current = detailSlot.state(target)) {
            CloudSlotState.Loading -> CloudMusicDelayedLoadingState(
                title = stringResource(R.string.cloud_music_detail_loading),
                modifier = Modifier.fillMaxSize(),
            )
            CloudSlotState.Error -> CloudMusicBlankState(
                title = stringResource(R.string.cloud_music_detail_error),
                subtitle = null,
                actionText = stringResource(R.string.cloud_music_detail_retry),
                onActionClick = { detailSlot.reload(target) },
                modifier = Modifier.fillMaxSize(),
            )
            is CloudSlotState.Success -> {
                val bundle = current.data
                if (bundle.tracks.isEmpty() && bundle.albums.isEmpty()) {
                    CloudMusicBlankState(
                        title = stringResource(R.string.cloud_music_detail_empty),
                        subtitle = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                val visibleTracks = bundle.tracks
                // toMediaItem 携带在线身份 extras；withOnlinePlaybackPlaceholderUri 补上
                // smartisan-online://netease/{trackId} 占位 URI，播放服务端负责解析真实地址。
                val playableItems = remember(visibleTracks) {
                    visibleTracks.map { track -> track.toMediaItem().withOnlinePlaybackPlaceholderUri() }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = playbackBarOverlayHeight + 10.dp),
                ) {
                    item(key = "cloud-detail-header") {
                        CloudMusicDetailHeader(
                            target = target,
                            tracks = visibleTracks,
                            editMode = editMode,
                            selectedCount = selectedMediaIds.size,
                            actionInFlight = removeInFlight,
                            onDeletePlaylist = if (target is CloudDetailTarget.Playlist &&
                                target.accountEditable
                            ) {
                                { deleteConfirmVisible = true }
                            } else {
                                null
                            },
                            onEditClick = {
                                editMode = true
                                selectedMediaIds = emptySet()
                            },
                            onSelectAll = {
                                selectedMediaIds = visibleTracks.map { track -> track.mediaId }.toSet()
                            },
                            onRemoveSelected = ::removeSelectedFromAccountPlaylist,
                            onCancelEdit = {
                                editMode = false
                                selectedMediaIds = emptySet()
                            },
                            onPlayAll = {
                                playbackBrowser.replaceQueueAndPlay(
                                    mediaItems = playableItems,
                                    startIndex = 0,
                                )
                            },
                            onShuffle = {
                                playbackBrowser.replaceQueueAndPlayShuffled(
                                    mediaItems = playableItems,
                                )
                            },
                        )
                    }
                    itemsIndexed(
                        items = visibleTracks,
                        key = { index, track -> "${track.mediaId}:$index" },
                    ) { index, track ->
                        Column {
                            if (editMode) {
                                CloudMusicDetailEditRow(
                                    track = track,
                                    selected = track.mediaId in selectedMediaIds,
                                    onToggle = {
                                        selectedMediaIds = if (track.mediaId in selectedMediaIds) {
                                            selectedMediaIds - track.mediaId
                                        } else {
                                            selectedMediaIds + track.mediaId
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                CloudMusicTrackRow(
                                    track = track,
                                    onClick = {
                                        playbackBrowser.replaceQueueAndPlay(
                                            mediaItems = playableItems,
                                            startIndex = index,
                                        )
                                    },
                                    onMoreClick = { trackActionsState.show(track) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            CloudMusicDivider()
                        }
                    }
                    if (target is CloudDetailTarget.Artist) {
                        if (bundle.albums.isNotEmpty()) {
                            item(key = "cloud-detail-albums") {
                                CloudMusicArtistAlbumsSection(
                                    albums = bundle.albums,
                                    onOpenArtistAlbums = onOpenArtistAlbums,
                                )
                            }
                        }
                        if (bundle.introduction.isNotEmpty()) {
                            item(key = "cloud-detail-intro") {
                                CloudMusicArtistIntroSection(introduction = bundle.introduction)
                            }
                        }
                    }
                }
                }
            }
        }

        // ── 单曲「更多」动作集（加入歌单/加队列/喜欢/从歌单移除），与搜索页共用 ──
        CloudTrackActionsOverlays(
            state = trackActionsState,
            repository = repository,
            editablePlaylist = (target as? CloudDetailTarget.Playlist)
                ?.takeIf { it.accountEditable }
                ?.let(::accountPlaylistFor),
            onTrackRemoved = { detailSlot.reload(target) },
            onAccountLibraryChanged = onAccountLibraryChanged,
            onAddedToPlaylist = { added ->
                if ((target as? CloudDetailTarget.Playlist)?.id == added.playlistId) {
                    detailSlot.reload(target)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (deleteConfirmVisible) {
            CloudMusicDeletePlaylistConfirmDialog(
                onDismiss = { deleteConfirmVisible = false },
                onConfirm = { onDeletePlaylistConfirm() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        }
    }
}

/**
 * 详情页常驻返回栏（位于滚动区外，所有加载态同样可返回）。
 * 返回箭头沿用 standard_icon_back_selector，与「我的」页顶栏一致。
 */
@Composable
private fun CloudMusicDetailTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val backInteraction = remember { MutableInteractionSource() }
        val backPressed by backInteraction.collectIsPressedAsState()
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(
                    interactionSource = backInteraction,
                    indication = null,
                    onClick = onBack,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = rememberSmartisanDrawablePainter(
                    R.drawable.standard_icon_back_selector,
                    pressed = backPressed,
                ),
                contentDescription = stringResource(R.string.back),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
