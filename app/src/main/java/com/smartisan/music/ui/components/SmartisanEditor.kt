package com.smartisan.music.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R

/** 邮件 editor_left_right_widget_min_height：40dp。 */
private val SmartisanEditorMinHeight = 40.dp

/** 邮件 editor_bg_single 的圆角（xxhdpi 21px ≈ 7dp）与描边色。 */
private val SmartisanEditorCornerRadius = 7.dp
private val SmartisanEditorBorderColor = Color(0xFFEBEBEB)
private val SmartisanEditorFillColor = Color.White

/**
 * 锤子标准单行输入框（对齐邮件 APK 的 sos_smartisanos_style_EditorTextStyle + QuickDeleteEditText）。
 *
 * 外观：白底圆角 + 细描边 + 文字 `editor_text_color`、提示 `editor_hint_text_color`
 * （深浅色都已入 token）。有内容时右侧浮出清空按钮（邮件 quick_icon_delete 原图标，自带按压态）。
 *
 * 背景用 Compose 绘制而非邮件那张 `editor_bg_single` 9-patch：该资产边框标记是半透明
 * （alpha 31/77），AAPT2 判定非规范标记拒绝编译，且内容区在反编译时已被裁坏。
 * 圆角与描边色按邮件原图量取，视觉一致。
 *
 * 只做单行：邮件那套还含 top/middle/bottom 三段拼接与左侧图标容器，登录场景用不上。
 *
 * [trailing] 给调用方挂右侧控件（如「获取验证码」），排在清空按钮之后。
 */
@Composable
internal fun SmartisanEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val textColor = colorResource(R.color.editor_text_color)
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SmartisanEditorMinHeight)
            .clip(RoundedCornerShape(SmartisanEditorCornerRadius))
            .background(SmartisanEditorFillColor)
            .border(
                width = 0.67.dp,
                color = SmartisanEditorBorderColor,
                shape = RoundedCornerShape(SmartisanEditorCornerRadius),
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder != null) {
                    androidx.compose.foundation.text.BasicText(
                        text = placeholder,
                        modifier = Modifier.align(Alignment.CenterStart),
                        style = SmartisanEditorTextStyle.copy(
                            color = colorResource(R.color.editor_hint_text_color),
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    singleLine = true,
                    textStyle = SmartisanEditorTextStyle.copy(color = textColor),
                    cursorBrush = SolidColor(textColor),
                    visualTransformation = visualTransformation,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = imeAction,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { onImeAction?.invoke() ?: keyboard?.hide() },
                        onGo = { onImeAction?.invoke() ?: keyboard?.hide() },
                        onSend = { onImeAction?.invoke() ?: keyboard?.hide() },
                    ),
                    interactionSource = interaction,
                )
            }
            if (enabled && value.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                SmartisanEditorClearButton(onClick = { onValueChange("") })
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.width(6.dp))
                trailing()
            }
        }
    }
}

/** 清空按钮：邮件 quick_icon_delete 原图标。 */
@Composable
private fun SmartisanEditorClearButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectSmartisanPressedAsState()
    Image(
        painter = rememberSmartisanDrawablePainter(
            R.drawable.quick_icon_delete,
            pressed = pressed,
        ),
        contentDescription = null,
        modifier = Modifier
            .size(16.dp)
            .clickable(interaction, null, onClick = smartisanClick(onClick)),
    )
}

/** 邮件 EditorTextStyle：15sp。颜色按状态在调用处补。 */
private val SmartisanEditorTextStyle = TextStyle(
    fontSize = 15.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = true),
)
