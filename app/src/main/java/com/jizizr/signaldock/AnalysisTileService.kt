package com.jizizr.signaldock

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * AnalysisTileService  Quick Settings Action Tile
 *
 * 点击后执行一次动作，不保持后台状态。
 *
 * 截图优先级（全程静默，不跳转 MainActivity）：
 *   ① 无障碍截图服务已运行  unlockAndRun  延迟截图
 *   ② Shell 已连接  后台开启无障碍  截图
 *   ③ Shizuku 已授权但未绑定  按需绑定  → ②
 *   ④ Shizuku 未授权  弹授权对话框
 *
 * 连接管理完全委托给 [AppShell]，本 Tile 不持有任何 ServiceConnection，
 * 因此不会产生孤立的 :shell 进程。
 */
class AnalysisTileService : TileService() {

    companion object {
        private const val TAG = "AnalysisTileService"
        private const val SHIZUKU_REQUEST_CODE = 201
        private const val PANEL_MIN_CLOSE_DELAY_MS = 250L
        private const val PANEL_MAX_CLOSE_DELAY_MS = 500L
        private const val PANEL_READY_POLL_MS = 50L
    }

    // -----------------------------------------------------------------------
    //  Shizuku 事件监听（仅在面板展开时注册，面板收起时移除）
    // -----------------------------------------------------------------------

    private val shizukuPermListener =
        Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code == SHIZUKU_REQUEST_CODE &&
                result == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                // 授权刚完成，触发截图
                enableAccessibilityAndShoot()
            }
        }

    private val shizukuBinderReceived = Shizuku.OnBinderReceivedListener { updateTileLabel() }
    private val shizukuBinderDead     = Shizuku.OnBinderDeadListener     { updateTileLabel() }
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
        Shizuku.addRequestPermissionResultListener(shizukuPermListener)
        updateTileLabel()
    }

    override fun onStopListening() {
        Shizuku.removeBinderReceivedListener(shizukuBinderReceived)
        Shizuku.removeBinderDeadListener(shizukuBinderDead)
        Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        pendingTapStartedAtMs = SystemClock.elapsedRealtime()
        unlockAndRun(::handleTileClick)
    }

    private fun handleTileClick() {
        Log.d(
            TAG,
            "Tile unlocked  a11y=${AccessibilityScreenshotService.instance != null} " +
                "shell=${AppShell.isShizukuAvailable}",
        )

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

            // ④ Shizuku 可用但未授权 → 弹授权对话框
            shizukuAvailable() -> {
                Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
                setTileLabel("等待 Shizuku 授权")
            }

            else -> setTileLabel("请先开启无障碍")
        }
    }

    // -----------------------------------------------------------------------
    //  Private helpers
    // -----------------------------------------------------------------------

    /** Waits for SystemUI's unlockAndRun transition before capturing. */
    private fun scheduleScreenshot() {
        var waitedMs = PANEL_MIN_CLOSE_DELAY_MS
        val captureWhenReady = object : Runnable {
            override fun run() {
                val service = AccessibilityScreenshotService.instance
                if (service == null) {
                    Log.w(TAG, "a11y instance gone before screenshot")
                    return
                }
                if (service.isCaptureTargetReady() || waitedMs >= PANEL_MAX_CLOSE_DELAY_MS) {
                    service.triggerScreenshot(pendingTapStartedAtMs)
                    mainHandler.postDelayed({ updateTileLabel() }, 2000)
                    return
                }
                waitedMs += PANEL_READY_POLL_MS
                mainHandler.postDelayed(this, PANEL_READY_POLL_MS)
            }
        }
        mainHandler.postDelayed(captureWhenReady, PANEL_MIN_CLOSE_DELAY_MS)
    }

    /**
     * 通过 [AppShell] 开启无障碍服务，等待其就绪后截图。
     * 调用时 AppShell.service 必须已连接。
     */
    private fun enableAccessibilityAndShoot() {
        AppShell.enableAccessibility(this) { ok ->
            // onDone 在主线程
            if (!ok) { updateTileLabel(); return@enableAccessibility }
            var waited  = 0
            val poll = object : Runnable {
                override fun run() {
                    val svc = AccessibilityScreenshotService.instance
                    when {
                        svc != null -> {
                            setTileLabel("截图中…")
                            scheduleScreenshot()
                        }
                        waited < 6000 -> { waited += 500; mainHandler.postDelayed(this, 500) }
                        else -> { Log.w(TAG, "A11y service timeout"); updateTileLabel() }
                    }
                }
            }
            mainHandler.postDelayed(poll, 500)
        }
    }

    private fun updateTileLabel() = setTileLabel("截图分析")

    private fun setTileLabel(label: String) {
        qsTile?.apply { state = Tile.STATE_INACTIVE; this.label = label; updateTile() }
    }

    private fun shizukuAvailable() =
        try { Shizuku.pingBinder() } catch (_: Exception) { false }

}
