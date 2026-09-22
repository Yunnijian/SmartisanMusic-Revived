package com.smartisan.music.ui.cloud.components

import androidx.media3.common.MediaItem
import com.smartisan.music.R
import com.smartisan.music.data.online.NeteaseAccountActionResult
import com.smartisan.music.data.online.NeteaseAccountActionStatus
import com.smartisan.music.data.online.OnlineAccountPlaylist
import com.smartisan.music.data.online.OnlineAccountPlaylistCreateResult
import com.smartisan.music.data.online.OnlineMusicProvider
import com.smartisan.music.data.online.OnlineMusicProviderRepository
import com.smartisan.music.data.online.OnlineTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CloudTrackActionsRunner] 两处修复的回归：新建歌单并加歌的三态提示、加入播放队列的失败提示。
 *
 * 这两条分支原先写在 Composable 闭包里（Toast + `rememberCoroutineScope` + 仓库），JVM 单测够不到：
 * 把修复回滚成「丢弃加歌结果、无条件提示成功」或「队列缺项时静默 return」，都不会有任何测试报警。
 * 运行器把文案与调用顺序提出来之后，这里逐条钉住。
 *
 * 覆盖边界：
 * - 断言的是**字符串资源 id**，不是最终文案：`R.string` 到界面上那句话的映射由资源文件负责，
 *   消息出口在单测里被换成记录资源 id 的假实现（Composable 里注入的才是 Toast）；
 * - [CloudTrackActionsRunner.queueMediaItem] 在单测里被替换：`OnlineTrack.toMediaItem()` 要构造
 *   `android.os.Bundle`，JVM 单测的桩实现调用即抛，所以「真实入队的媒体项长什么样」由设备端路径覆盖，
 *   这里覆盖的是「什么时候入队、入几次」；
 * - Composable 一侧的接线（`LocalPlaybackBrowser` → [PlaybackQueueInserter]、`context.getString` → Toast）
 *   仍不在单测范围内；
 * - 账户仓库与队列出口都是手写 Fake（项目无 mock 框架），协程用 `runBlocking` 驱动，
 *   `CancellationException` 按项目规约原样上抛。
 */
class CloudTrackActionsRunnerTest {

    @Test
    fun createAndAddSuccessShowsOnlyTheCreatedAndAddedMessage() = runBlocking {
        val track = sampleTrack()
        val fixture = Fixture(track = track)

        fixture.runner.createPlaylistAndAddTrack("新歌单")

        assertEquals(
            "建歌单与加歌都成功时只该出一条「已创建并加入」",
            listOf(R.string.cloud_music_created_and_added),
            fixture.messages,
        )
        assertEquals("弹窗里输入的名字应原样交给仓库", listOf("新歌单"), fixture.repository.createCalls)
        assertEquals(
            "加歌应打在刚建好的歌单上，且只带当前这首歌的 trackId",
            listOf(playlist("playlist-new") to listOf(track.trackId)),
            fixture.repository.addCalls,
        )
        assertEquals("歌单建好后应刷新一次账号歌单列表", 1, fixture.libraryChangedCount)
    }

    @Test
    fun addFailureAfterCreateShowsCreatedAddFailedAndStillRefreshesLibrary() = runBlocking {
        val fixture = Fixture()
        fixture.repository.addResult =
            NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed, code = 400)

        fixture.runner.createPlaylistAndAddTrack("新歌单")

        assertEquals(
            "加歌失败要说清「歌单已创建，添加歌曲失败」，不能报成功也不能报通用失败",
            listOf(R.string.cloud_music_created_add_failed),
            fixture.messages,
        )
        assertEquals(
            "歌单本身确实建好了，加歌失败同样要刷新账号歌单列表",
            1,
            fixture.libraryChangedCount,
        )
        assertEquals("加歌请求应恰好发起一次", 1, fixture.repository.addCalls.size)
    }

    @Test
    fun addRequiresLoginAfterCreateShowsTheLoginHint() = runBlocking {
        val fixture = Fixture()
        fixture.repository.addResult =
            NeteaseAccountActionResult(NeteaseAccountActionStatus.RequiresLogin, code = 301)

        fixture.runner.createPlaylistAndAddTrack("新歌单")

        assertEquals(
            "加歌时未登录要引导登录，而不是报通用失败或成功",
            listOf(R.string.cloud_music_detail_login_required),
            fixture.messages,
        )
        assertEquals("请求已经发出去了，这一步也要刷新账号歌单列表", 1, fixture.libraryChangedCount)
    }

    @Test
    fun createFailureDoesNotAddTrackAndShowsGenericFailureMessage() = runBlocking {
        val fixture = Fixture()
        fixture.repository.createResult =
            OnlineAccountPlaylistCreateResult(
                status = NeteaseAccountActionStatus.Failed,
                code = 403,
                // 故意带上歌单对象：要证明拦下来的是 status，不是 playlist 为空。
                playlist = playlist("playlist-new"),
            )

        fixture.runner.createPlaylistAndAddTrack("新歌单")

        assertTrue(
            "建歌单失败时不该给那个歌单加歌",
            fixture.repository.addCalls.isEmpty(),
        )
        assertEquals(
            "建歌单失败走通用失败文案",
            listOf(R.string.cloud_music_action_failed),
            fixture.messages,
        )
        assertEquals(
            "歌单没建成，不该刷新账号歌单列表",
            0,
            fixture.libraryChangedCount,
        )
    }

    @Test
    fun createWithoutPlaylistDoesNotAddTrack() = runBlocking {
        val fixture = Fixture()
        fixture.repository.createResult =
            OnlineAccountPlaylistCreateResult(
                status = NeteaseAccountActionStatus.Success,
                playlist = null,
            )

        fixture.runner.createPlaylistAndAddTrack("新歌单")

        assertTrue(
            "没拿到歌单对象就无从加歌，不能拿 null 歌单去发起写入",
            fixture.repository.addCalls.isEmpty(),
        )
        assertEquals("没拿到歌单对象时也不会刷新账号歌单列表", 0, fixture.libraryChangedCount)
        // 文案沿用现有映射（按 status 出成功串），本轮不修改这条决策；这里钉的是「不加歌」这条底线。
        assertEquals(
            listOf(R.string.cloud_music_created_and_added),
            fixture.messages,
        )
    }

    @Test
    fun createRequiresLoginShowsTheLoginHintWithoutAddingTrack() = runBlocking {
        val fixture = Fixture()
        fixture.repository.createResult =
            OnlineAccountPlaylistCreateResult(
                status = NeteaseAccountActionStatus.RequiresLogin,
                code = 301,
            )

        fixture.runner.createPlaylistAndAddTrack("新歌单")

        assertTrue("未登录建歌单不该继续加歌", fixture.repository.addCalls.isEmpty())
        assertEquals(
            "未登录建歌单要引导登录",
            listOf(R.string.cloud_music_detail_login_required),
            fixture.messages,
        )
    }

    @Test
    fun withoutPendingTrackCreateDoesNothing() = runBlocking {
        val fixture = Fixture(track = null)

        fixture.runner.createPlaylistAndAddTrack("新歌单")

        assertTrue("没有待处理歌曲时不该建歌单", fixture.repository.createCalls.isEmpty())
        assertTrue("也不该有任何提示：弹窗那侧已经收掉了", fixture.messages.isEmpty())
    }

    @Test
    fun addToQueueWithoutPendingTrackShowsFailureAndInsertsNothing() {
        val fixture = Fixture(track = null)

        fixture.runner.addPendingTrackToQueue()

        assertEquals(
            "没有待处理歌曲时要提示操作失败，不能静默什么都不发生",
            listOf(R.string.cloud_music_action_failed),
            fixture.messages,
        )
        assertTrue(
            "没有歌曲就不该往队列里塞东西",
            fixture.insertedQueueItems.isEmpty(),
        )
    }

    @Test
    fun addToQueueWithoutPlaybackBrowserShowsFailureAndInsertsNothing() {
        val fixture = Fixture(withPlaybackQueue = false)

        fixture.runner.addPendingTrackToQueue()

        assertEquals(
            "播放浏览器不可用（播放服务未就绪）时要提示操作失败，不能静默",
            listOf(R.string.cloud_music_action_failed),
            fixture.messages,
        )
        assertTrue("没有队列出口时不该有入队记录", fixture.insertedQueueItems.isEmpty())
    }

    @Test
    fun addToQueueWithTrackAndBrowserInsertsExactlyOnceAndShowsSuccess() {
        val track = sampleTrack()
        val fixture = Fixture(track = track)

        fixture.runner.addPendingTrackToQueue()

        assertEquals(
            "歌曲与播放浏览器齐备时应恰好入队一次，且只带这一首",
            listOf(listOf(track.mediaId)),
            fixture.insertedQueueItems.map { items -> items.map(MediaItem::mediaId) },
        )
        assertEquals(
            "入队成功提示",
            listOf(R.string.add_to_queue_success),
            fixture.messages,
        )
    }

    @Test
    fun createAndAddRethrowsCancellation() {
        val fixture = Fixture()
        fixture.repository.failure = CancellationException("cancelled")

        assertThrows(
            "取消信号必须原样上抛，不能被降级成失败提示",
            CancellationException::class.java,
        ) {
            runBlocking { fixture.runner.createPlaylistAndAddTrack("新歌单") }
        }
    }

    /** 调用记录 + 注入依赖的测试夹具：消息出口收资源 id，队列出口收媒体项。 */
    private class Fixture(
        track: OnlineTrack? = sampleTrack(),
        withPlaybackQueue: Boolean = true,
    ) {
        val repository = FakeOnlineMusicProviderRepository()
        val messages = mutableListOf<Int>()
        val insertedQueueItems = mutableListOf<List<MediaItem>>()
        var libraryChangedCount = 0

        val runner =
            CloudTrackActionsRunner(
                repository = repository,
                pendingTrack = { track },
                showMessage = { messageRes -> messages += messageRes },
                onAccountLibraryChanged = { libraryChangedCount += 1 },
                playbackQueue =
                    if (withPlaybackQueue) {
                        PlaybackQueueInserter { items -> insertedQueueItems += items }
                    } else {
                        null
                    },
                queueMediaItem = { item -> MediaItem.Builder().setMediaId(item.mediaId).build() },
            )
    }
}

private const val TrackId = "2001"

private fun sampleTrack(trackId: String = TrackId): OnlineTrack =
    OnlineTrack(
        source = OnlineMusicProvider.Netease.sourceId,
        trackId = trackId,
        title = "歌曲 $trackId",
        artist = "歌手",
        album = "专辑",
        durationMs = 180_000L,
        artworkUrl = null,
    )

private fun playlist(playlistId: String): OnlineAccountPlaylist =
    OnlineAccountPlaylist(
        provider = OnlineMusicProvider.Netease,
        playlistId = playlistId,
        title = "测试歌单",
        trackCount = 0,
        isEditable = true,
    )

/**
 * 手写假仓库：记录建歌单 / 加歌调用，回放预设结果，可配置一次失败（含 [CancellationException]）。
 *
 * 先记录再抛：失败用例也要能断言「请求确实发出去了」。
 */
private class FakeOnlineMusicProviderRepository : OnlineMusicProviderRepository {

    override val provider = OnlineMusicProvider.Netease

    val createCalls = mutableListOf<String>()
    val addCalls = mutableListOf<Pair<OnlineAccountPlaylist, List<String>>>()
    var createResult =
        OnlineAccountPlaylistCreateResult(
            status = NeteaseAccountActionStatus.Success,
            playlist = playlist("playlist-new"),
        )
    var addResult = NeteaseAccountActionResult(NeteaseAccountActionStatus.Success)
    var failure: Throwable? = null

    override suspend fun search(query: String): List<OnlineTrack> = emptyList()

    override suspend fun featuredTracks(): List<OnlineTrack> = emptyList()

    override suspend fun resolvePlayableMediaItem(
        mediaItem: MediaItem,
        includeLyrics: Boolean,
        forceRefresh: Boolean,
    ): MediaItem? = null

    override suspend fun createAccountPlaylist(name: String): OnlineAccountPlaylistCreateResult {
        createCalls += name
        failIfConfigured()
        return createResult
    }

    override suspend fun addTracksToAccountPlaylist(
        playlist: OnlineAccountPlaylist,
        trackIds: List<String>,
    ): NeteaseAccountActionResult {
        addCalls += playlist to trackIds
        failIfConfigured()
        return addResult
    }

    private fun failIfConfigured() {
        failure?.let { throw it }
    }
}
