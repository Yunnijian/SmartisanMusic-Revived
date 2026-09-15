package com.smartisan.music.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.MediaItem
import com.smartisan.music.R
import com.smartisan.music.isExternalAudioLaunchItem
import com.smartisan.music.ui.components.TrackActionItem
import com.smartisan.music.ui.components.TrackActionsOverlay

/** 触发曲目操作面板的页面来源，决定要不要给「删除」这一项。 */
internal enum class TrackActionSource {
    Library,
    Loved,
    Playlist,
}

/**
 * 曲目操作面板：条目集合由「来源 + 是否已收藏」决定，动作全部回主壳执行。
 *
 * 收藏、加歌单等待确认项都在 [MusicShellViewModel] 里，这里只渲染与转发动作。
 *
 * 从 `MusicAppShell` 原样搬来的区域，保持既有细节：
 * 每项点击都先 `onDismiss()` 再执行动作（顺序与迁移前一致），
 * 收藏项在 `canFavorite` 为假时只关闭面板，「删除」只在 Library 来源出现。
 */
@Composable
internal fun ShellTrackActionsOverlay(
    uiState: MusicShellViewModel,
    favoriteIds: Set<String>,
    onAddToQueue: (List<MediaItem>) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onRequestDelete: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionItem = uiState.pendingTrackActionItem
    TrackActionsOverlay(
        visible = actionItem != null,
        actions =
            actionItem
                ?.let { item ->
                    trackActionItems(
                        actionItem = item,
                        source = uiState.pendingTrackActionSource,
                        isFavorite = item.mediaId in favoriteIds,
                        onDismiss = uiState::dismissTrackActions,
                        onAddToPlaylist = uiState::requestPlaylistPicker,
                        onAddToQueue = onAddToQueue,
                        onToggleFavorite = onToggleFavorite,
                        onRequestDelete = onRequestDelete,
                    )
                }
                .orEmpty(),
        onDismissRequest = uiState::dismissTrackActions,
        modifier = modifier,
    )
}

private fun trackActionItems(
    actionItem: MediaItem,
    source: TrackActionSource,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onAddToPlaylist: (List<MediaItem>) -> Unit,
    onAddToQueue: (List<MediaItem>) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    onRequestDelete: (Set<String>) -> Unit,
): List<TrackActionItem> {
    val mediaId = actionItem.mediaId
    val canAddToPlaylist = mediaId.isNotBlank() && !actionItem.isExternalAudioLaunchItem()
    val canFavorite = mediaId.isNotBlank() && !actionItem.isExternalAudioLaunchItem()
    val actions =
        mutableListOf(
            TrackActionItem(
                labelRes = R.string.add_to_playlist,
                iconRes = R.drawable.more_select_icon_addlist,
                pressedIconRes = R.drawable.more_select_icon_addlist_down,
                enabled = canAddToPlaylist,
                onClick = {
                    onDismiss()
                    onAddToPlaylist(listOf(actionItem))
                },
            ),
            TrackActionItem(
                labelRes = R.string.add_to_queue,
                iconRes = R.drawable.more_select_icon_addplay,
                pressedIconRes = R.drawable.more_select_icon_addplay_down,
                onClick = {
                    onDismiss()
                    onAddToQueue(listOf(actionItem))
                },
            ),
            TrackActionItem(
                labelRes = if (isFavorite) R.string.cancel_love else R.string.love,
                iconRes =
                    if (isFavorite) {
                        R.drawable.more_select_icon_favorite_cancel
                    } else {
                        R.drawable.more_select_icon_favorite_add
                    },
                pressedIconRes =
                    if (isFavorite) {
                        R.drawable.more_select_icon_favorite_cancel_down
                    } else {
                        R.drawable.more_select_icon_favorite_add_down
                    },
                enabled = canFavorite,
                selected = isFavorite,
                onClick = {
                    onDismiss()
                    if (canFavorite) {
                        onToggleFavorite(actionItem)
                    }
                },
            ),
        )
    if (source == TrackActionSource.Library) {
        actions +=
            TrackActionItem(
                labelRes = R.string.delete,
                iconRes = R.drawable.more_select_icon_delete,
                onClick = {
                    onDismiss()
                    onRequestDelete(setOf(mediaId))
                },
            )
    }
    return actions
}
