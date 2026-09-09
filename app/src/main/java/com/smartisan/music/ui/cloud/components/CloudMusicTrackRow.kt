package com.smartisan.music.ui.cloud.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.data.online.OnlineTrack
import java.util.Locale

/**
 * 云音乐歌曲行：封面（Coil）、歌名、艺人 - 专辑、右侧时长。
 * 结构对齐旧版云音乐搜索结果行，纯 Compose 实现（旧版复用 Legacy ListView 适配器）。
 */
@Composable
internal fun CloudMusicTrackRow(
    track: OnlineTrack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CloudTrackRowHeight)
            .cloudMusicPressable(onClick = onClick)
            .padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CloudMusicCoverImage(
            imageUrl = track.artworkUrl,
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
                text = track.title,
                style = TextStyle(
                    fontSize = 15.sp,
                    color = CloudTrackTitleColor,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.rowSubtitle(),
                style = TextStyle(
                    fontSize = 11.sp,
                    color = CloudSecondaryTextColor,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = track.durationMs.formatTrackDuration(),
            style = TextStyle(
                fontSize = 12.sp,
                color = CloudSecondaryTextColor,
            ),
            maxLines = 1,
        )
    }
}

private fun OnlineTrack.rowSubtitle(): String {
    return listOfNotNull(
        artist.takeIf(String::isNotBlank),
        album?.takeIf(String::isNotBlank),
    )
        .joinToString(" - ")
        .takeIf(String::isNotBlank)
        ?: "网易云音乐"
}

/** 毫秒时长格式化为 m:ss（不足一分钟按 0:ss 展示）。 */
private fun Long.formatTrackDuration(): String {
    if (this <= 0L) {
        return "-:--"
    }
    val totalSeconds = this / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}
