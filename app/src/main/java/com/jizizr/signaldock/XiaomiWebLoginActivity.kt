package com.jizizr.signaldock

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.jizizr.signaldock.ui.theme.SignaldockTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Official web login; only a session that passes actual FAST recognition is saved. */
class XiaomiWebLoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var statusText by mutableStateOf("正在检查网页登录适配…")
    private var webVisible by mutableStateOf(false)
    private var retryAvailable by mutableStateOf(false)
    private var exchanging = false
    private var completed = false
    private var preparing = false

    @SuppressLint("SetJavaScriptEnabled") // Xiaomi's first-party login page requires JavaScript.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val allowed = isXiaomiLoginUrlAllowed(request.url.toString())
                    if (!allowed) {
                        tryExchange()
                        if (!exchanging && !completed) {
                            statusText = "此登录跳转暂不支持，请留在小米官方页面完成登录"
                        }
                    }
                    return !allowed
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (isXiaomiLoginUrlAllowed(url)) tryExchange()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && !exchanging && !completed) {
                        statusText = "登录页面加载失败，请检查网络后重试"
                        retryAvailable = true
                    }
                }
            }
        }
        setContent {
            SignaldockTheme {
                Scaffold(topBar = {
                    SmallTopAppBar(
                        title = "小米账号登录",
                        navigationIcon = {
                            IconButton(onClick = ::finish) {
                                Icon(MiuixIcons.Back, contentDescription = getString(R.string.back))
                            }
                        },
                        actions = {
                            if (retryAvailable) {
                                TextButton(text = "重试", onClick = { prepareLogin() })
                            }
                        },
                    )
                }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Text(
                            text = statusText,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                        )
                        if (webVisible) {
                            AndroidView(factory = { web }, modifier = Modifier.fillMaxWidth().weight(1f))
                        } else if (completed) {
                            TextButton(
                                text = "返回信岛",
                                onClick = ::finish,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                    }
                }
            }
        }
        if (savedInstanceState == null) XiaomiWebLoginCoordinator.reset()
        lifecycleScope.launch {
            XiaomiWebLoginCoordinator.state.collect { state ->
                when (state) {
                    LoginVerificationRunner.State.Idle -> Unit
                    LoginVerificationRunner.State.Running -> {
                        exchanging = true
                        webVisible = false
                        retryAvailable = false
                        statusText = "登录成功，正在验证识别能力。你可以返回信岛，验证通过后会自动保存连接。"
                    }
                    LoginVerificationRunner.State.Success -> {
                        exchanging = false
                        completed = true
                        webVisible = false
                        retryAvailable = false
                        statusText = "连接已保存，可使用取餐码识别和快速模式，无需 Root。专家模式仍需单独授权。"
                    }
                    is LoginVerificationRunner.State.Failed -> {
                        exchanging = false
                        completed = true
                        webVisible = false
                        retryAvailable = true
                        statusText = state.message
                    }
                }
            }
        }
        if (XiaomiWebLoginCoordinator.state.value != LoginVerificationRunner.State.Idle) return
        if (savedInstanceState != null && web.restoreState(savedInstanceState) != null) {
            webVisible = true
            statusText = LOGIN_INSTRUCTIONS
        } else {
            prepareLogin()
        }
    }

    private fun prepareLogin() {
        if (preparing || exchanging) return
        preparing = true
        completed = false
        webVisible = false
        retryAvailable = false
        XiaomiWebLoginCoordinator.reset()
        statusText = "正在检查网页登录适配…"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { XiaomiPassportClient.checkSupported(applicationContext) }
                statusText = LOGIN_INSTRUCTIONS
                AppLog.i("XiaomiWebLogin", "WEB_LOGIN_READY")
                CookieManager.getInstance().removeAllCookies {
                    if (!isDestroyed && !isFinishing) {
                        webVisible = true
                        web.loadUrl(XiaomiPassportClient.LOGIN_URL)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                statusText = "当前小爱版本的网页登录适配检查未通过，原有连接未改变"
                retryAvailable = true
            } finally {
                preparing = false
            }
        }
    }

    private fun tryExchange() {
        if (exchanging || completed || isFinishing) return
        val cookies = CookieManager.getInstance()
        val credential = xiaomiWebServiceCredential(
            cookies.getCookie("https://account.ai.xiaomi.com"),
            cookies.getCookie("https://account.xiaomi.com"),
        ) ?: return
        exchanging = true
        webVisible = false
        XiaomiWebLoginCoordinator.begin(applicationContext, credential)
        clearWebCookies()
    }

    private fun clearWebCookies() {
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies { cookies.flush() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (webVisible && !exchanging) web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        web.stopLoading()
        web.destroy()
        if (isFinishing) clearWebCookies()
        super.onDestroy()
    }

    private companion object {
        const val LOGIN_INSTRUCTIONS = "请在下方小米官方页面登录。登录后将使用内置测试图验证识别能力，验证通过后保存连接。"
    }
}
