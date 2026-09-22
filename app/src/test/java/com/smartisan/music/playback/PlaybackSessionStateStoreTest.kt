package com.smartisan.music.playback

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 队列与进度拆成两个文件后，盘上格式（键名 + 队列条目编码）仍是老样子，
 * 所以老版本写下的单文件状态必须原样读得出来。这里按字面键名构造 preferences 把格式钉住：
 * 改键名或改编码会在这里失败，而不是等用户升级后才发现队列读不回来。
 */
class PlaybackSessionStateStoreTest {

    @Test
    fun legacyQueuePreferencesDecodeStableKeysAndMetadata() {
        val preferences = preferencesOf(
            stringPreferencesKey("media_ids") to "42\n43",
            stringPreferencesKey("queue_items") to
                """
                [
                  {"mediaId":"42","stableKey":"primary:Music/a.flac","title":"A"},
                  {"mediaId":"43","stableKey":"primary:Music/b.flac","title":"B"}
                ]
                """.trimIndent(),
        )

        val queue = preferences.toPlaybackSessionQueueSnapshot()

        assertEquals(listOf("42", "43"), queue.mediaIds)
        assertEquals(
            listOf(
                PlaybackQueueSnapshotItem(
                    mediaId = "42",
                    stableKey = "primary:Music/a.flac",
                    title = "A",
                ),
                PlaybackQueueSnapshotItem(
                    mediaId = "43",
                    stableKey = "primary:Music/b.flac",
                    title = "B",
                ),
            ),
            queue.queueItems,
        )
    }

    @Test
    fun legacyVersionOneQueueItemsStillDecode() {
        val preferences = preferencesOf(
            stringPreferencesKey("media_ids") to "42",
            stringPreferencesKey("queue_items") to "42\tprimary:Music/a.flac",
        )

        assertEquals(
            listOf(PlaybackQueueSnapshotItem(mediaId = "42", stableKey = "primary:Music/a.flac")),
            preferences.toPlaybackSessionQueueSnapshot().queueItems,
        )
    }

    @Test
    fun queueItemsWithoutQueueItemsKeyFallsBackToMediaIds() {
        val preferences = preferencesOf(stringPreferencesKey("media_ids") to "42\n43")

        assertEquals(
            listOf(
                PlaybackQueueSnapshotItem(mediaId = "42"),
                PlaybackQueueSnapshotItem(mediaId = "43"),
            ),
            preferences.toPlaybackSessionQueueSnapshot().queueItems,
        )
    }

    @Test
    fun brokenQueueItemsDecodeAsEmptyQueueInsteadOfThrowing() {
        val preferences = preferencesOf(
            stringPreferencesKey("media_ids") to "42",
            stringPreferencesKey("queue_items") to "[{\"mediaId\":",
        )

        val queue = preferences.toPlaybackSessionQueueSnapshot()

        assertEquals(listOf("42"), queue.mediaIds)
        assertEquals(listOf(PlaybackQueueSnapshotItem(mediaId = "42")), queue.queueItems)
    }

    @Test
    fun preferencesWithoutProgressKeysReportMissingProgress() {
        val legacyQueuePreferences = preferencesOf(stringPreferencesKey("media_ids") to "42")

        assertNull(legacyQueuePreferences.toPlaybackSessionProgressSnapshot())
        assertTrue(emptyPreferences().toPlaybackSessionQueueSnapshot().mediaIds.isEmpty())
        assertEquals(
            PlaybackSessionProgressSnapshot(),
            resolvePlaybackSessionProgressSnapshot(
                progressPreferences = emptyPreferences(),
                legacyQueuePreferences = legacyQueuePreferences,
            ),
        )
    }

    @Test
    fun legacySingleFileProgressIsUsedUntilProgressFileHasValues() {
        val legacyQueuePreferences = preferencesOf(
            stringPreferencesKey("media_ids") to "42",
            stringPreferencesKey("current_media_id") to "42",
            intPreferencesKey("current_index") to 7,
            longPreferencesKey("position_ms") to 1_234L,
            intPreferencesKey("repeat_mode") to Player.REPEAT_MODE_ALL,
            booleanPreferencesKey("shuffle_mode_enabled") to true,
        )

        assertEquals(
            PlaybackSessionProgressSnapshot(
                currentMediaId = "42",
                currentIndex = 7,
                positionMs = 1_234L,
                repeatMode = Player.REPEAT_MODE_ALL,
                shuffleModeEnabled = true,
            ),
            resolvePlaybackSessionProgressSnapshot(
                progressPreferences = emptyPreferences(),
                legacyQueuePreferences = legacyQueuePreferences,
            ),
        )
    }

    @Test
    fun progressFileWinsOverLegacyProgressKeys() {
        val legacyQueuePreferences = preferencesOf(
            stringPreferencesKey("media_ids") to "42",
            stringPreferencesKey("current_media_id") to "42",
            intPreferencesKey("current_index") to 7,
            longPreferencesKey("position_ms") to 1_234L,
        )
        val progressPreferences = preferencesOf(
            intPreferencesKey("current_index") to 1,
            longPreferencesKey("position_ms") to 20L,
        )

        val progress = resolvePlaybackSessionProgressSnapshot(
            progressPreferences = progressPreferences,
            legacyQueuePreferences = legacyQueuePreferences,
        )

        assertEquals(1, progress.currentIndex)
        assertEquals(20L, progress.positionMs)
        assertNull(progress.currentMediaId)
        assertEquals(Player.REPEAT_MODE_OFF, progress.repeatMode)
    }
}
