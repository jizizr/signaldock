package com.jizizr.signaldock

import android.app.Application
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Custom Application class.
 *
 * Bootstraps LSPosed HiddenApiBypass on Android 9+ (P+) so that methods
 * marked as `api=blocked` in custom ROMs (e.g. MIUI / HyperOS) — such as
 * Notification.Builder#setRequestPromotedOngoing — can be called directly
 * at runtime without throwing NoSuchMethodError.
 *
 * This mirrors the approach used by InstallerX-Revived:
 * https://github.com/wxxsfxyzm/InstallerX-Revived/blob/main/app/src/main/java/com/rosan/installer/App.kt
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLogStore.init(this)
        DiagnosticCrashHandler.init()
        AppLog.i("App", "Application process started")
        // minSdk=36 >= P(28), always apply bypass
        HiddenApiBypass.addHiddenApiExemptions("")
        // 初始化 AI 参数持久化存储
        AiSettingsStore.init(this)
        MiclawSessionStore.init(this)
        SuperIslandSettingsStore.init(this)
        if (
            AiSettingsStore.miclawUseExternalAgent &&
            !MiclawAgentClient.isCompatibilitySupported(this)
        ) {
            AiSettingsStore.miclawUseExternalAgent = false
        }
    }
}
