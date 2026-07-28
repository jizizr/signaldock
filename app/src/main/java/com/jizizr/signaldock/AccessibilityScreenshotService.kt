package com.jizizr.signaldock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AccessibilityScreenshotService
 *
 * Captures a screenshot via [takeScreenshot] (API 30+) and passes the raw RGBA
 * bitmap to Rust for analysis. Android is responsible only for screen capture
 * and UI interaction; all analysis is handled by [RustBridge.analyzeScreenshot].
 */
class AccessibilityScreenshotService : AccessibilityService() {

    companion object {
        private const val TAG = "A11yScreenshot"
        private const val MAX_CONCURRENT_ANALYSES = 2

        @Volatile
        var instance: AccessibilityScreenshotService? = null
            private set

    }

    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_ANALYSES)
    private val sourceIconExecutor = Executors.newSingleThreadExecutor()
    private val analysisSlots = Semaphore(MAX_CONCURRENT_ANALYSES)
    private val screenshotInProgress = AtomicBoolean(false)

    //  AccessibilityService lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityScreenshotService connected  ready to capture")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }
    override fun onInterrupt() { /* not used */ }

    override fun onDestroy() {
        instance = null
        callbackExecutor.shutdown()
        analysisExecutor.shutdown()
        sourceIconExecutor.shutdown()
        Log.i(TAG, "AccessibilityScreenshotService destroyed")
        super.onDestroy()
    }

    //  Public API

    fun isCaptureTargetReady(): Boolean {
        val activePackage = rootInActiveWindow?.packageName?.toString()
            ?: windows.asSequence()
                .filter { it.isActive || it.isFocused }
                .mapNotNull { it.root?.packageName?.toString() }
                .firstOrNull()
            ?: return false
        return activePackage != "com.android.systemui"
    }

    fun triggerScreenshot(tileTapStartedAtMs: Long = SystemClock.elapsedRealtime()) {
        if (!analysisSlots.tryAcquire()) {
            Log.w(TAG, "Analysis capacity reached; ignoring request")
            return
        }
        if (!screenshotInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Screenshot already in progress  ignoring request")
            analysisSlots.release()
            return
        }
        Log.i(
            TAG,
            "pipeline screenshot requested: fromTile=${SystemClock.elapsedRealtime() - tileTapStartedAtMs}ms",
        )
        val sourceInspection = SourceIconResolver.inspect(this)

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            callbackExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardwareBuffer = result.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBuffer.close()

                    if (bitmap == null) {
                        Log.e(TAG, "Failed to obtain Bitmap from ScreenshotResult")
                        screenshotInProgress.set(false)
                        analysisSlots.release()
                        return
                    }

                    Log.d(TAG, "Screenshot captured: ${bitmap.width}x${bitmap.height}")
                    Log.i(
                        TAG,
                        "pipeline screenshot captured: fromTile=${SystemClock.elapsedRealtime() - tileTapStartedAtMs}ms",
                    )
                    // 截图采集完成，立即解锁，允许新截图（分析在后台并行进行）
                    screenshotInProgress.set(false)
                    // Allocate a unique session ID for this analysis run.
                    val sessionId = LiveUpdateService.newSessionId()
                    // Navigation must not depend on whether icon or QR extraction succeeds.
                    SourceIconCache.putSource(sessionId, sourceInspection.packageName)
                    val sourceIconFuture = CompletableFuture.runAsync({
                        val startedAtMs = SystemClock.elapsedRealtime()
                        SourceIconResolver.resolveInitialIcon(
                            this@AccessibilityScreenshotService,
                            sourceInspection,
                            bitmap,
                        )?.let { resolved ->
                            SourceIconCache.put(
                                sessionId,
                                sourceInspection.packageName,
                                resolved.bitmap,
                                resolved.source,
                                resolved.taskId,
                            )
                        }
                        Log.i(
                            TAG,
                            "pipeline source icon ready: session=$sessionId " +
                                "elapsed=${SystemClock.elapsedRealtime() - startedAtMs}ms",
                        )
                    }, sourceIconExecutor)
                    // Immediately show "识别中" state before the (blocking) Rust analysis.
                    startForegroundService(Intent(this@AccessibilityScreenshotService,
                        LiveUpdateService::class.java).apply {
                        action = LiveUpdateService.ACTION_RECOGNIZING
                        putExtra(LiveUpdateService.EXTRA_SESSION_ID, sessionId)
                    })
                    processCapture(sessionId, bitmap, sourceIconFuture, tileTapStartedAtMs)
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "takeScreenshot failed: errorCode=$errorCode")
                    screenshotInProgress.set(false)
                    analysisSlots.release()
                }
            }
        )
    }

    //  Processing pipeline

    private fun processCapture(
        sessionId: Int,
        bitmap: Bitmap,
        sourceIconFuture: CompletableFuture<Void>,
        tileTapStartedAtMs: Long,
    ) {
        analysisExecutor.execute {
            try {
                val analysisStartedAtMs = SystemClock.elapsedRealtime()
                val notifData = RustBridge.analyzeScreenshot(this, bitmap)
                Log.i(
                    TAG,
                    "pipeline analysis ready: session=$sessionId " +
                        "elapsed=${SystemClock.elapsedRealtime() - analysisStartedAtMs}ms",
                )
                runCatching { sourceIconFuture.join() }
                    .onFailure { Log.w(TAG, "Source icon resolution failed", it) }
                sendToLiveUpdateService(sessionId, notifData)
                Log.i(
                    TAG,
                    "pipeline result submitted: session=$sessionId " +
                        "fromTile=${SystemClock.elapsedRealtime() - tileTapStartedAtMs}ms",
                )
            } catch (e: Exception) {
                Log.e(TAG, "Processing failed", e)
                sendToLiveUpdateService(
                    sessionId,
                    RustBridge.NotificationData(
                        title = "截图分析失败",
                        body = e.message ?: "未知错误",
                    ),
                )
            } finally {
                runCatching { sourceIconFuture.join() }
                bitmap.recycle()
                analysisSlots.release()
            }
        }
    }

    private fun sendToLiveUpdateService(sessionId: Int, data: RustBridge.NotificationData) {
        // Decode bitmap locally and hand it via in-process cache — avoids
        // TransactionTooLargeException from routing large base64 through Binder.
        if (data.qrFound && data.qrRegionPngB64.isNotEmpty()) {
            val bytes = Base64.decode(data.qrRegionPngB64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?.let { SessionQrBitmapStore.stage(sessionId, it) }
        }

        val intent = Intent(this, LiveUpdateService::class.java).apply {
            action = LiveUpdateService.ACTION_UPDATE
            putExtra(LiveUpdateService.EXTRA_SESSION_ID, sessionId)
            putExtra(LiveUpdateService.EXTRA_TEXT, data.title)
            putExtra(LiveUpdateService.EXTRA_RAW_TEXT,
                data.infoLines.joinToString("\n").ifEmpty { data.body })
            putExtra(LiveUpdateService.EXTRA_CONTENT, data.content)
            putExtra(LiveUpdateService.EXTRA_PRICE, data.price)
            putExtra(LiveUpdateService.EXTRA_BUTTON_TEXT, data.buttonText)
            putExtra(LiveUpdateService.EXTRA_ITEM, data.item)
            putExtra(LiveUpdateService.EXTRA_ITEM_DETAIL, data.itemDetail)
            putExtra(LiveUpdateService.EXTRA_MERCHANT, data.merchant)
        }
        startForegroundService(intent)
    }
}
