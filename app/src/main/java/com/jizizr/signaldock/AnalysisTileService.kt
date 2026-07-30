package com.jizizr.signaldock

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.Shizuku

/**
 * AnalysisTileService  Quick Settings Action Tile
 *
 * 点击后执行一次动作，不保持后台状态。
 *
 * 截图优先级：
 *   ① 无障碍截图服务已运行  unlockAndRun  延迟截图
 *   ② Shizuku 已授权  后台开启无障碍  截图
 *   ③ 权限、AI 配置或 Shizuku 不可用  显示原因并打开主页修复
 *
 * 连接管理完全委托给 [AppShell]，本 Tile 不持有任何 ServiceConnection，
 * 因此不会产生孤立的 :shell 进程。
 */
class AnalysisTileService : TileService() {

    companion object {
        private const val TAG = "AnalysisTileService"
        private const val UNLOCK_CALLBACK_TIMEOUT_MS = 1500L
    }

    // -----------------------------------------------------------------------
    //  Shizuku 事件监听（仅在面板展开时注册，面板收起时移除）
    // -----------------------------------------------------------------------

    private val shizukuBinderReceived = Shizuku.OnBinderReceivedListener { updateTileLabel() }
    private val shizukuBinderDead = Shizuku.OnBinderDeadListener { updateTileLabel() }
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var pendingTapStartedAtMs = 0L

    // -----------------------------------------------------------------------
    //  TileService 生命周期
    // -----------------------------------------------------------------------

    override fun onStartListening() {
        super.onStartListening()
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceived)
        Shizuku.addBinderDeadListener(shizukuBinderDead)
        updateTileLabel()
    }

    override fun onStopListening() {
        Shizuku.removeBinderReceivedListener(shizukuBinderReceived)
        Shizuku.removeBinderDeadListener(shizukuBinderDead)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        pendingTapStartedAtMs = SystemClock.elapsedRealtime()
        AppLog.i(TAG, "Tile clicked")
        setTileLabel("正在启动…")
        val actionHandled = AtomicBoolean(false)
        val action = Runnable {
            if (!actionHandled.compareAndSet(false, true)) return@Runnable
            runCatching(::handleTileClick)
                .onFailure { error ->
                    AppLog.e(TAG, "Tile action failed", error)
                    openMainApp(R.string.tile_action_failed)
                }
        }
        mainHandler.postDelayed({
            if (actionHandled.compareAndSet(false, true)) {
                AppLog.w(TAG, "unlockAndRun callback timed out")
                openMainApp(R.string.tile_action_failed)
            }
        }, UNLOCK_CALLBACK_TIMEOUT_MS)
        runCatching {
            unlockAndRun(action)
        }.onFailure { error ->
            AppLog.e(TAG, "Unable to collapse Quick Settings", error)
            if (actionHandled.compareAndSet(false, true)) {
                openMainApp(R.string.tile_action_failed)
            }
        }
    }

    private fun handleTileClick() {
        AppLog.d(
            TAG,
            "Tile unlocked  a11y=${AccessibilityScreenshotService.instance != null} " +
                "shell=${AppShell.isShizukuAvailable}",
        )

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            openMainApp(R.string.tile_notification_permission_required)
            return
        }

        val aiSettings = AiSettingsStore.runtimeSnapshot()
        val miclawSessionAvailable = runCatching {
            MiclawSessionStore.load()?.isUsable == true
        }.getOrDefault(false)
        if (!isAiConfigurationReady(
                usesMiclaw = aiSettings.usesMiclaw,
                miclawUseExternalAgent = aiSettings.miclawUseExternalAgent,
                miclawSessionAvailable = miclawSessionAvailable,
                apiKey = aiSettings.apiKey,
                connection = aiSettings.connection,
            )
        ) {
            openMainApp(R.string.tile_ai_configuration_required)
            return
        }

        val a11ySvc = AccessibilityScreenshotService.instance

        when {
            // ① 无障碍就绪，不依赖 Shizuku 即可截图
            a11ySvc != null -> {
                setTileLabel("截图中…")
                scheduleScreenshot()
            }

            // ② Shell 已连接 → 后台开启无障碍后截图
            AppShell.isShizukuAvailable -> {
                setTileLabel("正在开启无障碍…")
                enableAccessibilityAndShoot()
            }

            // Shizuku 可用但未授权：由主页的稳定授权流程处理。
            shizukuAvailable() -> {
                openMainApp(R.string.tile_shizuku_permission_required)
            }

            else -> openMainApp(R.string.tile_shizuku_unavailable)
        }
    }

    // -----------------------------------------------------------------------
    //  Private helpers
    // -----------------------------------------------------------------------

    /** Lets the long-lived accessibility service wait for SystemUI to close. */
    private fun scheduleScreenshot() {
        val service = AccessibilityScreenshotService.instance
        if (service == null) {
            AppLog.w(TAG, "a11y instance gone before screenshot")
            openMainApp(R.string.tile_accessibility_unavailable)
            return
        }
        service.scheduleScreenshot(pendingTapStartedAtMs)
        mainHandler.postDelayed({ updateTileLabel() }, 2000)
    }

    /**
     * 通过 [AppShell] 开启无障碍服务，等待其就绪后截图。
     * 调用时 AppShell.service 必须已连接。
     */
    private fun enableAccessibilityAndShoot() {
        AppShell.enableAccessibility(this) { ok ->
            // onDone 在主线程
            if (!ok) {
                openMainApp(R.string.tile_accessibility_enable_failed)
                return@enableAccessibility
            }
            var waited = 0
            val poll = object : Runnable {
                override fun run() {
                    val svc = AccessibilityScreenshotService.instance
                    when {
                        svc != null -> {
                            setTileLabel("截图中…")
                            scheduleScreenshot()
                        }
                        waited < 6000 -> {
                            waited += 500
                            mainHandler.postDelayed(this, 500)
                        }
                        else -> {
                            AppLog.w(TAG, "A11y service timeout")
                            openMainApp(R.string.tile_accessibility_unavailable)
                        }
                    }
                }
            }
            mainHandler.postDelayed(poll, 500)
        }
    }

    private fun updateTileLabel() = setTileLabel("截图分析")

    private fun openMainApp(messageRes: Int) {
        val message = getString(messageRes)
        setTileLabel(message)
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            messageRes,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { startActivityAndCollapse(pendingIntent) }
            .onFailure { error ->
                AppLog.e(TAG, "Unable to open setup screen", error)
                runCatching { pendingIntent.send() }
                    .onFailure { sendError ->
                        AppLog.e(TAG, "Setup screen fallback failed", sendError)
                    }
            }
    }

    private fun setTileLabel(label: String) {
        qsTile?.apply { state = Tile.STATE_INACTIVE; this.label = label; updateTile() }
    }

    private fun shizukuAvailable() =
        try { Shizuku.pingBinder() } catch (_: Exception) { false }

}
