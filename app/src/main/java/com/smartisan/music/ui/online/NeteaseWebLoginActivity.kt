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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.smartisan.music.R
import com.smartisan.music.data.online.NeteaseAuthStore
import com.smartisan.music.data.online.parseNeteaseCookieHeader
import com.smartisan.music.ui.components.SmartisanTitleBar
import com.smartisan.music.ui.components.SmartisanTitleBarAction
import org.json.JSONObject

/**
 * 网易云音乐网页登录页。
 *
 * 内嵌桌面版 music.163.com，用户完成登录后点右上角“完成”，
 * 从 CookieManager 提取 MUSIC_U 等凭据，校验通过后以 RESULT_OK + cookie json 返回。
 * 登录态由调用方写入 [NeteaseAuthStore]。
 */
internal class NeteaseWebLoginActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var authStore: NeteaseAuthStore
    private var hasReturned = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false
        authStore = NeteaseAuthStore(this)
        setResult(Activity.RESULT_CANCELED)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        seedSavedCookies(cookieManager)

        webView = WebView(this).apply {
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
            webViewClient = LoginWebViewClient()
        }
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        setContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.White),
            ) {
                SmartisanTitleBar(
                    title = getString(R.string.netease_login_title),
                    navigationIcon = SmartisanTitleBarAction(
                        iconRes = R.drawable.standard_icon_back_selector,
                        contentDescription = getString(R.string.back),
                        onClick = { handleBackNavigation() },
                    ),
                    action = SmartisanTitleBarAction(
                        iconRes = R.drawable.standard_icon_complete_selector,
                        contentDescription = getString(R.string.done),
                        onClick = { returnCookiesIfLoggedIn(showError = true) },
                    ),
                )
                AndroidView(
                    factory = { webView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                )
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackNavigation()
                }
            },
        )

        webView.loadUrl(TargetUrl)
    }

    override fun onDestroy() {
        if (this::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun handleBackNavigation() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            finish()
        }
    }

    private fun returnCookiesIfLoggedIn(showError: Boolean): Boolean {
        if (hasReturned) {
            return true
        }
        val cookies = readCookieMap()
        val validation = authStore.validateCookies(cookies)
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

    private fun readCookieMap(): Map<String, String> {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        return buildList {
            webView.url?.takeIf(String::isNotBlank)?.let(::add)
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

    private inner class LoginWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            return !isAllowedLoginUri(request?.url)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            CookieManager.getInstance().flush()
        }
    }

    companion object {
        const val ExtraCookieJson = "com.smartisan.music.extra.NETEASE_COOKIE_JSON"

        fun createIntent(context: Context): Intent {
            return Intent(context, NeteaseWebLoginActivity::class.java)
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
