package com.smartisan.music.listentogether

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回声过滤的会话级回归测试（缺陷①的两个止血点）。
 *
 * selfId 拿不到时（资料接口失败）回声只能靠「上报窗口 + 命令水位」认，这里把两条都钉死：
 * - SkipEcho 必须把命令水位推过去 → [echoPushesServerSeqWatermarkSoReplayIsStaleNotApplied]；
 *   不推水位的旧实现会让同一条回显在窗口过期后被当成新命令再应用一次（每秒 seek 回旧位置）。
 * - 上报窗口必须记在发送之前 → [reportWindowIsRecordedBeforeTheSendSoInFlightEchoIsSkipped]；
 *   发送失败同样要算 → [reportWindowIsRecordedEvenWhenTheReportRequestFails]。
 *
 * 每条用例都带一条「栅栏命令」（其它用户在队列里点的新歌）：它被应用即说明前面的回显都已处置完，
 * 于是「回显没有被应用」的否定断言不会因为并发的轮询还没跑到而假绿。
 */
class ListenTogetherStoreEchoTest {

    @Test
    fun echoPushesServerSeqWatermarkSoReplayIsStaleNotApplied() = runBlocking {
        val fixture = EchoFixture()

        reportLocalPlaybackEvent(fixture)

        // 服务端每秒回读同一条命令：第一次落在上报窗口内（认回声），第二次窗口已过期（旧实现会再应用一次）。
        fixture.api.arrivals += ListenTogetherPollArrival(command = echoCommand, advanceMs = 200L)
        fixture.api.arrivals += ListenTogetherPollArrival(command = echoCommand, advanceMs = 1_500L)
        fixture.api.arrivals += ListenTogetherPollArrival(command = fenceCommand)

        awaitUntil("栅栏命令（其它用户的新命令）应被应用，说明前面的回显都已处置完") {
            fixture.playback.positionSeeks.contains(FenceProgressMs)
        }

        assertFalse(
            "窗口过期后的同一条回显必须被 SkipEcho 推高的水位挡住（SkipStale）；" +
                "水位不推就会 seek 回 ${EchoProgressMs}ms 的旧位置。实际播放器动作：${fixture.playback.actions}",
            fixture.playback.positionSeeks.contains(EchoProgressMs),
        )
    }

    @Test
    fun reportWindowIsRecordedBeforeTheSendSoInFlightEchoIsSkipped() = runBlocking {
        val fixture = EchoFixture()
        val gate = CompletableDeferred<Unit>()
        fixture.api.reportSendGate = gate

        reportLocalPlaybackEvent(fixture)

        assertEquals("本地播放事件应触发一次命令上报", 1, fixture.api.commandReportCalls.size)
        assertFalse("前置条件：上报请求应还挂在网关上（还没返回）", gate.isCompleted)

        // 服务端回显比请求返回更早到：这条命令必须已经被「发送前记下的窗口」认出来。
        fixture.api.arrivals += ListenTogetherPollArrival(command = echoCommand, advanceMs = 300L)
        fixture.api.arrivals += ListenTogetherPollArrival(command = fenceCommand)

        awaitUntil("栅栏命令（其它用户的新命令）应被应用") {
            fixture.playback.positionSeeks.contains(FenceProgressMs)
        }

        assertFalse(
            "上报还没返回时到达的回显必须被跳过（窗口要记在发送之前）。" +
                "实际播放器动作：${fixture.playback.actions}",
            fixture.playback.positionSeeks.contains(EchoProgressMs),
        )

        gate.complete(Unit)
        assertEquals("放行后上报请求应正常完成", 1, fixture.api.completedCommandReports.size)
    }

    @Test
    fun reportWindowIsRecordedEvenWhenTheReportRequestFails() = runBlocking {
        val fixture = EchoFixture()
        fixture.api.reportCommandFailure = IOException("network down")

        reportLocalPlaybackEvent(fixture)

        assertEquals("上报请求应已发出（哪怕会失败）", 1, fixture.api.commandReportCalls.size)
        assertTrue("前置条件：上报确实失败、没有完成", fixture.api.completedCommandReports.isEmpty())

        fixture.api.arrivals += ListenTogetherPollArrival(command = echoCommand, advanceMs = 100L)
        fixture.api.arrivals += ListenTogetherPollArrival(command = fenceCommand)

        awaitUntil("栅栏命令（其它用户的新命令）应被应用") {
            fixture.playback.positionSeeks.contains(FenceProgressMs)
        }

        assertFalse(
            "发送失败也要算：窗口内的回显同样必须被跳过。实际播放器动作：${fixture.playback.actions}",
            fixture.playback.positionSeeks.contains(EchoProgressMs),
        )
    }
}

/**
 * 回声用例的公共前置：资料接口失败 + 三人房（认不出自己）→ selfId 未知，
 * 回声只能靠上报窗口 + 命令水位认，正是缺陷①的现场。
 */
private class EchoFixture {

    val clock = VirtualClock()
    val api = FakeListenTogetherApi(clock)
    val playback = FakeListenTogetherPlayback(
        currentTrackId = CurrentTrackId,
        isPlaying = true,
        queueTrackIds = listOf(CurrentTrackId, EchoTargetTrackId, FenceTargetTrackId),
    )
    val store = listenTogetherStore(api, clock)

    init {
        api.joinedRoom = listenTogetherRoom(creatorId = 99L, memberIds = listOf(99L, 42L, 7L))
        store.attachPlayback(playback)
        store.offerJoinFromUrl(inviteUrl)
        store.confirmPendingInvite()

        assertEquals("前置条件：应已入房", "room-1", store.state.value.roomId)
        assertEquals("前置条件：入房请求恰好一次", listOf("room-1" to "42"), api.joinCalls)
        assertNull(
            "前置条件：资料接口失败 + 三人房认不出自己，selfId 必须为空（否则回声走 userId 分支，测不到窗口/水位）",
            store.state.value.selfUserId,
        )
    }
}

/** 本地上报一条命令：播放器事件是生产代码里除建房之外唯一的上报入口。 */
private fun reportLocalPlaybackEvent(fixture: EchoFixture) {
    fixture.playback.emitEvents(ListenTogetherPlaybackEvents(isPlayingChanged = true))
    assertEquals(
        "本地播放事件应上报一条命令（commandType=PLAY）",
        1,
        fixture.api.commandReportCalls.size,
    )
    assertTrue(
        "上报内容应带自己的 clientSeq（从 1 开始）",
        fixture.api.commandReportCalls.first().second.contains("\"clientSeq\":1"),
    )
}

private const val CurrentTrackId = "111"
private const val EchoTargetTrackId = "222"
private const val FenceTargetTrackId = "333"

/** 自己上报的那条命令的进度：回显被应用就会 seek 到这个位置。 */
private const val EchoProgressMs = 42_000L

/** 栅栏命令的进度：只有它被 seek 才说明后面的命令真的处理完了。 */
private const val FenceProgressMs = 99_999L

private val inviteUrl = buildListenTogetherInviteUrl(roomId = "room-1", inviterId = "42")

/** 自己上报后被服务端回显的命令：userId 缺失，clientSeq 与最近一次上报的值一致。 */
private val echoCommand = listenTogetherCommand(
    userId = null,
    clientSeq = 1L,
    serverSeq = 7L,
    targetSongId = EchoTargetTrackId,
    progressMs = EchoProgressMs,
    playStatus = "PLAY",
)

/** 栅栏命令：队列里另一首新歌（clientSeq 是对方自己的计数，比自己的新），被应用即证明前面都已处置。 */
private val fenceCommand = listenTogetherCommand(
    userId = 999L,
    clientSeq = 5L,
    serverSeq = 8L,
    targetSongId = FenceTargetTrackId,
    progressMs = FenceProgressMs,
    playStatus = "PAUSE",
)
