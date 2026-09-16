package com.smartisan.music.data.online

import com.smartisan.music.AppDispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 登录域端点：短信验证码、手机号登录、扫码登录。
 *
 * 全部走 eapi 且**必须匿名单发**（[NeteaseCloudMusicClient.callEApi] 的 `anonymous = true`）：
 * 这些接口在未登录时才有意义，带上已存的 MUSIC_U 会被服务端按「已登录」拒绝
 * （扫码轮询会直接返回 `code=400 "device has login success"`）。
 *
 * 登录成功由响应 `Set-Cookie` 下发 MUSIC_U，调用方取
 * [NeteaseCloudMusicClient.sessionCookieSnapshot] 写入 [NeteaseAuthStore]。
 */

/** 下发短信验证码。返回 200 仅代表请求被受理，不代表号码有效（风控会静默丢弃）。 */
internal suspend fun NeteaseCloudMusicClient.sendLoginSmsCode(
    phone: String,
    countryCode: String = "86",
): NeteaseSmsSendResult = withContext(AppDispatchers.IO) {
    val safePhone = phone.trim()
    if (safePhone.isEmpty()) {
        return@withContext NeteaseSmsSendResult(NeteaseAccountActionStatus.Failed)
    }
    val response = runSuspendCatching {
        callEApi(
            path = "/sms/captcha/sent",
            params = mapOf(
                "cellphone" to safePhone,
                "ctcode" to countryCode,
            ),
            anonymous = true,
        )
    }.getOrNull() ?: return@withContext NeteaseSmsSendResult(NeteaseAccountActionStatus.Failed)
    android.util.Log.d("NeteasePhoneDbg", "sms/sent resp=$response")
    parseNeteaseLoginCodeResponse(response)
}

/** 手机号 + 短信验证码登录。 */
internal suspend fun NeteaseCloudMusicClient.loginWithPhone(
    phone: String,
    smsCode: String,
    countryCode: String = "86",
): NeteasePhoneLoginResult = withContext(AppDispatchers.IO) {
    val safePhone = phone.trim()
    val safeCode = smsCode.trim()
    if (safePhone.isEmpty() || safeCode.isEmpty()) {
        return@withContext NeteasePhoneLoginResult(NeteaseAccountActionStatus.Failed)
    }
    val params = neteaseCellphoneLoginParams(
        phone = safePhone,
        smsCode = safeCode,
        countryCode = countryCode,
    )
    val response = runSuspendCatching {
        callEApi(
            path = "/login/cellphone",
            params = params,
            anonymous = true,
        )
    }.getOrNull() ?: return@withContext NeteasePhoneLoginResult(NeteaseAccountActionStatus.Failed)
    android.util.Log.d(
        "NeteasePhoneDbg",
        "login/cellphone keys=${params.keys} phoneLen=${safePhone.length} codeLen=${safeCode.length} resp=$response",
    )
    parseNeteasePhoneLoginResponse(response)
}

/** 扫码登录第一步：取二维码 key。 */
internal suspend fun NeteaseCloudMusicClient.getLoginQrKey(): NeteaseQrKeyResult = withContext(AppDispatchers.IO) {
    val response = runSuspendCatching {
        callEApi(
            path = "/login/qrcode/unikey",
            params = mapOf("type" to LoginQrKeyType),
            anonymous = true,
        )
    }.getOrNull() ?: return@withContext NeteaseQrKeyResult(NeteaseAccountActionStatus.Failed)
    parseNeteaseQrKeyResponse(response)
}

/** 扫码登录第二步：轮询扫码状态（间隔 2~3s，二维码 5 分钟内有效）。 */
internal suspend fun NeteaseCloudMusicClient.pollLoginQrStatus(unikey: String): NeteaseQrPollResult = withContext(AppDispatchers.IO) {
    val safeKey = unikey.trim()
    if (safeKey.isEmpty()) {
        return@withContext NeteaseQrPollResult(NeteaseQrLoginStatus.Unknown)
    }
    val response = runSuspendCatching {
        callEApi(
            path = "/login/qrcode/client/login",
            params = mapOf(
                "key" to safeKey,
                "type" to LoginQrKeyType,
            ),
            anonymous = true,
        )
    }.getOrNull() ?: return@withContext NeteaseQrPollResult(NeteaseQrLoginStatus.Unknown)
    parseNeteaseQrPollResponse(response)
}

/** 二维码类型：1 与官方 App 一致（扫码端为云音乐 App）。 */
private const val LoginQrKeyType = "1"

/** 国际区号（中国大陆）。 */
internal const val NeteaseDefaultCountryCode = "86"

/**
 * 手机号登录参数。
 *
 * 号码字段名是 **`phone`**，不是 `cellphone`——发短信端点用 `cellphone`，两者不同名。
 * 用 `cellphone` 时服务端读不到号码，会回 `400`（探测：`cellphone`+密码 → 400，
 * `phone`+密码 → 502 账号或密码错误，说明后者才被读到）；而带验证码时它先校验验证码，
 * 只回一句「验证码错误」，会把「参数名错」伪装成「验证码不对」，极难排查。
 *
 * `rememberLogin` 让服务端下发长效 MUSIC_U，避免频繁重登。
 */
internal fun neteaseCellphoneLoginParams(
    phone: String,
    smsCode: String,
    countryCode: String = NeteaseDefaultCountryCode,
): Map<String, String> = mapOf(
    "phone" to phone,
    "captcha" to smsCode,
    "countrycode" to countryCode,
    "rememberLogin" to "true",
)

internal fun parseNeteaseLoginCodeResponse(response: String): NeteaseSmsSendResult {
    val root = runCatching { JSONObject(response) }.getOrNull()
        ?: return NeteaseSmsSendResult(NeteaseAccountActionStatus.Failed)
    val code = root.optInt("code", -1)
    val status = when {
        code == 200 -> NeteaseAccountActionStatus.Success
        code == 301 -> NeteaseAccountActionStatus.RequiresLogin
        else -> NeteaseAccountActionStatus.Failed
    }
    return NeteaseSmsSendResult(
        status = status,
        code = code.takeIf { it >= 0 },
        message = loginMessageOf(root),
    )
}

internal fun parseNeteasePhoneLoginResponse(response: String): NeteasePhoneLoginResult {
    val root = runCatching { JSONObject(response) }.getOrNull()
        ?: return NeteasePhoneLoginResult(NeteaseAccountActionStatus.Failed)
    val code = root.optInt("code", -1)
    val status = when {
        code == 200 -> NeteaseAccountActionStatus.Success
        code == 301 -> NeteaseAccountActionStatus.RequiresLogin
        else -> NeteaseAccountActionStatus.Failed
    }
    return NeteasePhoneLoginResult(
        status = status,
        code = code.takeIf { it >= 0 },
        message = loginMessageOf(root),
    )
}

internal fun parseNeteaseQrKeyResponse(response: String): NeteaseQrKeyResult {
    val root = runCatching { JSONObject(response) }.getOrNull()
        ?: return NeteaseQrKeyResult(NeteaseAccountActionStatus.Failed)
    val code = root.optInt("code", -1)
    if (code != 200) {
        return NeteaseQrKeyResult(
            status = NeteaseAccountActionStatus.Failed,
            code = code.takeIf { it >= 0 },
            message = loginMessageOf(root),
        )
    }
    // 实测响应把 unikey 放在顶层（{"code":200,"unikey":"..."}），不在 data 里。
    val unikey = root.optNonBlankString("unikey")
        ?: root.optJSONObject("data")?.optNonBlankString("unikey")
        ?: return NeteaseQrKeyResult(NeteaseAccountActionStatus.Failed, code = code)
    return NeteaseQrKeyResult(
        status = NeteaseAccountActionStatus.Success,
        unikey = unikey,
        code = code,
    )
}

internal fun parseNeteaseQrPollResponse(response: String): NeteaseQrPollResult {
    val root = runCatching { JSONObject(response) }.getOrNull()
        ?: return NeteaseQrPollResult(NeteaseQrLoginStatus.Unknown)
    val code = root.optInt("code", -1)
    return NeteaseQrPollResult(
        status = NeteaseQrLoginStatus.from(code),
        code = code.takeIf { it >= 0 },
        message = loginMessageOf(root),
    )
}

/** 登录类响应的错误说明：`msg` / `message` 两种字段名都见过，取先出现的非空值。 */
private fun loginMessageOf(root: JSONObject): String? {
    return root.optNonBlankString("message") ?: root.optNonBlankString("msg")
}
