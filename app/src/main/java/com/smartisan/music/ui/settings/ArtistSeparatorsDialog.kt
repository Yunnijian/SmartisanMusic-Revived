package com.smartisan.music.ui.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.data.settings.parseArtistSeparatorInput
import com.smartisan.music.ui.components.SmartisanDialogButton
import com.smartisan.music.ui.components.SmartisanModal
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.smartisanPainterBackground
import kotlinx.coroutines.delay

@Composable
internal fun ArtistSeparatorsDialog(
    initialSeparators: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var separators by remember(initialSeparators) { mutableStateOf(initialSeparators) }
    var input by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    fun addInput(): Set<String> {
        val parsed = parseArtistSeparatorInput(input)
        if (parsed.isNotEmpty()) {
            separators = separators + parsed
            input = ""
        }
        return separators
    }
    SmartisanModal(
        onDismiss,
        Modifier.widthIn(max = dimensionResource(R.dimen.revone_global_dialog_content_width))
            .fillMaxWidth(),
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            delay(300)
            focus.requestFocus()
            keyboard?.show()
        }
        Column(
            Modifier.fillMaxWidth()
                .smartisanPainterBackground(
                    rememberSmartisanDrawablePainter(
                        R.drawable.revone_global_dialog_shape_background
                    )
                )
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .height(dimensionResource(R.dimen.revone_dialog_button_height)),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    stringResource(R.string.artist_separators),
                    style =
                        TextStyle(
                            color = colorResource(R.color.title_color),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            platformStyle = PlatformTextStyle(includeFontPadding = true),
                        ),
                )
            }
            Column(
                Modifier.fillMaxWidth()
                    .smartisanPainterBackground(
                        rememberSmartisanDrawablePainter(
                            R.drawable.revone_global_dialog_message_background
                        )
                    )
                    .padding(horizontal = 18.dp)
            ) {
                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                    BasicText(
                        stringResource(R.string.artist_separators_hint),
                        style =
                            settingsTextStyle(summary = true)
                                .copy(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                ),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().height(42.dp).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (separators.isEmpty())
                        BasicText(
                            stringResource(R.string.not_set),
                            style = settingsTextStyle(summary = true).copy(fontSize = 15.sp),
                        )
                    separators.sorted().forEach { separator ->
                        Box(
                            Modifier.padding(end = 8.dp)
                                .height(36.dp)
                                .background(
                                    colorResource(R.color.surface_raised),
                                    RoundedCornerShape(5.dp),
                                )
                                .border(
                                    1.dp,
                                    colorResource(R.color.input_border),
                                    RoundedCornerShape(5.dp),
                                )
                                .clickable { separators = separators - separator }
                                .padding(start = 16.dp, end = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                "$separator  ×",
                                style =
                                    settingsTextStyle(summary = true)
                                        .copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp).height(44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f)
                            .height(40.dp)
                            .smartisanPainterBackground(
                                rememberSmartisanDrawablePainter(R.drawable.edit_text_bg)
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicTextField(
                            input,
                            { input = it },
                            Modifier.weight(1f)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .focusRequester(focus),
                            singleLine = true,
                            textStyle =
                                TextStyle(
                                    color = colorResource(R.color.editor_text_color),
                                    fontSize = 15.sp,
                                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                                ),
                            cursorBrush = SolidColor(colorResource(R.color.editor_text_color)),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { addInput() }),
                            decorationBox = { field ->
                                Box {
                                    if (input.isEmpty())
                                        BasicText(
                                            stringResource(R.string.artist_custom_separator_hint),
                                            style =
                                                TextStyle(
                                                    color =
                                                        colorResource(
                                                            R.color.editor_hint_text_color
                                                        ),
                                                    fontSize = 15.sp,
                                                    platformStyle =
                                                        PlatformTextStyle(
                                                            includeFontPadding = true
                                                        ),
                                                ),
                                        )
                                    field()
                                }
                            },
                        )
                        Image(
                            rememberSmartisanDrawablePainter(R.drawable.quick_icon_delete),
                            stringResource(R.string.delete),
                            Modifier.size(32.dp).clickable { input = "" },
                            contentScale = ContentScale.Inside,
                        )
                    }
                    Box(
                        Modifier.padding(start = 8.dp)
                            .size(64.dp, 40.dp)
                            .background(
                                colorResource(R.color.surface_raised_soft),
                                RoundedCornerShape(7.dp),
                            )
                            .border(1.dp, Color(0xffd7dce8), RoundedCornerShape(7.dp))
                            .clickable { addInput() },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            stringResource(R.string.add),
                            style =
                                TextStyle(
                                    color = colorResource(R.color.btn_text_color_blue),
                                    fontSize = 14.sp,
                                ),
                        )
                    }
                }
            }
            SmartisanDialogButton(
                stringResource(R.string.done),
                { onConfirm(addInput()) },
                Modifier.fillMaxWidth()
                    .height(dimensionResource(R.dimen.revone_dialog_button_height)),
                backgroundRes = R.drawable.revone_dialog_button_bg_selector,
                textColorRes = R.color.blue_btn_text_color_selector,
            )
        }
    }
}
