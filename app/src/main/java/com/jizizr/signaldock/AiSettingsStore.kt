package com.jizizr.signaldock

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** 单个 AI 服务商预设配置。 */
data class AiPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val modelId: String,
    /** 空字符串表示不传递此参数 */
    val reasoningEffort: String,
    val transport: AiTransport = AiTransport.OPENAI_COMPATIBLE,
)

data class AiConnectionConfiguration(
    val baseUrl: String = "",
    val modelId: String = "",
    val reasoningEffort: String = "",
)

data class AiRuntimeSettings(
    val usesMiclaw: Boolean,
    val apiKey: String,
    val connection: AiConnectionConfiguration,
    val miclawThinkingEnabled: Boolean,
    val miclawUseExternalAgent: Boolean,
)

enum class AiTransport {
    OPENAI_COMPATIBLE,
    MICLAW,
}

/**
 * 持久化存储 AI 调用参数（SharedPreferences）。
 * 在 [App.onCreate] 中调用 [init] 完成初始化，之后可在任意位置读写。
 */
object AiSettingsStore {

    /** 「自定义」预设的固定 ID */
    const val CUSTOM_PRESET_ID = "custom"
    const val MICLAW_PRESET_ID = "miclaw"

    // 预设列表（静态定义）
    val PRESETS = listOf(
        AiPreset(
            id = MICLAW_PRESET_ID,
            name = "Miclaw",
            baseUrl = "",
            modelId = "Miclaw 当前模型",
            reasoningEffort = "",
            transport = AiTransport.MICLAW,
        ),
        AiPreset(
            id = CUSTOM_PRESET_ID,
            name = "自定义",
            baseUrl = "",
            modelId = "",
            reasoningEffort = "",
        ),
    )

    private const val PREFS_NAME = "ai_settings"
    private const val KEY_SELECTED_PRESET = "selected_preset_id"
    private const val KEY_LEGACY_BASE_URL = "base_url"
    private const val KEY_LEGACY_MODEL_ID = "model_id"
    private const val KEY_LEGACY_REASONING_EFFORT = "reasoning_effort"
    private const val KEY_CUSTOM_BASE_URL = "custom_base_url"
    private const val KEY_CUSTOM_MODEL_ID = "custom_model_id"
    private const val KEY_CUSTOM_REASONING_EFFORT = "custom_reasoning_effort"
    private const val KEY_CUSTOM_CONFIGURATION_MIGRATED = "custom_configuration_migrated_v1"
    private const val KEY_MICLAW_THINKING = "miclaw_enable_thinking"
    private const val KEY_MICLAW_EXTERNAL_AGENT = "miclaw_use_external_agent"
    private const val SECRETS_PREFS_NAME = "ai_secrets"
    private const val SECRETS_KEY_ALIAS = "signaldock_ai_secrets_v1"

    private fun apiKeyPrefKey(presetId: String) = "api_key_$presetId"

    private val defaultPreset get() = PRESETS.first()

    private lateinit var prefs: SharedPreferences
    private lateinit var secrets: EncryptedValueStore

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        secrets = EncryptedValueStore(context, SECRETS_PREFS_NAME, SECRETS_KEY_ALIAS)
        migratePlaintextApiKeys()
        migrateRemovedPreset()
        migrateLegacyCustomConfiguration()
    }

    private fun migratePlaintextApiKeys() {
        prefs.all.keys
            .filter { it.startsWith("api_key_") }
            .forEach { key ->
                val value = prefs.getString(key, "").orEmpty()
                if (value.isNotBlank() && secrets.get(key).isNullOrBlank()) {
                    secrets.put(key, value)
                }
                prefs.edit { remove(key) }
            }
    }

    /** Removed named providers become Custom without losing their endpoint, model, or key. */
    private fun migrateRemovedPreset() {
        val selectedId = prefs.getString(KEY_SELECTED_PRESET, defaultPreset.id) ?: defaultPreset.id
        if (PRESETS.any { it.id == selectedId }) return
        val oldApiKey = apiKeyFor(selectedId)
        val customApiKey = apiKeyFor(CUSTOM_PRESET_ID)
        prefs.edit {
            putString(KEY_SELECTED_PRESET, CUSTOM_PRESET_ID)
        }
        if (customApiKey.isBlank() && oldApiKey.isNotBlank()) {
            setApiKeyFor(CUSTOM_PRESET_ID, oldApiKey)
        }
    }

    /** Moves the pre-1.0.3 shared fields only when they actually represent Custom. */
    private fun migrateLegacyCustomConfiguration() {
        if (prefs.getBoolean(KEY_CUSTOM_CONFIGURATION_MIGRATED, false)) return

        val selectedId = prefs.getString(KEY_SELECTED_PRESET, defaultPreset.id) ?: defaultPreset.id
        val legacyConfiguration = legacyCustomConfigurationOrNull(
            selectedPresetId = selectedId,
            baseUrl = prefs.getString(KEY_LEGACY_BASE_URL, "").orEmpty(),
            modelId = prefs.getString(KEY_LEGACY_MODEL_ID, "").orEmpty(),
            reasoningEffort = prefs.getString(KEY_LEGACY_REASONING_EFFORT, "").orEmpty(),
        )
        prefs.edit {
            if (legacyConfiguration != null) {
                putString(KEY_CUSTOM_BASE_URL, legacyConfiguration.baseUrl)
                putString(KEY_CUSTOM_MODEL_ID, legacyConfiguration.modelId)
                putString(KEY_CUSTOM_REASONING_EFFORT, legacyConfiguration.reasoningEffort)
            }
            putBoolean(KEY_CUSTOM_CONFIGURATION_MIGRATED, true)
            remove(KEY_LEGACY_BASE_URL)
            remove(KEY_LEGACY_MODEL_ID)
            remove(KEY_LEGACY_REASONING_EFFORT)
        }
    }

    /** 当前选中的预设 ID */
    val selectedPresetId: String
        get() = prefs.getString(KEY_SELECTED_PRESET, defaultPreset.id) ?: defaultPreset.id

    val selectedPreset: AiPreset
        get() = PRESETS.firstOrNull { it.id == selectedPresetId } ?: defaultPreset

    val usesMiclaw: Boolean
        get() = selectedPreset.transport == AiTransport.MICLAW

    // ---------- 每个预设独立记忆 API Key ----------

    fun apiKeyFor(presetId: String): String =
        secrets.get(apiKeyPrefKey(presetId)).orEmpty()

    private fun setApiKeyFor(presetId: String, key: String) {
        val prefKey = apiKeyPrefKey(presetId)
        if (key.isBlank()) secrets.remove(prefKey) else secrets.put(prefKey, key)
    }

    private val customConfiguration: AiConnectionConfiguration
        get() = AiConnectionConfiguration(
            baseUrl = prefs.getString(KEY_CUSTOM_BASE_URL, "").orEmpty(),
            modelId = prefs.getString(KEY_CUSTOM_MODEL_ID, "").orEmpty(),
            reasoningEffort = prefs.getString(KEY_CUSTOM_REASONING_EFFORT, "").orEmpty(),
        )

    val activeConfiguration: AiConnectionConfiguration
        get() = resolveAiConnectionConfiguration(selectedPreset, customConfiguration)

    fun runtimeSnapshot(): AiRuntimeSettings {
        val preset = selectedPreset
        return AiRuntimeSettings(
            usesMiclaw = preset.transport == AiTransport.MICLAW,
            apiKey = apiKeyFor(preset.id),
            connection = resolveAiConnectionConfiguration(preset, customConfiguration),
            miclawThinkingEnabled = miclawThinkingEnabled,
            miclawUseExternalAgent = miclawUseExternalAgent,
        )
    }

    /** Direct API thinking control. Disabled by default for lower latency and stricter JSON. */
    var miclawThinkingEnabled: Boolean
        get() = prefs.getBoolean(KEY_MICLAW_THINKING, false)
        set(value) { prefs.edit { putBoolean(KEY_MICLAW_THINKING, value) } }

    /** Explicit compatibility mode. This invokes Miclaw and may create its own island notification. */
    var miclawUseExternalAgent: Boolean
        get() = prefs.getBoolean(KEY_MICLAW_EXTERNAL_AGENT, false)
        set(value) { prefs.edit { putBoolean(KEY_MICLAW_EXTERNAL_AGENT, value) } }

    /** Switches only the active provider; every provider keeps its own configuration. */
    fun applyPreset(preset: AiPreset) {
        prefs.edit { putString(KEY_SELECTED_PRESET, preset.id) }
    }

    /** Persists the complete Custom editor draft without changing the active provider. */
    fun saveCustomConfiguration(
        apiKey: String,
        baseUrl: String,
        modelId: String,
        reasoningEffort: String,
    ) {
        setApiKeyFor(CUSTOM_PRESET_ID, apiKey)
        prefs.edit {
            putString(KEY_CUSTOM_BASE_URL, baseUrl)
            putString(KEY_CUSTOM_MODEL_ID, modelId)
            putString(KEY_CUSTOM_REASONING_EFFORT, reasoningEffort)
        }
    }
}

internal fun resolveAiConnectionConfiguration(
    preset: AiPreset,
    customConfiguration: AiConnectionConfiguration,
): AiConnectionConfiguration = if (preset.id == AiSettingsStore.CUSTOM_PRESET_ID) {
    customConfiguration
} else {
    AiConnectionConfiguration(
        baseUrl = preset.baseUrl,
        modelId = preset.modelId,
        reasoningEffort = preset.reasoningEffort,
    )
}

internal fun isAiConfigurationReady(
    usesMiclaw: Boolean,
    miclawUseExternalAgent: Boolean,
    miclawSessionAvailable: Boolean,
    apiKey: String,
    connection: AiConnectionConfiguration,
): Boolean = if (usesMiclaw) {
    miclawUseExternalAgent || miclawSessionAvailable
} else {
    apiKey.isNotBlank() &&
        (connection.baseUrl.startsWith("https://") ||
            connection.baseUrl.startsWith("http://")) &&
        connection.modelId.isNotBlank()
}

internal fun legacyCustomConfigurationOrNull(
    selectedPresetId: String,
    baseUrl: String,
    modelId: String,
    reasoningEffort: String,
): AiConnectionConfiguration? {
    if (selectedPresetId != AiSettingsStore.CUSTOM_PRESET_ID) return null
    val miclaw = AiSettingsStore.PRESETS.first { it.id == AiSettingsStore.MICLAW_PRESET_ID }
    if (baseUrl == miclaw.baseUrl &&
        modelId == miclaw.modelId &&
        reasoningEffort == miclaw.reasoningEffort
    ) {
        return null
    }
    return AiConnectionConfiguration(baseUrl, modelId, reasoningEffort)
}
