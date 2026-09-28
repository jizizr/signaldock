package com.jizizr.signaldock

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Official web login; only a session that passes actual FAST recognition is saved. */
class XiaomiWebLoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
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
        root.addView(status)
        root.addView(close)
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
                override fun onPageFinished(view: WebView, url: String) {
                    if (isXiaomiLoginUrlAllowed(url)) tryExchange()
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()
        XiaomiWebLoginCoordinator.reset()
        lifecycleScope.launch {
            XiaomiWebLoginCoordinator.state.collect { state ->
                when (state) {
                    LoginVerificationRunner.State.Idle -> Unit
                    LoginVerificationRunner.State.Running -> {
                        exchanging = true
                        web.visibility = View.INVISIBLE
                        status.text = "登录已完成，正在验证快速识别。返回信岛后验证仍会继续，成功后自动保存连接。"
                    }
                    LoginVerificationRunner.State.Success -> {
                        exchanging = false
                        completed = true
                        web.visibility = View.INVISIBLE
                        status.text = "网页登录及快速识别验证通过：取餐码 5312、芭乐奶绿。连接已保存。专家模式尚未通过此登录方式验证。"
                    }
                    is LoginVerificationRunner.State.Failed -> {
                        exchanging = false
                        completed = true
                        web.visibility = View.INVISIBLE
                        status.text = state.message
                    }
                }
            }
        }
        if (XiaomiWebLoginCoordinator.state.value == LoginVerificationRunner.State.Running) return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { XiaomiPassportClient.checkSupported(applicationContext) }
                status.text = "请在下方小米官方页面登录。随后仅发送内置取餐码测试图验证识别；不会导入系统账号数据。"
                AppLog.i("XiaomiWebLogin", "WEB_LOGIN_READY")
                CookieManager.getInstance().removeAllCookies {
                    if (!isDestroyed && !isFinishing) web.loadUrl(XiaomiPassportClient.LOGIN_URL)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Throwable) {
                status.text = "当前小爱版本的网页登录适配检查未通过，原有连接未改变"
                AppLog.w("XiaomiWebLogin", "Web login configuration failed: ${error.javaClass.simpleName}")
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

    override fun onDestroy() {
        web.stopLoading()
        web.destroy()
        clearWebCookies()
        super.onDestroy()
    }
}
