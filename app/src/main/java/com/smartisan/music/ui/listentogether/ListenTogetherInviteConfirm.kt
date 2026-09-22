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
import androidx.compose.ui.text.font.FontWeight
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
 * 「一起听」邀请确认弹层：入房的唯一入口。
 *
 * 深链（`163cn.tv` / `st.music.163.com` 分享页）能从任意网页或 App 拉起，
 * 静默入房等于把本地播放控制权交给链接发起方，所以这里只展示解析出的房间信息，
 * 用户点「加入一起听」才真正入房；X 或返回键只是忽略邀请。
 *
 * 邀请状态存在应用级 Store 上，弹层挂在应用壳层（[ListenTogetherSessionWiring]），
 * Activity 重建不会重复弹窗。
 */
@Composable
internal fun ListenTogetherInviteConfirmOverlay() {
    val store = LocalMusicAppContainer.current.listenTogetherStore
    val invite by store.pendingInvite.collectAsState()
    val pending = invite ?: return
    SmartisanModal(
        onDismiss = store::dismissPendingInvite,
        modifier = Modifier.fillMaxWidth(),
        bottom = true,
    ) {
        SmartisanMenuTitleBar(
            stringResource(R.string.listen_together_invite_title),
            store::dismissPendingInvite,
        )
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
            InviteLine(stringResource(R.string.listen_together_invite_message), emphasized = true)
            InviteLine(stringResource(R.string.listen_together_invite_room_id, pending.roomId))
            InviteLine(stringResource(R.string.listen_together_invite_inviter, pending.inviterId))
            InviteLine(stringResource(R.string.listen_together_invite_hint))
            SmartisanDialogButton(
                stringResource(R.string.listen_together_invite_accept),
                store::confirmPendingInvite,
                Modifier.fillMaxWidth().padding(top = AcceptButtonTopGap),
            )
        }
    }
}

@Composable
private fun InviteLine(text: String, emphasized: Boolean = false) {
    BasicText(
        text,
        Modifier.fillMaxWidth().padding(bottom = InviteLineGap),
        style =
            TextStyle(
                color =
                    colorResource(
                        if (emphasized) R.color.title_color
                        else R.color.setting_item_summary_text_color
                    ),
                fontSize = if (emphasized) 15.sp else 13.sp,
                fontWeight = if (emphasized) FontWeight.Bold else null,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = true),
            ),
    )
}

private val InviteLineGap = 6.dp
private val AcceptButtonTopGap = 8.dp
