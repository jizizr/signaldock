package com.jizizr.signaldock

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.AppOpsManager
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

object AutoPageBackgroundPolicy {
    enum class AutoStartState { ENABLED, DISABLED, UNKNOWN }

    @Suppress("DiscouragedPrivateApi")
    private val checkOpNoThrowMethod by lazy {
        AppOpsManager::class.java.getDeclaredMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        ).apply { isAccessible = true }
    }

    fun isBatteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    fun requirementsSatisfied(context: Context): Boolean = isBatteryUnrestricted(context)

    fun autoStartState(context: Context): AutoStartState = runCatching {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = checkOpNoThrowMethod.invoke(
            appOps,
            OP_AUTO_START,
            Process.myUid(),
            context.packageName,
        ) as Int
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> AutoStartState.ENABLED
            AppOpsManager.MODE_IGNORED,
            AppOpsManager.MODE_ERRORED,
            -> AutoStartState.DISABLED
            else -> AutoStartState.UNKNOWN
        }
    }.getOrDefault(AutoStartState.UNKNOWN)

    fun autoStartSettingsIntent(context: Context): Intent {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            ),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        )
        return candidates.firstOrNull { it.resolveActivity(context.packageManager) != null }
            ?: candidates.last()
    }

    fun batterySettingsIntent(context: Context): Intent {
        val packageUri = Uri.parse("package:${context.packageName}")
        val candidates = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
        )
        return candidates.firstOrNull { it.resolveActivity(context.packageManager) != null }
            ?: candidates.last()
    }

    private const val OP_AUTO_START = 10008

}
