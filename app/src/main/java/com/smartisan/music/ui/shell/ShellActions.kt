package com.smartisan.music.ui.shell

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import com.smartisan.music.AppDispatchers
import com.smartisan.music.R
import com.smartisan.music.data.favorite.FavoriteSongsRepository
import com.smartisan.music.data.library.LibraryExclusionsStore
import com.smartisan.music.data.online.OnlineMusicProvider
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.data.online.onlineTrackIdentityOrNull
import com.smartisan.music.data.playback.PlaybackStatsRepository
import com.smartisan.music.data.playlist.PlaylistRepository
import com.smartisan.music.data.settings.LibraryDisplaySettingsStore
import com.smartisan.music.isExternalAudioLaunchItem
import com.smartisan.music.platform.media.audioMediaItemUri
import com.smartisan.music.playback.await
import com.smartisan.music.playback.deduplicateQueueCandidates
import com.smartisan.music.playback.invalidateLibrary
import com.smartisan.music.playback.refreshLibrary
import com.smartisan.music.playback.removeMediaItemsByMediaIds
import com.smartisan.music.ui.album.AlbumViewMode
import com.smartisan.music.ui.components.MediaStoreDeleteCoordinator
import com.smartisan.music.ui.components.MediaStoreDeleteItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 主壳的动作处理集：删除/加队列/收藏/刷新等意图落库与播放服务调用的收口。
 *
 * 从 `MusicAppShellContent` 的局部函数原样搬来，函数体、调用时机与 Toast 文案都不变；
 * 需要跨重组保持的计数（刷新版本、在线喜欢版本、刷新中标记）由主壳以 [MutableState] 注入。
 */
internal class ShellActions(
    private val context: Context,
    private val controller: MediaController?,
    private val scope: CoroutineScope,
    private val uiState: MusicShellViewModel,
    private val favoriteRepository: FavoriteSongsRepository,
    private val playlistRepository: PlaylistRepository,
    private val playbackStatsRepository: PlaybackStatsRepository,
    private val libraryExclusionsStore: LibraryExclusionsStore,
    private val onlineRepositoryRouter: OnlineMusicRepositoryRouter,
    private val libraryDisplaySettingsStore: LibraryDisplaySettingsStore,
    private val artistAlbumViewModeProvider: () -> AlbumViewMode,
    private val libraryRefreshVersion: MutableState<Int>,
    private val libraryRefreshing: MutableState<Boolean>,
    private val onlineLovedRefreshVersion: MutableState<Int>,
) {
    /** 由主壳在组合期回填：与 [cleanupDeletedSongs] 存在互引（删除协调器的 onDeleted 调它）。 */
    lateinit var deleteCoordinator: MediaStoreDeleteCoordinator

    fun cleanupDeletedSongs(mediaIds: Set<String>, hideFromLibrary: Boolean) {
        if (mediaIds.isEmpty()) {
            return
        }
        controller.removeMediaItemsByMediaIds(mediaIds)
        scope.launch {
            if (hideFromLibrary) {
                libraryExclusionsStore.hideMediaIds(mediaIds)
            }
            favoriteRepository.removeAll(mediaIds)
            playlistRepository.removeMediaIdsFromAll(mediaIds)
            playbackStatsRepository.deleteByIds(mediaIds)
            runCatching {
                controller?.invalidateLibrary()?.await(context)
            }
            libraryRefreshVersion.value += 1
        }
    }

    fun reclaimHiddenMediaIds(mediaIds: Set<String>) {
        if (mediaIds.isEmpty()) {
            return
        }
        controller.removeMediaItemsByMediaIds(mediaIds)
    }

    fun enqueueMediaItems(items: List<MediaItem>) {
        if (items.isEmpty()) {
            return
        }
        if (controller?.repeatMode == Player.REPEAT_MODE_ONE) {
            Toast.makeText(context, R.string.can_not_add_to_queue_single_repeat, Toast.LENGTH_SHORT)
                .show()
        } else {
            val deduplicatedItems = controller?.deduplicateQueueCandidates(items).orEmpty()
            if (deduplicatedItems.isNotEmpty()) {
                controller?.addMediaItems(deduplicatedItems)
                Toast.makeText(context, R.string.add_to_queue_success, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 在线歌曲的喜欢状态回写网易云账号。本地收藏为准，同步失败不回滚本地。 */
    suspend fun syncOnlineLikedState(mediaId: String, liked: Boolean) {
        val identity = mediaId.onlineTrackIdentityOrNull() ?: return
        if (identity.source != OnlineMusicProvider.Netease.sourceId) {
            return
        }
        runCatching { onlineRepositoryRouter.setTrackLiked(identity, liked) }
        // 在线喜欢变了就重拉一次；不在「我喜欢的歌曲」页时自增不会产生请求，仅清空已有快照。
        onlineLovedRefreshVersion.value += 1
    }

    fun toggleFavorite(mediaItem: MediaItem) {
        if (mediaItem.isExternalAudioLaunchItem()) {
            return
        }
        val mediaId = mediaItem.mediaId.takeIf(String::isNotBlank) ?: return
        scope.launch {
            val likedNow = favoriteRepository.toggle(mediaId)
            syncOnlineLikedState(mediaId, likedNow)
        }
    }

    fun toggleArtistAlbumViewMode() {
        val nextMode =
            if (artistAlbumViewModeProvider() == AlbumViewMode.List) {
                AlbumViewMode.Tile
            } else {
                AlbumViewMode.List
            }
        scope.launch {
            libraryDisplaySettingsStore.setArtistAlbumViewMode(nextMode)
        }
    }

    fun removeFavoriteMediaIds(mediaIds: Set<String>) {
        if (mediaIds.isEmpty()) {
            return
        }
        scope.launch {
            if (mediaIds.size == 1) {
                favoriteRepository.remove(mediaIds.first())
            } else {
                favoriteRepository.removeAll(mediaIds)
            }
            mediaIds.forEach { mediaId -> syncOnlineLikedState(mediaId, false) }
        }
    }

    fun refreshLibrary() {
        if (libraryRefreshing.value) {
            return
        }
        val playbackController = controller
        if (playbackController == null) {
            Toast.makeText(context, R.string.library_refresh_failed, Toast.LENGTH_SHORT).show()
            return
        }
        libraryRefreshing.value = true
        scope.launch {
            val result = runCatching {
                playbackController.refreshLibrary().await(context)
            }
                .getOrNull()
            libraryRefreshing.value = false
            if (result?.resultCode == SessionResult.RESULT_SUCCESS) {
                libraryRefreshVersion.value += 1
                Toast.makeText(context, R.string.library_refresh_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.library_refresh_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun requestSystemDeleteMediaIds(mediaIds: Set<String>) {
        if (mediaIds.isEmpty()) {
            return
        }
        val deleteItems = mediaIds.mapNotNull { mediaId ->
            val mediaStoreId = mediaId.trim().toLongOrNull() ?: return@mapNotNull null
            MediaStoreDeleteItem(
                mediaId = mediaId,
                uri = audioMediaItemUri(mediaStoreId),
            )
        }
        val deleteItemIds = deleteItems.mapTo(linkedSetOf(), MediaStoreDeleteItem::mediaId)
        val invalidMediaIds = mediaIds - deleteItemIds
        if (invalidMediaIds.isNotEmpty()) {
            cleanupDeletedSongs(invalidMediaIds, hideFromLibrary = true)
        }
        if (deleteItems.isEmpty()) {
            return
        }
        deleteCoordinator.delete(deleteItems)
    }
}
