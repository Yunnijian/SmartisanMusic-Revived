package com.smartisan.music.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.Player
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private const val PlaybackSessionQueueStoreName = "playback_session_state"
private const val PlaybackSessionProgressStoreName = "playback_session_progress"
private const val MediaIdSeparator = "\n"
private const val QueueItemSeparator = "\n"
private const val QueueItemFieldSeparator = "\t"

private val Context.playbackSessionQueueDataStore by preferencesDataStore(
    name = PlaybackSessionQueueStoreName,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

private val Context.playbackSessionProgressDataStore by preferencesDataStore(
    name = PlaybackSessionProgressStoreName,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** 整队列快照：条目数与文本量都随队列规模增长，只在队列结构变化时写。 */
internal data class PlaybackSessionQueueSnapshot(
    val mediaIds: List<String> = emptyList(),
    val queueItems: List<PlaybackQueueSnapshotItem> = mediaIds.map { mediaId ->
        PlaybackQueueSnapshotItem(mediaId = mediaId)
    },
)

/** 轻量进度：播放中周期保存只写这几个键，与队列内容无关。 */
internal data class PlaybackSessionProgressSnapshot(
    val currentMediaId: String? = null,
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleModeEnabled: Boolean = false,
)

/** 一次恢复需要的全部状态：队列来自队列文件，进度来自进度文件（见 [PlaybackSessionStateStore]）。 */
internal data class PlaybackSessionSnapshot(
    val queue: PlaybackSessionQueueSnapshot = PlaybackSessionQueueSnapshot(),
    val progress: PlaybackSessionProgressSnapshot = PlaybackSessionProgressSnapshot(),
)

internal data class PlaybackQueueSnapshotItem(
    val mediaId: String,
    val stableKey: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val artworkUri: String = "",
)

/**
 * 播放会话状态的落盘。队列与进度分两个 preferences 文件存放，因为 DataStore 每次
 * `edit` 都会整份重写文件：两者同文件时，播放中每 15s 的周期保存都要把几千首的队列文本
 * 重新序列化、连 fsync 一起写一遍。
 *
 * - 队列文件 [PlaybackSessionQueueStoreName]：`media_ids` + `queue_items`，只在队列结构变化时写；
 * - 进度文件 [PlaybackSessionProgressStoreName]：positionMs/currentIndex 等几个小键，随播放周期写。
 *
 * 键名沿用老版本单文件时的写法，所以老文件（队列与进度存在同一个文件里）能被原样读出来。
 *
 * 两个 [DataStore] 由构造函数注入，生产走 [Context] 上的委托（[Context.playbackSessionQueueDataStore]
 * 等两个同名文件）；单测则在临时目录里建同样的两个真实文件，好断言「这一次落盘动了哪个文件」。
 */
internal class PlaybackSessionStateStore(
    private val queueDataStore: DataStore<Preferences>,
    private val progressDataStore: DataStore<Preferences>,
) {

    constructor(context: Context) : this(
        queueDataStore = context.playbackSessionQueueDataStore,
        progressDataStore = context.playbackSessionProgressDataStore,
    )

    /** 队列文件里的遗留进度键只在升级后的首次成功写进度时清一次，之后不再碰队列文件。 */
    private var legacyProgressKeysPurged = false

    /**
     * 读盘：两个文件各自缺失、被损坏处理器清空，或队列条目编码残缺，都只退化成默认值
     * （空队列 / 0 进度）。
     *
     * 文件本身读写失败（IOException）不在这里吞掉，交给调用方决定：`PlaybackSessionStateCoordinator`
     * 读不到就这一次不恢复也不落盘，免得拿空状态覆盖掉盘上的旧队列。
     *
     * 升级后的首次恢复时进度文件还是空的，此时回退读老文件里的进度键，续播位置不会丢一次。
     */
    suspend fun load(): PlaybackSessionSnapshot {
        val queuePreferences = queueDataStore.data.first()
        val progressPreferences = progressDataStore.data.first()
        return PlaybackSessionSnapshot(
            queue = queuePreferences.toPlaybackSessionQueueSnapshot(),
            progress = resolvePlaybackSessionProgressSnapshot(
                progressPreferences = progressPreferences,
                legacyQueuePreferences = queuePreferences,
            ),
        )
    }

    /**
     * 整队列落盘。队列先写、进度后写：两次写之间被杀最坏是「新队列 + 旧进度」，
     * 恢复时按 mediaId 重新定位（对不上就从 0 开始），不会串歌。
     */
    suspend fun saveQueueSnapshot(
        queue: PlaybackSessionQueueSnapshot,
        progress: PlaybackSessionProgressSnapshot,
    ) {
        queueDataStore.edit { preferences ->
            preferences[MediaIdsKey] = queue.mediaIds.encodeMediaIds()
            preferences[QueueItemsKey] = queue.queueItems.encodeQueueItemsForStore()
        }
        saveProgressSnapshot(progress)
    }

    /**
     * 仅进度落盘：常规情况下只写进度文件，不动队列文件。唯一例外是升级后的首次成功写入，
     * 顺带删掉队列文件里的遗留进度键（[purgeLegacyProgressKeysOnce]）。
     */
    suspend fun saveProgressSnapshot(progress: PlaybackSessionProgressSnapshot) {
        progressDataStore.edit { preferences ->
            progress.writeTo(preferences)
        }
        purgeLegacyProgressKeysOnce()
    }

    /**
     * 单文件布局时代进度键留在队列文件里；新进度文件写入成功后，这些键就成了陈旧回退源
     * （进度文件损坏或被清空时会读到升级前的位置）。首次成功写进度后删掉一次，之后不再碰队列文件。
     */
    private suspend fun purgeLegacyProgressKeysOnce() {
        if (legacyProgressKeysPurged) return
        val queuePreferences = queueDataStore.data.first()
        val hasLegacyKeys =
            queuePreferences.contains(CurrentMediaIdKey) ||
                queuePreferences.contains(CurrentIndexKey) ||
                queuePreferences.contains(PositionMsKey) ||
                queuePreferences.contains(RepeatModeKey) ||
                queuePreferences.contains(ShuffleModeEnabledKey)
        if (hasLegacyKeys) {
            queueDataStore.edit { preferences ->
                preferences.remove(CurrentMediaIdKey)
                preferences.remove(CurrentIndexKey)
                preferences.remove(PositionMsKey)
                preferences.remove(RepeatModeKey)
                preferences.remove(ShuffleModeEnabledKey)
            }
        }
        legacyProgressKeysPurged = true
    }
}

internal fun Preferences.toPlaybackSessionQueueSnapshot(): PlaybackSessionQueueSnapshot {
    val mediaIds = this[MediaIdsKey].orEmpty().decodeMediaIds()
    return PlaybackSessionQueueSnapshot(
        mediaIds = mediaIds,
        queueItems =
            this[QueueItemsKey]
                ?.decodeQueueItemsFromStore()
                ?.takeIf(List<PlaybackQueueSnapshotItem>::isNotEmpty)
                ?: mediaIds.map { mediaId ->
                    PlaybackQueueSnapshotItem(mediaId = mediaId)
                },
    )
}

/**
 * 读进度：进度文件优先，还没写过（刚升级、或文件被损坏处理器清空）时回退读老文件里同名的键，
 * 两处都没有才用默认值。键名在新旧文件里一致，所以两个文件共用这一个读取函数。
 */
internal fun resolvePlaybackSessionProgressSnapshot(
    progressPreferences: Preferences,
    legacyQueuePreferences: Preferences,
): PlaybackSessionProgressSnapshot {
    return progressPreferences.toPlaybackSessionProgressSnapshot()
        ?: legacyQueuePreferences.toPlaybackSessionProgressSnapshot()
        ?: PlaybackSessionProgressSnapshot()
}

/** 一个进度键都没有（而非「都是默认值」）时返回 null，交由调用方决定是否回退。 */
internal fun Preferences.toPlaybackSessionProgressSnapshot(): PlaybackSessionProgressSnapshot? {
    if (
        CurrentMediaIdKey !in this &&
        CurrentIndexKey !in this &&
        PositionMsKey !in this &&
        RepeatModeKey !in this &&
        ShuffleModeEnabledKey !in this
    ) {
        return null
    }
    return PlaybackSessionProgressSnapshot(
        currentMediaId = this[CurrentMediaIdKey]?.takeIf(String::isNotBlank),
        currentIndex = this[CurrentIndexKey] ?: 0,
        positionMs = this[PositionMsKey] ?: 0L,
        repeatMode = this[RepeatModeKey] ?: Player.REPEAT_MODE_OFF,
        shuffleModeEnabled = this[ShuffleModeEnabledKey] ?: false,
    )
}

private fun PlaybackSessionProgressSnapshot.writeTo(preferences: MutablePreferences) {
    val savedMediaId = currentMediaId
    if (savedMediaId == null) {
        preferences.remove(CurrentMediaIdKey)
    } else {
        preferences[CurrentMediaIdKey] = savedMediaId
    }
    preferences[CurrentIndexKey] = currentIndex
    preferences[PositionMsKey] = positionMs.coerceAtLeast(0L)
    preferences[RepeatModeKey] = repeatMode
    preferences[ShuffleModeEnabledKey] = shuffleModeEnabled
}

private fun List<String>.encodeMediaIds(): String {
    return asSequence().map(String::trim).filter(String::isNotEmpty).joinToString(MediaIdSeparator)
}

private fun String.decodeMediaIds(): List<String> {
    return lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
}

internal fun List<PlaybackQueueSnapshotItem>.encodeQueueItemsForStore(): String {
    val array = JSONArray()
    asSequence()
        .filter { item -> item.mediaId.isNotBlank() }
        .forEach { item ->
            array.put(
                JSONObject()
                    .put(QueueItemMediaIdKey, item.mediaId.trim())
                    .put(QueueItemStableKeyKey, item.stableKey.trim())
                    .put(QueueItemTitleKey, item.title.trim())
                    .put(QueueItemArtistKey, item.artist.trim())
                    .put(QueueItemAlbumKey, item.album.trim())
                    .put(QueueItemDurationMsKey, item.durationMs.coerceAtLeast(0L))
                    .put(QueueItemArtworkUriKey, item.artworkUri.trim()),
            )
        }
    return array.toString()
}

internal fun String.decodeQueueItemsFromStore(): List<PlaybackQueueSnapshotItem> {
    val rawValue = trim()
    if (rawValue.isEmpty()) {
        return emptyList()
    }
    if (rawValue.startsWith("[")) {
        return decodeJsonQueueItems(rawValue)
    }
    return decodeVersionOneQueueItems()
}

private fun String.decodeVersionOneQueueItems(): List<PlaybackQueueSnapshotItem> {
    return lineSequence()
        .mapNotNull { line ->
            val parts = line.split(QueueItemFieldSeparator, limit = 2)
            val mediaId = parts.getOrNull(0)?.trim().orEmpty()
            if (mediaId.isBlank()) {
                null
            } else {
                PlaybackQueueSnapshotItem(
                    mediaId = mediaId,
                    stableKey = parts.getOrNull(1)?.trim().orEmpty(),
                )
            }
        }
        .toList()
}

private fun decodeJsonQueueItems(rawValue: String): List<PlaybackQueueSnapshotItem> {
    val array = runCatching { JSONArray(rawValue) }.getOrNull() ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val root = array.optJSONObject(index) ?: continue
            val mediaId = root.optString(QueueItemMediaIdKey).trim()
            if (mediaId.isBlank()) {
                continue
            }
            add(
                PlaybackQueueSnapshotItem(
                    mediaId = mediaId,
                    stableKey = root.optString(QueueItemStableKeyKey).trim(),
                    title = root.optString(QueueItemTitleKey).trim(),
                    artist = root.optString(QueueItemArtistKey).trim(),
                    album = root.optString(QueueItemAlbumKey).trim(),
                    durationMs = root.optLong(QueueItemDurationMsKey, 0L).coerceAtLeast(0L),
                    artworkUri = root.optString(QueueItemArtworkUriKey).trim(),
                )
            )
        }
    }
}

private val MediaIdsKey = stringPreferencesKey("media_ids")
private val QueueItemsKey = stringPreferencesKey("queue_items")
private val CurrentMediaIdKey = stringPreferencesKey("current_media_id")
private val CurrentIndexKey = intPreferencesKey("current_index")
private val PositionMsKey = longPreferencesKey("position_ms")
private val RepeatModeKey = intPreferencesKey("repeat_mode")
private val ShuffleModeEnabledKey = booleanPreferencesKey("shuffle_mode_enabled")

private const val QueueItemMediaIdKey = "mediaId"
private const val QueueItemStableKeyKey = "stableKey"
private const val QueueItemTitleKey = "title"
private const val QueueItemArtistKey = "artist"
private const val QueueItemAlbumKey = "album"
private const val QueueItemDurationMsKey = "durationMs"
private const val QueueItemArtworkUriKey = "artworkUri"
