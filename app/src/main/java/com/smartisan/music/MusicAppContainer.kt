package com.smartisan.music

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.smartisan.music.data.library.LibraryExclusionsStore
import com.smartisan.music.data.online.NeteaseAuthStore
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.data.search.SearchHistoryStore
import com.smartisan.music.data.settings.ArtistSettingsStore
import com.smartisan.music.data.settings.LibraryDisplaySettingsStore
import com.smartisan.music.data.settings.NavigationSettingsStore
import com.smartisan.music.data.settings.OnlineMusicSettingsStore
import com.smartisan.music.data.settings.PlaybackSettingsStore
import com.smartisan.music.data.settings.ThemeSettingsStore
import com.smartisan.music.listentogether.ListenTogetherStore

/**
 * 应用级依赖容器：懒加载单例持有跨页面共享的数据层对象。
 *
 * 之前这些 Store 在 composable 里 `remember { XxxStore(appContext) }` 现场构造，
 * 导致同类型 Store 出现多个实例（如 NeteaseAuthStore 在设置页与云音乐页各建一个），
 * 各自持有独立缓存，收藏/登录态可能出现读到旧数据的隐患。统一收口后全 app 每个类型仅一个实例。
 */
internal class MusicAppContainer private constructor(context: Context) {
    private val appContext = context.applicationContext

    val onlineRepositoryRouter: OnlineMusicRepositoryRouter by lazy {
        OnlineMusicRepositoryRouter.getInstance(appContext)
    }
    val listenTogetherStore: ListenTogetherStore by lazy {
        ListenTogetherStore(onlineRepositoryRouter)
    }
    val neteaseAuthStore: NeteaseAuthStore by lazy { NeteaseAuthStore(appContext) }
    val onlineMusicSettingsStore: OnlineMusicSettingsStore by lazy {
        OnlineMusicSettingsStore(appContext)
    }
    val libraryExclusionsStore: LibraryExclusionsStore by lazy {
        LibraryExclusionsStore(appContext)
    }
    val searchHistoryStore: SearchHistoryStore by lazy { SearchHistoryStore(appContext) }
    val playbackSettingsStore: PlaybackSettingsStore by lazy { PlaybackSettingsStore(appContext) }
    val artistSettingsStore: ArtistSettingsStore by lazy { ArtistSettingsStore(appContext) }
    val libraryDisplaySettingsStore: LibraryDisplaySettingsStore by lazy {
        LibraryDisplaySettingsStore(appContext)
    }
    val navigationSettingsStore: NavigationSettingsStore by lazy {
        NavigationSettingsStore(appContext)
    }
    val themeSettingsStore: ThemeSettingsStore by lazy { ThemeSettingsStore(appContext) }

    companion object {
        @Volatile
        private var instance: MusicAppContainer? = null

        fun getInstance(context: Context): MusicAppContainer {
            return instance ?: synchronized(this) {
                instance ?: MusicAppContainer(context.applicationContext).also { instance = it }
            }
        }
    }
}

internal val LocalMusicAppContainer = staticCompositionLocalOf<MusicAppContainer> {
    error("MusicAppContainer not provided")
}
