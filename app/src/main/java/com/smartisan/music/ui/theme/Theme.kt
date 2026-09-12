package com.smartisan.music.ui.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.res.colorResource
import com.smartisan.music.R

/**
 * 本项目的真实配色不走 Material 主题：页面一律用 colorResource 读 8.1.0 原版 token，
 * 夜间由 values-night / drawable-night 同名资源变体切换，代码里不出现 dark 分支。
 *
 * 全仓没有任何 MaterialTheme.colorScheme / shapes / typography 读取，Material3 组件只有
 * Text 且调用点都显式给了 color 或自带 color 的 style，因此这里不定制 colorScheme，
 * 同时也彻底排除了 Material You 动态取色。
 *
 * 唯一的例外是文本选择色：MaterialTheme 会用 colorScheme 派生 LocalTextSelectionColors，
 * 而 5 处 BasicTextField 都没传 textSelectionConfig。不接管的话夜间会退回固定日间方案，
 * 所以这里直接用品牌蓝显式提供——该 token 深浅主题通用，与原版选区视觉一致。
 */
@Composable
fun MusicTheme(content: @Composable () -> Unit) {
    val selectionAccent = colorResource(R.color.btn_text_color_blue)
    CompositionLocalProvider(
        LocalTextSelectionColors provides
            TextSelectionColors(
                handleColor = selectionAccent,
                backgroundColor = selectionAccent.copy(alpha = 0.4f),
            ),
    ) {
        MaterialTheme(
            typography = Typography,
            content = content,
        )
    }
}
