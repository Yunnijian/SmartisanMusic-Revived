package com.smartisan.music.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TurntableSettingsPreferencesName = "turntable_settings"
private const val TurntableStyleKey = "turntable_style"

enum class TurntableStyle(
    val preferenceValue: String,
    val labelRes: Int,
) {
    Original("original", com.smartisan.music.R.string.turntable_style_original),
    Netease("netease", com.smartisan.music.R.string.turntable_style_netease),
    ;

    companion object {
        fun fromPreference(value: String?): TurntableStyle {
            return entries.firstOrNull { style -> style.preferenceValue == value } ?: Original
        }
    }
}

class TurntableStyleStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        TurntableSettingsPreferencesName,
        Context.MODE_PRIVATE,
    )
    private val styleFlow = MutableStateFlow(readPersistedStyle())

    val style: StateFlow<TurntableStyle> = styleFlow.asStateFlow()

    fun currentStyle(): TurntableStyle = styleFlow.value

    fun setStyle(style: TurntableStyle) {
        preferences.edit()
            .putString(TurntableStyleKey, style.preferenceValue)
            .apply()
        styleFlow.value = style
    }

    private fun readPersistedStyle(): TurntableStyle {
        return TurntableStyle.fromPreference(preferences.getString(TurntableStyleKey, null))
    }
}
