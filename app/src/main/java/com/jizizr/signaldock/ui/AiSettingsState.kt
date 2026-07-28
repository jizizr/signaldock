package com.jizizr.signaldock.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jizizr.signaldock.AiSettingsStore
import com.jizizr.signaldock.MiclawSession
import com.jizizr.signaldock.MiclawSessionStore

/**
 * AI 参数的 UI 状态持有者。
 *
 * [AiSettingsStore] is the only persistence layer. The editor keeps a local draft and saves
 * all fields together, so dismissing it never leaks partial changes.
 */
@Stable
class AiSettingsState {
    private val presets = AiSettingsStore.PRESETS

    /** 预设显示名列表（与 [presetIndex] 对应） */
    val presetNames: List<String> = presets.map { it.name }

    val selectedPresetName: String
        get() = presets.getOrNull(presetIndex)?.name.orEmpty()

    val usesMiclaw: Boolean
        get() = presets.getOrNull(presetIndex)?.id == AiSettingsStore.MICLAW_PRESET_ID

    var presetIndex by mutableIntStateOf(
        presets.indexOfFirst { it.id == AiSettingsStore.selectedPresetId }.coerceAtLeast(0)
    )
        private set

    var apiKey by mutableStateOf(AiSettingsStore.apiKey)
        private set
    var baseUrl by mutableStateOf(AiSettingsStore.baseUrl)
        private set
    var modelId by mutableStateOf(AiSettingsStore.modelId)
        private set
    var reasoningEffort by mutableStateOf(AiSettingsStore.reasoningEffort)
        private set

    var miclawThinkingEnabled by mutableStateOf(AiSettingsStore.miclawThinkingEnabled)
        private set
    var miclawUseExternalAgent by mutableStateOf(AiSettingsStore.miclawUseExternalAgent)
        private set
    var miclawSessionAvailable by mutableStateOf(MiclawSessionStore.load()?.isUsable == true)
        private set

    val isConfigured: Boolean
        get() = (usesMiclaw && (miclawUseExternalAgent || miclawSessionAvailable)) ||
                !usesMiclaw && apiKey.isNotBlank() &&
                (baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) &&
                modelId.isNotBlank()

    fun updateMiclawThinkingEnabled(enabled: Boolean) {
        miclawThinkingEnabled = enabled
        AiSettingsStore.miclawThinkingEnabled = enabled
    }

    fun updateMiclawUseExternalAgent(enabled: Boolean) {
        miclawUseExternalAgent = enabled
        AiSettingsStore.miclawUseExternalAgent = enabled
    }

    fun saveMiclawSession(
        serviceToken: String,
        passToken: String,
        userId: String,
        cUserId: String,
    ) {
        MiclawSessionStore.save(
            MiclawSession(
                serviceToken = serviceToken.trim(),
                passToken = passToken.trim(),
                userId = userId.trim(),
                cUserId = cUserId.trim(),
            ),
        )
        refreshMiclawSessionStatus()
    }

    fun clearMiclawSession() {
        MiclawSessionStore.clear()
        refreshMiclawSessionStatus()
    }

    fun refreshMiclawSessionStatus() {
        miclawSessionAvailable = MiclawSessionStore.load()?.isUsable == true
    }

    fun selectPreset(index: Int) {
        val preset = presets.getOrNull(index) ?: return
        AiSettingsStore.applyPreset(preset)
        presetIndex = index
        if (preset.id != AiSettingsStore.CUSTOM_PRESET_ID) {
            baseUrl = preset.baseUrl
            modelId = preset.modelId
            reasoningEffort = preset.reasoningEffort
        }
        // API Key 按预设独立记忆
        apiKey = AiSettingsStore.apiKeyFor(preset.id)
    }

    /**
     * Saves the dialog draft in one transaction from the UI's perspective.
     * Editing an endpoint/model converts a named preset into a custom configuration;
     * editing only the key keeps the selected provider and its independent key slot.
     */
    fun saveConfiguration(
        apiKey: String,
        baseUrl: String,
        modelId: String,
        reasoningEffort: String,
    ) {
        val connectionChanged = this.baseUrl != baseUrl ||
                this.modelId != modelId ||
                this.reasoningEffort != reasoningEffort

        val currentPresetId = presets[presetIndex].id
        val targetPresetId = if (connectionChanged) AiSettingsStore.CUSTOM_PRESET_ID
        else currentPresetId
        if (targetPresetId != currentPresetId) {
            presetIndex = presets.indexOfFirst { it.id == targetPresetId }
        }

        this.apiKey = apiKey
        this.baseUrl = baseUrl
        this.modelId = modelId
        this.reasoningEffort = reasoningEffort
        AiSettingsStore.saveConfiguration(
            presetId = targetPresetId,
            apiKey = apiKey,
            baseUrl = baseUrl,
            modelId = modelId,
            reasoningEffort = reasoningEffort,
        )
    }
}

@Composable
fun rememberAiSettingsState(): AiSettingsState = remember { AiSettingsState() }
