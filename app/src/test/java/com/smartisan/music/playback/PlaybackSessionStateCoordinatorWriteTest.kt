package com.smartisan.music.playback

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两档落盘的写侧行为：队列结构没变时只写进度文件，变了（或被强制收尾）才重建整份队列快照，
 * 而且构建/写盘期间到来的变化与写失败都不能把「还要再写一次队列」这件事吞掉。
 *
 * 断言落在真 DataStore 上（临时目录里的两个真实 preferences 文件）：看的是「哪个文件被重写了、
 * 里面是什么」，不是内部调用序号，所以把 [PlaybackSessionStateCoordinator.saveSessionState]
 * 改回「每次保存都重建整份队列快照」这类改动会在这里直接红。
 */
class PlaybackSessionStateCoordinatorWriteTest {

    private val fixture = PlaybackSessionWriteFixture()
    private val player = FakePlaybackSessionStatePlayer()

    /** 监听器触发的去抖保存不在这里跑（scope 已取消）：每条用例只观察自己显式发起的那几次落盘。 */
    private val scope: CoroutineScope = CoroutineScope(Job()).also { it.cancel() }

    private val coordinator = createCoordinator(fixture, player, scope)

    @After
    fun deleteFixtureFiles() {
        fixture.delete()
    }

    @Test
    fun progressOnlySaveDoesNotRewriteQueueSnapshot() = runBlocking {
        player.queue = queueSnapshotOf("42", "43")
        coordinator.saveNow()
        val queueBytesAfterSeed = fixture.queueFile.readBytes()
        val queueWritesAfterSeed = fixture.queueDataStore.writeCount

        // 队列一动不动，只有播放位置在走：这是播放中每 15s 那一次保存的样子。
        player.progress = PlaybackSessionProgressSnapshot(currentIndex = 0, positionMs = 5_000L)
        coordinator.saveSessionState()

        assertEquals(5_000L, fixture.savedProgress().positionMs)
        assertEquals(
            "队列没变时不该重建队列快照",
            queueWritesAfterSeed,
            fixture.queueDataStore.writeCount,
        )
        assertArrayEquals(
            "队列文件不该被这一档落盘重写",
            queueBytesAfterSeed,
            fixture.queueFile.readBytes(),
        )
    }

    @Test
    fun dirtyQueueSnapshotIsRewrittenEvenWhenProgressIsUnchanged() = runBlocking {
        player.queue = queueSnapshotOf("42")
        coordinator.saveNow()
        val queueWritesAfterSeed = fixture.queueDataStore.writeCount

        // 队列结构变了（播放器时间线变了），进度没变。
        player.queue = queueSnapshotOf("42", "43")
        coordinator.markQueueSnapshotDirty()
        coordinator.saveSessionState()

        assertEquals(
            "队列结构变了就必须重写队列快照",
            listOf("42", "43"),
            fixture.savedQueueMediaIds(),
        )
        assertTrue(
            "队列文件应该被再写一次",
            fixture.queueDataStore.writeCount > queueWritesAfterSeed,
        )
    }

    @Test
    fun queueChangeArrivingWhileSnapshotIsWrittenKeepsDirtyForNextSave() = runBlocking {
        player.queue = queueSnapshotOf("42")
        coordinator.saveNow()
        player.queue = queueSnapshotOf("42", "43")
        coordinator.markQueueSnapshotDirty()

        // 这一次快照写下去的过程中，播放器队列又变成了 [42, 43, 44]：
        // 变化晚于「清 dirty」、早于写盘返回，不能被本次收尾吞掉。
        fixture.queueDataStore.duringUpdate = {
            player.queue = queueSnapshotOf("42", "43", "44")
            coordinator.markQueueSnapshotDirty()
        }
        coordinator.saveSessionState()
        fixture.queueDataStore.duringUpdate = null

        val queueWritesBeforeRetry = fixture.queueDataStore.writeCount
        coordinator.saveSessionState()

        assertEquals(
            "写盘期间到达的变化被吞了：下一次保存必须再重建一次队列快照",
            listOf("42", "43", "44"),
            fixture.savedQueueMediaIds(),
        )
        assertTrue(
            "写盘期间到达的变化要求再写一次队列文件",
            fixture.queueDataStore.writeCount > queueWritesBeforeRetry,
        )
    }

    @Test
    fun failedQueueSnapshotWriteKeepsDirtyAndRetriesOnNextSave() = runBlocking {
        player.queue = queueSnapshotOf("42")
        coordinator.saveNow()
        player.queue = queueSnapshotOf("42", "43")
        coordinator.markQueueSnapshotDirty()

        fixture.queueDataStore.failNextWrite(IOException("模拟写盘失败"))
        coordinator.saveSessionState()

        assertEquals(
            "写失败时盘上应该还是旧队列",
            listOf("42"),
            fixture.savedQueueMediaIds(),
        )

        coordinator.saveSessionState()

        assertEquals(
            "写失败要留到下一次保存重试，而不是让这次失败把队列变更丢掉",
            listOf("42", "43"),
            fixture.savedQueueMediaIds(),
        )
    }

    @Test
    fun queueSnapshotWriteCancellationIsNotSwallowedAsWriteFailure() = runBlocking {
        player.queue = queueSnapshotOf("42")
        coordinator.saveNow()
        player.queue = queueSnapshotOf("42", "43")
        coordinator.markQueueSnapshotDirty()

        fixture.queueDataStore.failNextWrite(CancellationException("写盘被取消"))

        val thrown = runCatching { coordinator.saveSessionState() }.exceptionOrNull()

        assertTrue(
            "取消信号要原样上抛：被当成写失败吞掉的话，落盘协程会带着陈旧状态继续跑",
            thrown is CancellationException,
        )
    }

    @Test
    fun timelineChangeEventTriggersQueueSnapshotWriteByItself() = runBlocking {
        val eventFixture = PlaybackSessionWriteFixture()
        val eventPlayer = FakePlaybackSessionStatePlayer()
        val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val eventCoordinator = createCoordinator(
            fixture = eventFixture,
            player = eventPlayer,
            scope = eventScope,
            canLoadLibraryItems = { false },
        )
        try {
            eventPlayer.queue = queueSnapshotOf("42", "43")
            eventCoordinator.start()

            // 完整链路：播放器事件 → 监听器标脏 → 250ms 去抖 → 全量快照落盘。
            // 反复发事件是因为启动恢复还没跑完时的事件会被 restoring 挡掉，测试不该和它抢时序。
            val savedMediaIds = awaitSavedQueue(
                fixture = eventFixture,
                expected = listOf("42", "43"),
                emit = eventPlayer::emitTimelineChanged,
            )

            assertEquals(
                "时间线变更事件必须自己把队列快照写下去",
                listOf("42", "43"),
                savedMediaIds,
            )
        } finally {
            eventScope.cancel()
            eventFixture.delete()
        }
    }

    @Test
    fun playbackStateEventTriggersProgressOnlySaveByItself() = runBlocking {
        val eventFixture = PlaybackSessionWriteFixture()
        val eventPlayer = FakePlaybackSessionStatePlayer()
        val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val eventCoordinator = createCoordinator(
            fixture = eventFixture,
            player = eventPlayer,
            scope = eventScope,
            // 队列在盘上还不存在时不落盘的那条分支与本用例无关；关掉它也就关掉了启动恢复
            // 顺手改写队列的可能，好让「队列文件一次都没被写过」成为干净的自证。
            canLoadLibraryItems = { false },
        )
        try {
            eventPlayer.queue = queueSnapshotOf("42", "43")
            eventCoordinator.start()
            val queueWritesBefore = eventFixture.queueDataStore.writeCount

            eventPlayer.progress =
                PlaybackSessionProgressSnapshot(
                    currentMediaId = "42",
                    currentIndex = 0,
                    positionMs = 7_000L,
                )
            awaitSavedProgress(
                fixture = eventFixture,
                expectedPositionMs = 7_000L,
                emit = eventPlayer::emitPlaybackStateChanged,
            )

            assertEquals(
                "只与进度有关的事件不该让队列这一档落盘",
                queueWritesBefore,
                eventFixture.queueDataStore.writeCount,
            )
        } finally {
            eventScope.cancel()
            eventFixture.delete()
        }
    }

    private fun createCoordinator(
        fixture: PlaybackSessionWriteFixture,
        player: FakePlaybackSessionStatePlayer,
        scope: CoroutineScope,
        canLoadLibraryItems: () -> Boolean = { true },
    ): PlaybackSessionStateCoordinator {
        return PlaybackSessionStateCoordinator(
            player = player,
            stateStore = fixture.stateStore,
            scope = scope,
            canLoadLibraryItems = canLoadLibraryItems,
            loadLibraryItemsByQueueKeys = { emptyList() },
        )
    }

    /**
     * 事件驱动的落盘要等去抖（250ms）过去才会发生，所以一次事件之后必须留出安静窗口：
     * 窗口里再发事件只会把上一次的落盘取消掉，永远等不到写盘。
     * 反复尝试是为了避开启动恢复的 restoring 窗口——那期间的事件按设计会被丢掉。
     */
    private suspend fun awaitDebouncedSave(
        emit: () -> Unit,
        isSaved: suspend () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + EventWiringTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            emit()
            val attemptDeadline = System.currentTimeMillis() + EventWiringAttemptMs
            while (System.currentTimeMillis() < attemptDeadline) {
                delay(EventWiringPollIntervalMs)
                if (isSaved()) {
                    return true
                }
            }
        }
        return false
    }

    private suspend fun awaitSavedQueue(
        fixture: PlaybackSessionWriteFixture,
        expected: List<String>,
        emit: () -> Unit,
    ): List<String> {
        awaitDebouncedSave(emit) { fixture.savedQueueMediaIds() == expected }
        return fixture.savedQueueMediaIds()
    }

    private suspend fun awaitSavedProgress(
        fixture: PlaybackSessionWriteFixture,
        expectedPositionMs: Long,
        emit: () -> Unit,
    ) {
        awaitDebouncedSave(emit) { fixture.savedProgress().positionMs == expectedPositionMs }
        assertEquals(
            "进度事件应该让进度这一档自己落盘",
            expectedPositionMs,
            fixture.savedProgress().positionMs,
        )
    }

    private companion object {
        private const val EventWiringTimeoutMs = 20_000L
        private const val EventWiringAttemptMs = 2_000L
        private const val EventWiringPollIntervalMs = 100L
    }
}
