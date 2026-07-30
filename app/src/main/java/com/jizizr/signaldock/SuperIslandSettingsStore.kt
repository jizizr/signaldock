package com.jizizr.signaldock

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Persistent controls for Xiaomi Super Island delivery behavior. */
object SuperIslandSettingsStore {
    private const val PREFS_NAME = "super_island_settings"
    private const val KEY_NETWORK_BYPASS_ENABLED = "network_bypass_enabled"
    private const val KEY_NETWORK_BYPASS_MODE = "network_bypass_mode"
    private const val KEY_NETWORK_BYPASS_DURATION_MS = "network_bypass_duration_ms"
    private const val KEY_SHARE_CONTENT_TEMPLATE = "share_content_template"
    private const val KEY_OUTER_GLOW_ENABLED = "outer_glow_enabled"

    val defaultShareTemplate = IslandShareTemplate(
        title = "{商家}",
        description = "{类型} {号码} · {商品}",
        content = "{类型}：{号码}\n商品：{商品规格}\n商家：{商家}",
    )

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var networkBypassEnabled: Boolean
        get() = networkBypassMode != NetworkBypassMode.DISABLED
        set(value) {
            prefs.edit {
                putBoolean(KEY_NETWORK_BYPASS_ENABLED, value)
                if (!value) putString(KEY_NETWORK_BYPASS_MODE, NetworkBypassMode.DISABLED.storageValue)
                else if (networkBypassMode == NetworkBypassMode.DISABLED) {
                    putString(KEY_NETWORK_BYPASS_MODE, NetworkBypassMode.STANDARD.storageValue)
                }
            }
        }

    var networkBypassMode: NetworkBypassMode
        get() {
            val stored = prefs.getString(KEY_NETWORK_BYPASS_MODE, null)
            if (stored != null) return NetworkBypassMode.fromStorageValue(stored)
            return if (prefs.getBoolean(KEY_NETWORK_BYPASS_ENABLED, true)) {
                NetworkBypassMode.STANDARD
            } else {
                NetworkBypassMode.DISABLED
            }
        }
        set(value) = prefs.edit {
            putString(KEY_NETWORK_BYPASS_MODE, value.storageValue)
            putBoolean(KEY_NETWORK_BYPASS_ENABLED, value != NetworkBypassMode.DISABLED)
        }

    var networkBypassDurationMs: Int
        get() = prefs.getInt(KEY_NETWORK_BYPASS_DURATION_MS, NetworkBypassMode.DEFAULT_DURATION_MS)
            .coerceIn(NetworkBypassMode.MIN_DURATION_MS, NetworkBypassMode.MAX_DURATION_MS)
        set(value) = prefs.edit {
            putInt(
                KEY_NETWORK_BYPASS_DURATION_MS,
                value.coerceIn(NetworkBypassMode.MIN_DURATION_MS, NetworkBypassMode.MAX_DURATION_MS),
            )
        }

    var outerGlowEnabled: Boolean
        get() = prefs.getBoolean(KEY_OUTER_GLOW_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_OUTER_GLOW_ENABLED, value) }

    val shareTemplate: IslandShareTemplate
        get() = defaultShareTemplate.copy(
            content = prefs.getString(KEY_SHARE_CONTENT_TEMPLATE, defaultShareTemplate.content)
                ?: defaultShareTemplate.content,
        )

    fun saveShareContentTemplate(content: String) {
        prefs.edit {
            putString(KEY_SHARE_CONTENT_TEMPLATE, content)
        }
    }
}

enum class NetworkBypassMode(val storageValue: String) {
    DISABLED("disabled"),
    STANDARD("standard"),
    CUSTOM("custom");

    companion object {
        const val DEFAULT_DURATION_MS = 100
        const val MIN_DURATION_MS = 100
        const val MAX_DURATION_MS = 500

        fun fromStorageValue(value: String?): NetworkBypassMode =
            entries.firstOrNull { it.storageValue == value } ?: STANDARD
    }
}

data class IslandShareTemplate(
    val title: String,
    val description: String,
    val content: String,
)

data class IslandShareValues(
    val label: String,
    val code: String,
    val item: String,
    val detail: String,
    val merchant: String,
)

data class RenderedIslandShare(
    val title: String,
    val description: String,
    val content: String,
)

fun renderIslandShare(
    template: IslandShareTemplate,
    values: IslandShareValues,
): RenderedIslandShare {
    val replacements = mapOf(
        "{类型}" to values.label,
        "{号码}" to values.code,
        "{商品}" to values.item,
        "{规格}" to values.detail,
        "{商品规格}" to listOf(values.item, values.detail)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(" · "),
        "{商家}" to values.merchant,
    )

    fun render(source: String): String = source
        .lines()
        .map { rawLine ->
            val replaced = replacements.entries.fold(rawLine) { line, (token, value) ->
                line.replace(token, value)
            }
            replaced
                .split('·')
                .map { it.trim().replace(Regex("[ \\t]+"), " ") }
                .filter(String::isNotEmpty)
                .joinToString(" · ")
                .takeUnless { it.endsWith('：') || it.endsWith(':') }
                .orEmpty()
        }
        .filter(String::isNotEmpty)
        .joinToString("\n")

    return RenderedIslandShare(
        title = render(template.title).lineSequence().firstOrNull().orEmpty(),
        description = render(template.description).replace('\n', ' '),
        content = render(template.content),
    )
}
