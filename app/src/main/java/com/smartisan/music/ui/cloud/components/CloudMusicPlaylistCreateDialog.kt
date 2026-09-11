package com.smartisan.music.ui.cloud.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import com.smartisan.music.R

/**
 * 创建歌单对话框：居中白色圆角卡片 + 半透明遮罩。
 * 单行输入框（打开时自动聚焦），名称非空才可点「确定」，取消直接关闭。
 */
@Composable
internal fun CloudMusicPlaylistCreateDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) {
        return
    }
    var name by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    val confirmEnabled = name.isNotBlank()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.40f))
            .cloudMusicPressable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CloudSurfaceColor)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                text = stringResource(R.string.cloud_music_new_playlist),
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = CloudTrackTitleColor,
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )
            // 单行输入框，底色浅灰，自动聚焦。
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 15.sp,
                    color = CloudTrackTitleColor,
                ),
                cursorBrush = SolidColor(CloudAccentColor),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(CloudSearchFieldBackgroundColor)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (name.isEmpty()) {
                            Text(
                                text = stringResource(R.string.cloud_music_playlist_name),
                                style = TextStyle(
                                    fontSize = 15.sp,
                                    color = CloudSecondaryTextColor,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box(modifier = Modifier.focusRequester(focusRequester)) {
                            innerTextField()
                        }
                    }
                },
            )
            // 取消 / 确定。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(CloudSearchFieldBackgroundColor)
                        .cloudMusicPressable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.cloud_music_cancel),
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = CloudTrackTitleColor,
                        ),
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (confirmEnabled) {
                                CloudAccentColor
                            } else {
                                CloudAccentColor.copy(alpha = 0.4f)
                            },
                        )
                        .cloudMusicPressable(enabled = confirmEnabled, onClick = {
                            onConfirm(name.trim())
                        }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.cloud_music_confirm),
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = Color.White,
                        ),
                    )
                }
            }
        }
    }
}