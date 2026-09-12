package com.smartisan.music.data.online

import com.smartisan.music.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

internal const val NeteaseFeaturedCacheTtlMs = 10 * 60 * 1000L
internal const val NeteaseDetailCacheTtlMs = 10 * 60 * 1000L
internal const val NeteaseAccountCacheTtlMs = 2 * 60 * 1000L
internal const val NeteaseSearchCacheTtlMs = 5 * 60 * 1000L
internal const val NeteaseLyricsCacheTtlMs = 7L * 24L * 60L * 60L * 1000L
internal const val NeteaseEmptyLyricsCacheTtlMs = 5L * 60L * 1000L

internal object NeteaseOnlineMemoryCache {
    /**
     * 内存条目容量上限：超出后按访问顺序（LRU）淘汰最久未使用的条目，避免长会话下无界增长。
     *
     * 播放地址、歌曲详情、歌词与页面分节共用这一个桶（约 3 条/首），因此取值要能容纳
     * 整条播放队列，否则回听旧歌要重新联网解析。
     */
    private const val MaxEntryCount = 1024

    /**
     * accessOrder = true 的 [LinkedHashMap] 即 LRU 容器：读命中也会把条目移到队尾，
     * 插入后超出 [MaxEntryCount] 时淘汰队首。
     *
     * 它本身不是线程安全的，所以对它的每次读写都在 [entriesLock] 下完成，线程安全性不弱于
     * 原先的 ConcurrentHashMap 实现；TTL 判定用的是取条目时锁内的 loadedAtMs，在锁外比较即可。
     * 同一 key 的重复加载仍由 [inFlightLoads] 合并。
     */
    private val entriesLock = Any()
    private val entries = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > MaxEntryCount
        }
    }
    private val inFlightLoads = ConcurrentHashMap<String, Deferred<Any?>>()
    private val loadScope = CoroutineScope(SupervisorJob() + AppDispatchers.IO)

    @Suppress("UNCHECKED_CAST")
    fun <T> getFreshValue(
        key: String,
        ttlMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): FreshValue<T>? {
        val cachedEntry = synchronized(entriesLock) { entries[key] }
        return cachedEntry
            ?.takeIf { entry -> nowMs - entry.loadedAtMs <= ttlMs }
            ?.let { entry -> FreshValue(entry.unboxedValue() as T) }
    }

    fun <T : Any> getFresh(
        key: String,
        ttlMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): T? {
        return getFreshValue<T?>(key, ttlMs, nowMs)?.value
    }

    fun put(
        key: String,
        value: Any?,
        loadedAtMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(entriesLock) {
            entries[key] = CacheEntry(
                value = value ?: NullValue,
                loadedAtMs = loadedAtMs,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> getOrLoad(
        key: String,
        ttlMs: Long,
        loader: suspend () -> T,
    ): T {
        getFreshValue<T>(key, ttlMs)?.let { cached -> return cached.value }
        return loadCoalesced(key) {
            val value = loader()
            put(key, value)
            value
        }
    }

    suspend fun <T : Any> getOrLoadNonNull(
        key: String,
        ttlMs: Long,
        loader: suspend () -> T?,
    ): T? {
        getFresh<T>(key, ttlMs)?.let { cached -> return cached }
        return loadCoalesced(key) {
            loader()?.also { value -> put(key, value) }
        }
    }

    suspend fun <T> coalesceLoad(
        key: String,
        loader: suspend () -> T,
    ): T {
        return loadCoalesced(key, loader)
    }

    private suspend fun <T> loadCoalesced(
        key: String,
        loader: suspend () -> T,
    ): T {
        val newLoad = loadScope.async(start = CoroutineStart.LAZY) {
            loader() as Any?
        }
        val activeLoad = inFlightLoads.putIfAbsent(key, newLoad)
        val load = activeLoad ?: newLoad.also { pendingLoad ->
            pendingLoad.invokeOnCompletion {
                inFlightLoads.remove(key, pendingLoad)
            }
            pendingLoad.start()
        }
        if (activeLoad != null) {
            newLoad.cancel()
        }
        @Suppress("UNCHECKED_CAST")
        return load.await() as T
    }

    fun invalidate(prefix: String) {
        synchronized(entriesLock) {
            entries.keys.removeAll { key -> key.startsWith(prefix) }
        }
        cancelInFlightLoads { key -> key.startsWith(prefix) }
    }

    /**
     * 中止与 [shouldCancel] 匹配的在途合并加载（不删除已缓存的结果）。
     *
     * 这是进程级 [loadScope] 的取消入口：[NeteaseOnlineMemoryCache] 是常驻单例且没有重建时机，
     * 整体取消它的 SupervisorJob 会让之后每一次合并加载立刻失败，所以只能按 key 精确中止待完成的任务。
     * 调用方必须排除播放地址解析相关的 key——那些加载有正在 await 的播放链路，取消会直接造成播放失败。
     */
    fun cancelInFlightLoads(shouldCancel: (String) -> Boolean) {
        inFlightLoads.entries.removeIf { entry ->
            if (shouldCancel(entry.key)) {
                entry.value.cancel()
                true
            } else {
                false
            }
        }
    }

    private data class CacheEntry(
        val value: Any,
        val loadedAtMs: Long,
    ) {
        fun unboxedValue(): Any? {
            return if (value === NullValue) null else value
        }
    }

    data class FreshValue<T>(
        val value: T,
    )

    private object NullValue
}
