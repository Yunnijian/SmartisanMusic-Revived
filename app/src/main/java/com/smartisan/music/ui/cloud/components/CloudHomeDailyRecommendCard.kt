package com.smartisan.music.ui.cloud.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.data.online.OnlineTrack
import java.util.Calendar

/**
 * 首页「每日推荐」区块：整块可点的入口卡片（旧版 CloudHomeDailyRecommendCard 规格）。
 *
 * 卡片右上角拼贴最多 3 张封面，点按进入每日推荐整页（[CloudHomeDailyRecommendSection] 的
 * `onClick`），与旧版一致：这一行本身不逐首铺歌，逐首列表在整页里。
 */
@Composable
internal fun CloudHomeDailyRecommendSection(
    title: String,
    tracks: List<OnlineTrack>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) {
        return
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CloudSurfaceColor),
    ) {
        CloudHomeSectionHeader(
            title = title,
            actionText = null,
            onClick = null,
        )
        CloudHomeDailyRecommendCard(
            tracks = tracks,
            title = title,
            countText = stringResource(R.string.cloud_music_playlist_track_count, tracks.size),
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
        CloudMusicDivider()
    }
}

/** 卡片本体：旧版为粉色横向渐变 + 星期 + 标题 + 曲目数 + 3 张叠放封面。 */
@Composable
private fun CloudHomeDailyRecommendCard(
    tracks: List<OnlineTrack>,
    title: String,
    countText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val weekDay = remember {
        val calendar = Calendar.getInstance()
        when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "星期一"
            Calendar.TUESDAY -> "星期二"
            Calendar.WEDNESDAY -> "星期三"
            Calendar.THURSDAY -> "星期四"
            Calendar.FRIDAY -> "星期五"
            Calendar.SATURDAY -> "星期六"
            else -> "星期日"
        }
    }

    Box(
        modifier = modifier
            .height(120.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFFFFF5F5),
                        Color(0xFFFFE8E6),
                    ),
                ),
            )
            .cloudMusicPressable(onClick = onClick)
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .padding(end = 148.dp),
        ) {
            Text(
                text = weekDay,
                style = TextStyle(fontSize = 13.sp, color = CloudAccentColor),
            )
            Text(
                text = title,
                style = TextStyle(fontSize = 18.sp, color = Color(0xE6000000)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = countText,
                style = TextStyle(fontSize = 12.sp, color = CloudSecondaryTextColor),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        val previewTracks = remember(tracks) { tracks.take(3) }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy((-8).dp),
        ) {
            previewTracks.forEach { track ->
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFEDEDED))
                        .border(
                            width = 1.dp,
                            color = Color.White,
                            shape = RoundedCornerShape(4.dp),
                        ),
                ) {
                    CloudMusicCoverImage(
                        imageUrl = track.artworkUrl,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
