package com.smartisan.music.ui.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smartisan.music.R
import com.smartisan.music.data.online.OnlineRadio
import com.smartisan.music.data.online.OnlineRadioHome
import com.smartisan.music.data.online.OnlineTrack
import com.smartisan.music.data.online.toMediaItem
import com.smartisan.music.data.online.withOnlinePlaybackPlaceholderUri
import com.smartisan.music.playback.LocalPlaybackBrowser
import com.smartisan.music.playback.replaceQueueAndPlay
import com.smartisan.music.ui.cloud.components.CloudHomeSectionHeader
import com.smartisan.music.ui.cloud.components.CloudMusicBlankState
import com.smartisan.music.ui.cloud.components.CloudMusicCoverCard
import com.smartisan.music.ui.cloud.components.CloudMusicCoverCardSection
import com.smartisan.music.ui.cloud.components.CloudMusicDelayedLoadingState
import com.smartisan.music.ui.cloud.components.CloudMusicDivider
import com.smartisan.music.ui.cloud.components.CloudMusicSectionTitle
import com.smartisan.music.ui.cloud.components.CloudMusicTrackRow
import com.smartisan.music.ui.cloud.components.CloudMusicVerticalCoverList
import com.smartisan.music.ui.cloud.components.CloudPageBackgroundColor
import com.smartisan.music.ui.cloud.components.CloudPullRefresh
import com.smartisan.music.ui.cloud.components.CloudSurfaceColor
import com.smartisan.music.ui.cloud.components.CloudTrackActionsOverlays
import com.smartisan.music.ui.cloud.components.cloudRadioSubtitle
import com.smartisan.music.ui.cloud.components.rememberCloudTrackActionsState

/** 电台模块内部子页：电台首页 / 热门播客列表 / 推荐节目列表。 */
internal enum class CloudRadioSubPage(val titleRes: Int) {
    Home(R.string.cloud_music_section_radio),
    List(R.string.cloud_music_section_hot_radios),
    Tracks(R.string.cloud_music_section_radio_programs),
}

/** 电台首页推荐节目的预览行数。 */
private const val CloudRadioPreviewTrackCount = 4

/**
 * 电台三页：首页（推荐节目预览 + 热门播客横滑）、热门播客竖排列表、推荐节目完整列表。
 * 数据统一来自宿主级 [CloudMusicDataStore.radio]（featuredRadioHome 端点），
 * 子页切换只换渲染切片，返回电台首页也不重新联网。
 */
@Composable
internal fun CloudMusicRadioPage(
    data: CloudMusicDataStore,
    scrollStates: CloudMusicScrollStates,
    active: Boolean,
    playbackBarOverlayHeight: Dp,
    subPage: CloudRadioSubPage,
    onSubPageChange: (CloudRadioSubPage) -> Unit,
    onOpenRadio: (OnlineRadio) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playbackBrowser = LocalPlaybackBrowser.current
    val trackActionsState = rememberCloudTrackActionsState()
    val radioSlot = data.radio

    LaunchedEffect(radioSlot, active) {
        if (active) {
            radioSlot.ensureLoaded(Unit)
        }
    }

    val currentListState = when (subPage) {
        CloudRadioSubPage.Home -> scrollStates.radioHome
        CloudRadioSubPage.List -> scrollStates.radioList
        CloudRadioSubPage.Tracks -> scrollStates.radioTracks
    }

    // 下拉刷新：请求发出后等槽位离开 Loading 即视为完成。
    var refreshRequested by remember { mutableStateOf(false) }
    var radioRefreshVersion by remember { mutableIntStateOf(-1) }
    val radioState = radioSlot.state(Unit)
    val radioSlotVersion = radioSlot.version
    LaunchedEffect(radioSlotVersion) {
        if (refreshRequested && radioSlotVersion != radioRefreshVersion) {
            refreshRequested = false
        }
    }

    CloudPullRefresh(
        refreshing = refreshRequested,
        onRefresh = {
            refreshRequested = true
            radioRefreshVersion = radioSlot.version
            data.refreshRadio()
        },
        canChildScrollUp = {
            currentListState.firstVisibleItemIndex > 0 ||
                currentListState.firstVisibleItemScrollOffset > 0
        },
        modifier = modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize().background(CloudPageBackgroundColor)) {
        CloudMusicSectionTitle(
            title = stringResource(subPage.titleRes),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (val current = radioState) {
                CloudSlotState.Loading -> CloudMusicDelayedLoadingState(
                    title = stringResource(R.string.cloud_music_radio_loading),
                    modifier = Modifier.fillMaxSize(),
                )
                CloudSlotState.Error -> CloudMusicBlankState(
                    title = stringResource(R.string.cloud_music_radio_error),
                    subtitle = null,
                    actionText = stringResource(R.string.cloud_music_retry),
                    onActionClick = { radioSlot.reload(Unit) },
                    modifier = Modifier.fillMaxSize(),
                )
                is CloudSlotState.Success -> when (subPage) {
                    CloudRadioSubPage.Home -> CloudRadioHomeContent(
                        home = current.data,
                        playbackBarOverlayHeight = playbackBarOverlayHeight,
                        listState = scrollStates.radioHome,
                        onOpenTracks = { onSubPageChange(CloudRadioSubPage.Tracks) },
                        onOpenList = { onSubPageChange(CloudRadioSubPage.List) },
                        onOpenRadio = onOpenRadio,
                        onPlayTracks = { tracks, index ->
                            playbackBrowser?.replaceQueueAndPlay(
                                mediaItems = tracks.map {
                                    it.toMediaItem().withOnlinePlaybackPlaceholderUri()
                                },
                                startIndex = index,
                            )
                        },
                        onTrackMoreClick = trackActionsState::show,
                        modifier = Modifier.fillMaxSize(),
                    )
                    CloudRadioSubPage.List -> CloudMusicVerticalCoverList(
                        items = current.data.radios,
                        playbackBarOverlayHeight = playbackBarOverlayHeight,
                        listState = scrollStates.radioList,
                        title = OnlineRadio::title,
                        subtitle = { radio -> cloudRadioSubtitle(radio) },
                        imageUrl = OnlineRadio::artworkUrl,
                        onItemClick = onOpenRadio,
                        itemKey = OnlineRadio::radioId,
                        modifier = Modifier.fillMaxSize(),
                    )
                    CloudRadioSubPage.Tracks -> CloudRadioTrackList(
                        tracks = current.data.tracks,
                        playbackBarOverlayHeight = playbackBarOverlayHeight,
                        listState = scrollStates.radioTracks,
                        onPlayTracks = { tracks, index ->
                            playbackBrowser?.replaceQueueAndPlay(
                                mediaItems = tracks.map {
                                    it.toMediaItem().withOnlinePlaybackPlaceholderUri()
                                },
                                startIndex = index,
                            )
                        },
                        onTrackMoreClick = trackActionsState::show,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            CloudTrackActionsOverlays(
                state = trackActionsState,
                repository = data.repository,
                editablePlaylist = null,
                onTrackRemoved = {},
                onAccountLibraryChanged = {},
                onAddedToPlaylist = { data.detail.invalidateAll() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        }
    }
}

/** 电台首页：推荐节目预览（标题 + 前 N 行）+ 热门播客横滑区块（带「全部」）。 */
@Composable
private fun CloudRadioHomeContent(
    home: OnlineRadioHome,
    playbackBarOverlayHeight: Dp,
    listState: LazyListState,
    onOpenTracks: () -> Unit,
    onOpenList: () -> Unit,
    onOpenRadio: (OnlineRadio) -> Unit,
    onPlayTracks: (tracks: List<OnlineTrack>, index: Int) -> Unit,
    onTrackMoreClick: (OnlineTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (home.tracks.isEmpty() && home.radios.isEmpty()) {
        CloudMusicBlankState(
            title = stringResource(R.string.cloud_music_radio_empty),
            subtitle = stringResource(R.string.cloud_music_empty_subtitle),
            modifier = modifier,
        )
        return
    }
    val viewAllText = stringResource(R.string.cloud_music_section_view_all)
    val tracks = home.tracks
    LazyColumn(
        state = listState,
        modifier = modifier.background(CloudSurfaceColor),
        contentPadding = PaddingValues(bottom = playbackBarOverlayHeight + 10.dp),
    ) {
        if (tracks.isNotEmpty()) {
            item(key = "cloud-radio-home-tracks-header") {
                CloudHomeSectionHeader(
                    title = stringResource(R.string.cloud_music_section_radio_programs),
                    actionText = viewAllText,
                    onClick = onOpenTracks,
                )
            }
            itemsIndexed(
                items = tracks.take(CloudRadioPreviewTrackCount),
                key = { index, track -> "radio-track:${track.mediaId}:$index" },
            ) { index, track ->
                Column {
                    CloudMusicTrackRow(
                        track = track,
                        onClick = { onPlayTracks(tracks, index) },
                        onMoreClick = { onTrackMoreClick(track) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CloudMusicDivider()
                }
            }
        }
        if (home.radios.isNotEmpty()) {
            item(key = "cloud-radio-home-radios") {
                val radios = home.radios
                CloudMusicCoverCardSection(
                    title = stringResource(R.string.cloud_music_section_hot_radios),
                    actionText = viewAllText,
                    onActionClick = onOpenList,
                ) {
                    itemsIndexed(
                        items = radios,
                        key = { index, radio -> "radio:${radio.radioId}:$index" },
                    ) { _, radio ->
                        CloudMusicCoverCard(
                            imageUrl = radio.artworkUrl,
                            title = radio.title,
                            subtitle = radio.category ?: radio.creator,
                            onClick = { onOpenRadio(radio) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudRadioTrackList(
    tracks: List<OnlineTrack>,
    playbackBarOverlayHeight: Dp,
    listState: LazyListState,
    onPlayTracks: (tracks: List<OnlineTrack>, index: Int) -> Unit,
    onTrackMoreClick: (OnlineTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) {
        CloudMusicBlankState(
            title = stringResource(R.string.cloud_music_radio_empty),
            subtitle = stringResource(R.string.cloud_music_empty_subtitle),
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.background(CloudSurfaceColor),
        contentPadding = PaddingValues(bottom = playbackBarOverlayHeight + 10.dp),
    ) {
        itemsIndexed(
            items = tracks,
            key = { index, track -> "${track.mediaId}:$index" },
        ) { index, track ->
            Column {
                CloudMusicTrackRow(
                    track = track,
                    onClick = { onPlayTracks(tracks, index) },
                    onMoreClick = { onTrackMoreClick(track) },
                    modifier = Modifier.fillMaxWidth(),
                )
                CloudMusicDivider()
            }
        }
    }
}
