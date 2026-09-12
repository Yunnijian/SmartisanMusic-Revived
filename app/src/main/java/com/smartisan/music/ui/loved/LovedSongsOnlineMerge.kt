package com.smartisan.music.ui.loved

import androidx.media3.common.MediaItem

/**
 * 「我喜欢的歌曲」的展示合并。
 *
 * 本地收藏只记录 mediaId，列表由「收藏 ∩ 可见歌曲」求得，在线歌曲不在本地媒体库里，
 * 因此需要先把在线喜欢的媒体项并入可见集合，交集才能命中。
 *
 * 与云端账号的收敛策略（差集、只补不删）已经下沉到 `data/favorite/LovedSongsCloudSync.kt`，
 * 这里只剩页面展示用的列表合并。
 */

/** 把在线喜欢的媒体项并入可见歌曲；本地项优先，mediaId 去重。 */
internal fun mergeLovedSongsMediaItems(
    localMediaItems: List<MediaItem>,
    onlineLovedMediaItems: List<MediaItem>,
): List<MediaItem> {
    if (onlineLovedMediaItems.isEmpty()) {
        return localMediaItems
    }
    return (localMediaItems + onlineLovedMediaItems).distinctBy(MediaItem::mediaId)
}
