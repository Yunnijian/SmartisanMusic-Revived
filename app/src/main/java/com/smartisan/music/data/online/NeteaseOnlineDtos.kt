package com.smartisan.music.data.online

internal data class OnlineTrack(
    val source: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artworkUrl: String?,
) {
    val mediaId: String = buildOnlineMediaId(source, trackId)
}

internal data class OnlinePlaybackUrl(
    val url: String,
    val mimeType: String?,
)

internal data class NeteaseAccountProfile(
    val userId: Long,
    val nickname: String,
    val avatarUrl: String?,
)

internal data class NeteasePlaylistSummary(
    val playlistId: String,
    val name: String,
    val trackCount: Int,
    val specialType: Int,
    val creatorUserId: Long? = null,
    val subscribed: Boolean = false,
) {
    val isLikedSongs: Boolean
        get() = specialType == 5

    fun isEditableBy(userId: Long): Boolean {
        return !isLikedSongs && !subscribed && creatorUserId == userId
    }
}

internal data class NeteasePlaylistDetail(
    val tracks: List<OnlineTrack>,
    val trackIds: List<String>,
    val trackCount: Int,
)

internal enum class NeteasePlaybackParseStatus {
    Success,
    Preview,
    RequiresLogin,
    Unavailable,
}

internal data class NeteasePlaybackParseResult(
    val status: NeteasePlaybackParseStatus,
    val playbackUrl: OnlinePlaybackUrl? = null,
)

internal enum class NeteaseAccountActionStatus {
    Success,
    RequiresLogin,
    Failed,
}

internal data class NeteaseAccountActionResult(
    val status: NeteaseAccountActionStatus,
    val code: Int? = null,
)

internal data class NeteaseLikedTrackIdsResult(
    val status: NeteaseAccountActionStatus,
    val trackIds: Set<String> = emptySet(),
    val code: Int? = null,
)

internal data class NeteaseDailyRecommendedTracksResult(
    val status: NeteaseAccountActionStatus,
    val tracks: List<OnlineTrack> = emptyList(),
    val code: Int? = null,
)

internal enum class NeteasePlaylistTrackOperation(val apiValue: String) {
    Add("add"),
    Remove("del"),
}

internal data class OnlineTrackIdentity(
    val source: String,
    val trackId: String,
)
