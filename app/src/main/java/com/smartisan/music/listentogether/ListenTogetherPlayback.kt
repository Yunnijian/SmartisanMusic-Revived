package com.smartisan.music.listentogether

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.smartisan.music.data.online.NeteaseSourceId
import com.smartisan.music.data.online.onlineIdentityOrNull

/**
 * Store 需要的那几个播放器事件标志位（收窄自 `Player.Events`）。
 *
 * 字段与 `Player.EVENT_*` 一一对应，合并语义原样保留：`Player.Listener.onEvents` 一次回调
 * 可能同时带着多个标志，状态机据此决定是「切歌」「播放/暂停」还是「拖进度」。
 * 生产适配器负责把 `Player.Events` 翻成这里的数据类。
 */
internal data class ListenTogetherPlaybackEvents(
    val isPlayingChanged: Boolean = false,
    val timelineChanged: Boolean = false,
    val mediaItemTransition: Boolean = false,
    val positionDiscontinuity: Boolean = false,
)

/** 播放器事件回调；[playback] 是事件来源，等价于 `Player.Listener.onEvents(player = …)`。 */
internal fun interface ListenTogetherPlaybackListener {
    fun onEvents(playback: ListenTogetherPlayback, events: ListenTogetherPlaybackEvents)
}

/**
 * [ListenTogetherStore] 用到的播放器能力子集。
 *
 * `Player` 有上百个成员，JVM 单测实现不了，于是按需收窄：只留状态机真正调用的那些，
 * 语义与 `Player` 逐条对应。生产适配器是 [PlayerListenTogetherPlayback]，
 * 单测里换成手写假实现即可把 Store 跑起来（无需 robolectric/mockk）。
 */
internal interface ListenTogetherPlayback {
    val isPlaying: Boolean

    /** 当前媒体项的网易云曲目 id；没有媒体项或不是网易云来源时为 null。 */
    val currentTrackId: String?

    /** 当前播放位置（毫秒）。 */
    val currentPositionMs: Long

    /** 队列里各媒体项的网易云曲目 id，下标与队列一致；非网易云来源为 null。 */
    val queueTrackIds: List<String?>

    /** 目标曲目在队列里的下标；不在队列中返回 -1。 */
    fun trackIndex(trackId: String): Int

    fun setQueue(items: List<MediaItem>, startIndex: Int, startPositionMs: Long)

    fun setCurrentItem(item: MediaItem)

    fun prepare()

    fun seekToTrack(index: Int, positionMs: Long)

    fun seekToPosition(positionMs: Long)

    fun play()

    fun pause()

    fun addListener(listener: ListenTogetherPlaybackListener)

    fun removeListener(listener: ListenTogetherPlaybackListener)
}

/** [ListenTogetherPlayback] 的生产适配器：把每个成员原样转发给真实 [Player]。 */
internal class PlayerListenTogetherPlayback(
    private val player: Player,
) : ListenTogetherPlayback {

    /** 是否包着同一个 [Player]：重复接入同一个控制器时按幂等处理（与旧行为一致）。 */
    fun wraps(other: Player): Boolean = player === other

    private val bridges = mutableMapOf<ListenTogetherPlaybackListener, Player.Listener>()

    override val isPlaying: Boolean
        get() = player.isPlaying

    override val currentTrackId: String?
        get() = player.currentMediaItem?.neteaseTrackIdOrNull()

    override val currentPositionMs: Long
        get() = player.currentPosition

    override val queueTrackIds: List<String?>
        get() = (0 until player.mediaItemCount).map { index -> player.neteaseTrackIdAt(index) }

    override fun trackIndex(trackId: String): Int {
        for (index in 0 until player.mediaItemCount) {
            if (player.neteaseTrackIdAt(index) == trackId) {
                return index
            }
        }
        return -1
    }

    override fun setQueue(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        player.setMediaItems(items, startIndex, startPositionMs)
    }

    override fun setCurrentItem(item: MediaItem) {
        player.setMediaItem(item)
    }

    override fun prepare() {
        player.prepare()
    }

    override fun seekToTrack(index: Int, positionMs: Long) {
        player.seekTo(index, positionMs)
    }

    override fun seekToPosition(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun addListener(listener: ListenTogetherPlaybackListener) {
        val bridge = bridges.getOrPut(listener) { PlayerListenerBridge(listener) }
        player.addListener(bridge)
    }

    override fun removeListener(listener: ListenTogetherPlaybackListener) {
        bridges.remove(listener)?.let(player::removeListener)
    }

    /** 把 `Player.Events` 的标志位翻成窄事件，回调里带上适配器自身作为事件来源。 */
    private inner class PlayerListenerBridge(
        private val listener: ListenTogetherPlaybackListener,
    ) : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            listener.onEvents(
                this@PlayerListenTogetherPlayback,
                ListenTogetherPlaybackEvents(
                    isPlayingChanged = events.contains(Player.EVENT_IS_PLAYING_CHANGED),
                    timelineChanged = events.contains(Player.EVENT_TIMELINE_CHANGED),
                    mediaItemTransition = events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION),
                    positionDiscontinuity = events.contains(Player.EVENT_POSITION_DISCONTINUITY),
                ),
            )
        }
    }
}

private fun Player.neteaseTrackIdAt(index: Int): String? = getMediaItemAt(index).neteaseTrackIdOrNull()

private fun MediaItem.neteaseTrackIdOrNull(): String? =
    onlineIdentityOrNull()?.takeIf { it.source == NeteaseSourceId }?.trackId
