package com.smartisan.music.data.settings

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val OnlineMusicSettingsStoreName = "online_music_settings"

private val Context.onlineMusicSettingsDataStore by preferencesDataStore(
    name = OnlineMusicSettingsStoreName,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

data class OnlineMusicSettings(
    val neteasePlaybackQuality: NeteaseAudioQuality = NeteaseAudioQuality.ExHigh,
)

/**
 * 在线播放音质档位。
 *
 * [immerseType] 只在沉浸环绕声（sky）档传出：官方客户端的取值逻辑是「sky 用 c51/aac、
 * 其余档位默认 c51」，而实测非 sky 档位传与不传结果完全一致，因此这里只对 sky 传 c51，
 * 避免无谓改动其他档位的请求。不传时服务端会把 sky 静默降级成 jyeffect（实测）。
 */
enum class NeteaseAudioQuality(
    val preferenceValue: String,
    val level: String,
    val encodeType: String,
    val immerseType: String? = null,
) {
    Standard("standard", "standard", "mp3"),
    Higher("higher", "higher", "mp3"),
    ExHigh("exhigh", "exhigh", "mp3"),
    Lossless("lossless", "lossless", "flac"),
    HiRes("hires", "hires", "flac"),
    HdSurround("jyeffect", "jyeffect", "flac"),
    Surround("sky", "sky", "flac", immerseType = "c51"),
    Master("jymaster", "jymaster", "flac");

    companion object {
        fun fromPreference(value: String?): NeteaseAudioQuality {
            return entries.firstOrNull { quality -> quality.preferenceValue == value } ?: ExHigh
        }
    }
}

/**
 * 账号的在线音质权益档次，用于设置页判断哪些档位可选。
 *
 * 未登录与非会员同为 [Free]：两者都拿不到付费档位。
 */
enum class NeteaseVipLevel {
    Free,
    Vip,
    Svip,
}

/**
 * 该档位要求的最低会员等级，取自官方客户端的档位表（vipType 字段）：
 * 标准/较高为免费，极高至高清臻音需黑胶 VIP，超清母带及以上需黑胶 SVIP。
 * 极高官方标为 unknown（取决于灰度），这里按需 VIP 处理——免费账号实际拿不到 320k。
 */
val NeteaseAudioQuality.requiredVipLevel: NeteaseVipLevel
    get() = when (this) {
        NeteaseAudioQuality.Standard,
        NeteaseAudioQuality.Higher,
        -> NeteaseVipLevel.Free

        NeteaseAudioQuality.ExHigh,
        NeteaseAudioQuality.Lossless,
        NeteaseAudioQuality.HiRes,
        NeteaseAudioQuality.HdSurround,
        -> NeteaseVipLevel.Vip

        NeteaseAudioQuality.Surround,
        NeteaseAudioQuality.Master,
        -> NeteaseVipLevel.Svip
    }

/** 当前账号档次是否够用。播放时仍走 [fallbackCandidates] 逐档降级，这里只决定设置页可选性。 */
fun NeteaseAudioQuality.isAvailableFor(vipLevel: NeteaseVipLevel): Boolean {
    return requiredVipLevel.ordinal <= vipLevel.ordinal
}

val NeteaseAudioQualityFallbackOrder = listOf(
    NeteaseAudioQuality.Master,
    NeteaseAudioQuality.Surround,
    NeteaseAudioQuality.HdSurround,
    NeteaseAudioQuality.HiRes,
    NeteaseAudioQuality.Lossless,
    NeteaseAudioQuality.ExHigh,
    NeteaseAudioQuality.Higher,
    NeteaseAudioQuality.Standard,
)

fun NeteaseAudioQuality.fallbackCandidates(): List<NeteaseAudioQuality> {
    val index = NeteaseAudioQualityFallbackOrder.indexOf(this)
    return if (index >= 0) {
        NeteaseAudioQualityFallbackOrder.drop(index)
    } else {
        listOf(this, NeteaseAudioQuality.ExHigh, NeteaseAudioQuality.Standard).distinct()
    }
}

class OnlineMusicSettingsStore(
    private val context: Context,
) {

    val settings: Flow<OnlineMusicSettings> = context.onlineMusicSettingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(Preferences::toOnlineMusicSettings)
        .distinctUntilChanged()

    suspend fun readSettings(): OnlineMusicSettings {
        return context.onlineMusicSettingsDataStore.data
            .catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }
            .first()
            .toOnlineMusicSettings()
    }

    suspend fun setNeteasePlaybackQuality(quality: NeteaseAudioQuality) {
        context.onlineMusicSettingsDataStore.edit { preferences ->
            preferences[NeteasePlaybackQualityKey] = quality.preferenceValue
        }
    }
}

private fun Preferences.toOnlineMusicSettings(): OnlineMusicSettings {
    return OnlineMusicSettings(
        neteasePlaybackQuality = NeteaseAudioQuality.fromPreference(this[NeteasePlaybackQualityKey]),
    )
}

private val NeteasePlaybackQualityKey = stringPreferencesKey("netease_playback_quality")
