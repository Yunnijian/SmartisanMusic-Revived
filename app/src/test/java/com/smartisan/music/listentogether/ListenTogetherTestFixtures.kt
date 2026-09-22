package com.smartisan.music.listentogether

import androidx.media3.common.MediaItem
import com.smartisan.music.data.online.ListenTogetherCreateResult
import com.smartisan.music.data.online.ListenTogetherPlayCommand
import com.smartisan.music.data.online.ListenTogetherRoom
import com.smartisan.music.data.online.ListenTogetherRoomStatus
import com.smartisan.music.data.online.ListenTogetherStatistics
import com.smartisan.music.data.online.ListenTogetherStatisticsResult
import com.smartisan.music.data.online.ListenTogetherStatusResult
import com.smartisan.music.data.online.ListenTogetherSyncResult
import com.smartisan.music.data.online.ListenTogetherSyncSnapshot
import com.smartisan.music.data.online.ListenTogetherUser
import com.smartisan.music.data.online.NeteaseAccountActionResult
import com.smartisan.music.data.online.NeteaseAccountActionStatus
import com.smartisan.music.data.online.NeteaseAccountProfile
import com.smartisan.music.data.online.OnlineTrackIdentity
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * [ListenTogetherStore] 回归测试的公共夹具。
 *
 * 手写假实现 + 立即执行调度器 + 虚拟时钟：不用 mockk/robolectric/kotlinx-coroutines-test，
 * 但状态机是真的在跑（轮询、入房、上报、远端命令处置都走生产代码路径）。
 */

/** 测试用虚拟时钟：1s 回声窗口的「窗口内 / 窗口外」由测试自己拨，不依赖真实时间。 */
internal class VirtualClock(private var nowMs: Long = 1_000_000L) {
    fun now(): Long = nowMs

    fun advance(ms: Long) {
        nowMs += ms
    }
}

/**
 * 立即执行的调度器：`launch` 直接在当前线程跑到第一个挂起点。
 *
 * 挂起之后的恢复（`delay`、[CompletableDeferred]）由默认调度器串行推进，
 * 因此断言不受线程调度抖动影响，也不需要 sleep 轮询竞态之外的等待。
 */
internal object ImmediateDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        block.run()
    }
}

/**
 * 一次轮询的到达脚本：先按 [advanceMs] 拨虚拟时钟（「这条命令到达时已经过去了多久」），
 * 再回读 [command]；[command] 为 null 表示这一轮服务端没有命令。
 */
internal data class ListenTogetherPollArrival(
    val command: ListenTogetherPlayCommand?,
    val advanceMs: Long = 0L,
)

/** 组装一个跑在立即执行调度器 + 虚拟时钟上的 Store；轮询间隔压到几十毫秒。 */
internal fun listenTogetherStore(
    api: ListenTogetherApi,
    clock: VirtualClock,
    pollIntervalMs: Long = 25L,
): ListenTogetherStore {
    return ListenTogetherStore(
        api = api,
        scope = CoroutineScope(SupervisorJob() + ImmediateDispatcher),
        clock = clock::now,
        pollIntervalMs = pollIntervalMs,
    )
}

internal fun listenTogetherRoom(
    roomId: String = "room-1",
    creatorId: Long = 99L,
    memberIds: List<Long> = listOf(99L, 42L, 7L),
): ListenTogetherRoom {
    return ListenTogetherRoom(
        roomId = roomId,
        creatorId = creatorId,
        users = memberIds.map { ListenTogetherUser(userId = it, nickname = null, avatarUrl = null) },
        roomCreateTime = null,
        effectiveDurationMs = null,
        roomType = null,
    )
}

internal fun listenTogetherCommand(
    userId: Long? = null,
    clientSeq: Long = 1L,
    serverSeq: Long = 7L,
    targetSongId: String? = "222",
    progressMs: Long = 0L,
    playStatus: String = "PAUSE",
): ListenTogetherPlayCommand {
    return ListenTogetherPlayCommand(
        commandType = "GOTO",
        progressMs = progressMs,
        playStatus = playStatus,
        formerSongId = "-1",
        targetSongId = targetSongId,
        clientSeq = clientSeq,
        serverSeq = serverSeq,
        userId = userId,
    )
}

/** 等到 [condition] 成立；超时即失败，并把 [what] 写进消息（避免「断言绿了但其实没跑」）。 */
internal fun awaitUntil(
    what: String,
    timeoutMs: Long = 3_000L,
    condition: () -> Boolean,
) {
    val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L
    while (System.nanoTime() < deadlineNanos) {
        if (condition()) {
            return
        }
        Thread.sleep(5L)
    }
    org.junit.Assert.fail("等待 ${timeoutMs}ms 仍未满足：$what")
}

/**
 * 手写假接口：记录调用参数，回放预设状态，可按轮次脚本化 sync 的回读结果。
 *
 * 所有记录用 [CopyOnWriteArrayList]/[AtomicInteger]：Store 的协程跑在其它线程上，
 * 测试线程读记录不能靠运气。
 */
internal class FakeListenTogetherApi(
    private val clock: VirtualClock,
) : ListenTogetherApi {

    val joinCalls = CopyOnWriteArrayList<Pair<String, String>>()
    val endCalls = CopyOnWriteArrayList<String>()
    val commandReportCalls = CopyOnWriteArrayList<Pair<String, String>>()
    val completedCommandReports = CopyOnWriteArrayList<Pair<String, String>>()
    val playlistReportCalls = CopyOnWriteArrayList<Pair<String, String>>()
    val heartbeatCalls = CopyOnWriteArrayList<Triple<String, String, Long>>()
    val statisticsCalls = CopyOnWriteArrayList<List<Long>>()
    val createCount = AtomicInteger()
    val syncCount = AtomicInteger()
    val profileCount = AtomicInteger()

    /** sync 的回读脚本，按轮次消费；用完之后一律没有命令。 */
    val arrivals = CopyOnWriteArrayList<ListenTogetherPollArrival>()

    /** 资料接口：连失败 [profileFailuresBeforeSuccess] 次后返回 [profile]。 */
    var profile: NeteaseAccountProfile? = null
    var profileFailuresBeforeSuccess = 0

    var createdRoom: ListenTogetherRoom = listenTogetherRoom(creatorId = 99L, memberIds = listOf(99L))
    var joinedRoom: ListenTogetherRoom = listenTogetherRoom()
    var statusRoom: ListenTogetherRoom? = null
    var inRoom = true

    /** 上报命令时先挂在这个网关上（模拟「请求还没返回，回显已经先到」）。 */
    var reportSendGate: CompletableDeferred<Unit>? = null

    var reportCommandFailure: Throwable? = null

    /** 让 sync 直接抛（含 [kotlinx.coroutines.CancellationException]）：验证取消信号不被吞。 */
    var syncFailure: Throwable? = null

    override suspend fun currentUserProfile(): NeteaseAccountProfile? {
        val call = profileCount.incrementAndGet()
        if (call <= profileFailuresBeforeSuccess) {
            throw IOException("资料接口第 $call 次调用失败")
        }
        return profile
    }

    override suspend fun createRoom(): ListenTogetherCreateResult {
        createCount.incrementAndGet()
        return ListenTogetherCreateResult(
            status = NeteaseAccountActionStatus.Success,
            room = createdRoom,
        )
    }

    override suspend fun acceptInvitation(
        roomId: String,
        inviterId: String,
    ): ListenTogetherCreateResult {
        joinCalls += roomId to inviterId
        return ListenTogetherCreateResult(
            status = NeteaseAccountActionStatus.Success,
            room = joinedRoom,
        )
    }

    override suspend fun endRoom(roomId: String): NeteaseAccountActionResult {
        endCalls += roomId
        return NeteaseAccountActionResult(NeteaseAccountActionStatus.Success)
    }

    override suspend fun sync(roomId: String): ListenTogetherSyncResult {
        syncCount.incrementAndGet()
        syncFailure?.let { throw it }
        val arrival = if (arrivals.isEmpty()) null else arrivals.removeAt(0)
        arrival?.let { clock.advance(it.advanceMs) }
        return ListenTogetherSyncResult(
            status = NeteaseAccountActionStatus.Success,
            snapshot = ListenTogetherSyncSnapshot(command = arrival?.command, playlist = null),
        )
    }

    override suspend fun status(): ListenTogetherStatusResult {
        val room = statusRoom ?: joinedRoom
        if (!inRoom) {
            return ListenTogetherStatusResult(
                status = NeteaseAccountActionStatus.Success,
                roomStatus = ListenTogetherRoomStatus(inRoom = false, room = null, status = "ended"),
            )
        }
        return ListenTogetherStatusResult(
            status = NeteaseAccountActionStatus.Success,
            roomStatus = ListenTogetherRoomStatus(inRoom = true, room = room, status = "in"),
        )
    }

    override suspend fun heartbeat(
        roomId: String,
        songId: String,
        playStatus: String,
        progressMs: Long,
    ): NeteaseAccountActionResult {
        heartbeatCalls += Triple(songId, playStatus, progressMs)
        return NeteaseAccountActionResult(NeteaseAccountActionStatus.Success)
    }

    override suspend fun reportCommand(
        roomId: String,
        commandInfo: String,
    ): NeteaseAccountActionResult {
        commandReportCalls += roomId to commandInfo
        reportSendGate?.await()
        reportCommandFailure?.let { throw it }
        completedCommandReports += roomId to commandInfo
        return NeteaseAccountActionResult(NeteaseAccountActionStatus.Success)
    }

    override suspend fun reportPlaylist(
        roomId: String,
        playlistParam: String,
    ): NeteaseAccountActionResult {
        playlistReportCalls += roomId to playlistParam
        return NeteaseAccountActionResult(NeteaseAccountActionStatus.Success)
    }

    override suspend fun statistics(
        roomId: String,
        roomUserIds: List<Long>,
    ): ListenTogetherStatisticsResult {
        statisticsCalls += roomUserIds
        return ListenTogetherStatisticsResult(
            status = NeteaseAccountActionStatus.Success,
            statistics = ListenTogetherStatistics(totalConnectionTimeSeconds = 0L, listenCount = 0),
        )
    }

    /** 单测里不构造 MediaItem：命令处置需要它时一律返回空列表（转成「无法播放」提示）。 */
    override suspend fun mediaItems(identities: List<OnlineTrackIdentity>): List<MediaItem> =
        emptyList()

    override suspend fun playableItems(
        identities: List<OnlineTrackIdentity>,
        includeLyrics: Boolean,
    ): List<MediaItem> = emptyList()
}

/**
 * 手写假播放器：记录所有动作，并按需模拟一次播放器事件回调。
 *
 * 队列只记网易云曲目 id，够状态机判「目标歌在不在队列里」用；`MediaItem` 不构造。
 */
internal class FakeListenTogetherPlayback(
    override var currentTrackId: String? = "111",
    override var isPlaying: Boolean = false,
    override var currentPositionMs: Long = 0L,
    override var queueTrackIds: List<String?> = emptyList(),
) : ListenTogetherPlayback {

    private val listeners = CopyOnWriteArrayList<ListenTogetherPlaybackListener>()

    val actions = CopyOnWriteArrayList<String>()
    val trackSeeks = CopyOnWriteArrayList<Pair<Int, Long>>()
    val positionSeeks = CopyOnWriteArrayList<Long>()

    override fun trackIndex(trackId: String): Int = queueTrackIds.indexOf(trackId)

    override fun setQueue(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        actions += "setQueue(startIndex=$startIndex, startPositionMs=$startPositionMs)"
    }

    override fun setCurrentItem(item: MediaItem) {
        actions += "setCurrentItem"
    }

    override fun prepare() {
        actions += "prepare"
    }

    override fun seekToTrack(index: Int, positionMs: Long) {
        trackSeeks += index to positionMs
        actions += "seekToTrack(index=$index, positionMs=$positionMs)"
    }

    override fun seekToPosition(positionMs: Long) {
        positionSeeks += positionMs
        actions += "seekToPosition(positionMs=$positionMs)"
    }

    override fun play() {
        actions += "play"
    }

    override fun pause() {
        actions += "pause"
    }

    override fun addListener(listener: ListenTogetherPlaybackListener) {
        listeners += listener
    }

    override fun removeListener(listener: ListenTogetherPlaybackListener) {
        listeners -= listener
    }

    /** 模拟一次播放器事件回调，等价于 `Player.Listener.onEvents(player, events)`。 */
    fun emitEvents(events: ListenTogetherPlaybackEvents) {
        listeners.forEach { it.onEvents(this, events) }
    }
}
