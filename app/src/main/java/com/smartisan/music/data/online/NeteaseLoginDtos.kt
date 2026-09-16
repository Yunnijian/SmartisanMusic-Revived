package com.smartisan.music.data.online

/** 云音乐登录方式。 */
internal enum class NeteaseLoginMethod {
    Phone,
    QrCode,
    Web,
}

/** 短信验证码下发结果。 */
internal data class NeteaseSmsSendResult(
    val status: NeteaseAccountActionStatus,
    val code: Int? = null,
    val message: String? = null,
)

/**
 * 手机号登录结果。
 *
 * 凭据不在响应体里：登录成功时服务端通过 `Set-Cookie` 下发 MUSIC_U，
 * 由调用方取 [NeteaseCloudMusicClient.sessionCookieSnapshot] 落盘。
 */
internal data class NeteasePhoneLoginResult(
    val status: NeteaseAccountActionStatus,
    val code: Int? = null,
    val message: String? = null,
)

/** 扫码登录取 key 结果。 */
internal data class NeteaseQrKeyResult(
    val status: NeteaseAccountActionStatus,
    val unikey: String? = null,
    val code: Int? = null,
    val message: String? = null,
)

/**
 * 二维码轮询状态（对齐官方 App 的 800/801/802/803/804，外加实测遇到的风控码）。
 *
 * 803 时响应 `Set-Cookie` 已下发 MUSIC_U，与手机号登录同一套持久化路径。
 */
internal enum class NeteaseQrLoginStatus(val rawCode: Int) {
    /** 二维码不存在或已过期，需重新取 key。 */
    Expired(800),

    /** 等待扫码。 */
    WaitingScan(801),

    /** 已扫码，等待用户在手机上确认授权。 */
    WaitingConfirm(802),

    /** 授权登录成功。 */
    Success(803),

    /** 未知错误。 */
    Unknown(804),

    /**
     * 风控拦截（实测扫到 802 后返回 8821「请切换其他登录方式或升级新版本再试」）。
     *
     * 必须与 [Unknown] 分开：它虽然也不是 800-804，但**是终态**，继续轮询永远不会成功。
     */
    RiskRejected(8821),
    ;

    /** 是否还会再变：只有等待扫码/授权中需要继续轮询，其余都应停止并给出结果。 */
    val isPending: Boolean
        get() = this == WaitingScan || this == WaitingConfirm

    companion object {
        fun from(rawCode: Int): NeteaseQrLoginStatus {
            return entries.firstOrNull { it.rawCode == rawCode } ?: Unknown
        }
    }
}

internal data class NeteaseQrPollResult(
    val status: NeteaseQrLoginStatus,
    val code: Int? = null,
    val message: String? = null,
)
