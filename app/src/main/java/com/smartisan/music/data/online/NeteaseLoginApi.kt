package com.smartisan.music.data.online

/**
 * 登录域门面：三种登录方式的编排与登录态落盘。
 *
 * 三种方式最终汇到同一条出口——凭据都是响应 `Set-Cookie` 里的 MUSIC_U
 * （扫码登录同样如此，不是 accessToken），由本文件统一写进 [NeteaseAuthStore]。
 */

/** 登录流程的三态结果；比 [NeteaseAccountActionStatus] 多带一句面向用户的错误说明。 */
internal data class NeteaseLoginOutcome(
    val status: NeteaseAccountActionStatus,
    val message: String? = null,
)

internal suspend fun NeteaseOnlineMusicRepository.sendLoginSmsCode(phone: String): NeteaseLoginOutcome {
    val result = client.sendLoginSmsCode(phone)
    return NeteaseLoginOutcome(
        status = result.status,
        message = result.message,
    )
}

/**
 * 开始一次登录：清掉上一次累积的会话 Cookie。
 *
 * 登录页打开时调一次即可。不能放在「发短信」与「提交登录」之间——
 * 那两步属于同一个服务端会话（短信下发时的 NMTID 要延续到登录请求），
 * 中途清空会让服务端认为会话断裂，实测会被判环境异常。
 */
internal fun NeteaseOnlineMusicRepository.beginLoginSession() {
    client.clearSessionCookies()
}

internal suspend fun NeteaseOnlineMusicRepository.loginWithPhone(
    phone: String,
    smsCode: String,
): NeteaseLoginOutcome {
    val result = client.loginWithPhone(phone = phone, smsCode = smsCode)
    return persistLoginResult(NeteaseLoginOutcome(result.status, result.message))
}

internal suspend fun NeteaseOnlineMusicRepository.getLoginQrKey(): NeteaseQrKeyResult {
    return client.getLoginQrKey()
}

internal suspend fun NeteaseOnlineMusicRepository.pollLoginQrStatus(unikey: String): NeteaseQrPollResult {
    return client.pollLoginQrStatus(unikey)
}

/**
 * 扫码轮询到 803 后确认登录：把会话 Cookie 落盘并收尾。
 *
 * 与 [loginWithPhone] 分开，是因为扫码的「授权成功」由轮询响应给出，
 * 轮询本身要保持匿名与轻量，不做落盘副作用。
 */
internal suspend fun NeteaseOnlineMusicRepository.completeQrLogin(): NeteaseLoginOutcome {
    return persistLoginResult(NeteaseLoginOutcome(NeteaseAccountActionStatus.Success))
}

/**
 * 登录成功的统一收尾：持久化 Cookie → 作废账号域缓存 → 拉一次资料。
 *
 * 缓存必须整体作废：上一账号的歌单 / 收藏 / 每日推荐都可能挂在内存或磁盘里，
 * 不清会出现「刚登进去看到的是别人数据」。
 */
private suspend fun NeteaseOnlineMusicRepository.persistLoginResult(
    outcome: NeteaseLoginOutcome,
): NeteaseLoginOutcome {
    if (outcome.status != NeteaseAccountActionStatus.Success) {
        return outcome
    }
    val cookies = client.sessionCookieSnapshot()
    val saved = authStore?.saveCookies(cookies) == true
    if (!saved) {
        // 没有拿到 MUSIC_U：接口报成功但凭据缺失，按失败处理，避免留下「已登录」假象。
        return NeteaseLoginOutcome(NeteaseAccountActionStatus.Failed)
    }
    invalidateAccountCaches()
    invalidatePageCaches("featured", "radio", "daily", "playlist:tracks", "artist")
    // 资料拉取失败不影响登录态本身，能拿到昵称/头像就顺手存下。
    runSuspendCatching { currentUserProfile() }
    return outcome
}
