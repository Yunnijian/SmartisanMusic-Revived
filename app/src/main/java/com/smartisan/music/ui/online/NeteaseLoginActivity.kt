package com.smartisan.music.ui.online

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.smartisan.music.MusicAppContainer
import com.smartisan.music.R
import com.smartisan.music.data.online.NeteaseAccountActionStatus
import com.smartisan.music.data.online.NeteaseAuthStore
import com.smartisan.music.data.online.NeteaseLoginMethod
import com.smartisan.music.data.online.NeteaseQrLoginStatus
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.data.online.parseNeteaseCookieHeader
import com.smartisan.music.ui.components.SmartisanDialogButton
import com.smartisan.music.ui.components.SmartisanEditor
import com.smartisan.music.ui.components.SmartisanTitleBar
import com.smartisan.music.ui.components.SmartisanTitleBarAction
import com.smartisan.music.ui.components.smartisanClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 扫码轮询间隔：官方建议 2~3s，取 2s 保证扫码确认后能较快反应。 */
private const val QrPollIntervalMs = 2_000L

/** 短信倒计时秒数。 */
private const val SmsCountdownSeconds = 60

/**
 * 网易云音乐登录页：手机号 / 扫码 / 网页版三种方式并列。
 *
 * 手机号与扫码走 eapi（匿名请求），成功后由数据层把 MUSIC_U 写进 [NeteaseAuthStore]；
 * 网页版内嵌桌面站，从 CookieManager 提取凭据后同样写进 store。
 * 三条路径最终都以 RESULT_OK + cookie json 返回给调用方。
 */
internal class NeteaseLoginActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var hasReturned = false
    private lateinit var authStore: NeteaseAuthStore
    private lateinit var router: OnlineMusicRepositoryRouter

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false
        val container = MusicAppContainer.getInstance(this)
        authStore = container.neteaseAuthStore
        router = container.onlineRepositoryRouter
        setResult(Activity.RESULT_CANCELED)

        setContent {
            NeteaseLoginPage(
                router = router,
                onBack = { handleBackNavigation() },
                onLoggedIn = { returnCookiesIfLoggedIn(showError = true) },
                onFinishWebLogin = { finishWebLogin() },
                ensureWebView = { ensureWebView() },
                releaseWebView = { releaseWebView() },
            )
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackNavigation()
                }
            },
        )
    }

    override fun onDestroy() {
        releaseWebView()
        super.onDestroy()
    }

    private fun handleBackNavigation() {
        val view = webView
        if (view != null && view.canGoBack()) {
            view.goBack()
        } else {
            finish()
        }
    }

    /** 网页版收尾：把 WebView 里的 Cookie 提出来存进 store，再统一回传。 */
    private fun finishWebLogin() {
        val cookies = collectWebViewCookies()
        if (!authStore.saveCookies(cookies)) {
            Toast.makeText(this, R.string.netease_login_cookie_missing, Toast.LENGTH_SHORT).show()
            return
        }
        returnCookiesIfLoggedIn(showError = true)
    }

    private fun returnCookiesIfLoggedIn(showError: Boolean): Boolean {
        if (hasReturned) {
            return true
        }
        val validation = authStore.validateCookies(authStore.getCookies())
        if (!validation.isAccepted) {
            if (showError) {
                Toast.makeText(this, R.string.netease_login_cookie_missing, Toast.LENGTH_SHORT).show()
            }
            return false
        }
        hasReturned = true
        val cookieJson = JSONObject().also { root ->
            validation.cookies.forEach { (key, value) -> root.put(key, value) }
        }.toString()
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(ExtraCookieJson, cookieJson),
        )
        finish()
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        seedSavedCookies(cookieManager)
        val created = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                allowFileAccess = false
                allowContentAccess = false
                userAgentString = DesktopUserAgent
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
            webChromeClient = WebChromeClient()
            webViewClient =
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean = !isAllowedLoginUri(request?.url)

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        CookieManager.getInstance().flush()
                    }
                }
            loadUrl(TargetUrl)
        }
        cookieManager.setAcceptThirdPartyCookies(created, true)
        webView = created
        return created
    }

    private fun releaseWebView() {
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
    }

    private fun collectWebViewCookies(): Map<String, String> {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        val currentUrl = webView?.url
        return buildList {
            currentUrl?.takeIf(String::isNotBlank)?.let(::add)
            addAll(CookieReadUrls)
        }
            .distinct()
            .mapNotNull { url -> cookieManager.getCookie(url)?.takeIf(String::isNotBlank) }
            .joinToString("; ")
            .let(::parseNeteaseCookieHeader)
    }

    private fun seedSavedCookies(cookieManager: CookieManager) {
        val cookies = authStore.getCookies()
        if (cookies.isEmpty()) {
            return
        }
        for (url in CookieReadUrls) {
            cookies.forEach { (key, value) ->
                cookieManager.setCookie(url, "$key=$value; Path=/")
            }
        }
        cookieManager.flush()
    }

    private fun isAllowedLoginUri(uri: Uri?): Boolean {
        val target = uri ?: return false
        if (target.toString() == "about:blank") {
            return true
        }
        if (!target.scheme.equals("https", ignoreCase = true)) {
            return false
        }
        val host = target.host?.lowercase().orEmpty()
        return AllowedLoginDomains.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }

    companion object {
        const val ExtraCookieJson = "com.smartisan.music.extra.NETEASE_COOKIE_JSON"

        fun createIntent(context: Context): Intent {
            return Intent(context, NeteaseLoginActivity::class.java)
        }

        private const val TargetUrl = "https://music.163.com/"
        private val CookieReadUrls = listOf(
            "https://music.163.com/",
            "https://music.163.com",
            "https://interface.music.163.com/",
            "https://interface3.music.163.com/",
        )
        private val AllowedLoginDomains = setOf(
            "163.com",
            "126.net",
            "163yun.com",
        )
        private const val DesktopUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
    }
}

/** 登录页骨架：标题栏 + 三枚并列 tab + 对应内容。 */
@Composable
private fun NeteaseLoginPage(
    router: OnlineMusicRepositoryRouter,
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
    onFinishWebLogin: () -> Unit,
    ensureWebView: () -> WebView,
    releaseWebView: () -> Unit,
) {
    var method by remember { mutableStateOf(NeteaseLoginMethod.Phone) }
    // 进入登录页时清一次上一轮的会话残留，之后整页内不再清（见 beginLoginSession 注释）。
    LaunchedEffect(Unit) {
        router.beginLoginSession()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(R.color.page_background)),
    ) {
        SmartisanTitleBar(
            title = stringResource(R.string.netease_login_title),
            navigationIcon = SmartisanTitleBarAction(
                iconRes = R.drawable.standard_icon_back_selector,
                contentDescription = stringResource(R.string.back),
                onClick = onBack,
            ),
            // 网页版沿用原交互：用户自行登录后点「完成」提取 Cookie。
            action = if (method == NeteaseLoginMethod.Web) {
                SmartisanTitleBarAction(
                    iconRes = R.drawable.standard_icon_complete_selector,
                    contentDescription = stringResource(R.string.done),
                    onClick = onFinishWebLogin,
                )
            } else {
                null
            },
        )
        NeteaseLoginMethodBar(
            selected = method,
            onSelect = { method = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (method) {
                NeteaseLoginMethod.Phone -> PhoneLoginPane(
                    router = router,
                    onLoggedIn = onLoggedIn,
                )
                NeteaseLoginMethod.QrCode -> QrCodeLoginPane(
                    router = router,
                    onLoggedIn = onLoggedIn,
                )
                NeteaseLoginMethod.Web -> WebLoginPane(
                    ensureWebView = ensureWebView,
                    releaseWebView = releaseWebView,
                )
            }
        }
    }
}

/** 三枚并列 tab（选中态与云音乐日推分段控件一致）。 */
@Composable
private fun NeteaseLoginMethodBar(
    selected: NeteaseLoginMethod,
    onSelect: (NeteaseLoginMethod) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = mapOf(
        NeteaseLoginMethod.Phone to R.string.netease_login_tab_phone,
        NeteaseLoginMethod.QrCode to R.string.netease_login_tab_qrcode,
        NeteaseLoginMethod.Web to R.string.netease_login_tab_web,
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NeteaseLoginMethod.entries.forEach { entry ->
            val active = entry == selected
            Box(
                modifier = Modifier
                    .height(32.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (active) {
                            colorResource(R.color.btn_text_color_red).copy(alpha = 0.12f)
                        } else {
                            colorResource(R.color.tab_bar_top_background)
                        }
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = smartisanClick { onSelect(entry) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(labels.getValue(entry)),
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = if (active) {
                            colorResource(R.color.btn_text_color_red)
                        } else {
                            colorResource(R.color.title_text_color)
                        },
                    ),
                )
            }
        }
    }
}

/** 手机号 + 短信验证码登录。 */
@Composable
private fun PhoneLoginPane(
    router: OnlineMusicRepositoryRouter,
    onLoggedIn: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var smsCode by remember { mutableStateOf("") }
    var countdown by remember { mutableIntStateOf(0) }
    var submitting by remember { mutableStateOf(false) }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1_000L)
            countdown -= 1
        }
    }

    val phoneValid = phone.length == 11 && phone.all(Char::isDigit)
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        SmartisanEditor(
            value = phone,
            onValueChange = { input -> phone = input.filter(Char::isDigit).take(11) },
            placeholder = stringResource(R.string.netease_login_phone_hint),
            keyboardType = KeyboardType.Phone,
        )
        Spacer(modifier = Modifier.height(12.dp))
        SmartisanEditor(
            value = smsCode,
            onValueChange = { input -> smsCode = input.filter(Char::isDigit).take(6) },
            placeholder = stringResource(R.string.netease_login_sms_hint),
            keyboardType = KeyboardType.NumberPassword,
            trailing = {
                val enabled = phoneValid && countdown == 0
                Text(
                    text = if (countdown > 0) {
                        stringResource(R.string.netease_login_send_sms_countdown, countdown)
                    } else {
                        stringResource(R.string.netease_login_send_sms)
                    },
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = if (enabled) {
                            colorResource(R.color.btn_text_color_red)
                        } else {
                            colorResource(R.color.editor_hint_text_color)
                        },
                    ),
                    modifier = Modifier.clickable(
                        enabled = enabled,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = smartisanClick {
                            scope.launch {
                                val outcome = router.sendLoginSmsCode(phone)
                                if (outcome.status == NeteaseAccountActionStatus.Success) {
                                    countdown = SmsCountdownSeconds
                                    Toast.makeText(
                                        context,
                                        R.string.netease_login_sms_sent,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        R.string.netease_login_failed,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                    ),
                )
            },
        )
        Spacer(modifier = Modifier.height(24.dp))
        SmartisanDialogButton(
            text = stringResource(R.string.netease_login_submit),
            enabled = phoneValid && smsCode.length >= 4 && !submitting,
            onClick = {
                submitting = true
                scope.launch {
                    val outcome = router.loginWithPhone(phone, smsCode)
                    submitting = false
                    if (outcome.status == NeteaseAccountActionStatus.Success) {
                        onLoggedIn()
                    } else {
                        // 服务端原话（如「验证码错误」「环境风险」）比笼统的失败更有诊断价值。
                        val reason = outcome.message
                            ?.takeIf(String::isNotBlank)
                            ?: context.getString(R.string.netease_login_failed)
                        Toast.makeText(context, reason, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 扫码登录：展示二维码并轮询状态，成功后自动收尾。 */
@Composable
private fun QrCodeLoginPane(
    router: OnlineMusicRepositoryRouter,
    onLoggedIn: () -> Unit,
) {
    var unikey by remember { mutableStateOf<String?>(null) }
    var statusRes by remember { mutableStateOf<Int?>(R.string.netease_login_qr_hint) }
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTick) {
        unikey = null
        statusRes = R.string.netease_login_qr_hint
        val result = router.getLoginQrKey()
        unikey = result.unikey
        if (result.unikey == null) {
            statusRes = R.string.netease_login_qr_failed
        }
    }

    // 轮询：key 变化即重开；只要状态还会变就继续，进入终态一律停止（否则风控码会让它空转到二维码过期）。
    LaunchedEffect(unikey) {
        val key = unikey ?: return@LaunchedEffect
        while (true) {
            delay(QrPollIntervalMs)
            val result = router.pollLoginQrStatus(key)
            statusRes = when (result.status) {
                NeteaseQrLoginStatus.WaitingScan -> R.string.netease_login_qr_hint
                NeteaseQrLoginStatus.WaitingConfirm -> R.string.netease_login_qr_scanned
                NeteaseQrLoginStatus.Expired -> R.string.netease_login_qr_expired
                NeteaseQrLoginStatus.RiskRejected -> R.string.netease_login_qr_risk_rejected
                else -> R.string.netease_login_qr_failed
            }
            if (result.status.isPending) {
                continue
            }
            if (result.status == NeteaseQrLoginStatus.Success) {
                val outcome = router.completeQrLogin()
                if (outcome.status == NeteaseAccountActionStatus.Success) {
                    onLoggedIn()
                } else {
                    statusRes = R.string.netease_login_qr_failed
                }
            }
            return@LaunchedEffect
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        val key = unikey
        Box(
            modifier = Modifier.clickable(
                enabled = key == null,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = smartisanClick { refreshTick += 1 },
            ),
        ) {
            NeteaseLoginQrCode(
                content = key?.let { "https://music.163.com/login?codekey=$it" }.orEmpty(),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        statusRes?.let { textRes ->
            Text(
                text = stringResource(textRes),
                style = TextStyle(
                    fontSize = 14.sp,
                    color = colorResource(R.color.title_text_color),
                ),
            )
        }
    }
}

/** 网页版登录：内嵌桌面版 music.163.com，与原实现一致，作为兜底入口。 */
@Composable
private fun WebLoginPane(
    ensureWebView: () -> WebView,
    releaseWebView: () -> Unit,
) {
    DisposableEffect(Unit) {
        onDispose { releaseWebView() }
    }
    AndroidView(
        factory = { ensureWebView() },
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars),
    )
}
