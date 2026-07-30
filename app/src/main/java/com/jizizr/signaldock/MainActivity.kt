package com.jizizr.signaldock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.jizizr.signaldock.ui.MainScreen
import com.jizizr.signaldock.ui.ShizukuGateScreen
import com.jizizr.signaldock.ui.ShizukuState
import com.jizizr.signaldock.ui.SplashScreen
import com.jizizr.signaldock.ui.theme.SignaldockTheme
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val SHIZUKU_REQUEST_CODE = 101
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_HOME = "https://shizuku.rikka.app/"
    }

    /** Shizuku 状态（Compose 可观察），由下方监听器维护 */
    private val shizuku = ShizukuState()

    private var notificationGranted by mutableStateOf(false)
    private var shizukuPermissionPending by mutableStateOf(false)
    private var shizukuPermissionDenied by mutableStateOf(false)
    private var shizukuPermissionAttempted = false

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationGranted = granted
            AppLog.i(TAG, "POST_NOTIFICATIONS granted=$granted")
        }

    // ── Shizuku 监听 ────────────────────────────────────────────────────────

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code == SHIZUKU_REQUEST_CODE) {
                shizukuPermissionPending = false
                shizukuPermissionDenied = result != PackageManager.PERMISSION_GRANTED
                refreshShizukuState()
            }
        }

    private val shizukuBinderReceived = Shizuku.OnBinderReceivedListener {
        refreshShizukuState()
    }

    private val shizukuBinderDead = Shizuku.OnBinderDeadListener {
        shizuku.available = false
        shizuku.granted = false
        shizuku.uid = -1
        shizukuPermissionPending = false
        shizukuPermissionDenied = false
        shizukuPermissionAttempted = false
    }

    private fun refreshShizukuState() {
        val available = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = available &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)
        shizuku.available = available
        shizuku.granted = granted
        shizuku.uid = if (granted) {
            runCatching { Shizuku.getUid() }.getOrDefault(-1)
        } else {
            -1
        }
        if (granted) {
            shizukuPermissionPending = false
            shizukuPermissionDenied = false
        }
        AppLog.i(TAG, "Shizuku state available=$available granted=$granted uid=${shizuku.uid}")
    }

    private fun requestShizukuPermission() {
        refreshShizukuState()
        if (!shizuku.available || shizuku.granted || shizukuPermissionPending) return

        shizukuPermissionAttempted = true
        shizukuPermissionPending = true
        shizukuPermissionDenied = false
        runCatching { Shizuku.requestPermission(SHIZUKU_REQUEST_CODE) }
            .onFailure { error ->
                shizukuPermissionPending = false
                AppLog.e(TAG, "Failed to request Shizuku permission", error)
            }
    }

    private fun openShizuku() {
        val launchIntent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent)
            return
        }

        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE"))
        runCatching { startActivity(marketIntent) }
            .onFailure {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_HOME)))
            }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // HyperOS 会给导航栏强制加半透明底色，关闭以保持纯净的边到边效果
        window.isNavigationBarContrastEnforced = false

        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceived)
        Shizuku.addBinderDeadListener(shizukuBinderDead)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        notificationGranted = hasNotificationPermission()

        // sticky 监听器在 binder 已存在时会立即回调；这里兜底覆盖未安装 Shizuku 的场景
        refreshShizukuState()

        setContent {
            SignaldockTheme {
                // rememberSaveable：深浅色切换/旋转等配置变更重建 Activity 时不重播开屏动画
                var showSplash by rememberSaveable {
                    mutableStateOf(AppUiSettingsStore.splashAnimationEnabled)
                }
                if (showSplash) {
                    SplashScreen(onFinished = { showSplash = false })
                } else if (!shizuku.ready) {
                    LaunchedEffect(shizuku.available, shizuku.granted) {
                        if (
                            shizuku.available &&
                            !shizuku.granted &&
                            !shizukuPermissionAttempted
                        ) {
                            requestShizukuPermission()
                        }
                    }
                    ShizukuGateScreen(
                        available = shizuku.available,
                        permissionPending = shizukuPermissionPending,
                        permissionDenied = shizukuPermissionDenied,
                        onOpenShizuku = ::openShizuku,
                        onRequestPermission = ::requestShizukuPermission,
                        onRetry = ::refreshShizukuState,
                    )
                } else {
                    MainScreen(
                        shizuku = shizuku,
                        notificationGranted = notificationGranted,
                        onRequestShizuku = ::requestShizukuPermission,
                        onRequestNotification = {
                            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notificationGranted = hasNotificationPermission()
        refreshShizukuState()
    }

    private fun hasNotificationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(shizukuBinderReceived)
        Shizuku.removeBinderDeadListener(shizukuBinderDead)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }
}
