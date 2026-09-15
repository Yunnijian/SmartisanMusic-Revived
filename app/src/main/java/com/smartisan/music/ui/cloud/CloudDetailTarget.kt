package com.smartisan.music.ui.cloud

/** 云音乐 tab 内部一级页面。 */
internal enum class CloudSubPage {
    Home,
    Mine,
}

/** 详情页目标：歌单 / 专辑 / 艺人 / 电台四种，由宿主持有、PageStackTransition 驱动列表↔详情转场。
 *
 * 除 id/title 外携带展示元数据（封面、副标题来源、计数），跳转时一并传入，
 * 详情页头部不必再退化到"第一首歌的封面/种类硬编码"。
 */
internal sealed interface CloudDetailTarget {
    /** @param accountEditable 是否为当前账号可编辑的「我的歌单」（用于删除歌单/从歌单移除歌曲）。 */
    data class Playlist(
        val id: String,
        val title: String,
        val accountEditable: Boolean = false,
        val artworkUrl: String? = null,
        val subtitle: String? = null,
        val trackCount: Int = 0,
        val playCount: Long = 0L,
    ) : CloudDetailTarget

    data class Album(
        val id: String,
        val title: String,
        val artworkUrl: String? = null,
        val artist: String? = null,
        val trackCount: Int = 0,
    ) : CloudDetailTarget

    data class Artist(
        val id: String,
        val name: String,
        val artworkUrl: String? = null,
        val alias: String? = null,
        val trackCount: Int = 0,
        val albumCount: Int = 0,
    ) : CloudDetailTarget

    data class Radio(
        val id: String,
        val title: String,
        val artworkUrl: String? = null,
        val category: String? = null,
        val creator: String? = null,
        val programCount: Int = 0,
        val playCount: Long = 0L,
    ) : CloudDetailTarget

    /** Banner 指向的单曲：旧版 CloudMusicRoute.BannerTrack，进详情页而非直接播放。 */
    data class BannerTrack(
        val id: String,
        val title: String,
        val artworkUrl: String? = null,
        val subtitle: String? = null,
    ) : CloudDetailTarget
}
