package com.smartisan.music.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R

/** Empty hint used by the calibrated song and collection layouts. */
@Composable
internal fun SmartisanEmptyHint(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val hasAction = actionLabel != null && onAction != null
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            rememberSmartisanDrawablePainter(icon),
            null,
            Modifier.padding(top = 40.dp).size(120.dp),
        )
        BasicText(
            title,
            Modifier.padding(start = 60.dp, end = 60.dp, top = 18.dp),
            style =
                TextStyle(
                    color = colorResource(R.color.editor_hint_text_color),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
            maxLines = 1,
        )
        if (subtitle != null)
            BasicText(
                subtitle,
                Modifier.padding(
                    start = 60.dp,
                    end = 60.dp,
                    top = 5.dp,
                    bottom = if (hasAction) 0.dp else 40.dp,
                ),
                style =
                    TextStyle(
                        color = colorResource(R.color.editor_hint_text_color),
                        fontSize = 13.5.sp,
                        textAlign = TextAlign.Center,
                        platformStyle = PlatformTextStyle(includeFontPadding = true),
                    ),
            )
        if (hasAction) SmartisanEmptyHintAction(actionLabel!!, onAction!!)
    }
}

/** Text action shown under an empty hint, styled like the dialog's blue text button. */
@Composable
private fun SmartisanEmptyHintAction(
    text: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectSmartisanPressedAsState()
    BasicText(
        text,
        Modifier.clickable(interaction, null, onClick = smartisanClick(onClick))
            .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 26.dp),
        style =
            TextStyle(
                color =
                    smartisanStateColor(
                        R.color.blue_btn_text_color_selector,
                        pressed = pressed,
                    ),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = true),
            ),
        maxLines = 1,
    )
}
