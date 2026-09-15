package com.smartisan.music.ui.cloud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.smartisan.music.data.online.NeteaseAuthState
import com.smartisan.music.data.online.NeteaseAuthStore
import com.smartisan.music.data.online.OnlineAlbum
import com.smartisan.music.data.online.OnlineArtist
import com.smartisan.music.data.online.OnlineBanner
import com.smartisan.music.data.online.OnlinePlaylist
import com.smartisan.music.data.online.OnlineRadio
import com.smartisan.music.ui.cloud.components.CloudHomeEntry

/**
 * 一级内容页标识：五个入口页与各推进层（歌手 / 歌手专辑 / 电台 /「查看全部」）。
 *
 * 作为转场的 key：值变化即横向推入下一页，与详情层的方向一致。
 */
internal sealed interface CloudPrimaryPage {
    /** 入口行上的位置（推进层接在所属入口之后），仅用于决定转场方向。 */
    val order: Int

    data class ArtistAlbums(val artist: CloudDetailTarget.Artist) : CloudPrimaryPage {
        override val order: Int get() = 5
    }

    data object Artists : CloudPrimaryPage {
        override val order: Int get() = 4
    }

    data object Radio : CloudPrimaryPage {
        override val order: Int get() = 2
    }

    data class Featured(val page: CloudFeaturedPage) : CloudPrimaryPage {
        override val order: Int get() = 3
    }

    data class Entry(val subPage: CloudSubPage) : CloudPrimaryPage {
        override val order: Int
            get() = if (subPage == CloudSubPage.Mine) MineOrder else RecommendOrder
    }

    private companion object {
        const val MineOrder = 0
        const val RecommendOrder = 1
    }
}

/**
 * 云音乐宿主的界面状态：登录态、一级页/推进层、搜索场与详情目标。
 *
 * 原先全部在 [CloudMusicHost] 里 `remember`/`rememberSaveable` 现场持有，旋转即丢；
 * 迁入 ViewModel 后随配置变更存活，详情与筛选态旋转后保持。
 */
internal class CloudMusicHostViewModel(
    private val authStore: NeteaseAuthStore,
) : ViewModel() {

    var authState: NeteaseAuthState by mutableStateOf(authStore.load())

    var subPage: CloudSubPage by mutableStateOf(CloudSubPage.Home)
    var mineFilter: CloudAccountLibraryFilter by mutableStateOf(CloudAccountLibraryFilter.All)
    // 从「我的」打开的账号歌单：合并列表里把该行标题染成强调色（对齐旧版高亮）。
    var selectedAccountPlaylistId: String? by mutableStateOf(null)
    var searchVisible: Boolean by mutableStateOf(false)
    var searchQuery: String by mutableStateOf("")
    var searchCategory: CloudSearchCategory by mutableStateOf(CloudSearchCategory.All)
    var selectedDetail: CloudDetailTarget? by mutableStateOf(null)
    // 「查看全部」整页：与详情页同属列表之上的推进层，返回时先退整页再退详情。
    var featuredPage: CloudFeaturedPage? by mutableStateOf(null)
    // 电台模块（首页/热门播客/推荐节目三个子页）与歌手页/歌手专辑页，均由顶部入口行进入。
    var radioVisible: Boolean by mutableStateOf(false)
    var radioSubPage: CloudRadioSubPage by mutableStateOf(CloudRadioSubPage.Home)
    var artistsVisible: Boolean by mutableStateOf(false)
    var artistAlbumsTarget: CloudDetailTarget.Artist? by mutableStateOf(null)
    // 账号歌单库变更（加歌/移除/新建/删除）后由详情页回调递增，「我的」页订阅此值重拉。
    var accountLibraryRevision: Int by mutableStateOf(0)

    fun reloadAuthState() {
        authState = authStore.load()
    }

    /** 当前一级内容页；与 [CloudMusicHostPrimaryContent] 的分支顺序保持一致。 */
    val primaryPage: CloudPrimaryPage
        get() {
            val albums = artistAlbumsTarget
            val featured = featuredPage
            return when {
                albums != null -> CloudPrimaryPage.ArtistAlbums(albums)
                artistsVisible -> CloudPrimaryPage.Artists
                radioVisible -> CloudPrimaryPage.Radio
                featured != null -> CloudPrimaryPage.Featured(featured)
                else -> CloudPrimaryPage.Entry(subPage)
            }
        }

    fun saveLoginCookie(cookieJson: String): Boolean = authStore.saveCookieJson(cookieJson)

    val onAccountLibraryChanged: () -> Unit = { accountLibraryRevision += 1 }

    fun openPlaylistDetail(playlist: OnlinePlaylist) {
        selectedDetail = CloudDetailTarget.Playlist(
            id = playlist.playlistId,
            title = playlist.title,
            artworkUrl = playlist.artworkUrl,
            subtitle = playlist.subtitle,
            trackCount = playlist.trackCount,
            playCount = playlist.playCount,
        )
    }

    fun openAlbumDetail(album: OnlineAlbum) {
        selectedDetail = CloudDetailTarget.Album(
            id = album.albumId,
            title = album.title,
            artworkUrl = album.artworkUrl,
            artist = album.artist,
            trackCount = album.trackCount,
        )
    }

    fun openArtistDetail(artist: OnlineArtist) {
        selectedDetail = CloudDetailTarget.Artist(
            id = artist.artistId,
            name = artist.name,
            artworkUrl = artist.artworkUrl,
            alias = artist.subtitle,
            trackCount = artist.trackCount,
            albumCount = artist.albumCount,
        )
    }

    fun openRadioDetail(radio: OnlineRadio) {
        selectedDetail = CloudDetailTarget.Radio(
            id = radio.radioId,
            title = radio.title,
            artworkUrl = radio.artworkUrl,
            category = radio.category,
            creator = radio.creator,
            programCount = radio.programCount,
            playCount = radio.playCount,
        )
    }

    /** 带歌曲目标的 Banner 进单曲详情页（旧版 CloudMusicRoute.BannerTrack），不再直接播放。 */
    fun openBannerTrack(banner: OnlineBanner) {
        selectedDetail = CloudDetailTarget.BannerTrack(
            id = banner.targetTrackId.orEmpty(),
            title = banner.title,
            artworkUrl = banner.imageUrl,
            subtitle = banner.subtitle,
        )
    }

    /** 入口行切换：进入任一入口时清掉其他推进层，避免层叠残留。 */
    fun switchEntry(entry: CloudHomeEntry) {
        featuredPage = null
        radioVisible = false
        radioSubPage = CloudRadioSubPage.Home
        artistsVisible = false
        artistAlbumsTarget = null
        selectedAccountPlaylistId = null
        // 详情层压在内容区上，点入口必须一并关掉，否则只有高亮变、页面不切。
        selectedDetail = null
        when (entry) {
            CloudHomeEntry.Mine -> subPage = CloudSubPage.Mine
            CloudHomeEntry.Recommend -> subPage = CloudSubPage.Home
            CloudHomeEntry.Radio -> radioVisible = true
            CloudHomeEntry.Collection -> featuredPage = CloudFeaturedPage.Playlists
            CloudHomeEntry.Artist -> artistsVisible = true
        }
    }

    companion object {
        fun factory(authStore: NeteaseAuthStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CloudMusicHostViewModel(authStore) as T
            }
    }
}
