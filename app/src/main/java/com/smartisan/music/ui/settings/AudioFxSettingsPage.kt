package com.smartisan.music.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.data.settings.AudioFxMaxGainDb
import com.smartisan.music.data.settings.AudioFxMinGainDb
import com.smartisan.music.data.settings.AudioFxPreset
import com.smartisan.music.data.settings.PlaybackSettings
import com.smartisan.music.data.settings.equalizerGainDbPoints
import com.smartisan.music.data.settings.normalizeAudioFxGainDbPoints
import com.smartisan.music.ui.components.collectSmartisanPressedAsState
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.smartisanClick
import com.smartisan.music.ui.components.smartisanShadowBackground
import kotlin.math.roundToInt

@Composable
internal fun AudioFxSettingsPage(
    active: Boolean,
    playbackSettings: PlaybackSettings,
    onClose: () -> Unit,
    onAudioFxEnabledChange: (Boolean) -> Unit,
    onAudioFxPresetChange: (AudioFxPreset) -> Unit,
    onAudioFxCustomGainDbPointsChange: (List<Float>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val preset =
        if (playbackSettings.audioFxEnabled) playbackSettings.audioFxPreset
        else AudioFxPreset.Original
    SettingsScaffold(active, stringResource(R.string.audio_fx), onClose, modifier) {
        SettingsGap()
        SettingsSwitchRow(
            R.string.audio_fx_enabled,
            playbackSettings.audioFxEnabled,
            RowShape.Single,
            onAudioFxEnabledChange,
        )
        SettingsGap()
        Column(
            Modifier.fillMaxWidth()
                .height(188.dp)
                .smartisanShadowBackground(RowShape.Single.background, RowShape.Single.shadow)
                .padding(start = 18.dp, top = 10.dp, end = 18.dp, bottom = 12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .height(34.dp)
                    .alpha(if (playbackSettings.audioFxEnabled) 1f else 0.72f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    stringResource(R.string.audio_fx_curve),
                    Modifier.weight(1f),
                    style = settingsTextStyle(),
                )
                BasicText(
                    stringResource(preset.labelRes()),
                    style = settingsTextStyle(summary = true),
                )
            }
            AudioFxCurve(
                preset,
                playbackSettings.audioFxEnabled,
                playbackSettings.audioFxCustomGainDbPoints,
                onAudioFxCustomGainDbPointsChange,
                Modifier.fillMaxWidth().weight(1f),
            )
        }
        SettingsGap()
        Column(Modifier.selectableGroup()) {
            AudioFxPreset.entries.forEachIndexed { index, item ->
                val source = remember { MutableInteractionSource() }
                val pressed by source.collectSmartisanPressedAsState()
                SettingsRow(
                    item.labelRes(),
                    stringResource(item.summaryRes()),
                    RowShape.at(index, AudioFxPreset.entries.size),
                    playbackSettings.audioFxEnabled,
                    source,
                    Modifier.selectable(
                        item == preset,
                        source,
                        null,
                        enabled = playbackSettings.audioFxEnabled,
                        role = Role.RadioButton,
                        onClick = smartisanClick { onAudioFxPresetChange(item) },
                    ),
                    titleAccessoryGap = 10.dp,
                ) {
                    Box(
                        Modifier.padding(end = 6.dp)
                            .size(28.dp)
                            .alpha(if (playbackSettings.audioFxEnabled) 1f else 0.62f)
                    ) {
                        if (item == preset)
                            Image(
                                rememberSmartisanDrawablePainter(
                                    R.drawable.selector_radio_choice,
                                    enabled = playbackSettings.audioFxEnabled,
                                    pressed = pressed,
                                ),
                                null,
                                Modifier.fillMaxSize(),
                                contentScale = ContentScale.Inside,
                            )
                    }
                }
            }
        }
        SettingsGap()
    }
}

internal fun AudioFxPreset.labelRes() =
    when (this) {
        AudioFxPreset.Original -> R.string.audio_fx_original
        AudioFxPreset.Bass -> R.string.audio_fx_bass
        AudioFxPreset.Clear -> R.string.audio_fx_clear
        AudioFxPreset.Vocal -> R.string.audio_fx_vocal
        AudioFxPreset.Rock -> R.string.audio_fx_rock
        AudioFxPreset.Custom -> R.string.audio_fx_custom
    }

private fun AudioFxPreset.summaryRes() =
    when (this) {
        AudioFxPreset.Original -> R.string.audio_fx_original_summary
        AudioFxPreset.Bass -> R.string.audio_fx_bass_summary
        AudioFxPreset.Clear -> R.string.audio_fx_clear_summary
        AudioFxPreset.Vocal -> R.string.audio_fx_vocal_summary
        AudioFxPreset.Rock -> R.string.audio_fx_rock_summary
        AudioFxPreset.Custom -> R.string.audio_fx_custom_summary
    }

@Composable
private fun AudioFxCurve(
    preset: AudioFxPreset,
    enabled: Boolean,
    customGains: List<Float>,
    onChange: (List<Float>) -> Unit,
    modifier: Modifier,
) {
    val labels = listOf("60", "230", "910", "4k", "14k")
    val measurer = rememberTextMeasurer()
    val labelStyle =
        TextStyle(
            color = colorResource(R.color.setting_item_summary_text_color),
            fontSize = 10.5.sp,
        )
    val labelLayouts = labels.map { measurer.measure(it, labelStyle) }
    val gridColor = colorResource(R.color.input_border)
    val handleBorder = colorResource(R.color.surface_card)
    val curveColor = if (enabled) Color(0xffdb3b3b) else Color(0xffa6a6a6)
    var values by
        remember(preset, customGains) {
            mutableStateOf(
                if (preset == AudioFxPreset.Custom) normalizeAudioFxGainDbPoints(customGains)
                else preset.equalizerGainDbPoints().toList()
            )
        }
    val currentValues by rememberUpdatedState(values)
    val callback by rememberUpdatedState(onChange)
    val editable = enabled && preset == AudioFxPreset.Custom
    val maxLabelWidth = labelLayouts.maxOf { it.size.width }.toFloat()
    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(editable, maxLabelWidth) {
                if (!editable) return@pointerInput
                val inset = maxOf(maxLabelWidth / 2f, 5.6.dp.toPx()) + 2.dp.toPx()
                val top = 4.dp.toPx()
                val bottom = size.height - 24.dp.toPx()
                val graphHeight = bottom - top
                if (graphHeight <= 0 || size.width <= inset * 2f) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = 22.dp.toPx()
                    if (
                        down.position.x !in (inset - slop)..(size.width - inset + slop) ||
                            down.position.y !in (top - slop)..(bottom + slop)
                    )
                        return@awaitEachGesture
                    down.consume()
                    val band =
                        (((down.position.x - inset) / (size.width - 2f * inset)) * 4)
                            .roundToInt()
                            .coerceIn(0, 4)
                    fun update(y: Float) {
                        val raw = ((top + bottom) / 2f - y) / (graphHeight / 2f) * AudioFxMaxGainDb
                        val gain =
                            (raw.coerceIn(AudioFxMinGainDb, AudioFxMaxGainDb) * 2).roundToInt() / 2f
                        if (gain != currentValues[band]) {
                            values = currentValues.toMutableList().also { it[band] = gain }
                            callback(values)
                        }
                    }
                    update(down.position.y)
                    do {
                        val change =
                            awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        update(change.position.y)
                        change.consume()
                    } while (change.pressed)
                }
            }
        ) {
            val inset = maxOf(maxLabelWidth / 2f, 5.6.dp.toPx()) + 2.dp.toPx()
            val left = inset
            val right = size.width - inset
            val top = 4.dp.toPx()
            val bottom = size.height - 24.dp.toPx()
            if (right <= left || bottom <= top) return@Canvas
            labels.indices.forEach { index ->
                val x = left + (right - left) * index / 4
                drawLine(gridColor, Offset(x, top), Offset(x, bottom), 1.dp.toPx())
                val label = labelLayouts[index]
                drawText(
                    label,
                    topLeft =
                        Offset(
                            x - label.size.width / 2f,
                            size.height - 7.dp.toPx() - label.firstBaseline,
                        ),
                )
            }
            (0..4).forEach { i ->
                val y = top + (bottom - top) * i / 4
                drawLine(gridColor, Offset(left, y), Offset(right, y), 1.dp.toPx())
            }
            drawLine(
                Color(0xffd8d8d8),
                Offset(left, (top + bottom) / 2),
                Offset(right, (top + bottom) / 2),
                1.2.dp.toPx(),
            )
            val points = values.mapIndexed { index, gain ->
                Offset(
                    left + (right - left) * index / 4,
                    (top + bottom) / 2 -
                        gain.coerceIn(AudioFxMinGainDb, AudioFxMaxGainDb) / AudioFxMaxGainDb *
                            (bottom - top) / 2,
                )
            }
            val path =
                Path().apply {
                    points.forEachIndexed { i, point ->
                        if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                    }
                }
            drawPath(
                path,
                curveColor,
                style =
                    Stroke(
                        2.1.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    ),
            )
            if (preset == AudioFxPreset.Custom)
                points.forEach { point ->
                    drawCircle(curveColor, 5.6.dp.toPx(), point)
                    drawCircle(handleBorder, 5.6.dp.toPx(), point, style = Stroke(1.4.dp.toPx()))
                }
        }
        if (editable)
            Row(Modifier.fillMaxSize()) {
                labels.forEachIndexed { index, label ->
                    Spacer(
                        Modifier.weight(1f).fillMaxHeight().semantics {
                            contentDescription = "$label Hz"
                            progressBarRangeInfo =
                                ProgressBarRangeInfo(
                                    values[index],
                                    AudioFxMinGainDb..AudioFxMaxGainDb,
                                )
                            setProgress { value ->
                                val gain =
                                    (value.coerceIn(AudioFxMinGainDb, AudioFxMaxGainDb) * 2)
                                        .roundToInt() / 2f
                                values = values.toMutableList().also { it[index] = gain }
                                callback(values)
                                true
                            }
                        }
                    )
                }
            }
    }
}
