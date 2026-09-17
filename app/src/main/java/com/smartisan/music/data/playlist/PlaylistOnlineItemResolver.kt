package com.smartisan.music.data.playlist

import android.content.Context
import androidx.media3.common.MediaItem
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.data.online.OnlineTrackIdentity
import com.smartisan.music.data.online.onlineTrackIdentityOrNull
import com.smartisan.music.data.online.withOnlinePlaybackPlaceholderUri

/** 从本地歌单的 mediaId 里筛出在线身份：trim、去空、去重，保持原有顺序。 */
internal fun onlineIdentityInPlaylistIds(mediaIds: Collection<String>): List<OnlineTrackIdentity> {
    return mediaIds
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull(String::onlineTrackIdentityOrNull)
        .distinct()
        .toList()
}

/**
 * 本地播放列表里在线条目的物料补齐。
 *
 * 本地歌单只存 mediaId（在线条目形如 `online:netease:<trackId>`），但曲目行需要标题/艺人/封面/时长
 * 才能展示与播放。这里按 mediaId 反查在线仓库，取回带完整元数据与在线占位 URI 的 [MediaItem]，
 * 让播放列表页能像「我喜欢的歌曲」一样并入在线条目展示与播放。
 *
 * 与 [com.smartisan.music.data.favorite.LovedSongsCloudSync] 同属「本地存 ID、在线补物料」模式：
 * 查询入口唯一，拉取失败由仓库层降级为空列表（页面只展示本地部分），这里不向上抛错。
 */
internal class PlaylistOnlineItemResolver(
    private val fetchMediaItems: suspend (List<OnlineTrackIdentity>) -> List<MediaItem>,
) {

    /** 反查这些 mediaId 对应的可展示媒体项；没有在线条目时直接返回空。 */
    suspend fun resolveOnlineMediaItems(mediaIds: Collection<String>): List<MediaItem> {
        val identities = onlineIdentityInPlaylistIds(mediaIds)
        if (identities.isEmpty()) {
            return emptyList()
        }
        return fetchMediaItems(identities)
    }

    companion object {
        @Volatile
        private var instance: PlaylistOnlineItemResolver? = null

        fun getInstance(context: Context): PlaylistOnlineItemResolver {
            return instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }
        }

        internal fun create(context: Context): PlaylistOnlineItemResolver {
            val onlineRouter = OnlineMusicRepositoryRouter.getInstance(context.applicationContext)
            return PlaylistOnlineItemResolver(
                fetchMediaItems = { identities ->
                    onlineRouter.getMediaItems(identities)
                        .map(MediaItem::withOnlinePlaybackPlaceholderUri)
                },
            )
        }
    }
}
