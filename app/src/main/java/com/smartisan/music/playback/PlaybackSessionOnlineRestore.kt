package com.smartisan.music.playback

import androidx.media3.common.MediaItem
import com.smartisan.music.data.online.OnlineTrack
import com.smartisan.music.data.online.OnlineTrackIdentity
import com.smartisan.music.data.online.toMediaItem
import com.smartisan.music.data.online.withOnlinePlaybackPlaceholderUri

/**
 * 在线队列会话恢复：杀进程重启后，本地歌曲可从媒体库重新装载，
 * 在线歌曲则依据快照里保存的展示元数据（标题/歌手/封面等）直接重建 MediaItem；
 * 快照缺字段时再经 Router 拉取详情，兜底使用快照里的有限信息。
 */
internal fun PlaybackQueueSnapshotItem.hasOnlineDisplayMetadata(): Boolean {
    return title.isNotBlank() &&
        artist.isNotBlank() &&
        (durationMs > 0L || artworkUri.isNotBlank())
}

internal fun PlaybackQueueSnapshotItem.toOnlineSnapshotMediaItem(
    identity: OnlineTrackIdentity,
): MediaItem {
    return toOnlineSnapshotTrack(identity)
        .toMediaItem()
        .withOnlinePlaybackPlaceholderUri()
}

internal fun PlaybackQueueSnapshotItem.toOnlineSnapshotTrack(
    identity: OnlineTrackIdentity,
): OnlineTrack {
    return OnlineTrack(
        source = identity.source,
        trackId = identity.trackId,
        title = title.takeIf(String::isNotBlank) ?: identity.trackId,
        artist = artist,
        album = album.takeIf(String::isNotBlank),
        durationMs = durationMs,
        artworkUrl = artworkUri.takeIf(String::isNotBlank),
    )
}
