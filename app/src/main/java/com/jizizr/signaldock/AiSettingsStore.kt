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
            id              = MICLAW_PRESET_ID,
            name            = "Miclaw",
            baseUrl         = "",
            modelId         = "Miclaw 当前模型",
            reasoningEffort = "",
            transport       = AiTransport.MICLAW,
        ),
        AiPreset(
            id              = CUSTOM_PRESET_ID,
            name            = "自定义",
            baseUrl         = "",
            modelId         = "",
            reasoningEffort = ""        )
    )

    private const val PREFS_NAME           = "ai_settings"
    private const val KEY_SELECTED_PRESET  = "selected_preset_id"
    private const val KEY_BASE_URL         = "base_url"
    private const val KEY_MODEL_ID         = "model_id"
    private const val KEY_REASONING_EFFORT = "reasoning_effort"
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

    /** 当前选中的预设 ID */
    var selectedPresetId: String
        get() = prefs.getString(KEY_SELECTED_PRESET, defaultPreset.id) ?: defaultPreset.id
        set(value) { prefs.edit { putString(KEY_SELECTED_PRESET, value) } }

    val selectedPreset: AiPreset
        get() = PRESETS.firstOrNull { it.id == selectedPresetId } ?: defaultPreset

    val usesMiclaw: Boolean
        get() = selectedPreset.transport == AiTransport.MICLAW

    // ---------- 每个预设独立记忆 API Key ----------

    fun apiKeyFor(presetId: String): String =
        secrets.get(apiKeyPrefKey(presetId)).orEmpty()

    fun setApiKeyFor(presetId: String, key: String) {
        val prefKey = apiKeyPrefKey(presetId)
        if (key.isBlank()) secrets.remove(prefKey) else secrets.put(prefKey, key)
    }

    /** 当前活跃预设的 API Key */
    var apiKey: String
        get() = apiKeyFor(selectedPresetId)
        set(value) { setApiKeyFor(selectedPresetId, value) }

    // ---------- baseUrl / modelId / reasoningEffort 支持手动覆盖 ----------

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, selectedPreset.baseUrl) ?: selectedPreset.baseUrl
        set(value) { prefs.edit { putString(KEY_BASE_URL, value) } }

    var modelId: String
        get() = prefs.getString(KEY_MODEL_ID, selectedPreset.modelId) ?: selectedPreset.modelId
        set(value) { prefs.edit { putString(KEY_MODEL_ID, value) } }

    /** 空字符串表示不向 Rust 传递 reasoning_effort 参数。 */
    var reasoningEffort: String
        get() = prefs.getString(KEY_REASONING_EFFORT, selectedPreset.reasoningEffort)
                    ?: selectedPreset.reasoningEffort
        set(value) { prefs.edit { putString(KEY_REASONING_EFFORT, value) } }

    /** Direct API thinking control. Disabled by default for lower latency and stricter JSON. */
    var miclawThinkingEnabled: Boolean
        get() = prefs.getBoolean(KEY_MICLAW_THINKING, false)
        set(value) { prefs.edit { putBoolean(KEY_MICLAW_THINKING, value) } }

    /** Explicit compatibility mode. This invokes Miclaw and may create its own island notification. */
    var miclawUseExternalAgent: Boolean
        get() = prefs.getBoolean(KEY_MICLAW_EXTERNAL_AGENT, false)
        set(value) { prefs.edit { putBoolean(KEY_MICLAW_EXTERNAL_AGENT, value) } }

    /**
     * 应用指定预设：更新 selectedPresetId，并将 baseUrl/modelId/reasoningEffort 重置为预设默认值。
     * 自定义预设（id==[CUSTOM_PRESET_ID]）仅更新 selectedPresetId，不修改其他字段。
     * 不修改 API Key（每个预设独立记忆）。
     */
    fun applyPreset(preset: AiPreset) {
        prefs.edit {
            putString(KEY_SELECTED_PRESET, preset.id)
            if (preset.id != CUSTOM_PRESET_ID) {
                putString(KEY_BASE_URL, preset.baseUrl)
                putString(KEY_MODEL_ID, preset.modelId)
                putString(KEY_REASONING_EFFORT, preset.reasoningEffort)
            }
        }
    }

    /** Persists one complete editor draft with a single preferences transaction. */
    fun saveConfiguration(
        presetId: String,
        apiKey: String,
        baseUrl: String,
        modelId: String,
        reasoningEffort: String,
    ) {
        setApiKeyFor(presetId, apiKey)
        prefs.edit {
            putString(KEY_SELECTED_PRESET, presetId)
            putString(KEY_BASE_URL, baseUrl)
            putString(KEY_MODEL_ID, modelId)
            putString(KEY_REASONING_EFFORT, reasoningEffort)
        }
    }
}
