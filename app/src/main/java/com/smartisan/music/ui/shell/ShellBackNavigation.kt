package com.smartisan.music.ui.shell

import com.smartisan.music.ui.navigation.MusicDestination

/**
 * 主壳的返回栈仲裁。
 *
 * 原先是 [MusicAppShell] 里 4 个并列的 `BackHandler`，按目的地与编辑态手工判断，条件和动作分散。
 * 这里把「这一次返回该落到哪、由谁消费」抽成不依赖 Compose 的纯状态机，可以整体穷举验证；
 * `BackHandler` 只保留一次委托调用。
 *
 * 优先级与迁移前逐条等价：
 * 1. 覆盖层（播放 / 搜索 / 歌单选择 / 导航编辑）可见时让位——它们的 `BackHandler` 组合在主壳之后，
 *    注册更晚因而必然优先；主壳此时不消费只是把这条既有事实显式化。
 * 2. 之后按 `BackHandler` 的注册顺序倒序仲裁主壳自有分支：嵌套艺术家详情 > 回「更多」 >
 *    根艺术家详情 > 专辑详情。这四个分支两两互斥（见 ShellBackNavigationTest 的穷举用例），
 *    所以「取最高优先级的 enabled 分支」与「4 个并列 enabled」得到同一个动作。
 */

/** 整页覆盖层：可见期间自己消费返回键。 */
internal enum class ShellBackOverlay {
    None,
    Playback,
    Search,
    PlaylistPicker,
    NavigationEditor,
}

/** 主壳自有分支，对应迁移前的 4 个 `BackHandler`。 */
internal enum class ShellBackBranch {
    /** 关闭专辑详情：`destination == Album && selectedAlbumId != null`。 */
    AlbumDetail,

    /** 从艺术家根详情退回艺术家列表。 */
    RootArtistDetail,

    /** 从「更多」的子页退回「更多」。 */
    ReturnToMore,

    /** 艺术家详情向上级退一层。 */
    NestedArtistDetail,
}

/** 这一次返回最终由谁消费。 */
internal enum class ShellBackOwner {
    /** 主壳与覆盖层都不消费，交给系统（一级 tab 根目的地、编辑态等）。 */
    None,
    CloseAlbumDetail,
    CloseArtistDetail,
    ReturnToMore,

    /** 由可见覆盖层自有的 `BackHandler` 消费。 */
    VisibleOverlay,
}

/** 主壳是否需要注册一个 enabled 的 `BackHandler`；让位与无人消费时都是 false。 */
internal val ShellBackOwner.consumedByShell: Boolean
    get() =
        this == ShellBackOwner.CloseAlbumDetail ||
            this == ShellBackOwner.CloseArtistDetail ||
            this == ShellBackOwner.ReturnToMore

/** 主壳返回仲裁的输入快照。 */
internal data class ShellBackNavigationState(
    val destination: MusicDestination = MusicDestination.Playlist,
    val presentedFromMore: Boolean = false,
    val songsEditMode: Boolean = false,
    val albumEditMode: Boolean = false,
    /** 专辑详情整页是否打开（`selectedAlbumId != null`）。 */
    val albumDetailOpen: Boolean = false,
    /** 艺术家详情栈是否打开（`selectedArtistTarget != null`）。 */
    val artistDetailOpen: Boolean = false,
    /** 艺术家详情是否还有上一级（`selectedArtistTarget?.parentTarget() != null`）。 */
    val artistDetailHasParent: Boolean = false,
    val visibleOverlay: ShellBackOverlay = ShellBackOverlay.None,
) {

    /** 迁移前第 1 个 BackHandler 的 enabled。 */
    val closesAlbumDetail: Boolean
        get() = destination == MusicDestination.Album && albumDetailOpen

    /** 迁移前第 2 个 BackHandler 的 enabled。 */
    val closesRootArtistDetail: Boolean
        get() =
            destination == MusicDestination.Artist &&
                artistDetailOpen &&
                !artistDetailHasParent

    /** 迁移前第 3 个 BackHandler 的 enabled。 */
    val returnsToMore: Boolean
        get() =
            presentedFromMore &&
                when (destination) {
                    MusicDestination.Songs -> !songsEditMode
                    MusicDestination.Album -> !albumDetailOpen && !albumEditMode
                    MusicDestination.Artist -> !artistDetailOpen
                    else -> false
                }

    /** 迁移前第 4 个 BackHandler 的 enabled。 */
    val closesNestedArtistDetail: Boolean
        get() =
            destination == MusicDestination.Artist &&
                artistDetailOpen &&
                artistDetailHasParent

    /** 主壳自有分支的 enabled 列表，保留迁移前的注册顺序。 */
    val enabledBranches: List<ShellBackBranch>
        get() =
            listOfNotNull(
                ShellBackBranch.AlbumDetail.takeIf { closesAlbumDetail },
                ShellBackBranch.RootArtistDetail.takeIf { closesRootArtistDetail },
                ShellBackBranch.ReturnToMore.takeIf { returnsToMore },
                ShellBackBranch.NestedArtistDetail.takeIf { closesNestedArtistDetail },
            )

    /** 覆盖层优先，其次取注册顺序最靠后的主壳自有分支。 */
    fun resolveOwner(): ShellBackOwner {
        if (visibleOverlay != ShellBackOverlay.None) {
            return ShellBackOwner.VisibleOverlay
        }
        return when (resolveShellBranch()) {
            ShellBackBranch.AlbumDetail -> ShellBackOwner.CloseAlbumDetail
            ShellBackBranch.RootArtistDetail -> ShellBackOwner.CloseArtistDetail
            ShellBackBranch.NestedArtistDetail -> ShellBackOwner.CloseArtistDetail
            ShellBackBranch.ReturnToMore -> ShellBackOwner.ReturnToMore
            null -> ShellBackOwner.None
        }
    }

    /** 主壳自有分支里最高优先级（最后注册）的那一个；没有分支 enabled 时为 null。 */
    fun resolveShellBranch(): ShellBackBranch? {
        return enabledBranches.lastOrNull()
    }
}

/**
 * 把 4 个覆盖层的可见性折算成「当前由哪个覆盖层持有返回键」。
 *
 * 顺序取组合顺序最靠后的：导航编辑 > 歌单选择 > 搜索 > 播放，与它们 `BackHandler` 的注册顺序一致。
 * 歌单选择沿用其自身的 `visible` 表达式，新建歌单弹窗接管时不算覆盖层持有，主壳行为与迁移前一致。
 */
internal fun shellBackOverlay(
    playbackVisible: Boolean,
    searchVisible: Boolean,
    playlistPickerVisible: Boolean,
    navigationEditorVisible: Boolean,
): ShellBackOverlay {
    return when {
        navigationEditorVisible -> ShellBackOverlay.NavigationEditor
        playlistPickerVisible -> ShellBackOverlay.PlaylistPicker
        searchVisible -> ShellBackOverlay.Search
        playbackVisible -> ShellBackOverlay.Playback
        else -> ShellBackOverlay.None
    }
}
