package com.smartisan.music.ui.cloud.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.data.online.OnlineArtist
import com.smartisan.music.data.online.OnlinePlaylist
import com.smartisan.music.data.online.OnlineRadio

/**
 * 竖排封面结果列表：48dp 圆角封面 + 标题/副标题两行，行高分隔线。
 * 供「查看全部」整页与搜索分类结果复用（对齐旧版 CloudSearchCoverResultList）。
 */
@Composable
internal fun <T> CloudMusicVerticalCoverList(
    items: List<T>,
    playbackBarOverlayHeight: Dp,
    title: (T) -> String,
    subtitle: @Composable (T) -> String?,
    imageUrl: (T) -> String?,
    onItemClick: (T) -> Unit,
    itemKey: (T) -> Any,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.background(CloudSurfaceColor),
        contentPadding = PaddingValues(bottom = playbackBarOverlayHeight + 10.dp),
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> itemKey(item) },
        ) { _, item ->
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CloudSearchCoverRowHeight)
                        .cloudMusicPressable(onClick = { onItemClick(item) })
                        .padding(start = 12.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CloudMusicCoverImage(
                        imageUrl = imageUrl(item),
                        modifier = Modifier
                            .size(CloudSearchCoverArtworkSize)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                    Column(
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = title(item),
                            style = TextStyle(
                                fontSize = 15.sp,
                                color = CloudTrackTitleColor,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        subtitle(item)?.takeIf(String::isNotBlank)?.let { subtitleText ->
                            Text(
                                text = subtitleText,
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
                CloudMusicDivider()
            }
        }
    }
}

/** 歌手竖排列表：名字 + 别名/计数副标题两行文字行（旧版为 ListView 文字行）。 */
@Composable
internal fun CloudMusicArtistList(
    artists: List<OnlineArtist>,
    playbackBarOverlayHeight: Dp,
    onArtistClick: (OnlineArtist) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.background(CloudSurfaceColor),
        contentPadding = PaddingValues(bottom = playbackBarOverlayHeight + 10.dp),
    ) {
        itemsIndexed(
            items = artists,
            key = { _, artist -> artist.artistId },
        ) { _, artist ->
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CloudTrackRowHeight)
                        .cloudMusicPressable(onClick = { onArtistClick(artist) })
                        .padding(horizontal = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = artist.name,
                            style = TextStyle(
                                fontSize = 15.sp,
                                color = CloudTrackTitleColor,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = cloudArtistSubtitle(artist),
                            style = TextStyle(
                                fontSize = 11.sp,
                                color = CloudSecondaryTextColor,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                }
                CloudMusicDivider()
            }
        }
    }
}

/** 歌手副标题：别名 · 歌曲/专辑计数，全缺时回退提供方文案（对齐旧版 subtitleText）。 */
@Composable
internal fun cloudArtistSubtitle(artist: OnlineArtist): String {
    val aliasText = artist.subtitle?.takeIf(String::isNotBlank)
    val countText = when {
        artist.trackCount > 0 && artist.albumCount > 0 -> stringResource(
            R.string.cloud_music_artist_track_album_count,
            artist.trackCount,
            artist.albumCount,
        )
        artist.trackCount > 0 -> stringResource(
            R.string.cloud_music_artist_track_count,
            artist.trackCount,
        )
        artist.albumCount > 0 -> stringResource(
            R.string.cloud_music_artist_album_count,
            artist.albumCount,
        )
        else -> null
    }
    return listOfNotNull(aliasText, countText)
        .joinToString(" · ")
        .ifBlank { stringResource(R.string.cloud_music_artist_provider_netease) }
}

/** 歌单副标题：自带副标题 → 曲目数 → 播放数（≥1 万按万次缩写，对齐旧版 homeSubtitle）。 */
@Composable
internal fun cloudPlaylistSubtitle(playlist: OnlinePlaylist): String? {
    playlist.subtitle?.takeIf(String::isNotBlank)?.let { return it }
    return when {
        playlist.trackCount > 0 -> pluralStringResource(
            R.plurals.track_count,
            playlist.trackCount,
            playlist.trackCount,
        )
        playlist.playCount >= 10_000L -> stringResource(
            R.string.cloud_music_play_count_wan,
            playlist.playCount / 10_000L,
        )
        playlist.playCount > 0L -> pluralStringResource(
            R.plurals.cloud_music_play_count,
            playlist.playCount.toInt(),
            playlist.playCount,
        )
        else -> null
    }
}

/** 电台副标题：分类 · 节目数 · 播放数，全缺时回退自带副标题/提供方文案（对齐旧版 subtitleText）。 */
@Composable
internal fun cloudRadioSubtitle(radio: OnlineRadio): String {
    val parts = listOfNotNull(
        radio.category?.takeIf(String::isNotBlank),
        radio.programCount.takeIf { it > 0 }?.let { count ->
            stringResource(R.string.cloud_music_radio_program_count, count)
        },
        radio.playCount.takeIf { it > 0L }?.let { count ->
            pluralStringResource(R.plurals.cloud_music_play_count, count.toInt(), count)
        },
    )
    return parts.joinToString(" · ").ifBlank {
        radio.subtitle?.takeIf(String::isNotBlank)
            ?: stringResource(R.string.cloud_music_provider_netease)
    }
}
