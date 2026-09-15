@file:Suppress("DEPRECATION")

package com.smartisan.music.data.online

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

private const val NeteaseAuthPrefsName = "netease_auth"
private const val NeteaseAuthCookieJsonKey = "cookie_json"
private const val NeteaseAuthSavedAtKey = "saved_at"
private const val NeteaseAuthProfileJsonKey = "profile_json"
private const val NeteaseLoginCookieKey = "MUSIC_U"

internal data class NeteaseAuthState(
    val cookies: Map<String, String>,
    val savedAt: Long,
    val profile: NeteaseAccountProfile?,
) {
    val isLoggedIn: Boolean
        get() = !cookies[NeteaseLoginCookieKey].isNullOrBlank()
}

internal data class NeteaseCookieValidationResult(
    val cookies: Map<String, String>,
    val rejectedKeys: List<String>,
) {
    val isAccepted: Boolean
        get() = cookies.isNotEmpty() && !cookies[NeteaseLoginCookieKey].isNullOrBlank()
}

internal class NeteaseAuthStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = openPreferences(appContext)

    fun load(): NeteaseAuthState {
        val cookieJson = prefs.getString(NeteaseAuthCookieJsonKey, null).orEmpty()
        val cookies = runCatching {
            parseCookieJson(cookieJson)
        }.getOrDefault(emptyMap())
        return NeteaseAuthState(
            cookies = cookies,
            savedAt = prefs.getLong(NeteaseAuthSavedAtKey, 0L),
            profile = prefs.getString(NeteaseAuthProfileJsonKey, null)
                ?.let { profileJson -> runCatching { parseNeteaseAccountProfileJson(profileJson) }.getOrNull() },
        )
    }

    fun getCookies(): Map<String, String> {
        return load().cookies
    }

    fun validateCookies(cookies: Map<String, String>): NeteaseCookieValidationResult {
        return validateNeteaseCookies(cookies)
    }

    fun saveCookies(cookies: Map<String, String>, savedAt: Long = System.currentTimeMillis()): Boolean {
        val validation = validateCookies(cookies)
        if (!validation.isAccepted) {
            return false
        }
        prefs.edit()
            .putString(NeteaseAuthCookieJsonKey, validation.cookies.toCookieJson())
            .putLong(NeteaseAuthSavedAtKey, savedAt)
            .remove(NeteaseAuthProfileJsonKey)
            .apply()
        bumpAuthScopeRevision()
        return true
    }

    fun saveCookieJson(cookieJson: String, savedAt: Long = System.currentTimeMillis()): Boolean {
        return saveCookies(parseCookieJson(cookieJson), savedAt)
    }

    fun saveProfile(profile: NeteaseAccountProfile) {
        prefs.edit()
            .putString(NeteaseAuthProfileJsonKey, profile.toProfileJson())
            .apply()
    }

    fun clear() {
        prefs.edit()
            .remove(NeteaseAuthCookieJsonKey)
            .remove(NeteaseAuthSavedAtKey)
            .remove(NeteaseAuthProfileJsonKey)
            .apply()
        bumpAuthScopeRevision()
    }

    companion object {
        /**
         * 账号态写入序号：进程级单调递增。凭据存在 EncryptedSharedPreferences 后面，
         * 每次 [load] 都要 Keystore 解密 + JSON 解析，而缓存键构造（账号缓存域）发生在主线程调用路径上；
         * [NeteaseOnlineMusicRepository] 用本序号判定自己缓存的账号域快照是否还新鲜（读序号只是一次 get）。
         * 只有会改变登录态/账号的写入（[saveCookies]、[clear]）才递增。
         */
        private val authScopeRevision = AtomicLong(0L)

        internal fun authScopeRevision(): Long = authScopeRevision.get()

        private fun bumpAuthScopeRevision() {
            authScopeRevision.incrementAndGet()
        }
    }
}

private fun openPreferences(context: Context): SharedPreferences {
    return createEncryptedPrefs(context) ?: run {
        // 加密存储创建失败（如 Keystore 损坏），删除可能损坏的文件后重试一次。
        // 绝不降级为明文 MODE_PRIVATE——MUSIC_U 等登录凭据明文落盘是不可接受的安全风险。
        runCatching { context.deleteSharedPreferences(NeteaseAuthPrefsName) }
        // 使用进程级共享实例：本项目在设置页、云音乐页、Repository、播放服务各自 new NeteaseAuthStore，
        // 若各自创建独立的内存存储，登录保存后其他实例读不到，会误判为未登录。
        createEncryptedPrefs(context) ?: InMemorySharedPreferences
    }
}

private fun createEncryptedPrefs(context: Context): SharedPreferences? {
    return runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            NeteaseAuthPrefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()
}

/**
 * 加密存储彻底不可用时的进程级兜底：内存内可读写 SharedPreferences，不落盘。
 * 所有 NeteaseAuthStore 实例共享同一份，写入的数据本次会话内有效，重启 App 后丢失。
 * 凭据绝不以明文形式留在磁盘上。
 */
private object InMemorySharedPreferences : SharedPreferences {
    private val store = java.util.concurrent.ConcurrentHashMap<String, Any?>()
    private val listeners = java.util.Collections.synchronizedSet(mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>())

    @Suppress("UNCHECKED_CAST")
    override fun getAll(): MutableMap<String, *> = store.toMap() as MutableMap<String, *>
    override fun getString(key: String?, defValue: String?): String? = (store[key] as? String) ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = (store[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = (store[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (store[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (store[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (store[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = store.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor(store, listeners)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listeners.add(listener)
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listeners.remove(listener)
    }

    private class Editor(
        private val store: java.util.concurrent.ConcurrentHashMap<String, Any?>,
        private val listeners: MutableSet<SharedPreferences.OnSharedPreferenceChangeListener>,
    ) : SharedPreferences.Editor {
        private val pending = java.util.concurrent.ConcurrentHashMap<String, Any?>()
        private var doClear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) pending[key] = values
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = REMOVE_MARKER
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            doClear = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (doClear) {
                store.clear()
            }
            pending.forEach { (key, value) ->
                if (value === REMOVE_MARKER) {
                    store.remove(key)
                } else {
                    store[key] = value
                }
            }
            listeners.forEach { it.onSharedPreferenceChanged(null, null) }
        }
    }

    private val REMOVE_MARKER = Any()
}

internal fun parseNeteaseCookieHeader(rawCookieHeader: String): Map<String, String> {
    if (rawCookieHeader.isBlank()) {
        return emptyMap()
    }
    val cookies = linkedMapOf<String, String>()
    rawCookieHeader
        .split(';')
        .map(String::trim)
        .filter { part -> part.isNotBlank() && '=' in part }
        .forEach { part ->
            val separatorIndex = part.indexOf('=')
            val key = part.substring(0, separatorIndex).trim()
            val value = part.substring(separatorIndex + 1).trim()
            if (key.isNotEmpty()) {
                cookies[key] = value
            }
        }
    return cookies
}

internal fun validateNeteaseCookies(cookies: Map<String, String>): NeteaseCookieValidationResult {
    val sanitized = linkedMapOf<String, String>()
    val rejected = linkedSetOf<String>()
    cookies.forEach { (rawKey, rawValue) ->
        val key = rawKey.trim()
        val value = rawValue.trim()
        val rejectedKey = key.ifBlank { "<blank>" }
        when {
            key.isBlank() -> rejected += rejectedKey
            !NeteaseCookieNameRegex.matches(key) -> rejected += rejectedKey
            value.isBlank() -> rejected += rejectedKey
            value.any(Char::isISOControl) -> rejected += rejectedKey
            ';' in value -> rejected += rejectedKey
            else -> sanitized[key] = value
        }
    }
    if (sanitized.isNotEmpty()) {
        sanitized.putIfAbsent("os", "pc")
        sanitized.putIfAbsent("appver", "8.10.35")
    }
    return NeteaseCookieValidationResult(
        cookies = sanitized,
        rejectedKeys = rejected.toList(),
    )
}

internal fun parseCookieJson(cookieJson: String): Map<String, String> {
    if (cookieJson.isBlank()) {
        return emptyMap()
    }
    val root = JSONObject(cookieJson)
    val cookies = linkedMapOf<String, String>()
    val keys = root.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = root.optString(key, "").takeIf(String::isNotBlank)
        if (value != null) {
            cookies[key] = value
        }
    }
    return cookies
}

private fun Map<String, String>.toCookieJson(): String {
    return JSONObject().also { root ->
        forEach { (key, value) -> root.put(key, value) }
    }.toString()
}

private fun NeteaseAccountProfile.toProfileJson(): String {
    return JSONObject()
        .put("userId", userId)
        .put("nickname", nickname)
        .apply {
            avatarUrl?.takeIf(String::isNotBlank)?.let { url -> put("avatarUrl", url) }
        }
        .toString()
}

private val NeteaseCookieNameRegex = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
