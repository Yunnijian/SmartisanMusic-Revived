package com.smartisan.music.playback

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.storage.StorageVolume
import android.provider.MediaStore
import com.smartisan.music.R
import androidx.annotation.RequiresApi
import com.smartisan.music.data.library.LibraryIndexEntity
import com.smartisan.music.platform.media.audioMediaCollectionUri
import com.smartisan.music.platform.text.HanLatinTransliterator
import java.io.File
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/**
 * MediaStore 侧的曲库读取与映射：游标查询、快照指纹、稳定键与元数据归一化。
 *
 * 从 [LocalAudioLibrary] 搬出的纯读取逻辑；索引落库与 diff 见 [LibraryIndexReconciler]。
 */

internal fun LocalAudioLibrary.queryAudioIndexesByIds(mediaIds: List<Long>): List<LibraryIndexEntity> {
    if (mediaIds.isEmpty()) {
        return emptyList()
    }
    val indexedAt = System.currentTimeMillis()
    return mediaIds
        .chunked(LocalAudioLibrary.MediaStoreIdSelectionChunkSize)
        .flatMap { ids ->
            queryAudioCursorRows(
                selection =
                    buildString {
                        append(audioSelection())
                        append(" AND ${MediaStore.Audio.Media._ID} IN (")
                        append(ids.joinToString(separator = ",") { "?" })
                        append(")")
                    },
                selectionArgs = ids.map(Long::toString).toTypedArray(),
                sortOrder = null,
            )
        }
        .map { row -> row.toLibraryIndexEntity(this, indexedAt) }
}

internal fun LocalAudioLibrary.queryAudioCursorRows(
    selection: String,
    selectionArgs: Array<String>?,
    sortOrder: String?,
): List<AudioCursorRow> {
    val rows = mutableListOf<AudioCursorRow>()
    val externalVolumeRoots =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            externalVolumeRoots()
        } else {
            emptySet()
        }
    try {
        context.contentResolver
            .query(
                audioCollection(),
                audioItemProjection(),
                selection,
                selectionArgs,
                sortOrder,
            )
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.toAudioCursorRow(this, externalVolumeRoots)?.let(rows::add)
                }
            }
    } catch (_: SecurityException) {
        return emptyList()
    }

    return rows
}

internal fun Cursor.toAudioCursorRow(
    library: LocalAudioLibrary,
    externalVolumeRoots: Set<LocalAudioLibrary.ExternalVolumeRoot>,
): AudioCursorRow? {
    val id = getLong(getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
    val storageLocation = audioStorageLocation(externalVolumeRoots)
    val displayName =
        getString(getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
            ?.trim()
            ?.takeIf(String::isNotEmpty) ?: return null
    val stableKey =
        stableAudioLibraryKey(
            storageLocation.volumeName,
            storageLocation.relativePath,
            displayName,
        ) ?: return null
    val durationMs = getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION))
    val mimeType =
        getString(getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))?.takeIf {
            it.isNotBlank()
        }

    return AudioCursorRow(
        mediaId = id.toString(),
        stableKey = stableKey,
        uri = ContentUris.withAppendedId(library.audioCollection(), id).toString(),
        title = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)),
        artist = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)),
        album = getString(getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)),
        albumArtist =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getString(getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST))
            } else {
                null
            },
        albumId =
            getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)).takeIf { it > 0L },
        durationMs = durationMs,
        track = getInt(getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)).takeIf { it > 0 },
        year = getInt(getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)).takeIf { it > 0 },
        volumeName = storageLocation.volumeName,
        relativePath = storageLocation.relativePath,
        displayName = displayName,
        mimeType = mimeType,
        dateAdded =
            getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)).takeIf {
                it > 0L
            },
        dateModified =
            getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)).takeIf {
                it > 0L
            },
        generationAdded =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.GENERATION_ADDED)).takeIf {
                    it > 0L
                }
            } else {
                null
            },
        generationModified =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getLong(getColumnIndexOrThrow(MediaStore.Audio.Media.GENERATION_MODIFIED))
                    .takeIf { it > 0L }
            } else {
                null
            },
    )
}

private fun Cursor.audioStorageLocation(
    externalVolumeRoots: Set<LocalAudioLibrary.ExternalVolumeRoot>,
): AudioStorageLocation {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return AudioStorageLocation(
            volumeName =
                getString(getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME))
                    ?.trim()
                    ?.takeIf(String::isNotEmpty) ?: StableKeyVolumeFallback,
            relativePath =
                normalizeLibraryRelativePath(
                    getString(getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
                ),
        )
    }

    val dataPath = getString(getColumnIndexOrThrow(MediaStore.MediaColumns.DATA))
    val audioFile = dataPath?.let(::File)
    val volumeRoot = audioFile?.let { file ->
        externalVolumeRoots.firstOrNull { candidate ->
            file.absolutePath == candidate.root.absolutePath ||
                file.absolutePath.startsWith("${candidate.root.absolutePath}${File.separator}")
        }
    }
    return AudioStorageLocation(
        volumeName = volumeRoot?.mediaStoreVolumeName ?: StableKeyVolumeFallback,
        relativePath =
            normalizeLibraryRelativePath(
                if (audioFile != null && volumeRoot != null) {
                    audioFile.relativePathFromVolumeRoot(volumeRoot.root)
                } else {
                    audioFile?.parentFile?.absolutePath
                }
            ),
    )
}

internal fun AudioCursorRow.toLibraryIndexEntity(
    library: LocalAudioLibrary,
    indexedAt: Long,
): LibraryIndexEntity {
    val normalizedTitle =
        title?.repairMetadataEncoding()?.takeIf { it.isNotBlank() }
            ?: library.context.getString(R.string.unknown_song_title)
    val normalizedArtist =
        artist?.repairMetadataEncoding()?.takeIf {
            it.isNotBlank() && it != MediaStore.UNKNOWN_STRING
        } ?: library.context.getString(R.string.unknown_artist)
    val normalizedAlbum = album?.repairMetadataEncoding()?.takeIf { it.isNotBlank() }
    val normalizedAlbumArtist =
        albumArtist?.repairMetadataEncoding()?.takeIf {
            it.isNotBlank() && it != MediaStore.UNKNOWN_STRING
        }
    val titleSortKey = LibraryTitleNormalizer.normalize(normalizedTitle)
    return LibraryIndexEntity(
        mediaId = mediaId,
        stableKey = stableKey,
        uri = uri,
        title = normalizedTitle,
        artist = normalizedArtist,
        album = normalizedAlbum,
        albumArtist = normalizedAlbumArtist,
        albumId = albumId,
        durationMs = durationMs,
        track = track,
        year = year,
        volumeName = volumeName,
        relativePath = relativePath,
        displayName = displayName,
        mimeType = mimeType,
        dateAdded = dateAdded,
        dateModified = dateModified,
        generationAdded = generationAdded,
        generationModified = generationModified,
        titleSortKey = titleSortKey,
        titleSection = titleSortKey.libraryTitleSection(),
        qualityBadge = resolveAudioQualityBadge(displayName, mimeType),
        indexedAt = indexedAt,
        valid = true,
    )
}

private fun resolveAudioQualityBadge(
    displayName: String?,
    mimeType: String?,
): String? {
    val extension =
        displayName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
    val normalizedMimeType = mimeType?.lowercase(Locale.ROOT).orEmpty()

    return when {
        extension == LocalAudioLibrary.AudioQualityBadgeFlac ||
            normalizedMimeType.contains(LocalAudioLibrary.AudioQualityBadgeFlac) -> {
            LocalAudioLibrary.AudioQualityBadgeFlac
        }
        extension == LocalAudioLibrary.AudioQualityBadgeApe ||
            normalizedMimeType.contains(LocalAudioLibrary.AudioQualityBadgeApe) ||
            normalizedMimeType.contains("monkeys-audio") -> {
            LocalAudioLibrary.AudioQualityBadgeApe
        }
        extension == LocalAudioLibrary.AudioQualityBadgeWav ||
            normalizedMimeType.contains(LocalAudioLibrary.AudioQualityBadgeWav) ||
            normalizedMimeType.contains("wave") -> {
            LocalAudioLibrary.AudioQualityBadgeWav
        }
        extension == LocalAudioLibrary.AudioQualityBadgeAiff ||
            extension == "aif" ||
            normalizedMimeType.contains(LocalAudioLibrary.AudioQualityBadgeAiff) -> {
            LocalAudioLibrary.AudioQualityBadgeAiff
        }
        extension == LocalAudioLibrary.AudioQualityBadgeAlac ||
            normalizedMimeType.contains(LocalAudioLibrary.AudioQualityBadgeAlac) -> {
            LocalAudioLibrary.AudioQualityBadgeAlac
        }
        extension == LocalAudioLibrary.AudioQualityBadgeCue ||
            normalizedMimeType.contains(LocalAudioLibrary.AudioQualityBadgeCue) -> {
            LocalAudioLibrary.AudioQualityBadgeCue
        }
        else -> null
    }
}

internal fun LocalAudioLibrary.currentMediaStoreSnapshot(): MediaStoreSnapshot {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return currentApi30MediaStoreSnapshot()
    }
    val fingerprint = currentMediaStoreFingerprint()
    val versions =
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            Api29.mediaStoreVersions(context)
        } else {
            mapOf(LocalAudioLibrary.ExternalVolumeName to MediaStore.getVersion(context))
        }
    return MediaStoreSnapshot(
        versions.mapValues { (_, version) ->
            VolumeSnapshot(
                version = version,
                generation = fingerprint,
            )
        }
    )
}

@RequiresApi(Build.VERSION_CODES.R)
private fun LocalAudioLibrary.currentApi30MediaStoreSnapshot(): MediaStoreSnapshot {
    val volumes = runCatching {
        MediaStore.getExternalVolumeNames(context)
    }
        .getOrDefault(emptySet())
    return MediaStoreSnapshot(
        volumes.sorted().associateWith { volume ->
            VolumeSnapshot(
                version = MediaStore.getVersion(context, volume),
                generation = MediaStore.getGeneration(context, volume),
            )
        }
    )
}

private fun LocalAudioLibrary.currentMediaStoreFingerprint(): Long {
    var fingerprint = LocalAudioLibrary.MediaStoreFingerprintSeed
    runCatching {
        context.contentResolver.query(
            audioCollection(),
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.SIZE,
            ),
            audioSelection(),
            null,
            "${MediaStore.Audio.Media._ID} ASC",
        )
    }
        .getOrNull()
        ?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val dateModifiedColumn =
                cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            while (cursor.moveToNext()) {
                fingerprint =
                    fingerprint * LocalAudioLibrary.MediaStoreFingerprintMultiplier + cursor.getLong(idColumn)
                fingerprint =
                    fingerprint * LocalAudioLibrary.MediaStoreFingerprintMultiplier +
                        cursor.getLong(dateModifiedColumn)
                fingerprint =
                    fingerprint * LocalAudioLibrary.MediaStoreFingerprintMultiplier + cursor.getLong(sizeColumn)
            }
        }
    return fingerprint
}

internal data class MediaStoreSnapshot(val volumes: Map<String, VolumeSnapshot>) {
    val storageKey: String =
        volumes.entries.joinToString("\u001e") { (volume, snapshot) ->
            listOf(volume, snapshot.version, snapshot.generation.toString())
                .joinToString("\u001f")
        }
}

internal data class VolumeSnapshot(
    val version: String,
    val generation: Long,
)

internal data class AudioStorageLocation(
    val volumeName: String,
    val relativePath: String,
)

internal data class AudioCursorRow(
    val mediaId: String,
    val stableKey: String,
    val uri: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val albumId: Long?,
    val durationMs: Long,
    val track: Int?,
    val year: Int?,
    val volumeName: String,
    val relativePath: String,
    val displayName: String,
    val mimeType: String?,
    val dateAdded: Long?,
    val dateModified: Long?,
    val generationAdded: Long?,
    val generationModified: Long?,
)

internal fun LocalAudioLibrary.audioCollection(): Uri {
    return audioMediaCollectionUri()
}

private fun LocalAudioLibrary.audioItemProjection(): Array<String> {
    val projection =
        mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
        )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        projection += MediaStore.Audio.Media.ALBUM_ARTIST
        projection += MediaStore.Audio.Media.GENERATION_ADDED
        projection += MediaStore.Audio.Media.GENERATION_MODIFIED
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        projection += MediaStore.MediaColumns.VOLUME_NAME
        projection += MediaStore.MediaColumns.RELATIVE_PATH
    } else {
        projection += MediaStore.MediaColumns.DATA
    }
    return projection.toTypedArray()
}

internal fun LocalAudioLibrary.audioSelection(): String {
    return buildString {
        append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
        append(" AND ${MediaStore.Audio.Media.DURATION} > 0")
    }
}

internal fun LocalAudioLibrary.audioSortOrder(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        "${MediaStore.Audio.Media.GENERATION_ADDED} DESC"
    } else {
        "${MediaStore.Audio.Media.DATE_ADDED} DESC"
    }
}

internal fun LocalAudioLibrary.queryIndexedAudioSnapshot(): LocalAudioLibrary.IndexedAudioSnapshot {
    val fileKeys = linkedSetOf<String>()
    val externalVolumeRoots =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            externalVolumeRoots()
        } else {
            emptySet()
        }
    val projection =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.MediaColumns.VOLUME_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DISPLAY_NAME,
            )
        } else {
            arrayOf(
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME,
            )
        }

    runCatching {
        context.contentResolver.query(
            audioCollection(),
            projection,
            audioSelection(),
            null,
            null,
        )
    }
        .getOrNull()
        ?.use { cursor ->
            val displayNameColumn =
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val storageLocation = cursor.audioStorageLocation(externalVolumeRoots)
                val displayName = cursor.getString(displayNameColumn)
                stableAudioLibraryKey(
                        storageLocation.volumeName,
                        storageLocation.relativePath,
                        displayName,
                    )
                    ?.let(fileKeys::add)
            }
        }

    return LocalAudioLibrary.IndexedAudioSnapshot(fileKeys = fileKeys)
}

@RequiresApi(Build.VERSION_CODES.Q)
internal object Api29 {
    fun mediaStoreVersions(context: Context): Map<String, String> {
        return runCatching {
            MediaStore.getExternalVolumeNames(context)
                .sorted()
                .associateWith { volume -> MediaStore.getVersion(context, volume) }
                .takeIf(Map<String, String>::isNotEmpty)
        }
            .getOrNull() ?: mapOf(MediaStore.VOLUME_EXTERNAL to MediaStore.getVersion(context))
    }

    fun mediaStoreVolumeName(storageVolume: StorageVolume?, root: File): String {
        return when {
            storageVolume?.isPrimary == true -> MediaStore.VOLUME_EXTERNAL_PRIMARY
            !storageVolume?.uuid.isNullOrBlank() ->
                storageVolume.uuid.orEmpty().lowercase(Locale.ROOT)
            else -> root.absolutePath
        }
    }
}

private object LibraryTitleNormalizer {
    private val combiningMarks = "\\p{Mn}+".toRegex()

    @Synchronized
    fun normalize(title: String): String {
        val trimmed = title.trim()
        val transliterated = HanLatinTransliterator.transliterate(trimmed)
        return Normalizer.normalize(transliterated, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)
            .trim()
    }
}

internal fun String.repairMetadataEncoding(): String {
    if (isEmpty() || !looksLikeMojibake()) {
        return this
    }
    return MetadataRepairCharsets.firstNotNullOfOrNull { charset ->
        repairMojibake(charset)
    } ?: this
}

private fun String.repairMojibake(charset: Charset): String? {
    return toByteArrayOrNull(charset)
        ?.let { bytes -> String(bytes, StandardCharsets.UTF_8) }
        ?.takeIf { repaired ->
            repaired.isNotEmpty() &&
                repaired != this &&
                ReplacementCharacter !in repaired &&
                (repaired.containsCjkOrFullWidth() || !repaired.looksLikeMojibake())
        }
}

private fun String.toByteArrayOrNull(charset: Charset): ByteArray? {
    return runCatching {
        val encoder =
            charset
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = encoder.encode(CharBuffer.wrap(this))
        ByteArray(buffer.remaining()).also(buffer::get)
    }
        .getOrNull()
}

private fun String.looksLikeMojibake(): Boolean {
    return MojibakeMarkers.any(::contains)
}

private fun String.containsCjkOrFullWidth(): Boolean {
    return any { char ->
        val code = char.code
        code in 0x3040..0x30FF ||
            code in 0x3400..0x9FFF ||
            code in 0x1100..0x11FF ||
            code in 0xAC00..0xD7AF ||
            code in 0xFF01..0xFFEF
    }
}

private val MetadataRepairCharsets =
    listOf(
        StandardCharsets.ISO_8859_1,
        Charset.forName("windows-1252"),
        Charset.forName("windows-1250"),
    )

private val MojibakeMarkers = listOf("Ã", "Â", "ã", "ď", "ï", "æ", "å", "¤", "½", "ž")
private const val ReplacementCharacter = '\uFFFD'

private fun String.libraryTitleSection(): String {
    val firstLetter =
        firstOrNull { char ->
            char.isLetterOrDigit()
        } ?: return "#"
    val upper = firstLetter.uppercaseChar()
    return if (upper in 'A'..'Z') {
        upper.toString()
    } else {
        "#"
    }
}

internal fun stableAudioLibraryKey(
    volumeName: String?,
    relativePath: String?,
    displayName: String?,
): String? {
    val normalizedDisplayName = displayName?.trim().orEmpty()
    if (normalizedDisplayName.isEmpty()) {
        return null
    }
    val normalizedVolumeName =
        volumeName?.trim()?.takeIf(String::isNotEmpty) ?: StableKeyVolumeFallback
    return "$normalizedVolumeName:${normalizeLibraryRelativePath(relativePath)}$normalizedDisplayName"
        .lowercase(Locale.ROOT)
}

private fun normalizeLibraryRelativePath(relativePath: String?): String {
    return relativePath
        ?.replace('\\', '/')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { if (it.endsWith('/')) it else "$it/" }
        .orEmpty()
}

internal fun shouldSkipMediaScannerPath(relativePath: String): Boolean {
    return relativePath.replace('\\', '/').split('/').filter(String::isNotEmpty).any { segment ->
        segment.startsWith('.')
    }
}

private const val StableKeyVolumeFallback = "unknown-volume"

internal fun File.relativePathFromVolumeRoot(volumeRoot: File): String? {
    val parent = parentFile ?: return null
    if (parent == volumeRoot) {
        return null
    }
    return parent
        .relativeToOrNull(volumeRoot)
        ?.invariantSeparatorsPath
        ?.takeIf { it.isNotBlank() }
        ?.let { "$it/" }
}
