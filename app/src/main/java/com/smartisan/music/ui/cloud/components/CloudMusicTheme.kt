package com.smartisan.music.ui.cloud.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 云音乐（网易云垂直切片）视觉令牌，与旧版 3.0.1 云音乐页保持一致的尺寸与颜色。 */
internal val CloudAccentColor = Color(0xFFE65C53)

/**
 * 语义色随系统深浅模式切换。
 *
 * 浅色模式沿用旧版 3.0.1 固定色；深色模式使用对应语义的反向亮度，保证对比度可读。
 */

/** 云音乐页面背景色。 */
internal val CloudPageBackgroundColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF232323) else Color(0xFFF8F8F8)

/** 云音乐表层色（顶栏、列表、底部弹层/对话框面板等白底区域）。 */
internal val CloudSurfaceColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2A2A2A) else Color.White

/** 主文本色（标题、正文主色）。 */
internal val CloudTrackTitleColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xCCFFFFFF) else Color(0xCC000000)

/** 次要文本色（副标题、说明文字）。 */
internal val CloudSecondaryTextColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0x80FFFFFF) else Color(0x80000000)

/** 分隔线。 */
internal val CloudDividerColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF3A3A3A) else Color(0xFFEBEBEB)

/** 搜索框 / 输入框 / 次级按钮底色。 */
internal val CloudSearchFieldBackgroundColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF353535) else Color(0xFFF0F0F0)

/** 输入占位提示色（灰度 hint）。 */
internal val CloudTextHintColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0x66FFFFFF) else Color(0x66000000)

/** 封面加载前 / 无图时的占位底色，深色下略暗。 */
internal val CloudArtworkPlaceholderColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2E2E2E) else Color(0xFFEDEDED)

/** 空态图标 tint 色彩。 */
internal val CloudBlankIconTintColor: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0x8AFFFFFF) else Color(0xFFD0D0D0)

internal val CloudMusicSearchBarHeight = 50.dp
internal val CloudSearchCoverArtworkSize = 48.dp
internal val CloudTrackRowHeight = 60.dp
internal val CloudSectionTitleHeight = 39.dp

/** 搜索输入防抖时长：避免每敲一个字符就发起一次网络请求。 */
internal const val CloudSearchDebounceMs = 350L

/** 封面请求尺寸（px），跟随旧版 param=260y260 的服务端缩放参数。 */
internal const val CloudCoverArtworkSizePx = 260
