package com.smartisan.music.ui.cloud

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.data.online.NeteaseAuthStore
import com.smartisan.music.data.online.OnlineAlbum
import com.smartisan.music.data.online.OnlineArtist
import com.smartisan.music.data.online.OnlineArtistIntroduction
import com.smartisan.music.data.online.OnlineMusicProvider
import com.smartisan.music.data.online.OnlineMusicProviderRepository
import com.smartisan.music.data.online.OnlinePlaylist
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
import com.smartisan.music.ui.cloud.components.CloudMusicSectionTitle
import com.smartisan.music.ui.cloud.components.CloudMusicTrackRow
import com.smartisan.music.ui.cloud.components.CloudPageBackgroundColor
import com.smartisan.music.ui.cloud.components.CloudSecondaryTextColor
import com.smartisan.music.ui.cloud.components.CloudTrackTitleColor
import com.smartisan.music.ui.cloud.components.cloudMusicPressable
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import kotlinx.coroutines.CancellationException

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
                    val playlist = OnlinePlaylist(
                        provider = OnlineMusicProvider.Netease,
                        playlistId = target.id,
                        title = target.title,
                    )
                    val tracks = repository.playlistTracks(playlist)
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
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            CloudDetailState.Error(target)
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
                // toMediaItem 携带在线身份 extras；withOnlinePlaybackPlaceholderUri 补上
                // smartisan-online://netease/{trackId} 占位 URI，播放服务端负责解析真实地址。
                val playableItems = remember(current.tracks) {
                    current.tracks.map { track -> track.toMediaItem().withOnlinePlaybackPlaceholderUri() }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = playbackBarOverlayHeight + 10.dp),
                ) {
                    item(key = "cloud-detail-header") {
                        CloudMusicDetailHeader(
                            target = current.target,
                            tracks = current.tracks,
                            onBack = onBack,
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
                        items = current.tracks,
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
) {
    val title = when (target) {
        is CloudDetailTarget.Playlist -> target.title
        is CloudDetailTarget.Album -> target.title
        is CloudDetailTarget.Artist -> target.name
    }
    val subtitle = when (target) {
        is CloudDetailTarget.Playlist -> "歌单"
        is CloudDetailTarget.Album -> "专辑"
        is CloudDetailTarget.Artist -> "艺人"
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
