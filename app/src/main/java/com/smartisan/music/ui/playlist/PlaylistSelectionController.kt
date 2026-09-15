package com.smartisan.music.ui.playlist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smartisan.music.ui.components.withSelection

/**
 * 播放列表页的编辑态与选择态：根列表多选、详情多选、添加模式三套选择互不干扰，
 * 各自的进出场清理集中在这里，避免 UI 回调里散落状态改写。
 */
internal class PlaylistSelectionController {

    var rootEditMode by mutableStateOf(false)
        private set
    var selectedPlaylistIds by mutableStateOf(emptySet<String>())
        private set
    var detailEditMode by mutableStateOf(false)
        private set
    var selectedTrackIds by mutableStateOf(emptySet<String>())
        private set
    var addMode by mutableStateOf(false)
        private set
    var addModeTarget by mutableStateOf<PlaylistTarget?>(null)
        private set
    var addModeReturnsToRoot by mutableStateOf(false)
        private set
    var selectedAddSongIds by mutableStateOf(emptySet<String>())
        private set

    val addModeVisible: Boolean
        get() = addMode && addModeTarget != null

    fun enterRootEdit() {
        rootEditMode = true
        selectedPlaylistIds = emptySet()
    }

    fun exitRootEdit() {
        rootEditMode = false
        selectedPlaylistIds = emptySet()
    }

    fun togglePlaylistSelection(playlistId: String) {
        selectedPlaylistIds = selectedPlaylistIds.togglePlaylistSelection(playlistId)
    }

    fun setPlaylistSelected(playlistId: String, selected: Boolean) {
        selectedPlaylistIds = selectedPlaylistIds.withSelection(playlistId, selected)
    }

    fun changeDetailEditMode(enabled: Boolean) {
        detailEditMode = enabled
        selectedTrackIds = emptySet()
    }

    fun exitDetailEdit() {
        detailEditMode = false
        selectedTrackIds = emptySet()
    }

    fun toggleTrackSelection(mediaId: String) {
        selectedTrackIds = selectedTrackIds.togglePlaylistSelection(mediaId)
    }

    fun setTrackSelected(mediaId: String, selected: Boolean) {
        selectedTrackIds = selectedTrackIds.withSelection(mediaId, selected)
    }

    fun replaceSelectedTracks(mediaIds: Collection<String>) {
        selectedTrackIds = mediaIds.toSet()
    }

    /** 详情页返回：清空详情编辑态与添加模式（target 由调用方负责）。 */
    fun resetDetail() {
        detailEditMode = false
        selectedTrackIds = emptySet()
        addMode = false
        addModeTarget = null
        addModeReturnsToRoot = false
        selectedAddSongIds = emptySet()
    }

    /** 目标歌单已不存在时的清理（不含 selectedPlaylistIds）。 */
    fun resetMissingPlaylist() {
        detailEditMode = false
        addMode = false
        addModeTarget = null
        addModeReturnsToRoot = false
        selectedTrackIds = emptySet()
        selectedAddSongIds = emptySet()
    }

    /** 删除详情歌单后的清理：只复位编辑态与添加开关，保留添加模式的其它字段（与原实现一致）。 */
    fun resetAfterDetailPlaylistDelete() {
        detailEditMode = false
        addMode = false
        selectedTrackIds = emptySet()
    }

    fun beginAddMode(target: PlaylistTarget?, returnsToRoot: Boolean) {
        addModeTarget = target
        addModeReturnsToRoot = returnsToRoot
        addMode = true
        selectedAddSongIds = emptySet()
    }

    /** 关闭添加模式；返回是否需要把页面 target 清空（调用方据此回到根列表）。 */
    fun closeAddMode(): Boolean {
        addMode = false
        selectedAddSongIds = emptySet()
        val returnsToRoot = addModeReturnsToRoot
        addModeReturnsToRoot = false
        return returnsToRoot
    }

    fun setAddSongSelected(mediaId: String, selected: Boolean) {
        selectedAddSongIds = selectedAddSongIds.withSelection(mediaId, selected)
    }
}
