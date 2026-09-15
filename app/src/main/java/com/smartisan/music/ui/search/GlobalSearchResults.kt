package com.smartisan.music.ui.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import com.smartisan.music.R
import com.smartisan.music.playback.artworkRequestKey
import com.smartisan.music.ui.album.AlbumSummary
import com.smartisan.music.ui.artist.ArtistSummary
import com.smartisan.music.ui.components.SmartisanDrawableBackground
import com.smartisan.music.ui.components.collectSmartisanPressedAsState
import com.smartisan.music.ui.components.loadArtworkThumbnail
import com.smartisan.music.ui.components.smartisanPressedTextColor

@Composable
internal fun SearchResultsPage(
    results: SearchResults,
    currentMediaId: String?,
    showPlaybackBar: Boolean,
    onSongClick: (MediaItem) -> Unit,
    onAlbumClick: (AlbumSummary) -> Unit,
    onArtistClick: (ArtistSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        SmartisanDrawableBackground(
            drawableRes = R.drawable.account_background,
            modifier = Modifier.matchParentSize(),
        )
        if (!results.hasResults) {
            SearchNoResultState(modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        bottom =
                            if (showPlaybackBar) SearchPlaybackBarReservedHeight + 16.dp else 16.dp
                    ),
            ) {
                appendSuggestedResults(
                    songs = results.songs.take(2),
                    currentMediaId = currentMediaId,
                    onSongClick = onSongClick,
                )
                appendAlbumResults(
                    albums = results.albums,
                    onAlbumClick = onAlbumClick,
                )
                appendArtistResults(
                    artists = results.artists,
                    onArtistClick = onArtistClick,
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.appendSuggestedResults(
    songs: List<MediaItem>,
    currentMediaId: String?,
    onSongClick: (MediaItem) -> Unit,
) {
    if (songs.isEmpty()) return
    item(key = "suggested-header") { SearchSectionHeader(title = R.string.suggestion) }
    items(
        items = songs,
        key = { item -> "suggested-${item.mediaId}" },
    ) { item ->
        SearchSongRow(
            mediaItem = item,
            selected = item.mediaId == currentMediaId,
            onClick = { onSongClick(item) },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.appendAlbumResults(
    albums: List<AlbumSummary>,
    onAlbumClick: (AlbumSummary) -> Unit,
) {
    if (albums.isEmpty()) return
    item(key = "albums-header") { SearchSectionHeader(title = R.string.search_tab_albums) }
    items(
        items = albums,
        key = { album -> "album-${album.id}" },
    ) { album ->
        SearchEntityRow(
            title = album.title,
            subtitle = album.artist,
            representative = album.representative,
            titleColor = SearchResultHighlightColor,
            subtitleColor = SearchResultHighlightColor,
            action = SearchEntityAction.Source,
            onClick = { onAlbumClick(album) },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.appendArtistResults(
    artists: List<ArtistSummary>,
    onArtistClick: (ArtistSummary) -> Unit,
) {
    if (artists.isEmpty()) return
    item(key = "artists-header") { SearchSectionHeader(title = R.string.search_tab_artists) }
    items(
        items = artists,
        key = { artist -> "artist-${artist.id}" },
    ) { artist ->
        SearchEntityRow(
            title = artist.name,
            subtitle = null,
            representative = artist.representative,
            titleColor = SearchResultHighlightColor,
            action = SearchEntityAction.Source,
            onClick = { onArtistClick(artist) },
        )
    }
}

@Composable
private fun SearchSectionHeader(title: Int) {
    Box(modifier = Modifier.fillMaxWidth().height(SearchSectionHeaderHeight)) {
        SmartisanDrawableBackground(
            drawableRes = R.drawable.home_recommend_title_noline_bg,
            modifier = Modifier.matchParentSize(),
        )
        Text(
            text = stringResource(title),
            style = TextStyle(fontSize = 15.sp, color = SearchSectionTitleColor),
            modifier =
                Modifier.align(Alignment.CenterStart)
                    .padding(start = SearchSectionHeaderStartPadding),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(SearchDividerColor)
        )
    }
}

@Composable
private fun SearchSongRow(
    mediaItem: MediaItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SearchEntityRow(
        title =
            mediaItem.mediaMetadata.title?.toString()
                ?: mediaItem.mediaMetadata.displayTitle?.toString()
                ?: stringResource(R.string.unknown_song_title),
        subtitle =
            mediaItem.mediaMetadata.artist?.toString() ?: stringResource(R.string.unknown_artist),
        representative = mediaItem,
        titleColor = if (selected) SearchSongPlayingColor else SearchSongTitleColor,
        onClick = onClick,
    )
}

@Composable
private fun SearchEntityRow(
    title: String,
    subtitle: String?,
    representative: MediaItem,
    onClick: () -> Unit,
    titleColor: Color = SearchSongTitleColor,
    subtitleColor: Color = SearchSubtitleColor,
    action: SearchEntityAction = SearchEntityAction.More,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectSmartisanPressedAsState()

    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(SearchResultRowHeight)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
    ) {
        SmartisanDrawableBackground(
            drawableRes =
                if (pressed) R.drawable.list_item_bgwithoutphoto_down else R.color.surface_card,
            modifier = Modifier.matchParentSize(),
        )
        Row(
            modifier = Modifier.fillMaxSize().padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.width(SearchResultArtworkFrameWidth)
                        .height(SearchResultRowHeight)
                        .padding(start = 12.dp, top = 5.dp, bottom = 5.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                SearchArtwork(
                    mediaItem = representative,
                    modifier = Modifier.size(SearchResultArtworkSize),
                )
            }
            Column(
                modifier = Modifier.weight(1f).padding(start = 12.dp, end = 10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    style = SearchPrimaryTextStyle.copy(color = smartisanPressedTextColor(titleColor, pressed)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        style = SearchSecondaryTextStyle.copy(color = smartisanPressedTextColor(subtitleColor, pressed)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Box(
                modifier = Modifier.width(SearchResultActionWidth).height(SearchResultRowHeight),
                contentAlignment = Alignment.CenterEnd,
            ) {
                val iconRes =
                    when (action) {
                        SearchEntityAction.More ->
                            if (pressed) R.drawable.btn_more_white else R.drawable.btn_more
                        SearchEntityAction.Source ->
                            if (pressed) {
                                R.drawable.local_phone_icon_white
                            } else {
                                R.drawable.local_phone_icon
                            }
                    }
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier =
                        Modifier.size(
                            if (action == SearchEntityAction.More) {
                                SearchResultMoreIconSize
                            } else {
                                SearchResultSourceIconSize
                            }
                        ),
                )
            }
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SearchDividerColor))
}

@Composable
private fun SearchNoResultState(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colorResource(R.color.page_background))
                .padding(top = SearchNoResultTopPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.blank_search),
            contentDescription = null,
            modifier = Modifier.size(SearchNoResultArtworkSize),
        )
        Text(
            text = stringResource(R.string.search_no_result),
            style = TextStyle(fontSize = 23.sp, color = SearchEmptyTextColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 25.dp),
        )
    }
}

@Composable
private fun SearchArtwork(
    mediaItem: MediaItem,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val artworkRequestKey = mediaItem.artworkRequestKey()
    val artwork by
        produceState<ImageBitmap?>(
            initialValue = null,
            artworkRequestKey,
        ) {
            value = loadArtworkThumbnail(context, mediaItem, SearchArtworkDecodeSize)
        }

    if (artwork != null) {
        Box(modifier = modifier) {
            Image(
                bitmap = artwork!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Image(
                painter = painterResource(R.drawable.mask_albumcover_list),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize(),
            )
        }
    } else {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.noalbumcover_120),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Image(
                painter = painterResource(R.drawable.mask_albumcover_list),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
