package com.smartisan.music.ui.shell

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.smartisan.music.data.settings.ArtistSettings
import com.smartisan.music.ui.album.AlbumSummary
import com.smartisan.music.ui.album.buildAlbumSummaries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 专辑多选删除的确认通路：确认弹层 → 解析选中专辑的全部曲目 mediaId → 发起系统授权删除。
 *
 * 这条接线原先整个写在 `AlbumDeleteConfirmOverlay` 的 `onConfirm` 闭包里，把它改成空实现
 * （或传空 ids）不会有任何测试报警：[buildAlbumSummaries] / 曲目解析只测了纯逻辑，
 * [MusicShellViewModel] 只测了弹窗状态机，中间这段没人守。现在由 [requestAlbumDelete] 承担，
 * 这里用真实 ViewModel + 记录式假删除入口逐条钉住。
 *
 * 覆盖边界：
 * - 只覆盖到「发起删除」这一层：`ShellActions.requestSystemDeleteMediaIds` 之后的 MediaStore 授权弹窗、
 *   真正删除与曲库刷新由设备端路径覆盖，单测里只记录收到的 ids；
 * - 曲库快照是手写的 [MediaItem]（不碰 Room / MediaStore），专辑分组沿用与列表页相同的
 *   [buildAlbumSummaries]，所以「选中的专辑 id」在测试里按用户可见的标题 / 艺术家反查，与专辑页所见一致；
 * - 取消与空选择断言的是「一次都不发起」。
 */
class AlbumDeleteRequestTest {

    @Test
    fun confirmRequestsEveryTrackOfEverySelectedAlbumExactlyOnce() {
        val viewModel = MusicShellViewModel()
        val snapshot =
            listOf(
                track("alpha-1", album = "Alpha", trackNumber = 1),
                track("alpha-2", album = "Alpha", trackNumber = 2),
                track("beta-1", album = "Beta", trackNumber = 1),
                track("gamma-1", album = "Gamma", trackNumber = 1),
            )
        selectAlbums(viewModel, snapshot, "Alpha", "Beta")
        val requested = mutableListOf<Set<String>>()

        requestAlbumDelete(
            viewModel = viewModel,
            mediaItems = snapshot,
            hiddenMediaIds = emptySet(),
            unknownAlbumTitle = "未知专辑",
            multipleArtistsTitle = "多位艺术家",
            artistSettings = ArtistSettings(),
            requestSystemDeleteMediaIds = { mediaIds -> requested += mediaIds },
        )

        assertEquals(
            "确认后应恰好发起一次系统删除，ids 是所选两张专辑在屏幕上显示的全部曲目",
            listOf(setOf("alpha-1", "alpha-2", "beta-1")),
            requested,
        )
        assertFalse("确认后弹层要收掉", viewModel.showAlbumDeleteConfirm)
        assertFalse("确认后退出多选态", viewModel.albumEditMode)
        assertTrue("确认后清空选择", viewModel.selectedAlbumIds.isEmpty())
        assertTrue("确认后待确认的专辑 id 也要清空", viewModel.pendingAlbumDeleteAlbumIds.isEmpty())
    }

    @Test
    fun hiddenTracksAreNeverRequestedForDeletion() {
        val viewModel = MusicShellViewModel()
        val snapshot =
            listOf(
                track("alpha-1", album = "Alpha", trackNumber = 1),
                track("alpha-2", album = "Alpha", trackNumber = 2),
            )
        selectAlbums(viewModel, snapshot, "Alpha")
        val requested = mutableListOf<Set<String>>()

        requestAlbumDelete(
            viewModel = viewModel,
            mediaItems = snapshot,
            hiddenMediaIds = setOf("alpha-1"),
            unknownAlbumTitle = "未知专辑",
            multipleArtistsTitle = "多位艺术家",
            artistSettings = ArtistSettings(),
            requestSystemDeleteMediaIds = { mediaIds -> requested += mediaIds },
        )

        assertEquals(
            "已从曲库隐藏的曲目不该被删——否则「选中的专辑」和屏幕上看到的不是同一批曲目",
            listOf(setOf("alpha-2")),
            requested,
        )
    }

    @Test
    fun duplicateTracksAcrossSelectedAlbumsAreRequestedOnce() {
        val viewModel = MusicShellViewModel()
        // 同一首曲子在曲库里出现两次且专辑字段不同（扫描重复 / 元数据漂移），两张专辑都被选中时
        // 它只该出现在一次删除请求里。
        val snapshot =
            listOf(
                track("dup-1", album = "Alpha", trackNumber = 1),
                track("dup-1", album = "Beta", trackNumber = 1),
                track("beta-2", album = "Beta", trackNumber = 2),
            )
        selectAlbums(viewModel, snapshot, "Alpha", "Beta")
        val requested = mutableListOf<Set<String>>()

        requestAlbumDelete(
            viewModel = viewModel,
            mediaItems = snapshot,
            hiddenMediaIds = emptySet(),
            unknownAlbumTitle = "未知专辑",
            multipleArtistsTitle = "多位艺术家",
            artistSettings = ArtistSettings(),
            requestSystemDeleteMediaIds = { mediaIds -> requested += mediaIds },
        )

        assertEquals(
            "跨专辑合并后应去重，且仍是一次请求",
            listOf(setOf("dup-1", "beta-2")),
            requested,
        )
    }

    @Test
    fun albumsSharingATitleWithDifferentArtistsAreDeletedSeparately() {
        val viewModel = MusicShellViewModel()
        val snapshot =
            listOf(
                track("hits-a", album = "Greatest Hits", artist = "Singer A", albumArtist = "Singer A"),
                track("hits-b", album = "Greatest Hits", artist = "Singer B", albumArtist = "Singer B"),
            )
        val summaries = albumSummaries(snapshot)
        viewModel.enterAlbumEditMode()
        viewModel.selectAlbumRow(summaries.single { it.artist == "Singer B" }.id, true)
        viewModel.requestDeleteSelectedAlbums()
        val requested = mutableListOf<Set<String>>()

        requestAlbumDelete(
            viewModel = viewModel,
            mediaItems = snapshot,
            hiddenMediaIds = emptySet(),
            unknownAlbumTitle = "未知专辑",
            multipleArtistsTitle = "多位艺术家",
            artistSettings = ArtistSettings(),
            requestSystemDeleteMediaIds = { mediaIds -> requested += mediaIds },
        )

        assertEquals(
            "同名不同艺术家的两张专辑必须分开：选中的是 Singer B 的那张，就只删它的曲目",
            listOf(setOf("hits-b")),
            requested,
        )
    }

    @Test
    fun cancelledConfirmationNeverRequestsSystemDelete() {
        val viewModel = MusicShellViewModel()
        val snapshot = listOf(track("alpha-1", album = "Alpha", trackNumber = 1))
        selectAlbums(viewModel, snapshot, "Alpha")
        viewModel.dismissAlbumDeleteConfirmation()
        val requested = mutableListOf<Set<String>>()

        requestAlbumDelete(
            viewModel = viewModel,
            mediaItems = snapshot,
            hiddenMediaIds = emptySet(),
            unknownAlbumTitle = "未知专辑",
            multipleArtistsTitle = "多位艺术家",
            artistSettings = ArtistSettings(),
            requestSystemDeleteMediaIds = { mediaIds -> requested += mediaIds },
        )

        assertTrue("取消后待确认的专辑 id 已清空，不该再发起任何删除", requested.isEmpty())
    }

    @Test
    fun emptySelectionNeverRequestsSystemDelete() {
        val viewModel = MusicShellViewModel()
        val snapshot = listOf(track("alpha-1", album = "Alpha", trackNumber = 1))
        val requested = mutableListOf<Set<String>>()

        requestAlbumDelete(
            viewModel = viewModel,
            mediaItems = snapshot,
            hiddenMediaIds = emptySet(),
            unknownAlbumTitle = "未知专辑",
            multipleArtistsTitle = "多位艺术家",
            artistSettings = ArtistSettings(),
            requestSystemDeleteMediaIds = { mediaIds -> requested += mediaIds },
        )

        assertTrue("没有任何选中专辑时不该发起删除", requested.isEmpty())
        assertFalse("也不该弹确认框", viewModel.showAlbumDeleteConfirm)
    }

    // --------------------------------------------------------------------- 工具

    /** 进多选、按标题选中专辑、请求确认——与专辑页上用户的操作顺序一致。 */
    private fun selectAlbums(
        viewModel: MusicShellViewModel,
        mediaItems: List<MediaItem>,
        vararg titles: String,
    ) {
        val summaries = albumSummaries(mediaItems)
        viewModel.enterAlbumEditMode()
        titles.forEach { title ->
            viewModel.selectAlbumRow(summaries.single { it.title == title }.id, true)
        }
        viewModel.requestDeleteSelectedAlbums()
    }

    private fun albumSummaries(mediaItems: List<MediaItem>): List<AlbumSummary> =
        buildAlbumSummaries(
            mediaItems = mediaItems,
            unknownAlbumTitle = "未知专辑",
            multipleArtistsTitle = "多位艺术家",
        )

    private fun track(
        id: String,
        album: String,
        artist: String = "Artist",
        albumArtist: String? = null,
        trackNumber: Int? = null,
    ): MediaItem {
        val metadataBuilder =
            MediaMetadata.Builder()
                .setTitle("曲目 $id")
                .setAlbumTitle(album)
                .setArtist(artist)
        if (albumArtist != null) {
            metadataBuilder.setAlbumArtist(albumArtist)
        }
        if (trackNumber != null) {
            metadataBuilder.setTrackNumber(trackNumber)
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }
}
