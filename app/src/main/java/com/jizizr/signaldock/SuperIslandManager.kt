package com.jizizr.signaldock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 超级岛通知管理器 —— 独立于现有 LiveUpdateService 的平行通知体系。
 *
 * 核心流程（绕过白名单）：
 * 1. 通过 Shizuku UserService 的 IConnectivityManager Binder IPC 禁用 xmsf 网络
 * 2. 发送包含超级岛参数的 Notification
 * 3. 盲窗等待 SystemUI 完成异步认证
 * 4. 恢复 xmsf 网络
 *
 * 网络操作使用 FIREWALL_CHAIN_OEM_DENY_3 (chain 9) 直接 Binder 调用，
 * 匹配 InstallerX-Revived 方案 — 无 shell 命令参与。
 */
object SuperIslandManager : SessionNotificationManager {

    private const val TAG = "SuperIslandManager"
    private const val CHANNEL_ID = "super_island_channel"
    private const val XMSF_PKG = "com.xiaomi.xmsf"

    /** 超级岛调试通知 ID（与 LiveUpdateService 的 ID 不冲突） */
    private const val ISLAND_NOTIFICATION_ID = 9001
    private const val MAX_TEST_NOTIFICATION_ID = 9999

    /**
     * xmsf 网络盲窗时长（ms），保持 disable 状态足够久，
     * 让 SystemUI 在 xmsf 无网期间完成异步认证。
     * 参考 InstallerX-Revived XIAOMI_MAGIC_BLIND_WINDOW_MS。
     */
    private const val BLIND_WINDOW_MS = 100L
    private const val MAX_NETWORK_RETRIES = 2
    private const val NETWORK_RETRY_DELAY_MS = 50L

    /**
     * 单线程执行器，保证 bypass 严格串行（等价于 InstallerX 的 Mutex）。
     * 前一个 disable→notify→delay→enable 全部完成后才开始下一个。
     */
    private val bypassExecutor = newIdleExecutor(
        "SignalDock-IslandBypass",
        1,
        android.os.Process.THREAD_PRIORITY_BACKGROUND,
    )
    private val networkBlockActive = AtomicBoolean(false)
    private val nextTestNotificationId = AtomicInteger(ISLAND_NOTIFICATION_ID)
    private val lastTestNotificationId = AtomicInteger(-1)
    @Volatile
    private var blockedUid: Int = -1

    // ── 通知渠道 ────────────────────────────────────────────

    override fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "超级岛通知", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "HyperOS 超级岛样式通知"
                }
            )
        }
    }

    // ── 内部：含白名单绕过的通知发送 ─────────────────────────

    private var xmsfUid: Int = -1

    private fun getXmsfUid(context: Context): Int {
        if (xmsfUid != -1) return xmsfUid
        return try {
            xmsfUid = context.packageManager.getPackageUid(XMSF_PKG, 0)
            xmsfUid
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to get UID for $XMSF_PKG", e)
            -1
        }
    }

    /** Enqueue a notification while the XMSF network is briefly blocked. */
    private fun withBypass(context: Context, block: () -> Unit) {
        if (SuperIslandSettingsStore.networkBypassMode == NetworkBypassMode.DISABLED) {
            block()
            return
        }
        val uid = getXmsfUid(context)
        if (uid == -1) {
            AppLog.w(TAG, "withBypass: failed to get xmsf uid, skipping bypass")
            block()
            return
        }
        bypassExecutor.execute {
            executeBypass(uid, block)
        }
    }

    /** Synchronous variant used by the debug notification path. */
    private fun withBypassBlocking(context: Context, block: () -> Unit) {
        if (SuperIslandSettingsStore.networkBypassMode == NetworkBypassMode.DISABLED) {
            block()
            return
        }
        val uid = getXmsfUid(context)
        if (uid == -1 || !AppShell.isShizukuAvailable) {
            block()
            return
        }
        bypassExecutor.submit { executeBypass(uid, block) }.get()
    }

    private fun executeBypass(uid: Int, block: () -> Unit) {
        if (!AppShell.isShizukuAvailable) {
            AppLog.w(TAG, "Shizuku unavailable; sending notification without bypass")
            block()
            return
        }

        var networkBlockAttempted = false
        try {
            networkBlockAttempted = true
            networkBlockActive.set(true)
            blockedUid = uid
            val networkBlocked = setXmsfNetworkingWithRetry(uid, enabled = false)
            block()
            if (networkBlocked) {
                Thread.sleep(currentBypassDurationMs())
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            AppLog.w(TAG, "Bypass worker interrupted", interrupted)
        } catch (error: Throwable) {
            AppLog.e(TAG, "Bypass notification failed", error)
        } finally {
            if (networkBlockAttempted) {
                restoreXmsfNetworking(uid)
            }
        }
    }

    private fun currentBypassDurationMs(): Long = when (SuperIslandSettingsStore.networkBypassMode) {
        NetworkBypassMode.CUSTOM -> SuperIslandSettingsStore.networkBypassDurationMs.toLong()
        NetworkBypassMode.STANDARD -> BLIND_WINDOW_MS
        NetworkBypassMode.DISABLED -> 0L
    }

    private fun setXmsfNetworkingWithRetry(uid: Int, enabled: Boolean): Boolean {
        var lastError: String? = null
        repeat(MAX_NETWORK_RETRIES) { attempt ->
            val error = AppShell.setPackageNetworkingEnabled(uid, enabled)
            if (error == null) return true
            lastError = error
            AppLog.w(TAG, "XMSF network ${if (enabled) "restore" else "block"} failed " +
                "(${attempt + 1}/$MAX_NETWORK_RETRIES): $error")
            if (attempt + 1 < MAX_NETWORK_RETRIES) Thread.sleep(NETWORK_RETRY_DELAY_MS)
        }
        AppLog.e(TAG, "XMSF network operation failed: $lastError")
        return false
    }

    private fun restoreXmsfNetworking(uid: Int) {
        val restored = setXmsfNetworkingWithRetry(uid, enabled = true)
        if (restored) {
            networkBlockActive.set(false)
            blockedUid = -1
        } else {
            AppLog.e(TAG, "XMSF network restore failed; leaving diagnostic state active")
        }
    }

    // ── 构建超级岛通知 ──────────────────────────────────────

    /**
     * 构建一条包含超级岛参数的 Notification。
     *
     * 展开态使用模板 10：文本组件2 + 识别图形组件1 + 按钮组件3。
     * ┌─────────────────────────────────────┐
     * │  baseInfo                 │ picInfo  │
     * │  商品名 [规格] / 商家名     │ 商家 Logo │
     * ├──────────────────────────┴──────────┤
     * │  hintInfo (按钮组件3)               │
     * │  取餐码  [价格标签]      [操作按钮] │
     * └────────────────────────────────────-┘
     *
     * @param title      通知标题及无商品信息时的主要文本
     * @param content    通知详情及无商家信息时的次要文本
     * @param ticker     状态栏显示文案
     * @param keyText    小岛右侧文本 + hintInfo 主要文本（如取餐号、识别结果）
     * @param ongoing    是否为持续性通知（识别中 = true，结果 = false）
     * @param updatable  是否可更新
     * @param islandTimeout 岛自动消失时间（秒）
     * @param hintActionTitle 按钮组件3 操作按钮文字，空则不显示按钮
     */
    private fun buildIslandNotification(
        context: Context,
        title: String,
        content: String,
        ticker: String,
        keyText: String = "",
        price: String = "",
        itemTitle: String = "",
        itemSubtitle: String = "",
        merchant: String = "",
        ongoing: Boolean = false,
        updatable: Boolean = true,
        islandTimeout: Int = 60,
        expandedTime: Int = 0,      // 展开态保持时间（秒），0=立即收起
        actionTitle: String = "",
        actionPendingIntent: PendingIntent? = null,    // 下层文字按钮 PendingIntent（如"已完成"）
        contentPendingIntent: PendingIntent? = null,   // 岛点击/下拉小窗使用的 Activity
        deleteIntent: PendingIntent? = null,           // 滑动删除时触发的 PendingIntent
        sourceIcon: Icon? = null,
        dragShareTitle: String = "",
        dragShareDescription: String = "",
        dragShareContent: String = "",
        statusOnly: Boolean = false,
    ): Notification {
        ensureChannels(context)
        val appIcon = sourceIcon
            ?: Icon.createWithResource(context, R.mipmap.ic_launcher)

        // ── pics Bundle ──
        val pics = Bundle().apply {
            putParcelable("miui.focus.pic_ticker", appIcon)
            putParcelable("miui.focus.pic_small", appIcon)
            putParcelable("miui.focus.pic_icon", appIcon)
        }

        // ── 展开态：文本组件2 (baseInfo) ──
        val displayKeyText = keyText.ifBlank { title }
        val productText = islandProductTexts(title, content, itemTitle, itemSubtitle, merchant)
        val displayPrice = price.trim().take(8)
        val baseInfo = JSONObject().apply {
            put("type", 2)
            put("title", if (statusOnly) displayKeyText else productText.title)
            put("content", if (statusOnly) content else productText.merchant)
            put("colorTitle", "#000000")
            put("colorTitleDark", "#FFFFFF")
            put("colorContent", "#666666")
            put("colorContentDark", "#B8B8B8")
            put("showDivider", false)
            put("showContentDivider", false)
            if (!statusOnly && productText.detail.isNotBlank()) {
                put("specialTitle", productText.detail)
                put("colorSpecialTitle", "#2F80ED")
                put("colorSpecialTitleDark", "#79B8FF")
                put("colorSpecialBg", "#182F80ED")
            }
        }

        // ── 展开态：识别图形组件1 (picInfo) ──
        val actionKey1 = "miui.focus.action_main" // 下层文字按钮（已完成）
        val expandPicInfo = JSONObject().apply {
            put("type", 1)
            put("pic", "miui.focus.pic_icon")
            put("picDark", "miui.focus.pic_icon")
        }

        // ── 展开态：按钮组件3 (hintInfo) ──
        val hintInfo = JSONObject().apply {
            put("type", 1)
            put("title", displayKeyText)
            put("colorTitle", "#000000")
            put("colorTitleDark", "#FFFFFF")
            if (displayPrice.isNotBlank()) {
                put("content", displayPrice)
                put("colorContent", "#D94B30")
                put("colorContentDark", "#FF9B82")
                put("colorContentBg", "#18E85D3F")
            }
            if (actionTitle.isNotBlank() && actionPendingIntent != null) {
                put("actionInfo", JSONObject().apply {
                    put("action", actionKey1)
                    put("actionTitle", actionTitle)
                    put("actionTitleColor", "#FFFFFF")
                    put("actionTitleColorDark", "#000000")
                    put("actionBgColor", "#1A1A1A")
                    put("actionBgColorDark", "#E0E0E0")
                    put("clickWithCollapse", false)
                })
            }
        }

        // ── 超级岛配置 (param_island) ──
        // 小岛（压缩态）：仅显示图标圈
        val smallPicInfo = JSONObject().apply {
            put("type", 1)
            put("pic", "miui.focus.pic_small")
        }
        val smallIslandArea = JSONObject().apply {
            put("picInfo", smallPicInfo)
        }

        // 大岛（摘要态胶囊）：左侧圆形图标，右侧关键文字
        // imageTextInfoLeft：只放 picInfo（图标）
        val imageTextInfoLeft = JSONObject().apply {
            put("type", 1)  // 1 = 左边组件
            put("picInfo", JSONObject().apply {
                put("type", 1)
                put("pic", "miui.focus.pic_icon")
            })
        }
        // imageTextInfoRight：摘要态保持紧凑，只显示关键码；价格仅在展开态展示。
        val useNarrowFont = supportsNarrowFont(displayKeyText)
        val capsuleText = islandCapsuleText(displayKeyText, useNarrowFont)
        val imageTextInfoRight = JSONObject().apply {
            put("type", 2)  // 2 = 右边组件
            put("textInfo", JSONObject().apply {
                put("title", capsuleText)
                put("colorTitle", "#FFFFFF")      // 胶囊背景深，文字用白色
                put("turnAnim", false)
                put("narrowFont", useNarrowFont)
                put("showHighlightColor", false)
            })
        }
        val bigIslandArea = if (statusOnly) {
            // Xiaomi's image-text island keeps the icon and the two-line text in separate
            // protocol components. Putting textInfo beside picInfo in the left component
            // makes HyperOS silently discard both on some versions.
            JSONObject().apply {
                put("imageTextInfoLeft", JSONObject().apply {
                    put("type", 1)
                    put("picInfo", JSONObject().apply {
                        put("type", 1)
                        put("pic", "miui.focus.pic_icon")
                    })
                })
                put("imageTextInfoRight", JSONObject().apply {
                    put("type", 3)
                    put("textInfo", JSONObject().apply {
                        put("title", displayKeyText)
                        put("content", content)
                        put("colorTitle", "#FFFFFF")
                        put("colorContent", "#B8B8B8")
                        put("turnAnim", false)
                        put("narrowFont", false)
                        put("showHighlightColor", false)
                    })
                })
            }
        } else {
            JSONObject().apply {
                put("imageTextInfoLeft", imageTextInfoLeft)
                put("imageTextInfoRight", imageTextInfoRight)
            }
        }

        val paramIsland = JSONObject().apply {
            put("islandProperty", 1)
            put("islandPriority", 2)
            put("islandTimeout", islandTimeout)
            put("dismissIsland", false)
            put("expandedTime", expandedTime)
            put("maxSize", false)
            put("needCloseAnimation", true)
            put("bigIslandArea", bigIslandArea)
            put("smallIslandArea", smallIslandArea)
            if (dragShareContent.isNotBlank()) {
                put("shareData", JSONObject().apply {
                    put("pic", "miui.focus.pic_icon")
                    put("title", dragShareTitle.ifBlank { title }.take(32))
                    put("content", dragShareDescription.ifBlank { content }.take(80))
                    put("shareContent", dragShareContent.take(500))
                })
            }
        }

        // ── param_v2 ──
        val paramV2 = JSONObject().apply {
            put("protocol", 1)
            put("enableFloat", true)
            put("updatable", updatable)
            put("ticker", ticker)
            put("tickerPic", "miui.focus.pic_ticker")
            if (SuperIslandSettingsStore.outerGlowEnabled) {
                put("outEffectSrc", "outer_glow")
            }
            put("isShowNotification", true)
            put("islandFirstFloat", true)
            put("timeout", islandTimeout)
            put("baseInfo", baseInfo)       // 商品名 + 同行规格标签 + 商家
            if (!statusOnly) {
                put("picInfo", expandPicInfo)   // 右侧商家 Logo
                put("hintInfo", hintInfo)       // 取餐码 + 价格标签 + 完成按钮
            }
            put("param_island", paramIsland)
        }

        val param = JSONObject().apply {
            put("param_v2", paramV2)
        }

        AppLog.d(
            TAG,
            if (statusOnly) {
                "Building single-area status island"
            } else {
                "Building template 10: protocol=1 baseInfo + hintInfo " +
                    "sourceIcon=${sourceIcon != null}"
            },
        )

        // ── 组装 extras Bundle ──
        val extras = Bundle().apply {
            putString("miui.focus.param", param.toString())
            putBundle("miui.focus.pics", pics)
            // 挂载原生 Action：HyperOS 通过 actionInfo.action key 引用，点击后直接触发 PendingIntent
            val actionsBundle = Bundle()
            // 下层文字按钮（已完成）
            if (actionPendingIntent != null && actionTitle.isNotBlank()) {
                actionsBundle.putParcelable(
                    actionKey1,
                    Notification.Action.Builder(
                        null,
                        actionTitle,
                        actionPendingIntent
                    ).build()
                )
            }
            if (actionsBundle.size() > 0) putBundle("miui.focus.actions", actionsBundle)
        }

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(ongoing)
            .setAutoCancel(false)
            .addExtras(extras)
            .also { if (contentPendingIntent != null) it.setContentIntent(contentPendingIntent) }
            .also { if (deleteIntent != null) it.setDeleteIntent(deleteIntent) }
            .build()
    }

    // ── 对外 API ────────────────────────────────────────────

    /**
     * 发送一条超级岛调试通知（带白名单绕过）。
     *
     * 完整流程：
     * 1. 确认 Shizuku Shell 已连接
     * 2. 禁用 xmsf 网络
     * 3. 发送通知
     * 4. 短暂等待（让 SystemUI 处理通知）
     * 5. 恢复 xmsf 网络
     *
     * 必须在后台线程调用（内含 Thread.sleep 和 shell 阻塞操作）。
     *
     * @return 描述结果的字符串，用于 UI 显示
     */
    fun sendTestIslandNotification(context: Context): String {
        val bypassEnabled = SuperIslandSettingsStore.networkBypassMode != NetworkBypassMode.DISABLED
        if (bypassEnabled && !AppShell.isShizukuAvailable) {
            return "Shizuku Shell 未连接，请先授权 Shizuku"
        }

        val uid = if (bypassEnabled) getXmsfUid(context) else -1
        if (bypassEnabled && uid == -1) return "无法获取 xmsf UID"
        val notificationId = nextTestNotificationId.updateAndGet { current ->
            if (current >= MAX_TEST_NOTIFICATION_ID) ISLAND_NOTIFICATION_ID else current + 1
        }

        return try {
            withBypassBlocking(context) {
                val notificationManager = context.getSystemService(NotificationManager::class.java)
                lastTestNotificationId.getAndSet(notificationId)
                    .takeIf { it != -1 && it != notificationId }
                    ?.let(notificationManager::cancel)
                val notification = buildIslandNotification(
                    context = context,
                    title = "信岛测试",
                    content = "超级岛通知测试成功",
                    ticker = "信岛 · 超级岛测试",
                    keyText = "测试",
                    expandedTime = 5,
                    actionTitle = "测试",
                    actionPendingIntent = PendingIntent.getActivity(
                        context,
                        notificationId,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                notificationManager.notify(notificationId, notification)
                AppLog.i(TAG, "Test island notification sent with fresh id=$notificationId")
            }

            "超级岛通知已发送"
        } catch (e: Exception) {
            AppLog.e(TAG, "sendTestIslandNotification failed", e)
            "发送失败: ${e.message}"
        }
    }

    // ── SessionNotificationManager overrides ────────────────────────────────

    override fun notificationIdForSession(sessionId: Int): Int = sessionId + 3000
    fun islandIdForSession(sessionId: Int): Int = notificationIdForSession(sessionId)

    /**
     * bypass 激活期间调用 [onForegroundReady]：此时 xmsf 网络已禁用，
     * LiveUpdateService 在回调中 startForeground，xmsf 第一次见到该 ID 就是岛通知。
     */
    override fun sendRecognizingNotification(
        context: Context,
        sessionId: Int,
        onForegroundReady: (notifId: Int, notif: Notification) -> Unit,
    ) {
        val islandId = notificationIdForSession(sessionId)
        val dismissPI = PendingIntent.getService(
            context, sessionId,
            Intent(context, LiveUpdateService::class.java).apply {
                action = LiveUpdateService.ACTION_STOP
                putExtra(LiveUpdateService.EXTRA_SESSION_ID, sessionId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        withBypass(context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val notification = buildIslandNotification(
                context = context,
                title = "识别中",
                content = "正在分析截图",
                ticker = "识别中",
                keyText = "识别中",
                ongoing = false,
                updatable = true,
                islandTimeout = 120,
                expandedTime = 0,
                deleteIntent = dismissPI,
                sourceIcon = SourceIconCache.iconFor(sessionId),
                statusOnly = true,
            )
            nm.notify(islandId, notification)
            onForegroundReady(islandId, notification)
            AppLog.i(TAG, "Recognizing island sent for session $sessionId (id=$islandId)")
        }
    }

    override fun sendResultNotification(
        context: Context,
        sessionId: Int,
        result: SessionNotificationResult,
        dismissIntent: PendingIntent,
    ) {
        val content = result.details.ifBlank { result.title }.ifBlank { "无内容" }
        sendResultIsland(context, sessionId, result, content, dismissIntent)
    }

    override fun transferForeground(
        context: Context,
        sessionId: Int,
        onReady: (notifId: Int, notif: Notification) -> Unit,
    ) {
        val notifId = notificationIdForSession(sessionId)
        withBypass(context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val existing = nm.activeNotifications.firstOrNull { it.id == notifId }?.notification
                ?: return@withBypass
            onReady(notifId, existing)
        }
    }

    override fun cancelForSession(context: Context, sessionId: Int) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(notificationIdForSession(sessionId))
    }

    override fun onServiceDestroy(context: Context) {
        val uid = blockedUid
        if (networkBlockActive.get() && uid != -1) {
            bypassExecutor.execute { restoreXmsfNetworking(uid) }
        }
    }

    // ── 截图分析集成 API ─────────────────────────────────────

    /**
     * 发送分析结果超级岛通知（对应 LiveUpdateService.ACTION_UPDATE）。
     * 在后台线程完成白名单绕过 + 通知发送，调用者无需关心线程。
     */
    fun sendResultIsland(
        context: Context,
        sessionId: Int,
        result: SessionNotificationResult,
        content: String,
        dismissPendingIntent: PendingIntent? = null,
    ) {
        val islandId = islandIdForSession(sessionId)
        withBypass(context) {
            val displayTitle = result.title.ifBlank { "截图分析" }
            val displayContent = content.ifBlank { "无内容" }
            val nm = context.getSystemService(NotificationManager::class.java)
            val share = renderIslandShare(
                SuperIslandSettingsStore.shareTemplate,
                IslandShareValues(
                    label = result.label.ifBlank { "凭证" },
                    code = result.title,
                    item = result.item,
                    detail = result.itemDetail,
                    merchant = result.merchant,
                ),
            )

            val islandActivity = PendingIntent.getActivity(
                context,
                sessionId + 10000,
                Intent(context, QrResultActivity::class.java).apply {
                    putExtra(
                        QrResultActivity.EXTRA_MODE,
                        if (result.hasQrBitmap) {
                            QrResultActivity.MODE_IMAGE
                        } else {
                            QrResultActivity.MODE_OPEN_SOURCE
                        },
                    )
                    putExtra(QrResultActivity.EXTRA_SESSION_ID, sessionId)
                    putExtra(
                        QrResultActivity.EXTRA_SOURCE_PACKAGE,
                        SourceIconCache.packageNameFor(sessionId) ?: context.packageName,
                    )
                    putExtra(
                        QrResultActivity.EXTRA_SOURCE_TASK_ID,
                        SourceIconCache.taskIdFor(sessionId) ?: -1,
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val notification = buildIslandNotification(
                context = context,
                title = displayTitle,
                content = displayContent,
                ticker = "信岛 · $displayTitle",
                keyText = displayTitle,   // 取餐码/识别结果关键信息始终在 hintInfo.title 展示
                price = result.price,
                itemTitle = result.item,
                itemSubtitle = result.itemDetail,
                merchant = result.merchant,
                ongoing = true,
                updatable = true,
                islandTimeout = 300,
                actionTitle = result.actionText.ifBlank { "已完成" },
                actionPendingIntent = dismissPendingIntent,
                // 普通点击始终进入来源应用；有二维码时，向下拖动由系统将同一 Activity
                // 转成二维码小窗。点击能力不能依赖二维码是否存在。
                contentPendingIntent = islandActivity,
                deleteIntent = dismissPendingIntent,
                sourceIcon = SourceIconCache.iconFor(sessionId),
                dragShareTitle = share.title.ifBlank {
                    result.merchant.ifBlank {
                    result.item.ifBlank { result.label.ifBlank { "取餐信息" } }
                    }
                },
                dragShareDescription = share.description.ifBlank { displayTitle },
                dragShareContent = share.content.ifBlank { displayContent },
            )
            nm.notify(islandId, notification)
            AppLog.i(TAG, "Result island sent for session $sessionId (id=$islandId)")
        }
    }

    /**
     * 取消超级岛通知。
     */
    fun cancelIslandNotification(context: Context, notificationId: Int = ISLAND_NOTIFICATION_ID) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(notificationId)
    }

    /**
     * 取消指定 session 的超级岛通知。
     */
    fun cancelIslandForSession(context: Context, sessionId: Int) {
        cancelIslandNotification(context, islandIdForSession(sessionId))
    }


}

/** Xiaomi's narrow face supports numeric and Latin code text. */
internal fun supportsNarrowFont(text: String): Boolean {
    val compact = text.trim()
    return compact.isNotEmpty() && compact.all {
        it.isDigit() || it in 'A'..'Z' || it in 'a'..'z' || it in ".:-+/"
    }
}

/**
 * HyperOS can clip the right overhang of the last condensed glyph. A trailing thin space
 * expands the measured slot without producing a visibly large gap.
 */
internal fun islandCapsuleText(text: String, narrowFont: Boolean): String =
    text.trimEnd() + if (narrowFont) "\u2009" else ""

internal data class IslandProductTexts(
    val title: String,
    val detail: String,
    val merchant: String,
)

/** Selects template 16's product title and two smaller secondary texts. */
internal fun islandProductTexts(
    fallbackTitle: String,
    details: String,
    itemTitle: String,
    itemSubtitle: String,
    merchant: String,
): IslandProductTexts {
    val lines = details.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val primary = itemTitle.trim().ifEmpty { lines.firstOrNull().orEmpty() }.ifEmpty { fallbackTitle }
    val detail = itemSubtitle.trim()
    val merchantLine = merchant.trim().ifEmpty {
        lines.firstOrNull { it != primary && it != detail }.orEmpty()
    }.ifEmpty { "订单信息" }
    return IslandProductTexts(primary, detail, merchantLine)
}
