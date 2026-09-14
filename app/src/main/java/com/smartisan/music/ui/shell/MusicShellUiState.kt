package com.smartisan.music.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import com.smartisan.music.isExternalAudioLaunchItem
import com.smartisan.music.ui.artist.ArtistTarget
import com.smartisan.music.ui.artist.parentTarget
import com.smartisan.music.ui.components.withSelection
import com.smartisan.music.ui.navigation.MusicDestination
import com.smartisan.music.ui.playlist.PlaylistNameDialogRequest
import com.smartisan.music.ui.search.SearchDrilldownTarget

/**
 * 主壳的临时界面状态：目的地栈、覆盖层可见性、编辑与选择态、待确认操作。
 *
 * 它是这些状态的唯一来源（原先是 `MusicAppShellContent` 里 24 个 `remember { mutableStateOf(...) }`，
 * 现在整体搬进一个由主壳 `remember {}` 持有的对象，键与创建时机都不变），
 * 页面层通过它读写、通过它发起状态收敛，不再靠一堆一次性 lambda 参数下传。
 *
 * 播放条快照与封面状态在 `PlaybackBarHost`，收藏与云端收敛在 `data/favorite`，
 * 这里不持有任何持久化状态，也不新增第二套导航状态源。
 */
@Stable
internal class MusicShellUiState {

    // ── 目的地栈 ──

    var currentDestination: MusicDestination by mutableStateOf(MusicDestination.Playlist)
    var presentedFromMore: Boolean by mutableStateOf(false)
    var playlistAddModeActive: Boolean by mutableStateOf(false)
    var moreSettingsPageActive: Boolean by mutableStateOf(false)
    var navigationEditorVisible: Boolean by mutableStateOf(false)

    /** 从「更多」进入子页。 */
    fun selectOverflow(destination: MusicDestination) {
        presentedFromMore = true
        currentDestination = destination
    }

    /** 页面栈当前真正展示的目的地：子页态下「更多」是落点。 */
    val stackDestination: MusicDestination
        get() = if (presentedFromMore) MusicDestination.More else currentDestination

    /** 底栏切一级 tab：回到非「更多」子页态。 */
    fun selectTab(destination: MusicDestination) {
        presentedFromMore = false
        currentDestination = destination
    }

    /** 子页退回「更多」。 */
    fun returnToMore() {
        presentedFromMore = false
        currentDestination = MusicDestination.More
    }

    fun updatePlaylistAddModeActive(active: Boolean) {
        playlistAddModeActive = active
    }

    fun updateMoreSettingsPageActive(active: Boolean) {
        moreSettingsPageActive = active
    }

    /** 加歌模式不允许编辑导航，与迁移前的判断一致。 */
    fun showNavigationEditor() {
        if (!playlistAddModeActive) {
            navigationEditorVisible = true
        }
    }

    fun hideNavigationEditor() {
        navigationEditorVisible = false
    }

    // ── 整页覆盖层 ──

    var playbackVisible: Boolean by mutableStateOf(false)
    var searchVisible: Boolean by mutableStateOf(false)
    var searchQuery: String by mutableStateOf("")
    var searchDrilldownTarget: SearchDrilldownTarget? by mutableStateOf(null)
    /** 云音乐页内搜索的打开请求计数：标题栏搜索按钮在云页时递增，由云页宿主消费。 */
    var cloudSearchOpenRequest: Int by mutableStateOf(0)
        private set

    fun showPlaybackOverlay() {
        playbackVisible = true
    }

    fun hidePlaybackOverlay() {
        playbackVisible = false
    }

    /** 标题栏搜索：云音乐页走页内在线搜索，其余目的地走本地搜索覆盖层。 */
    fun openSearch() {
        if (currentDestination == MusicDestination.Cloud) {
            cloudSearchOpenRequest += 1
        } else {
            searchQuery = ""
            searchDrilldownTarget = null
            searchVisible = true
        }
    }

    /** 云页宿主已消费打开请求，清零等待下一次。 */
    fun consumeCloudSearchOpenRequest() {
        cloudSearchOpenRequest = 0
    }

    fun closeSearch() {
        searchVisible = false
        searchDrilldownTarget = null
    }

    fun updateSearchQuery(value: String) {
        searchQuery = value
    }

    fun clearArtistAndSearchDetail() {
        // 艺术家拆分规则变了，已有的下钻栈立即失效。
        selectedArtistTarget = null
        searchDrilldownTarget = null
    }

    // ── 编辑与选择态 ──

    var songsEditMode: Boolean by mutableStateOf(false)
    var selectedSongIds: Set<String> by mutableStateOf(emptySet())
    var albumEditMode: Boolean by mutableStateOf(false)
    var selectedAlbumIds: Set<String> by mutableStateOf(emptySet())

    fun enterSongsEditMode() {
        songsEditMode = true
        selectedSongIds = emptySet()
    }

    fun exitSongsEditMode() {
        songsEditMode = false
        selectedSongIds = emptySet()
        showSongDeleteConfirm = false
    }

    fun enterAlbumEditMode() {
        albumEditMode = true
        selectedAlbumIds = emptySet()
    }

    fun exitAlbumEditMode() {
        albumEditMode = false
        selectedAlbumIds = emptySet()
    }

    fun selectSongRow(mediaId: String, selected: Boolean) {
        selectedSongIds = selectedSongIds.withSelection(mediaId, selected)
    }

    fun selectAlbumRow(albumId: String, selected: Boolean) {
        selectedAlbumIds = selectedAlbumIds.withSelection(albumId, selected)
    }

    // ── 详情页 ──

    var selectedAlbumId: String? by mutableStateOf(null)
    var selectedAlbumTitle: String? by mutableStateOf(null)
    var selectedArtistTarget: ArtistTarget? by mutableStateOf(null)

    /** 打开专辑详情：先退出多选，与迁移前一致。 */
    fun openAlbumDetail(albumId: String, albumTitle: String) {
        albumEditMode = false
        selectedAlbumIds = emptySet()
        selectedAlbumId = albumId
        selectedAlbumTitle = albumTitle
    }

    fun closeAlbumDetail() {
        selectedAlbumId = null
        selectedAlbumTitle = null
    }

    /** 艺术家详情逐级回退。 */
    fun closeArtistDetail() {
        selectedArtistTarget = selectedArtistTarget?.parentTarget()
    }

    fun setArtistTarget(target: ArtistTarget?) {
        selectedArtistTarget = target
    }

    // ── 待确认操作 ──

    var showSongDeleteConfirm: Boolean by mutableStateOf(false)
    var pendingSongDeleteMediaIds: Set<String> by mutableStateOf(emptySet())
    var pendingSongDeleteDismissAction: (() -> Unit)? by mutableStateOf(null)
    var pendingPlaylistPickerMediaItems: List<MediaItem>? by mutableStateOf(null)
    var pendingTrackActionItem: MediaItem? by mutableStateOf(null)
    var pendingTrackActionSource: TrackActionSource by mutableStateOf(TrackActionSource.Library)
    var playbackPlaylistCreateRequest: PlaylistNameDialogRequest.Create? by mutableStateOf(null)
    var ratingOverrides: Map<String, Int> by mutableStateOf(emptyMap())

    /** 歌单选择面板是否持有返回键：新建歌单弹窗接管时不算，与迁移前的表达式一致。 */
    val playlistPickerVisible: Boolean
        get() =
            pendingPlaylistPickerMediaItems != null && playbackPlaylistCreateRequest == null

    fun showTrackActions(item: MediaItem, source: TrackActionSource) {
        if (item.mediaId.isBlank()) {
            return
        }
        pendingTrackActionItem = item
        pendingTrackActionSource = source
    }

    fun dismissTrackActions() {
        pendingTrackActionItem = null
    }

    /** 外部音频入口进来的项没有稳定 mediaId，不进歌单。 */
    fun requestPlaylistPicker(items: List<MediaItem>) {
        val candidates =
            items.filter { item ->
                item.mediaId.isNotBlank() && !item.isExternalAudioLaunchItem()
            }
        if (candidates.isNotEmpty()) {
            pendingPlaylistPickerMediaItems = candidates
        }
    }

    fun dismissPlaylistPicker() {
        pendingPlaylistPickerMediaItems = null
    }

    fun openPlaylistCreateRequest(request: PlaylistNameDialogRequest.Create) {
        playbackPlaylistCreateRequest = request
    }

    fun dismissPlaylistCreateRequest() {
        playbackPlaylistCreateRequest = null
    }

    /** 新建歌单成功：弹窗与选择面板一起收掉，顺序与迁移前一致。 */
    fun finishPlaylistCreate() {
        playbackPlaylistCreateRequest = null
        pendingPlaylistPickerMediaItems = null
    }

    fun requestSongDeleteConfirmation(
        mediaIds: Set<String>,
        onDismiss: (() -> Unit)? = null,
    ) {
        if (mediaIds.isEmpty()) {
            return
        }
        pendingSongDeleteMediaIds = mediaIds
        pendingSongDeleteDismissAction = onDismiss
        showSongDeleteConfirm = true
    }

    fun requestDeleteSelectedSongs() {
        if (selectedSongIds.isNotEmpty()) {
            requestSongDeleteConfirmation(selectedSongIds)
        }
    }

    fun dismissSongDeleteConfirmation() {
        val dismissAction = pendingSongDeleteDismissAction
        showSongDeleteConfirm = false
        pendingSongDeleteMediaIds = emptySet()
        pendingSongDeleteDismissAction = null
        dismissAction?.invoke()
    }

    /** 确认删除：先清界面状态，再走系统授权删除，最后补执行来源页的收尾动作。 */
    fun confirmSongDelete(onDeleted: (Set<String>) -> Unit) {
        val mediaIds = pendingSongDeleteMediaIds
        val dismissAction = pendingSongDeleteDismissAction
        if (mediaIds.isEmpty()) {
            dismissSongDeleteConfirmation()
            return
        }
        showSongDeleteConfirm = false
        pendingSongDeleteMediaIds = emptySet()
        pendingSongDeleteDismissAction = null
        songsEditMode = false
        selectedSongIds = emptySet()
        onDeleted(mediaIds)
        dismissAction?.invoke()
    }

    fun addRatingOverride(mediaId: String, score: Int) {
        ratingOverrides = ratingOverrides + (mediaId to score.coerceIn(0, 5))
    }
}
