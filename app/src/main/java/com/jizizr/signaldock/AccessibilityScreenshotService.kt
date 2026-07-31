package com.jizizr.signaldock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
        private const val AUTO_EVENT_DEBOUNCE_MS = 600L
        private const val AUTO_REARM_DELAY_MS = 800L
        private val AUTO_EVENT_TYPES =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

        @Volatile
        var instance: AccessibilityScreenshotService? = null
            private set

    }

    private val callbackExecutor = newIdleExecutor("SignalDock-Screenshot", 1)
    private val analysisExecutor = newIdleExecutor(
        "SignalDock-Analysis",
        MAX_CONCURRENT_ANALYSES,
        Process.THREAD_PRIORITY_BACKGROUND,
    )
    private val sourceIconExecutor = newIdleExecutor(
        "SignalDock-SourceIcon",
        1,
        Process.THREAD_PRIORITY_BACKGROUND,
    )
    private val analysisSlots = Semaphore(MAX_CONCURRENT_ANALYSES)
    private val screenshotInProgress = AtomicBoolean(false)
    private val manualAnalysisInProgress = AtomicBoolean(false)
    private val autoAnalysisInProgress = AtomicBoolean(false)
    private val autoEvaluationPending = AtomicBoolean(false)
    private val manualGeneration = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoCoordinator = AutoTriggerCoordinator()
    private var configuredProfiles: List<AutoPageProfile> = emptyList()
    private var availableProfiles: List<AutoPageProfile> = emptyList()
    private var targetPackages: Set<String> = emptySet()
    private var lastAutoEventAtMs = 0L
    private var activeMiniProgramTaskId = -1
    @Volatile
    private var activeAutoSessionId: Int? = null
    private var lastActivityClassName = ""
    private val autoEvaluateRunnable = Runnable(::runAutoEvaluationWhenStable)
    private val autoResetRunnable = Runnable(::confirmAutoPageExit)

    private sealed interface CaptureTrigger {
        val startedAtMs: Long

        data class Manual(
            override val startedAtMs: Long,
            val retryDeadlineMs: Long,
        ) : CaptureTrigger

        data class Auto(
            override val startedAtMs: Long,
            val profile: AutoPageProfile,
            val inspection: SourceAppInspection,
            val contentSignature: String,
            val manualGenerationAtStart: Int,
        ) : CaptureTrigger
    }

    //  AccessibilityService lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshAutoConfiguration()
        AppLog.i(TAG, "AccessibilityScreenshotService connected  ready to capture")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!AutoPageProfileStore.enabled || event.eventType and AUTO_EVENT_TYPES == 0) return
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isBlank() || isSystemUiPackage(packageName) ||
            packageName == applicationContext.packageName
        ) return
        val profiles = availableProfiles
        if (profiles.isEmpty()) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.className?.toString()?.takeIf(String::isNotBlank)?.let {
                lastActivityClassName = it
            }
        }
        val activeProfile = autoCoordinator.activeProfileId()?.let { activeId ->
            profiles.firstOrNull { it.id == activeId }
        }
        if (
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            activeProfile != null &&
            !manualAnalysisInProgress.get() &&
            !autoAnalysisInProgress.get() &&
            AutoPageMatcher.isDefiniteIdentityExit(
                profile = activeProfile,
                observedPackageName = packageName,
                observedActivityClassName = event.className?.toString().orEmpty(),
            )
        ) {
            mainHandler.removeCallbacks(autoResetRunnable)
            mainHandler.removeCallbacks(autoEvaluateRunnable)
            resetAutoPageState()
            AppLog.d(
                TAG,
                "auto page rearmed on window exit package=$packageName " +
                    "activity=${event.className}",
            )
        }
        if (profiles.none { profile ->
                AutoPageMatcher.canMatchIdentity(profile, packageName, lastActivityClassName)
            }) {
            return
        }
        if (autoAnalysisInProgress.get() || manualAnalysisInProgress.get()) {
            autoEvaluationPending.set(true)
            return
        }
        scheduleAutoEvaluation()
    }
    override fun onInterrupt() { /* not used */ }

    override fun onDestroy() {
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        callbackExecutor.shutdown()
        analysisExecutor.shutdown()
        sourceIconExecutor.shutdown()
        configuredProfiles = emptyList()
        availableProfiles = emptyList()
        targetPackages = emptySet()
        AppLog.i(TAG, "AccessibilityScreenshotService destroyed")
        super.onDestroy()
    }

    //  Public API

    fun refreshAutoConfiguration() {
        mainHandler.post {
            AutoPageNotificationLockStore.pruneMissingNotifications(this)
            configuredProfiles = AutoPageProfileStore.enabledProfiles()
            updateRuntimeEventFilter()
            if (configuredProfiles.isEmpty()) resetAutoPageState()
            AppLog.i(TAG, "auto configuration refreshed profiles=${configuredProfiles.size}")
        }
    }

    private fun updateRuntimeEventFilter() {
        availableProfiles = configuredProfiles.filterNot { profile ->
            AutoPageNotificationLockStore.isLocked(profile.id)
        }
        targetPackages = availableProfiles.mapTo(linkedSetOf(), AutoPageProfile::packageName)
        serviceInfo = serviceInfo.apply {
            eventTypes = if (availableProfiles.isEmpty()) 0 else AUTO_EVENT_TYPES
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 150L
            packageNames = targetPackages.takeIf { it.isNotEmpty() }?.toTypedArray()
        }
    }

    fun isCaptureTargetReady(): Boolean {
        val activePackage = rootInActiveWindow?.packageName?.toString()
            ?: windows.asSequence()
                .filter { it.isActive || it.isFocused }
                .mapNotNull { it.root?.packageName?.toString() }
                .firstOrNull()
            ?: return false
        return !isSystemUiPackage(activePackage)
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
        manualGeneration.incrementAndGet()
        mainHandler.removeCallbacks(autoEvaluateRunnable)
        mainHandler.removeCallbacks(autoResetRunnable)
        autoEvaluationPending.set(false)
        activeAutoSessionId?.let(::stopRecognizingSession)
        autoCoordinator.activeProfileId()?.let(autoCoordinator::suppressUntilExit)
        requestScreenshot(
            CaptureTrigger.Manual(
                startedAtMs = tileTapStartedAtMs,
                retryDeadlineMs = SystemClock.elapsedRealtime() + PANEL_MAX_CLOSE_DELAY_MS,
            ),
        )
    }

    fun onAutoNotificationPublished(profileId: String, notificationId: Int) {
        mainHandler.post {
            mainHandler.removeCallbacks(autoEvaluateRunnable)
            if (autoCoordinator.activeProfileId() == profileId) resetAutoPageState()
            updateRuntimeEventFilter()
            AppLog.d(
                TAG,
                "auto page held by result notification profile=$profileId id=$notificationId",
            )
        }
    }

    fun onAutoNotificationReleased(profileId: String, notificationId: Int) {
        mainHandler.post {
            if (autoCoordinator.activeProfileId() == profileId) resetAutoPageState()
            updateRuntimeEventFilter()
            AppLog.d(
                TAG,
                "auto page rearmed by notification removal profile=$profileId id=$notificationId",
            )
        }
    }

    private fun evaluateAutoPage() {
        if (!AutoPageProfileStore.enabled || !isDeviceReadyForAutoCapture() ||
            autoAnalysisInProgress.get() || manualAnalysisInProgress.get()
        ) return
        val profiles = availableProfiles
        if (profiles.isEmpty()) return
        val inspection = SourceIconResolver.inspect(this, lastActivityClassName)
        val packageName = inspection.packageName.orEmpty()
        if (packageName.isBlank() || profiles.none { it.packageName == packageName }) {
            return
        }
        val observation = inspection.toObservation()
        val ranked = AutoPageMatcher.rank(observation, profiles)
        var activeProfileId = autoCoordinator.activeProfileId()
        val activeProfile = profiles.firstOrNull { it.id == activeProfileId }
        if (
            autoCoordinator.isFired() &&
            activeProfile?.packageName == SourceIconResolver.WECHAT_PACKAGE &&
            activeMiniProgramTaskId >= 0 &&
            inspection.miniProgramTaskId >= 0 &&
            activeMiniProgramTaskId != inspection.miniProgramTaskId
        ) {
            AppLog.d(
                TAG,
                "auto page rearmed for new mini-program task " +
                    "$activeMiniProgramTaskId->${inspection.miniProgramTaskId}",
            )
            resetAutoPageState()
            activeProfileId = null
        }
        if (activeProfileId != null) {
            val activeMatch = ranked.firstOrNull { it.profile.id == activeProfileId }
            if (activeMatch == null ||
                (autoCoordinator.isFired() && !activeMatch.textMatched)
            ) {
                scheduleAutoReset()
            } else {
                mainHandler.removeCallbacks(autoResetRunnable)
            }
            // The coordinator covers the short interval before the result notification is sent.
            // After publication, the persisted notification lock becomes authoritative.
            if (autoCoordinator.isFired()) return
        }

        val best = ranked.firstOrNull { match ->
            autoCoordinator.canEvaluate(match.profile.id) && match.textMatched
        } ?: run {
            scheduleAutoReset()
            return
        }
        val signature = observation.contentSignature()
        if (!autoCoordinator.canAttempt(best.profile.id, signature)) return
        AppLog.i(
            TAG,
            "auto candidate profile=${best.profile.id} text=${best.textMatched} " +
                "score=${"%.2f".format(best.score)}",
        )
        requestScreenshot(
            CaptureTrigger.Auto(
                startedAtMs = SystemClock.elapsedRealtime(),
                profile = best.profile,
                inspection = inspection,
                contentSignature = signature,
                manualGenerationAtStart = manualGeneration.get(),
            ),
        )
    }

    private fun requestScreenshot(trigger: CaptureTrigger) {
        if (trigger is CaptureTrigger.Auto &&
            trigger.manualGenerationAtStart != manualGeneration.get()
        ) return
        if (!analysisSlots.tryAcquire()) {
            AppLog.w(TAG, "Analysis capacity reached; ignoring request")
            retryManualOrShowBusy(trigger)
            return
        }
        if (!screenshotInProgress.compareAndSet(false, true)) {
            AppLog.w(TAG, "Screenshot already in progress  ignoring request")
            analysisSlots.release()
            retryManualOrShowBusy(trigger)
            return
        }
        if (trigger is CaptureTrigger.Auto &&
            !autoAnalysisInProgress.compareAndSet(false, true)
        ) {
            screenshotInProgress.set(false)
            analysisSlots.release()
            return
        }
        if (trigger is CaptureTrigger.Manual) manualAnalysisInProgress.set(true)
        AppLog.i(
            TAG,
            "pipeline screenshot requested: trigger=${trigger.javaClass.simpleName} " +
                "elapsed=${SystemClock.elapsedRealtime() - trigger.startedAtMs}ms",
        )
        val sourceInspection = when (trigger) {
            is CaptureTrigger.Manual -> SourceIconResolver.inspect(this, lastActivityClassName)
            is CaptureTrigger.Auto -> trigger.inspection
        }
        if (trigger is CaptureTrigger.Manual) {
            AutoPageMatcher.rank(
                sourceInspection.toObservation(),
                configuredProfiles,
            ).firstOrNull { it.textMatched }?.profile?.let { profile ->
                autoCoordinator.suppressUntilExit(profile.id)
                activeMiniProgramTaskId = sourceInspection.miniProgramTaskId
                AppLog.d(TAG, "manual capture suppressed auto profile=${profile.id}")
            }
        }

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
                            releaseCaptureAnalysis(trigger)
                            if (trigger is CaptureTrigger.Manual) showCaptureMessage(R.string.screenshot_failed)
                            return
                        }

                        AppLog.d(TAG, "Screenshot captured: ${bitmap.width}x${bitmap.height}")
                        AppLog.i(
                            TAG,
                            "pipeline screenshot captured: " +
                                "elapsed=${SystemClock.elapsedRealtime() - trigger.startedAtMs}ms",
                        )
                        // 截图采集完成，立即解锁，允许新截图（分析在后台并行进行）
                        screenshotInProgress.set(false)
                        if (trigger is CaptureTrigger.Auto &&
                            trigger.manualGenerationAtStart != manualGeneration.get()
                        ) {
                            bitmap.recycle()
                            analysisSlots.release()
                            autoCoordinator.suppressUntilExit(trigger.profile.id)
                            releaseCaptureAnalysis(trigger)
                            return
                        }
                        if (trigger is CaptureTrigger.Auto) {
                            if (!autoCoordinator.canAttempt(
                                    trigger.profile.id,
                                    trigger.contentSignature,
                                )
                            ) {
                                bitmap.recycle()
                                analysisSlots.release()
                                releaseCaptureAnalysis(trigger)
                                return
                            }
                            autoCoordinator.onAttempt(
                                trigger.profile.id,
                                trigger.contentSignature,
                            )
                        }
                        val pageObservation = sourceInspection.toObservation()
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
                            if (trigger is CaptureTrigger.Manual) showCaptureMessage(R.string.screenshot_failed)
                        }.isSuccess
                        if (!recognizingStarted) {
                            sourceIconFuture.whenComplete { _, _ ->
                                SourceIconCache.remove(sessionId)
                                bitmap.recycle()
                                analysisSlots.release()
                                releaseCaptureAnalysis(trigger)
                            }
                            return
                        }
                        if (trigger is CaptureTrigger.Auto) activeAutoSessionId = sessionId
                        processCapture(
                            sessionId = sessionId,
                            bitmap = bitmap,
                            sourceIconFuture = sourceIconFuture,
                            trigger = trigger,
                            pageObservation = pageObservation,
                        )
                    }

                    override fun onFailure(errorCode: Int) {
                        AppLog.e(TAG, "takeScreenshot failed: errorCode=$errorCode")
                        screenshotInProgress.set(false)
                        analysisSlots.release()
                        releaseCaptureAnalysis(trigger)
                        if (trigger is CaptureTrigger.Manual) showCaptureMessage(R.string.screenshot_failed)
                    }
                },
            )
        }.onFailure { error ->
            AppLog.e(TAG, "takeScreenshot threw before callback", error)
            screenshotInProgress.set(false)
            analysisSlots.release()
            releaseCaptureAnalysis(trigger)
            if (trigger is CaptureTrigger.Manual) showCaptureMessage(R.string.screenshot_failed)
        }
    }

    //  Processing pipeline

    private fun processCapture(
        sessionId: Int,
        bitmap: Bitmap,
        sourceIconFuture: CompletableFuture<Void>,
        trigger: CaptureTrigger,
        pageObservation: PageObservationSnapshot,
    ) {
        analysisExecutor.execute {
            var retryAutoIfPending = false
            try {
                if (trigger is CaptureTrigger.Auto &&
                    trigger.manualGenerationAtStart != manualGeneration.get()
                ) {
                    autoCoordinator.suppressUntilExit(trigger.profile.id)
                    stopRecognizingSession(sessionId)
                    return@execute
                }
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
                if (trigger is CaptureTrigger.Auto &&
                    trigger.manualGenerationAtStart != manualGeneration.get()
                ) {
                    autoCoordinator.suppressUntilExit(trigger.profile.id)
                    stopRecognizingSession(sessionId)
                    return@execute
                }
                if (trigger is CaptureTrigger.Auto) {
                    autoCoordinator.onSuccess(trigger.profile.id)
                    activeMiniProgramTaskId = trigger.inspection.miniProgramTaskId
                    autoEvaluationPending.set(false)
                }
                sendToLiveUpdateService(
                    sessionId = sessionId,
                    data = notifData,
                    autoProfileId = (trigger as? CaptureTrigger.Auto)?.profile?.id,
                )
                if (trigger is CaptureTrigger.Auto) activeAutoSessionId = null
                runCatching {
                    RecognitionHistoryStore.save(
                        context = this,
                        sessionId = sessionId,
                        screenshot = bitmap,
                        data = notifData,
                        analysisDurationMs = analysisDurationMs,
                        pageObservation = pageObservation,
                    )
                }.onFailure { error ->
                    AppLog.w(TAG, "Recognition history save failed", error)
                }
                AppLog.i(
                    TAG,
                    "pipeline result submitted: session=$sessionId " +
                        "trigger=${trigger.javaClass.simpleName} " +
                        "elapsed=${SystemClock.elapsedRealtime() - trigger.startedAtMs}ms",
                )
            } catch (e: Exception) {
                AppLog.e(TAG, "Processing failed", e)
                if (trigger is CaptureTrigger.Auto) {
                    stopRecognizingSession(sessionId)
                    retryAutoIfPending = true
                    return@execute
                }
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
                        pageObservation = pageObservation,
                    )
                }.onFailure { historyError ->
                    AppLog.w(TAG, "Failure history save failed", historyError)
                }
            } finally {
                runCatching { sourceIconFuture.join() }
                bitmap.recycle()
                analysisSlots.release()
                releaseCaptureAnalysis(trigger, retryAutoIfPending)
            }
        }
    }

    private fun stopRecognizingSession(sessionId: Int) {
        if (activeAutoSessionId == sessionId) activeAutoSessionId = null
        runCatching {
            startService(Intent(this, LiveUpdateService::class.java).apply {
                action = LiveUpdateService.ACTION_STOP
                putExtra(LiveUpdateService.EXTRA_SESSION_ID, sessionId)
            })
        }.onFailure { error ->
            AppLog.w(TAG, "Unable to stop rejected auto session=$sessionId", error)
            SourceIconCache.remove(sessionId)
            SessionQrBitmapStore.remove(sessionId)
        }
    }

    private fun isDeviceReadyForAutoCapture(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java)
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        return powerManager?.isInteractive == true && keyguardManager?.isKeyguardLocked != true
    }

    private fun scheduleAutoEvaluation() {
        lastAutoEventAtMs = SystemClock.uptimeMillis()
        if (!mainHandler.hasCallbacks(autoEvaluateRunnable)) {
            mainHandler.postDelayed(autoEvaluateRunnable, AUTO_EVENT_DEBOUNCE_MS)
        }
    }

    private fun runAutoEvaluationWhenStable() {
        val elapsed = SystemClock.uptimeMillis() - lastAutoEventAtMs
        val remaining = AUTO_EVENT_DEBOUNCE_MS - elapsed
        if (remaining > 0L) {
            mainHandler.postDelayed(autoEvaluateRunnable, remaining)
        } else {
            evaluateAutoPage()
        }
    }

    private fun scheduleAutoReset() {
        if (!mainHandler.hasCallbacks(autoResetRunnable)) {
            mainHandler.postDelayed(autoResetRunnable, AUTO_REARM_DELAY_MS)
        }
    }

    private fun confirmAutoPageExit() {
        val activeProfileId = autoCoordinator.activeProfileId() ?: return
        val profile = configuredProfiles
            .firstOrNull { it.id == activeProfileId }
        if (profile == null) {
            resetAutoPageState()
            return
        }

        val inspection = SourceIconResolver.inspect(this, lastActivityClassName)
        if (inspection.packageName != profile.packageName) {
            AppLog.d(TAG, "auto page session suspended outside source app")
            return
        }
        val activeMatch = AutoPageMatcher.rank(
            inspection.toObservation(),
            listOf(profile),
        ).firstOrNull()
        val sameMiniProgramTask = profile.packageName != SourceIconResolver.WECHAT_PACKAGE ||
            activeMiniProgramTaskId < 0 ||
            inspection.miniProgramTaskId < 0 ||
            activeMiniProgramTaskId == inspection.miniProgramTaskId
        if (activeMatch?.textMatched == true && sameMiniProgramTask) {
            AppLog.d(TAG, "auto page remains suppressed: learned identity is still foreground")
            return
        }

        AppLog.d(TAG, "auto page rearmed after confirmed page exit")
        resetAutoPageState()
    }

    private fun resetAutoPageState() {
        autoCoordinator.reset()
        activeMiniProgramTaskId = -1
    }

    private fun isSystemUiPackage(packageName: String): Boolean =
        packageName == "com.android.systemui" ||
            packageName == "miui.systemui.plugin" ||
            packageName.startsWith("com.miui.systemui")

    private fun retryManualOrShowBusy(trigger: CaptureTrigger) {
        if (trigger !is CaptureTrigger.Manual) return
        if (SystemClock.elapsedRealtime() < trigger.retryDeadlineMs) {
            mainHandler.postDelayed({ requestScreenshot(trigger) }, PANEL_READY_POLL_MS)
        } else {
            showCaptureMessage(R.string.screenshot_busy)
        }
    }

    private fun releaseAutoAnalysis(
        trigger: CaptureTrigger,
        retryIfPending: Boolean = false,
    ) {
        if (trigger !is CaptureTrigger.Auto) return
        autoAnalysisInProgress.set(false)
        val pending = autoEvaluationPending.getAndSet(false)
        if (retryIfPending && pending && AutoPageProfileStore.enabled) {
            mainHandler.removeCallbacks(autoEvaluateRunnable)
            lastAutoEventAtMs = SystemClock.uptimeMillis()
            mainHandler.postDelayed(autoEvaluateRunnable, AUTO_EVENT_DEBOUNCE_MS)
        }
    }

    private fun releaseCaptureAnalysis(
        trigger: CaptureTrigger,
        retryIfPending: Boolean = false,
    ) {
        when (trigger) {
            is CaptureTrigger.Manual -> manualAnalysisInProgress.set(false)
            is CaptureTrigger.Auto -> releaseAutoAnalysis(trigger, retryIfPending)
        }
    }

    private fun SourceAppInspection.toObservation(): PageObservationSnapshot = PageObservationSnapshot(
        packageName = packageName.orEmpty(),
        activityClassName = activityClassName,
        isWechatMiniProgram = isWechatMiniProgram,
        miniProgramLabel = miniProgramLabel,
        miniProgramIconHash = miniProgramIconHash,
        stableKeywords = stableKeywords,
    )

    private fun PageObservationSnapshot.contentSignature(): String = buildString {
        append(packageName)
        append('|')
        append(activityClassName)
        append('|')
        append(miniProgramLabel)
        append('|')
        append(stableKeywords.sorted().joinToString("|"))
    }.hashCode().toString()

    private fun sendToLiveUpdateService(
        sessionId: Int,
        data: RustBridge.NotificationData,
        autoProfileId: String? = null,
    ) {
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
            autoProfileId?.let { putExtra(LiveUpdateService.EXTRA_AUTO_PROFILE_ID, it) }
        }
        startForegroundService(intent)
    }

    private fun showCaptureMessage(messageRes: Int) {
        mainHandler.post {
            Toast.makeText(applicationContext, messageRes, Toast.LENGTH_LONG).show()
        }
    }
}
