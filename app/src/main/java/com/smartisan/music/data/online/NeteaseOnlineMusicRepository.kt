package com.smartisan.music.data.online

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import com.smartisan.music.AppDispatchers
import com.smartisan.music.data.settings.NeteaseAudioQuality
import com.smartisan.music.data.settings.OnlineMusicSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal val NeteaseSourceId = OnlineMusicProvider.Netease.sourceId
internal const val SearchLimit = 30
internal const val ArtistSearchLimit = 30
internal const val AlbumSearchLimit = 30
internal const val PlaylistSearchLimit = 30
internal const val HotSearchLimit = 20
internal const val FeaturedArtistLimit = 30
internal const val FeaturedBannerLimit = 6
private const val FeaturedHomeTrackLimit = 12
private const val FeaturedHomeSectionCount = 5
internal const val FeaturedPlaylistLimit = 18
internal const val FeaturedChartLimit = 12
internal const val FeaturedAlbumLimit = 18
internal const val FeaturedRadioTrackLimit = 18
internal const val FeaturedRadioLimit = 18
internal const val ArtistTopTracksLimit = 60
internal const val AlbumTracksLimit = 80
internal const val RadioTracksLimit = 80
private const val FeaturedPlaylistId = "3778678"
private const val FeaturedLimit = 30
internal const val AccountPlaylistLimit = 50
internal const val AccountAlbumLimit = 50
internal const val AccountRadioLimit = 50
internal const val OnlinePlaybackUrlMaxAgeMs = 15 * 60 * 1000L
internal const val NeteaseLoginCookieName = "MUSIC_U"

/**
 * [runCatching] 的协程安全替代：只把非取消异常收敛成失败结果，
 * [CancellationException] 原样重抛，避免取消信号被吞后继续执行后续写缓存等副作用。
 *
 * 包裹 suspend 调用（或 [kotlinx.coroutines.withContext] 内的阻塞网络调用）时一律用它；
 * 纯 CPU 解析（如 JSONObject 构造）继续用 runCatching 即可。
 */
internal inline fun <T> runSuspendCatching(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

/**
 * 播放地址解析链路用到的缓存命名空间。
 * 登录/换号中止旧会话在途加载时必须整体跳过这些 key：它们正被播放链路 await，
 * 取消会直接导致本次播放失败。
 */
private val PlaybackResolutionNamespaces = listOf("playback-url", "track", "lyrics", "lyrics-empty")

internal class NeteaseOnlineMusicRepository(
    private val authStore: NeteaseAuthStore? = null,
    private val playbackQualityProvider: suspend () -> NeteaseAudioQuality = { NeteaseAudioQuality.ExHigh },
    private val client: NeteaseCloudMusicClient = NeteaseCloudMusicClient(
        cookieProvider = { authStore?.getCookies().orEmpty() },
        playbackQualityProvider = playbackQualityProvider,
    ),
    private val lyricsDiskCache: OnlineLyricsDiskCache? = null,
    private val pageDiskCache: OnlinePageDiskCache? = null,
    private val pageCacheRefreshScope: CoroutineScope? = null,
) : OnlineMusicProviderRepository {

    override val provider: OnlineMusicProvider = OnlineMusicProvider.Netease
    private val mutableCacheRefreshEvents = MutableSharedFlow<OnlineCacheRefreshEvent>(
        extraBufferCapacity = 32,
    )
    override val cacheRefreshEvents: SharedFlow<OnlineCacheRefreshEvent> = mutableCacheRefreshEvents

    /**
     * 在途的后台页缓存刷新：cacheKey -> 取消句柄。
     * 同一个 key 只允许一个刷新在途（句柄存在即代表在途），账号态变化时按句柄取消。
     */
    private val pageCacheRefreshLock = Any()
    private val pageCacheRefreshHandles = HashMap<String, Job>()

    /** 上一次观测到的账号缓存域，用于识别登录 / 登出 / 换号。 */
    private val authScopeLock = Any()
    private var lastObservedAuthCacheScope: String? = null

    /**
     * 账号缓存域的内存快照与生成它时的 [NeteaseAuthStore] 写入序号。
     *
     * 缓存键构造（search/featured 取键）发生在主线程调用路径上，而 `authStore.load()` 每次都要
     * Keystore 解密 + JSON 解析；序号未变时直接复用快照，只有登录/登出等写清除才重新加载。
     */
    private var authScopeSnapshot: String? = null
    private var authScopeSnapshotRevision: Long = -1L

    constructor(context: Context) : this(
        authStore = NeteaseAuthStore(context.applicationContext),
        playbackQualityProvider = {
            OnlineMusicSettingsStore(context.applicationContext)
                .readSettings()
                .neteasePlaybackQuality
        },
        lyricsDiskCache = OnlineLyricsDiskCache(context.applicationContext),
        pageDiskCache = OnlinePageDiskCache(context.applicationContext),
        pageCacheRefreshScope = CoroutineScope(SupervisorJob() + AppDispatchers.IO),
    )

    private fun cacheKey(namespace: String, vararg parts: Any?): String {
        return buildString {
            append("netease:")
            append(authCacheScope())
            append(':')
            append(namespace)
            for (part in parts) {
                append(':')
                append(part?.toString().orEmpty())
            }
        }
    }

    private fun cachePrefix(namespace: String): String {
        return "netease:${authCacheScope()}:$namespace"
    }

    private fun loadAuthCacheScope(): String {
        val state = authStore?.load()
        return when {
            state == null || !state.isLoggedIn -> "anon"
            else -> "user:${state.savedAt}:${state.cookies[NeteaseLoginCookieName]?.hashCode() ?: 0}"
        }
    }

    private fun authCacheScope(): String {
        val (scope, previousScope) = synchronized(authScopeLock) {
            val revision = NeteaseAuthStore.authScopeRevision()
            val currentScope =
                authScopeSnapshot
                    ?.takeIf { authScopeSnapshotRevision == revision }
                    ?: loadAuthCacheScope().also { loadedScope ->
                        authScopeSnapshot = loadedScope
                        authScopeSnapshotRevision = revision
                    }
            val previous = lastObservedAuthCacheScope
            lastObservedAuthCacheScope = currentScope
            currentScope to previous?.takeIf { it != currentScope }
        }
        if (previousScope != null) {
            onAccountCacheScopeChanged(previousScope)
        }
        return scope
    }

    /**
     * 登录 / 登出 / 换号导致账号缓存域变化时的清理入口（两个进程级 CoroutineScope 的取消点）。
     *
     * 1. 取消尚未完成的后台页缓存刷新：旧会话的刷新结果晚点落地会覆盖新账号的数据；
     * 2. 中止旧账号域在途的合并加载，但跳过播放地址解析链路的 key——那类加载正被播放链路
     *    await，取消会直接造成本次播放失败。
     *
     * 两个 CoroutineScope 自身仍不取消：本 Repository 由进程级 Router 单例持有，没有重建时机，
     * 取消它们的 SupervisorJob 会让之后所有缓存读取与后台刷新永久失效。
     */
    private fun onAccountCacheScopeChanged(previousScope: String) {
        val previousPrefix = "netease:$previousScope:"
        // 旧账号域的内存+磁盘页缓存整体作废，避免换号/登出后读到旧账号残留数据。
        invalidatePageCache(previousPrefix)
        NeteaseOnlineMemoryCache.cancelInFlightLoads { key ->
            key.startsWith(previousPrefix) &&
                PlaybackResolutionNamespaces.none { namespace -> key.contains(":$namespace:") }
        }
    }

    private fun invalidateAccountCaches() {
        invalidatePageCache(cachePrefix("account"))
        invalidatePageCache(cachePrefix("liked"))
    }

    private fun invalidatePageCache(prefix: String) {
        // 先中止该前缀下在途的后台刷新：否则它会把失效前的旧结果写回内存与磁盘缓存。
        cancelPendingPageCacheRefreshes(prefix)
        NeteaseOnlineMemoryCache.invalidate(prefix)
        pageCacheRefreshScope?.launch {
            pageDiskCache?.removePrefix(prefix)
        }
    }

    private fun invalidatePlaylistTrackCaches(playlistId: String) {
        invalidatePageCache(cacheKey("playlist:tracks", playlistId))
        invalidatePageCache(cacheKey("account:playlist-tracks", playlistId))
    }

    /**
     * 下拉刷新入口：同步作废给定命名空间的内存与磁盘页缓存，使随后的加载必定联网。
     *
     * 不能复用 [invalidatePageCache]：它的磁盘删除是异步的，紧跟其后的 reload 仍可能
     * 读到旧磁盘条目并把它回填内存，刷新就成了空转。这里在 IO 上等删除完成再返回。
     */
    override suspend fun invalidatePageCaches(vararg namespaces: String) {
        withContext(AppDispatchers.IO) {
            namespaces.forEach { namespace ->
                val prefix = cachePrefix(namespace)
                cancelPendingPageCacheRefreshes(prefix)
                NeteaseOnlineMemoryCache.invalidate(prefix)
                pageDiskCache?.removePrefix(prefix)
            }
        }
    }

    private suspend fun <T : Any> cachedPage(
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

    private suspend fun <T : Any> loadAndPersistNullablePage(
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

    private suspend fun <T : Any> cachedNullablePage(
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

    private fun <T : Any> refreshPageCacheInBackground(
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

    private fun <T : Any> refreshNullablePageCacheInBackground(
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
    private fun beginPageCacheRefresh(key: String): Job? {
        synchronized(pageCacheRefreshLock) {
            if (pageCacheRefreshHandles.containsKey(key)) {
                return null
            }
            return Job().also { handle -> pageCacheRefreshHandles[key] = handle }
        }
    }

    private fun endPageCacheRefresh(key: String, handle: Job) {
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
    private fun cancelPendingPageCacheRefreshes(prefix: String) {
        val handles = synchronized(pageCacheRefreshLock) {
            pageCacheRefreshHandles
                .filterKeys { key -> key.startsWith(prefix) }
                .values
                .toList()
        }
        handles.forEach { handle -> handle.cancel() }
    }

    private fun emitCacheRefreshEvent(
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

    override suspend fun search(query: String): List<OnlineTrack> {
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

    override suspend fun searchAll(query: String): OnlineSearchResults {
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
    private suspend fun performSearch(query: String): OnlineSearchResults = coroutineScope {
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
    private suspend fun <T> safeSearchCall(block: suspend () -> List<T>): Result<List<T>> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun searchArtists(query: String): List<OnlineArtist> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            return emptyList()
        }
        return client.searchArtists(normalizedQuery, limit = ArtistSearchLimit)
    }

    override suspend fun searchAlbums(query: String): List<OnlineAlbum> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            return emptyList()
        }
        return client.searchAlbums(normalizedQuery, limit = AlbumSearchLimit)
    }

    override suspend fun searchPlaylists(query: String): List<OnlinePlaylist> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            return emptyList()
        }
        return client.searchPlaylists(normalizedQuery, limit = PlaylistSearchLimit)
    }

    override suspend fun searchHotKeywords(): List<OnlineSearchHotKeyword> {
        return cachedPage(
            key = cacheKey("search:hot"),
            ttlMs = NeteaseSearchCacheTtlMs,
            codec = OnlinePageCacheCodecs.HotKeywords,
        ) {
            client.getHotSearchKeywords(limit = HotSearchLimit)
        }
    }

    override suspend fun featuredTracks(): List<OnlineTrack> {
        return cachedPage(
            key = cacheKey("featured:tracks"),
            ttlMs = NeteaseFeaturedCacheTtlMs,
            codec = OnlinePageCacheCodecs.Tracks,
        ) {
            currentUserDailyRecommendedTracks(limit = FeaturedLimit)
                ?.takeIf(List<OnlineTrack>::isNotEmpty)
                ?: featuredSongs()
        }
    }

    override suspend fun featuredHome(): OnlineMusicHome {
        return cachedPage(
            key = cacheKey("featured:home"),
            ttlMs = NeteaseFeaturedCacheTtlMs,
            codec = OnlinePageCacheCodecs.MusicHome,
        ) {
            val failures = mutableListOf<Throwable>()
            val tracks = featuredSection(failures) {
                featuredTracks().take(FeaturedHomeTrackLimit)
            }
            val playlists = featuredSection(failures) {
                featuredPlaylists()
            }
            val charts = featuredSection(failures) {
                featuredCharts()
            }
            val albums = featuredSection(failures) {
                featuredAlbums()
            }
            val artists = featuredSection(failures) {
                featuredArtists()
            }
            // 全部 5 个分节都失败 = 整页不可用，上抛让 UI 显示 Error 而不是静默的空态；
            // 只要有一节成功就照常渲染（部分成功仍是 Success）。
            if (failures.size == FeaturedHomeSectionCount) {
                throw failures.last()
            }
            OnlineMusicHome(
                tracks = tracks,
                playlists = playlists,
                charts = charts,
                albums = albums,
                artists = artists,
            )
        }
    }

    /**
     * 推荐页单个分节的加载：失败退化为空列表并记录下来，供上层判断是否整页都失败了。
     *
     * 不能用 runCatching——它会吞掉 CancellationException 导致协程取消信号丢失。
     */
    private suspend fun <T> featuredSection(
        failures: MutableList<Throwable>,
        block: suspend () -> List<T>,
    ): List<T> {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failures += e
            emptyList()
        }
    }

    override suspend fun featuredBanners(): List<OnlineBanner> {
        return cachedPage(
            key = cacheKey("featured:banners"),
            ttlMs = NeteaseFeaturedCacheTtlMs,
            codec = OnlinePageCacheCodecs.Banners,
        ) {
            client.getBanners(limit = FeaturedBannerLimit)
        }
    }

    override suspend fun featuredPlaylists(): List<OnlinePlaylist> {
        return cachedPage(
            key = cacheKey("featured:playlists"),
            ttlMs = NeteaseFeaturedCacheTtlMs,
            codec = OnlinePageCacheCodecs.Playlists,
        ) {
            client.getPersonalizedPlaylists(limit = FeaturedPlaylistLimit)
        }
    }

    override suspend fun featuredCharts(): List<OnlinePlaylist> {
        return cachedPage(
            key = cacheKey("featured:charts"),
            ttlMs = NeteaseFeaturedCacheTtlMs,
            codec = OnlinePageCacheCodecs.Playlists,
        ) {
            client.getToplists(limit = FeaturedChartLimit)
        }
    }

    override suspend fun featuredAlbums(): List<OnlineAlbum> {
        return cachedPage(
            key = cacheKey("featured:albums"),
            ttlMs = NeteaseFeaturedCacheTtlMs,
            codec = OnlinePageCacheCodecs.Albums,
        ) {
            client.getNewAlbums(limit = FeaturedAlbumLimit)
        }
    }

    override suspend fun featuredArtists(): List<OnlineArtist> {
        return cachedPage(
            key = cacheKey("featured:artists"),
            ttlMs = NeteaseFeaturedCacheTtlMs,
            codec = OnlinePageCacheCodecs.Artists,
        ) {
            client.getTopArtists(limit = FeaturedArtistLimit)
        }
    }

    override suspend fun featuredRadioTracks(): List<OnlineTrack> {
        return cachedPage(
            key = cacheKey("radio:tracks:featured"),
            ttlMs = NeteaseFeaturedCacheTtlMs,
            codec = OnlinePageCacheCodecs.Tracks,
        ) {
            client.getRecommendedRadioPrograms(limit = FeaturedRadioTrackLimit)
        }
    }

    override suspend fun featuredRadios(): List<OnlineRadio> {
        return cachedPage(
            key = cacheKey("radio:list:featured"),
            ttlMs = NeteaseFeaturedCacheTtlMs,
            codec = OnlinePageCacheCodecs.Radios,
        ) {
            client.getRecommendedRadios(limit = FeaturedRadioLimit)
        }
    }

    override suspend fun featuredRadioHome(): OnlineRadioHome {
        return cachedPage(
            key = cacheKey("radio:home"),
            ttlMs = NeteaseFeaturedCacheTtlMs,
            codec = OnlinePageCacheCodecs.RadioHome,
        ) {
            OnlineRadioHome(
                tracks = runSuspendCatching {
                    featuredRadioTracks()
                }.getOrDefault(emptyList()),
                radios = runSuspendCatching {
                    featuredRadios()
                }.getOrDefault(emptyList()),
            )
        }
    }

    override suspend fun track(trackId: String): OnlineTrack? {
        // 不能再套一层同 key 的 getOrLoad：getTrack 内部已按同 key 合并缓存，
        // 嵌套会让内层加载 join 外层在途任务并 await 自己，永久挂起。
        return getTrack(trackId)
    }

    suspend fun featuredSongs(): List<OnlineTrack> {
        return client.getPlaylistSongs(playlistId = FeaturedPlaylistId, limit = FeaturedLimit)
    }

    suspend fun getTrack(trackId: String): OnlineTrack? {
        val normalizedTrackId = trackId.trim().takeIf(String::isNotEmpty) ?: return null
        return NeteaseOnlineMemoryCache.getOrLoad(
            key = cacheKey("track", normalizedTrackId),
            ttlMs = NeteaseDetailCacheTtlMs,
        ) {
            client.getSongs(listOf(normalizedTrackId)).firstOrNull()
        }
    }

    suspend fun getTracks(trackIds: List<String>): List<OnlineTrack> {
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

    suspend fun currentUserProfile(): NeteaseAccountProfile? {
        val state = authStore?.load() ?: return null
        if (!state.isLoggedIn) {
            return null
        }
        return NeteaseOnlineMemoryCache.getOrLoad(
            key = cacheKey("account:profile"),
            ttlMs = NeteaseAccountCacheTtlMs,
        ) {
            val profile = runSuspendCatching {
                client.getCurrentUserProfile()
            }.getOrNull()
            if (profile != null) {
                authStore.saveProfile(profile)
            }
            profile ?: state.profile
        }
    }

    suspend fun currentUserPlaylists(limit: Int = AccountPlaylistLimit): List<NeteasePlaylistSummary>? {
        val state = authStore?.load() ?: return null
        if (!state.isLoggedIn) {
            return null
        }
        val profile = currentUserProfile() ?: state.profile ?: return null
        return NeteaseOnlineMemoryCache.getOrLoad(
            key = cacheKey("account:playlist-summaries", limit),
            ttlMs = NeteaseAccountCacheTtlMs,
        ) {
            client.getUserPlaylists(
                userId = profile.userId,
                limit = limit,
            )
        }
    }

    suspend fun currentUserLikedTracks(limit: Int = Int.MAX_VALUE): List<OnlineTrack>? {
        val likedPlaylist = currentUserPlaylists()
            ?.firstOrNull(NeteasePlaylistSummary::isLikedSongs)
            ?: return null
        return playlistTracks(
            playlist = likedPlaylist,
            limit = likedPlaylist.trackFetchLimit(maxLimit = limit),
        )
    }

    override suspend fun currentUserDailyRecommendedTracks(limit: Int): List<OnlineTrack>? {
        val state = authStore?.load() ?: return null
        if (!state.isLoggedIn) {
            return null
        }
        return cachedNullablePage(
            key = cacheKey("featured:daily", limit),
            ttlMs = NeteaseFeaturedCacheTtlMs,
            codec = OnlinePageCacheCodecs.Tracks,
        ) {
            val result = runSuspendCatching {
                client.getDailyRecommendedSongs(limit = limit)
            }.getOrDefault(NeteaseDailyRecommendedTracksResult(NeteaseAccountActionStatus.Failed))
            result.tracks.takeIf { result.status == NeteaseAccountActionStatus.Success }
        }
    }

    suspend fun currentUserLikedTrackIds(): Set<String>? {
        val state = authStore?.load() ?: return null
        if (!state.isLoggedIn) {
            return null
        }
        val profile = currentUserProfile() ?: state.profile ?: return null
        val result = runSuspendCatching {
            client.getUserLikedTrackIds(profile.userId)
        }.getOrDefault(NeteaseLikedTrackIdsResult(NeteaseAccountActionStatus.Failed))
        if (result.status == NeteaseAccountActionStatus.Success && result.trackIds.isNotEmpty()) {
            return result.trackIds
        }
        val playlistTrackIds = runSuspendCatching {
            currentUserLikedPlaylistTrackIds(profile.userId)
        }.getOrNull()
        return resolveNeteaseLikedTrackIds(result, playlistTrackIds)
    }

    private suspend fun currentUserLikedPlaylistTrackIds(userId: Long): Set<String>? {
        val likedPlaylist = client.getUserPlaylists(
            userId = userId,
            limit = AccountPlaylistLimit,
        ).firstOrNull(NeteasePlaylistSummary::isLikedSongs) ?: return null
        return client.getPlaylistTrackIds(
            playlistId = likedPlaylist.playlistId,
            limit = likedPlaylist.trackFetchLimit(),
        ).toSet()
    }

    suspend fun setTrackLiked(trackId: String, liked: Boolean): NeteaseAccountActionResult {
        val normalizedTrackId = trackId.trim().takeIf(String::isNotEmpty)
            ?: return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
        val state = authStore?.load()
        if (state?.isLoggedIn != true) {
            return NeteaseAccountActionResult(NeteaseAccountActionStatus.RequiresLogin, code = 301)
        }
        return runSuspendCatching {
            client.setSongLiked(
                trackId = normalizedTrackId,
                liked = liked,
            )
        }.getOrDefault(NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed))
            .also { result ->
                if (result.status == NeteaseAccountActionStatus.Success) {
                    invalidateAccountCaches()
                    invalidatePageCache(cachePrefix("playlist:tracks"))
                    invalidatePageCache(cachePrefix("account:playlist-tracks"))
                }
            }
    }

    suspend fun addTracksToPlaylist(
        playlistId: String,
        trackIds: List<String>,
    ): NeteaseAccountActionResult {
        val normalizedPlaylistId = playlistId.trim().takeIf(String::isNotEmpty)
            ?: return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
        val normalizedTrackIds = normalizeNeteasePlaylistTrackIds(trackIds)
        if (normalizedTrackIds.isEmpty()) {
            return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
        }
        val state = authStore?.load()
        if (state?.isLoggedIn != true) {
            return NeteaseAccountActionResult(NeteaseAccountActionStatus.RequiresLogin, code = 301)
        }
        return runSuspendCatching {
            client.manipulatePlaylistTracks(
                playlistId = normalizedPlaylistId,
                trackIds = normalizedTrackIds,
                operation = NeteasePlaylistTrackOperation.Add,
            )
        }.getOrDefault(NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed))
            .also { result ->
                if (result.status == NeteaseAccountActionStatus.Success) {
                    invalidateAccountCaches()
                    invalidatePlaylistTrackCaches(normalizedPlaylistId)
                }
            }
    }

    suspend fun removeTracksFromPlaylist(
        playlistId: String,
        trackIds: List<String>,
    ): NeteaseAccountActionResult {
        val normalizedPlaylistId = playlistId.trim().takeIf(String::isNotEmpty)
            ?: return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
        val normalizedTrackIds = normalizeNeteasePlaylistTrackIds(trackIds)
        if (normalizedTrackIds.isEmpty()) {
            return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
        }
        val state = authStore?.load()
        if (state?.isLoggedIn != true) {
            return NeteaseAccountActionResult(NeteaseAccountActionStatus.RequiresLogin, code = 301)
        }
        return runSuspendCatching {
            client.manipulatePlaylistTracks(
                playlistId = normalizedPlaylistId,
                trackIds = normalizedTrackIds,
                operation = NeteasePlaylistTrackOperation.Remove,
            )
        }.getOrDefault(NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed))
            .also { result ->
                if (result.status == NeteaseAccountActionStatus.Success) {
                    invalidateAccountCaches()
                    invalidatePlaylistTrackCaches(normalizedPlaylistId)
                }
            }
    }

    suspend fun deletePlaylist(playlistId: String): NeteaseAccountActionResult {
        val normalizedPlaylistId = playlistId.trim().takeIf(String::isNotEmpty)
            ?: return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
        val state = authStore?.load()
        if (state?.isLoggedIn != true) {
            return NeteaseAccountActionResult(NeteaseAccountActionStatus.RequiresLogin, code = 301)
        }
        return runSuspendCatching {
            client.deletePlaylist(normalizedPlaylistId)
        }.getOrDefault(NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed))
            .also { result ->
                if (result.status == NeteaseAccountActionStatus.Success) {
                    invalidateAccountCaches()
                    invalidatePlaylistTrackCaches(normalizedPlaylistId)
                }
            }
    }

    suspend fun createPlaylist(name: String): OnlineAccountPlaylistCreateResult {
        val normalizedName = name.trim().takeIf(String::isNotEmpty)
            ?: return OnlineAccountPlaylistCreateResult(NeteaseAccountActionStatus.Failed)
        val state = authStore?.load()
        if (state?.isLoggedIn != true) {
            return OnlineAccountPlaylistCreateResult(NeteaseAccountActionStatus.RequiresLogin, code = 301)
        }
        return runSuspendCatching {
            client.createPlaylist(normalizedName)
        }.getOrDefault(OnlineAccountPlaylistCreateResult(NeteaseAccountActionStatus.Failed))
    }

    override suspend fun accountPlaylists(): List<OnlineAccountPlaylist>? {
        val state = authStore?.load() ?: return null
        if (!state.isLoggedIn) {
            return null
        }
        val profile = currentUserProfile() ?: state.profile ?: return null
        return cachedPage(
            key = cacheKey("account:playlists"),
            ttlMs = NeteaseAccountCacheTtlMs,
            codec = OnlinePageCacheCodecs.AccountPlaylists,
        ) {
            client.getUserPlaylists(
                userId = profile.userId,
                limit = AccountPlaylistLimit,
            ).map { playlist ->
                OnlineAccountPlaylist(
                    provider = provider,
                    playlistId = playlist.playlistId,
                    title = playlist.name,
                    trackCount = playlist.trackCount,
                    isLikedSongs = playlist.isLikedSongs,
                    isEditable = playlist.isEditableBy(profile.userId),
                )
            }
        }
    }

    override suspend fun accountAlbums(): List<OnlineAlbum>? {
        val state = authStore?.load() ?: return null
        if (!state.isLoggedIn) {
            return null
        }
        val profile = currentUserProfile() ?: state.profile ?: return null
        return cachedPage(
            key = cacheKey("account:albums"),
            ttlMs = NeteaseAccountCacheTtlMs,
            codec = OnlinePageCacheCodecs.Albums,
        ) {
            client.getUserAlbums(
                userId = profile.userId,
                limit = AccountAlbumLimit,
            )
        }
    }

    override suspend fun accountRadios(): List<OnlineRadio>? {
        val state = authStore?.load() ?: return null
        if (!state.isLoggedIn) {
            return null
        }
        val profile = currentUserProfile() ?: state.profile ?: return null
        return cachedPage(
            key = cacheKey("account:radios"),
            ttlMs = NeteaseAccountCacheTtlMs,
            codec = OnlinePageCacheCodecs.Radios,
        ) {
            client.getUserRadios(
                userId = profile.userId,
                limit = AccountRadioLimit,
            )
        }
    }

    override suspend fun accountLikedTrackIds(): Set<String>? {
        return cachedNullablePage(
            key = cacheKey("liked:track-ids"),
            ttlMs = NeteaseAccountCacheTtlMs,
            codec = OnlinePageCacheCodecs.TrackIds,
        ) {
            currentUserLikedTrackIds()
        }
    }

    override suspend fun addTracksToAccountPlaylist(
        playlist: OnlineAccountPlaylist,
        trackIds: List<String>,
    ): NeteaseAccountActionResult {
        if (playlist.provider != provider || !playlist.isEditable) {
            return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
        }
        val result = addTracksToPlaylist(
            playlistId = playlist.playlistId,
            trackIds = trackIds,
        )
        return result
    }

    override suspend fun removeTracksFromAccountPlaylist(
        playlist: OnlineAccountPlaylist,
        trackIds: List<String>,
    ): NeteaseAccountActionResult {
        if (playlist.provider != provider || !playlist.isEditable) {
            return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
        }
        val result = removeTracksFromPlaylist(
            playlistId = playlist.playlistId,
            trackIds = trackIds,
        )
        return result
    }

    override suspend fun deleteAccountPlaylist(
        playlist: OnlineAccountPlaylist,
    ): NeteaseAccountActionResult {
        if (playlist.provider != provider || !playlist.isEditable) {
            return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
        }
        val result = deletePlaylist(playlist.playlistId)
        return result
    }

    override suspend fun createAccountPlaylist(name: String): OnlineAccountPlaylistCreateResult {
        val result = createPlaylist(name)
        if (result.status == NeteaseAccountActionStatus.Success) {
            invalidateAccountCaches()
        }
        return result
    }

    override suspend fun accountPlaylistTracks(playlist: OnlineAccountPlaylist): List<OnlineTrack> {
        if (playlist.provider != provider) {
            return emptyList()
        }
        return cachedPage(
            key = cacheKey("account:playlist-tracks", playlist.playlistId),
            ttlMs = NeteaseAccountCacheTtlMs,
            codec = OnlinePageCacheCodecs.Tracks,
        ) {
            client.getPlaylistSongs(
                playlistId = playlist.playlistId,
                limit = playlist.trackFetchLimit(),
            )
        }
    }

    override suspend fun playlistTracks(playlist: OnlinePlaylist): List<OnlineTrack> {
        if (playlist.provider != provider) {
            return emptyList()
        }
        return cachedPage(
            key = cacheKey("playlist:tracks", playlist.playlistId),
            ttlMs = NeteaseDetailCacheTtlMs,
            codec = OnlinePageCacheCodecs.Tracks,
        ) {
            client.getPlaylistSongs(
                playlistId = playlist.playlistId,
                limit = playlist.trackFetchLimit(),
            )
        }
    }

    override suspend fun albumTracks(album: OnlineAlbum): List<OnlineTrack> {
        if (album.provider != provider) {
            return emptyList()
        }
        return cachedPage(
            key = cacheKey("album:tracks", album.albumId),
            ttlMs = NeteaseDetailCacheTtlMs,
            codec = OnlinePageCacheCodecs.Tracks,
        ) {
            client.getAlbumSongs(
                albumId = album.albumId,
                limit = AlbumTracksLimit,
            )
        }
    }

    override suspend fun artistTopTracks(artist: OnlineArtist): List<OnlineTrack> {
        if (artist.provider != provider) {
            return emptyList()
        }
        return cachedPage(
            key = cacheKey("artist:tracks", artist.artistId),
            ttlMs = NeteaseDetailCacheTtlMs,
            codec = OnlinePageCacheCodecs.Tracks,
        ) {
            client.getArtistTopSongs(
                artistId = artist.artistId,
                limit = ArtistTopTracksLimit,
            )
        }
    }

    override suspend fun artistAlbums(artist: OnlineArtist): List<OnlineAlbum> {
        if (artist.provider != provider) {
            return emptyList()
        }
        return cachedPage(
            key = cacheKey("artist:albums", artist.artistId),
            ttlMs = NeteaseDetailCacheTtlMs,
            codec = OnlinePageCacheCodecs.Albums,
        ) {
            client.getArtistAlbums(
                artistId = artist.artistId,
                expectedCount = artist.albumCount.takeIf { albumCount -> albumCount > 0 },
            )
        }
    }

    override suspend fun artistIntroduction(artist: OnlineArtist): List<OnlineArtistIntroduction> {
        if (artist.provider != provider) {
            return emptyList()
        }
        return cachedPage(
            key = cacheKey("artist:introduction", artist.artistId),
            ttlMs = NeteaseDetailCacheTtlMs,
            codec = OnlinePageCacheCodecs.ArtistIntroductions,
        ) {
            client.getArtistIntroduction(artist.artistId)
        }
    }

    override suspend fun radioTracks(radio: OnlineRadio): List<OnlineTrack> {
        if (radio.provider != provider) {
            return emptyList()
        }
        return cachedPage(
            key = cacheKey("radio:tracks", radio.radioId),
            ttlMs = NeteaseDetailCacheTtlMs,
            codec = OnlinePageCacheCodecs.Tracks,
        ) {
            client.getRadioPrograms(
                radioId = radio.radioId,
                limit = RadioTracksLimit,
            )
        }
    }

    suspend fun playlistTracks(
        playlist: NeteasePlaylistSummary,
        limit: Int = playlist.trackFetchLimit(),
    ): List<OnlineTrack> {
        return cachedPage(
            key = cacheKey("playlist:tracks", playlist.playlistId, limit),
            ttlMs = NeteaseDetailCacheTtlMs,
            codec = OnlinePageCacheCodecs.Tracks,
        ) {
            client.getPlaylistSongs(
                playlistId = playlist.playlistId,
                limit = limit,
            )
        }
    }

    override suspend fun lyrics(identity: OnlineTrackIdentity): OnlineLyrics? {
        if (identity.source != NeteaseSourceId) {
            return null
        }
        return cachedLyrics(identity)
    }

    suspend fun resolvePlayableTrack(
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

    private suspend fun cachedPlaybackResult(
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

    suspend fun resolvePlaybackUri(identity: OnlineTrackIdentity): Uri {
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

    private suspend fun cachedLyrics(identity: OnlineTrackIdentity): OnlineLyrics {
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

    override suspend fun resolvePlayableMediaItem(
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

    override suspend fun resolvePlayableMediaItems(
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
}
