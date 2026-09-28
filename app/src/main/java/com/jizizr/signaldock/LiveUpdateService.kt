package com.jizizr.signaldock

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decide how a queued result should be published at execution time.
 * Kept pure so the lifecycle branch can be regression-tested without mocking
 * Android's Service and NotificationManager.
 */
internal fun resultNotificationPublishDecision(
    isForeground: Boolean,
    foregroundSessionId: Int,
    notificationId: Int,
    activeSession: Boolean,
): ResultNotificationPublishDecision = when {
    !activeSession -> ResultNotificationPublishDecision.DROP
    isForeground && foregroundSessionId == notificationId ->
        ResultNotificationPublishDecision.FOREGROUND_REPOSTED
    else -> ResultNotificationPublishDecision.NORMAL_REPOST
}

/**
 * LiveUpdateService
 *
 * 前台服务，管理截图分析会话的通知生命周期。
 * 通知的具体样式与投递由 [SessionNotificationManager] 负责：
 *   - 标准设备：[StandardNotificationManager]（实时通知）
 *   - HyperOS 3+ 小米设备：[SuperIslandManager]（超级岛）
 *
 * 两条路径在本 Service 内结构完全对称，无任何 if/else 分支。
 */
class LiveUpdateService : Service() {

    companion object {
        private const val TAG = "LiveUpdateService"

        const val ACTION_UPDATE      = "com.jizizr.signaldock.ACTION_UPDATE"
        const val ACTION_RECOGNIZING = "com.jizizr.signaldock.ACTION_RECOGNIZING"
        const val ACTION_STOP        = "com.jizizr.signaldock.ACTION_STOP"

        const val EXTRA_TEXT       = "extra_text"
        const val EXTRA_RAW_TEXT   = "extra_raw_text"
        const val EXTRA_CONTENT    = "extra_content"
        const val EXTRA_PRICE      = "extra_price"
        const val EXTRA_BUTTON_TEXT = "extra_button_text"
        const val EXTRA_ITEM       = "extra_item"
        const val EXTRA_ITEM_DETAIL = "extra_item_detail"
        const val EXTRA_MERCHANT   = "extra_merchant"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_AUTO_PROFILE_ID = "extra_auto_profile_id"

        private const val MIN_SESSION_ID = 2000
        private const val MAX_SESSION_ID = Int.MAX_VALUE - 4000
        private val sessionCounter = AtomicInteger(
            (System.currentTimeMillis() % 1_000_000_000L)
                .toInt()
                .coerceAtLeast(MIN_SESSION_ID),
        )

        fun newSessionId(): Int = sessionCounter.getAndUpdate { current ->
            if (current >= MAX_SESSION_ID) MIN_SESSION_ID else current + 1
        }

    }

    private val activeSessions = ConcurrentHashMap<Int, Unit>()
    private val foregroundLock = Any()
    @Volatile
    private var isForeground = false
    @Volatile
    private var foregroundSessionId: Int = -1

    /** 根据设备能力懒加载一次，整个服务生命周期内不变 */
    private val provider: SessionNotificationManager by lazy {
        if (HyperIslandHelper.shouldUseSuperIsland(this)) SuperIslandManager
        else StandardNotificationManager
    }

    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        provider.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
                if (sessionId != -1) {
                    AppLog.d(TAG, "Stopping session $sessionId")
                    // Invalidate the session before cancelling its notification. A queued
                    // result that reaches the publisher concurrently will then be dropped;
                    // if it already published, the following cancellation removes it.
                    synchronized(foregroundLock) {
                        activeSessions.remove(sessionId)
                        if (foregroundSessionId == provider.notificationIdForSession(sessionId)) {
                            // cancel() cannot remove the current FGS anchor. Detach it first.
                            stopForeground(Service.STOP_FOREGROUND_REMOVE)
                            foregroundSessionId = -1
                            isForeground = false
                        }
                        provider.cancelForSession(this, sessionId)
                    }
                    val releasedAutoLock = AutoPageNotificationLockStore.releaseBySession(sessionId)
                    SessionQrBitmapStore.remove(sessionId)
                    SourceIconCache.remove(sessionId)
                    releasedAutoLock?.let { lock ->
                        AccessibilityScreenshotService.instance
                            ?.onAutoNotificationReleased(lock.profileId, lock.notificationId)
                    }
                    if (activeSessions.isEmpty()) {
                        synchronized(foregroundLock) {
                            foregroundSessionId = -1
                            isForeground = false
                        }
                        stopForeground(Service.STOP_FOREGROUND_REMOVE)
                        stopSelf(startId)
                    } else if (!isForeground) {
                        val nextSession = activeSessions.keys().nextElement()
                        synchronized(foregroundLock) {
                            foregroundSessionId = -1
                        }
                        provider.transferForeground(this, nextSession) { id, notif ->
                            synchronized(foregroundLock) {
                                if (!isForeground && activeSessions.containsKey(nextSession)) {
                                    foregroundSessionId = id
                                    startForeground(
                                        id,
                                        notif,
                                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                                    )
                                    isForeground = true
                                }
                            }
                        }
                    }
                } else {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_RECOGNIZING -> {
                val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
                if (sessionId == -1) return START_NOT_STICKY
                activeSessions[sessionId] = Unit
                provider.sendRecognizingNotification(this, sessionId) { notifId, notif ->
                    synchronized(foregroundLock) {
                        if (activeSessions.containsKey(sessionId)) {
                            if (!isForeground) {
                                foregroundSessionId = notifId
                                startForeground(
                                    notifId,
                                    notif,
                                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                                )
                                isForeground = true
                            } else {
                                getSystemService(NotificationManager::class.java).notify(notifId, notif)
                            }
                        }
                    }
                }
            }
            ACTION_UPDATE -> {
                val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
                if (sessionId == -1 || !activeSessions.containsKey(sessionId)) {
                    if (sessionId != -1) {
                        SessionQrBitmapStore.remove(sessionId)
                        SourceIconCache.remove(sessionId)
                    }
                    return START_NOT_STICKY
                }
                val text      = intent.getStringExtra(EXTRA_TEXT) ?: ""
                val rawText   = intent.getStringExtra(EXTRA_RAW_TEXT) ?: ""
                val label     = intent.getStringExtra(EXTRA_CONTENT) ?: ""
                val price     = intent.getStringExtra(EXTRA_PRICE) ?: ""
                val item      = intent.getStringExtra(EXTRA_ITEM) ?: ""
                val itemDetail = intent.getStringExtra(EXTRA_ITEM_DETAIL) ?: ""
                val merchant   = intent.getStringExtra(EXTRA_MERCHANT) ?: ""
                val actionText = intent.getStringExtra(EXTRA_BUTTON_TEXT)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "已完成"
                val hasBitmap = SessionQrBitmapStore.promote(sessionId)
                provider.sendResultNotification(
                    this,
                    sessionId,
                    SessionNotificationResult(
                        title = text,
                        details = rawText,
                        label = label,
                        price = price,
                        actionText = actionText,
                        item = item,
                        itemDetail = itemDetail,
                        merchant = merchant,
                        hasQrBitmap = hasBitmap,
                    ),
                    makeDismissPendingIntent(sessionId),
                    publisher = { notifId, notif, replaceExisting ->
                        publishResult(
                            sessionId, notifId, notif, replaceExisting,
                            intent.getStringExtra(EXTRA_AUTO_PROFILE_ID),
                        )
                    },
                )
            }
            else -> {}
        }
        return START_NOT_STICKY
    }

    private fun publishResult(
        sessionId: Int,
        notificationId: Int,
        notification: Notification,
        replaceExisting: Boolean,
        autoProfileId: String?,
    ): Boolean = synchronized(foregroundLock) {
        val decision = resultNotificationPublishDecision(
            isForeground, foregroundSessionId, notificationId, activeSessions.containsKey(sessionId),
        )
        if (decision == ResultNotificationPublishDecision.DROP) return@synchronized false
        if (decision == ResultNotificationPublishDecision.FOREGROUND_REPOSTED) {
            // HyperOS must remeasure the one-shot -> result template transition.
            if (replaceExisting) stopForeground(Service.STOP_FOREGROUND_REMOVE)
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            val manager = getSystemService(NotificationManager::class.java)
            if (replaceExisting) manager.cancel(notificationId)
            manager.notify(notificationId, notification)
        }
        autoProfileId?.takeIf(String::isNotBlank)?.let { profileId ->
            // Lock only after a successful publication, never while still queued for bypass.
            AutoPageNotificationLockStore.lock(profileId, sessionId, notificationId)
            AccessibilityScreenshotService.instance
                ?.onAutoNotificationPublished(profileId, notificationId)
        }
        true
    }

    override fun onDestroy() {
        provider.onServiceDestroy(this)
        synchronized(foregroundLock) {
            isForeground = false
            foregroundSessionId = -1
            activeSessions.keys.forEach { sessionId ->
                provider.cancelForSession(this, sessionId)
                SessionQrBitmapStore.remove(sessionId)
                SourceIconCache.remove(sessionId)
            }
            activeSessions.clear()
        }
        // Do not release an auto-page lock here. A service restart is not a user
        // acknowledgement; the persisted lock is reconciled against active
        // notifications when the accessibility service reconnects.
        // History replay notifications are independent of this service's active sessions.
        // Do not clear their QR images or source icons when the last live session ends.
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------

    private fun makeDismissPendingIntent(sessionId: Int): PendingIntent =
        PendingIntent.getService(
            this, sessionId,
            Intent(this, LiveUpdateService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_SESSION_ID, sessionId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}
