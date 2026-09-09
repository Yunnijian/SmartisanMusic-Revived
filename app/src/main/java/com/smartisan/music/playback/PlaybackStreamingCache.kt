@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.smartisan.music.data.online.OnlineMediaIdPrefix
import com.smartisan.music.data.online.OnlinePlaybackUriScheme
import java.io.File
import java.net.URI

/**
 * 在线播放的媒体流缓存：
 * - 以 SimpleCache（LRU 淘汰，容量取可用空间的 1/8，夹在 128MB~2GB）落盘缓存已下载的分片；
 * - 在线条目的 DataSpec 携带 customCacheKey（online:source:trackId），URL 过期重解析后仍命中同一份缓存；
 * - 缓存不可用（如磁盘满、数据库损坏）时退回直连上游，不影响本地播放。
 */
internal object PlaybackStreamingCache {
    private const val CacheDirectoryName = "media_cache"

    private val lock = Any()

    @Volatile
    private var sharedCache: SimpleCache? = null

    fun createDataSourceFactory(
        context: Context,
        upstreamFactory: DataSource.Factory,
    ): DataSource.Factory {
        val appContext = context.applicationContext
        val cache = runCatching {
            getOrCreateCache(appContext)
        }.getOrNull() ?: return upstreamFactory

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheKeyFactory(SmartisanPlaybackCacheKeyFactory)
            .setFlags(
                CacheDataSource.FLAG_BLOCK_ON_CACHE or
                    CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
            )
        return DefaultDataSource.Factory(appContext, cacheDataSourceFactory)
    }

    fun getOrCreateCache(context: Context): Cache {
        sharedCache?.let { cache -> return cache }
        return synchronized(lock) {
            val appContext = context.applicationContext
            val cacheDirectory = File(appContext.cacheDir, CacheDirectoryName)
            sharedCache ?: SimpleCache(
                cacheDirectory,
                LeastRecentlyUsedCacheEvictor(
                    playbackStreamingMaxCacheSizeBytes(appContext.cacheDir.usableSpace),
                ),
                StandaloneDatabaseProvider(appContext),
            ).also { cache ->
                sharedCache = cache
            }
        }
    }
}

private val SmartisanPlaybackCacheKeyFactory = CacheKeyFactory { dataSpec ->
    playbackStreamingCacheKey(dataSpec)
}

internal fun playbackStreamingCacheKey(dataSpec: DataSpec): String {
    return playbackStreamingCacheKey(
        explicitKey = dataSpec.key,
        uri = dataSpec.uri.toString(),
    )
}

internal fun playbackStreamingCacheKey(
    explicitKey: String?,
    uri: String,
): String {
    return explicitKey
        ?: uri.onlinePlaybackCacheKeyOrNull()
        ?: uri
}

internal fun playbackStreamingMaxCacheSizeBytes(usableSpaceBytes: Long): Long {
    if (usableSpaceBytes <= 0L) {
        return DefaultMaxCacheSizeBytes
    }
    return (usableSpaceBytes / CacheUsableSpaceDivisor)
        .coerceIn(MinMaxCacheSizeBytes, MaxMaxCacheSizeBytes)
}

private fun String.onlinePlaybackCacheKeyOrNull(): String? {
    val parsedUri = runCatching { URI(this) }.getOrNull() ?: return null
    if (parsedUri.scheme != OnlinePlaybackUriScheme) {
        return null
    }
    val source = parsedUri.host?.takeIf(String::isNotBlank) ?: return null
    val trackId = parsedUri.path
        ?.trim('/')
        ?.substringBefore('/')
        ?.takeIf(String::isNotBlank)
        ?: return null
    return "$OnlineMediaIdPrefix$source:$trackId"
}

private const val CacheUsableSpaceDivisor = 8L
private const val MinMaxCacheSizeBytes = 128L * 1024L * 1024L
private const val DefaultMaxCacheSizeBytes = 1024L * 1024L * 1024L
private const val MaxMaxCacheSizeBytes = 2L * 1024L * 1024L * 1024L
