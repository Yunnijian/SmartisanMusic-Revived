package com.smartisan.music.playback

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/**
 * 落盘写侧测试用的替身与夹具。手写，不引第三方 mock 库（本模块单测只依赖 junit 与 org.json）。
 *
 * 真 [DataStore] 在这里是能用的：`PreferenceDataStoreFactory.create { File(...) }` 不碰 Android 框架，
 * 临时目录里的 preferences 文件跟设备上跑的完全同一条代码路径，于是「这一次落盘动了哪个文件」
 * 可以按次数与字节断言，而不是只看调用序列。
 */

/** 与生产同名的队列文件（`playback_session_state.preferences_pb`）。 */
internal const val QueueStoreFileName = "playback_session_state.preferences_pb"

/** 与生产同名的进度文件（`playback_session_progress.preferences_pb`）。 */
internal const val ProgressStoreFileName = "playback_session_progress.preferences_pb"

internal fun createTestPreferencesDataStore(file: File): DataStore<Preferences> {
    return PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    ) { file }
}

/**
 * 真 [DataStore] 外面的一层记录：数 `updateData` 调用——preferences 的 `edit` 每次都走它，
 * 一次调用就是一次整份文件的重新序列化 + 重写；读计数则是 `data` 被收集的次数。
 * [failNextWrite] 在碰真文件之前抛，模拟磁盘 IO 异常，真 DataStore 自身不受影响、后续写照常。
 */
internal class RecordingPreferencesDataStore(
    private val delegate: DataStore<Preferences>,
) : DataStore<Preferences> {

    private val writes = AtomicInteger()
    private val reads = AtomicInteger()
    private var pendingWriteFailure: Throwable? = null

    /** 写盘期间发生的事：用来模拟「快照写下去的过程中播放器又变了」。 */
    var duringUpdate: (() -> Unit)? = null

    override val data: Flow<Preferences> = delegate.data.onEach { reads.incrementAndGet() }

    /** 整份文件重写次数，含被注入失败的尝试。 */
    val writeCount: Int get() = writes.get()

    /** `data` 被收集次数，近似「读了几次盘」。 */
    val readCount: Int get() = reads.get()

    fun failNextWrite(error: Throwable) {
        pendingWriteFailure = error
    }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        writes.incrementAndGet()
        duringUpdate?.invoke()
        pendingWriteFailure?.let { error ->
            pendingWriteFailure = null
            throw error
        }
        return delegate.updateData(transform)
    }
}

/** 落盘测试用的播放器：[PlaybackSessionStatePlayer] 的最小实现，状态由测试直接摆。 */
internal class FakePlaybackSessionStatePlayer : PlaybackSessionStatePlayer {

    private val listeners = mutableListOf<PlaybackSessionStatePlayerListener>()

    override var isPlaying = true
    var progress = PlaybackSessionProgressSnapshot()
    var queue = PlaybackSessionQueueSnapshot()

    override var repeatMode: Int = Player.REPEAT_MODE_OFF
    override var shuffleModeEnabled: Boolean = false

    override val hasQueueItems: Boolean get() = queue.mediaIds.isNotEmpty()

    override fun addListener(listener: PlaybackSessionStatePlayerListener) {
        listeners += listener
    }

    override fun removeListener(listener: PlaybackSessionStatePlayerListener) {
        listeners -= listener
    }

    override fun progressSnapshot(): PlaybackSessionProgressSnapshot = progress

    override suspend fun queueSnapshot(): PlaybackSessionQueueSnapshot = queue

    override fun replaceQueuePaused(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        error("落盘测试不该动播放器队列")
    }

    /** 与 [Player.EVENT_TIMELINE_CHANGED] 同义：队列结构可能变了。 */
    fun emitTimelineChanged() {
        emitPlayerEvents(Player.EVENT_TIMELINE_CHANGED)
    }

    /** 与 [Player.EVENT_PLAYBACK_STATE_CHANGED] 同义：只与进度有关。 */
    fun emitPlaybackStateChanged() {
        emitPlayerEvents(Player.EVENT_PLAYBACK_STATE_CHANGED)
    }

    private fun emitPlayerEvents(vararg eventFlags: Int) {
        val events = PlaybackSessionStatePlayerEvents { event -> eventFlags.contains(event) }
        listeners.toList().forEach { listener -> listener.onPlayerEvents(events) }
    }
}

/**
 * 一次落盘测试的全套件：临时目录里两个真实 preferences 文件 + 真 store + Fake 播放器。
 * 断言用的 [savedQueueMediaIds]/[savedProgress] 走 store 自己的读路径，确保看到的是盘上的结果。
 */
internal class PlaybackSessionWriteFixture {

    val directory: File = Files.createTempDirectory("playback-session-state-test-").toFile()
    val queueFile: File = File(directory, QueueStoreFileName)
    val progressFile: File = File(directory, ProgressStoreFileName)
    val queueDataStore = RecordingPreferencesDataStore(createTestPreferencesDataStore(queueFile))
    val progressDataStore = RecordingPreferencesDataStore(createTestPreferencesDataStore(progressFile))
    val stateStore = PlaybackSessionStateStore(
        queueDataStore = queueDataStore,
        progressDataStore = progressDataStore,
    )

    suspend fun savedQueueMediaIds(): List<String> = stateStore.load().queue.mediaIds

    suspend fun savedProgress(): PlaybackSessionProgressSnapshot = stateStore.load().progress

    fun delete() {
        directory.deleteRecursively()
    }
}

internal fun queueSnapshotOf(vararg mediaIds: String): PlaybackSessionQueueSnapshot {
    return PlaybackSessionQueueSnapshot(
        mediaIds = mediaIds.toList(),
        queueItems = mediaIds.map { mediaId -> PlaybackQueueSnapshotItem(mediaId = mediaId) },
    )
}
