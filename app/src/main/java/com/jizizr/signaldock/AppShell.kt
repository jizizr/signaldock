package com.jizizr.signaldock

import android.app.ActivityManager
import android.content.Context
import android.content.pm.IPackageManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.IConnectivityManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Bundle
import android.provider.Settings
import androidx.core.graphics.scale
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.Executors

/**
 * 全局唯一的 Shizuku 管理器。
 *
 * 设计原则：
 * - 不再单独开启 :shell 进程，直接在主进程通过 ShizukuBinderWrapper 代理 Binder 调用
 * - [isShizukuAvailable]：检查 Shizuku 是否可用且已授权
 * - [enableAccessibility]：共用的无障碍开启逻辑，避免 Activity / Tile 重复实现
 */
object AppShell {
    private const val TAG = "AppShell"
    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val WECHAT_APP_BRAND_ACTIVITY = ".plugin.appbrand.ui.AppBrandUI"
    private const val RECENT_WITH_EXCLUDED = 0x0001

    data class WechatMiniProgramTask(
        val taskId: Int,
        val icon: Bitmap?,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val privilegedExecutor = Executors.newSingleThreadExecutor()

    /** 检查 Shizuku 是否可用且已授权 */
    val isShizukuAvailable: Boolean
        get() = runCatching {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** 当前 Shizuku 服务身份；0 为 root，2000 为 shell。 */
    val shizukuUid: Int
        get() = if (isShizukuAvailable) runCatching { Shizuku.getUid() }.getOrDefault(-1) else -1

    val isShizukuRoot: Boolean
        get() = shizukuUid == 0

    /**
     * Lazily obtained proxy to the system ConnectivityService via Binder IPC.
     * [SystemServiceHelper.getSystemService] gets the original binder, and
     * [ShizukuBinderWrapper] wraps it so that transact calls are forwarded to Shizuku.
     */
    private val iConnectivityManager: IConnectivityManager by lazy {
        val originalBinder = SystemServiceHelper.getSystemService(Context.CONNECTIVITY_SERVICE)
            ?: throw IllegalStateException("ConnectivityService binder not available")
        val originalService = IConnectivityManager.Stub.asInterface(originalBinder)
            ?: throw IllegalStateException("Platform IConnectivityManager unavailable")
        val wrapper = ShizukuBinderWrapper(originalService.asBinder())
        IConnectivityManager.Stub.asInterface(wrapper)
    }

    /**
     * Lazily obtained proxy to the system PackageManager via Binder IPC.
     */
    private val iPackageManager: IPackageManager by lazy {
        val originalBinder = SystemServiceHelper.getSystemService("package")
            ?: throw IllegalStateException("PackageManager binder not available")
        val originalService = IPackageManager.Stub.asInterface(originalBinder)
            ?: throw IllegalStateException("Platform IPackageManager unavailable")
        val wrapper = ShizukuBinderWrapper(originalService.asBinder())
        IPackageManager.Stub.asInterface(wrapper)
    }

    /**
     * Block or unblock network for [uid] via [IConnectivityManager] Binder IPC.
     *
     * Uses FIREWALL_CHAIN_OEM_DENY_3 (chain 9):
     * - **Block**: enable chain → DENY rule
     * - **Unblock**: DEFAULT rule
     */
    fun setPackageNetworkingEnabled(uid: Int, enabled: Boolean): String? {
        if (!isShizukuAvailable) {
            return "Shizuku is not available or permission not granted"
        }
        return try {
            val cm = iConnectivityManager
            val chain = 9  // FIREWALL_CHAIN_OEM_DENY_3
            val rule = if (enabled) 0 else 2  // FIREWALL_RULE_DEFAULT : FIREWALL_RULE_DENY

            if (!enabled) {
                cm.setFirewallChainEnabled(chain, true)
                cm.setUidFirewallRule(chain, uid, rule)
            } else {
                cm.setUidFirewallRule(chain, uid, rule)
                // Do not disable the entire chain when restoring a single app
            }
            AppLog.d(TAG, "Network ${if (enabled) "RESTORED" else "BLOCKED"} for uid=$uid")
            null // Success
        } catch (t: Throwable) {
            AppLog.e(TAG, "setPackageNetworkingEnabled failed", t)
            t.stackTraceToString()
        }
    }



    /**
     * Returns the icon WeChat publishes for its newest mini-program task in Recents.
     * The hidden task-manager interface is reflected so its Binder is still routed
     * through Shizuku instead of running with the application's restricted identity.
     */
    @android.annotation.SuppressLint("BlockedPrivateApi")
    @Suppress("DEPRECATION")
    fun findWechatMiniProgramTask(): WechatMiniProgramTask? {
        if (!isShizukuAvailable) {
            AppLog.w(TAG, "Cannot read recent tasks: Shizuku unavailable")
            return null
        }

        return try {
            val originalBinder = SystemServiceHelper.getSystemService("activity_task")
                ?: throw IllegalStateException("ActivityTaskManager binder not available")
            val wrapper = ShizukuBinderWrapper(originalBinder)
            val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
            val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            val taskManager = asInterface.invoke(null, wrapper)
                ?: throw IllegalStateException("IActivityTaskManager proxy not available")
            val expectedParameters = arrayOf(
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            val getRecentTasks = taskManager.javaClass.methods.first { method ->
                method.name == "getRecentTasks" &&
                    method.parameterTypes.contentEquals(expectedParameters)
            }
            val userId = android.os.Process.myUid() / 100000
            val slice = getRecentTasks.invoke(taskManager, 20, RECENT_WITH_EXCLUDED, userId)
                ?: return null
            val tasks = slice.javaClass.getMethod("getList").invoke(slice) as? List<*>
                ?: return null

            for (item in tasks) {
                val task = item as? ActivityManager.RecentTaskInfo ?: continue
                val component = task.topActivity ?: task.baseActivity ?: task.baseIntent.component
                if (component?.packageName != WECHAT_PACKAGE ||
                    !component.className.contains(WECHAT_APP_BRAND_ACTIVITY)
                ) {
                    continue
                }

                val taskDescription = task.taskDescription
                val taskIcon = taskDescription?.let { description ->
                    description.javaClass
                        .getMethod("getInMemoryIcon")
                        .invoke(description) as? Bitmap
                } ?: run {
                        val iconFilename = taskDescription?.let { description ->
                            description.javaClass
                                .getMethod("getIconFilename")
                                .invoke(description) as? String
                        } ?: return@run null
                        val expectedIconParameters = arrayOf(
                            String::class.java,
                            Int::class.javaPrimitiveType,
                        )
                        val getTaskDescriptionIcon = taskManager.javaClass.methods.first { method ->
                            method.name == "getTaskDescriptionIcon" &&
                                method.parameterTypes.contentEquals(expectedIconParameters)
                        }
                        getTaskDescriptionIcon.invoke(taskManager, iconFilename, userId) as? Bitmap
                    }
                val scaled = taskIcon
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                    ?.let { copy ->
                        copy.scale(128, 128).also { if (it !== copy) copy.recycle() }
                    }
                AppLog.i(
                    TAG,
                    "Found WeChat mini-program recent task " +
                        "taskId=${task.taskId} component=${component.className} icon=${scaled != null}",
                )
                return WechatMiniProgramTask(task.taskId, scaled)
            }

            AppLog.w(TAG, "No WeChat mini-program task with an icon found in Recents")
            null
        } catch (error: Throwable) {
            AppLog.e(TAG, "Failed to read WeChat mini-program icon from Recents", error)
            null
        }
    }

    /** Brings an existing recent task to the foreground without relaunching its package. */
    @android.annotation.SuppressLint("BlockedPrivateApi")
    fun startActivityFromRecents(taskId: Int): Boolean {
        if (!isShizukuAvailable || taskId < 0) return false
        return try {
            val originalBinder = SystemServiceHelper.getSystemService("activity_task")
                ?: throw IllegalStateException("ActivityTaskManager binder not available")
            val wrapper = ShizukuBinderWrapper(originalBinder)
            val stubClass = Class.forName("android.app.IActivityTaskManager\$Stub")
            val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            val taskManager = asInterface.invoke(null, wrapper)
                ?: throw IllegalStateException("IActivityTaskManager proxy not available")
            val method = taskManager.javaClass.methods.first { candidate ->
                candidate.name == "startActivityFromRecents" &&
                    candidate.parameterTypes.size == 2 &&
                    candidate.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    candidate.parameterTypes[1] == Bundle::class.java
            }
            val result = method.invoke(taskManager, taskId, null) as? Int ?: -1
            AppLog.i(TAG, "startActivityFromRecents taskId=$taskId result=$result")
            result >= 0
        } catch (error: Throwable) {
            AppLog.e(TAG, "Unable to restore recent task $taskId", error)
            false
        }
    }

    /**
     * 使用 Shizuku 提权为自身赋予 WRITE_SECURE_SETTINGS 权限，
     * 然后优雅地使用原生 API 开启 [AccessibilityScreenshotService]。
     */
    fun enableAccessibility(ctx: Context, onDone: (Boolean) -> Unit) {
        if (!isShizukuAvailable) {
            mainHandler.post { onDone(false) }
            return
        }
        privilegedExecutor.execute {
            try {
                // 1. 优雅提权：如果应用自身没有 WRITE_SECURE_SETTINGS 权限，则通过 Binder 赋予自己
                if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
                    // 获取当前用户 ID (兼容全版本 Android 的 UserHandle.getUserId(uid) 算法)
                    val userId = android.os.Process.myUid() / 100000
                    iPackageManager.grantRuntimePermission(
                        ctx.packageName,
                        android.Manifest.permission.WRITE_SECURE_SETTINGS,
                        userId
                    )
                }

                // 2. 原生调用：拥有权限后，直接使用 Android 原生 API 修改 Settings
                val resolver = ctx.contentResolver
                val component = "${ctx.packageName}/${AccessibilityScreenshotService::class.java.name}"

                val existing = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""

                val newValue = when {
                    existing.isEmpty() || existing == "null" -> component
                    existing.contains(component)            -> existing
                    else                                    -> "$existing:$component"
                }

                // 避免重复写入触发不必要的 ContentObserver 回调
                if (newValue != existing) {
                    Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newValue)
                }
                Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")

                mainHandler.post { onDone(true) }
            } catch (e: Exception) {
                AppLog.e(TAG, "enableAccessibility failed", e)
                mainHandler.post { onDone(false) }
            }
        }
    }
}
