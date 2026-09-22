package com.smartisan.music.ui.listentogether

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.LocalMusicAppContainer
import com.smartisan.music.R
import com.smartisan.music.ui.components.SmartisanDialogButton
import com.smartisan.music.ui.components.SmartisanMenuTitleBar
import com.smartisan.music.ui.components.SmartisanModal
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import com.smartisan.music.ui.components.smartisanPainterBackground

/**
 * 「一起听」房间面板：播放页徽标点开后弹的这一层，提供「退出房间」入口。
 *
 * 退出走 [com.smartisan.music.listentogether.ListenTogetherStore.leaveRoom]，
 * 它会结束服务端房间并清掉本地会话；点完即关面板，不用等网络返回。
 */
@Composable
internal fun ListenTogetherRoomSheet(onDismiss: () -> Unit) {
    val store = LocalMusicAppContainer.current.listenTogetherStore
    val state by store.state.collectAsState()
    SmartisanModal(onDismiss, Modifier.fillMaxWidth(), bottom = true) {
        SmartisanMenuTitleBar(stringResource(R.string.listen_together_title), onDismiss)
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
            BasicText(
                text =
                    formatTogetherDuration(
                        seconds =
                            togetherTotalSeconds(
                                accumulatedSeconds = state.accumulatedSeconds,
                                thisRoomSeconds = state.thisRoomSeconds,
                            ),
                        oneMinute = stringResource(R.string.listen_together_listened_one_minute),
                        minutes = stringResource(R.string.listen_together_listened_minutes),
                        hoursMinutes = stringResource(R.string.listen_together_listened_hours_minutes),
                    ),
                Modifier.fillMaxWidth().padding(bottom = DurationBottomGap),
                style =
                    TextStyle(
                        color = colorResource(R.color.setting_item_summary_text_color),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        platformStyle = PlatformTextStyle(includeFontPadding = true),
                    ),
            )
            SmartisanDialogButton(
                stringResource(R.string.listen_together_leave_room),
                onClick = {
                    onDismiss()
                    store.leaveRoom()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val DurationBottomGap = 10.dp
