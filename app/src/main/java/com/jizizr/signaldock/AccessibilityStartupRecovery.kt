package com.jizizr.signaldock

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.Shizuku

/** Restores a stale accessibility binding once when an auto-started process comes back. */
internal object AccessibilityStartupRecovery {
    private const val TAG = "A11yStartupRecovery"
    private const val STARTUP_CHECK_DELAY_MS = 1_500L
    private const val SHIZUKU_READY_DELAY_MS = 300L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scheduled = AtomicBoolean(false)
    private val attempted = AtomicBoolean(false)
    private lateinit var appContext: Context
    private var listenerRegistered = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        scheduleIfNeeded(SHIZUKU_READY_DELAY_MS)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!shouldRecover()) return
        scheduleIfNeeded(STARTUP_CHECK_DELAY_MS)
        registerBinderListener()
    }

    fun onAccessibilityConnected() {
        attempted.set(true)
        scheduled.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        unregisterBinderListener()
    }

    private fun scheduleIfNeeded(delayMs: Long) {
        if (!shouldRecover() || attempted.get()) return
        if (AccessibilityScreenshotService.instance != null) {
            onAccessibilityConnected()
            return
        }
        if (!scheduled.compareAndSet(false, true)) return
        mainHandler.postDelayed(
            {
                scheduled.set(false)
                recoverIfNeeded()
            },
            delayMs,
        )
    }

    private fun recoverIfNeeded() {
        if (!shouldRecover() || attempted.get()) return
        if (AccessibilityScreenshotService.instance != null) {
            onAccessibilityConnected()
            return
        }
        if (!AppShell.isShizukuAvailable) {
            AppLog.i(TAG, "Waiting for Shizuku before accessibility startup recovery")
            return
        }
        if (!attempted.compareAndSet(false, true)) return
        AppLog.i(TAG, "Accessibility is enabled but not bound; requesting one startup repair")
        AppShell.repairAccessibility(appContext) { repaired ->
            AppLog.i(TAG, "Accessibility startup repair requested=$repaired")
            unregisterBinderListener()
        }
    }

    private fun shouldRecover(): Boolean =
        shouldRecoverAccessibilityOnStartup(
            autoPageEnabled = AutoPageProfileStore.enabled,
            hasEnabledProfiles = AutoPageProfileStore.hasEnabledProfiles(),
            backgroundAllowed = AutoPageBackgroundPolicy.requirementsSatisfied(appContext),
            serviceConnected = AccessibilityScreenshotService.instance != null,
        )

    private fun registerBinderListener() {
        if (listenerRegistered) return
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        listenerRegistered = true
    }

    private fun unregisterBinderListener() {
        if (!listenerRegistered) return
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        listenerRegistered = false
    }
}

internal fun shouldRecoverAccessibilityOnStartup(
    autoPageEnabled: Boolean,
    hasEnabledProfiles: Boolean,
    backgroundAllowed: Boolean,
    serviceConnected: Boolean,
): Boolean = autoPageEnabled &&
    hasEnabledProfiles &&
    backgroundAllowed &&
    !serviceConnected
