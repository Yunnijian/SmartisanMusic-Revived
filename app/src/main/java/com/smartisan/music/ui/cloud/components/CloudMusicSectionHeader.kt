package com.smartisan.music.ui.cloud.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R

/** 云音乐分区标题：左侧强调色短竖条 + 标题，与旧版云音乐页一致。 */
@Composable
internal fun CloudMusicSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(CloudSectionTitleHeight)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(CloudAccentColor),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = CloudTrackTitleColor,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 云音乐列表分隔线：0.67dp 浅灰线。 */
@Composable
internal fun CloudMusicDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(0.67.dp)
            .background(CloudDividerColor),
    )
}

/**
 * 首页分区标题（带右侧动作）：左竖条 + 标题，右侧 [actionText] + 箭头，整行可点。
 * 对齐旧版 CloudHomeSectionHeader：actionText 为 null 时退化为纯标题。
 */
@Composable
internal fun CloudHomeSectionHeader(
    title: String,
    actionText: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(CloudSectionTitleHeight)
            .clickable(
                enabled = onClick != null,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = { onClick?.invoke() },
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = if (actionText == null) 0.dp else 72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(CloudAccentColor),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = CloudTrackTitleColor,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (actionText != null) {
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = actionText,
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = CloudAccentColor,
                    ),
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.width(2.dp))
                Image(
                    painter = painterResource(R.drawable.arrow3),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
