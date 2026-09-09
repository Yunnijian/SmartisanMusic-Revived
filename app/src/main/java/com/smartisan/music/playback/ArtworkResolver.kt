package com.smartisan.music.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Size
import androidx.media3.common.MediaItem
import com.smartisan.music.data.online.onlineIdentityOrNull
import com.smartisan.music.platform.media.audioMediaItemUri
import com.smartisan.music.platform.media.loadMediaThumbnailCompat
import java.net.HttpURLConnection
import java.net.URL

internal data class ArtworkRequestKey(
    val mediaId: String?,
    val artworkUri: String?,
    val albumId: Long?,
    val mediaUri: String?,
    val artworkDataHash: Int?,
    val artworkDataSize: Int?,
)

internal fun MediaItem.artworkRequestKey(): ArtworkRequestKey {
    val artworkData = mediaMetadata.artworkData
    val onlineIdentity = onlineIdentityOrNull()
    return artworkRequestKeyState(
        mediaId = mediaId,
        artworkUri = mediaMetadata.artworkUri?.toString(),
        albumId = mediaMetadata.extras.albumId(),
        mediaUri = localConfiguration?.uri?.toString(),
        artworkData = artworkData,
        onlineSource = onlineIdentity?.source,
        onlineTrackId = onlineIdentity?.trackId,
    )
}

internal fun artworkRequestKeyState(
    mediaId: String?,
    artworkUri: String?,
    albumId: Long?,
    mediaUri: String?,
    artworkData: ByteArray?,
    onlineSource: String? = null,
    onlineTrackId: String? = null,
): ArtworkRequestKey {
    // 在线条目以 online:source:trackId 作为缓存主键，并排除会过期轮换的播放 URI。
    val onlineArtworkMediaId = if (!onlineSource.isNullOrBlank() && !onlineTrackId.isNullOrBlank()) {
        "online:$onlineSource:$onlineTrackId"
    } else {
        null
    }
    return ArtworkRequestKey(
        mediaId = onlineArtworkMediaId ?: mediaId,
        artworkUri = artworkUri,
        albumId = albumId,
        mediaUri = if (onlineArtworkMediaId == null) mediaUri else null,
        artworkDataHash = artworkData?.contentHashCode(),
        artworkDataSize = artworkData?.size,
    )
}

internal suspend fun loadArtworkBitmap(
    context: Context,
    mediaItem: MediaItem,
    size: Size,
): Bitmap? = NowPlayingArtworkRepository.load(context, mediaItem, size)

internal fun loadArtworkBitmapSync(
    context: Context,
    mediaItem: MediaItem,
    size: Size,
): Bitmap? {
    val metadata = mediaItem.mediaMetadata
    return decodeArtworkData(metadata.artworkData, size)
        ?: loadArtworkUriBitmap(context, metadata.artworkUri, size)
        ?: loadTrackArtworkBitmap(context, mediaItem.mediaId, size)
        ?: loadAlbumArtworkBitmap(context, metadata.extras, size)
        ?: loadMediaStoreAudioArtworkBitmap(context, mediaItem.mediaId, size)
        ?: loadMediaThumbnail(context, mediaItem.localConfiguration?.uri, size)
        ?: loadEmbeddedArtworkBitmap(context, mediaItem.localConfiguration?.uri, size)
}

internal fun loadArtworkUriBitmap(
    context: Context,
    uri: Uri?,
    size: Size,
): Bitmap? {
    uri ?: return null
    if (uri.isNetworkUri()) {
        return loadNetworkArtworkBitmap(uri, size)
    }
    return context.contentResolver.loadMediaThumbnailCompat(uri, size)?.scaledToFit(size)
        ?: runCatching {
        decodeStreamSampled(context, uri, size)?.scaledToFit(size)
    }.getOrNull()
}

internal fun decodeArtworkData(
    artworkData: ByteArray?,
    size: Size,
): Bitmap? {
    artworkData ?: return null
    return runCatching {
        decodeByteArraySampled(artworkData, size)?.scaledToFit(size)
    }.getOrNull()
}

private fun loadTrackArtworkBitmap(
    context: Context,
    mediaId: String,
    size: Size,
): Bitmap? {
    val numericMediaId = mediaId.toLongOrNull() ?: return null
    return loadArtworkUriBitmap(context, LocalAudioLibrary.trackArtworkUri(numericMediaId), size)
}

private fun loadAlbumArtworkBitmap(
    context: Context,
    extras: Bundle?,
    size: Size,
): Bitmap? {
    val albumId = extras.albumId() ?: return null
    return loadArtworkUriBitmap(context, LocalAudioLibrary.albumArtworkUri(albumId), size)
}

private fun loadMediaStoreAudioArtworkBitmap(
    context: Context,
    mediaId: String,
    size: Size,
): Bitmap? {
    val mediaUri = localAudioMediaUri(mediaId) ?: return null
    return loadMediaThumbnail(context, mediaUri, size)
        ?: loadEmbeddedArtworkBitmap(context, mediaUri, size)
}

internal fun localAudioMediaUri(mediaId: String): Uri? {
    val numericMediaId = mediaId.toLongOrNull() ?: return null
    return audioMediaItemUri(numericMediaId)
}

/**
 * 网络封面地址的同步兜底加载（MediaSession 直接携带 http 封面 URI 时使用）：
 * 走 HttpURLConnection 下载后按目标尺寸采样解码。常规路径优先走 Coil 异步加载。
 */
private fun loadNetworkArtworkBitmap(
    uri: Uri,
    size: Size,
): Bitmap? {
    return runCatching {
        val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = NetworkArtworkTimeoutMs
            readTimeout = NetworkArtworkTimeoutMs
            setRequestProperty("User-Agent", NetworkArtworkUserAgent)
        }
        try {
            if (connection.responseCode !in 200..299) {
                return@runCatching null
            }
            val bytes = connection.inputStream.use { stream -> stream.readBytes() }
            decodeByteArraySampled(bytes, size)?.scaledToFit(size)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

private fun loadMediaThumbnail(
    context: Context,
    mediaUri: Uri?,
    size: Size,
): Bitmap? {
    mediaUri ?: return null
    return context.contentResolver.loadMediaThumbnailCompat(mediaUri, size)?.scaledToFit(size)
}

internal fun loadEmbeddedArtworkBitmap(
    context: Context,
    mediaUri: Uri?,
    size: Size,
): Bitmap? {
    mediaUri ?: return null
    return runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, mediaUri)
            retriever.embeddedPicture?.let { bytes ->
                decodeByteArraySampled(bytes, size)?.scaledToFit(size)
            }
        } finally {
            retriever.release()
        }
    }.getOrNull()
}

private fun Bundle?.albumId(): Long? {
    return this
        ?.getLong(LocalAudioLibrary.AlbumIdExtraKey)
        ?.takeIf { it > 0L }
}

private fun decodeStreamSampled(
    context: Context,
    uri: Uri,
    size: Size,
): Bitmap? {
    val boundsOptions = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, boundsOptions)
    } ?: return null
    val sampleOptions = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(boundsOptions, size)
    }
    return context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, sampleOptions)
    }
}

private fun decodeByteArraySampled(
    bytes: ByteArray,
    size: Size,
): Bitmap? {
    val boundsOptions = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
    val sampleOptions = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(boundsOptions, size)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sampleOptions)
}

private fun calculateInSampleSize(
    options: BitmapFactory.Options,
    size: Size,
): Int {
    val rawHeight = options.outHeight
    val rawWidth = options.outWidth
    val requestedHeight = size.height.coerceAtLeast(1)
    val requestedWidth = size.width.coerceAtLeast(1)
    var inSampleSize = 1

    if (rawHeight > requestedHeight || rawWidth > requestedWidth) {
        val halfHeight = rawHeight / 2
        val halfWidth = rawWidth / 2
        while (
            halfHeight / inSampleSize >= requestedHeight &&
            halfWidth / inSampleSize >= requestedWidth
        ) {
            inSampleSize *= 2
        }
    }

    return inSampleSize.coerceAtLeast(1)
}

private fun Bitmap.scaledToFit(size: Size): Bitmap {
    val maxWidth = size.width.coerceAtLeast(1)
    val maxHeight = size.height.coerceAtLeast(1)
    if (width <= maxWidth && height <= maxHeight) {
        return this
    }
    val scale = minOf(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
    val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
}

private fun Uri.isNetworkUri(): Boolean {
    return scheme == "http" || scheme == "https"
}

private const val NetworkArtworkTimeoutMs = 12_000
private const val NetworkArtworkUserAgent =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
