package com.smartisan.music.ui.cloud

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.data.online.NeteaseAuthStore
import com.smartisan.music.data.online.OnlineAccountPlaylist
import com.smartisan.music.data.online.OnlineAlbum
import com.smartisan.music.data.online.OnlineMusicProviderRepository
import com.smartisan.music.data.online.OnlineRadio
import com.smartisan.music.ui.cloud.components.CloudAccentColor
import com.smartisan.music.ui.cloud.components.CloudMusicBlankState
import com.smartisan.music.ui.cloud.components.CloudMusicCoverImage
import com.smartisan.music.ui.cloud.components.CloudMusicDelayedLoadingState
import com.smartisan.music.ui.cloud.components.CloudMusicDivider
import com.smartisan.music.ui.cloud.components.CloudPageBackgroundColor
import com.smartisan.music.ui.cloud.components.CloudMusicSearchBarHeight
import com.smartisan.music.ui.cloud.components.CloudSearchCoverArtworkSize
import com.smartisan.music.ui.cloud.components.CloudSecondaryTextColor
import com.smartisan.music.ui.cloud.components.CloudSectionTitleHeight
import com.smartisan.music.ui.cloud.components.CloudSurfaceColor
import com.smartisan.music.ui.cloud.components.CloudTrackRowHeight
import com.smartisan.music.ui.cloud.components.CloudTrackTitleColor
import com.smartisan.music.ui.cloud.components.cloudMusicPressable
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import kotlinx.coroutines.CancellationException

/** "我的"页三个内容分区。切换 tab 时重新拉取对应数据。 */
internal enum class MineTab(val titleRes: Int) {
    Playlists(R.string.cloud_music_mine_playlists),
    Albums(R.string.cloud_music_mine_albums),
    Radios(R.string.cloud_music_mine_radios),
}

/**
 * 单个分区的加载状态机：loading / error / empty / success / 登录失效。
 *
 * 各分支携带强类型的数据列表，渲染时无需再强转。
 */
internal sealed interface MineLoadState {
    data object Loading : MineLoadState
    data object Error : MineLoadState
    data object LoginRequired : MineLoadState
    data object Empty : MineLoadState
    data class PlaylistsLoaded(val items: List<OnlineAccountPlaylist>) : MineLoadState
    data class AlbumsLoaded(val items: List<OnlineAlbum>) : MineLoadState
    data class RadiosLoaded(val items: List<OnlineRadio>) : MineLoadState
}

/**
 * 云音乐“我的”页：顶部返回标题栏 + 用户信息行 + 歌单/专辑/电台三个 tab 切换 + 列表。
 *
 * - 无 ViewModel，状态用 [produceState] 直接发起数据请求；
 * - 三个 tab 各自独立加载，切换 tab 时通过 [produceState] 的 key 重新触发；
 * - 登录失效（接口返回 null）展示登录引导空态；
 * - 列表项为正方形封面 + 标题 + 副标题的封面卡片，风格对齐云音乐首页封面卡片。
 */
@Composable
internal fun CloudMusicMinePage(
    repository: OnlineMusicProviderRepository,
    authStore: NeteaseAuthStore,
    active: Boolean,
    libraryRevision: Int,
    playbackBarOverlayHeight: Dp,
    onOpenPlaylist: (OnlineAccountPlaylist) -> Unit,
    onOpenAlbum: (OnlineAlbum) -> Unit,
    onOpenRadio: (OnlineRadio) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // authState.isLoggedIn 在宿主处已判定为 true 才会进入本页；profile 可能为空（仅有 cookie）。
    val authState = remember { authStore.load() }
    val profile = authState.profile
    val nickname = profile?.nickname.orEmpty()
    val avatarUrl = profile?.avatarUrl

    var selectedTab by rememberSaveable { mutableStateOf(MineTab.Playlists) }
    // 失败重试时递增，让 produceState 在相同 tab 下重新发起请求。
    var retryRevision by remember { mutableStateOf(0) }

    val state by produceState<MineLoadState>(
        initialValue = MineLoadState.Loading,
        selectedTab,
        retryRevision,
        libraryRevision,
    ) {
        value = MineLoadState.Loading
        value = try {
            val items: List<*>? = when (selectedTab) {
                MineTab.Playlists -> repository.accountPlaylists()
                MineTab.Albums -> repository.accountAlbums()
                MineTab.Radios -> repository.accountRadios()
            }
            when {
                items == null -> MineLoadState.LoginRequired
                items.isEmpty() -> MineLoadState.Empty
                selectedTab == MineTab.Playlists ->
                    MineLoadState.PlaylistsLoaded(items.filterIsInstance<OnlineAccountPlaylist>())
                selectedTab == MineTab.Albums ->
                    MineLoadState.AlbumsLoaded(items.filterIsInstance<OnlineAlbum>())
                else ->
                    MineLoadState.RadiosLoaded(items.filterIsInstance<OnlineRadio>())
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            MineLoadState.Error
        }
    }

    Column(modifier = modifier.fillMaxSize().background(CloudPageBackgroundColor)) {
        CloudMusicMineTopBar(
            title = stringResource(R.string.cloud_music_mine_title),
            onBack = onBack,
        )
        CloudMusicMineUserInfoRow(
            nickname = nickname,
            avatarUrl = avatarUrl,
        )
        CloudMusicMineTabRow(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (val currentState = state) {
                MineLoadState.Loading -> CloudMusicDelayedLoadingState(
                    title = stringResource(R.string.cloud_music_mine_loading),
                    modifier = Modifier.fillMaxSize(),
                )
                MineLoadState.Error -> CloudMusicBlankState(
                    title = stringResource(R.string.cloud_music_mine_error),
                    subtitle = null,
                    actionText = stringResource(R.string.cloud_music_mine_retry),
                    onActionClick = { retryRevision += 1 },
                    modifier = Modifier.fillMaxSize(),
                )
                MineLoadState.LoginRequired -> CloudMusicBlankState(
                    title = stringResource(R.string.cloud_music_mine_no_data),
                    subtitle = null,
                    modifier = Modifier.fillMaxSize(),
                )
                MineLoadState.Empty -> CloudMusicBlankState(
                    title = stringResource(R.string.cloud_music_mine_no_data),
                    subtitle = null,
                    modifier = Modifier.fillMaxSize(),
                )
                is MineLoadState.PlaylistsLoaded -> CloudMusicMineList(
                    items = currentState.items,
                    itemKey = { index, item -> "${item.playlistId}:$index" },
                    itemTitle = OnlineAccountPlaylist::title,
                    itemSubtitle = OnlineAccountPlaylist::mineSubtitle,
                    itemArtwork = OnlineAccountPlaylist::artworkUrl,
                    itemOnClick = { item -> onOpenPlaylist(item) },
                    playbackBarOverlayHeight = playbackBarOverlayHeight,
                    modifier = Modifier.fillMaxSize(),
                )
                is MineLoadState.AlbumsLoaded -> CloudMusicMineList(
                    items = currentState.items,
                    itemKey = { index, item -> "${item.albumId}:$index" },
                    itemTitle = OnlineAlbum::title,
                    itemSubtitle = OnlineAlbum::mineSubtitle,
                    itemArtwork = OnlineAlbum::artworkUrl,
                    itemOnClick = { item -> onOpenAlbum(item) },
                    playbackBarOverlayHeight = playbackBarOverlayHeight,
                    modifier = Modifier.fillMaxSize(),
                )
                is MineLoadState.RadiosLoaded -> CloudMusicMineList(
                    items = currentState.items,
                    itemKey = { index, item -> "${item.radioId}:$index" },
                    itemTitle = OnlineRadio::title,
                    itemSubtitle = OnlineRadio::mineSubtitle,
                    itemArtwork = OnlineRadio::artworkUrl,
                    itemOnClick = { item -> onOpenRadio(item) },
                    playbackBarOverlayHeight = playbackBarOverlayHeight,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 顶部标题栏：返回按钮 + “我的音乐”。布局与搜索栏等高，风格与云音乐各页一致。 */
@Composable
private fun CloudMusicMineTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CloudMusicSearchBarHeight)
            .background(CloudSurfaceColor),
    ) {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = CloudMusicSearchBarHeight)
                .cloudMusicPressable(onClick = onBack)
                .align(Alignment.CenterStart),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = rememberSmartisanDrawablePainter(R.drawable.standard_icon_back_selector),
                contentDescription = stringResource(R.string.back),
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            text = title,
            style = TextStyle(
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = CloudTrackTitleColor,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 56.dp, end = 12.dp),
        )
    }
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

/** 三个 tab 切换行：选中态使用强调色 + Medium 字重。 */
@Composable
private fun CloudMusicMineTabRow(
    selectedTab: MineTab,
    onTabSelected: (MineTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CloudSectionTitleHeight)
            .background(CloudSurfaceColor),
    ) {
        MineTab.values().forEach { tab ->
            val selected = tab == selectedTab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(CloudSectionTitleHeight)
                    .cloudMusicPressable(onClick = { onTabSelected(tab) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(tab.titleRes),
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) CloudAccentColor else CloudSecondaryTextColor,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 通用封面卡片列表：正方形封面 + 标题 + 副标题。
 *
 * [itemOnClick] 为 null 时（如电台，后续再实现点击）整行不响应点击。
 */
@Composable
private fun <T> CloudMusicMineList(
    items: List<T>,
    itemKey: (index: Int, item: T) -> Any,
    itemTitle: (item: T) -> String,
    itemSubtitle: @Composable (item: T) -> String,
    itemArtwork: (item: T) -> String?,
    itemOnClick: ((item: T) -> Unit)?,
    playbackBarOverlayHeight: Dp,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.background(CloudSurfaceColor),
        contentPadding = PaddingValues(bottom = playbackBarOverlayHeight + 10.dp),
    ) {
        itemsIndexed(
            items = items,
            key = { index, item -> itemKey(index, item) },
        ) { _, item ->
            Column {
                CloudMusicMineCard(
                    artworkUrl = itemArtwork(item),
                    title = itemTitle(item),
                    subtitle = itemSubtitle(item),
                    onClick = itemOnClick?.let { cb -> { cb(item) } },
                )
                CloudMusicDivider()
            }
        }
    }
}

/** 封面卡片行：正方形封面 + 标题 / 副标题，高度与歌曲行一致。 */
@Composable
private fun CloudMusicMineCard(
    artworkUrl: String?,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val rowModifier = if (onClick != null) {
        modifier.cloudMusicPressable(onClick = onClick)
    } else {
        modifier
    }
    Row(
        modifier = rowModifier
            .fillMaxWidth()
            .height(CloudTrackRowHeight)
            .padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CloudMusicCoverImage(
            imageUrl = artworkUrl,
            modifier = Modifier
                .size(CloudSearchCoverArtworkSize)
                .clip(RoundedCornerShape(6.dp)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 15.sp,
                    color = CloudTrackTitleColor,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = CloudSecondaryTextColor,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun OnlineAccountPlaylist.mineSubtitle(): String {
    subtitle?.takeIf(String::isNotBlank)?.let { return it }
    return if (trackCount > 0) {
        pluralStringResource(R.plurals.cloud_music_count_track_short, trackCount, trackCount)
    } else {
        ""
    }
}

@Composable
private fun OnlineAlbum.mineSubtitle(): String {
    val trackCountText = if (trackCount > 0) {
        pluralStringResource(R.plurals.cloud_music_count_track_short, trackCount, trackCount)
    } else {
        null
    }
    val parts = listOfNotNull(
        artist?.takeIf(String::isNotBlank),
        trackCountText,
    )
    return parts.joinToString(" · ").ifBlank { stringResource(R.string.cloud_music_provider_netease) }
}

@Composable
private fun OnlineRadio.mineSubtitle(): String {
    val programCountText = if (programCount > 0) {
        pluralStringResource(R.plurals.cloud_music_count_program_short, programCount, programCount)
    } else {
        null
    }
    val parts = listOfNotNull(
        creator?.takeIf(String::isNotBlank),
        category?.takeIf(String::isNotBlank),
        programCountText,
    )
    return parts.joinToString(" · ").ifBlank { stringResource(R.string.cloud_music_provider_netease) }
}
