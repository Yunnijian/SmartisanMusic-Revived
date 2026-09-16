package com.smartisan.music.data.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录响应解析器单测。
 *
 * 样本取自设备 Cookie 直连 eapi 的实测响应（见 _tools/probe_eapi_style.py 的探测方式），
 * 尤其覆盖两个容易踩的点：unikey 在顶层而非 data 内、轮询状态码不在 200 一族。
 */
class NeteaseLoginParserTest {

    @Test
    fun qrKeyIsReadFromTopLevel() {
        val result = parseNeteaseQrKeyResponse("""{"code":200,"unikey":"5aca4c73-69c1-41a7-b69f-8aaaec9a99a1"}""")

        assertEquals(NeteaseAccountActionStatus.Success, result.status)
        assertEquals("5aca4c73-69c1-41a7-b69f-8aaaec9a99a1", result.unikey)
    }

    @Test
    fun qrKeyFallsBackToDataObject() {
        val result = parseNeteaseQrKeyResponse("""{"code":200,"data":{"unikey":"abc-123"}}""")

        assertEquals(NeteaseAccountActionStatus.Success, result.status)
        assertEquals("abc-123", result.unikey)
    }

    @Test
    fun qrKeyWithoutUnikeyIsFailure() {
        val result = parseNeteaseQrKeyResponse("""{"code":200}""")

        assertEquals(NeteaseAccountActionStatus.Failed, result.status)
        assertNull(result.unikey)
    }

    @Test
    fun qrPollStatusesMapToOfficialStateMachine() {
        assertEquals(NeteaseQrLoginStatus.Expired, parseQrPoll(800))
        assertEquals(NeteaseQrLoginStatus.WaitingScan, parseQrPoll(801))
        assertEquals(NeteaseQrLoginStatus.WaitingConfirm, parseQrPoll(802))
        assertEquals(NeteaseQrLoginStatus.Success, parseQrPoll(803))
        assertEquals(NeteaseQrLoginStatus.Unknown, parseQrPoll(804))
    }

    @Test
    fun qrPollUnknownCodeFallsBackToUnknown() {
        assertEquals(NeteaseQrLoginStatus.Unknown, parseQrPoll(500))
    }

    @Test
    fun qrPollRiskCodeIsRecognisedAsTerminal() {
        // 实测：扫到 802 后服务端返回 8821「请切换其他登录方式或升级新版本再试」。
        val status = parseQrPoll(8821)

        assertEquals(NeteaseQrLoginStatus.RiskRejected, status)
        assertFalse("风控是终态，不能继续轮询", status.isPending)
    }

    @Test
    fun qrPollOnlyWaitingStatesKeepPolling() {
        assertTrue(NeteaseQrLoginStatus.WaitingScan.isPending)
        assertTrue(NeteaseQrLoginStatus.WaitingConfirm.isPending)
        assertFalse(NeteaseQrLoginStatus.Expired.isPending)
        assertFalse(NeteaseQrLoginStatus.Success.isPending)
        assertFalse(NeteaseQrLoginStatus.Unknown.isPending)
    }

    @Test
    fun qrPollKeepsMessageForUserFacingError() {
        val result = parseNeteaseQrPollResponse("""{"code":801,"message":"等待扫码"}""")

        assertEquals(NeteaseQrLoginStatus.WaitingScan, result.status)
        assertEquals("等待扫码", result.message)
    }

    @Test
    fun phoneLoginReadsMsgFieldWhenMessageAbsent() {
        val result = parseNeteasePhoneLoginResponse("""{"msg":"账号或密码错误","code":502,"message":"账号或密码错误"}""")

        assertEquals(NeteaseAccountActionStatus.Failed, result.status)
        assertEquals(502, result.code)
        assertEquals("账号或密码错误", result.message)
    }

    @Test
    fun phoneLoginSuccessHasNoMessage() {
        val result = parseNeteasePhoneLoginResponse("""{"code":200,"profile":{"userId":1}}""")

        assertEquals(NeteaseAccountActionStatus.Success, result.status)
        assertNull(result.message)
    }

    @Test
    fun cellphoneLoginUsesPhoneFieldNameNotCellphone() {
        // 实测坑：号码字段名是 phone。写成 cellphone 服务端读不到号码，
        // 带验证码时只回「验证码错误」，把参数名错伪装成验证码不对。
        val params = neteaseCellphoneLoginParams(
            phone = "13800000000",
            smsCode = "1234",
            countryCode = "86",
        )

        assertEquals("13800000000", params["phone"])
        assertNull("登录接口不能用 cellphone 传号码", params["cellphone"])
        assertEquals("1234", params["captcha"])
        assertEquals("86", params["countrycode"])
    }

    @Test
    fun cellphoneLoginDefaultsToChinaCountryCode() {
        val params = neteaseCellphoneLoginParams(phone = "13800000000", smsCode = "1234")

        assertEquals(NeteaseDefaultCountryCode, params["countrycode"])
    }

    @Test
    fun smsSendRejectsMalformedBody() {
        val result = parseNeteaseLoginCodeResponse("not json")

        assertEquals(NeteaseAccountActionStatus.Failed, result.status)
    }

    @Test
    fun smsSendAccepts200AsAccepted() {
        // 实测：该端点对未通过风控的号码也回 200，故 200 只代表请求被受理。
        val result = parseNeteaseLoginCodeResponse("""{"code":200,"data":true}""")

        assertEquals(NeteaseAccountActionStatus.Success, result.status)
        assertTrue(result.code == 200)
    }

    private fun parseQrPoll(code: Int): NeteaseQrLoginStatus {
        return parseNeteaseQrPollResponse("""{"code":$code}""").status
    }
}
