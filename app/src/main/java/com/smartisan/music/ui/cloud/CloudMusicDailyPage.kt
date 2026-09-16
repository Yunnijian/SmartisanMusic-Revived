package com.smartisan.music.ui.cloud

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.data.online.NeteaseAccountActionStatus
import com.smartisan.music.data.online.NeteaseDailyStyleTag
import com.smartisan.music.data.online.OnlineTrack
import com.smartisan.music.data.online.toMediaItem
import com.smartisan.music.data.online.withOnlinePlaybackPlaceholderUri
import com.smartisan.music.playback.LocalPlaybackBrowser
import com.smartisan.music.playback.replaceQueueAndPlay
import com.smartisan.music.ui.cloud.components.CloudAccentColor
import com.smartisan.music.ui.cloud.components.CloudDailyStyleChip
import com.smartisan.music.ui.cloud.components.CloudDailyStylePickerOverlay
import com.smartisan.music.ui.cloud.components.CloudFilterChipIdleBackgroundColor
import com.smartisan.music.ui.cloud.components.CloudFilterChipIdleTextColor
import com.smartisan.music.ui.cloud.components.CloudMusicBlankState
import com.smartisan.music.ui.cloud.components.CloudMusicDelayedLoadingState
import com.smartisan.music.ui.cloud.components.CloudMusicDivider
import com.smartisan.music.ui.cloud.components.CloudMusicSectionTitle
import com.smartisan.music.ui.cloud.components.CloudMusicTrackRow
import com.smartisan.music.ui.cloud.components.CloudPageBackgroundColor
import com.smartisan.music.ui.cloud.components.CloudPullRefresh
import com.smartisan.music.ui.cloud.components.CloudSurfaceColor
import com.smartisan.music.ui.cloud.components.cloudMusicPressable
import kotlinx.coroutines.launch

/** 「每日推荐」整页的两个并列 tab（对齐官方：默认推荐 / 风格推荐）。 */
internal enum class CloudDailyTab(val labelRes: Int) {
    Default(R.string.cloud_music_section_daily_default),
    Style(R.string.cloud_music_section_daily_style),
}

/**
 * 「每日推荐」整页：顶栏 + 默认推荐/风格推荐双 tab。
 *
 * 默认推荐复用首页的 [CloudMusicDataStore.home] 槽（同一份日推，不再联网）；
 * 风格推荐走独立的 [CloudMusicDataStore.dailyStyleHome] 槽，首次切到该 tab 才加载，
 * 风格入口胶囊只出现在风格 tab 内，切风格后由宿主重拉该槽。
 */
@Composable
internal fun CloudMusicDailyPage(
    data: CloudMusicDataStore,
    scrollStates: CloudMusicScrollStates,
    active: Boolean,
    playbackBarOverlayHeight: Dp,
    tab: CloudDailyTab,
    onTabChange: (CloudDailyTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val homeSlot = data.home
    val styleSlot = data.dailyStyleHome
    val styleCategoriesSlot = data.dailyStyles

    // 风格选择层：打开时才加载分类，选中即保存并重拉风格曲目。
    var stylePickerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(styleCategoriesSlot, stylePickerVisible) {
        if (stylePickerVisible) {
            styleCategoriesSlot.ensureLoaded(Unit)
        }
    }
    // 风格 tab 首次激活才加载风格曲目（默认 tab 不进这个端点）。
    LaunchedEffect(styleSlot, active, tab) {
        if (active && tab == CloudDailyTab.Style) {
            styleSlot.ensureLoaded(Unit)
        }
    }
    LaunchedEffect(homeSlot, active) {
        if (active && tab == CloudDailyTab.Default) {
            homeSlot.ensureLoaded(Unit)
        }
    }

    fun onStyleSelected(tag: NeteaseDailyStyleTag) {
        stylePickerVisible = false
        scope.launch {
            val status = data.switchDailyStyle(tag.categoryId, tag.tagId)
            val message = when (status) {
                NeteaseAccountActionStatus.Success -> null
                NeteaseAccountActionStatus.RequiresLogin ->
                    context.getString(R.string.cloud_music_detail_login_required)
                else -> context.getString(R.string.cloud_music_action_failed)
            }
            message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
    }

    // 刷新收尾看当前 tab 那个槽的完成代数（两个槽类型不同，分开取）。
    var refreshRequested by remember { mutableStateOf(false) }
    var refreshVersion by remember { mutableIntStateOf(-1) }
    val slotVersion = when (tab) {
        CloudDailyTab.Default -> homeSlot.version
        CloudDailyTab.Style -> styleSlot.version
    }
    LaunchedEffect(slotVersion) {
        if (refreshRequested && slotVersion != refreshVersion) {
            refreshRequested = false
        }
    }
    fun startRefresh() {
        refreshRequested = true
        refreshVersion = slotVersion
        when (tab) {
            CloudDailyTab.Default -> data.refreshHome()
            CloudDailyTab.Style -> styleSlot.reload(Unit)
        }
    }

    val listState = if (tab == CloudDailyTab.Default) scrollStates.dailyDefault else scrollStates.dailyStyle
    CloudPullRefresh(
        refreshing = refreshRequested,
        onRefresh = ::startRefresh,
        canChildScrollUp = {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > 0
        },
        modifier = modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize().background(CloudPageBackgroundColor)) {
            CloudMusicSectionTitle(
                title = stringResource(R.string.cloud_music_section_daily_tracks),
                modifier = Modifier.fillMaxWidth(),
            )
            CloudDailyTabBar(
                selected = tab,
                onSelect = onTabChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (tab) {
                    CloudDailyTab.Default -> when (val state = homeSlot.state(Unit)) {
                        CloudSlotState.Loading -> DailyLoading()
                        CloudSlotState.Error -> DailyError(onRetry = { homeSlot.reload(Unit) })
                        is CloudSlotState.Success -> CloudDailyTrackList(
                            tracks = state.data.dailyTracks,
                            listState = listState,
                            playbackBarOverlayHeight = playbackBarOverlayHeight,
                            styleChip = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    CloudDailyTab.Style -> when (val state = styleSlot.state(Unit)) {
                        CloudSlotState.Loading -> DailyLoading()
                        CloudSlotState.Error -> DailyError(onRetry = { styleSlot.reload(Unit) })
                        // 槽本身成功但内容为 null：登录态失效或风格接口无数据，按错误处理。
                        is CloudSlotState.Success -> state.data?.let { styleHome ->
                            CloudDailyTrackList(
                                tracks = styleHome.tracks,
                                listState = listState,
                                playbackBarOverlayHeight = playbackBarOverlayHeight,
                                styleChip = {
                                    CloudDailyStyleChip(
                                        selection = styleHome.selection,
                                        onClick = { stylePickerVisible = true },
                                    )
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } ?: DailyError(onRetry = { styleSlot.reload(Unit) })
                    }
                }
            }
        }
    }

    if (stylePickerVisible) {
        CloudDailyStylePickerOverlay(
            categories = (styleCategoriesSlot.state(Unit) as? CloudSlotState.Success)?.data.orEmpty(),
            selection = ((styleSlot.state(Unit) as? CloudSlotState.Success)?.data)?.selection,
            loading = styleCategoriesSlot.state(Unit) is CloudSlotState.Loading,
            onConfirm = ::onStyleSelected,
            onDismiss = { stylePickerVisible = false },
        )
    }
}

/** 分段控件：等宽两段，选中段强调色 12% 底 + 强调色文字（与「我的」页筛选胶囊同款）。 */
@Composable
private fun CloudDailyTabBar(
    selected: CloudDailyTab,
    onSelect: (CloudDailyTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CloudDailyTab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        if (active) CloudAccentColor.copy(alpha = 0.12f)
                        else CloudFilterChipIdleBackgroundColor,
                    )
                    .cloudMusicPressable(onClick = { onSelect(tab) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(tab.labelRes),
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = if (active) CloudAccentColor else CloudFilterChipIdleTextColor,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DailyLoading() {
    CloudMusicDelayedLoadingState(
        title = stringResource(R.string.cloud_music_featured_loading),
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun DailyError(onRetry: () -> Unit) {
    CloudMusicBlankState(
        title = stringResource(R.string.cloud_music_featured_error),
        subtitle = null,
        actionText = stringResource(R.string.cloud_music_retry),
        onActionClick = onRetry,
        modifier = Modifier.fillMaxSize(),
    )
}

/** 曲目竖排列表；[styleChip] 非空时插在首项上方（风格 tab 专属的风格入口）。 */
@Composable
private fun CloudDailyTrackList(
    tracks: List<OnlineTrack>,
    listState: LazyListState,
    playbackBarOverlayHeight: Dp,
    styleChip: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val playbackBrowser = LocalPlaybackBrowser.current
    if (tracks.isEmpty() && styleChip == null) {
        CloudMusicBlankState(
            title = stringResource(R.string.cloud_music_empty_title),
            subtitle = stringResource(R.string.cloud_music_empty_subtitle),
            modifier = modifier,
        )
        return
    }
    val playableItems = remember(tracks) {
        tracks.map { track -> track.toMediaItem().withOnlinePlaybackPlaceholderUri() }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.background(CloudSurfaceColor),
        contentPadding = PaddingValues(bottom = playbackBarOverlayHeight + 10.dp),
    ) {
        if (styleChip != null) {
            item(key = "cloud-daily-style-chip") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    styleChip()
                }
            }
            item(key = "cloud-daily-style-divider") { CloudMusicDivider() }
        }
        itemsIndexed(
            items = tracks,
            key = { index, track -> "${track.mediaId}:$index" },
        ) { index, track ->
            Column {
                CloudMusicTrackRow(
                    track = track,
                    onClick = {
                        playbackBrowser?.replaceQueueAndPlay(
                            mediaItems = playableItems,
                            startIndex = index,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                CloudMusicDivider()
            }
        }
    }
}
