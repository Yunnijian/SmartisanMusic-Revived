package com.smartisan.music.data.online

import android.net.Uri
import androidx.media3.common.MediaItem

/** 播放域：曲目详情、播放地址解析与 MediaItem 物化。 */

internal suspend fun NeteaseOnlineMusicRepository.trackPage(trackId: String): OnlineTrack? {
    // 不能再套一层同 key 的 getOrLoad：getTrack 内部已按同 key 合并缓存，
    // 嵌套会让内层加载 join 外层在途任务并 await 自己，永久挂起。
    return getTrack(trackId)
}

internal suspend fun NeteaseOnlineMusicRepository.getTrack(trackId: String): OnlineTrack? {
    val normalizedTrackId = trackId.trim().takeIf(String::isNotEmpty) ?: return null
    return NeteaseOnlineMemoryCache.getOrLoad(
        key = cacheKey("track", normalizedTrackId),
        ttlMs = NeteaseDetailCacheTtlMs,
    ) {
        client.getSongs(listOf(normalizedTrackId)).firstOrNull()
    }
}

internal suspend fun NeteaseOnlineMusicRepository.getTracks(trackIds: List<String>): List<OnlineTrack> {
    val normalizedIds = trackIds
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()
    if (normalizedIds.isEmpty()) {
        return emptyList()
    }
    return client.getSongs(normalizedIds)
}

internal suspend fun NeteaseOnlineMusicRepository.resolvePlayableTrack(
    track: OnlineTrack,
    includeLyrics: Boolean = true,
    forceRefresh: Boolean = false,
): MediaItem? {
    if (track.source != NeteaseSourceId) {
        return null
    }
    val identity = OnlineTrackIdentity(source = track.source, trackId = track.trackId)
    val playbackUrl = cachedPlaybackResult(track, forceRefresh).playbackUrl ?: return null
    val lyrics = if (includeLyrics) {
        runSuspendCatching { cachedLyrics(identity) }.getOrNull()
    } else {
        null
    }
    return track.toMediaItem(
        playbackUrl = playbackUrl.url,
        mimeType = playbackUrl.mimeType,
        lyrics = lyrics,
    )
}

internal suspend fun NeteaseOnlineMusicRepository.cachedPlaybackResult(
    track: OnlineTrack,
    forceRefresh: Boolean,
): NeteasePlaybackParseResult {
    val requestedQuality = playbackQualityProvider()
    val key = cacheKey(
        "playback-url",
        track.trackId,
        requestedQuality.preferenceValue,
    )
    if (!forceRefresh) {
        NeteaseOnlineMemoryCache.getFresh<OnlinePlaybackUrl>(
            key = key,
            ttlMs = OnlinePlaybackUrlMaxAgeMs,
        )?.let { playbackUrl ->
            return NeteasePlaybackParseResult(
                status = NeteasePlaybackParseStatus.Success,
                playbackUrl = playbackUrl,
            )
        }
    }
    val resultKey = if (forceRefresh) "$key:result:force" else "$key:result"
    val result = NeteaseOnlineMemoryCache.coalesceLoad(resultKey) {
        client.getPlaybackUrlResult(
            trackId = track.trackId,
            originalDurationMs = track.durationMs,
            requestedQuality = requestedQuality,
        )
    }
    result.playbackUrl?.let { playbackUrl ->
        NeteaseOnlineMemoryCache.put(key, playbackUrl)
    }
    return result
}

internal suspend fun NeteaseOnlineMusicRepository.resolvePlaybackUri(
    identity: OnlineTrackIdentity,
): Uri {
    if (identity.source != NeteaseSourceId) {
        throw OnlinePlaybackResolutionException(
            reason = OnlinePlaybackFailureReason.Unavailable,
            message = "Unsupported online source ${identity.source}",
        )
    }
    val track = getTrack(identity.trackId) ?: throw OnlinePlaybackResolutionException(
        reason = OnlinePlaybackFailureReason.Unavailable,
        message = "Online track ${identity.source}/${identity.trackId} is unavailable",
    )
    val result = cachedPlaybackResult(track, forceRefresh = false)
    val playbackUrl = result.playbackUrl ?: throw OnlinePlaybackResolutionException(
        reason = result.status.toOnlinePlaybackFailureReason(),
        message = "Unable to resolve online playback uri for ${identity.source}/${identity.trackId}: ${result.status}",
    )
    return Uri.parse(playbackUrl.url)
}

private fun NeteasePlaybackParseStatus.toOnlinePlaybackFailureReason(): OnlinePlaybackFailureReason {
    return when (this) {
        NeteasePlaybackParseStatus.Preview -> OnlinePlaybackFailureReason.PreviewOnly
        NeteasePlaybackParseStatus.RequiresLogin -> OnlinePlaybackFailureReason.LoginRequired
        NeteasePlaybackParseStatus.Success,
        NeteasePlaybackParseStatus.Unavailable -> OnlinePlaybackFailureReason.Unavailable
    }
}

internal suspend fun NeteaseOnlineMusicRepository.resolvePlayableMediaItemPage(
    mediaItem: MediaItem,
    includeLyrics: Boolean,
    forceRefresh: Boolean,
): MediaItem? {
    val identity = mediaItem.onlineIdentityOrNull() ?: return null
    if (identity.source != NeteaseSourceId) {
        return null
    }
    val fallbackTrack = mediaItem.toOnlineTrackFallback(identity)
    val track = fallbackTrack ?: getTrack(identity.trackId) ?: return null
    return resolvePlayableTrack(
        track = track,
        includeLyrics = includeLyrics,
        forceRefresh = forceRefresh,
    )
}

internal suspend fun NeteaseOnlineMusicRepository.resolvePlayableMediaItemsPage(
    mediaItems: List<MediaItem>,
    includeLyrics: Boolean,
): List<MediaItem> {
    val entries = mediaItems.mapNotNull { item ->
        val identity = item.onlineIdentityOrNull()
            ?.takeIf { identity -> identity.source == NeteaseSourceId }
            ?: return@mapNotNull null
        item to identity
    }
    if (entries.isEmpty()) {
        return emptyList()
    }
    val unresolvedEntries = entries.filter { (item, _) ->
        item.localConfiguration?.uri == null || item.shouldRefreshOnlinePlaybackUrl()
    }
    val detailsById = if (unresolvedEntries.isEmpty()) {
        emptyMap()
    } else {
        getTracks(unresolvedEntries.map { (_, identity) -> identity.trackId })
            .associateBy(OnlineTrack::trackId)
    }
    return entries.mapNotNull { (item, identity) ->
        if (item.localConfiguration?.uri != null && !item.shouldRefreshOnlinePlaybackUrl()) {
            return@mapNotNull item
        }
        val track = detailsById[identity.trackId]
            ?: item.toOnlineTrackFallback(identity)
            ?: return@mapNotNull null
        resolvePlayableTrack(
            track = track,
            includeLyrics = includeLyrics,
        )
    }
}
