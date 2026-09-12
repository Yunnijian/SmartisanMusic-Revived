package com.smartisan.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// MaterialTheme 用 typography.bodyLarge 兜底 ProvideTextStyle(LocalTextStyle)，
// 只有未显式传 style 的 Material3 Text 会读到它；页面文字一律自带 token 字号，故仅保留这一档。
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
)