package com.smartisan.music.data.online

/** 歌词域：内存/磁盘三级读取与空歌词负缓存。 */

internal suspend fun NeteaseOnlineMusicRepository.lyricsPage(
    identity: OnlineTrackIdentity,
): OnlineLyrics? {
    if (identity.source != NeteaseSourceId) {
        return null
    }
    return cachedLyrics(identity)
}

internal suspend fun NeteaseOnlineMusicRepository.cachedLyrics(
    identity: OnlineTrackIdentity,
): OnlineLyrics {
    val scope = authCacheScope()
    val lyricsKey = cacheKey("lyrics", identity.trackId)
    NeteaseOnlineMemoryCache.getFresh<OnlineLyrics>(
        key = lyricsKey,
        ttlMs = NeteaseLyricsCacheTtlMs,
    )?.let { lyrics -> return lyrics }
    val emptyLyricsKey = cacheKey("lyrics-empty", identity.trackId)
    NeteaseOnlineMemoryCache.getFresh<Boolean>(
        key = emptyLyricsKey,
        ttlMs = NeteaseEmptyLyricsCacheTtlMs,
    )?.let {
        return OnlineLyrics(lyric = null, translatedLyric = null)
    }

    lyricsDiskCache?.get(identity, scope)
        ?.takeIf(OnlineLyrics::hasContent)
        ?.let { lyrics ->
            NeteaseOnlineMemoryCache.put(lyricsKey, lyrics)
            return lyrics
        }

    return client.getLyrics(identity.trackId).also { lyrics ->
        if (lyrics.hasContent()) {
            NeteaseOnlineMemoryCache.put(lyricsKey, lyrics)
            lyricsDiskCache?.put(identity, lyrics, scope)
        } else {
            NeteaseOnlineMemoryCache.put(emptyLyricsKey, true)
        }
    }
}
