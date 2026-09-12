package com.smartisan.music.data.favorite

import androidx.media3.common.MediaItem
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LovedSongsCloudSyncTest {

    // ── 差集算法（自 ui/loved/LovedSongsOnlineMerge.kt 下沉） ──

    @Test
    fun missingIdsKeepsOnlyCloudTracksAbsentFromLocalFavorites() {
        val missing = missingOnlineLikedMediaIds(
            cloudTrackIds = setOf("2001", "2002", "2003"),
            localFavoriteMediaIds = setOf(
                "online:netease:2001",
                "1002",
                "local-file",
            ),
        )

        assertEquals(setOf("online:netease:2002", "online:netease:2003"), missing)
    }

    @Test
    fun missingIdsIgnoresLocalFavoritesFromOtherSources() {
        val missing = missingOnlineLikedMediaIds(
            cloudTrackIds = setOf("2001"),
            localFavoriteMediaIds = setOf("online:qqmusic:2001"),
        )

        assertEquals(setOf("online:netease:2001"), missing)
    }

    @Test
    fun missingIdsIsEmptyWhenCloudTrackIdsUnavailable() {
        assertEquals(
            emptySet<String>(),
            missingOnlineLikedMediaIds(
                cloudTrackIds = null,
                localFavoriteMediaIds = setOf("online:netease:2001"),
            ),
        )
        assertEquals(
            emptySet<String>(),
            missingOnlineLikedMediaIds(
                cloudTrackIds = emptySet(),
                localFavoriteMediaIds = emptySet(),
            ),
        )
    }

    @Test
    fun missingIdsOnlyConsidersLocalFavoritesOfTheRequestedSource() {
        val missing = missingOnlineLikedMediaIds(
            cloudTrackIds = setOf("2001"),
            localFavoriteMediaIds = setOf("online:netease:2001"),
            source = "qqmusic",
        )

        assertEquals(setOf("online:qqmusic:2001"), missing)
    }

    // ── 收敛策略 ──

    @Test
    fun inactivePageSkipsCloudEntirelyAndShowsNothingOnline() {
        val source = FakeCloudSource(loggedIn = true)
        val writer = RecordingFavoriteWriter()

        val result = runSync(source, pageActive = false, writer = writer)

        assertTrue(result.isEmpty())
        assertEquals(0, source.loginReads.get())
        assertEquals(0, source.trackIdReads.get())
        assertEquals(0, source.mediaItemReads.get())
        assertTrue(writer.writtenMediaIds.isEmpty())
    }

    @Test
    fun loggedOutDegradesToLocalOnly() {
        val source = FakeCloudSource(loggedIn = false)

        val result = runSync(source, pageActive = true)

        assertTrue(result.isEmpty())
        assertEquals(1, source.loginReads.get())
        assertEquals(0, source.trackIdReads.get())
        assertEquals(0, source.mediaItemReads.get())
    }

    @Test
    fun cloudLikesAreAddedOnceAndNeverRemoveLocalEntries() {
        val source = FakeCloudSource(
            loggedIn = true,
            trackIds = setOf("2001", "2002"),
            mediaItems = listOf(mediaItem("online:netease:2001")),
        )
        val writer = RecordingFavoriteWriter()

        val result = runSync(
            source = source,
            pageActive = true,
            localFavoriteMediaIds = setOf("online:netease:2001", "1002"),
            writer = writer,
        )

        assertEquals(listOf(setOf("online:netease:2002")), writer.writtenMediaIds)
        assertEquals(listOf("online:netease:2001"), result.map(MediaItem::mediaId))
        // 只补不删：本地已有的收藏与本地歌曲都不在写入集合里。
        assertFalse(writer.writtenMediaIds.first().contains("online:netease:2001"))
        assertFalse(writer.writtenMediaIds.first().contains("1002"))
    }

    @Test
    fun emptyDiffDoesNotTouchRoom() {
        val source = FakeCloudSource(
            loggedIn = true,
            trackIds = setOf("2001"),
            mediaItems = listOf(mediaItem("online:netease:2001")),
        )
        val writer = RecordingFavoriteWriter()

        runSync(
            source = source,
            pageActive = true,
            localFavoriteMediaIds = setOf("online:netease:2001"),
            writer = writer,
        )

        assertTrue(writer.writtenMediaIds.isEmpty())
    }

    @Test
    fun failedCloudFetchDegradesToLocalOnly() {
        // accountLikedTrackIds() 把失败与「确实没有内容」都收敛成 null，这里同样只退化为不补。
        val source = FakeCloudSource(loggedIn = true, trackIds = null)
        val writer = RecordingFavoriteWriter()

        val result = runSync(source, pageActive = true, writer = writer)

        assertTrue(source.trackIdReads.get() > 0)
        assertTrue(result.isEmpty())
        assertTrue(writer.writtenMediaIds.isEmpty())
    }

    @Test
    fun trackIdsAndMediaItemsComeFromSeparateFetches() {
        val items = listOf(mediaItem("online:netease:2001"), mediaItem("online:netease:2002"))
        val source = FakeCloudSource(
            loggedIn = true,
            trackIds = setOf("2001", "2002"),
            mediaItems = items,
        )

        val result = runSync(source, pageActive = true)

        assertEquals(1, source.trackIdReads.get())
        assertEquals(1, source.mediaItemReads.get())
        assertSame(items, result)
    }

    private fun runSync(
        source: CloudLovedSongsSource,
        pageActive: Boolean,
        localFavoriteMediaIds: Set<String> = emptySet(),
        writer: MissingFavoriteWriter = RecordingFavoriteWriter(),
    ): List<MediaItem> {
        val sync = LovedSongsCloudSync(source, writer)
        return runBlocking {
            sync.mediaItemsForLovedSongsPage(
                lovedSongsPageActive = pageActive,
                localFavoriteMediaIds = localFavoriteMediaIds,
            )
        }
    }

    private class FakeCloudSource(
        private val loggedIn: Boolean,
        private val trackIds: Set<String>? = emptySet(),
        private val mediaItems: List<MediaItem> = emptyList(),
    ) : CloudLovedSongsSource {

        val loginReads = AtomicInteger()
        val trackIdReads = AtomicInteger()
        val mediaItemReads = AtomicInteger()

        override fun isLoggedIn(): Boolean {
            loginReads.incrementAndGet()
            return loggedIn
        }

        override suspend fun likedTrackIds(): Set<String>? {
            trackIdReads.incrementAndGet()
            return trackIds
        }

        override suspend fun likedMediaItems(): List<MediaItem> {
            mediaItemReads.incrementAndGet()
            return mediaItems
        }
    }

    private class RecordingFavoriteWriter : MissingFavoriteWriter {
        val writtenMediaIds = mutableListOf<Set<String>>()

        override suspend fun addMissing(mediaIds: Set<String>) {
            writtenMediaIds += mediaIds
        }
    }

    private fun mediaItem(id: String): MediaItem {
        return MediaItem.Builder().setMediaId(id).build()
    }
}
