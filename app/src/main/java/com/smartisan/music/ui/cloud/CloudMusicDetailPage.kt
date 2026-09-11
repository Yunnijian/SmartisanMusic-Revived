package com.smartisan.music.ui.cloud

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.data.online.NeteaseAccountActionStatus
import com.smartisan.music.data.online.NeteaseAuthStore
import com.smartisan.music.data.online.OnlineAccountPlaylist
import com.smartisan.music.data.online.OnlineAlbum
import com.smartisan.music.data.online.OnlineArtist
import com.smartisan.music.data.online.OnlineArtistIntroduction
import com.smartisan.music.data.online.OnlineMusicProvider
import com.smartisan.music.data.online.OnlineMusicProviderRepository
import com.smartisan.music.data.online.OnlinePlaylist
import com.smartisan.music.data.online.OnlineRadio
import com.smartisan.music.data.online.OnlineTrack
import com.smartisan.music.data.online.toMediaItem
import com.smartisan.music.data.online.withOnlinePlaybackPlaceholderUri
import com.smartisan.music.playback.LocalPlaybackBrowser
import com.smartisan.music.playback.replaceQueueAndPlay
import com.smartisan.music.ui.cloud.components.CloudAccentColor
import com.smartisan.music.ui.cloud.components.CloudMusicBlankState
import com.smartisan.music.ui.cloud.components.CloudMusicCoverImage
import com.smartisan.music.ui.cloud.components.CloudMusicDelayedLoadingState
import com.smartisan.music.ui.cloud.components.CloudMusicDivider
import com.smartisan.music.ui.cloud.components.CloudMusicPlaylistCreateDialog
import com.smartisan.music.ui.cloud.components.CloudMusicPlaylistPickerOverlay
import com.smartisan.music.ui.cloud.components.CloudMusicSectionTitle
import com.smartisan.music.ui.cloud.components.CloudMusicTrackAction
import com.smartisan.music.ui.cloud.components.CloudMusicTrackActionsOverlay
import com.smartisan.music.ui.cloud.components.CloudMusicTrackRow
import com.smartisan.music.ui.cloud.components.CloudPageBackgroundColor
import com.smartisan.music.ui.cloud.components.CloudSearchFieldBackgroundColor
import com.smartisan.music.ui.cloud.components.CloudSecondaryTextColor
import com.smartisan.music.ui.cloud.components.CloudSurfaceColor
import com.smartisan.music.ui.cloud.components.CloudTrackTitleColor
import com.smartisan.music.ui.cloud.components.cloudMusicPressable
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 云音乐详情页状态机：loading/error/empty/success 四态。
 * 无 ViewModel，数据请求由 [CloudMusicDetailPage] 内的 [produceState] 按 [CloudDetailTarget] 发起。
 */
internal sealed interface CloudDetailState {
    object Loading : CloudDetailState
    data class Error(val target: CloudDetailTarget) : CloudDetailState
    data class Empty(val target: CloudDetailTarget) : CloudDetailState
    data class Success(
        val target: CloudDetailTarget,
        val tracks: List<OnlineTrack>,
        val albums: List<OnlineAlbum> = emptyList(),
        val introduction: List<OnlineArtistIntroduction> = emptyList(),
    ) : CloudDetailState
}

/**
 * 云音乐详情页：歌单 / 专辑 / 艺人三种分发。
 *
 * 与作者新版框架的 [com.smartisan.music.ui.album.AlbumDetailPage] 组织方式一致：
 * - 无 ViewModel，[produceState] 按 [CloudDetailTarget] 发起请求，状态机 loading/error/empty/success；
 * - [LazyColumn] 垂直滚动，顶部 header（返回 + 封面 + 标题 + 播放全部/随机），下方歌曲列表；
 * - 艺人页额外渲染专辑横滑列表与简介文本段落；
 * - 播放入口走 [LocalPlaybackBrowser.replaceQueueAndPlay]，在线占位 URI 由播放服务解析。
 */
@Composable
internal fun CloudMusicDetailPage(
    repository: OnlineMusicProviderRepository,
    authStore: NeteaseAuthStore,
    active: Boolean,
    playbackBarOverlayHeight: Dp,
    target: CloudDetailTarget,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playbackBrowser = LocalPlaybackBrowser.current
    // 错误重试时递增，让 produceState 以相同 target 重新发起请求。
    var loadRevision by remember { mutableStateOf(0) }

    val state by produceState<CloudDetailState>(
        initialValue = CloudDetailState.Loading,
        target,
        loadRevision,
        active,
    ) {
        // 切到非活跃 tab 时不发起请求，回到 Loading；active 恢复后由 key 变化重新触发加载。
        if (!active) {
            return@produceState
        }
        value = CloudDetailState.Loading
        value = try {
            when (target) {
                is CloudDetailTarget.Playlist -> {
                    val tracks = if (target.accountEditable) {
                        val playlist = OnlineAccountPlaylist(
                            provider = OnlineMusicProvider.Netease,
                            playlistId = target.id,
                            title = target.title,
                            trackCount = 0,
                            isEditable = true,
                        )
                        repository.accountPlaylistTracks(playlist)
                    } else {
                        val playlist = OnlinePlaylist(
                            provider = OnlineMusicProvider.Netease,
                            playlistId = target.id,
                            title = target.title,
                        )
                        repository.playlistTracks(playlist)
                    }
                    if (tracks.isEmpty()) CloudDetailState.Empty(target)
                    else CloudDetailState.Success(target = target, tracks = tracks)
                }
                is CloudDetailTarget.Album -> {
                    val album = OnlineAlbum(
                        provider = OnlineMusicProvider.Netease,
                        albumId = target.id,
                        title = target.title,
                    )
                    val tracks = repository.albumTracks(album)
                    if (tracks.isEmpty()) CloudDetailState.Empty(target)
                    else CloudDetailState.Success(target = target, tracks = tracks)
                }
                is CloudDetailTarget.Artist -> {
                    val artist = OnlineArtist(
                        provider = OnlineMusicProvider.Netease,
                        artistId = target.id,
                        name = target.name,
                    )
                    val tracks = repository.artistTopTracks(artist)
                    val albums = repository.artistAlbums(artist)
                    val introduction = repository.artistIntroduction(artist)
                    if (tracks.isEmpty() && albums.isEmpty()) {
                        CloudDetailState.Empty(target)
                    } else {
                        CloudDetailState.Success(
                            target = target,
                            tracks = tracks,
                            albums = albums,
                            introduction = introduction,
                        )
                    }
                }
                is CloudDetailTarget.Radio -> {
                    val radio = OnlineRadio(
                        provider = OnlineMusicProvider.Netease,
                        radioId = target.id,
                        title = target.title,
                    )
                    val tracks = repository.radioTracks(radio)
                    if (tracks.isEmpty()) CloudDetailState.Empty(target)
                    else CloudDetailState.Success(target = target, tracks = tracks)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            CloudDetailState.Error(target)
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 歌单管理本地状态（全部本页内管理，不改宿主） ──
    var pendingTrack by remember { mutableStateOf<OnlineTrack?>(null) }
    var trackActionsVisible by remember { mutableStateOf(false) }
    var pickerVisible by remember { mutableStateOf(false) }
    var createDialogVisible by remember { mutableStateOf(false) }
    var deleteConfirmVisible by remember { mutableStateOf(false) }
    // 「从歌单移除」的曲目，渲染时从列表中过滤。
    var removedTrackIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 歌单选择器数据：打开时拉取我的歌单，关闭时清空。
    val pickerPlaylists by produceState<List<OnlineAccountPlaylist>>(
        initialValue = emptyList(),
        pickerVisible,
    ) {
        value = if (pickerVisible) {
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

    fun accountPlaylistFor(target: CloudDetailTarget.Playlist): OnlineAccountPlaylist {
        return OnlineAccountPlaylist(
            provider = OnlineMusicProvider.Netease,
            playlistId = target.id,
            title = target.title,
            trackCount = 0,
            isEditable = true,
        )
    }

    fun onAddToPlaylist(playlist: OnlineAccountPlaylist) {
        val track = pendingTrack ?: return
        pickerVisible = false
        scope.launch {
            val result = repository.addTracksToAccountPlaylist(playlist, listOf(track.trackId))
            val message = if (result.status == NeteaseAccountActionStatus.Success) {
                    context.getString(R.string.cloud_music_added_to_playlist)
                } else {
                    context.getString(R.string.cloud_music_action_failed)
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun onRemoveFromPlaylist(track: OnlineTrack) {
        val playlistTarget = target as? CloudDetailTarget.Playlist ?: return
        scope.launch {
            val result = repository.removeTracksFromAccountPlaylist(
                playlist = accountPlaylistFor(playlistTarget),
                trackIds = listOf(track.trackId),
            )
            if (result.status == NeteaseAccountActionStatus.Success) {
                removedTrackIds = removedTrackIds + track.trackId
                Toast.makeText(context, context.getString(R.string.cloud_music_removed_from_playlist), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.cloud_music_action_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onCreatePlaylist(name: String) {
        val track = pendingTrack ?: run {
            createDialogVisible = false
            return
        }
        createDialogVisible = false
        scope.launch {
            val result = repository.createAccountPlaylist(name)
            val newPlaylist = result.playlist
            if (result.status == NeteaseAccountActionStatus.Success && newPlaylist != null) {
                repository.addTracksToAccountPlaylist(newPlaylist, listOf(track.trackId))
                Toast.makeText(context, context.getString(R.string.cloud_music_created_and_added), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.cloud_music_create_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onDeletePlaylistConfirm() {
        deleteConfirmVisible = false
        val playlistTarget = target as? CloudDetailTarget.Playlist ?: return
        scope.launch {
            val result = repository.deleteAccountPlaylist(accountPlaylistFor(playlistTarget))
            if (result.status == NeteaseAccountActionStatus.Success) {
                Toast.makeText(context, context.getString(R.string.cloud_music_deleted_playlist), Toast.LENGTH_SHORT).show()
                onBack()
            } else {
                Toast.makeText(context, context.getString(R.string.cloud_music_action_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(modifier.fillMaxSize().background(CloudPageBackgroundColor)) {
        when (val current = state) {
            CloudDetailState.Loading -> CloudMusicDelayedLoadingState(
                title = stringResource(R.string.cloud_music_detail_loading),
                modifier = Modifier.fillMaxSize(),
            )
            is CloudDetailState.Error -> CloudMusicBlankState(
                title = stringResource(R.string.cloud_music_detail_error),
                subtitle = null,
                actionText = stringResource(R.string.cloud_music_detail_retry),
                onActionClick = { loadRevision += 1 },
                modifier = Modifier.fillMaxSize(),
            )
            is CloudDetailState.Empty -> CloudMusicBlankState(
                title = stringResource(R.string.cloud_music_detail_empty),
                subtitle = null,
                modifier = Modifier.fillMaxSize(),
            )
            is CloudDetailState.Success -> {
                // 「从歌单移除」的曲目在渲染时过滤掉，保持当前列表同步刷新。
                val visibleTracks = current.tracks.filterNot { visibleTrack ->
                    removedTrackIds.contains(visibleTrack.trackId)
                }
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
                            target = current.target,
                            tracks = visibleTracks,
                            onBack = onBack,
                            onDeletePlaylist = if (current.target is CloudDetailTarget.Playlist &&
                                current.target.accountEditable
                            ) {
                                { deleteConfirmVisible = true }
                            } else {
                                null
                            },
                            onPlayAll = {
                                playbackBrowser.replaceQueueAndPlay(
                                    mediaItems = playableItems,
                                    startIndex = 0,
                                )
                            },
                            onShuffle = {
                                playbackBrowser.replaceQueueAndPlay(
                                    mediaItems = playableItems.shuffled(),
                                    startIndex = 0,
                                )
                            },
                        )
                    }
                    itemsIndexed(
                        items = visibleTracks,
                        key = { index, track -> "${track.mediaId}:$index" },
                    ) { index, track ->
                        Column {
                            CloudMusicTrackRow(
                                track = track,
                                onClick = {
                                    playbackBrowser.replaceQueueAndPlay(
                                        mediaItems = playableItems,
                                        startIndex = index,
                                    )
                                },
                                onMoreClick = {
                                    pendingTrack = track
                                    trackActionsVisible = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            CloudMusicDivider()
                        }
                    }
                    if (current.target is CloudDetailTarget.Artist) {
                        if (current.albums.isNotEmpty()) {
                            item(key = "cloud-detail-albums") {
                                CloudMusicArtistAlbumsSection(albums = current.albums)
                            }
                        }
                        if (current.introduction.isNotEmpty()) {
                            item(key = "cloud-detail-intro") {
                                CloudMusicArtistIntroSection(introduction = current.introduction)
                            }
                        }
                    }
                }
            }
        }

        // ── 底部管理弹层 / 对话框（叠加在内容之上） ──
        val actions = buildList {
            add(
                CloudMusicTrackAction(
                    label = stringResource(R.string.cloud_music_add_to_playlist),
                    destructive = false,
                    onClick = {
                        trackActionsVisible = false
                        pickerVisible = true
                    },
                ),
            )
            if (target is CloudDetailTarget.Playlist && target.accountEditable) {
                add(
                    CloudMusicTrackAction(
                        label = stringResource(R.string.cloud_music_remove_from_playlist),
                        destructive = true,
                        onClick = {
                            val track = pendingTrack
                            trackActionsVisible = false
                            if (track != null) {
                                onRemoveFromPlaylist(track)
                            }
                        },
                    ),
                )
            }
        }
        CloudMusicTrackActionsOverlay(
            visible = trackActionsVisible,
            trackTitle = pendingTrack?.title.orEmpty(),
            actions = actions,
            onDismiss = { trackActionsVisible = false },
            modifier = Modifier.fillMaxSize(),
        )
        val filterablePlaylists = pickerPlaylists.filter { playlist ->
            playlist.provider == OnlineMusicProvider.Netease &&
                !playlist.isLikedSongs &&
                !(target is CloudDetailTarget.Playlist &&
                    target.accountEditable &&
                    playlist.playlistId == target.id)
        }
        CloudMusicPlaylistPickerOverlay(
            visible = pickerVisible,
            playlists = filterablePlaylists,
            onPlaylistSelected = { onAddToPlaylist(it) },
            onCreateNewPlaylist = {
                pickerVisible = false
                createDialogVisible = true
            },
            onDismiss = { pickerVisible = false },
            modifier = Modifier.fillMaxSize(),
        )
        CloudMusicPlaylistCreateDialog(
            visible = createDialogVisible,
            onDismiss = { createDialogVisible = false },
            onConfirm = { onCreatePlaylist(it) },
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

/**
 * 详情页顶部 header：返回按钮 + 封面 + 标题/副标题 + 播放全部 / 随机播放。
 * 封面取列表首曲的 [OnlineTrack.artworkUrl]（target 仅携带 id/title，无封面 URL）；
 * 列表为空时由 [CloudMusicCoverImage] 兜底占位色。
 */
@Composable
private fun CloudMusicDetailHeader(
    target: CloudDetailTarget,
    tracks: List<OnlineTrack>,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onDeletePlaylist: (() -> Unit)? = null,
) {
    val title = when (target) {
        is CloudDetailTarget.Playlist -> target.title
        is CloudDetailTarget.Album -> target.title
        is CloudDetailTarget.Artist -> target.name
        is CloudDetailTarget.Radio -> target.title
    }
    val subtitle = when (target) {
        is CloudDetailTarget.Playlist -> stringResource(R.string.cloud_music_detail_kind_playlist)
        is CloudDetailTarget.Album -> stringResource(R.string.cloud_music_detail_kind_album)
        is CloudDetailTarget.Artist -> stringResource(R.string.cloud_music_detail_kind_artist)
        is CloudDetailTarget.Radio -> stringResource(R.string.cloud_music_detail_kind_radio)
    }
    val artworkUrl = tracks.firstOrNull()?.artworkUrl
    val playEnabled = tracks.isNotEmpty()

    Column(Modifier.fillMaxWidth()) {
        // 顶栏：仅返回按钮（左对齐）。
        Box(
            modifier = Modifier
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
        // 封面 + 标题 + 副标题。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            CloudMusicCoverImage(
                imageUrl = artworkUrl,
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = CloudTrackTitleColor,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = CloudSecondaryTextColor,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        // 播放全部 / 随机播放。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CloudMusicDetailActionButton(
                text = stringResource(R.string.cloud_music_detail_play_all),
                enabled = playEnabled,
                onClick = onPlayAll,
                modifier = Modifier.weight(1f),
            )
            CloudMusicDetailActionButton(
                text = stringResource(R.string.cloud_music_detail_shuffle),
                enabled = playEnabled,
                onClick = onShuffle,
                modifier = Modifier.weight(1f),
            )
        }
        // 账号可编辑歌单的「删除歌单」入口（浅色文字按钮）。
        if (onDeletePlaylist != null) {
            Text(
                text = stringResource(R.string.cloud_music_delete_playlist),
                style = TextStyle(
                    fontSize = 13.sp,
                    color = CloudSecondaryTextColor,
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .cloudMusicPressable(onClick = onDeletePlaylist)
                    .padding(vertical = 4.dp),
            )
        }
        CloudMusicDivider()
    }
}

/** header 中的强调色圆角操作按钮。 */
@Composable
private fun CloudMusicDetailActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (enabled) CloudAccentColor else CloudAccentColor.copy(alpha = 0.4f))
            .cloudMusicPressable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = 14.sp,
                color = Color.White,
            ),
        )
    }
}

/** 艺人专辑横滑列表：展示为不可点击的展示列表（CloudDetailTarget 已知，详情入口由宿主重新驱动）。 */
@Composable
private fun CloudMusicArtistAlbumsSection(albums: List<OnlineAlbum>) {
    CloudMusicSectionTitle(title = stringResource(R.string.cloud_music_detail_artist_albums))
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(
            items = albums,
            key = { _, album -> album.albumId },
        ) { _, album ->
            Column(modifier = Modifier.width(120.dp)) {
                CloudMusicCoverImage(
                    imageUrl = album.artworkUrl,
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Text(
                    text = album.title,
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = CloudTrackTitleColor,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                album.artist?.takeIf(String::isNotBlank)?.let { artist ->
                    Text(
                        text = artist,
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = CloudSecondaryTextColor,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

/** 艺人简介：分区标题 + 每段文本段落。 */
@Composable
private fun CloudMusicArtistIntroSection(introduction: List<OnlineArtistIntroduction>) {
    CloudMusicSectionTitle(title = stringResource(R.string.cloud_music_detail_artist_intro))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        introduction.forEach { intro ->
            if (intro.title.isNotBlank()) {
                Text(
                    text = intro.title,
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = CloudTrackTitleColor,
                    ),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                text = intro.text,
                style = TextStyle(
                    fontSize = 13.sp,
                    color = CloudSecondaryTextColor,
                ),
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

/** 删除歌单确认对话框：居中白色圆角卡片 + 半透明遮罩，取消/删除（红色）双按钮。 */
@Composable
private fun CloudMusicDeletePlaylistConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.40f))
            .cloudMusicPressable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CloudSurfaceColor)
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.cloud_music_delete_playlist),
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = CloudTrackTitleColor,
                ),
            )
            Text(
                text = stringResource(R.string.cloud_music_delete_playlist_confirm),
                style = TextStyle(
                    fontSize = 13.sp,
                    color = CloudSecondaryTextColor,
                ),
                modifier = Modifier.padding(top = 10.dp, bottom = 20.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(CloudSearchFieldBackgroundColor)
                        .cloudMusicPressable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.cloud_music_cancel),
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = CloudTrackTitleColor,
                        ),
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(CloudAccentColor)
                        .cloudMusicPressable(onClick = onConfirm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.cloud_music_delete),
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = Color.White,
                        ),
                    )
                }
            }
        }
    }
}
