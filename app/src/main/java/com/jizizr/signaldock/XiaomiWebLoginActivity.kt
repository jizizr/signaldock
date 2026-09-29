package com.jizizr.signaldock

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.jizizr.signaldock.XiaomiWebLoginView.Stage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Official web login; only a session that passes actual FAST recognition is saved. */
class XiaomiWebLoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var loginView: XiaomiWebLoginView
    private var preparing = false
    private var exchanging = false
    private var completed = false

    @SuppressLint("SetJavaScriptEnabled") // Xiaomi's first-party login page requires JavaScript.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root = layoutInflater.inflate(
            R.layout.activity_xiaomi_web_login, findViewById<ViewGroup>(android.R.id.content), false
        ).apply {
            setOnApplyWindowInsetsListener { view, insets ->
                val safeArea = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
                )
                view.setPadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom)
                loginView.setKeyboardVisible(insets.isVisible(WindowInsets.Type.ime()))
                insets
            }
        }
        loginView = XiaomiWebLoginView(root, onRetry = ::prepareLogin, onClose = ::finish)
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
                        if (!exchanging && !completed && loginView.stage != Stage.FAILED) {
                            loginView.show(Stage.READY, getString(R.string.login_unsupported_redirect))
                        }
                    }
                    return !allowed
                }
                override fun onPageCommitVisible(view: WebView, url: String) {
                    if (!exchanging && !completed && loginView.stage != Stage.FAILED && isXiaomiLoginUrlAllowed(url)) {
                        loginView.show(Stage.READY)
                        AppLog.i("XiaomiWebLogin", "WEB_LOGIN_VISIBLE viewport=${view.width}x${view.height}")
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (isXiaomiLoginUrlAllowed(url)) tryExchange()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && !exchanging && !completed) {
                        loginView.show(Stage.FAILED, getString(R.string.login_network_error))
                        AppLog.w("XiaomiWebLogin", "Web login load failed: code=${error.errorCode}")
                    }
                }
            }
        }
        // Keep the browser attached to a native parent for its entire Activity lifetime.
        // Do not let a conditional Compose mount race with page initialization:
        // both loading and restoration wait for attachment and a nonzero viewport.
        root.findViewById<FrameLayout>(R.id.login_browser).addView(
            web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        root.requestApplyInsets()
        if (savedInstanceState == null) XiaomiWebLoginCoordinator.reset()
        lifecycleScope.launch {
            XiaomiWebLoginCoordinator.state.collect { state ->
                when (state) {
                    LoginVerificationRunner.State.Idle -> Unit
                    LoginVerificationRunner.State.Running -> {
                        exchanging = true
                        web.visibility = View.INVISIBLE
                        loginView.show(Stage.VERIFYING)
                        WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime())
                    }
                    LoginVerificationRunner.State.Success -> {
                        exchanging = false
                        completed = true
                        web.visibility = View.INVISIBLE
                        loginView.show(Stage.SUCCESS)
                    }
                    is LoginVerificationRunner.State.Failed -> {
                        exchanging = false
                        completed = true
                        web.visibility = View.INVISIBLE
                        loginView.show(Stage.FAILED, state.message)
                    }
                }
            }
        }
        if (XiaomiWebLoginCoordinator.state.value != LoginVerificationRunner.State.Idle) return
        if (savedInstanceState != null) {
            withBrowserViewport {
                if (web.restoreState(savedInstanceState) != null) {
                    loginView.show(Stage.READY)
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
        loginView.show(Stage.PREPARING)
        XiaomiWebLoginCoordinator.reset()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { XiaomiPassportClient.checkSupported(applicationContext) }
                loginView.show(Stage.LOADING)
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
                loginView.show(Stage.FAILED, getString(R.string.login_config_error))
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
}
