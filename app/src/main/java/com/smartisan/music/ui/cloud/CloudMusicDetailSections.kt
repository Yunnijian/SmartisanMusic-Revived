package com.smartisan.music.ui.cloud

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.data.online.OnlineAlbum
import com.smartisan.music.data.online.OnlineArtistIntroduction
import com.smartisan.music.data.online.OnlineTrack
import com.smartisan.music.ui.cloud.components.*
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter

/** 编辑态列表行：勾选框 + 封面 + 标题/副标题，点击切换选中。 */
@Composable
internal fun CloudMusicDetailEditRow(
    track: OnlineTrack,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(CloudTrackRowHeight)
            .cloudMusicPressable(onClick = onToggle)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = rememberSmartisanDrawablePainter(
                R.drawable.check_box_selector,
                checked = selected,
            ),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        CloudMusicCoverImage(
            imageUrl = track.artworkUrl,
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
                text = track.title,
                style = TextStyle(
                    fontSize = 15.sp,
                    color = CloudTrackTitleColor,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist.orEmpty(),
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

/** 艺人专辑横滑列表：展示为不可点击的展示列表（CloudDetailTarget 已知，详情入口由宿主重新驱动）。 */
@Composable
internal fun CloudMusicArtistAlbumsSection(
    albums: List<OnlineAlbum>,
    onOpenArtistAlbums: (() -> Unit)? = null,
) {
    CloudHomeSectionHeader(
        title = stringResource(R.string.cloud_music_detail_artist_albums),
        actionText = if (onOpenArtistAlbums != null) {
            stringResource(R.string.cloud_music_section_view_all)
        } else {
            null
        },
        onClick = onOpenArtistAlbums,
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(
            items = albums,
            key = { _, album -> album.albumId },
        ) { _, album ->
            Column(modifier = Modifier.width(120.dp)) {
                CloudMusicCoverImage(
                    imageUrl = album.artworkUrl,
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Text(
                    text = album.title,
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = CloudTrackTitleColor,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                album.artist?.takeIf(String::isNotBlank)?.let { artist ->
                    Text(
                        text = artist,
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = CloudSecondaryTextColor,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

/** 艺人简介：分区标题 + 每段文本段落。 */
@Composable
internal fun CloudMusicArtistIntroSection(introduction: List<OnlineArtistIntroduction>) {
    CloudMusicSectionTitle(title = stringResource(R.string.cloud_music_detail_artist_intro))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        introduction.forEach { intro ->
            if (intro.title.isNotBlank()) {
                Text(
                    text = intro.title,
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = CloudTrackTitleColor,
                    ),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                text = intro.text,
                style = TextStyle(
                    fontSize = 13.sp,
                    color = CloudSecondaryTextColor,
                ),
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

/** 删除歌单确认对话框：居中白色圆角卡片 + 半透明遮罩，取消/删除（红色）双按钮。 */
@Composable
internal fun CloudMusicDeletePlaylistConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.40f))
            .cloudMusicPressable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(CloudSurfaceColor)
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.cloud_music_delete_playlist),
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = CloudTrackTitleColor,
                ),
            )
            Text(
                text = stringResource(R.string.cloud_music_delete_playlist_confirm),
                style = TextStyle(
                    fontSize = 13.sp,
                    color = CloudSecondaryTextColor,
                ),
                modifier = Modifier.padding(top = 10.dp, bottom = 20.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CloudMusicActionButton(
                    text = stringResource(R.string.cloud_music_cancel),
                    enabled = true,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                CloudMusicActionButton(
                    text = stringResource(R.string.cloud_music_delete),
                    enabled = true,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
