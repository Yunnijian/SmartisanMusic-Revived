package com.smartisan.music.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.smartisan.music.R
import com.smartisan.music.data.settings.NeteaseAudioQuality
import com.smartisan.music.ui.components.SmartisanDialogButton
import com.smartisan.music.ui.components.SmartisanMenuTitleBar
import com.smartisan.music.ui.components.SmartisanModal
import com.smartisan.music.ui.components.collectSmartisanPressedAsState
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.smartisanClick
import com.smartisan.music.ui.components.smartisanPainterBackground

/** 在线播放音质选择页：八档单选，选中即写入 [com.smartisan.music.data.settings.OnlineMusicSettingsStore]。 */
@Composable
internal fun OnlineQualitySettingsPage(
    active: Boolean,
    quality: NeteaseAudioQuality,
    onClose: () -> Unit,
    onQualityChange: (NeteaseAudioQuality) -> Unit,
    modifier: Modifier = Modifier,
) {
    val qualities = NeteaseAudioQuality.entries
    SettingsScaffold(
        active,
        stringResource(R.string.online_music_play_quality),
        onClose,
        modifier,
    ) {
        SettingsGap()
        Column(Modifier.selectableGroup()) {
            qualities.forEachIndexed { index, item ->
                val source = remember { MutableInteractionSource() }
                val pressed by source.collectSmartisanPressedAsState()
                SettingsRow(
                    item.labelRes(),
                    stringResource(item.summaryRes()),
                    RowShape.at(index, qualities.size),
                    true,
                    source,
                    Modifier.selectable(
                        item == quality,
                        source,
                        null,
                        role = Role.RadioButton,
                        onClick = smartisanClick { onQualityChange(item) },
                    ),
                    titleAccessoryGap = 10.dp,
                ) {
                    Box(Modifier.padding(end = 6.dp).size(28.dp)) {
                        if (item == quality)
                            Image(
                                rememberSmartisanDrawablePainter(
                                    R.drawable.selector_radio_choice,
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
        BasicText(
            stringResource(R.string.online_music_quality_hint),
            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            style = settingsTextStyle(summary = true),
        )
        SettingsGap()
    }
}

/** 退出网易云确认弹层：沿用底部菜单对话框形态，X 取消、红色按钮执行退出。 */
@Composable
internal fun NeteaseLogoutConfirmation(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    SmartisanModal(onDismiss, Modifier.fillMaxWidth(), bottom = true) {
        SmartisanMenuTitleBar(stringResource(R.string.cloud_music_logout_confirm_title), onDismiss)
        Column(
            Modifier.fillMaxWidth()
                .smartisanPainterBackground(
                    rememberSmartisanDrawablePainter(R.drawable.menu_dialog_background)
                )
                .padding(horizontal = dimensionResource(R.dimen.menu_dialog_horizontal_distance))
                .padding(
                    top = dimensionResource(R.dimen.menu_dialog_btn_margin_view),
                    bottom = dimensionResource(R.dimen.menu_dialog_btn_margin_edge),
                ),
        ) {
            SmartisanDialogButton(
                stringResource(R.string.cloud_music_logout_confirm_action),
                onConfirm,
                Modifier.fillMaxWidth(),
            )
        }
    }
}

internal fun NeteaseAudioQuality.labelRes() =
    when (this) {
        NeteaseAudioQuality.Standard -> R.string.online_music_quality_standard
        NeteaseAudioQuality.Higher -> R.string.online_music_quality_higher
        NeteaseAudioQuality.ExHigh -> R.string.online_music_quality_exhigh
        NeteaseAudioQuality.Lossless -> R.string.online_music_quality_lossless
        NeteaseAudioQuality.HiRes -> R.string.online_music_quality_hires
        NeteaseAudioQuality.HdSurround -> R.string.online_music_quality_hd_surround
        NeteaseAudioQuality.Surround -> R.string.online_music_quality_surround
        NeteaseAudioQuality.Master -> R.string.online_music_quality_master
    }

internal fun NeteaseAudioQuality.summaryRes() =
    when (this) {
        NeteaseAudioQuality.Standard -> R.string.online_music_quality_summary_standard
        NeteaseAudioQuality.Higher -> R.string.online_music_quality_summary_higher
        NeteaseAudioQuality.ExHigh -> R.string.online_music_quality_summary_exhigh
        NeteaseAudioQuality.Lossless -> R.string.online_music_quality_summary_lossless
        NeteaseAudioQuality.HiRes -> R.string.online_music_quality_summary_hires
        NeteaseAudioQuality.HdSurround -> R.string.online_music_quality_summary_hd_surround
        NeteaseAudioQuality.Surround -> R.string.online_music_quality_summary_surround
        NeteaseAudioQuality.Master -> R.string.online_music_quality_summary_master
    }
