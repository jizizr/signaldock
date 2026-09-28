package com.jizizr.signaldock.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jizizr.signaldock.AiConnectionConfiguration
import com.jizizr.signaldock.AiSettingsStore
import com.jizizr.signaldock.AiTransport
import com.jizizr.signaldock.XiaoAiMode
import com.jizizr.signaldock.XiaomiSessionStore
import com.jizizr.signaldock.isAiConfigurationReady
import com.jizizr.signaldock.isXiaomiConfigurationReady

/**
 * AI 参数的 UI 状态持有者。
 *
 * [AiSettingsStore] is the only persistence layer. The editor keeps a local draft and saves
 * all fields together, so dismissing it never leaks partial changes.
 */
@Stable
class AiSettingsState {
    private val presets = AiSettingsStore.PRESETS
    private val initialConfiguration = AiSettingsStore.activeConfiguration

    /** 预设显示名列表（与 [presetIndex] 对应） */
    val presetNames: List<String> = presets.map { it.name }

    val selectedPresetName: String
        get() = presets.getOrNull(presetIndex)?.name.orEmpty()

    val usesXiaomi: Boolean
        get() = presets.getOrNull(presetIndex)?.transport != AiTransport.OPENAI_COMPATIBLE
    val usesPickup: Boolean
        get() = presets.getOrNull(presetIndex)?.transport == AiTransport.XIAOMI_PICKUP

    var presetIndex by mutableIntStateOf(
        presets.indexOfFirst { it.id == AiSettingsStore.selectedPresetId }.coerceAtLeast(0)
    )
        private set

    var apiKey by mutableStateOf(AiSettingsStore.apiKeyFor(AiSettingsStore.selectedPresetId))
        private set
    var baseUrl by mutableStateOf(initialConfiguration.baseUrl)
        private set
    var modelId by mutableStateOf(initialConfiguration.modelId)
        private set
    var reasoningEffort by mutableStateOf(initialConfiguration.reasoningEffort)
        private set

    var xiaoAiMode by mutableStateOf(AiSettingsStore.xiaoAiMode)
        private set
    var xiaomiConnected by mutableStateOf(XiaomiSessionStore.load()?.isUsable == true)
        private set
    var expertConnected by mutableStateOf(XiaomiSessionStore.load()?.expertToken?.isNotBlank() == true)
        private set
    var independentXiaomiSession by mutableStateOf(XiaomiSessionStore.load()?.independentDevice == true)
        private set

    val isConfigured: Boolean
        get() = if (usesXiaomi) {
            isXiaomiConfigurationReady(usesPickup, xiaoAiMode, xiaomiConnected,
                independentXiaomiSession, expertConnected)
        } else {
            isAiConfigurationReady(false, false, false, apiKey, AiConnectionConfiguration(baseUrl, modelId, reasoningEffort))
        }

    fun selectXiaoAiMode(index: Int) {
        xiaoAiMode = if (index == 1) XiaoAiMode.EXPERT else XiaoAiMode.FAST
        AiSettingsStore.xiaoAiMode = xiaoAiMode
    }

    fun refreshXiaomiSession() {
        val session = XiaomiSessionStore.load()
        xiaomiConnected = session?.isUsable == true
        expertConnected = session?.expertToken?.isNotBlank() == true
        independentXiaomiSession = session?.independentDevice == true
    }

    fun selectPreset(index: Int) {
        val preset = presets.getOrNull(index) ?: return
        AiSettingsStore.applyPreset(preset)
        presetIndex = index
        val configuration = AiSettingsStore.activeConfiguration
        baseUrl = configuration.baseUrl
        modelId = configuration.modelId
        reasoningEffort = configuration.reasoningEffort
        apiKey = AiSettingsStore.apiKeyFor(preset.id)
    }

    fun saveCustomConfiguration(
        apiKey: String,
        baseUrl: String,
        modelId: String,
        reasoningEffort: String,
    ) {
        this.apiKey = apiKey
        this.baseUrl = baseUrl
        this.modelId = modelId
        this.reasoningEffort = reasoningEffort
        AiSettingsStore.saveCustomConfiguration(
            apiKey = apiKey,
            baseUrl = baseUrl,
            modelId = modelId,
            reasoningEffort = reasoningEffort,
        )
    }
}

@Composable
fun rememberAiSettingsState(): AiSettingsState = remember { AiSettingsState() }
