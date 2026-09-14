package com.smartisan.music.ui.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.data.online.NeteaseAuthStore
import com.smartisan.music.data.online.OnlineAccountPlaylist
import com.smartisan.music.data.online.OnlineAlbum
import com.smartisan.music.data.online.OnlineRadio
import com.smartisan.music.ui.cloud.components.CloudAccentColor
import com.smartisan.music.ui.cloud.components.CloudFilterChipIdleBackgroundColor
import com.smartisan.music.ui.cloud.components.CloudFilterChipIdleTextColor
import com.smartisan.music.ui.cloud.components.CloudListFooterTextColor
import com.smartisan.music.ui.cloud.components.CloudMusicBlankState
import com.smartisan.music.ui.cloud.components.CloudMusicCoverImage
import com.smartisan.music.ui.cloud.components.CloudMusicDelayedLoadingState
import com.smartisan.music.ui.cloud.components.CloudMusicDivider
import com.smartisan.music.ui.cloud.components.CloudMusicSectionTitle
import com.smartisan.music.ui.cloud.components.CloudPageBackgroundColor
import com.smartisan.music.ui.cloud.components.CloudPullRefresh
import com.smartisan.music.ui.cloud.components.CloudSecondaryTextColor
import com.smartisan.music.ui.cloud.components.CloudSurfaceColor
import com.smartisan.music.ui.cloud.components.CloudTrackRowHeight
import com.smartisan.music.ui.cloud.components.CloudTrackTitleColor
import com.smartisan.music.ui.cloud.components.cloudAlbumSubtitle
import com.smartisan.music.ui.cloud.components.cloudMusicPressable
import com.smartisan.music.ui.cloud.components.cloudRadioSubtitle

/** 「我的」页筛选胶囊：全部 / 歌单 / 专辑 / 播客，对齐旧版 CloudAccountLibraryFilter。 */
internal enum class CloudAccountLibraryFilter(val labelRes: Int) {
    All(R.string.cloud_music_filter_all),
    Playlist(R.string.cloud_music_filter_playlist),
    Album(R.string.cloud_music_filter_album),
    Radio(R.string.cloud_music_filter_radio),
}

/** 合并列表的行模型：三类账号库数据混排时保留各自的点击与副标题规则。 */
internal sealed interface CloudAccountLibraryItem {
    data class Playlist(val playlist: OnlineAccountPlaylist) : CloudAccountLibraryItem
    data class Album(val album: OnlineAlbum) : CloudAccountLibraryItem
    data class Radio(val radio: OnlineRadio) : CloudAccountLibraryItem
}

/** footer 计数达到该条数才显示，对齐旧版 LegacyPortListFooterThreshold。 */
private const val CloudAccountLibraryFooterThreshold = 8

/**
 * 云音乐“我的”页：返回标题栏 + 用户信息行 + 四枚筛选胶囊 + 合并列表 + 计数 footer。
 *
 * - 数据来自宿主级 [CloudMusicDataStore.accountLibrary]：三个端点一次拉全，胶囊只做
 *   客户端切片（对齐旧版宿主集中持有账号库、页面纯渲染的分工）；宿主推进
 *   [libraryRevision]（删歌单 / 加歌 / 新建歌单后）即作废缓存重新拉取；
 * - 行样式对齐旧版账号库列表：纯文字两行（标题 + 「类型 · 元数据」副标题），无封面；
 * - 登录失效（三个接口全返回 null）与「已登录但无内容」都显示空态；
 * - 筛选选中态与滚动位置均由宿主持有，返回首页再进来时不重置。
 */
@Composable
internal fun CloudMusicMinePage(
    data: CloudMusicDataStore,
    scrollStates: CloudMusicScrollStates,
    authStore: NeteaseAuthStore,
    active: Boolean,
    libraryRevision: Int,
    selectedFilter: CloudAccountLibraryFilter,
    onFilterChange: (CloudAccountLibraryFilter) -> Unit,
    selectedPlaylistId: String?,
    playbackBarOverlayHeight: Dp,
    onOpenPlaylist: (OnlineAccountPlaylist) -> Unit,
    onOpenAlbum: (OnlineAlbum) -> Unit,
    onOpenRadio: (OnlineRadio) -> Unit,
    modifier: Modifier = Modifier,
) {
    // authState.isLoggedIn 在宿主处已判定为 true 才会进入本页；profile 可能为空（仅有 cookie）。
    val authState = remember { authStore.load() }
    val profile = authState.profile
    val nickname = profile?.nickname.orEmpty()
    val avatarUrl = profile?.avatarUrl

    val librarySlot = data.accountLibrary

    LaunchedEffect(librarySlot, libraryRevision, active) {
        if (active) {
            librarySlot.ensureLoaded(Unit, libraryRevision)
        }
    }

    // 下拉刷新：请求发出后等槽位离开 Loading 即视为完成。
    var refreshRequested by remember { mutableStateOf(false) }
    var libraryRefreshVersion by remember { mutableIntStateOf(-1) }
    val libraryState = librarySlot.state(Unit)
    val librarySlotVersion = librarySlot.version
    LaunchedEffect(librarySlotVersion) {
        if (refreshRequested && librarySlotVersion != libraryRefreshVersion) {
            refreshRequested = false
        }
    }

    CloudPullRefresh(
        refreshing = refreshRequested,
        onRefresh = {
            refreshRequested = true
            libraryRefreshVersion = librarySlot.version
            data.refreshAccountLibrary(libraryRevision)
        },
        canChildScrollUp = {
            scrollStates.mine.firstVisibleItemIndex > 0 ||
                scrollStates.mine.firstVisibleItemScrollOffset > 0
        },
        modifier = modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize().background(CloudPageBackgroundColor)) {
        CloudMusicSectionTitle(
            title = stringResource(R.string.cloud_music_mine_title),
            modifier = Modifier.fillMaxWidth(),
        )
        CloudMusicMineUserInfoRow(
            nickname = nickname,
            avatarUrl = avatarUrl,
        )
        CloudMusicFilterBar(
            selectedFilter = selectedFilter,
            onFilterChange = onFilterChange,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        )
        CloudMusicDivider()
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (val current = librarySlot.state(Unit)) {
                CloudSlotState.Loading -> CloudMusicDelayedLoadingState(
                    title = stringResource(R.string.cloud_music_mine_loading),
                    modifier = Modifier.fillMaxSize(),
                )
                CloudSlotState.Error -> CloudMusicBlankState(
                    title = stringResource(R.string.cloud_music_mine_error),
                    subtitle = null,
                    actionText = stringResource(R.string.cloud_music_mine_retry),
                    onActionClick = { librarySlot.reload(Unit, libraryRevision) },
                    modifier = Modifier.fillMaxSize(),
                )
                is CloudSlotState.Success -> {
                    val bundle = current.data
                    val items = remember(bundle, selectedFilter) {
                        buildList {
                            if (selectedFilter != CloudAccountLibraryFilter.Album &&
                                selectedFilter != CloudAccountLibraryFilter.Radio
                            ) {
                                bundle.playlists.forEach { add(CloudAccountLibraryItem.Playlist(it)) }
                            }
                            if (selectedFilter != CloudAccountLibraryFilter.Playlist &&
                                selectedFilter != CloudAccountLibraryFilter.Radio
                            ) {
                                bundle.albums.forEach { add(CloudAccountLibraryItem.Album(it)) }
                            }
                            if (selectedFilter != CloudAccountLibraryFilter.Playlist &&
                                selectedFilter != CloudAccountLibraryFilter.Album
                            ) {
                                bundle.radios.forEach { add(CloudAccountLibraryItem.Radio(it)) }
                            }
                        }
                    }
                    if (bundle.loginRequired || items.isEmpty()) {
                        CloudMusicMineEmpty(modifier = Modifier.fillMaxSize())
                    } else {
                        CloudMusicAccountLibraryList(
                            items = items,
                            selectedPlaylistId = selectedPlaylistId,
                            playbackBarOverlayHeight = playbackBarOverlayHeight,
                            listState = scrollStates.mine,
                            onPlaylistClick = onOpenPlaylist,
                            onAlbumClick = onOpenAlbum,
                            onRadioClick = onOpenRadio,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        }
    }
}

/** 账号库无内容 / 登录失效时的空态。 */
@Composable
private fun CloudMusicMineEmpty(modifier: Modifier = Modifier) {
    CloudMusicBlankState(
        title = stringResource(R.string.cloud_music_blank_no_content),
        subtitle = null,
        modifier = modifier,
    )
}

/** 合并列表：文字两行 + 分隔线，末尾按条数挂计数 footer。 */
@Composable
private fun CloudMusicAccountLibraryList(
    items: List<CloudAccountLibraryItem>,
    selectedPlaylistId: String?,
    playbackBarOverlayHeight: Dp,
    listState: LazyListState,
    onPlaylistClick: (OnlineAccountPlaylist) -> Unit,
    onAlbumClick: (OnlineAlbum) -> Unit,
    onRadioClick: (OnlineRadio) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.background(CloudSurfaceColor),
        contentPadding = PaddingValues(bottom = playbackBarOverlayHeight + 10.dp),
    ) {
        items(
            items = items,
            key = { item ->
                when (item) {
                    is CloudAccountLibraryItem.Playlist -> "playlist:${item.playlist.playlistId}"
                    is CloudAccountLibraryItem.Album -> "album:${item.album.albumId}"
                    is CloudAccountLibraryItem.Radio -> "radio:${item.radio.radioId}"
                }
            },
        ) { item ->
            Column {
                CloudMusicAccountLibraryRow(
                    item = item,
                    highlighted = item is CloudAccountLibraryItem.Playlist &&
                        item.playlist.playlistId == selectedPlaylistId,
                    onClick = {
                        when (item) {
                            is CloudAccountLibraryItem.Playlist -> onPlaylistClick(item.playlist)
                            is CloudAccountLibraryItem.Album -> onAlbumClick(item.album)
                            is CloudAccountLibraryItem.Radio -> onRadioClick(item.radio)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                CloudMusicDivider()
            }
        }
        if (items.size >= CloudAccountLibraryFooterThreshold) {
            item(key = "cloud-mine-library-footer") {
                Text(
                    text = pluralStringResource(
                        R.plurals.cloud_music_account_library_count,
                        items.size,
                        items.size,
                    ),
                    style = TextStyle(
                        fontSize = 15.sp,
                        color = CloudListFooterTextColor,
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 22.dp),
                )
            }
        }
    }
}

/** 账号库行：标题 + 「类型 · 元数据」副标题两行文字，对齐旧版 ListView 行。 */
@Composable
private fun CloudMusicAccountLibraryRow(
    item: CloudAccountLibraryItem,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(CloudTrackRowHeight)
            .cloudMusicPressable(onClick = onClick)
            .padding(horizontal = 15.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = item.displayTitle(),
            style = TextStyle(
                fontSize = 16.sp,
                color = if (highlighted) CloudAccentColor else CloudTrackTitleColor,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.displaySubtitle(),
            style = TextStyle(
                fontSize = 13.sp,
                color = CloudSecondaryTextColor,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/** 四枚筛选胶囊：等宽 28dp 圆角胶囊，选中态强调色 12% 底 + 强调色文字。 */
@Composable
private fun CloudMusicFilterBar(
    selectedFilter: CloudAccountLibraryFilter,
    onFilterChange: (CloudAccountLibraryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CloudAccountLibraryFilter.entries.forEach { filter ->
            val selected = filter == selectedFilter
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (selected) CloudAccentColor.copy(alpha = 0.12f)
                        else CloudFilterChipIdleBackgroundColor,
                    )
                    .cloudMusicPressable(onClick = { onFilterChange(filter) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(filter.labelRes),
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = if (selected) CloudAccentColor else CloudFilterChipIdleTextColor,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 行标题：喜欢的歌固定显示为「我喜欢的音乐」，其余用接口标题（对齐旧版 displayTitle）。 */
@Composable
private fun CloudAccountLibraryItem.displayTitle(): String {
    return when (this) {
        is CloudAccountLibraryItem.Playlist -> if (playlist.isLikedSongs) {
            stringResource(R.string.cloud_music_liked_songs_entry)
        } else {
            playlist.title
        }
        is CloudAccountLibraryItem.Album -> album.title
        is CloudAccountLibraryItem.Radio -> radio.title
    }
}

/** 行副标题：类型前缀 + 元数据，用「 · 」连接（对齐旧版 displaySubtitle）。 */
@Composable
private fun CloudAccountLibraryItem.displaySubtitle(): String {
    val parts = when (this) {
        is CloudAccountLibraryItem.Playlist -> listOf(
            stringResource(R.string.cloud_music_account_playlist_label),
            stringResource(R.string.cloud_music_playlist_track_count, playlist.trackCount),
        )
        is CloudAccountLibraryItem.Album -> listOfNotNull(
            stringResource(R.string.cloud_music_account_album_label),
            cloudAlbumSubtitle(album) ?: stringResource(R.string.cloud_music_provider_netease),
        )
        is CloudAccountLibraryItem.Radio -> listOfNotNull(
            stringResource(R.string.cloud_music_account_radio_label),
            cloudRadioSubtitle(radio),
        )
    }
    return parts.joinToString(" · ")
}

/** 用户信息行：圆形头像 + 昵称。昵称缺失时回退到页面标题文案。 */
@Composable
private fun CloudMusicMineUserInfoRow(
    nickname: String,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CloudSurfaceColor)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CloudMusicCoverImage(
            imageUrl = avatarUrl,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(28.dp)),
        )
        Text(
            text = nickname.ifBlank { stringResource(R.string.cloud_music_mine_title) },
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = CloudTrackTitleColor,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
