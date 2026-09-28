package com.jizizr.signaldock

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class IslandNotificationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun qrSnapshotSurvivesNotificationRemoval() {
        val sessionId = -101
        val original = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        SessionQrBitmapStore.stage(sessionId, original)
        SessionQrBitmapStore.stage(sessionId, original)
        assertFalse(original.isRecycled)
        assertTrue(SessionQrBitmapStore.promote(sessionId))
        val snapshot = checkNotNull(SessionQrBitmapStore.copyForSession(sessionId))
        try {
            SessionQrBitmapStore.remove(sessionId)
            assertTrue(original.isRecycled)
            assertFalse(snapshot.isRecycled)
            assertEquals(Color.WHITE, snapshot.getPixel(0, 0))
            assertNull(SessionQrBitmapStore.copyForSession(sessionId))
        } finally {
            snapshot.recycle()
            SessionQrBitmapStore.remove(sessionId)
        }
    }

    @Test
    fun notificationIconSurvivesSourceCacheRemoval() {
        val sessionId = -102
        SourceIconCache.put(
            sessionId, context.packageName,
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888), "test",
        )
        val icon = checkNotNull(SourceIconCache.iconFor(sessionId))
        SourceIconCache.remove(sessionId)
        assertTrue(icon.loadDrawable(context) != null)
    }

    @Test
    fun resultPayloadRetainsIslandAndSeparatesNavigationFromDismissal() {
        val open = PendingIntent.getActivity(
            context, 41, Intent(context, QrResultActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = PendingIntent.getService(
            context, 41,
            Intent(context, LiveUpdateService::class.java).setAction(LiveUpdateService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = SuperIslandManager.buildIslandNotification(
            context, "A123", "示例门店", "取餐码",
            ongoing = true,
            lifetime = RESULT_ISLAND_LIFETIME,
            contentPendingIntent = open,
            deleteIntent = dismiss,
            actionTitle = "已取餐",
            actionPendingIntent = dismiss,
        )
        val param = JSONObject(notification.extras.getString("miui.focus.param")!!)
            .getJSONObject("param_v2")
        val island = param.getJSONObject("param_island")
        assertEquals(2, island.getInt("islandProperty"))
        assertEquals(Int.MAX_VALUE, island.getInt("islandTimeout"))
        assertEquals(RESULT_ISLAND_LIFETIME.notificationMinutes, param.getInt("timeout"))
        assertFalse(island.getBoolean("dismissIsland"))
        assertFalse(param.getBoolean("islandFirstFloat"))
        assertFalse(param.getBoolean("enableFloat"))
        assertTrue(param.getBoolean("updatable"))
        assertEquals(RESULT_ISLAND_LIFETIME.notificationMillis, notification.timeoutAfter)
        assertEquals(0, notification.flags and Notification.FLAG_AUTO_CANCEL)
        assertEquals(open, notification.contentIntent)
        assertEquals(dismiss, notification.deleteIntent)
    }

    @Test
    fun recognizingPayloadRemainsTemporary() {
        val notification = SuperIslandManager.buildIslandNotification(
            context, "识别中", "正在分析截图", "识别中",
            statusOnly = true,
            lifetime = RECOGNIZING_ISLAND_LIFETIME,
        )
        val param = JSONObject(notification.extras.getString("miui.focus.param")!!)
            .getJSONObject("param_v2")
        val island = param.getJSONObject("param_island")
        assertEquals(0, island.getInt("islandProperty"))
        assertEquals(120, island.getInt("islandTimeout"))
        assertEquals(2, island.getInt("islandPriority"))
        assertFalse(param.getBoolean("isShowNotification"))
        assertShowsForegroundImmediately(notification)
    }

    @Test
    fun standardRecognitionAlsoBypassesForegroundNotificationDeferral() {
        StandardNotificationManager.ensureChannels(context)
        StandardNotificationManager.sendRecognizingNotification(context, 43) { _, notification ->
            assertShowsForegroundImmediately(notification)
        }
    }

    @Test
    fun immediateResultCannotOvertakeOrEraseRecognizingPhase() {
        val originalMode = SuperIslandSettingsStore.networkBypassMode
        val ready = CountDownLatch(1)
        val recognizingAt = AtomicLong(0)
        val resultAt = AtomicLong(0)
        val sessionId = -103
        try {
            SuperIslandSettingsStore.networkBypassMode = NetworkBypassMode.DISABLED
            SuperIslandManager.sendRecognizingNotification(context, sessionId) { _, _ ->
                recognizingAt.set(SystemClock.elapsedRealtime())
            }
            val dismiss = PendingIntent.getService(context, sessionId,
                Intent(context, LiveUpdateService::class.java).setAction(LiveUpdateService.ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            SuperIslandManager.sendResultNotification(context, sessionId,
                SessionNotificationResult("5312", "测试订单", "取餐码"), dismiss,
                publisher = { _, _, _ ->
                    resultAt.set(SystemClock.elapsedRealtime())
                    ready.countDown()
                    true
                })
            assertTrue("Notification queue did not complete", ready.await(5, TimeUnit.SECONDS))
            assertTrue(recognizingAt.get() > 0)
            assertTrue("Fast result skipped the visible recognizing phase",
                resultAt.get() - recognizingAt.get() >= 600)
        } finally {
            SuperIslandManager.cancelForSession(context, sessionId)
            SuperIslandSettingsStore.networkBypassMode = originalMode
        }
    }

    private fun assertShowsForegroundImmediately(notification: Notification) {
        // The framework's decision includes the explicit FGS behavior, but is a TestApi.
        val method = Notification::class.java.getDeclaredMethod("shouldShowForegroundImmediately")
        assertEquals(true, method.invoke(notification))
    }
}
