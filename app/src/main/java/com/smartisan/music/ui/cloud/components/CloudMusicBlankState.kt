package com.smartisan.music.ui.cloud.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.ui.components.SmartisanDialogButton
import kotlinx.coroutines.delay

/** 云音乐空态 / 未登录引导页。空白图标 + 主副标题 + 可选操作按钮，与旧版保持一致的居中布局。 */
@Composable
internal fun CloudMusicBlankState(
    title: String,
    subtitle: String?,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CloudPageBackgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.blank_search),
                contentDescription = null,
                colorFilter = ColorFilter.tint(CloudBlankIconTintColor),
                modifier = Modifier
                    .width(80.dp)
                    .height(80.dp),
            )
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 16.sp,
                    color = CloudSecondaryTextColor,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.padding(top = 16.dp),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = CloudSecondaryTextColor,
                        textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (!actionText.isNullOrBlank() && onActionClick != null) {
                // 与手机号登录页的提交按钮同款（原版 9-patch 实心红 + 投影）。
                SmartisanDialogButton(
                    text = actionText,
                    onClick = onActionClick,
                    modifier = Modifier
                        .padding(top = 24.dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

/** 延迟展示的加载态：请求很快返回时不闪 loading 页，超过 [delayMillis] 才提示“正在搜索”。 */
@Composable
internal fun CloudMusicDelayedLoadingState(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    delayMillis: Long = CloudMusicLoadingDelayMillis,
) {
    var visible by remember(title, subtitle) { mutableStateOf(false) }
    LaunchedEffect(title, subtitle, delayMillis) {
        visible = false
        delay(delayMillis)
        visible = true
    }
    if (visible) {
        CloudMusicBlankState(
            title = title,
            subtitle = subtitle,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(CloudPageBackgroundColor),
        )
    }
}

private const val CloudMusicLoadingDelayMillis = 500L
