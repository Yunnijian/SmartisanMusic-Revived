package com.smartisan.music.data.online

import android.content.Context
import com.smartisan.music.AppDispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

private const val LyricsCacheDirectoryName = "lyrics_cache"
private const val LyricsCacheTtlMs = 7L * 24L * 60L * 60L * 1000L
private const val MaxLyricsCacheFiles = 1_000

/**
 * 歌词磁盘缓存。
 *
 * 缓存键为 `SHA-256("<账号域>:<source>:<trackId>")`，账号域由 Repository 的
 * `authCacheScope()` 传入：VIP 歌词的译文按账号下发，键里不带账号域会让换号后
 * 读到上一个账号的译文。
 *
 * 键格式相比不含账号域的旧版本已变更，旧文件不会再被命中，由 [trimLocked] 的容量淘汰自然回收。
 */
internal class OnlineLyricsDiskCache(
    private val directory: File,
    private val ttlMs: Long = LyricsCacheTtlMs,
) {
    private val lock = Any()

    constructor(
        context: Context,
        ttlMs: Long = LyricsCacheTtlMs,
    ) : this(
        directory = File(context.applicationContext.cacheDir, LyricsCacheDirectoryName),
        ttlMs = ttlMs,
    )

    suspend fun get(
        identity: OnlineTrackIdentity,
        scope: String,
    ): OnlineLyrics? = withContext(AppDispatchers.IO) {
        synchronized(lock) {
            val file = identity.cacheFile(scope)
            if (!file.isFile) {
                return@synchronized null
            }
            val root = runCatching { JSONObject(file.readText()) }.getOrNull()
                ?: return@synchronized null
            val cachedAtMs = root.optLong(CachedAtMsKey, 0L)
            if (cachedAtMs <= 0L || System.currentTimeMillis() - cachedAtMs > ttlMs) {
                file.delete()
                return@synchronized null
            }
            file.setLastModified(System.currentTimeMillis())
            OnlineLyrics(
                lyric = root.optNullableString(LyricKey),
                translatedLyric = root.optNullableString(TranslatedLyricKey),
                wordLyric = root.optNullableString(WordLyricKey),
                translatedWordLyric = root.optNullableString(TranslatedWordLyricsKey),
            )
        }
    }

    suspend fun put(
        identity: OnlineTrackIdentity,
        lyrics: OnlineLyrics,
        scope: String,
    ) {
        withContext(AppDispatchers.IO) {
            synchronized(lock) {
                if (!lyrics.hasContent()) {
                    identity.cacheFile(scope).delete()
                    return@synchronized
                }
                if (!directory.exists() && !directory.mkdirs()) {
                    return@synchronized
                }
                val file = identity.cacheFile(scope)
                val tempFile = File(directory, "${file.name}.tmp")
                val root = JSONObject()
                    .put(CachedAtMsKey, System.currentTimeMillis())
                    .putNullable(LyricKey, lyrics.lyric)
                    .putNullable(TranslatedLyricKey, lyrics.translatedLyric)
                    .putNullable(WordLyricKey, lyrics.wordLyric)
                    .putNullable(TranslatedWordLyricsKey, lyrics.translatedWordLyric)
                runCatching {
                    tempFile.writeText(root.toString())
                    if (!tempFile.renameTo(file)) {
                        file.delete()
                        tempFile.renameTo(file)
                    }
                    trimLocked()
                }
                tempFile.delete()
            }
        }
    }

    private fun OnlineTrackIdentity.cacheFile(scope: String): File {
        return File(directory, "${stableLyricsCacheKey(scope)}.json")
    }

    private fun OnlineTrackIdentity.stableLyricsCacheKey(scope: String): String {
        val rawKey = "$scope:$source:$trackId"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(rawKey.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun trimLocked() {
        val files = directory
            .listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
        val overflow = files.size - MaxLyricsCacheFiles
        if (overflow <= 0) {
            return
        }
        files
            .sortedBy(File::lastModified)
            .take(overflow)
            .forEach { file -> file.delete() }
    }

    private companion object {
        const val CachedAtMsKey = "cachedAtMs"
        const val LyricKey = "lyric"
        const val TranslatedLyricKey = "translatedLyric"
        const val WordLyricKey = "wordLyric"
        const val TranslatedWordLyricsKey = "translatedWordLyrics"
    }
}

private fun JSONObject.putNullable(
    name: String,
    value: String?,
): JSONObject {
    return if (value.isNullOrBlank()) {
        put(name, JSONObject.NULL)
    } else {
        put(name, value)
    }
}

private fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return optString(name).takeIf(String::isNotBlank)
}
