package com.smartisan.music.ui.cloud.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartisan.music.R
import com.smartisan.music.data.online.NeteaseDailyStyleCategory
import com.smartisan.music.data.online.NeteaseDailyStyleSelection
import com.smartisan.music.data.online.NeteaseDailyStyleTag

private val CloudDailyStyleChipCornerRadius = 14.dp
private val CloudDailyStyleRowHeight = 44.dp
private val CloudDailyStylePickerMaxHeight = 340.dp
private val CloudDailyStyleCategoryWidth = 88.dp

/**
 * 每日推荐风格入口胶囊：显示当前风格，未选过时显示入口文案，点击打开风格选择层。
 *
 * 对齐官方「每日推荐」区块标题行右侧的风格入口。
 */
@Composable
internal fun CloudDailyStyleChip(
    selection: NeteaseDailyStyleSelection?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = selection?.tagName ?: stringResource(R.string.cloud_daily_style_action)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(CloudDailyStyleChipCornerRadius))
            .background(CloudFilterChipIdleBackgroundColor)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 12.sp, color = CloudFilterChipIdleTextColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 风格选择层：左栏分类（曲风/语种/情绪/场景/主题），右栏该分类下的标签，选中即保存并关闭。
 *
 * 保存是账号级操作，会同时改变官方 App 的每日推荐风格，因此不做本地乐观更新——
 * 由调用方在保存成功后整份重拉列表。
 *
 * 面板高度按实际行数算（行数取左右两栏较大者），超过 [CloudDailyStylePickerMaxHeight] 才封顶。
 * 不用 IntrinsicSize：两栏都是 LazyColumn，惰性列表不支持固有尺寸测量。
 */
@Composable
internal fun CloudDailyStylePickerOverlay(
    categories: List<NeteaseDailyStyleCategory>,
    selection: NeteaseDailyStyleSelection?,
    loading: Boolean,
    onConfirm: (NeteaseDailyStyleTag) -> Unit,
    onDismiss: () -> Unit,
) {
    // 默认落在当前风格所属分类，没有则取第一个。
    val initialCategory = remember(categories, selection) {
        categories.firstOrNull { it.categoryId == selection?.categoryId } ?: categories.firstOrNull()
    }
    var activeCategory by remember(initialCategory) { mutableStateOf(initialCategory) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CloudSurfaceColor),
        ) {
            CloudMusicSectionTitle(
                title = stringResource(R.string.cloud_daily_style_title),
                modifier = Modifier.fillMaxWidth(),
            )
            val current = activeCategory
            if (current == null) {
                // 面板尺寸固定，避免加载完成的一瞬间高度跳变。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CloudDailyStylePickerMaxHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            if (loading) R.string.cloud_music_home_loading
                            else R.string.cloud_music_home_error,
                        ),
                        style = TextStyle(fontSize = 13.sp, color = CloudSecondaryTextColor),
                    )
                }
                return@Column
            }
            val rowCount = maxOf(categories.size, current.tags.size)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(
                        (rowCount * CloudDailyStyleRowHeight.value + 12f)
                            .coerceAtMost(CloudDailyStylePickerMaxHeight.value)
                            .dp,
                    ),
            ) {
                LazyColumn(modifier = Modifier.width(CloudDailyStyleCategoryWidth).fillMaxHeight()) {
                    items(items = categories, key = { it.categoryId }) { category ->
                        val active = category.categoryId == current.categoryId
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(CloudDailyStyleRowHeight)
                                .background(
                                    if (active) CloudAccentColor.copy(alpha = 0.12f)
                                    else CloudSurfaceColor,
                                )
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = { activeCategory = category },
                                )
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = category.name,
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                                    color = if (active) CloudAccentColor else CloudTrackTitleColor,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .width(0.67.dp)
                        .fillMaxHeight()
                        .background(CloudDividerColor),
                )
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = current.tags,
                        key = { tag -> "${tag.categoryId}:${tag.tagId}" },
                    ) { tag ->
                        val active = selection?.tagId == tag.tagId
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(CloudDailyStyleRowHeight)
                                .clip(RoundedCornerShape(CloudDailyStyleChipCornerRadius))
                                .background(
                                    if (active) CloudAccentColor.copy(alpha = 0.12f)
                                    else CloudFilterChipIdleBackgroundColor,
                                )
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = { onConfirm(tag) },
                                )
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = tag.name,
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                                    color = if (active) CloudAccentColor else CloudTrackTitleColor,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
