package com.jizizr.signaldock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlin.concurrent.thread

/** Shell-only tests can outlive the broadcast timeout without keeping an Activity open. */
class MiclawSelfTestService : Service() {
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "自动化测试", NotificationManager.IMPORTANCE_LOW),
        )
        startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("信岛正在运行识别测试")
            .setContentText("测试完成后自动结束")
            .setOngoing(true).setOnlyAlertOnce(true).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_NOT_STICKY
        worker = thread(name = "recognition-self-test") {
            try {
                MiclawSelfTestReceiver().runTest(applicationContext, intent)
            }
            finally { stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker?.interrupt()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL = "recognition_self_test"
        const val NOTIFICATION_ID = 717999999
    }
}
