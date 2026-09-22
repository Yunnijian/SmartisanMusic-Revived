@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import com.smartisan.music.AppDispatchers
import com.smartisan.music.data.online.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 落盘链路看到的播放器。抽成接口只为一件事：「什么时候写哪一档、失败怎么重试」这些落盘决策
 * 要在 JVM 单测里用 Fake 驱动，而真 [ExoPlayer] 在单测里建不出来。[ExoPlayerPlaybackSessionStatePlayer]
 * 是唯一的实现，方法语义与 [Player] 上对应的那一个完全一致。
 */
internal interface PlaybackSessionStatePlayer {

    val isPlaying: Boolean

    /** [Player.mediaItemCount] > 0：时间线上有没有条目。 */
    val hasQueueItems: Boolean

    var repeatMode: Int

    var shuffleModeEnabled: Boolean

    fun addListener(listener: PlaybackSessionStatePlayerListener)

    fun removeListener(listener: PlaybackSessionStatePlayerListener)

    /** 进度快照：只碰标量与一次时间线引用，队列有多长都是 O(1)。 */
    fun progressSnapshot(): PlaybackSessionProgressSnapshot

    /** 整队列快照：时间线引用在调用线程取，遍历与序列化在 IO 线程做（见 [Timeline.toPlaybackSessionQueueSnapshot]）。 */
    suspend fun queueSnapshot(): PlaybackSessionQueueSnapshot

    /** 恢复队列：暂停、换队列、prepare。 */
    fun replaceQueuePaused(items: List<MediaItem>, startIndex: Int, startPositionMs: Long)
}

/**
 * [Player.Events] 在落盘侧的替身，只回答「这一批事件里有没有某一位」。
 * 不能直接传 media3 的 [Player.Events]：它是 final class，构造要经 `android.util.SparseBooleanArray`，
 * JVM 单测里造不出来，事件驱动的那条落盘路径就没法被测到。
 */
internal fun interface PlaybackSessionStatePlayerEvents {
    fun contains(event: Int): Boolean
}

internal interface PlaybackSessionStatePlayerListener {
    fun onPlayerEvents(events: PlaybackSessionStatePlayerEvents)
}

internal class PlaybackSessionStateCoordinator(
    private val player: PlaybackSessionStatePlayer,
    private val stateStore: PlaybackSessionStateStore,
    private val scope: CoroutineScope,
    private val canLoadLibraryItems: () -> Boolean,
    private val loadLibraryItemsByQueueKeys: suspend (List<PlaybackQueueSnapshotItem>) -> List<MediaItem>,
) {

    /** 生产入口：把 [ExoPlayer] 包成 [PlaybackSessionStatePlayer]。 */
    constructor(
        player: ExoPlayer,
        stateStore: PlaybackSessionStateStore,
        scope: CoroutineScope,
        canLoadLibraryItems: () -> Boolean,
        loadLibraryItemsByQueueKeys: suspend (List<PlaybackQueueSnapshotItem>) -> List<MediaItem>,
    ) : this(
        player = ExoPlayerPlaybackSessionStatePlayer(player),
        stateStore = stateStore,
        scope = scope,
        canLoadLibraryItems = canLoadLibraryItems,
        loadLibraryItemsByQueueKeys = loadLibraryItemsByQueueKeys,
    )

    private var persistJob: Job? = null
    private var periodicSaveJob: Job? = null
    private var restoring = false

    /** 队列结构变化后置位，直到整份队列快照真正写下去才清除（写失败就留到下次再试）。 */
    private var queueSnapshotDirty = false
    private var lastSavedQueue: PlaybackSessionQueueSnapshot? = null
    private var lastSavedProgress: PlaybackSessionProgressSnapshot? = null

    private val playerListener = object : PlaybackSessionStatePlayerListener {
        override fun onPlayerEvents(events: PlaybackSessionStatePlayerEvents) {
            if (restoring) {
                return
            }
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                markQueueSnapshotDirty()
            }
            if (
                events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                events.contains(Player.EVENT_REPEAT_MODE_CHANGED) ||
                events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) ||
                events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
                events.contains(Player.EVENT_IS_PLAYING_CHANGED) ||
                events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
            ) {
                persist(delayMillis = PlaybackSessionDebounceSaveMs)
            }
        }
    }

    /**
     * 队列结构变了：整份队列快照要重写。播放器事件路径（[playerListener]）与恢复路径
     * （[applyRestoredSnapshot]）都从这里置位，写下去之前一直保持脏。
     */
    internal fun markQueueSnapshotDirty() {
        queueSnapshotDirty = true
    }

    fun start() {
        player.addListener(playerListener)
        periodicSaveJob = scope.launch {
            while (isActive) {
                delay(PlaybackSessionPeriodicSaveIntervalMs)
                if (!restoring && player.isPlaying && player.hasQueueItems) {
                    persist()
                }
            }
        }
        restore()
    }

    fun restoreIfQueueEmpty() {
        if (!player.hasQueueItems) {
            restore()
        }
    }

    fun stop() {
        player.removeListener(playerListener)
        periodicSaveJob?.cancel()
        persistJob?.cancel()
        periodicSaveJob = null
        persistJob = null
    }

    /**
     * 收尾落盘：调用方在 onDestroy 里 runBlocking。进程随时可能被杀，所以这里不过去抖，
     * 但队列内容跟上次真正写下去的一样时仍然只写进度。
     */
    suspend fun saveNow() {
        persistJob?.cancel()
        saveSessionState(forceQueueSnapshot = true)
    }

    private fun restore() {
        if (restoring) {
            return
        }
        restoring = true
        scope.launch {
            var shouldPersistAfterRestore = true
            try {
                val snapshot = loadRestorableSnapshot()
                if (snapshot == null) {
                    shouldPersistAfterRestore = false
                } else {
                    lastSavedQueue = snapshot.queue
                    lastSavedProgress = snapshot.progress
                    shouldPersistAfterRestore = applyRestoredSnapshot(snapshot)
                }
            } finally {
                restoring = false
            }
            if (shouldPersistAfterRestore) {
                persist()
            }
        }
    }

    /**
     * 读盘失败（IO 异常等）时返回 null：调用方据此连随后的落盘一起跳过，
     * 免得拿当前（多半是空的）队列覆盖掉读不出来的旧状态。
     */
    private suspend fun loadRestorableSnapshot(): PlaybackSessionSnapshot? {
        return runSuspendCatching {
            withContext(AppDispatchers.IO) {
                stateStore.load()
            }
        }
            .onFailure { error -> logPlaybackSessionStateFailure(stage = "restore-load", error) }
            .getOrNull()
    }

    /** 把快照套到播放器上，返回「恢复后是否值得再落盘一次」。 */
    private suspend fun applyRestoredSnapshot(snapshot: PlaybackSessionSnapshot): Boolean {
        player.repeatMode = snapshot.progress.repeatMode.sanitizedRepeatMode()
        player.shuffleModeEnabled = snapshot.progress.shuffleModeEnabled
        if (snapshot.queue.mediaIds.isEmpty()) {
            return true
        }
        val restoredItems = runSuspendCatching {
            restoreItems(snapshot).filterRestorablePlaybackItems()
        }
            .onFailure { error -> logPlaybackSessionStateFailure(stage = "restore-items", error) }
            .getOrNull() ?: return false
        if (restoredItems.isEmpty()) {
            // 盘上队列一条都还原不出来（文件被删、暂时读不到曲库）：读不到曲库时保留旧快照，
            // 读得到就用当前空队列覆盖，避免每次启动都去还原一份已经不存在的东西。
            if (!canLoadLibraryItems()) {
                return false
            }
            markQueueSnapshotDirty()
            return true
        }
        if (player.hasQueueItems) {
            return true
        }
        val restoredIndex = snapshot.restoredIndexIn(restoredItems)
        val restoredPositionMs = if (
            restoredItems[restoredIndex].mediaId == snapshot.progress.currentMediaId
        ) {
            snapshot.progress.positionMs.coerceAtLeast(0L)
        } else {
            0L
        }
        player.replaceQueuePaused(restoredItems, restoredIndex, restoredPositionMs)
        // 恢复出来的队列可能比盘上少（文件被删、失权限），标记为脏让随后的 persist
        // 把修剪后的队列写回去。
        markQueueSnapshotDirty()
        return true
    }

    private suspend fun restoreItems(snapshot: PlaybackSessionSnapshot): List<MediaItem> {
        val items = withContext(AppDispatchers.IO) {
            loadLibraryItemsByQueueKeys(snapshot.queue.queueItems)
        }
        return restoreQueueItemOccurrences(snapshot.queue.queueItems, items)
    }

    private fun PlaybackSessionSnapshot.restoredIndexIn(items: List<MediaItem>): Int {
        return restoredQueueIndex(this, items)
    }

    private fun persist(delayMillis: Long = 0L) {
        if (restoring) {
            return
        }
        persistJob?.cancel()
        persistJob = scope.launch {
            if (delayMillis > 0L) {
                delay(delayMillis)
            }
            if (restoring) {
                return@launch
            }
            saveSessionState()
        }
    }

    /**
     * 落盘分两档，各自只跟「上次真正写下去的那一半」比较：
     * - 队列结构没变 → 只写 positionMs/currentIndex/currentMediaId/repeat/shuffle 这些小字段，
     *   播放中每 15s 的周期保存因此不再遍历、序列化整个队列（老实现里 positionMs 天天在变，
     *   「与上次相同就跳过」对这一档永远命中不了）；
     * - 队列变了（[queueSnapshotDirty]）或收尾落盘 → 重建整份队列快照。
     * 队列快照的遍历与序列化都在 IO 线程做，主线程只取一次不可变的时间线引用
     * （见 [PlaybackSessionStatePlayer.queueSnapshot]）。
     * 生产入口是 [persist]（事件/周期，带 250ms 去抖）与 [saveNow]（收尾，强制重建队列快照）。
     */
    internal suspend fun saveSessionState(forceQueueSnapshot: Boolean = false) {
        val progress = player.progressSnapshot()
        if (!forceQueueSnapshot && !queueSnapshotDirty) {
            if (shouldSkipEmptyQueueSave(hasQueueItems = player.hasQueueItems)) {
                return
            }
            if (progress == lastSavedProgress) {
                return
            }
            saveProgressSnapshot(progress)
            return
        }
        // clear-before-snapshot：先清 dirty 再取时间线。快照构建与写盘都让出主线程，
        // 期间到达的时间线变化会由监听器重新置位 dirty，不会被本次收尾清掉。
        queueSnapshotDirty = false
        val queue = player.queueSnapshot()
        if (shouldSkipEmptyQueueSave(hasQueueItems = queue.mediaIds.isNotEmpty())) {
            return
        }
        if (queue == lastSavedQueue && progress == lastSavedProgress) {
            return
        }
        saveQueueSnapshot(queue = queue, progress = progress)
    }

    private suspend fun saveProgressSnapshot(progress: PlaybackSessionProgressSnapshot) {
        val failure = runSuspendCatching {
            withContext(AppDispatchers.IO) {
                stateStore.saveProgressSnapshot(progress)
            }
        }.exceptionOrNull()
        if (failure != null) {
            logPlaybackSessionStateFailure(stage = "progress", failure)
            return
        }
        lastSavedProgress = progress
    }

    private suspend fun saveQueueSnapshot(
        queue: PlaybackSessionQueueSnapshot,
        progress: PlaybackSessionProgressSnapshot,
    ) {
        val failure = runSuspendCatching {
            withContext(AppDispatchers.IO) {
                stateStore.saveQueueSnapshot(queue = queue, progress = progress)
            }
        }.exceptionOrNull()
        if (failure != null) {
            // dirty 在取快照前已清，这里补回：下个周期保存或 onDestroy 收尾时再试一次。
            queueSnapshotDirty = true
            logPlaybackSessionStateFailure(stage = "queue", failure)
            return
        }
        lastSavedQueue = queue
        lastSavedProgress = progress
    }

    /**
     * 队列为空又读不到曲库（如启动初期没拿到音频权限）时不落盘：写下去会把上次会话存的
     * 队列与位置清空。进度这一档用「时间线上有没有条目」近似「快照里有没有 mediaId」，
     * 两者只差在「队列里全是不带 mediaId 的条目」这种极少数情况，且那时丢的也只是续播位置。
     */
    private fun shouldSkipEmptyQueueSave(hasQueueItems: Boolean): Boolean {
        return !hasQueueItems && !canLoadLibraryItems()
    }

    private companion object {
        private const val PlaybackSessionDebounceSaveMs = 250L
        private const val PlaybackSessionPeriodicSaveIntervalMs = 15_000L
    }
}

/** 生产实现：把 [PlaybackSessionStatePlayer] 的每个调用原样转给 [ExoPlayer]。 */
private class ExoPlayerPlaybackSessionStatePlayer(
    private val player: ExoPlayer,
) : PlaybackSessionStatePlayer {

    private val listenerBridges = mutableMapOf<PlaybackSessionStatePlayerListener, Player.Listener>()

    override val isPlaying: Boolean get() = player.isPlaying

    override val hasQueueItems: Boolean get() = player.mediaItemCount > 0

    override var repeatMode: Int
        get() = player.repeatMode
        set(value) {
            player.repeatMode = value
        }

    override var shuffleModeEnabled: Boolean
        get() = player.shuffleModeEnabled
        set(value) {
            player.shuffleModeEnabled = value
        }

    override fun addListener(listener: PlaybackSessionStatePlayerListener) {
        val bridge = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                listener.onPlayerEvents { event -> events.contains(event) }
            }
        }
        listenerBridges[listener] = bridge
        player.addListener(bridge)
    }

    override fun removeListener(listener: PlaybackSessionStatePlayerListener) {
        listenerBridges.remove(listener)?.let(player::removeListener)
    }

    override fun progressSnapshot(): PlaybackSessionProgressSnapshot {
        return player.toPlaybackSessionProgressSnapshot()
    }

    override suspend fun queueSnapshot(): PlaybackSessionQueueSnapshot {
        // 时间线引用在调用线程（主线程）取，遍历与序列化再进 IO。
        val timeline = player.currentTimeline
        return withContext(AppDispatchers.IO) {
            timeline.toPlaybackSessionQueueSnapshot()
        }
    }

    override fun replaceQueuePaused(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        player.restoreQueuePaused(items, startIndex, startPositionMs)
    }
}

/** 主线程读，只碰标量与一次时间线引用，队列有多长都是 O(1)。 */
private fun Player.toPlaybackSessionProgressSnapshot(): PlaybackSessionProgressSnapshot {
    val timeline = currentTimeline
    val currentIndex = currentMediaItemIndex.coerceAtLeast(0)
    return PlaybackSessionProgressSnapshot(
        currentMediaId = timeline.mediaIdAt(currentIndex),
        currentIndex = currentIndex,
        positionMs = currentPosition.coerceAtLeast(0L),
        repeatMode = repeatMode.sanitizedRepeatMode(),
        shuffleModeEnabled = shuffleModeEnabled,
    )
}

/**
 * 队列快照在 IO 线程构建：几千条的元数据读取与字符串分配放主线程会把播放卡住。
 *
 * 跨线程只用主线程上取到的那一个不可变 [Timeline]：media3 换时间线是整体替换已发布实例、
 * 不再改动旧实例，因此这里读到的队列自洽（条数与条目同源），也不需要 ExoPlayer 的
 * 「同一线程访问」保证——反过来说 ExoPlayer 的 `mediaItemCount`/`getMediaItemAt` 不能跨线程调用：
 * 前者会 `verifyApplicationThread()` 抛 IllegalStateException，后者还会复用播放器上的共享
 * Timeline.Window，所以这里的 [Timeline.Window] 用本次遍历私有的实例。
 */
private fun Timeline.toPlaybackSessionQueueSnapshot(): PlaybackSessionQueueSnapshot {
    val window = Timeline.Window()
    val mediaIds = ArrayList<String>(windowCount)
    val queueItems = ArrayList<PlaybackQueueSnapshotItem>(windowCount)
    for (index in 0 until windowCount) {
        val mediaItem = getWindow(index, window).mediaItem
        val mediaId = mediaItem.mediaId.trim()
        if (mediaId.isEmpty()) {
            continue
        }
        mediaIds += mediaId
        queueItems += mediaItem.toPlaybackQueueSnapshotItem(mediaId)
    }
    return PlaybackSessionQueueSnapshot(mediaIds = mediaIds, queueItems = queueItems)
}

private fun Timeline.mediaIdAt(index: Int): String? {
    if (index !in 0 until windowCount) {
        return null
    }
    return getWindow(index, Timeline.Window())
        .mediaItem
        .mediaId
        .trim()
        .takeIf(String::isNotEmpty)
}

private fun MediaItem.toPlaybackQueueSnapshotItem(mediaId: String): PlaybackQueueSnapshotItem {
    val metadata = mediaMetadata
    return PlaybackQueueSnapshotItem(
        mediaId = mediaId,
        stableKey = stableKey.orEmpty(),
        title = metadata.title?.toString()
            ?: metadata.displayTitle?.toString()
            ?: "",
        artist = metadata.artist?.toString()
            ?: metadata.subtitle?.toString()
            ?: "",
        album = metadata.albumTitle?.toString().orEmpty(),
        durationMs = metadata.durationMs?.coerceAtLeast(0L) ?: 0L,
        artworkUri = metadata.artworkUri?.toString().orEmpty(),
    )
}

private fun logPlaybackSessionStateFailure(stage: String, error: Throwable) {
    Log.w(
        PlaybackDiagnosticsTag,
        "Playback session state $stage failed type=${error.javaClass.simpleName} " +
            "message=${error.message}",
    )
}

/**
 * Rebuilds the saved queue without collapsing repeated local tracks. Media ids are preferred,
 * while the stable library key lets a moved or re-indexed file keep its queue position.
 */
internal fun restoreQueueItemOccurrences(
    queueItems: List<PlaybackQueueSnapshotItem>,
    candidates: List<MediaItem>,
): List<MediaItem> {
    if (queueItems.isEmpty() || candidates.isEmpty()) return emptyList()
    val remaining = candidates.toMutableList()
    return queueItems.mapNotNull { snapshot ->
        val matchIndex = remaining.indexOfFirst { item ->
            item.matchesRestoredOccurrence(snapshot)
        }
        if (matchIndex >= 0) {
            remaining.removeAt(matchIndex)
        } else {
            candidates.firstOrNull { item -> item.matchesRestoredOccurrence(snapshot) }
        }
    }
}

internal fun restoredQueueIndex(
    snapshot: PlaybackSessionSnapshot,
    items: List<MediaItem>,
): Int {
    if (items.isEmpty()) return 0
    val savedQueueItems = snapshot.queue.queueItems
    val savedIndex = snapshot.progress.currentIndex
    val savedQueueItem = savedQueueItems.getOrNull(savedIndex)
    if (savedQueueItem != null) {
        val occurrenceOrdinal = savedQueueItems
            .take(savedIndex + 1)
            .count { candidate -> candidate.matchesRestoredOccurrence(savedQueueItem) } - 1
        if (occurrenceOrdinal >= 0) {
            items.asSequence()
                .withIndex()
                .filter { (_, item) -> item.matchesRestoredOccurrence(savedQueueItem) }
                .drop(occurrenceOrdinal)
                .firstOrNull()
                ?.index
                ?.let { restoredIndex -> return restoredIndex }
        }
    }
    val clampedIndex = savedIndex.coerceIn(items.indices)
    val currentMediaId = snapshot.progress.currentMediaId
    if (currentMediaId == null || items[clampedIndex].mediaId == currentMediaId) {
        return clampedIndex
    }
    return items.indexOfFirst { item -> item.mediaId == currentMediaId }
        .takeIf { index -> index >= 0 }
        ?: clampedIndex
}

private fun PlaybackQueueSnapshotItem.matchesRestoredOccurrence(
    other: PlaybackQueueSnapshotItem,
): Boolean {
    val sameStableKey = stableKey.isNotBlank() && stableKey == other.stableKey
    return mediaId == other.mediaId || sameStableKey
}

private fun MediaItem.matchesRestoredOccurrence(
    snapshot: PlaybackQueueSnapshotItem,
): Boolean {
    val sameStableKey = snapshot.stableKey.isNotBlank() && stableKey == snapshot.stableKey
    return mediaId == snapshot.mediaId || sameStableKey
}

private fun Int.sanitizedRepeatMode(): Int {
    return when (this) {
        Player.REPEAT_MODE_OFF,
        Player.REPEAT_MODE_ONE,
        Player.REPEAT_MODE_ALL -> this
        else -> Player.REPEAT_MODE_OFF
    }
}

private fun Player.restoreQueuePaused(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
) {
    pause()
    setMediaItems(mediaItems, startIndex, startPositionMs)
    prepare()
}

internal fun List<MediaItem>.filterRestorablePlaybackItems(): List<MediaItem> {
    return filter { item ->
        isRestorablePlaybackItemState(
            hasPlaybackUri = item.localConfiguration?.uri != null,
        )
    }
}

internal fun isRestorablePlaybackItemState(hasPlaybackUri: Boolean): Boolean {
    return hasPlaybackUri
}
