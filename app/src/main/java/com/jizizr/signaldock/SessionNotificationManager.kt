package com.jizizr.signaldock

import android.app.Notification
import android.app.PendingIntent
import android.content.Context

data class SessionNotificationResult(
    val title: String,
    val details: String,
    val label: String = "",
    val price: String = "",
    val actionText: String = "已完成",
    val item: String = "",
    val itemDetail: String = "",
    val merchant: String = "",
    val hasQrBitmap: Boolean = false,
)

/**
 * 通知提供器接口。
 *
 * [StandardNotificationManager] 和 [SuperIslandManager] 分别实现，
 * [LiveUpdateService] 只依赖本接口，与具体通知样式完全解耦。
 */
interface SessionNotificationManager {

    /** 将 session ID 映射为实际通知 ID */
    fun notificationIdForSession(sessionId: Int): Int

    /**
     * 发送"识别中"通知，并在通知就绪时回调 [onForegroundReady]。
     * LiveUpdateService 在回调中调用 startForeground，确保前台绑定在通知发出之后。
     *
     * 标准路径：同步回调（主线程）。
     * 超级岛路径：在 bypass 窗口内异步回调（executor 线程）——此时 xmsf 网络已禁用，
     * startForeground 触发的通知被 xmsf 以岛样式处理。
     */
    fun sendRecognizingNotification(
        context: Context,
        sessionId: Int,
        onForegroundReady: (notifId: Int, notif: Notification) -> Unit,
    )

    /** 发送/更新结果通知（允许异步） */
    fun sendResultNotification(
        context: Context,
        sessionId: Int,
        result: SessionNotificationResult,
        dismissIntent: PendingIntent,
    )

    /**
     * 将服务前台锚点转移到另一个 session 的通知。
     * 实现者负责以正确时机（bypass 窗口内）调用 [onReady]，
     * LiveUpdateService 在回调中调用 startForeground。
     */
    fun transferForeground(
        context: Context,
        sessionId: Int,
        onReady: (notifId: Int, notif: Notification) -> Unit,
    )

    /** 取消该 session 的所有通知 */
    fun cancelForSession(context: Context, sessionId: Int)

    /** 服务销毁时调用（释放资源、后台进程等） */
    fun onServiceDestroy(context: Context)

    /** 注册所需通知渠道 */
    fun ensureChannels(context: Context)
}
