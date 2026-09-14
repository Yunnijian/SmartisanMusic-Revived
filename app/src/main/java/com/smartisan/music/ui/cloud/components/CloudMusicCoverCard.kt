package com.smartisan.music.ui.cloud.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 封面卡片默认边长：与旧版云音乐推荐位的方形封面尺寸接近。 */
internal val CloudMusicCoverCardSize = 100.dp

/** 区块内卡片之间的横向间距。 */
internal val CloudMusicCoverCardSpacing = 10.dp

/** 封面卡片圆角。 */
internal val CloudMusicCoverCardCornerRadius = 6.dp

/** 区块标题上下的纵向间距，让分区之间留出呼吸空间。 */
internal val CloudMusicCoverSectionVerticalSpacing = 6.dp

/**
 * 云音乐通用封面卡片：正方形封面 + 标题（1 行省略）+ 副标题（1 行省略），
 * 套用统一的按压缩放动效。供首页各横滑区块复用。
 */
@Composable
internal fun CloudMusicCoverCard(
    imageUrl: String?,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardSize: Dp = CloudMusicCoverCardSize,
) {
    Column(
        modifier = modifier
            .width(cardSize)
            .cloudMusicPressable(onClick = onClick),
    ) {
        CloudMusicCoverImage(
            imageUrl = imageUrl,
            modifier = Modifier
                .size(cardSize)
                .clip(RoundedCornerShape(CloudMusicCoverCardCornerRadius)),
        )
        Text(
            text = title,
            style = TextStyle(
                fontSize = 13.sp,
                color = CloudTrackTitleColor,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = CloudSecondaryTextColor,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 云音乐封面卡片区块容器：[CloudMusicSectionTitle] 标题 + [LazyRow] 横滑封面卡片列表。
 *
 * 区块内容以 [LazyListScope] 形式注入，调用方可在其中使用 `itemsIndexed` 安排任意类型的
 * 封面卡片（歌单 / 专辑 / 艺人 / 歌曲等），与新版框架 AlbumDetailPage 把内容交给
 * `LazyColumn` 调用方的组织方式一致。
 */
@Composable
internal fun CloudMusicCoverCardSection(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = CloudMusicCoverSectionVerticalSpacing),
    ) {
        CloudHomeSectionHeader(
            title = title,
            actionText = actionText,
            onClick = onActionClick,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(CloudMusicCoverCardSpacing),
            content = content,
        )
    }
}
