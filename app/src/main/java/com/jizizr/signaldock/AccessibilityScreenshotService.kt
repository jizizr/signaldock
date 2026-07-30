package com.jizizr.signaldock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
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
        private const val PANEL_MIN_CLOSE_DELAY_MS = 250L
        private const val PANEL_MAX_CLOSE_DELAY_MS = 1500L
        private const val PANEL_READY_POLL_MS = 50L
        private const val SOURCE_ICON_RECOGNIZING_WAIT_MS = 250L

        @Volatile
        var instance: AccessibilityScreenshotService? = null
            private set

    }

    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_ANALYSES)
    private val sourceIconExecutor = Executors.newSingleThreadExecutor()
    private val analysisSlots = Semaphore(MAX_CONCURRENT_ANALYSES)
    private val screenshotInProgress = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    //  AccessibilityService lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLog.i(TAG, "AccessibilityScreenshotService connected  ready to capture")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }
    override fun onInterrupt() { /* not used */ }

    override fun onDestroy() {
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        callbackExecutor.shutdown()
        analysisExecutor.shutdown()
        sourceIconExecutor.shutdown()
        AppLog.i(TAG, "AccessibilityScreenshotService destroyed")
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

    fun scheduleScreenshot(tileTapStartedAtMs: Long) {
        var waitedMs = PANEL_MIN_CLOSE_DELAY_MS
        val captureWhenReady = object : Runnable {
            override fun run() {
                if (isCaptureTargetReady() || waitedMs >= PANEL_MAX_CLOSE_DELAY_MS) {
                    triggerScreenshot(tileTapStartedAtMs)
                    return
                }
                waitedMs += PANEL_READY_POLL_MS
                mainHandler.postDelayed(this, PANEL_READY_POLL_MS)
            }
        }
        mainHandler.postDelayed(captureWhenReady, PANEL_MIN_CLOSE_DELAY_MS)
    }

    fun triggerScreenshot(tileTapStartedAtMs: Long = SystemClock.elapsedRealtime()) {
        if (!analysisSlots.tryAcquire()) {
            AppLog.w(TAG, "Analysis capacity reached; ignoring request")
            showCaptureMessage(R.string.screenshot_busy)
            return
        }
        if (!screenshotInProgress.compareAndSet(false, true)) {
            AppLog.w(TAG, "Screenshot already in progress  ignoring request")
            analysisSlots.release()
            showCaptureMessage(R.string.screenshot_busy)
            return
        }
        AppLog.i(
            TAG,
            "pipeline screenshot requested: fromTile=${SystemClock.elapsedRealtime() - tileTapStartedAtMs}ms",
        )
        val sourceInspection = SourceIconResolver.inspect(this)

        runCatching {
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
                            AppLog.e(TAG, "Failed to obtain Bitmap from ScreenshotResult")
                            screenshotInProgress.set(false)
                            analysisSlots.release()
                            showCaptureMessage(R.string.screenshot_failed)
                            return
                        }

                        AppLog.d(TAG, "Screenshot captured: ${bitmap.width}x${bitmap.height}")
                        AppLog.i(
                            TAG,
                            "pipeline screenshot captured: " +
                                "fromTile=${SystemClock.elapsedRealtime() - tileTapStartedAtMs}ms",
                        )
                        // 截图采集完成，立即解锁，允许新截图（分析在后台并行进行）
                        screenshotInProgress.set(false)
                        val sessionId = LiveUpdateService.newSessionId()
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
                            AppLog.i(
                                TAG,
                                "pipeline source icon ready: session=$sessionId " +
                                    "elapsed=${SystemClock.elapsedRealtime() - startedAtMs}ms",
                            )
                        }, sourceIconExecutor)
                        // Give the fast source-icon lookup a short head start so the recognizing
                        // island normally uses the same icon as the final result without turning
                        // icon resolution into an unbounded pipeline dependency.
                        runCatching {
                            sourceIconFuture.get(
                                SOURCE_ICON_RECOGNIZING_WAIT_MS,
                                TimeUnit.MILLISECONDS,
                            )
                        }
                        // Immediately show "识别中" state before the blocking analysis.
                        val recognizingStarted = runCatching {
                            startForegroundService(
                                Intent(
                                    this@AccessibilityScreenshotService,
                                    LiveUpdateService::class.java,
                                ).apply {
                                    action = LiveUpdateService.ACTION_RECOGNIZING
                                    putExtra(LiveUpdateService.EXTRA_SESSION_ID, sessionId)
                                },
                            )
                        }.onFailure { error ->
                            AppLog.e(TAG, "Unable to start recognizing service", error)
                            showCaptureMessage(R.string.screenshot_failed)
                        }.isSuccess
                        if (!recognizingStarted) {
                            sourceIconFuture.whenComplete { _, _ ->
                                SourceIconCache.remove(sessionId)
                                bitmap.recycle()
                                analysisSlots.release()
                            }
                            return
                        }
                        processCapture(sessionId, bitmap, sourceIconFuture, tileTapStartedAtMs)
                    }

                    override fun onFailure(errorCode: Int) {
                        AppLog.e(TAG, "takeScreenshot failed: errorCode=$errorCode")
                        screenshotInProgress.set(false)
                        analysisSlots.release()
                        showCaptureMessage(R.string.screenshot_failed)
                    }
                },
            )
        }.onFailure { error ->
            AppLog.e(TAG, "takeScreenshot threw before callback", error)
            screenshotInProgress.set(false)
            analysisSlots.release()
            showCaptureMessage(R.string.screenshot_failed)
        }
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
                val analysisDurationMs = SystemClock.elapsedRealtime() - analysisStartedAtMs
                AppLog.i(
                    TAG,
                    "pipeline analysis ready: session=$sessionId " +
                        "elapsed=${analysisDurationMs}ms",
                )
                runCatching { sourceIconFuture.join() }
                    .onFailure { AppLog.w(TAG, "Source icon resolution failed", it) }
                sendToLiveUpdateService(sessionId, notifData)
                runCatching {
                    RecognitionHistoryStore.save(
                        context = this,
                        sessionId = sessionId,
                        screenshot = bitmap,
                        data = notifData,
                        analysisDurationMs = analysisDurationMs,
                    )
                }.onFailure { error ->
                    AppLog.w(TAG, "Recognition history save failed", error)
                }
                AppLog.i(
                    TAG,
                    "pipeline result submitted: session=$sessionId " +
                        "fromTile=${SystemClock.elapsedRealtime() - tileTapStartedAtMs}ms",
                )
            } catch (e: Exception) {
                AppLog.e(TAG, "Processing failed", e)
                val failureData = RustBridge.NotificationData(
                    title = "截图分析失败",
                    body = e.message ?: "未知错误",
                    error = e.message.orEmpty(),
                )
                runCatching {
                    runCatching { sourceIconFuture.join() }
                    sendToLiveUpdateService(
                        sessionId,
                        failureData,
                    )
                }.onFailure { serviceError ->
                    AppLog.e(TAG, "Unable to publish processing failure", serviceError)
                    SourceIconCache.remove(sessionId)
                    SessionQrBitmapStore.remove(sessionId)
                    showCaptureMessage(R.string.screenshot_failed)
                }
                runCatching {
                    RecognitionHistoryStore.save(
                        context = this,
                        sessionId = sessionId,
                        screenshot = bitmap,
                        data = failureData,
                        analysisDurationMs = 0,
                    )
                }.onFailure { historyError ->
                    AppLog.w(TAG, "Failure history save failed", historyError)
                }
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

    private fun showCaptureMessage(messageRes: Int) {
        mainHandler.post {
            Toast.makeText(applicationContext, messageRes, Toast.LENGTH_LONG).show()
        }
    }
}
