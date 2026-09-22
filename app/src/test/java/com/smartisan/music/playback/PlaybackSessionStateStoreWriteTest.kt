package com.smartisan.music.playback

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.Player
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 写侧的 store 行为：进度这一档落盘时到底碰没碰队列文件。真 DataStore 落在临时目录里，
 * 断言的是写次数、读次数与文件字节——「改了哪个文件」这件事只有在这一层才看得见。
 */
class PlaybackSessionStateStoreWriteTest {

    @Test
    fun firstProgressWriteRemovesLegacyProgressKeysFromQueueFile() = runBlocking {
        val fixture = PlaybackSessionWriteFixture()
        try {
            // 单文件时代留下的进度键还躺在队列文件里（老版本把进度和队列写同一个文件）。
            fixture.queueDataStore.edit { preferences ->
                preferences[stringPreferencesKey("media_ids")] = "42"
                preferences[stringPreferencesKey("current_media_id")] = "42"
                preferences[intPreferencesKey("current_index")] = 7
                preferences[longPreferencesKey("position_ms")] = 1_234L
                preferences[intPreferencesKey("repeat_mode")] = Player.REPEAT_MODE_ALL
                preferences[booleanPreferencesKey("shuffle_mode_enabled")] = true
            }
            // 清理之前：进度文件还是空的，读到的是队列文件里的老进度 —— 正是必须清掉的回退源。
            assertEquals(
                "清理前会回退读到队列文件里的老进度",
                7,
                fixture.stateStore.load().progress.currentIndex,
            )

            fixture.stateStore.saveProgressSnapshot(
                PlaybackSessionProgressSnapshot(
                    currentMediaId = "43",
                    currentIndex = 1,
                    positionMs = 20L,
                ),
            )

            val queuePreferences = fixture.queueDataStore.data.first()
            assertNoLegacyProgressKeys(queuePreferences)
            assertEquals(
                "清遗留键不该动队列本体",
                "42",
                queuePreferences[stringPreferencesKey("media_ids")],
            )
            val restored = fixture.stateStore.load()
            assertEquals(
                "清完之后读到的应该是进度文件里的新进度",
                PlaybackSessionProgressSnapshot(
                    currentMediaId = "43",
                    currentIndex = 1,
                    positionMs = 20L,
                ),
                restored.progress,
            )
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun steadyStateProgressWritesDoNotTouchQueueFile() = runBlocking {
        val fixture = PlaybackSessionWriteFixture()
        try {
            fixture.stateStore.saveQueueSnapshot(
                queue = queueSnapshotOf("42", "43"),
                progress = PlaybackSessionProgressSnapshot(),
            )
            val queueBytesBefore = fixture.queueFile.readBytes()
            val queueWritesBefore = fixture.queueDataStore.writeCount
            val queueReadsBefore = fixture.queueDataStore.readCount

            fixture.stateStore.saveProgressSnapshot(
                PlaybackSessionProgressSnapshot(currentIndex = 1, positionMs = 1_000L),
            )
            fixture.stateStore.saveProgressSnapshot(
                PlaybackSessionProgressSnapshot(currentIndex = 2, positionMs = 2_000L),
            )

            assertEquals(
                "队列没变时进度这一档不该重写队列文件",
                queueWritesBefore,
                fixture.queueDataStore.writeCount,
            )
            assertEquals(
                "遗留键只清一次：稳态下的进度写连队列文件都不该再读",
                queueReadsBefore,
                fixture.queueDataStore.readCount,
            )
            assertArrayEquals(
                "队列文件字节不该被进度写改动",
                queueBytesBefore,
                fixture.queueFile.readBytes(),
            )
            assertEquals(2_000L, fixture.savedProgress().positionMs)
        } finally {
            fixture.delete()
        }
    }

    private fun assertNoLegacyProgressKeys(preferences: Preferences) {
        val legacyKeys =
            listOf(
                stringPreferencesKey("current_media_id"),
                intPreferencesKey("current_index"),
                longPreferencesKey("position_ms"),
                intPreferencesKey("repeat_mode"),
                booleanPreferencesKey("shuffle_mode_enabled"),
            )
        legacyKeys.forEach { key ->
            assertFalse(
                "队列文件里不该再留着遗留进度键 $key：进度文件损坏时会回退读到升级前的陈旧位置",
                preferences.contains(key),
            )
        }
    }
}
