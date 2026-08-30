package com.jizizr.signaldock

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/**
 * 标准实时通知（Live Update）提供器。
 *
 * 实现 [SessionNotificationManager]，封装识别中 / 结果两阶段通知的构建与投递。
 * 通知 ID 与 session ID 一一对应（无偏移）。
 */
object StandardNotificationManager : SessionNotificationManager {

    private const val CHANNEL_ID = "live_update_channel"

    // ── SessionNotificationManager implementation ──────────────────────────

    override fun notificationIdForSession(sessionId: Int): Int = sessionId

    override fun recognitionNotificationIdForSession(sessionId: Int): Int = sessionId

    override fun sendRecognizingNotification(
        context: Context,
        sessionId: Int,
        onForegroundReady: (notifId: Int, notif: Notification) -> Unit,
    ) {
        val notif = buildRecognizingNotif(context, sessionId)
        // 标准路径：同步回调，startForeground 与 notify 合一
        onForegroundReady(sessionId, notif)
    }

    override fun sendResultNotification(
        context: Context,
        sessionId: Int,
        result: SessionNotificationResult,
        dismissIntent: PendingIntent,
        publishDecision: ((notifId: Int, notif: Notification) -> ResultNotificationPublishDecision)?,
    ) {
        context.getSystemService(NotificationManager::class.java)
            .notify(sessionId, buildResultNotif(context, sessionId, result, dismissIntent))
    }

    override fun transferForeground(
        context: Context,
        sessionId: Int,
        onReady: (notifId: Int, notif: Notification) -> Unit,
    ) {
        val notifId = notificationIdForSession(sessionId)
        val existing = context.getSystemService(NotificationManager::class.java)
            .activeNotifications.firstOrNull { it.id == notifId }?.notification
        if (existing != null) onReady(notifId, existing)
    }

    override fun cancelForSession(context: Context, sessionId: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(sessionId)
    }

    override fun onServiceDestroy(context: Context) { /* 标准路径无需释放额外资源 */ }

    override fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "实时内容通知", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "显示截图 OCR 及二维码分析结果（Live Update）"
                }
            )
        }
    }

    // ── Notification builders ───────────────────────────────────────────────

    @SuppressLint("NewApi") // setRequestPromotedOngoing requires API 36.1; minSdk=36+HiddenApiBypass ensures availability
    private fun buildRecognizingNotif(context: Context, sessionId: Int): Notification {
        val stopPi = makeDismissPendingIntent(context, sessionId)
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("正在识别")
            .setContentText("截图分析中，请稍候…")
            .setShortCriticalText("识别中")
            .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    context.getString(R.string.dismiss),
                    stopPi
                ).build()
            )
            .setDeleteIntent(stopPi)
            .also { it.setRequestPromotedOngoing(true) }
            .build()
    }

    @SuppressLint("NewApi") // setRequestPromotedOngoing requires API 36.1
    private fun buildResultNotif(
        context: Context,
        sessionId: Int,
        result: SessionNotificationResult,
        stopPi: PendingIntent,
    ): Notification {
        val title = result.title.ifBlank { "截图" }
        val firstLine = result.details.substringBefore('\n').ifBlank { result.title }.ifBlank { "无内容" }
        val fullBody = result.details.ifBlank { result.title }.ifBlank { "无内容" }
        val actionText = result.actionText.ifBlank { "已完成" }

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(firstLine)
            .also { builder -> result.price.takeIf(String::isNotBlank)?.let(builder::setSubText) }
            .setShortCriticalText(title)
            .setStyle(Notification.BigTextStyle().bigText(fullBody))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    actionText,
                    stopPi
                ).build()
            )
            .setDeleteIntent(stopPi)

        if (result.hasQrBitmap) {
            val showImagePi = PendingIntent.getActivity(
                context, sessionId + 10000,
                Intent(context, QrResultActivity::class.java).apply {
                    putExtra(QrResultActivity.EXTRA_MODE, QrResultActivity.MODE_IMAGE)
                    putExtra(QrResultActivity.EXTRA_SESSION_ID, sessionId)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_qr_code),
                    context.getString(R.string.qr_show_image),
                    showImagePi
                ).build()
            )
        }

        builder.setRequestPromotedOngoing(true)
        return builder.build()
    }

    private fun makeDismissPendingIntent(context: Context, sessionId: Int): PendingIntent =
        PendingIntent.getService(
            context, sessionId,
            Intent(context, LiveUpdateService::class.java).apply {
                action = LiveUpdateService.ACTION_STOP
                putExtra(LiveUpdateService.EXTRA_SESSION_ID, sessionId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}
