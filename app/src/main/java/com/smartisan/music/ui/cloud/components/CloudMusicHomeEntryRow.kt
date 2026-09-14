package com.smartisan.music.ui.cloud.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R

/** 云音乐顶部五段入口：我的 / 推荐 / 电台 / 歌单 / 艺术家。 */
internal enum class CloudHomeEntry(val labelRes: Int, val iconRes: Int) {
    Mine(R.string.cloud_music_entry_mine, R.drawable.net_icon_my_music),
    Recommend(R.string.cloud_music_entry_recommend, R.drawable.net_icon_recommend),
    Radio(R.string.cloud_music_entry_radio, R.drawable.net_icon_radio),
    Collection(R.string.cloud_music_entry_collection, R.drawable.net_icon_collection),
    Artist(R.string.cloud_music_entry_artist, R.drawable.net_icon_artist),
}

/**
 * 五段入口行：横向可滚的胶囊按钮，选中态强调色文字 + 12% 强调色底。
 * 对齐旧版 CloudMusicHomeEntryRow，是电台/歌单广场/艺术家页的唯一入口通道。
 */
@Composable
internal fun CloudMusicHomeEntryRow(
    selectedEntry: CloudHomeEntry,
    onEntryClick: (CloudHomeEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.background(CloudSurfaceColor)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .height(56.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CloudHomeEntry.entries.forEach { entry ->
                CloudMusicHomeEntryButton(
                    entry = entry,
                    selected = entry == selectedEntry,
                    onClick = { onEntryClick(entry) },
                )
            }
        }
        CloudMusicDivider()
    }
}

@Composable
private fun CloudMusicHomeEntryButton(
    entry: CloudHomeEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (selected) CloudAccentColor else CloudSecondaryTextColor
    val backgroundColor =
        if (selected) CloudAccentColor.copy(alpha = 0.12f) else CloudSurfaceColor

    Row(
        modifier = Modifier
            .height(30.dp)
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(15.dp),
            )
            .cloudMusicPressable(onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(entry.iconRes),
            contentDescription = stringResource(entry.labelRes),
            colorFilter = ColorFilter.tint(contentColor),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(entry.labelRes),
            style = TextStyle(
                fontSize = 11.sp,
                color = contentColor,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}
