package com.smartisan.music.data.online

import android.content.Context
import androidx.media3.common.MediaItem
import com.smartisan.music.AppDispatchers
import com.smartisan.music.data.settings.NeteaseAudioQuality
import com.smartisan.music.data.settings.OnlineMusicSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val NeteaseSourceId = OnlineMusicProvider.Netease.sourceId
internal const val SearchLimit = 30
internal const val ArtistSearchLimit = 30
internal const val AlbumSearchLimit = 30
internal const val PlaylistSearchLimit = 30
internal const val HotSearchLimit = 20
internal const val FeaturedArtistLimit = 30
internal const val FeaturedBannerLimit = 6
internal const val FeaturedHomeTrackLimit = 12
internal const val FeaturedHomeSectionCount = 5
internal const val FeaturedPlaylistLimit = 18
internal const val FeaturedChartLimit = 12
internal const val FeaturedAlbumLimit = 18
internal const val FeaturedRadioTrackLimit = 18
internal const val FeaturedRadioLimit = 18
internal const val ArtistTopTracksLimit = 60
internal const val AlbumTracksLimit = 80
internal const val RadioTracksLimit = 80
internal const val FeaturedPlaylistId = "3778678"
internal const val FeaturedLimit = 30
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

/**
 * 网易云在线音乐仓库。
 *
 * 具体 API 实现按域拆在同包扩展函数文件里（search/featured/account/content/playback/lyrics），
 * 本类只保留账号缓存域的状态管理（缓存键构造、登录/换号失效）与对接口的一行委托。
 * 拆出的页缓存读写见 [NeteaseOnlinePageCache]。
 */
internal class NeteaseOnlineMusicRepository(
    internal val authStore: NeteaseAuthStore? = null,
    internal val playbackQualityProvider: suspend () -> NeteaseAudioQuality = { NeteaseAudioQuality.ExHigh },
    internal val client: NeteaseCloudMusicClient = NeteaseCloudMusicClient(
        cookieProvider = { authStore?.getCookies().orEmpty() },
        playbackQualityProvider = playbackQualityProvider,
    ),
    internal val lyricsDiskCache: OnlineLyricsDiskCache? = null,
    internal val pageDiskCache: OnlinePageDiskCache? = null,
    internal val pageCacheRefreshScope: CoroutineScope? = null,
) : OnlineMusicProviderRepository {

    override val provider: OnlineMusicProvider = OnlineMusicProvider.Netease
    internal val mutableCacheRefreshEvents = MutableSharedFlow<OnlineCacheRefreshEvent>(
        extraBufferCapacity = 32,
    )
    override val cacheRefreshEvents: SharedFlow<OnlineCacheRefreshEvent> = mutableCacheRefreshEvents

    /**
     * 在途的后台页缓存刷新：cacheKey -> 取消句柄。
     * 同一个 key 只允许一个刷新在途（句柄存在即代表在途），账号态变化时按句柄取消。
     */
    internal val pageCacheRefreshLock = Any()
    internal val pageCacheRefreshHandles = HashMap<String, Job>()

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

    internal fun cacheKey(namespace: String, vararg parts: Any?): String {
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

    internal fun cachePrefix(namespace: String): String {
        return "netease:${authCacheScope()}:$namespace"
    }

    private fun loadAuthCacheScope(): String {
        val state = authStore?.load()
        return when {
            state == null || !state.isLoggedIn -> "anon"
            else -> "user:${state.savedAt}:${state.cookies[NeteaseLoginCookieName]?.hashCode() ?: 0}"
        }
    }

    internal fun authCacheScope(): String {
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

    internal fun invalidateAccountCaches() {
        invalidatePageCache(cachePrefix("account"))
        invalidatePageCache(cachePrefix("liked"))
    }

    internal fun invalidatePageCache(prefix: String) {
        // 先中止该前缀下在途的后台刷新：否则它会把失效前的旧结果写回内存与磁盘缓存。
        cancelPendingPageCacheRefreshes(prefix)
        NeteaseOnlineMemoryCache.invalidate(prefix)
        pageCacheRefreshScope?.launch {
            pageDiskCache?.removePrefix(prefix)
        }
    }

    internal fun invalidatePlaylistTrackCaches(playlistId: String) {
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

    override suspend fun search(query: String): List<OnlineTrack> = searchPage(query)

    override suspend fun searchAll(query: String): OnlineSearchResults = searchAllPage(query)

    override suspend fun searchArtists(query: String): List<OnlineArtist> = searchArtistsPage(query)

    override suspend fun searchAlbums(query: String): List<OnlineAlbum> = searchAlbumsPage(query)

    override suspend fun searchPlaylists(query: String): List<OnlinePlaylist> =
        searchPlaylistsPage(query)

    override suspend fun searchHotKeywords(): List<OnlineSearchHotKeyword> = searchHotKeywordsPage()

    override suspend fun featuredTracks(): List<OnlineTrack> = featuredTracksPage()

    override suspend fun featuredHome(): OnlineMusicHome = featuredHomePage()

    override suspend fun featuredBanners(): List<OnlineBanner> = featuredBannersPage()

    override suspend fun featuredPlaylists(): List<OnlinePlaylist> = featuredPlaylistsPage()

    override suspend fun featuredCharts(): List<OnlinePlaylist> = featuredChartsPage()

    override suspend fun featuredAlbums(): List<OnlineAlbum> = featuredAlbumsPage()

    override suspend fun featuredArtists(): List<OnlineArtist> = featuredArtistsPage()

    override suspend fun featuredRadioTracks(): List<OnlineTrack> = featuredRadioTracksPage()

    override suspend fun featuredRadios(): List<OnlineRadio> = featuredRadiosPage()

    override suspend fun featuredRadioHome(): OnlineRadioHome = featuredRadioHomePage()

    override suspend fun track(trackId: String): OnlineTrack? = trackPage(trackId)

    override suspend fun currentUserDailyRecommendedTracks(limit: Int): List<OnlineTrack>? =
        currentUserDailyRecommendedTracksPage(limit)

    override suspend fun accountPlaylists(): List<OnlineAccountPlaylist>? = accountPlaylistsPage()

    override suspend fun accountAlbums(): List<OnlineAlbum>? = accountAlbumsPage()

    override suspend fun accountRadios(): List<OnlineRadio>? = accountRadiosPage()

    override suspend fun accountLikedTrackIds(): Set<String>? = accountLikedTrackIdsPage()

    override suspend fun addTracksToAccountPlaylist(
        playlist: OnlineAccountPlaylist,
        trackIds: List<String>,
    ): NeteaseAccountActionResult = addTracksToAccountPlaylistPage(playlist, trackIds)

    override suspend fun removeTracksFromAccountPlaylist(
        playlist: OnlineAccountPlaylist,
        trackIds: List<String>,
    ): NeteaseAccountActionResult = removeTracksFromAccountPlaylistPage(playlist, trackIds)

    override suspend fun deleteAccountPlaylist(
        playlist: OnlineAccountPlaylist,
    ): NeteaseAccountActionResult = deleteAccountPlaylistPage(playlist)

    override suspend fun createAccountPlaylist(name: String): OnlineAccountPlaylistCreateResult =
        createAccountPlaylistPage(name)

    override suspend fun accountPlaylistTracks(
        playlist: OnlineAccountPlaylist,
    ): List<OnlineTrack> = accountPlaylistTracksPage(playlist)

    override suspend fun playlistTracks(playlist: OnlinePlaylist): List<OnlineTrack> =
        playlistTracksPage(playlist)

    override suspend fun albumTracks(album: OnlineAlbum): List<OnlineTrack> = albumTracksPage(album)

    override suspend fun artistTopTracks(artist: OnlineArtist): List<OnlineTrack> =
        artistTopTracksPage(artist)

    override suspend fun artistAlbums(artist: OnlineArtist): List<OnlineAlbum> =
        artistAlbumsPage(artist)

    override suspend fun artistIntroduction(
        artist: OnlineArtist,
    ): List<OnlineArtistIntroduction> = artistIntroductionPage(artist)

    override suspend fun radioTracks(radio: OnlineRadio): List<OnlineTrack> = radioTracksPage(radio)

    override suspend fun lyrics(identity: OnlineTrackIdentity): OnlineLyrics? = lyricsPage(identity)

    override suspend fun resolvePlayableMediaItem(
        mediaItem: MediaItem,
        includeLyrics: Boolean,
        forceRefresh: Boolean,
    ): MediaItem? = resolvePlayableMediaItemPage(mediaItem, includeLyrics, forceRefresh)

    override suspend fun resolvePlayableMediaItems(
        mediaItems: List<MediaItem>,
        includeLyrics: Boolean,
    ): List<MediaItem> = resolvePlayableMediaItemsPage(mediaItems, includeLyrics)
}
