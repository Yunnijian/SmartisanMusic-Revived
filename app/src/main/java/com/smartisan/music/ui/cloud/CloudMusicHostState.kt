package com.smartisan.music.ui.cloud

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.smartisan.music.data.online.OnlineAccountPlaylist
import com.smartisan.music.data.online.OnlineAlbum
import com.smartisan.music.data.online.OnlineArtist
import com.smartisan.music.data.online.OnlineArtistIntroduction
import com.smartisan.music.data.online.OnlineBanner
import com.smartisan.music.data.online.OnlineMusicHome
import com.smartisan.music.data.online.OnlineMusicProvider
import com.smartisan.music.data.online.OnlineMusicProviderRepository
import com.smartisan.music.data.online.OnlinePlaylist
import com.smartisan.music.data.online.OnlineRadio
import com.smartisan.music.data.online.OnlineRadioHome
import com.smartisan.music.data.online.OnlineSearchResults
import com.smartisan.music.data.online.OnlineTrack
import com.smartisan.music.ui.cloud.components.CloudHomeSectionAnimation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 数据槽三态。空数据不进状态机，由调用方按 [CloudSlotState.Success] 里的空列表自行判定。 */
internal sealed interface CloudSlotState<out T> {
    data object Loading : CloudSlotState<Nothing>
    data object Error : CloudSlotState<Nothing>
    data class Success<T>(val data: T) : CloudSlotState<T>
}

/**
 * 宿主持有的异步数据槽：加载结果在页面被移出组合后依然保留。
 *
 * 云音乐各整页在入口层之间来回切换时会被销毁重建，数据状态若留在页内（produceState），
 * 返回时就要重新联网并把列表弹回顶部。旧版把全部云音乐数据集中在宿主持有
 * （LegacyPortCloudMusicPage 的 homeState / featuredHomeState / radioHomeState 等），
 * 页面只做渲染，这里沿用同一分工。
 *
 * [revision] 让宿主能主动作废缓存：同一 key 换 revision 即视为新一轮加载；
 * 加载完成时若 revision 已被推进，过期结果直接丢弃。
 */
@Stable
internal class CloudDataSlot<K, T>(
    private val scope: CoroutineScope,
    private val load: suspend (K) -> T,
) {
    private val entries = mutableStateMapOf<K, Entry<T>>()

    /** 在途加载的 key：reload 用它去重，避免刷新连点打出并发重复请求。 */
    private val inFlight = mutableSetOf<K>()

    /**
     * 完成代数：每次加载结束递增。
     * 调用方不能靠观察 Loading 判断刷新结束——仓库自身带缓存时 reload 可能在一帧内
     * 完成，Loading 根本不会被读到；完成代数一定会被观察到。
     */
    var version by mutableStateOf(0)
        private set

    fun state(key: K): CloudSlotState<T> = entries[key]?.state ?: CloudSlotState.Loading

    /**
     * 确保 key 对应的数据已加载。同一 revision 下只发起一次：成功结果直接复用，
     * 失败结果也不自动重试（交给 [reload]），避免每次重组都打一次网络。
     */
    fun ensureLoaded(key: K, revision: Int = 0) {
        if (entries[key]?.revision == revision) return
        entries[key] = Entry(revision, CloudSlotState.Loading)
        launchLoad(key, revision)
    }

    /**
     * 用户下拉刷新 / 点重试：重新加载但不清空已有结果。
     *
     * 刷新期间页面继续渲染旧内容（头部转圈即可），新数据落地后整体替换；
     * 若清掉条目，槽位会掉回 Loading 让整屏闪成「正在加载」。
     * 仅无成功结果时（首载、错误重试）才显示 Loading。
     */
    fun reload(key: K, revision: Int = 0) {
        if (key in inFlight) return
        if (entries[key]?.state !is CloudSlotState.Success) {
            entries[key] = Entry(revision, CloudSlotState.Loading)
        }
        launchLoad(key, revision)
    }

    /** 整体作废：写操作影响面无法定位到单个 key 时使用，下次读取重新加载。 */
    fun invalidateAll() {
        entries.clear()
    }

    private fun launchLoad(key: K, revision: Int) {
        inFlight += key
        scope.launch {
            val state = try {
                CloudSlotState.Success(load(key))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                CloudSlotState.Error
            }
            inFlight -= key
            if (entries[key]?.revision == revision) {
                entries[key] = Entry(revision, state)
            }
            version += 1
        }
    }

    private class Entry<T>(val revision: Int, val state: CloudSlotState<T>)
}

/** 首页与「查看全部」整页共用的推荐位数据：三个端点并行拉取后合成一份。 */
internal data class CloudHomeBundle(
    val banners: List<OnlineBanner>,
    val home: OnlineMusicHome,
    val dailyTracks: List<OnlineTrack>,
) {
    val isEmpty: Boolean
        get() = banners.isEmpty() &&
            home.tracks.isEmpty() &&
            home.playlists.isEmpty() &&
            home.charts.isEmpty() &&
            home.albums.isEmpty() &&
            home.artists.isEmpty()
}

/**
 * 「我的」页账号库数据：三个端点一次拉全，筛选胶囊只做客户端切片
 * （对齐旧版宿主同时持有 accountPlaylists / accountAlbums / accountRadios 的分工）。
 * 三个接口全返回 null 才视为登录态失效，与「已登录但列表为空」区分开。
 */
internal data class CloudAccountLibraryBundle(
    val playlists: List<OnlineAccountPlaylist>,
    val albums: List<OnlineAlbum>,
    val radios: List<OnlineRadio>,
    val loginRequired: Boolean,
)

/** 详情页数据：曲目列表，艺人页连带专辑横滑与简介段落。 */
internal data class CloudDetailBundle(
    val tracks: List<OnlineTrack>,
    val albums: List<OnlineAlbum> = emptyList(),
    val introduction: List<OnlineArtistIntroduction> = emptyList(),
)

/**
 * 云音乐宿主级数据仓库：一份数据一个槽，页面只读取状态与触发加载。
 *
 * [repository] 对外暴露，供页面发起不属于「整页数据」的动作（解析 Banner 单曲、
 * 收藏写回、加入歌单等）。
 */
@Stable
internal class CloudMusicDataStore(
    val repository: OnlineMusicProviderRepository,
    private val scope: CoroutineScope,
) {
    /** 首页区块入场动画：随仓库存活且只播一次，页面重建时保持展开。 */
    val homeSectionAnimation = CloudHomeSectionAnimation(scope)

    /** 首页推荐位；「查看全部」整页复用其中的分区全量列表，不再单独打一次 featuredHome。 */
    val home = CloudDataSlot<Unit, CloudHomeBundle>(scope) {
        coroutineScope {
            val homeAsync = async { repository.featuredHome() }
            val bannersAsync = async { repository.featuredBanners() }
            // 每日推荐需要登录，失败或缺数据时回退到推荐位歌曲，不打断整页加载。
            val dailyAsync = async {
                cloudRunSuspendCatching { repository.currentUserDailyRecommendedTracks() }
                    .getOrNull()
                    .orEmpty()
            }
            val home = homeAsync.await()
            val dailyTracks = dailyAsync.await()
            CloudHomeBundle(
                banners = bannersAsync.await(),
                home = home,
                dailyTracks = dailyTracks.ifEmpty { home.tracks },
            )
        }
    }

    /** 电台模块：首页预览与两个子页列表共用同一份数据，切子页只换渲染切片。 */
    val radio = CloudDataSlot<Unit, OnlineRadioHome>(scope) { repository.featuredRadioHome() }

    /** 歌手页全量列表（独立端点，与首页热门艺人子集不同）。 */
    val artists = CloudDataSlot<Unit, List<OnlineArtist>>(scope) { repository.featuredArtists() }

    /** 歌手专辑页：按歌手缓存，同一歌手返回时不重拉。 */
    val artistAlbums = CloudDataSlot<CloudDetailTarget.Artist, List<OnlineAlbum>>(scope) { artist ->
        repository.artistAlbums(
            OnlineArtist(
                provider = OnlineMusicProvider.Netease,
                artistId = artist.id,
                name = artist.name,
            ),
        )
    }

    /** 「我的」页：整份账号库缓存，宿主推进 revision 即可整体作废（删歌单/加歌后刷新）。 */
    val accountLibrary = CloudDataSlot<Unit, CloudAccountLibraryBundle>(scope) {
        val playlists = repository.accountPlaylists()
        val albums = repository.accountAlbums()
        val radios = repository.accountRadios()
        CloudAccountLibraryBundle(
            playlists = playlists.orEmpty(),
            albums = albums.orEmpty(),
            radios = radios.orEmpty(),
            loginRequired = playlists == null && albums == null && radios == null,
        )
    }

    /** 搜索结果：按 query 缓存，从结果点进详情再返回时直接复用，不重跑搜索。 */
    val search = CloudDataSlot<String, OnlineSearchResults>(scope) { query ->
        repository.searchAll(query)
    }

    /** 详情页：按 target 缓存曲目（艺人页连带专辑与简介），重开同一详情不再联网。 */
    val detail = CloudDataSlot<CloudDetailTarget, CloudDetailBundle>(scope) { target ->
        when (target) {
            is CloudDetailTarget.Playlist -> {
                val tracks = if (target.accountEditable) {
                    repository.accountPlaylistTracks(
                        OnlineAccountPlaylist(
                            provider = OnlineMusicProvider.Netease,
                            playlistId = target.id,
                            title = target.title,
                            trackCount = 0,
                            isEditable = true,
                        ),
                    )
                } else {
                    repository.playlistTracks(
                        OnlinePlaylist(
                            provider = OnlineMusicProvider.Netease,
                            playlistId = target.id,
                            title = target.title,
                        ),
                    )
                }
                CloudDetailBundle(tracks = tracks)
            }
            is CloudDetailTarget.Album -> CloudDetailBundle(
                tracks = repository.albumTracks(
                    OnlineAlbum(
                        provider = OnlineMusicProvider.Netease,
                        albumId = target.id,
                        title = target.title,
                    ),
                ),
            )
            is CloudDetailTarget.Artist -> {
                val artist = OnlineArtist(
                    provider = OnlineMusicProvider.Netease,
                    artistId = target.id,
                    name = target.name,
                )
                CloudDetailBundle(
                    tracks = repository.artistTopTracks(artist),
                    albums = repository.artistAlbums(artist),
                    introduction = repository.artistIntroduction(artist),
                )
            }
            is CloudDetailTarget.Radio -> CloudDetailBundle(
                tracks = repository.radioTracks(
                    OnlineRadio(
                        provider = OnlineMusicProvider.Netease,
                        radioId = target.id,
                        title = target.title,
                    ),
                ),
            )
            is CloudDetailTarget.BannerTrack -> CloudDetailBundle(
                tracks = listOfNotNull(repository.track(target.id)),
            )
        }
    }

    /**
     * 下拉刷新：先作废该页读取的缓存命名空间再 reload。
     *
     * 仓库自带 TTL 内存缓存与磁盘页缓存，直接 reload 会在毫秒级返回旧数据，
     * 刷新动画播完内容纹丝不动；作废后 reload 才真正联网。
     */
    fun refreshHome() {
        scope.launch {
            repository.invalidatePageCaches("featured")
            home.reload(Unit)
        }
    }

    fun refreshRadio() {
        scope.launch {
            repository.invalidatePageCaches(
                "radio:home",
                "radio:tracks:featured",
                "radio:list:featured",
            )
            radio.reload(Unit)
        }
    }

    fun refreshArtists() {
        scope.launch {
            repository.invalidatePageCaches("featured:artists")
            artists.reload(Unit)
        }
    }

    fun refreshAccountLibrary(revision: Int = 0) {
        scope.launch {
            repository.invalidatePageCaches("account")
            accountLibrary.reload(Unit, revision)
        }
    }

    fun refreshArtistAlbums(artist: CloudDetailTarget.Artist) {
        scope.launch {
            repository.invalidatePageCaches("artist:albums:${artist.id}")
            artistAlbums.reload(artist)
        }
    }
}

/**
 * 宿主持有的云音乐滚动位置。
 *
 * 页面被移出组合会连带销毁 LazyListState；数据不再重拉之后，滚动位置就成了返回时
 * 唯一还会跳变的东西，因此一并由宿主保管（对齐旧版宿主的 *ListState / *ScrollState）。
 */
@Stable
internal class CloudMusicScrollStates {
    val home = LazyListState()
    val mine = LazyListState()
    val artists = LazyListState()
    val artistAlbums = LazyListState()

    val radioHome = LazyListState()
    val radioList = LazyListState()
    val radioTracks = LazyListState()

    val featuredTracks = LazyListState()
    val featuredPlaylists = LazyListState()
    val featuredCharts = LazyListState()
    val featuredAlbums = LazyListState()
    val featuredArtists = LazyListState()

    val searchAll = LazyListState()
    val searchTracks = LazyListState()
    val searchArtists = LazyListState()
    val searchAlbums = LazyListState()
    val searchPlaylists = LazyListState()

    fun featured(page: CloudFeaturedPage): LazyListState = when (page) {
        CloudFeaturedPage.Tracks -> featuredTracks
        CloudFeaturedPage.Playlists -> featuredPlaylists
        CloudFeaturedPage.Charts -> featuredCharts
        CloudFeaturedPage.Albums -> featuredAlbums
        CloudFeaturedPage.Artists -> featuredArtists
    }

    fun search(category: CloudSearchCategory): LazyListState = when (category) {
        CloudSearchCategory.All -> searchAll
        CloudSearchCategory.Tracks -> searchTracks
        CloudSearchCategory.Artists -> searchArtists
        CloudSearchCategory.Albums -> searchAlbums
        CloudSearchCategory.Playlists -> searchPlaylists
    }
}

/** 创建宿主级数据仓库：随宿主组合存活，宿主离开组合时未完成的加载随之取消。 */
@Composable
internal fun rememberCloudMusicDataStore(
    repository: OnlineMusicProviderRepository,
    scope: CoroutineScope,
): CloudMusicDataStore {
    return remember(repository, scope) { CloudMusicDataStore(repository, scope) }
}

/** 捕获非取消异常，避免网络错误直接打断协程作用域。 */
internal suspend fun <T> cloudRunSuspendCatching(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
