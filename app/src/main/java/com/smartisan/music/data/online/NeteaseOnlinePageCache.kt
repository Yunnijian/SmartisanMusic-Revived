package com.smartisan.music.data.online

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * [NeteaseOnlineMusicRepository] 的通用页缓存读写与后台刷新登记。
 *
 * 与具体 API 域无关：内存 → 磁盘 → 联网三级读取、过期后台回填、同 key 刷新去重
 * 都由这里提供，各域文件只负责给出 key 与 loader。
 */

internal suspend fun <T : Any> NeteaseOnlineMusicRepository.cachedPage(
    key: String,
    ttlMs: Long,
    codec: OnlinePageCacheCodec<T>,
    loader: suspend () -> T,
): T {
    val nowMs = System.currentTimeMillis()
    NeteaseOnlineMemoryCache.getFresh<T>(key, ttlMs, nowMs)?.let { value ->
        return value
    }
    val diskEntry = pageDiskCache?.get(key, codec)
    if (diskEntry != null) {
        NeteaseOnlineMemoryCache.put(
            key = key,
            value = diskEntry.value,
            loadedAtMs = diskEntry.cachedAtMs,
        )
        if (nowMs - diskEntry.cachedAtMs > ttlMs) {
            refreshPageCacheInBackground(
                key = key,
                codec = codec,
                loader = loader,
            )
        }
        return diskEntry.value
    }
    return NeteaseOnlineMemoryCache.getOrLoad(
        key = key,
        ttlMs = ttlMs,
    ) {
        loader().also { value ->
            val loadedAtMs = System.currentTimeMillis()
            pageDiskCache?.put(
                key = key,
                value = value,
                codec = codec,
                cachedAtMs = loadedAtMs,
            )
        }
    }
}

internal suspend fun <T : Any> NeteaseOnlineMusicRepository.loadAndPersistNullablePage(
    key: String,
    codec: OnlinePageCacheCodec<T>,
    loader: suspend () -> T?,
): T? {
    return loader().also { value ->
        val loadedAtMs = System.currentTimeMillis()
        if (value != null) {
            pageDiskCache?.put(
                key = key,
                value = value,
                codec = codec,
                cachedAtMs = loadedAtMs,
            )
        }
    }
}

internal suspend fun <T : Any> NeteaseOnlineMusicRepository.cachedNullablePage(
    key: String,
    ttlMs: Long,
    codec: OnlinePageCacheCodec<T>,
    loader: suspend () -> T?,
): T? {
    val nowMs = System.currentTimeMillis()
    NeteaseOnlineMemoryCache.getFreshValue<T?>(key, ttlMs, nowMs)?.let { cached ->
        return cached.value
    }
    val diskEntry = pageDiskCache?.get(key, codec)
    if (diskEntry != null) {
        NeteaseOnlineMemoryCache.put(
            key = key,
            value = diskEntry.value,
            loadedAtMs = diskEntry.cachedAtMs,
        )
        if (nowMs - diskEntry.cachedAtMs > ttlMs) {
            refreshNullablePageCacheInBackground(
                key = key,
                codec = codec,
                loader = loader,
            )
        }
        return diskEntry.value
    }
    return NeteaseOnlineMemoryCache.getOrLoad(
        key = key,
        ttlMs = ttlMs,
    ) {
        loadAndPersistNullablePage(
            key = key,
            codec = codec,
            loader = loader,
        )
    }
}

internal fun <T : Any> NeteaseOnlineMusicRepository.refreshPageCacheInBackground(
    key: String,
    codec: OnlinePageCacheCodec<T>,
    loader: suspend () -> T,
    shouldCache: (T) -> Boolean = { true },
) {
    val scope = pageCacheRefreshScope ?: return
    val handle = beginPageCacheRefresh(key) ?: return
    emitCacheRefreshEvent(key, OnlineCacheRefreshEventKind.Started)
    scope.launch(handle) {
        try {
            val value = loader()
            if (!shouldCache(value)) {
                return@launch
            }
            val loadedAtMs = System.currentTimeMillis()
            NeteaseOnlineMemoryCache.put(key, value, loadedAtMs)
            pageDiskCache?.put(
                key = key,
                value = value,
                codec = codec,
                cachedAtMs = loadedAtMs,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Keep serving stale cache if a background refresh fails.
        }
    }.invokeOnCompletion {
        endPageCacheRefresh(key, handle)
        emitCacheRefreshEvent(key, OnlineCacheRefreshEventKind.Finished)
    }
}

internal fun <T : Any> NeteaseOnlineMusicRepository.refreshNullablePageCacheInBackground(
    key: String,
    codec: OnlinePageCacheCodec<T>,
    loader: suspend () -> T?,
) {
    val scope = pageCacheRefreshScope ?: return
    val handle = beginPageCacheRefresh(key) ?: return
    emitCacheRefreshEvent(key, OnlineCacheRefreshEventKind.Started)
    scope.launch(handle) {
        try {
            val value = loader()
            val loadedAtMs = System.currentTimeMillis()
            NeteaseOnlineMemoryCache.put(key, value, loadedAtMs)
            if (value != null) {
                pageDiskCache?.put(
                    key = key,
                    value = value,
                    codec = codec,
                    cachedAtMs = loadedAtMs,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Keep serving stale cache if a background refresh fails.
        }
    }.invokeOnCompletion {
        endPageCacheRefresh(key, handle)
        emitCacheRefreshEvent(key, OnlineCacheRefreshEventKind.Finished)
    }
}

/** 登记一次后台刷新并返回取消句柄；同一 key 已有刷新在途时返回 null（保持原有去重语义）。 */
internal fun NeteaseOnlineMusicRepository.beginPageCacheRefresh(key: String): Job? {
    synchronized(pageCacheRefreshLock) {
        if (pageCacheRefreshHandles.containsKey(key)) {
            return null
        }
        return Job().also { handle -> pageCacheRefreshHandles[key] = handle }
    }
}

internal fun NeteaseOnlineMusicRepository.endPageCacheRefresh(key: String, handle: Job) {
    synchronized(pageCacheRefreshLock) {
        if (pageCacheRefreshHandles[key] === handle) {
            pageCacheRefreshHandles.remove(key)
        }
    }
}

/**
 * [pageCacheRefreshScope] 的取消入口：中止 [prefix] 缓存前缀下在途的后台页缓存刷新。
 *
 * 只取消刷新任务，不取消 scope 自身（进程级常驻，之后还要继续用），
 * 也不触碰磁盘清理任务与播放地址解析（后者不走这个作用域）。
 */
internal fun NeteaseOnlineMusicRepository.cancelPendingPageCacheRefreshes(prefix: String) {
    val handles = synchronized(pageCacheRefreshLock) {
        pageCacheRefreshHandles
            .filterKeys { key -> key.startsWith(prefix) }
            .values
            .toList()
    }
    handles.forEach { handle -> handle.cancel() }
}

internal fun NeteaseOnlineMusicRepository.emitCacheRefreshEvent(
    key: String,
    kind: OnlineCacheRefreshEventKind,
) {
    mutableCacheRefreshEvents.tryEmit(
        OnlineCacheRefreshEvent(
            provider = provider,
            cacheKey = key,
            kind = kind,
        ),
    )
}
