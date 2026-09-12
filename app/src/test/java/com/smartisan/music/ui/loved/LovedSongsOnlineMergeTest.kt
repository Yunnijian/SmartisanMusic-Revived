package com.smartisan.music.ui.loved

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LovedSongsOnlineMergeTest {

    @Test
    fun mergeAppendsOnlineItemsAfterLocalOnes() {
        val merged = mergeLovedSongsMediaItems(
            localMediaItems = listOf(mediaItem("1001"), mediaItem("1002")),
            onlineLovedMediaItems = listOf(mediaItem("online:netease:2001")),
        )

        assertEquals(listOf("1001", "1002", "online:netease:2001"), merged.map(MediaItem::mediaId))
    }

    @Test
    fun mergeDeduplicatesByMediaIdKeepingLocalEntry() {
        val localItem = mediaItem("online:netease:2001", title = "Local Copy")
        val merged = mergeLovedSongsMediaItems(
            localMediaItems = listOf(localItem),
            onlineLovedMediaItems = listOf(
                mediaItem("online:netease:2001", title = "Remote Copy"),
                mediaItem("online:netease:2002"),
            ),
        )

        assertEquals(listOf("online:netease:2001", "online:netease:2002"), merged.map(MediaItem::mediaId))
        assertSame(localItem, merged.first())
    }

    @Test
    fun mergeReturnsLocalListUntouchedWhenNoOnlineItems() {
        val localItems = listOf(mediaItem("1001"))

        val merged = mergeLovedSongsMediaItems(
            localMediaItems = localItems,
            onlineLovedMediaItems = emptyList(),
        )

        assertSame(localItems, merged)
    }

    private fun mediaItem(
        id: String,
        title: String = id,
    ): MediaItem {
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setDisplayTitle(title)
                    .build(),
            )
            .build()
    }
}
