package com.smartisan.music.ui.cloud.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.data.online.OnlineAccountPlaylist

/**
 * 歌单选择器底部弹层：顶部「新建歌单」入口 + 下方可加入的歌单列表。
 * 顶部为白色圆角面板 + 半透明遮罩（点击遮罩关闭），列表为空时展示空提示。
 * 加载中/失败态由调用方处理，这里仅渲染传入的 [playlists]。
 */
@Composable
internal fun CloudMusicPlaylistPickerOverlay(
    visible: Boolean,
    playlists: List<OnlineAccountPlaylist>,
    onPlaylistSelected: (OnlineAccountPlaylist) -> Unit,
    onCreateNewPlaylist: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.40f))
                    .cloudMusicPressable(onClick = onDismiss),
            )
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .background(CloudSurfaceColor)
                        .padding(top = 6.dp),
                ) {
                    // 「新建歌单」入口。
                    Text(
                        text = stringResource(R.string.cloud_music_new_playlist),
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = CloudAccentColor,
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .cloudMusicPressable(onClick = onCreateNewPlaylist)
                            .padding(vertical = 14.dp),
                    )
                    CloudMusicDivider()
                    if (playlists.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.cloud_music_no_playlists),
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    color = CloudSecondaryTextColor,
                                ),
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(
                                items = playlists,
                                key = { playlist -> playlist.playlistId },
                            ) { playlist ->
                                Column {
                                    CloudMusicPlaylistPickerRow(
                                        playlist = playlist,
                                        onClick = { onPlaylistSelected(playlist) },
                                    )
                                    CloudMusicDivider()
                                }
                            }
                        }
                    }
                    Box(modifier = Modifier.padding(vertical = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun CloudMusicPlaylistPickerRow(
    playlist: OnlineAccountPlaylist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CloudTrackRowHeight)
            .cloudMusicPressable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CloudMusicCoverImage(
            imageUrl = playlist.artworkUrl,
            modifier = Modifier
                .size(CloudSearchCoverArtworkSize)
                .clip(RoundedCornerShape(6.dp)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = playlist.title,
                style = TextStyle(
                    fontSize = 15.sp,
                    color = CloudTrackTitleColor,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (playlist.trackCount > 0) "${playlist.trackCount}首" else "",
                style = TextStyle(
                    fontSize = 11.sp,
                    color = CloudSecondaryTextColor,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}