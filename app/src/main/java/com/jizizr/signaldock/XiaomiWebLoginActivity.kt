package com.jizizr.signaldock

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.doOnAttach
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Official web login; only a session that passes actual FAST recognition is saved. */
class XiaomiWebLoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var retry: Button
    private var preparing = false
    private var exchanging = false
    private var completed = false

    @SuppressLint("SetJavaScriptEnabled") // Xiaomi's first-party login page requires JavaScript.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.WHITE)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
                insets
            }
        }
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.BLACK)
            contentDescription = "小爱网页登录状态"
            text = "正在检查网页登录适配…"
        }
        val close = Button(this).apply { text = "返回信岛"; setOnClickListener { finish() } }
        retry = Button(this).apply {
            text = "重试"
            visibility = View.GONE
            setOnClickListener { prepareLogin() }
        }
        root.addView(status)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(close, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(retry, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
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
                        if (!exchanging && !completed) status.text = "此登录跳转暂不支持，请留在小米官方页面完成登录"
                    }
                    return !allowed
                }
                override fun onPageCommitVisible(view: WebView, url: String) {
                    if (!exchanging && !completed && isXiaomiLoginUrlAllowed(url)) {
                        status.text = LOGIN_INSTRUCTIONS
                        retry.visibility = View.GONE
                        AppLog.i("XiaomiWebLogin", "WEB_LOGIN_VISIBLE viewport=${view.width}x${view.height}")
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (isXiaomiLoginUrlAllowed(url)) tryExchange()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && !exchanging && !completed) {
                        status.text = "登录页面加载失败，请检查网络后重试"
                        retry.visibility = View.VISIBLE
                        AppLog.w("XiaomiWebLogin", "Web login load failed: code=${error.errorCode}")
                    }
                }
            }
        }
        // Keep the browser attached to a native parent for its entire Activity lifetime.
        // Do not let a conditional Compose mount race with page initialization:
        // both loading and restoration wait for attachment and a nonzero viewport.
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()
        if (savedInstanceState == null) XiaomiWebLoginCoordinator.reset()
        lifecycleScope.launch {
            XiaomiWebLoginCoordinator.state.collect { state ->
                when (state) {
                    LoginVerificationRunner.State.Idle -> Unit
                    LoginVerificationRunner.State.Running -> {
                        exchanging = true
                        web.visibility = View.INVISIBLE
                        retry.visibility = View.GONE
                        status.text = "登录成功，正在验证识别能力。你可以返回信岛，验证通过后会自动保存连接。"
                    }
                    LoginVerificationRunner.State.Success -> {
                        exchanging = false
                        completed = true
                        web.visibility = View.INVISIBLE
                        retry.visibility = View.GONE
                        status.text = "连接已保存，可使用取餐码识别和快速模式，无需 Root。专家模式仍需单独授权。"
                    }
                    is LoginVerificationRunner.State.Failed -> {
                        exchanging = false
                        completed = true
                        web.visibility = View.INVISIBLE
                        retry.visibility = View.VISIBLE
                        status.text = state.message
                    }
                }
            }
        }
        if (XiaomiWebLoginCoordinator.state.value != LoginVerificationRunner.State.Idle) return
        if (savedInstanceState != null) {
            withBrowserViewport {
                if (web.restoreState(savedInstanceState) != null) {
                    status.text = LOGIN_INSTRUCTIONS
                } else {
                    prepareLogin()
                }
            }
        } else {
            prepareLogin()
        }
    }

    private fun prepareLogin() {
        if (preparing || exchanging || isFinishing || isDestroyed) return
        preparing = true
        completed = false
        retry.visibility = View.GONE
        status.text = "正在检查网页登录适配…"
        XiaomiWebLoginCoordinator.reset()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { XiaomiPassportClient.checkSupported(applicationContext) }
                status.text = "正在加载小米官方登录页…"
                AppLog.i("XiaomiWebLogin", "WEB_LOGIN_READY")
                CookieManager.getInstance().removeAllCookies {
                    withBrowserViewport {
                        web.visibility = View.VISIBLE
                        AppLog.i("XiaomiWebLogin", "WEB_LOGIN_LOAD attached=${web.isAttachedToWindow} viewport=${web.width}x${web.height}")
                        web.loadUrl(XiaomiPassportClient.LOGIN_URL)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status.text = "当前小爱版本的网页登录适配检查未通过，原有连接未改变"
                retry.visibility = View.VISIBLE
                AppLog.w("XiaomiWebLogin", "Web login configuration failed: ${error.javaClass.simpleName}")
            } finally {
                preparing = false
            }
        }
    }

    private fun withBrowserViewport(action: () -> Unit) {
        if (isDestroyed || isFinishing) return
        web.doOnAttach {
            web.doOnLayout {
                if (!isDestroyed && !isFinishing && web.width > 0 && web.height > 0) action()
            }
        }
    }

    private fun tryExchange() {
        if (exchanging || completed || isFinishing) return
        val cookies = CookieManager.getInstance()
        val credential = xiaomiWebServiceCredential(cookies.getCookie("https://account.ai.xiaomi.com"),
            cookies.getCookie("https://account.xiaomi.com")) ?: return
        exchanging = true
        web.visibility = View.INVISIBLE
        XiaomiWebLoginCoordinator.begin(applicationContext, credential)
        clearWebCookies()
    }

    private fun clearWebCookies() {
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies { cookies.flush() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (web.isShown && !exchanging) web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        web.stopLoading()
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        if (isFinishing) clearWebCookies()
        super.onDestroy()
    }

    private companion object {
        const val LOGIN_INSTRUCTIONS = "请在下方小米官方页面登录。登录后将使用内置测试图验证识别能力，验证通过后保存连接。"
    }
}
