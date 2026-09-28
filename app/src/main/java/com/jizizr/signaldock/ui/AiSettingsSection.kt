package com.jizizr.signaldock.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.jizizr.signaldock.R
import com.jizizr.signaldock.XiaoAiMode
import com.jizizr.signaldock.XiaomiCredentialImporter
import com.jizizr.signaldock.XiaomiSessionStore
import com.jizizr.signaldock.XiaomiWebLoginActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference

@Composable
internal fun AiSettingsSection(
    settings: AiSettingsState,
    allowSystemAccountImport: Boolean,
    onOpenCustomModel: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connecting by remember { mutableStateOf(false) }
    fun importSystemAccount() {
        if (connecting) return
        connecting = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    XiaomiCredentialImporter.importFromXiaoAi(context.applicationContext)
                }
                settings.refreshXiaomiSession()
                onMessage(result.fold({ "小爱连接成功" }, { it.message ?: "连接失败" }))
            } finally { connecting = false }
        }
    }
    val presets = remember(settings.presetNames) { settings.presetNames.map { DropdownItem(text = it) } }
    LaunchedEffect(settings.presetIndex) { settings.refreshXiaomiSession() }
    LaunchedEffect(Unit) { XiaomiSessionStore.changes.collect { settings.refreshXiaomiSession() } }
    OverlaySpinnerPreference(
        items = presets,
        selectedIndex = settings.presetIndex,
        title = stringResource(R.string.provider_preset),
        summary = stringResource(R.string.provider_preset_summary),
        onSelectedIndexChange = settings::selectPreset,
    )
    if (settings.usesXiaomi) {
        if (!settings.usesPickup) {
            OverlaySpinnerPreference(
                items = listOf(DropdownItem(text = "快速模式"), DropdownItem(text = "专家模式")),
                selectedIndex = if (settings.xiaoAiMode == XiaoAiMode.EXPERT) 1 else 0,
                title = "识别模式",
                summary = if (settings.xiaoAiMode == XiaoAiMode.FAST) "网页登录后通过网络识别，无需 Root"
                    else "专家接口需要硬件设备签名，当前需 Root 授权",
                onSelectedIndexChange = settings::selectXiaoAiMode,
            )
        }
        BasicComponent(
            title = if (settings.usesPickup) "取餐码上岛" else "超级小爱连接",
            summary = when {
                settings.usesPickup && (!settings.xiaomiConnected || !settings.independentXiaomiSession) ->
                    "请通过小米官方网页登录连接取餐码识别，无需 Root"
                settings.usesPickup -> "已连接，通过小米取餐码接口识别截图，无需 Root"
                !settings.xiaomiConnected -> "请通过小米官方网页登录连接快速模式；专家模式仍需 Root 导入系统账号"
                settings.xiaoAiMode == XiaoAiMode.EXPERT && !allowSystemAccountImport ->
                    "账号已连接；专家模式仍需要 Root 授权"
                settings.xiaoAiMode == XiaoAiMode.EXPERT && !settings.expertConnected ->
                    "请先在超级小爱专家模式完成一次对话，再重新导入账号"
                else -> "已连接，可识别截图"
            },
        )
        ArrowPreference(
            title = "小米账号网页登录（免 Root）",
            summary = "连接取餐码和快速模式，识别验证通过后自动保存",
            onClick = { context.startActivity(Intent(context, XiaomiWebLoginActivity::class.java)) },
        )
        if (allowSystemAccountImport && !settings.usesPickup) {
            ArrowPreference(
                title = if (connecting) "正在导入…" else "从系统小爱导入（Root）",
                summary = "导入已登录的小爱凭据，供快速模式或专家模式使用",
                onClick = { importSystemAccount() },
            )
        }
    } else {
        val incompleteConfiguration = stringResource(R.string.connection_parameters_incomplete)
        ArrowPreference(
            title = stringResource(R.string.connection_parameters),
            summary = settings.modelId.ifBlank { incompleteConfiguration },
            onClick = onOpenCustomModel,
        )
    }
}
