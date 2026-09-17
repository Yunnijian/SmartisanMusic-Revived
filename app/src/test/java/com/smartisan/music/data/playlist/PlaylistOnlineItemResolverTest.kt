package com.smartisan.music.data.playlist

import androidx.media3.common.MediaItem
import com.smartisan.music.data.online.OnlineTrackIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistOnlineItemResolverTest {

    @Test
    fun identitiesKeepOnlyOnlineEntriesInOrderWithoutDuplicates() {
        val identities = onlineIdentityInPlaylistIds(
            listOf(
                "1001",
                "online:netease:2002",
                "  1002  ",
                "online:netease:2001",
                "online:netease:2002",
                "",
                "online:",
                "online:netease:",
            ),
        )

        assertEquals(
            listOf(
                OnlineTrackIdentity(source = "netease", trackId = "2002"),
                OnlineTrackIdentity(source = "netease", trackId = "2001"),
            ),
            identities,
        )
    }

    @Test
    fun identitiesAreEmptyWhenPlaylistHoldsOnlyLocalSongs() {
        assertTrue(onlineIdentityInPlaylistIds(listOf("1001", "1002")).isEmpty())
        assertTrue(onlineIdentityInPlaylistIds(emptyList()).isEmpty())
    }

    @Test
    fun resolveSkipsOnlineFetchWhenNoOnlineEntries() = runBlocking {
        var fetchCalls = 0
        val resolver = PlaylistOnlineItemResolver(
            fetchMediaItems = { identities ->
                fetchCalls += 1
                identities.map(::mediaItem)
            },
        )

        val items = resolver.resolveOnlineMediaItems(listOf("1001", "1002"))

        assertTrue(items.isEmpty())
        assertEquals(0, fetchCalls)
    }

    @Test
    fun resolveReturnsOnlineItemsForStoredOnlineEntries() = runBlocking {
        var requested: List<OnlineTrackIdentity>? = null
        val resolver = PlaylistOnlineItemResolver(
            fetchMediaItems = { identities ->
                requested = identities
                identities.map(::mediaItem)
            },
        )

        val items = resolver.resolveOnlineMediaItems(
            listOf("1001", "online:netease:2002", "online:netease:2001"),
        )

        assertEquals(
            listOf("online:netease:2002", "online:netease:2001"),
            requested?.map { identity -> "online:${identity.source}:${identity.trackId}" },
        )
        assertEquals(listOf("online:netease:2002", "online:netease:2001"), items.map(MediaItem::mediaId))
    }

    private fun mediaItem(identity: OnlineTrackIdentity): MediaItem {
        return MediaItem.Builder()
            .setMediaId("online:${identity.source}:${identity.trackId}")
            .build()
    }
}
