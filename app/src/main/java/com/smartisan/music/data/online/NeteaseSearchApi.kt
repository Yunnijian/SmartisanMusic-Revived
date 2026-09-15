package com.smartisan.music.data.online

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.IOException

/** 搜索域：歌曲/综合/歌手/专辑/歌单/热搜。 */

internal suspend fun NeteaseOnlineMusicRepository.searchPage(query: String): List<OnlineTrack> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) {
        return emptyList()
    }
    return cachedPage(
        key = cacheKey("search:songs", normalizedQuery),
        ttlMs = NeteaseSearchCacheTtlMs,
        codec = OnlinePageCacheCodecs.Tracks,
    ) {
        client.searchSongs(normalizedQuery, limit = SearchLimit)
    }
}

internal suspend fun NeteaseOnlineMusicRepository.searchAllPage(query: String): OnlineSearchResults {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) {
        return OnlineSearchResults(query = normalizedQuery)
    }
    val key = cacheKey("search:all", normalizedQuery)
    val nowMs = System.currentTimeMillis()
    // 只命中「有结果」的缓存：空结果（网络抖动/风控导致）不缓存，避免 5 分钟内持续返回空。
    NeteaseOnlineMemoryCache.getFreshValue<OnlineSearchResults>(key, NeteaseSearchCacheTtlMs, nowMs)
        ?.value
        ?.takeIf { it.hasResults }
        ?.let { return it }
    val diskEntry = pageDiskCache?.get(key, OnlinePageCacheCodecs.SearchResults)
    if (diskEntry != null && diskEntry.value.hasResults) {
        NeteaseOnlineMemoryCache.put(key, diskEntry.value, diskEntry.cachedAtMs)
        if (nowMs - diskEntry.cachedAtMs > NeteaseSearchCacheTtlMs) {
            refreshPageCacheInBackground(
                key = key,
                codec = OnlinePageCacheCodecs.SearchResults,
                loader = { performSearch(normalizedQuery) },
                // 后台刷新遇风控/空响应时不要覆盖已有的有效缓存。
                shouldCache = { it.hasResults },
            )
        }
        return diskEntry.value
    }
    return NeteaseOnlineMemoryCache.coalesceLoad("$key:cold") {
        val results = performSearch(normalizedQuery)
        // 仅缓存有结果的成功响应；空结果可能是瞬时风控，缓存它会让用户 5 分钟内持续看到空。
        if (results.hasResults) {
            val loadedAtMs = System.currentTimeMillis()
            NeteaseOnlineMemoryCache.put(key, results, loadedAtMs)
            pageDiskCache?.put(
                key = key,
                value = results,
                codec = OnlinePageCacheCodecs.SearchResults,
                cachedAtMs = loadedAtMs,
            )
        }
        results
    }
}

/**
 * 并行执行四类搜索。任一类型失败不拖垮其他类型（返回部分结果）；仅当全部失败时上抛异常，
 * 让 UI 显示 Error 而非静默吞成空列表。
 *
 * 注意：不能用 runCatching 包裹协程调用——它会吞掉 CancellationException 导致协程取消信号丢失。
 * 这里用 safeSearchCall 手动捕获非取消异常。
 */
internal suspend fun NeteaseOnlineMusicRepository.performSearch(
    query: String,
): OnlineSearchResults = coroutineScope {
    val tracksDeferred = async { safeSearchCall { client.searchSongs(query, limit = SearchLimit) } }
    val artistsDeferred = async { safeSearchCall { client.searchArtists(query, limit = ArtistSearchLimit) } }
    val albumsDeferred = async { safeSearchCall { client.searchAlbums(query, limit = AlbumSearchLimit) } }
    val playlistsDeferred = async { safeSearchCall { client.searchPlaylists(query, limit = PlaylistSearchLimit) } }

    val tracksResult = tracksDeferred.await()
    val artistsResult = artistsDeferred.await()
    val albumsResult = albumsDeferred.await()
    val playlistsResult = playlistsDeferred.await()

    // 全部失败时上抛最后一个异常，让 UI 显示 Error。
    if (tracksResult.isFailure && artistsResult.isFailure &&
        albumsResult.isFailure && playlistsResult.isFailure
    ) {
        throw tracksResult.exceptionOrNull()
            ?: artistsResult.exceptionOrNull()
            ?: albumsResult.exceptionOrNull()
            ?: playlistsResult.exceptionOrNull()
            ?: IOException("NetEase search failed for all categories")
    }
    OnlineSearchResults(
        query = query,
        tracks = tracksResult.getOrDefault(emptyList()),
        artists = artistsResult.getOrDefault(emptyList()),
        albums = albumsResult.getOrDefault(emptyList()),
        playlists = playlistsResult.getOrDefault(emptyList()),
    )
}

/**
 * 捕获搜索调用中的非取消异常，CancellationException 重新抛出以保留协程取消语义。
 */
internal suspend fun <T> NeteaseOnlineMusicRepository.safeSearchCall(
    block: suspend () -> List<T>,
): Result<List<T>> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

internal suspend fun NeteaseOnlineMusicRepository.searchArtistsPage(
    query: String,
): List<OnlineArtist> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) {
        return emptyList()
    }
    return client.searchArtists(normalizedQuery, limit = ArtistSearchLimit)
}

internal suspend fun NeteaseOnlineMusicRepository.searchAlbumsPage(
    query: String,
): List<OnlineAlbum> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) {
        return emptyList()
    }
    return client.searchAlbums(normalizedQuery, limit = AlbumSearchLimit)
}

internal suspend fun NeteaseOnlineMusicRepository.searchPlaylistsPage(
    query: String,
): List<OnlinePlaylist> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) {
        return emptyList()
    }
    return client.searchPlaylists(normalizedQuery, limit = PlaylistSearchLimit)
}

internal suspend fun NeteaseOnlineMusicRepository.searchHotKeywordsPage(): List<OnlineSearchHotKeyword> {
    return cachedPage(
        key = cacheKey("search:hot"),
        ttlMs = NeteaseSearchCacheTtlMs,
        codec = OnlinePageCacheCodecs.HotKeywords,
    ) {
        client.getHotSearchKeywords(limit = HotSearchLimit)
    }
}
