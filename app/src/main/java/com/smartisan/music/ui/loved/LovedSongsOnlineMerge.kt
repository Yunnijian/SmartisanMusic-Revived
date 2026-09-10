package com.smartisan.music.ui.loved

import androidx.media3.common.MediaItem
import com.smartisan.music.data.online.OnlineMusicProvider
import com.smartisan.music.data.online.buildOnlineMediaId
import com.smartisan.music.data.online.onlineTrackIdentityOrNull

/**
 * 「我喜欢的歌曲」的在线合并逻辑。
 *
 * 本地收藏只记录 mediaId，列表由「收藏 ∩ 可见歌曲」求得，在线歌曲不在本地媒体库里，
 * 因此需要先把在线喜欢的媒体项并入可见集合，交集才能命中。
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

/**
 * 计算「云端喜欢但本地收藏缺失」的 mediaId 集合，用于单向收敛（只补不删）。
 *
 * @param cloudTrackIds 账号「我喜欢」的纯数字 trackId 集合；null/空表示未登录或拉取失败，此时不补。
 */
internal fun missingOnlineLikedMediaIds(
    cloudTrackIds: Set<String>?,
    localFavoriteMediaIds: Set<String>,
    source: String = OnlineMusicProvider.Netease.sourceId,
): Set<String> {
    if (cloudTrackIds.isNullOrEmpty()) {
        return emptySet()
    }
    val localOnlineMediaIds = localFavoriteMediaIds
        .asSequence()
        .filter { mediaId -> mediaId.onlineTrackIdentityOrNull()?.source == source }
        .toSet()
    return cloudTrackIds
        .asSequence()
        .map { trackId -> buildOnlineMediaId(source, trackId) }
        .filterNot { mediaId -> mediaId in localOnlineMediaIds }
        .toSet()
}
