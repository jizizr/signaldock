package com.jizizr.signaldock

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/** Metadata-only tracing for the temporary source-window diagnostic build. */
internal object SourceWindowDiagnostics {
    private const val TAG = "SourceWindowDebug"

    val enabled: Boolean
        get() = BuildConfig.SOURCE_WINDOW_DIAGNOSTICS

    fun log(message: String) {
        if (enabled) {
            AppLog.i(TAG, "source-debug uptime=${SystemClock.uptimeMillis()} $message")
        }
    }

    fun logWindows(service: AccessibilityService, stage: String): String {
        if (!enabled) return ""
        val activeRoot = runCatching { service.rootInActiveWindow }.getOrNull()
        val activeRootDescription = describeNode(activeRoot)
        val windowDescriptions = service.windows.mapIndexed { index, window ->
            val root = runCatching { window.root }.getOrNull()
            buildString {
                append('#')
                append(index)
                append("{id=")
                append(window.id)
                append(",type=")
                append(window.type)
                append(",layer=")
                append(window.layer)
                append(",active=")
                append(window.isActive)
                append(",focused=")
                append(window.isFocused)
                append(",taskId=")
                append(readWindowTaskId(window))
                append(",root=")
                append(describeNode(root))
                append('}')
            }
        }
        val snapshot = "activeRoot=$activeRootDescription windows=[${windowDescriptions.joinToString()}]"
        log("stage=$stage $snapshot")
        return snapshot
    }

    private fun describeNode(node: AccessibilityNodeInfo?): String {
        if (node == null) return "null"
        val bounds = Rect().also(node::getBoundsInScreen)
        return buildString {
            append(node.packageName?.toString().orEmpty())
            append('/')
            append(node.className?.toString().orEmpty())
            append('@')
            append(bounds.flattenToString())
        }
    }

    private fun readWindowTaskId(window: Any): Int = runCatching {
        window.javaClass.getMethod("getTaskId").invoke(window) as? Int
    }.getOrNull() ?: -1
}
