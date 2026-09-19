package com.smartisan.music.ui.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.data.online.OnlineTrack
import com.smartisan.music.ui.cloud.components.*

/** 头部行最小高度与封面边长：对齐旧版 CloudDetailHeaderHeight / CloudDetailHeaderArtworkSize。
 *
 * 旧版封面写 64dp，但被 88dp 行高减去 16dp 上下内边距约束成 56dp，这里直接按实际值写。 */
private val CloudDetailHeaderMinHeight = 88.dp
private val CloudDetailHeaderArtworkSize = 56.dp

/**
 * 详情页顶部 header：封面 + 标题/副标题 + 播放全部 / 随机播放。
 * 副标题按目标类型拼接元数据（副标题/曲数/播放数等），缺失时回退类型文案；
 * 封面优先取 target 自带 artworkUrl，其次列表首曲封面，空列表由占位色兜底。
 */
@Composable
internal fun CloudMusicDetailHeader(
    target: CloudDetailTarget,
    tracks: List<OnlineTrack>,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    editMode: Boolean = false,
    selectedCount: Int = 0,
    actionInFlight: Boolean = false,
    onDeletePlaylist: (() -> Unit)? = null,
    onEditClick: (() -> Unit)? = null,
    onSelectAll: (() -> Unit)? = null,
    onRemoveSelected: (() -> Unit)? = null,
    onCancelEdit: (() -> Unit)? = null,
) {
    val title = when (target) {
        is CloudDetailTarget.Playlist -> target.title
        is CloudDetailTarget.Album -> target.title
        is CloudDetailTarget.Artist -> target.name
        is CloudDetailTarget.Radio -> target.title
        is CloudDetailTarget.BannerTrack -> target.title
    }
    val subtitle = cloudDetailSubtitle(target, tracks.size)
    val artworkUrl = when (target) {
        is CloudDetailTarget.Playlist -> target.artworkUrl
        is CloudDetailTarget.Album -> target.artworkUrl
        is CloudDetailTarget.Artist -> target.artworkUrl
        is CloudDetailTarget.Radio -> target.artworkUrl
        is CloudDetailTarget.BannerTrack -> target.artworkUrl
    } ?: tracks.firstOrNull()?.artworkUrl
    val playEnabled = tracks.isNotEmpty()

    Column(Modifier.fillMaxWidth()) {
        // 封面 + 标题 + 副标题（对齐旧版：88dp 行高、小封面、大号标题）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = CloudDetailHeaderMinHeight)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CloudMusicCoverImage(
                imageUrl = artworkUrl,
                modifier = Modifier
                    .size(CloudDetailHeaderArtworkSize)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CloudSearchFieldBackgroundColor),
                contentScale = ContentScale.Fit,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    style = TextStyle(
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Medium,
                        color = CloudTrackTitleColor,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = CloudSecondaryTextColor,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        when {
            editMode -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(CloudSurfaceColor)
                    .padding(horizontal = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CloudMusicDetailEditAction(
                    text = stringResource(R.string.cloud_music_select_all),
                    enabled = !actionInFlight && tracks.isNotEmpty(),
                    onClick = { onSelectAll?.invoke() },
                )
                Text(
                    text = stringResource(R.string.selected_item_format, selectedCount, tracks.size),
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = CloudSecondaryTextColor,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                CloudMusicDetailEditAction(
                    text = stringResource(R.string.delete_track),
                    enabled = !actionInFlight && selectedCount > 0,
                    destructive = true,
                    onClick = { onRemoveSelected?.invoke() },
                )
                CloudMusicDetailEditAction(
                    text = stringResource(R.string.cancel),
                    enabled = !actionInFlight,
                    onClick = { onCancelEdit?.invoke() },
                )
            }
            onDeletePlaylist != null -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CloudMusicActionButton(
                    text = stringResource(R.string.cloud_music_detail_shuffle),
                    iconRes = R.drawable.btn_icon_shuffle_selector,
                    enabled = playEnabled,
                    onClick = onShuffle,
                    modifier = Modifier.weight(1f),
                )
                CloudMusicActionButton(
                    text = stringResource(R.string.cloud_music_delete_playlist),
                    iconRes = R.drawable.btn_deletelist2_selector,
                    enabled = true,
                    onClick = onDeletePlaylist,
                    modifier = Modifier.weight(1f),
                )
                CloudMusicActionButton(
                    text = stringResource(R.string.cloud_music_edit_playlist),
                    iconRes = R.drawable.btn_editlist2_selector,
                    enabled = playEnabled,
                    onClick = { onEditClick?.invoke() },
                    modifier = Modifier.weight(1f),
                )
            }
            else -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CloudMusicActionButton(
                    text = stringResource(R.string.cloud_music_detail_play_all),
                    iconRes = R.drawable.btn_icon_play_selector,
                    enabled = playEnabled,
                    onClick = onPlayAll,
                    modifier = Modifier.weight(1f),
                )
                CloudMusicActionButton(
                    text = stringResource(R.string.cloud_music_detail_shuffle),
                    iconRes = R.drawable.btn_icon_shuffle_selector,
                    enabled = playEnabled,
                    onClick = onShuffle,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        CloudMusicDivider()
    }
}

/** 编辑态动作条上的文字按钮：固定宽度、禁用时降透明度（对齐旧版 bindCloudMusicAccountActionEnabled）。 */
@Composable
private fun CloudMusicDetailEditAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    Text(
        text = text,
        style = TextStyle(
            fontSize = 14.sp,
            color = when {
                !enabled -> CloudSecondaryTextColor
                destructive -> CloudAccentColor
                else -> CloudTrackTitleColor
            },
        ),
        maxLines = 1,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.28f)
            .cloudMusicPressable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    )
}

/** 按目标类型拼接详情页副标题；元数据全缺时回退类型文案。 */
@Composable
private fun cloudDetailSubtitle(target: CloudDetailTarget, loadedTrackCount: Int? = null): String {
    val kindLabel = when (target) {
        is CloudDetailTarget.Playlist -> stringResource(R.string.cloud_music_detail_kind_playlist)
        is CloudDetailTarget.Album -> stringResource(R.string.cloud_music_detail_kind_album)
        is CloudDetailTarget.Artist -> stringResource(R.string.cloud_music_detail_kind_artist)
        is CloudDetailTarget.Radio -> stringResource(R.string.cloud_music_detail_kind_radio)
        is CloudDetailTarget.BannerTrack -> stringResource(R.string.cloud_music_detail_kind_track)
    }
    val parts: List<String> = when (target) {
        is CloudDetailTarget.Playlist -> buildList {
            target.subtitle?.takeIf(String::isNotBlank)?.let(::add)
            val count = loadedTrackCount ?: target.trackCount
            if (count > 0) {
                add(pluralStringResource(R.plurals.track_count, count, count))
            }
            cloudPlayCountText(target.playCount)?.let(::add)
        }
        is CloudDetailTarget.Album -> buildList {
            target.artist?.takeIf(String::isNotBlank)?.let(::add)
            val count = loadedTrackCount ?: target.trackCount
            if (count > 0) {
                add(pluralStringResource(R.plurals.track_count, count, count))
            }
        }
        is CloudDetailTarget.Artist -> buildList {
            target.alias?.takeIf(String::isNotBlank)?.let(::add)
            if (target.trackCount > 0) {
                add(pluralStringResource(R.plurals.track_count, target.trackCount, target.trackCount))
            }
            if (target.albumCount > 0) {
                add(pluralStringResource(R.plurals.cloud_music_album_count, target.albumCount, target.albumCount))
            }
        }
        is CloudDetailTarget.Radio -> buildList {
            target.category?.takeIf(String::isNotBlank)?.let(::add)
            target.creator?.takeIf(String::isNotBlank)?.let(::add)
            if (target.programCount > 0) {
                add(pluralStringResource(R.plurals.cloud_music_program_count, target.programCount, target.programCount))
            }
            if (target.playCount > 0) {
                add(pluralStringResource(R.plurals.cloud_music_play_count, target.playCount.toInt(), target.playCount))
            }
        }
        is CloudDetailTarget.BannerTrack -> buildList {
            target.subtitle?.takeIf(String::isNotBlank)?.let(::add)
        }
    }
    return parts.joinToString(" · ").ifBlank { kindLabel }
}
