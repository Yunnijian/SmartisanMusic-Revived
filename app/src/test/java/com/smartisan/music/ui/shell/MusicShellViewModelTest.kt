package com.smartisan.music.ui.shell

import androidx.media3.common.MediaItem
import com.smartisan.music.ui.navigation.MusicDestination
import com.smartisan.music.ui.playlist.PlaylistNameDialogRequest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主壳临时界面状态的迁移保护：这些收敛动作原先写在 `MusicAppShellContent` 的 lambda 里，
 * 现在收在 [MusicShellViewModel]，逐条断言写入顺序与副作用范围没有变。
 */
class MusicShellViewModelTest {

    @Test
    fun overflowLandingAndReturnKeepStackFlags() {
        val state = MusicShellViewModel()

        state.selectOverflow(MusicDestination.LovedSongs)

        assertTrue(state.presentedFromMore)
        assertEquals(MusicDestination.LovedSongs, state.currentDestination)
        // 子页态下底栏与页面栈的落点仍是「更多」，对应迁移前 `if (presentedFromMore) More else currentDestination`。
        assertEquals(MusicDestination.More, state.stackDestination)

        state.returnToMore()

        assertFalse(state.presentedFromMore)
        assertEquals(MusicDestination.More, state.currentDestination)
    }

    @Test
    fun tabSelectionClearsPresentedFromMore() {
        val state = MusicShellViewModel()
        state.selectOverflow(MusicDestination.Folder)

        state.selectTab(MusicDestination.Playlist)

        assertFalse(state.presentedFromMore)
        assertEquals(MusicDestination.Playlist, state.currentDestination)
    }

    @Test
    fun navigationEditorStaysClosedWhileAddModeActive() {
        val addMode = MusicShellViewModel().apply { playlistAddModeActive = true }
        addMode.showNavigationEditor()
        assertFalse(addMode.navigationEditorVisible)

        val normal = MusicShellViewModel()
        normal.showNavigationEditor()
        assertTrue(normal.navigationEditorVisible)

        normal.hideNavigationEditor()
        assertFalse(normal.navigationEditorVisible)
    }

    @Test
    fun openSearchResetsQueryAndDrilldown() {
        val state = MusicShellViewModel()
        state.updateSearchQuery("echo")

        state.openSearch()

        assertTrue(state.searchVisible)
        assertEquals("", state.searchQuery)
        assertNull(state.searchDrilldownTarget)

        state.closeSearch()
        assertFalse(state.searchVisible)
    }

    @Test
    fun albumDetailOpenClearsMultiSelectionAndCloseKeepsEditMode() {
        val state = MusicShellViewModel()
        state.enterAlbumEditMode()
        state.selectAlbumRow("1", true)

        state.openAlbumDetail("album-7", "Album Seven")

        assertFalse(state.albumEditMode)
        assertTrue(state.selectedAlbumIds.isEmpty())
        assertEquals("album-7", state.selectedAlbumId)
        assertEquals("Album Seven", state.selectedAlbumTitle)

        state.closeAlbumDetail()
        assertNull(state.selectedAlbumId)
        assertNull(state.selectedAlbumTitle)
        // 退出详情不会顺带清掉编辑态，与迁移前一致。
        assertFalse(state.albumEditMode)
    }

    @Test
    fun songDeleteConfirmationFlowRunsInOrder() {
        val state = MusicShellViewModel()
        state.enterSongsEditMode()
        state.selectSongRow("1001", true)
        val dismissed = AtomicInteger()
        state.requestSongDeleteConfirmation(setOf("1001")) { dismissed.incrementAndGet() }

        assertTrue(state.showSongDeleteConfirm)
        assertEquals(setOf("1001"), state.pendingSongDeleteMediaIds)

        val deleted = linkedSetOf<String>()
        state.confirmSongDelete { mediaIds -> deleted += mediaIds }

        assertEquals(setOf("1001"), deleted)
        assertFalse(state.showSongDeleteConfirm)
        assertFalse(state.songsEditMode)
        assertTrue(state.selectedSongIds.isEmpty())
        // 收尾动作只在真正确认后执行。
        assertEquals(1, dismissed.get())
    }

    @Test
    fun emptyDeleteRequestIsIgnoredAndDismissFallsBackToClose() {
        val state = MusicShellViewModel()

        state.requestSongDeleteConfirmation(emptySet())
        assertFalse(state.showSongDeleteConfirm)

        state.confirmSongDelete { throw AssertionError("空集合不该触发系统删除") }
        assertFalse(state.showSongDeleteConfirm)
    }

    @Test
    fun albumDeleteConfirmationFlowRunsInOrder() {
        val state = MusicShellViewModel()
        state.enterAlbumEditMode()
        state.selectAlbumRow("album:7", true)

        state.requestDeleteSelectedAlbums()

        assertTrue(state.showAlbumDeleteConfirm)
        assertEquals(setOf("album:7"), state.pendingAlbumDeleteAlbumIds)

        val deleted = linkedSetOf<String>()
        state.confirmAlbumDelete { albumIds -> deleted += albumIds }

        assertEquals(setOf("album:7"), deleted)
        assertFalse(state.showAlbumDeleteConfirm)
        assertFalse(state.albumEditMode)
        assertTrue(state.selectedAlbumIds.isEmpty())
    }

    @Test
    fun albumDeleteRequestIsIgnoredWithoutSelection() {
        val state = MusicShellViewModel()

        state.requestDeleteSelectedAlbums()
        assertFalse(state.showAlbumDeleteConfirm)

        state.confirmAlbumDelete { throw AssertionError("空集合不该触发系统删除") }
        assertFalse(state.showAlbumDeleteConfirm)
    }

    @Test
    fun exitingAlbumEditModeClosesPendingDeleteConfirmation() {
        val state = MusicShellViewModel()
        state.enterAlbumEditMode()
        state.selectAlbumRow("album:7", true)
        state.requestDeleteSelectedAlbums()

        state.exitAlbumEditMode()

        assertFalse(state.showAlbumDeleteConfirm)
        assertTrue(state.pendingAlbumDeleteAlbumIds.isEmpty())
    }

    @Test
    fun playlistPickerVisibilityYieldsToCreateDialog() {
        val state = MusicShellViewModel()
        assertFalse(state.playlistPickerVisible)

        state.requestPlaylistPicker(
            listOf(
                mediaItem("1001"),
                mediaItem("online:netease:2001"),
                mediaItem("external-audio-1"),
            )
        )
        assertTrue(state.playlistPickerVisible)
        // 外部音频入口的项没有稳定 mediaId，不进歌单候选。
        assertEquals(
            listOf("1001", "online:netease:2001"),
            state.pendingPlaylistPickerMediaItems?.map(MediaItem::mediaId),
        )

        state.openPlaylistCreateRequest(createRequest())
        assertFalse(state.playlistPickerVisible)

        state.finishPlaylistCreate()
        assertNull(state.playbackPlaylistCreateRequest)
        assertNull(state.pendingPlaylistPickerMediaItems)
    }

    @Test
    fun trackActionsRequireMediaIdAndKeepSource() {
        val state = MusicShellViewModel()

        state.showTrackActions(mediaItem(" "), TrackActionSource.Library)
        assertNull(state.pendingTrackActionItem)

        state.showTrackActions(mediaItem("1001"), TrackActionSource.Playlist)
        assertEquals(TrackActionSource.Playlist, state.pendingTrackActionSource)

        state.dismissTrackActions()
        assertNull(state.pendingTrackActionItem)
    }

    @Test
    fun ratingOverrideIsClampedAndAdditive() {
        val state = MusicShellViewModel()

        state.addRatingOverride("1001", 9)
        state.addRatingOverride("1002", -3)

        assertEquals(5, state.ratingOverrides["1001"])
        assertEquals(0, state.ratingOverrides["1002"])
    }

    private fun mediaItem(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    private fun createRequest(): PlaylistNameDialogRequest.Create =
        PlaylistNameDialogRequest.Create(initialName = "未命名歌单")
}
