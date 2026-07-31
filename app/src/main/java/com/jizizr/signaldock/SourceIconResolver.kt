package com.jizizr.signaldock

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

data class SourceAppInspection(
    val packageName: String?,
    val activityClassName: String,
    val miniProgramTaskId: Int,
    val isWechatMiniProgram: Boolean,
    val miniProgramIconBounds: Rect?,
    val miniProgramLabel: String,
    val miniProgramIconHash: String,
    val stableKeywords: List<String>,
)

data class ResolvedSourceIcon(
    val bitmap: Bitmap,
    val source: String,
    val taskId: Int? = null,
)

data class SourceIconHistorySnapshot(
    val packageName: String?,
    val taskId: Int?,
    val bitmap: Bitmap?,
)

object SourceIconCache {
    private const val TAG = "SourceIconCache"

    private data class Entry(
        val packageName: String?,
        val bitmap: Bitmap?,
        val source: String,
        val taskId: Int?,
    )

    private val entries = ConcurrentHashMap<Int, Entry>()

    fun putSource(sessionId: Int, packageName: String?) {
        if (packageName == null) return
        entries.compute(sessionId) { _, current ->
            current?.copy(packageName = packageName)
                ?: Entry(packageName, null, "source_package", null)
        }
    }

    fun put(
        sessionId: Int,
        packageName: String?,
        bitmap: Bitmap,
        source: String,
        taskId: Int? = null,
    ) {
        entries.compute(sessionId) { _, current ->
            current?.bitmap?.takeUnless { it === bitmap }?.recycle()
            Entry(
                packageName = packageName ?: current?.packageName,
                bitmap = bitmap,
                source = source,
                taskId = taskId ?: current?.taskId,
            )
        }
        AppLog.i(TAG, "session=$sessionId package=$packageName source=$source taskId=$taskId")
    }

    fun iconFor(sessionId: Int): Icon? = entries[sessionId]
        ?.bitmap
        ?.takeUnless(Bitmap::isRecycled)
        ?.let(Icon::createWithBitmap)

    fun packageNameFor(sessionId: Int): String? = entries[sessionId]?.packageName

    fun taskIdFor(sessionId: Int): Int? = entries[sessionId]?.taskId

    fun snapshotForHistory(sessionId: Int): SourceIconHistorySnapshot? = entries[sessionId]
        ?.let { entry ->
            SourceIconHistorySnapshot(
                packageName = entry.packageName,
                taskId = entry.taskId,
                bitmap = runCatching {
                    entry.bitmap
                        ?.takeUnless(Bitmap::isRecycled)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                }.getOrNull(),
            )
        }

    fun restoreFromHistory(
        sessionId: Int,
        packageName: String?,
        bitmap: Bitmap?,
        taskId: Int?,
    ) {
        entries[sessionId] = Entry(packageName, bitmap, "recognition_history", taskId)
    }

    fun remove(sessionId: Int) {
        entries.remove(sessionId)?.bitmap?.recycle()
    }

    fun clear() {
        entries.values.forEach { it.bitmap?.recycle() }
        entries.clear()
    }
}

object SourceIconResolver {
    private const val TAG = "SourceIconResolver"
    const val WECHAT_PACKAGE = "com.tencent.mm"

    private val ignoredPackages = setOf(
        "com.android.systemui",
        "com.jizizr.signaldock",
    )
    fun inspect(
        service: AccessibilityService,
        activityClassName: String = "",
        verifyForegroundTask: Boolean = false,
    ): SourceAppInspection {
        SourceWindowDiagnostics.log(
            "stage=source-inspect-start verifyForegroundTask=$verifyForegroundTask " +
                "activityHint=$activityClassName",
        )
        SourceWindowDiagnostics.logWindows(service, "source-inspect-start")
        AppShell.logForegroundTaskSnapshotAsync("source-inspect-start")
        val activeRoot = service.rootInActiveWindow
        var rootSelection = "active-root"
        val initialRoot = activeRoot.takeIf(::isUsableRoot)
            ?: run {
                rootSelection = "focused-or-active-window"
                service.windows.asSequence()
                    .filter { it.isFocused || it.isActive }
                    .mapNotNull { it.root }
                    .firstOrNull(::isUsableRoot)
            }
            ?: run {
                rootSelection = "first-usable-window"
                service.windows.asSequence()
                    .mapNotNull { it.root }
                    .firstOrNull(::isUsableRoot)
            }
        val accessibilityPackageName = initialRoot?.packageName?.toString()
        val foregroundTask = if (
            verifyForegroundTask || accessibilityPackageName == WECHAT_PACKAGE
        ) {
            AppShell.findForegroundAppTask()
        } else {
            null
        }
        val foregroundMiniProgram = foregroundTask?.takeIf { task ->
            task.packageName == WECHAT_PACKAGE &&
                isWechatMiniProgramActivity(task.activityClassName)
        }
        val root = if (
            foregroundTask != null && accessibilityPackageName != foregroundTask.packageName
        ) {
            service.windows.asSequence()
                .mapNotNull { it.root }
                .firstOrNull { it.packageName?.toString() == foregroundTask.packageName }
                ?: initialRoot
        } else {
            initialRoot
        }
        val nodePackageName = root?.packageName?.toString()
        val packageName = resolveSourcePackageName(
            accessibilityPackageName = accessibilityPackageName,
            foregroundPackageName = foregroundTask?.packageName,
        )
        val resolvedActivityClassName = foregroundTask?.activityClassName ?: activityClassName
            .takeIf { candidate ->
                candidate.isNotBlank() &&
                    (packageName.isNullOrBlank() || candidate.startsWith(packageName))
            }
            ?: root?.className?.toString().orEmpty()
        val nodeSnapshot = collectPageNodeSnapshot(
            root = root,
            includeMiniProgramIcon = foregroundMiniProgram != null &&
                nodePackageName == WECHAT_PACKAGE,
        )
        val miniProgramTask = if (foregroundMiniProgram != null) {
            AppShell.findWechatMiniProgramTask(foregroundMiniProgram.taskId)
                ?: AppShell.findWechatMiniProgramTask()
        } else {
            null
        }
        val miniProgramIconHash = miniProgramTask?.icon?.let(IconFingerprint::hash).orEmpty()
        miniProgramTask?.icon?.takeUnless(Bitmap::isRecycled)?.recycle()
        AppLog.d(
            TAG,
            "source selection=$rootSelection package=$packageName " +
                "accessibilityPackage=$accessibilityPackageName " +
                "nodePackage=$nodePackageName " +
                "foregroundPackage=${foregroundTask?.packageName} " +
                "foregroundTask=${foregroundTask?.taskId ?: -1} " +
                "foregroundMiniProgramTask=${foregroundMiniProgram?.taskId ?: -1} " +
                "miniProgramNode=${nodeSnapshot.miniProgramIconBounds != null}",
        )
        return SourceAppInspection(
            packageName = packageName,
            activityClassName = resolvedActivityClassName,
            miniProgramTaskId = miniProgramTask?.taskId ?: foregroundMiniProgram?.taskId ?: -1,
            isWechatMiniProgram = foregroundMiniProgram != null,
            miniProgramIconBounds = nodeSnapshot.miniProgramIconBounds,
            miniProgramLabel = miniProgramTask?.label.orEmpty(),
            miniProgramIconHash = miniProgramIconHash,
            stableKeywords = nodeSnapshot.stableKeywords,
        )
    }

    private fun isUsableRoot(node: AccessibilityNodeInfo?): Boolean =
        node?.packageName?.toString()?.let { it !in ignoredPackages } == true

    fun resolveInitialIcon(
        context: Context,
        inspection: SourceAppInspection,
        screenshot: Bitmap,
    ): ResolvedSourceIcon? {
        var recentTaskId: Int? = null
        if (inspection.packageName == WECHAT_PACKAGE) {
            val recentTask = AppShell.findWechatMiniProgramTask(
                inspection.miniProgramTaskId.takeIf { it >= 0 },
            ) ?: AppShell.findWechatMiniProgramTask()
            recentTaskId = recentTask?.taskId
            recentTask?.icon?.let {
                return ResolvedSourceIcon(it, "wechat_recent_task", recentTask.taskId)
            }
            inspection.miniProgramIconBounds
                ?.let { cropBounds(screenshot, it) }
                ?.let {
                    return ResolvedSourceIcon(
                        it,
                        "wechat_accessibility_crop",
                        recentTask?.taskId,
                    )
                }
        }

        val packageName = inspection.packageName ?: return null
        return try {
            val icon = context.packageManager.getApplicationIcon(packageName)
                .toBitmap(width = 128, height = 128, config = Bitmap.Config.ARGB_8888)
            ResolvedSourceIcon(icon, "application_icon", recentTaskId)
        } catch (error: Exception) {
            AppLog.w(TAG, "Unable to load icon for $packageName", error)
            null
        }
    }

    private fun cropBounds(source: Bitmap, requested: Rect): Bitmap? {
        val clipped = Rect(requested)
        if (!clipped.intersect(0, 0, source.width, source.height)) return null
        if (clipped.width() < 24 || clipped.height() < 24) return null

        val side = (max(clipped.width(), clipped.height()) * 1.12f).roundToInt()
            .coerceAtMost(minOf(source.width, source.height))
        val centerX = clipped.centerX()
        val centerY = clipped.centerY()
        val left = (centerX - side / 2).coerceIn(0, source.width - side)
        val top = (centerY - side / 2).coerceIn(0, source.height - side)
        return Bitmap.createBitmap(source, left, top, side, side)
            .let { cropped ->
                cropped.scale(128, 128)
                    .also { if (it !== cropped) cropped.recycle() }
            }
    }

}

internal fun resolveSourcePackageName(
    accessibilityPackageName: String?,
    foregroundPackageName: String?,
): String? = foregroundPackageName?.takeIf(String::isNotBlank) ?: accessibilityPackageName

private val WECHAT_MINI_PROGRAM_ACTIVITY_REGEX =
    Regex("(^|.*\\.)AppBrandUI\\d*(\\$.*)?$")

internal fun isWechatMiniProgramActivity(className: String): Boolean =
    WECHAT_MINI_PROGRAM_ACTIVITY_REGEX.matches(className.trim())
