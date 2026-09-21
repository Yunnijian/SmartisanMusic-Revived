package com.smartisan.music.ui.settings

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.smartisan.music.R
import com.smartisan.music.data.settings.TurntableStyle
import com.smartisan.music.ui.components.collectSmartisanPressedAsState
import com.smartisan.music.ui.components.SmartisanTitleBar
import com.smartisan.music.ui.components.SmartisanTitleBarAction
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.smartisanClick
import com.smartisan.music.ui.components.smartisanPainterBackground
import com.smartisan.music.ui.components.smartisanShadowBackground
import com.smartisan.music.ui.components.smartisanStateColor
import com.smartisan.music.ui.components.smartisanTextSize

@Composable
internal fun TurntableStyleSettingsPage(
    active: Boolean,
    selectedStyle: TurntableStyle,
    onClose: () -> Unit,
    onStyleSelected: (TurntableStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(modifier = modifier.fillMaxSize().background(colorResource(R.color.page_background))) {
        if (active) {
            SmartisanTitleBar(
                title = stringResource(R.string.turntable_style),
                modifier = Modifier.fillMaxWidth(),
                navigationIcon =
                    SmartisanTitleBarAction(
                        iconRes = R.drawable.standard_icon_back_selector,
                        contentDescription = stringResource(R.string.back),
                        onClick = onClose,
                    ),
            )
            TurntableStyleSettingsContent(
                selectedStyle = selectedStyle,
                scrollState = scrollState,
                onStyleSelected = onStyleSelected,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

@Composable
private fun TurntableStyleSettingsContent(
    selectedStyle: TurntableStyle,
    scrollState: ScrollState,
    onStyleSelected: (TurntableStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontalMargin = dimensionResource(R.dimen.list_item_left_right_margin)
    val verticalGap = dimensionResource(R.dimen.list_item_vertical_gap)
    val tipsStyle =
        TextStyle(
            color = colorResource(R.color.setting_item_summary_text_color),
            fontSize = smartisanTextSize(R.dimen.settings_item_tips_text_size),
            fontFamily = FontFamily.SansSerif,
            platformStyle = PlatformTextStyle(includeFontPadding = true),
        )
    Column(
        modifier =
            modifier
                .smartisanPainterBackground(
                    painter = rememberSmartisanDrawablePainter(R.drawable.account_background)
                )
                .verticalScroll(scrollState)
    ) {
        Spacer(Modifier.height(verticalGap))
        BasicText(
            text = stringResource(R.string.turntable_style),
            style = tipsStyle.copy(fontWeight = FontWeight.Bold),
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        horizontal =
                            horizontalMargin + TurntableStyleSettingsMetrics.TipsHorizontalPadding
                    )
                    .padding(
                        bottom = TurntableStyleSettingsMetrics.SectionTitleBottomPadding
                    ),
        )
        Column(
            modifier =
                Modifier.fillMaxWidth().padding(horizontal = horizontalMargin).selectableGroup()
        ) {
            TurntableStyle.entries.forEachIndexed { index, style ->
                TurntableStyleSettingsRow(
                    style = style,
                    selected = style == selectedStyle,
                    shape =
                        when (index) {
                            0 -> TurntableStyleRowShape.Top
                            TurntableStyle.entries.lastIndex -> TurntableStyleRowShape.Bottom
                            else -> TurntableStyleRowShape.Middle
                        },
                    onClick = {
                        if (style != selectedStyle) onStyleSelected(style)
                    },
                )
            }
        }
        Spacer(Modifier.height(verticalGap))
    }
}

@Composable
private fun TurntableStyleSettingsRow(
    style: TurntableStyle,
    selected: Boolean,
    shape: TurntableStyleRowShape,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectSmartisanPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val title = stringResource(style.labelRes)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .height(dimensionResource(R.dimen.list_item_min_height))
                    .smartisanShadowBackground(
                        backgroundRes = shape.backgroundRes,
                        shadowRes = shape.shadowRes,
                        pressed = pressed,
                        selected = selected,
                        focused = focused,
                    )
                    .selectable(
                        selected = selected,
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.RadioButton,
                        onClick = smartisanClick(onClick),
                    )
                    .semantics { contentDescription = title }
                    .padding(
                        start = TurntableStyleSettingsMetrics.PreviewStartMargin,
                        end = TurntableStyleSettingsMetrics.SelectedEndMargin,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TurntableStylePreview(
                style = style,
                modifier =
                    Modifier.size(
                        TurntableStyleSettingsMetrics.PreviewSize
                    ),
            )
            BasicText(
                text = title,
                modifier =
                    Modifier.weight(1f)
                        .padding(
                            start = TurntableStyleSettingsMetrics.TextStartMargin,
                            end = TurntableStyleSettingsMetrics.TextEndMargin,
                        )
                        .clearAndSetSemantics {},
                maxLines = 1,
                softWrap = false,
                style =
                    TextStyle(
                        color =
                            smartisanStateColor(
                                R.color.setting_item_text_colorlist,
                                pressed = pressed,
                                selected = selected,
                                focused = focused,
                            ),
                        fontSize = smartisanTextSize(R.dimen.primary_text_size),
                        fontFamily = FontFamily.SansSerif,
                        platformStyle = PlatformTextStyle(includeFontPadding = true),
                        textDirection =
                            if (isRtl) TextDirection.ContentOrRtl
                            else TextDirection.ContentOrLtr,
                        textAlign = if (isRtl) TextAlign.Right else TextAlign.Left,
                    ),
            )
            Box(
                modifier = Modifier.size(TurntableStyleSettingsMetrics.SelectedSize)
            ) {
                if (selected) {
                    Image(
                        painter =
                            rememberSmartisanDrawablePainter(
                                R.drawable.selector_radio_choice,
                                pressed = pressed,
                            ),
                        contentDescription = null,
                        contentScale = ContentScale.Inside,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** 两种唱机样式的迷你预览；Netease 用官方 outline/disc 位图，仅示意布局差异。 */
@Composable
private fun TurntableStylePreview(
    style: TurntableStyle,
    modifier: Modifier = Modifier,
) {
    when (style) {
        TurntableStyle.Original ->
            Canvas(modifier = modifier) {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(Color(0xFF4A4A4C), radius, center)
                drawCircle(Color(0xFF26262A), radius * 0.38f, center)
                drawCircle(Color(0xFF111113), radius * 0.07f, center)
                drawLine(
                    Color.White.copy(alpha = 0.92f),
                    start = Offset(center.x + radius * 0.10f, center.y - radius * 1.04f),
                    end = Offset(center.x + radius * 0.46f, center.y - radius * 0.34f),
                    strokeWidth = radius * 0.10f,
                )
            }
        TurntableStyle.Netease ->
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.netease_vinyl_outline),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                Image(
                    painter = painterResource(R.drawable.netease_vinyl_disc),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
    }
}

private object TurntableStyleSettingsMetrics {
    val TipsHorizontalPadding = 18.dp
    val SectionTitleBottomPadding = 7.dp
    val PreviewSize = 40.dp
    val PreviewStartMargin = 12.dp
    val SelectedSize = 28.dp
    val SelectedEndMargin = 14.dp
    val TextStartMargin = 12.dp
    val TextEndMargin = 8.dp
}

private enum class TurntableStyleRowShape(
    @DrawableRes val backgroundRes: Int,
    @DrawableRes val shadowRes: Int,
) {
    Top(R.drawable.group_list_item_bg_top, R.drawable.list_content_item_top_shadow),
    Middle(R.drawable.group_list_item_bg_mid, R.drawable.list_content_item_middle_shadow),
    Bottom(R.drawable.group_list_item_bg_bottom, R.drawable.list_content_item_bottom_shadow),
}

@Preview(name = "Turntable style · Light", widthDp = 390, heightDp = 640, locale = "zh")
@Preview(
    name = "Turntable style · Dark",
    widthDp = 390,
    heightDp = 640,
    locale = "zh",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun TurntableStyleSettingsPagePreview() {
    TurntableStyleSettingsPage(
        active = true,
        selectedStyle = TurntableStyle.Original,
        onClose = {},
        onStyleSelected = {},
    )
}
