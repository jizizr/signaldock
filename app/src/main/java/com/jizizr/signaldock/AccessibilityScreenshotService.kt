package com.jizizr.signaldock

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context.WINDOW_SERVICE
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
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
        private const val AUTO_EVENT_DEBOUNCE_MS = 600L
        private const val AUTO_REARM_DELAY_MS = 800L
        private const val ACCESSIBILITY_EVENT_TEXT_TTL_MS = 2_000L
        private const val MAX_ACCESSIBILITY_EVENT_TEXTS = 48
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
    private var diagnosticEventCount = 0L
    private var diagnosticEvaluationCount = 0L
    private var diagnosticContentEventsSuppressed = 0
    private var lastDiagnosticContentEventAtMs = 0L
    @Volatile
    private var activeAutoSessionId: Int? = null
    private var lastActivityClassName = ""
    private var lastActivityPackageName = ""
    private val recentAccessibilityEventTexts = ArrayDeque<RecentAccessibilityEventText>()
    private val autoEvaluateRunnable = Runnable(::runAutoEvaluationWhenStable)
    private val autoResetRunnable = Runnable(::confirmAutoPageExit)
    private var keepAliveOverlay: View? = null
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

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
        AccessibilityStartupRecovery.onAccessibilityConnected()
        refreshAutoConfiguration(evaluateCurrentPage = true)
        syncKeepAliveOverlay()
        AppLog.i(TAG, "AccessibilityScreenshotService connected  ready to capture")
        AutoPageDiagnostics.log(
            "stage=service-connected globalEnabled=${AutoPageProfileStore.enabled} " +
                "serviceEventTypes=${serviceInfo.eventTypes} " +
                "servicePackages=${serviceInfo.packageNames?.joinToString().orEmpty()}",
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        diagnosticEventCount++
        if (!AutoPageProfileStore.enabled) {
            logAutoEvent(event, "ignored-global-disabled")
            return
        }
        if (event.eventType and AUTO_EVENT_TYPES == 0) {
            logAutoEvent(event, "ignored-event-type")
            return
        }
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isBlank()) {
            logAutoEvent(event, "ignored-empty-package")
            return
        }
        if (isSystemUiPackage(packageName)) {
            logAutoEvent(event, "ignored-system-ui")
            return
        }
        if (packageName == applicationContext.packageName) {
            logAutoEvent(event, "ignored-self")
            return
        }
        recordAccessibilityEventText(event, packageName)
        val profiles = availableProfiles
        if (profiles.isEmpty()) {
            logAutoEvent(event, "ignored-no-available-profiles")
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val observedClassName = event.className?.toString().orEmpty()
            if (packageName != lastActivityPackageName) {
                lastActivityClassName = ""
                lastActivityPackageName = packageName
            }
            observedClassName.takeIf {
                shouldRememberActivityClassName(packageName, it)
            }?.let {
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
            AutoPageDiagnostics.log(
                "stage=event-rearm reason=definite-identity-exit " +
                    "profile=${AutoPageDiagnostics.profileRef(activeProfile.id)} " +
                    "package=$packageName activity=${event.className}",
            )
        }
        val observedActivityClassName = lastActivityClassName
            .takeIf { lastActivityPackageName == packageName }
            .orEmpty()
        val identityCandidates = profiles.filter { profile ->
            AutoPageMatcher.canMatchIdentity(profile, packageName, observedActivityClassName)
        }
        if (identityCandidates.isEmpty()) {
            logAutoEvent(event, "ignored-identity-mismatch")
            return
        }
        if (autoAnalysisInProgress.get() || manualAnalysisInProgress.get()) {
            autoEvaluationPending.set(true)
            logAutoEvent(event, "queued-analysis-busy")
            return
        }
        scheduleAutoEvaluation()
        logAutoEvent(
            event,
            "scheduled candidates=${identityCandidates.joinToString { AutoPageDiagnostics.profileRef(it.id) }}",
        )
    }
    override fun onInterrupt() { /* not used */ }

    override fun onDestroy() {
        removeKeepAliveOverlay()
        instance = null
        AutoPageDiagnostics.log(
            "stage=service-destroy globalEnabled=${AutoPageProfileStore.enabled}",
        )
        mainHandler.removeCallbacksAndMessages(null)
        callbackExecutor.shutdown()
        analysisExecutor.shutdown()
        sourceIconExecutor.shutdown()
        configuredProfiles = emptyList()
        availableProfiles = emptyList()
        targetPackages = emptySet()
        lastActivityClassName = ""
        lastActivityPackageName = ""
        AppLog.i(TAG, "AccessibilityScreenshotService destroyed")
        super.onDestroy()
    }

    //  Public API

    fun refreshAutoConfiguration(evaluateCurrentPage: Boolean = false) {
        mainHandler.post {
            AutoPageNotificationLockStore.pruneMissingNotifications(this)
            configuredProfiles = AutoPageProfileStore.enabledProfiles()
            updateRuntimeEventFilter()
            if (configuredProfiles.isEmpty()) resetAutoPageState()
            syncKeepAliveOverlay()
            if (
                evaluateCurrentPage &&
                shouldEvaluateCurrentPageAfterServiceConnect(
                    autoPageEnabled = AutoPageProfileStore.enabled,
                    availableProfileCount = availableProfiles.size,
                )
            ) {
                scheduleAutoEvaluation()
                AutoPageDiagnostics.log(
                    "stage=service-bootstrap-evaluation profiles=${availableProfiles.size}",
                )
            }
            AppLog.i(TAG, "auto configuration refreshed profiles=${configuredProfiles.size}")
            AutoPageDiagnostics.log(
                "stage=config-refresh globalEnabled=${AutoPageProfileStore.enabled} " +
                    "configured=${configuredProfiles.joinToString { AutoPageDiagnostics.profileRef(it.id) }}",
            )
        }
    }

    /** GKD-style accessibility anchor, active only for automatic island mode. */
    private fun syncKeepAliveOverlay() {
        if (
            AutoPageProfileStore.enabled &&
            AutoPageProfileStore.hasEnabledProfiles() &&
            AutoPageBackgroundPolicy.requirementsSatisfied(this)
        ) {
            addKeepAliveOverlay()
        } else {
            removeKeepAliveOverlay()
        }
    }

    private fun addKeepAliveOverlay() {
        if (keepAliveOverlay != null) return
        val view = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = android.graphics.PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            gravity = Gravity.TOP or Gravity.START
            width = 1
            height = 1
            packageName = packageName
        }
        runCatching {
            windowManager.addView(view, layoutParams)
            keepAliveOverlay = view
            AppLog.d(TAG, "Accessibility keep-alive overlay attached")
        }.onFailure { error ->
            AppLog.w(TAG, "Unable to attach accessibility keep-alive overlay", error)
        }
    }

    private fun removeKeepAliveOverlay() {
        keepAliveOverlay?.let { view ->
            runCatching { windowManager.removeView(view) }
            keepAliveOverlay = null
            AppLog.d(TAG, "Accessibility keep-alive overlay removed")
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
            notificationTimeout = 300L
            packageNames = targetPackages.takeIf { it.isNotEmpty() }?.toTypedArray()
        }
        AutoPageDiagnostics.log(
            "stage=event-filter configured=${configuredProfiles.size} " +
                "available=${availableProfiles.size} targets=${targetPackages.joinToString()} " +
                "locked=${configuredProfiles.filter { AutoPageNotificationLockStore.isLocked(it.id) }
                    .joinToString { AutoPageDiagnostics.profileRef(it.id) }} " +
                "eventTypes=${serviceInfo.eventTypes} timeout=${serviceInfo.notificationTimeout}",
        )
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
        var nextDiagnosticAtMs = 0L
        val captureWhenReady = object : Runnable {
            override fun run() {
                val ready = isCaptureTargetReady()
                val now = SystemClock.uptimeMillis()
                if (SourceWindowDiagnostics.enabled &&
                    (now >= nextDiagnosticAtMs || ready || waitedMs >= PANEL_MAX_CLOSE_DELAY_MS)
                ) {
                    val stage = "capture-wait-${waitedMs}ms-ready=$ready"
                    SourceWindowDiagnostics.logWindows(this@AccessibilityScreenshotService, stage)
                    AppShell.logForegroundTaskSnapshotAsync(stage)
                    nextDiagnosticAtMs = now + 250L
                }
                if (ready || waitedMs >= PANEL_MAX_CLOSE_DELAY_MS) {
                    SourceWindowDiagnostics.log(
                        "stage=capture-trigger waitedMs=$waitedMs ready=$ready",
                    )
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
            AutoPageDiagnostics.log(
                "stage=notification-lock profile=${AutoPageDiagnostics.profileRef(profileId)} " +
                    "notification=$notificationId",
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
            AutoPageDiagnostics.log(
                "stage=notification-release profile=${AutoPageDiagnostics.profileRef(profileId)} " +
                    "notification=$notificationId",
            )
        }
    }

    private fun evaluateAutoPage() {
        diagnosticEvaluationCount++
        if (!AutoPageProfileStore.enabled) {
            AutoPageDiagnostics.log("stage=evaluate-skip reason=global-disabled")
            return
        }
        val readiness = autoCaptureReadiness()
        if (!readiness.interactive || readiness.keyguardLocked) {
            AutoPageDiagnostics.log(
                "stage=evaluate-skip reason=device-not-ready interactive=${readiness.interactive} " +
                    "keyguardLocked=${readiness.keyguardLocked}",
            )
            return
        }
        if (autoAnalysisInProgress.get() || manualAnalysisInProgress.get()) {
            AutoPageDiagnostics.log(
                "stage=evaluate-skip reason=analysis-busy auto=${autoAnalysisInProgress.get()} " +
                    "manual=${manualAnalysisInProgress.get()}",
            )
            return
        }
        val profiles = availableProfiles
        if (profiles.isEmpty()) {
            AutoPageDiagnostics.log("stage=evaluate-skip reason=no-available-profiles")
            return
        }
        AutoPageDiagnostics.log(
            "stage=evaluate-start evaluation=$diagnosticEvaluationCount " +
                "lastActivity=$lastActivityClassName profiles=${profiles.size} " +
                "coordinator=${describeCoordinatorState()}",
        )
        val inspection = SourceIconResolver.inspect(this, lastActivityClassName)
        val packageName = inspection.packageName.orEmpty()
        inspection.activityClassName.takeIf {
            shouldRememberActivityClassName(packageName, it)
        }?.let { activityClassName ->
            lastActivityPackageName = packageName
            lastActivityClassName = activityClassName
        }
        AutoPageDiagnostics.log(
            "stage=inspection package=$packageName activity=${inspection.activityClassName} " +
                "mini=${inspection.isWechatMiniProgram} miniTask=${inspection.miniProgramTaskId} " +
                "miniLabelHash=${AutoPageDiagnostics.fingerprint(normalizePageText(inspection.miniProgramLabel))} " +
                "miniIconHash=${inspection.miniProgramIconHash.take(12).ifBlank { "-" }} " +
                "nodes=${inspection.stableKeywords.size}",
        )
        if (packageName.isBlank() || profiles.none { it.packageName == packageName }) {
            AutoPageDiagnostics.log(
                "stage=evaluate-skip reason=inspection-package-mismatch package=$packageName " +
                    "targets=${profiles.map(AutoPageProfile::packageName).distinct().joinToString()}",
            )
            return
        }
        val observation = inspection.toObservation()
            .withRecentAccessibilityEventText(
                recentTexts = recentAccessibilityEventKeywords(packageName),
            )
        val ranked = AutoPageMatcher.rank(observation, profiles)
        profiles.forEach { profile ->
            AutoPageDiagnostics.log(
                "stage=match " + AutoPageDiagnostics.describeMatch(
                    observation,
                    profile,
                    ranked.firstOrNull { it.profile.id == profile.id },
                ),
            )
        }
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
            AutoPageDiagnostics.log("stage=coordinator-reset reason=new-mini-program-task")
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
            if (autoCoordinator.isFired()) {
                AutoPageDiagnostics.log(
                    "stage=evaluate-skip reason=coordinator-fired state=${describeCoordinatorState()}",
                )
                return
            }
        }

        val best = ranked.firstOrNull { match ->
            autoCoordinator.canEvaluate(match.profile.id) && match.textMatched
        } ?: run {
            scheduleAutoReset()
            AutoPageDiagnostics.log(
                "stage=evaluate-skip reason=no-text-matched-candidate " +
                    "state=${describeCoordinatorState()}",
            )
            return
        }
        val signature = observation.contentSignature()
        if (!autoCoordinator.canAttempt(best.profile.id, signature)) {
            AutoPageDiagnostics.log(
                "stage=evaluate-skip reason=coordinator-rejected-attempt " +
                    "profile=${AutoPageDiagnostics.profileRef(best.profile.id)} " +
                    "signature=${AutoPageDiagnostics.fingerprint(signature)} " +
                    "state=${describeCoordinatorState()}",
            )
            return
        }
        AppLog.i(
            TAG,
            "auto candidate profile=${best.profile.id} text=${best.textMatched} " +
                "score=${"%.2f".format(best.score)}",
        )
        AutoPageDiagnostics.log(
            "stage=candidate profile=${AutoPageDiagnostics.profileRef(best.profile.id)} " +
                "score=${"%.3f".format(java.util.Locale.US, best.score)} " +
                "signature=${AutoPageDiagnostics.fingerprint(signature)}",
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
        ) {
            AutoPageDiagnostics.log(
                "stage=capture-rejected reason=manual-generation-changed " +
                    "profile=${AutoPageDiagnostics.profileRef(trigger.profile.id)}",
            )
            return
        }
        if (!analysisSlots.tryAcquire()) {
            AppLog.w(TAG, "Analysis capacity reached; ignoring request")
            if (trigger is CaptureTrigger.Auto) {
                AutoPageDiagnostics.log(
                    "stage=capture-rejected reason=analysis-capacity " +
                        "profile=${AutoPageDiagnostics.profileRef(trigger.profile.id)}",
                )
            }
            retryManualOrShowBusy(trigger)
            return
        }
        if (!screenshotInProgress.compareAndSet(false, true)) {
            AppLog.w(TAG, "Screenshot already in progress  ignoring request")
            if (trigger is CaptureTrigger.Auto) {
                AutoPageDiagnostics.log(
                    "stage=capture-rejected reason=screenshot-in-progress " +
                        "profile=${AutoPageDiagnostics.profileRef(trigger.profile.id)}",
                )
            }
            analysisSlots.release()
            retryManualOrShowBusy(trigger)
            return
        }
        if (trigger is CaptureTrigger.Auto &&
            !autoAnalysisInProgress.compareAndSet(false, true)
        ) {
            screenshotInProgress.set(false)
            analysisSlots.release()
            AutoPageDiagnostics.log(
                "stage=capture-rejected reason=auto-analysis-race " +
                    "profile=${AutoPageDiagnostics.profileRef(trigger.profile.id)}",
            )
            return
        }
        if (trigger is CaptureTrigger.Manual) manualAnalysisInProgress.set(true)
        AppLog.i(
            TAG,
            "pipeline screenshot requested: trigger=${trigger.javaClass.simpleName} " +
                "elapsed=${SystemClock.elapsedRealtime() - trigger.startedAtMs}ms",
        )
        if (trigger is CaptureTrigger.Auto) {
            AutoPageDiagnostics.log(
                "stage=capture-request profile=${AutoPageDiagnostics.profileRef(trigger.profile.id)} " +
                    "signature=${AutoPageDiagnostics.fingerprint(trigger.contentSignature)}",
            )
        }
        val sourceInspection = when (trigger) {
            is CaptureTrigger.Manual -> SourceIconResolver.inspect(
                service = this,
                activityClassName = lastActivityClassName,
                verifyForegroundTask = true,
            )
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
                            if (trigger is CaptureTrigger.Auto) {
                                AutoPageDiagnostics.log(
                                    "stage=capture-failed reason=null-bitmap " +
                                        "profile=${AutoPageDiagnostics.profileRef(trigger.profile.id)}",
                                )
                            }
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
                            AutoPageDiagnostics.log(
                                "stage=capture-cancelled reason=manual-after-screenshot " +
                                    "profile=${AutoPageDiagnostics.profileRef(trigger.profile.id)}",
                            )
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
                                AutoPageDiagnostics.log(
                                    "stage=capture-cancelled reason=coordinator-recheck " +
                                        "profile=${AutoPageDiagnostics.profileRef(trigger.profile.id)} " +
                                        "state=${describeCoordinatorState()}",
                                )
                                releaseCaptureAnalysis(trigger)
                                return
                            }
                            autoCoordinator.onAttempt(
                                trigger.profile.id,
                                trigger.contentSignature,
                            )
                            AutoPageDiagnostics.log(
                                "stage=attempt-started " +
                                    "profile=${AutoPageDiagnostics.profileRef(trigger.profile.id)} " +
                                    "state=${describeCoordinatorState()}",
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
                        // Recognition uses a system animation; only the result needs the source icon.
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
                            if (trigger is CaptureTrigger.Auto) {
                                AutoPageDiagnostics.log(
                                    "stage=pipeline-failed reason=recognizing-service-start " +
                                        "profile=${AutoPageDiagnostics.profileRef(trigger.profile.id)}",
                                )
                            }
                            sourceIconFuture.whenComplete { _, _ ->
                                SourceIconCache.remove(sessionId)
                                bitmap.recycle()
                                analysisSlots.release()
                                releaseCaptureAnalysis(trigger)
                            }
                            return
                        }
                        if (trigger is CaptureTrigger.Auto) activeAutoSessionId = sessionId
                        if (trigger is CaptureTrigger.Auto) {
                            AutoPageDiagnostics.log(
                                "stage=analysis-start profile=${AutoPageDiagnostics.profileRef(trigger.profile.id)} " +
                                    "session=$sessionId",
                            )
                        }
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
                        if (trigger is CaptureTrigger.Auto) {
                            AutoPageDiagnostics.log(
                                "stage=capture-failed reason=take-screenshot-$errorCode " +
                                    "profile=${AutoPageDiagnostics.profileRef(trigger.profile.id)}",
                            )
                        }
                        if (trigger is CaptureTrigger.Manual) showCaptureMessage(R.string.screenshot_failed)
                    }
                },
            )
        }.onFailure { error ->
            AppLog.e(TAG, "takeScreenshot threw before callback", error)
            screenshotInProgress.set(false)
            analysisSlots.release()
            releaseCaptureAnalysis(trigger)
            if (trigger is CaptureTrigger.Auto) {
                AutoPageDiagnostics.log(
                    "stage=capture-failed reason=take-screenshot-threw " +
                        "error=${error.javaClass.simpleName} " +
                        "profile=${AutoPageDiagnostics.profileRef(trigger.profile.id)}",
                )
            }
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
                    AutoPageDiagnostics.log(
                        "stage=analysis-cancelled reason=manual-before-analysis " +
                            "profile=${AutoPageDiagnostics.profileRef(trigger.profile.id)} session=$sessionId",
                    )
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
                    AutoPageDiagnostics.log(
                        "stage=analysis-cancelled reason=manual-after-analysis " +
                            "profile=${AutoPageDiagnostics.profileRef(trigger.profile.id)} session=$sessionId",
                    )
                    return@execute
                }
                if (trigger is CaptureTrigger.Auto) {
                    autoCoordinator.onSuccess(trigger.profile.id)
                    activeMiniProgramTaskId = trigger.inspection.miniProgramTaskId
                    autoEvaluationPending.set(false)
                    AutoPageDiagnostics.log(
                        "stage=analysis-success profile=${AutoPageDiagnostics.profileRef(trigger.profile.id)} " +
                            "session=$sessionId durationMs=$analysisDurationMs " +
                            "state=${describeCoordinatorState()}",
                    )
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
                    AutoPageDiagnostics.log(
                        "stage=analysis-failed profile=${AutoPageDiagnostics.profileRef(trigger.profile.id)} " +
                            "session=$sessionId error=${e.javaClass.simpleName}",
                    )
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

    private data class AutoCaptureReadiness(
        val interactive: Boolean,
        val keyguardLocked: Boolean,
    )

    private fun autoCaptureReadiness(): AutoCaptureReadiness {
        val powerManager = getSystemService(PowerManager::class.java)
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        return AutoCaptureReadiness(
            interactive = powerManager?.isInteractive == true,
            keyguardLocked = keyguardManager?.isKeyguardLocked == true,
        )
    }

    private fun scheduleAutoEvaluation() {
        lastAutoEventAtMs = SystemClock.uptimeMillis()
        if (!mainHandler.hasCallbacks(autoEvaluateRunnable)) {
            mainHandler.postDelayed(autoEvaluateRunnable, AUTO_EVENT_DEBOUNCE_MS)
            AutoPageDiagnostics.log(
                "stage=debounce-scheduled delayMs=$AUTO_EVENT_DEBOUNCE_MS",
            )
        } else {
            AutoPageDiagnostics.log("stage=debounce-extended")
        }
    }

    private fun runAutoEvaluationWhenStable() {
        val elapsed = SystemClock.uptimeMillis() - lastAutoEventAtMs
        val remaining = AUTO_EVENT_DEBOUNCE_MS - elapsed
        if (remaining > 0L) {
            AutoPageDiagnostics.log("stage=debounce-wait remainingMs=$remaining")
            mainHandler.postDelayed(autoEvaluateRunnable, remaining)
        } else {
            AutoPageDiagnostics.log("stage=debounce-stable elapsedMs=$elapsed")
            evaluateAutoPage()
        }
    }

    private fun scheduleAutoReset() {
        if (!mainHandler.hasCallbacks(autoResetRunnable)) {
            mainHandler.postDelayed(autoResetRunnable, AUTO_REARM_DELAY_MS)
            AutoPageDiagnostics.log("stage=rearm-check-scheduled delayMs=$AUTO_REARM_DELAY_MS")
        }
    }

    private fun confirmAutoPageExit() {
        val activeProfileId = autoCoordinator.activeProfileId() ?: return
        val profile = configuredProfiles
            .firstOrNull { it.id == activeProfileId }
        if (profile == null) {
            AutoPageDiagnostics.log("stage=rearm reason=missing-active-profile")
            resetAutoPageState()
            return
        }

        val inspection = SourceIconResolver.inspect(this, lastActivityClassName)
        if (inspection.packageName != profile.packageName) {
            AppLog.d(TAG, "auto page session suspended outside source app")
            AutoPageDiagnostics.log(
                "stage=rearm-suspended reason=outside-source-app " +
                    "profile=${AutoPageDiagnostics.profileRef(profile.id)} " +
                    "observedPackage=${inspection.packageName}",
            )
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
            AutoPageDiagnostics.log(
                "stage=rearm-suppressed reason=identity-still-matched " +
                    "profile=${AutoPageDiagnostics.profileRef(profile.id)}",
            )
            return
        }

        AppLog.d(TAG, "auto page rearmed after confirmed page exit")
        AutoPageDiagnostics.log(
            "stage=rearm-confirmed profile=${AutoPageDiagnostics.profileRef(profile.id)}",
        )
        resetAutoPageState()
    }

    private fun resetAutoPageState() {
        AutoPageDiagnostics.log("stage=coordinator-reset previous=${describeCoordinatorState()}")
        autoCoordinator.reset()
        activeMiniProgramTaskId = -1
    }

    private fun recordAccessibilityEventText(
        event: AccessibilityEvent,
        packageName: String,
    ) {
        val values = buildList {
            addAll(event.text.map(CharSequence::toString))
            event.contentDescription?.toString()?.let(::add)
        }.map(::normalizePageText)
            .filter { it.length in 2..96 && !isDynamicPageText(it) }
            .distinct()
        if (values.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        synchronized(recentAccessibilityEventTexts) {
            while (recentAccessibilityEventTexts.isNotEmpty() &&
                now - recentAccessibilityEventTexts.first().atMs > ACCESSIBILITY_EVENT_TEXT_TTL_MS
            ) {
                recentAccessibilityEventTexts.removeFirst()
            }
            values.forEach { value ->
                recentAccessibilityEventTexts.addLast(
                    RecentAccessibilityEventText(packageName, value, now),
                )
            }
            while (recentAccessibilityEventTexts.size > MAX_ACCESSIBILITY_EVENT_TEXTS) {
                recentAccessibilityEventTexts.removeFirst()
            }
        }
        AutoPageDiagnostics.log(
            "stage=event-text-captured package=$packageName count=${values.size}",
        )
    }

    private fun recentAccessibilityEventKeywords(packageName: String): List<String> {
        val now = SystemClock.uptimeMillis()
        synchronized(recentAccessibilityEventTexts) {
            while (recentAccessibilityEventTexts.isNotEmpty() &&
                now - recentAccessibilityEventTexts.first().atMs > ACCESSIBILITY_EVENT_TEXT_TTL_MS
            ) {
                recentAccessibilityEventTexts.removeFirst()
            }
            return recentAccessibilityEventTexts
                .asSequence()
                .filter { it.packageName == packageName }
                .map(RecentAccessibilityEventText::text)
                .distinct()
                .toList()
        }
    }

    internal fun diagnosticAutoPageSnapshot(): String {
        if (!AutoPageDiagnostics.enabled) return "disabled"
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val rootPackage = root?.packageName?.toString().orEmpty()
        val rootClass = root?.className?.toString().orEmpty()
        return buildString {
            append("events=")
            append(diagnosticEventCount)
            append(" evaluations=")
            append(diagnosticEvaluationCount)
            append(" globalEnabled=")
            append(AutoPageProfileStore.enabled)
            append(" serviceEventTypes=")
            append(serviceInfo.eventTypes)
            append(" servicePackages=")
            append(serviceInfo.packageNames?.joinToString().orEmpty())
            append(" configured=")
            append(configuredProfiles.joinToString { AutoPageDiagnostics.profileRef(it.id) })
            append(" available=")
            append(availableProfiles.joinToString { AutoPageDiagnostics.profileRef(it.id) })
            append(" targetPackages=")
            append(targetPackages.joinToString())
            append(" lastActivity=")
            append(lastActivityClassName)
            append(" root=")
            append(rootPackage)
            append('/')
            append(rootClass)
            append(" manualBusy=")
            append(manualAnalysisInProgress.get())
            append(" autoBusy=")
            append(autoAnalysisInProgress.get())
            append(" screenshotBusy=")
            append(screenshotInProgress.get())
            append(" pending=")
            append(autoEvaluationPending.get())
            append(" coordinator=")
            append(describeCoordinatorState())
        }
    }

    private fun describeCoordinatorState(): String {
        val snapshot = autoCoordinator.diagnosticSnapshot()
        return "profile=${snapshot.activeProfileId?.let(AutoPageDiagnostics::profileRef) ?: "-"}" +
            ",attempts=${snapshot.attempts}" +
            ",fired=${snapshot.fired}" +
            ",signature=${AutoPageDiagnostics.fingerprint(snapshot.lastContentSignature)}"
    }

    private fun logAutoEvent(event: AccessibilityEvent, decision: String) {
        if (!AutoPageDiagnostics.enabled) return
        val now = SystemClock.uptimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            now - lastDiagnosticContentEventAtMs < 250L
        ) {
            diagnosticContentEventsSuppressed++
            return
        }
        val suppressed = diagnosticContentEventsSuppressed
        diagnosticContentEventsSuppressed = 0
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            lastDiagnosticContentEventAtMs = now
        }
        AutoPageDiagnostics.log(
            "stage=event count=$diagnosticEventCount " +
                "type=${AutoPageDiagnostics.eventTypeName(event.eventType)} " +
                "package=${event.packageName} class=${event.className} " +
                "lastActivity=$lastActivityClassName decision=$decision " +
                "suppressedContentEvents=$suppressed coordinator=${describeCoordinatorState()}",
        )
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

    private fun PageObservationSnapshot.withRecentAccessibilityEventText(
        recentTexts: List<String>,
    ): PageObservationSnapshot = copy(
        stableKeywords = (stableKeywords + recentTexts).distinct().take(80),
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

private data class RecentAccessibilityEventText(
    val packageName: String,
    val text: String,
    val atMs: Long,
)

internal fun shouldEvaluateCurrentPageAfterServiceConnect(
    autoPageEnabled: Boolean,
    availableProfileCount: Int,
): Boolean = autoPageEnabled && availableProfileCount > 0

internal fun shouldRememberActivityClassName(
    packageName: String,
    className: String,
): Boolean = packageName.isNotBlank() &&
    className.startsWith("$packageName.")
