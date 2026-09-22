package com.smartisan.music.ui.shell

import androidx.media3.common.MediaItem
import com.smartisan.music.data.settings.ArtistSettings
import com.smartisan.music.ui.album.selectedAlbumMediaIds

/**
 * 专辑多选删除的确认通路：确认弹层 → 按同一分组规则解析出选中专辑的全部曲目 mediaId →
 * 交给系统授权删除。
 *
 * 从 [MusicShellChrome] 的 Composable 闭包里提出来，是为了让这条通路在 JVM 单测里可验证：
 * 原先「确认 → 解析 → 真正发起删除」全写在 `AlbumDeleteConfirmOverlay` 的 `onConfirm` 里，
 * 把回调改成空实现（或传空 ids）不会有任何测试报警——[selectedAlbumMediaIds] 只测了纯解析，
 * [MusicShellViewModel] 只测了弹窗状态机，中间这段接线没人守。
 *
 * 输入是主壳手里的曲库快照与选择态，输出只有一次 [requestSystemDeleteMediaIds]：
 * Composable 只负责把曲库快照、隐藏曲目、艺术家设置与真实删除入口透传进来。
 */
internal fun requestAlbumDelete(
    viewModel: MusicShellViewModel,
    mediaItems: List<MediaItem>,
    hiddenMediaIds: Set<String>,
    unknownAlbumTitle: String,
    multipleArtistsTitle: String,
    artistSettings: ArtistSettings,
    requestSystemDeleteMediaIds: (Set<String>) -> Unit,
) {
    viewModel.confirmAlbumDelete { albumIds ->
        requestSystemDeleteMediaIds(
            selectedAlbumMediaIds(
                mediaItems = mediaItems,
                hiddenMediaIds = hiddenMediaIds,
                albumIds = albumIds,
                unknownAlbumTitle = unknownAlbumTitle,
                multipleArtistsTitle = multipleArtistsTitle,
                artistSettings = artistSettings,
            ),
        )
    }
}
