package com.smartisan.music.ui.cloud.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 云音乐（网易云垂直切片）视觉令牌，与旧版 3.0.1 云音乐页保持一致的尺寸与颜色。 */
internal val CloudAccentColor = Color(0xFFE65C53)
internal val CloudSecondaryTextColor = Color(0x80000000)
internal val CloudTrackTitleColor = Color(0xCC000000)
internal val CloudPageBackgroundColor = Color(0xFFF8F8F8)

internal val CloudMusicSearchBarHeight = 50.dp
internal val CloudSearchCoverArtworkSize = 48.dp
internal val CloudTrackRowHeight = 60.dp
internal val CloudSectionTitleHeight = 39.dp

/** 搜索输入防抖时长：避免每敲一个字符就发起一次网络请求。 */
internal const val CloudSearchDebounceMs = 350L

/** 封面请求尺寸（px），跟随旧版 param=260y260 的服务端缩放参数。 */
internal const val CloudCoverArtworkSizePx = 260
