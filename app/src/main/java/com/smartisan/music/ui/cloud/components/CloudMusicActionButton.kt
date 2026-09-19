package com.smartisan.music.ui.cloud.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import com.smartisan.music.R
import com.smartisan.music.ui.components.collectSmartisanPressedAsState
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.smartisanClick
import com.smartisan.music.ui.components.smartisanPainterBackground
import com.smartisan.music.ui.components.smartisanStateColor
import com.smartisan.music.ui.components.smartisanTextSize

/** 禁用态整体透明度，对齐旧版按钮的 0.22。 */
private const val CloudMusicActionDisabledAlpha = 0.22f

/** 文字基线微调，对齐旧版 layout 里的 marginBottom=0.67dp。 */
private val CloudMusicActionTextBaselinePadding = 0.67.dp

/**
 * 云音乐页内操作按钮：对齐旧版 `layout_play_btn_with_icon` 规格。
 *
 * 30dp 高、圆角 6dp、浅粉底 + 红字加粗、可选前置图标；按下/禁用态由原版 selector
 * （`btn_red_bg_selector` / `red_btn_text_color_selector` / `btn_icon_*_selector`）驱动，
 * 与旧版云音乐详情页的「播放列表 / 随机播放」同一套观感。
 */
@Composable
internal fun CloudMusicActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectSmartisanPressedAsState()
    val background =
        rememberSmartisanDrawablePainter(
            R.drawable.btn_red_bg_selector,
            enabled = enabled,
            pressed = pressed,
        )
    Box(
        modifier =
            modifier
                .height(30.dp)
                .graphicsLayer { alpha = if (enabled) 1f else CloudMusicActionDisabledAlpha }
                .smartisanPainterBackground(background)
                .clickable(
                    interaction,
                    null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = smartisanClick(onClick),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (iconRes != null) {
                Image(
                    painter =
                        rememberSmartisanDrawablePainter(
                            iconRes,
                            enabled = enabled,
                            pressed = pressed,
                        ),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
            BasicText(
                text = text,
                style =
                    TextStyle(
                        color =
                            smartisanStateColor(
                                R.color.red_btn_text_color_selector,
                                enabled = enabled,
                                pressed = pressed,
                            ),
                        fontSize = smartisanTextSize(R.dimen.settings_item_tips_text_size),
                        fontWeight = FontWeight.Bold,
                        platformStyle = PlatformTextStyle(includeFontPadding = true),
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = CloudMusicActionTextBaselinePadding),
            )
        }
    }
}
