package com.jizizr.signaldock

import android.content.Context
import androidx.core.net.toUri
import android.os.Bundle
import android.provider.Settings
import android.util.Log

/**
 * HyperOS 超级岛设备检测与查询工具。
 *
 * 职责：
 * - 检测当前设备是否为小米 / HyperOS
 * - 查询超级岛支持情况、焦点通知协议版本、焦点通知权限
 *
 * 与现有 LiveUpdateService 完全独立，不会互相影响。
 */
object HyperIslandHelper {

    private const val TAG = "HyperIslandHelper"

    // ── 设备检测 ────────────────────────────────────────────

    /** 是否是小米（含 Redmi / POCO）设备 */
    fun isXiaomiDevice(): Boolean {
        val brand = android.os.Build.BRAND.lowercase()
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        return brand in listOf("xiaomi", "redmi", "poco")
                || manufacturer in listOf("xiaomi", "redmi", "poco")
    }

    /** 是否运行 HyperOS（通过 SystemProperties 检测） */
    fun isHyperOS(): Boolean {
        return getProp("ro.mi.os.version.name").isNotBlank()
                || getProp("ro.miui.ui.version.name").isNotBlank()
    }

    /** 合并判断：是否为符合条件的小米设备（小米 + HyperOS） */
    fun isEligibleDevice(): Boolean = isXiaomiDevice() && isHyperOS()

    // ── 超级岛查询 ──────────────────────────────────────────

    /** 查询系统是否支持超级岛（OS3 feature） */
    fun isSupportIsland(): Boolean {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getDeclaredMethod(
                "getBoolean",
                String::class.java,
                Boolean::class.javaPrimitiveType
            )
            method.invoke(null, "persist.sys.feature.island", false) as Boolean
        } catch (e: Exception) {
            Log.w(TAG, "isSupportIsland check failed", e)
            false
        }
    }

    /**
     * 查询焦点通知协议版本。
     * - 0 = 不支持
     * - 1 = OS1（基础焦点通知）
     * - 2 = OS2（增强焦点通知）
     * - 3 = OS3（超级岛 + 焦点通知）
     */
    fun getFocusProtocolVersion(context: Context): Int {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                "notification_focus_protocol",
                0
            )
        } catch (e: Exception) {
            Log.w(TAG, "getFocusProtocolVersion failed", e)
            0
        }
    }

    /**
     * 查询焦点通知权限（耗时操作，不要在主线程调用）。
     */
    fun hasFocusPermission(context: Context): Boolean {
        return try {
            val uri = "content://miui.statusbar.notification.public".toUri()
            val extras = Bundle().apply {
                putString("package", context.packageName)
            }
            val result = context.contentResolver.call(uri, "canShowFocus", null, extras)
            result?.getBoolean("canShowFocus", false) == true
        } catch (e: Exception) {
            Log.d(TAG, "hasFocusPermission check failed: $e")
            false
        }
    }

    /**
     * 综合判断：设备是否适合使用超级岛通知。
     * 需要同时满足：小米设备 + HyperOS + 支持超级岛（或协议版本≥3）
     */
    fun shouldUseSuperIsland(context: Context): Boolean {
        if (!isEligibleDevice()) return false
        return isSupportIsland() || getFocusProtocolVersion(context) >= 3
    }

    // ── 内部工具 ────────────────────────────────────────────

    private fun getProp(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getDeclaredMethod("get", String::class.java, String::class.java)
            method.invoke(null, key, "") as String
        } catch (_: Exception) {
            ""
        }
    }
}
