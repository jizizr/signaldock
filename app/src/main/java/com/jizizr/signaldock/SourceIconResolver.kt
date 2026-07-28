package com.jizizr.signaldock

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

data class SourceAppInspection(
    val packageName: String?,
    val miniProgramIconBounds: Rect?,
)

data class ResolvedSourceIcon(
    val bitmap: Bitmap,
    val source: String,
    val taskId: Int? = null,
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
        Log.i(TAG, "session=$sessionId package=$packageName source=$source taskId=$taskId")
    }

    fun iconFor(sessionId: Int): Icon? = entries[sessionId]
        ?.bitmap
        ?.takeUnless(Bitmap::isRecycled)
        ?.let(Icon::createWithBitmap)

    fun packageNameFor(sessionId: Int): String? = entries[sessionId]?.packageName

    fun taskIdFor(sessionId: Int): Int? = entries[sessionId]?.taskId

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
    private val iconKeywords = listOf(
        "appbrand", "mini", "avatar", "head", "logo", "icon", "小程序", "头像",
    )

    fun inspect(service: AccessibilityService): SourceAppInspection {
        val roots = buildList {
            service.rootInActiveWindow?.let(::add)
            service.windows
                .asSequence()
                .sortedByDescending { it.isFocused || it.isActive }
                .mapNotNull { it.root }
                .forEach(::add)
        }
        val root = roots.firstOrNull { node ->
            node.packageName?.toString()?.let { it !in ignoredPackages } == true
        }
        val packageName = root?.packageName?.toString()
        val miniProgramBounds = if (packageName == WECHAT_PACKAGE) {
            findMiniProgramIconBounds(root)
        } else {
            null
        }
        Log.i(TAG, "foreground package=$packageName miniProgramNode=${miniProgramBounds != null}")
        return SourceAppInspection(packageName, miniProgramBounds)
    }

    fun resolveInitialIcon(
        context: Context,
        inspection: SourceAppInspection,
        screenshot: Bitmap,
    ): ResolvedSourceIcon? {
        var recentTaskId: Int? = null
        if (inspection.packageName == WECHAT_PACKAGE) {
            val recentTask = AppShell.findWechatMiniProgramTask()
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
            Log.w(TAG, "Unable to load icon for $packageName", error)
            null
        }
    }

    private fun findMiniProgramIconBounds(root: AccessibilityNodeInfo): Rect? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var best: Pair<Int, Rect>? = null
        var visited = 0
        while (queue.isNotEmpty() && visited < 2000) {
            val node = queue.removeFirst()
            visited++
            val bounds = Rect().also(node::getBoundsInScreen)
            val width = bounds.width()
            val height = bounds.height()
            if (width in 36..320 && height in 36..320) {
                val descriptor = buildString {
                    append(node.viewIdResourceName.orEmpty())
                    append(' ')
                    append(node.contentDescription?.toString().orEmpty())
                    append(' ')
                    append(node.className?.toString().orEmpty())
                }.lowercase()
                val keywordScore = iconKeywords.count(descriptor::contains) * 40
                val imageScore = if (descriptor.contains("image")) 25 else 0
                val squareScore = if (max(width, height).toFloat() / minOf(width, height) <= 1.35f) 15 else 0
                val edgePenalty = if (bounds.top < 260 && bounds.left > 800) 80 else 0
                val score = keywordScore + imageScore + squareScore - edgePenalty
                if (score >= 60 && (best == null || score > best.first)) {
                    best = score to Rect(bounds)
                }
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
        }
        return best?.second
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
