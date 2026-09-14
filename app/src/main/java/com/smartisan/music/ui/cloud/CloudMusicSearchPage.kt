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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.data.online.OnlineAlbum
import com.smartisan.music.data.online.OnlineArtist
import com.smartisan.music.data.online.OnlinePlaylist
import com.smartisan.music.data.online.OnlineSearchResults
import com.smartisan.music.data.online.OnlineTrack
import com.smartisan.music.data.online.toMediaItem
import com.smartisan.music.data.online.withOnlinePlaybackPlaceholderUri
import com.smartisan.music.playback.LocalPlaybackBrowser
import com.smartisan.music.playback.replaceQueueAndPlay
import com.smartisan.music.ui.cloud.components.CloudAccentColor
import com.smartisan.music.ui.cloud.components.CloudHomeSectionHeader
import com.smartisan.music.ui.cloud.components.CloudMusicArtistList
import com.smartisan.music.ui.cloud.components.CloudMusicBlankState
import com.smartisan.music.ui.cloud.components.CloudMusicCoverCard
import com.smartisan.music.ui.cloud.components.CloudMusicCoverCardSection
import com.smartisan.music.ui.cloud.components.CloudMusicDelayedLoadingState
import com.smartisan.music.ui.cloud.components.CloudMusicDivider
import com.smartisan.music.ui.cloud.components.CloudMusicSearchBarHeight
import com.smartisan.music.ui.cloud.components.CloudMusicTrackRow
import com.smartisan.music.ui.cloud.components.CloudMusicVerticalCoverList
import com.smartisan.music.ui.cloud.components.CloudSearchDebounceMs
import com.smartisan.music.ui.cloud.components.CloudSecondaryTextColor
import com.smartisan.music.ui.cloud.components.CloudSurfaceColor
import com.smartisan.music.ui.cloud.components.CloudTextHintColor
import com.smartisan.music.ui.cloud.components.CloudTrackActionsOverlays
import com.smartisan.music.ui.cloud.components.CloudTrackTitleColor
import com.smartisan.music.ui.cloud.components.cloudAlbumSubtitle
import com.smartisan.music.ui.cloud.components.cloudMusicPressable
import com.smartisan.music.ui.cloud.components.cloudPlaylistSubtitle
import com.smartisan.music.ui.cloud.components.rememberCloudTrackActionsState
import com.smartisan.music.ui.components.SmartisanDrawableBackground
import kotlinx.coroutines.delay

/** 搜索分类：综合聚合视图 + 四个单类列表，对齐旧版五段分类栏。 */
internal enum class CloudSearchCategory(val labelRes: Int) {
    All(R.string.cloud_music_search_tab_all),
    Tracks(R.string.search_tab_songs),
    Artists(R.string.search_tab_artists),
    Albums(R.string.search_tab_albums),
    Playlists(R.string.cloud_music_entry_collection),
}

/** 综合视图里歌曲预览的行数。 */
private const val CloudSearchPreviewTrackCount = 4

/**
 * 云音乐搜索页：搜索框 + 分类栏 + 五类结果（综合/歌曲/艺术家/专辑/歌单）。
 *
 * 数据走宿主级 [CloudMusicDataStore.search] 槽（`searchAll` 一次拿全五类），按 query 缓存：
 * 从结果点进详情时本页会被移出组合，返回时直接复用结果，不重跑搜索也不丢滚动位置。
 * 页内只保留输入防抖。综合视图按分区展示，各分区「全部」切到对应单类列表；
 * 歌曲点击把结果列表作为在线队列交给播放控制器，单曲「更多」复用
 * [CloudTrackActionsOverlays] 的完整动作集。query 与分类选中态均由宿主持有（受控）。
 */
@Composable
internal fun CloudMusicSearchPage(
    query: String,
    onQueryChange: (String) -> Unit,
    data: CloudMusicDataStore,
    scrollStates: CloudMusicScrollStates,
    active: Boolean,
    selectedCategory: CloudSearchCategory,
    onCategoryChange: (CloudSearchCategory) -> Unit,
    playbackBarOverlayHeight: Dp,
    onOpenPlaylist: (OnlinePlaylist) -> Unit,
    onOpenAlbum: (OnlineAlbum) -> Unit,
    onOpenArtist: (OnlineArtist) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playbackBrowser = LocalPlaybackBrowser.current
    val trackActionsState = rememberCloudTrackActionsState()
    val searchSlot = data.search
    val normalizedQuery = query.trim()

    // 输入防抖后触发搜索；query 变化即取消上一次的防抖等待。
    // 非活跃（被详情页覆盖）时不发起搜索，active 恢复后由 key 变化重新触发。
    LaunchedEffect(searchSlot, normalizedQuery, active) {
        if (!active || normalizedQuery.isEmpty()) return@LaunchedEffect
        delay(CloudSearchDebounceMs)
        searchSlot.ensureLoaded(normalizedQuery)
    }

    Column(modifier = modifier.fillMaxSize()) {
        CloudMusicSearchField(
            query = query,
            hint = stringResource(R.string.cloud_music_search_hint_netease),
            active = active,
            onQueryChange = onQueryChange,
            onCancel = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (normalizedQuery.isEmpty()) {
                CloudMusicBlankState(
                    title = stringResource(R.string.cloud_music_empty_title),
                    subtitle = stringResource(R.string.cloud_music_empty_subtitle),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                when (val current = searchSlot.state(normalizedQuery)) {
                    CloudSlotState.Loading -> CloudMusicDelayedLoadingState(
                        title = stringResource(R.string.cloud_music_loading),
                        modifier = Modifier.fillMaxSize(),
                    )
                    CloudSlotState.Error -> CloudMusicBlankState(
                        title = stringResource(R.string.cloud_music_error),
                        subtitle = normalizedQuery,
                        actionText = stringResource(R.string.cloud_music_retry),
                        onActionClick = { searchSlot.reload(normalizedQuery) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    is CloudSlotState.Success -> if (!current.data.hasResults) {
                        CloudMusicBlankState(
                            title = stringResource(R.string.cloud_music_no_result),
                            subtitle = normalizedQuery,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            CloudSearchCategoryBar(
                                selectedCategory = selectedCategory,
                                onCategoryChange = onCategoryChange,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            CloudSearchResultsContent(
                                results = current.data,
                                selectedCategory = selectedCategory,
                                scrollStates = scrollStates,
                                playbackBarOverlayHeight = playbackBarOverlayHeight,
                                onCategoryChange = onCategoryChange,
                                onOpenPlaylist = onOpenPlaylist,
                                onOpenAlbum = onOpenAlbum,
                                onOpenArtist = onOpenArtist,
                                onTrackMoreClick = trackActionsState::show,
                                onPlayTracks = { tracks, index ->
                                    val items = tracks.map {
                                        it.toMediaItem().withOnlinePlaybackPlaceholderUri()
                                    }
                                    playbackBrowser?.replaceQueueAndPlay(
                                        mediaItems = items,
                                        startIndex = index,
                                    )
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
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

/** 分类栏：五段等宽文字 tab，选中态强调色 + 底部短下划线。 */
@Composable
private fun CloudSearchCategoryBar(
    selectedCategory: CloudSearchCategory,
    onCategoryChange: (CloudSearchCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .background(CloudSurfaceColor),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CloudSearchCategory.entries.forEach { category ->
                val selected = category == selectedCategory
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .cloudMusicPressable(onClick = { onCategoryChange(category) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(category.labelRes),
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = if (selected) CloudAccentColor else CloudSecondaryTextColor,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .width(20.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(CloudAccentColor),
                        )
                    }
                }
            }
        }
        CloudMusicDivider()
    }
}

/** 分类内容：综合为分区聚合，其余为单类完整列表。空类显示无结果空态。 */
@Composable
private fun CloudSearchResultsContent(
    results: OnlineSearchResults,
    selectedCategory: CloudSearchCategory,
    scrollStates: CloudMusicScrollStates,
    playbackBarOverlayHeight: Dp,
    onCategoryChange: (CloudSearchCategory) -> Unit,
    onOpenPlaylist: (OnlinePlaylist) -> Unit,
    onOpenAlbum: (OnlineAlbum) -> Unit,
    onOpenArtist: (OnlineArtist) -> Unit,
    onTrackMoreClick: (OnlineTrack) -> Unit,
    onPlayTracks: (tracks: List<OnlineTrack>, index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewAllText = stringResource(R.string.cloud_music_section_view_all)
    when (selectedCategory) {
        CloudSearchCategory.All -> LazyColumn(
            state = scrollStates.search(CloudSearchCategory.All),
            modifier = modifier.background(CloudSurfaceColor),
            contentPadding = PaddingValues(bottom = playbackBarOverlayHeight + 10.dp),
        ) {
            if (results.tracks.isNotEmpty()) {
                item(key = "cloud-search-all-tracks-header") {
                    CloudHomeSectionHeader(
                        title = stringResource(R.string.search_tab_songs),
                        actionText = viewAllText,
                        onClick = { onCategoryChange(CloudSearchCategory.Tracks) },
                    )
                }
                itemsIndexed(
                    items = results.tracks.take(CloudSearchPreviewTrackCount),
                    key = { index, track -> "track:${track.mediaId}:$index" },
                ) { index, track ->
                    Column {
                        CloudMusicTrackRow(
                            track = track,
                            onClick = { onPlayTracks(results.tracks, index) },
                            onMoreClick = { onTrackMoreClick(track) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        CloudMusicDivider()
                    }
                }
            }
            if (results.artists.isNotEmpty()) {
                item(key = "cloud-search-all-artists") {
                    val artists = results.artists
                    CloudMusicCoverCardSection(
                        title = stringResource(R.string.search_tab_artists),
                        actionText = viewAllText,
                        onActionClick = { onCategoryChange(CloudSearchCategory.Artists) },
                    ) {
                        itemsIndexed(
                            items = artists,
                            key = { index, artist -> "artist:${artist.artistId}:$index" },
                        ) { _, artist ->
                            CloudMusicCoverCard(
                                imageUrl = artist.artworkUrl,
                                title = artist.name,
                                subtitle = artist.subtitle,
                                onClick = { onOpenArtist(artist) },
                            )
                        }
                    }
                }
            }
            if (results.albums.isNotEmpty()) {
                item(key = "cloud-search-all-albums") {
                    val albums = results.albums
                    CloudMusicCoverCardSection(
                        title = stringResource(R.string.search_tab_albums),
                        actionText = viewAllText,
                        onActionClick = { onCategoryChange(CloudSearchCategory.Albums) },
                    ) {
                        itemsIndexed(
                            items = albums,
                            key = { index, album -> "album:${album.albumId}:$index" },
                        ) { _, album ->
                            CloudMusicCoverCard(
                                imageUrl = album.artworkUrl,
                                title = album.title,
                                subtitle = album.artist,
                                onClick = { onOpenAlbum(album) },
                            )
                        }
                    }
                }
            }
            if (results.playlists.isNotEmpty()) {
                item(key = "cloud-search-all-playlists") {
                    val playlists = results.playlists
                    CloudMusicCoverCardSection(
                        title = stringResource(R.string.cloud_music_entry_collection),
                        actionText = viewAllText,
                        onActionClick = { onCategoryChange(CloudSearchCategory.Playlists) },
                    ) {
                        itemsIndexed(
                            items = playlists,
                            key = { index, playlist -> "playlist:${playlist.playlistId}:$index" },
                        ) { _, playlist ->
                            CloudMusicCoverCard(
                                imageUrl = playlist.artworkUrl,
                                title = playlist.title,
                                subtitle = playlist.subtitle,
                                onClick = { onOpenPlaylist(playlist) },
                            )
                        }
                    }
                }
            }
        }
        CloudSearchCategory.Tracks -> if (results.tracks.isEmpty()) {
            CloudMusicBlankState(
                title = stringResource(R.string.cloud_music_no_result),
                subtitle = results.query,
                modifier = modifier,
            )
        } else {
            val tracks = results.tracks
            LazyColumn(
                state = scrollStates.search(CloudSearchCategory.Tracks),
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
        CloudSearchCategory.Artists -> if (results.artists.isEmpty()) {
            CloudMusicBlankState(
                title = stringResource(R.string.cloud_music_no_result),
                subtitle = results.query,
                modifier = modifier,
            )
        } else {
            CloudMusicArtistList(
                artists = results.artists,
                playbackBarOverlayHeight = playbackBarOverlayHeight,
                listState = scrollStates.search(CloudSearchCategory.Artists),
                onArtistClick = onOpenArtist,
                modifier = modifier,
            )
        }
        CloudSearchCategory.Albums -> CloudMusicVerticalCoverList(
            items = results.albums,
            playbackBarOverlayHeight = playbackBarOverlayHeight,
            listState = scrollStates.search(CloudSearchCategory.Albums),
            title = OnlineAlbum::title,
            subtitle = { album -> cloudAlbumSubtitle(album) },
            imageUrl = OnlineAlbum::artworkUrl,
            onItemClick = onOpenAlbum,
            itemKey = OnlineAlbum::albumId,
            modifier = modifier,
        )
        CloudSearchCategory.Playlists -> CloudMusicVerticalCoverList(
            items = results.playlists,
            playbackBarOverlayHeight = playbackBarOverlayHeight,
            listState = scrollStates.search(CloudSearchCategory.Playlists),
            title = OnlinePlaylist::title,
            subtitle = { playlist -> cloudPlaylistSubtitle(playlist) },
            imageUrl = OnlinePlaylist::artworkUrl,
            onItemClick = onOpenPlaylist,
            itemKey = OnlinePlaylist::playlistId,
            modifier = modifier,
        )
    }
}

/** 云音乐搜索输入框：沿用原版 search_field 背景 + 左侧放大镜 + 清空按钮 + 取消。 */
@Composable
internal fun CloudMusicSearchField(
    query: String,
    hint: String,
    active: Boolean,
    onQueryChange: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(active) {
        if (active) {
            focusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
        }
    }

    Row(
        modifier = modifier
            .height(CloudMusicSearchBarHeight)
            .background(CloudSurfaceColor)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 15.sp,
                color = CloudTrackTitleColor,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                },
            ),
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .focusRequester(focusRequester),
            decorationBox = { innerTextField ->
                val clearInteractionSource = remember { MutableInteractionSource() }
                val clearPressed by clearInteractionSource.collectIsPressedAsState()
                Box(modifier = Modifier.fillMaxSize()) {
                    SmartisanDrawableBackground(
                        drawableRes = R.drawable.search_field,
                        modifier = Modifier.matchParentSize(),
                    )
                    Image(
                        painter = painterResource(R.drawable.search_bar_left_icon),
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 6.dp)
                            .width(24.dp)
                            .height(30.dp),
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .padding(
                                start = 36.dp,
                                end = if (query.isNotEmpty()) 30.dp else 12.dp,
                            ),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text = hint,
                                style = TextStyle(
                                    fontSize = 15.sp,
                                    color = CloudTextHintColor,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                    if (query.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .align(Alignment.CenterEnd)
                                .clickable(
                                    interactionSource = clearInteractionSource,
                                    indication = null,
                                    onClick = { onQueryChange("") },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(
                                    if (clearPressed) {
                                        R.drawable.text_clear_btn_pressed
                                    } else {
                                        R.drawable.text_clear_btn
                                    },
                                ),
                                contentDescription = stringResource(R.string.clear_search_text),
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                }
            },
        )
        Text(
            text = stringResource(R.string.cloud_music_search_cancel),
            style = TextStyle(
                fontSize = 14.sp,
                color = CloudAccentColor,
            ),
            maxLines = 1,
            modifier = Modifier
                .padding(start = 12.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onCancel,
                ),
        )
    }
}
