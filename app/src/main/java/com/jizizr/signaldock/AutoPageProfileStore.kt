package com.jizizr.signaldock

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.max

data class PageObservationSnapshot(
    val packageName: String,
    val activityClassName: String,
    val isWechatMiniProgram: Boolean = false,
    val miniProgramLabel: String,
    val miniProgramIconHash: String,
    val stableKeywords: List<String>,
)

data class AutoPageProfile(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val packageName: String,
    val activityClassName: String,
    val miniProgramLabel: String,
    val miniProgramIconHash: String,
    val keywords: List<String>,
    val excludedKeywords: List<String>,
    val nodeSignature: List<String>,
    val createdAtMs: Long,
)

data class AutoPageMatch(
    val profile: AutoPageProfile,
    val score: Double,
    val textMatched: Boolean,
)

data class AutoTriggerSnapshot(
    val activeProfileId: String?,
    val attempts: Int,
    val lastContentSignature: String,
    val fired: Boolean,
)

object AutoPageProfileStore {
    private const val PREFS_NAME = "auto_page_profiles"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROFILES = "profiles"
    private lateinit var preferences: SharedPreferences
    private lateinit var applicationContext: Context
    @Volatile
    private var enabledCache = false
    @Volatile
    private var profilesCache: List<AutoPageProfile> = emptyList()

    fun init(context: Context) {
        applicationContext = context.applicationContext
        preferences = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        enabledCache = preferences.getBoolean(KEY_ENABLED, false)
        profilesCache = readProfiles()
    }

    var enabled: Boolean
        get() = enabledCache
        set(value) {
            if (enabledCache == value) return
            enabledCache = value
            preferences.edit { putBoolean(KEY_ENABLED, value) }
            AccessibilityScreenshotService.instance?.refreshAutoConfiguration()
        }

    @Synchronized
    fun loadAll(): List<AutoPageProfile> = profilesCache

    private fun readProfiles(): List<AutoPageProfile> = runCatching {
        val array = JSONArray(preferences.getString(KEY_PROFILES, "[]") ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.toAutoPageProfile()
        }.sortedByDescending(AutoPageProfile::createdAtMs)
    }.onFailure { error ->
        AppLog.e("AutoPageProfileStore", "Unable to load profiles", error)
    }.getOrDefault(emptyList())

    fun enabledProfiles(): List<AutoPageProfile> =
        if (!enabled) emptyList() else loadAll().filter(AutoPageProfile::enabled)

    fun hasEnabledProfiles(): Boolean = enabledProfiles().isNotEmpty()

    @Synchronized
    fun save(profile: AutoPageProfile) {
        val profiles = loadAll().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) profiles[index] = profile else profiles.add(profile)
        persist(profiles)
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val profiles = loadAll()
        val remaining = profiles.filterNot { it.id == id }
        if (remaining.size == profiles.size) return false
        persist(remaining)
        return true
    }

    fun createFromHistory(
        context: Context,
        record: RecognitionHistoryRecord,
        name: String,
        keywords: List<String>,
        excludedKeywords: List<String> = emptyList(),
    ): AutoPageProfile {
        val packageName = record.sourcePackage
        val appName = runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
        val requiredKeywords = sanitizeKeywords(keywords).ifEmpty { suggestedKeywords(record) }
        return AutoPageProfile(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty {
                record.merchant.ifBlank { record.miniProgramLabel.ifBlank { appName } }
            },
            enabled = true,
            packageName = packageName,
            activityClassName = record.sourceActivityClass,
            miniProgramLabel = record.miniProgramLabel,
            miniProgramIconHash = record.miniProgramIconHash,
            keywords = requiredKeywords,
            excludedKeywords = sanitizeKeywords(excludedKeywords)
                .filterNot(requiredKeywords::contains),
            nodeSignature = sanitizeKeywords(record.pageStableKeywords),
            createdAtMs = System.currentTimeMillis(),
        )
    }

    fun suggestedKeywords(record: RecognitionHistoryRecord): List<String> {
        val semantic = PICKUP_CONTEXT_KEYWORDS
        val fromPage = record.pageStableKeywords.filter { candidate ->
            semantic.any(candidate::contains)
        }
        return sanitizeKeywords(
            fromPage + semantic.filter { keyword ->
                record.body.contains(keyword) || record.infoLines.any { it.contains(keyword) }
            },
        ).take(8).ifEmpty { listOf("取餐") }
    }

    fun sanitizeKeywords(values: List<String>): List<String> = values
        .asSequence()
        .flatMap { it.split(KEYWORD_SEPARATOR_REGEX).asSequence() }
        .map(::normalizePageText)
        .filter { it.length in 2..32 }
        .filterNot(::isDynamicPageText)
        .distinct()
        .take(24)
        .toList()

    private fun persist(profiles: List<AutoPageProfile>) {
        profilesCache = profiles.sortedByDescending(AutoPageProfile::createdAtMs)
        preferences.edit {
            putString(KEY_PROFILES, JSONArray().apply {
                profilesCache.forEach { put(it.toJson()) }
            }.toString())
        }
        AccessibilityScreenshotService.instance?.refreshAutoConfiguration()
    }

    private fun AutoPageProfile.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("packageName", packageName)
        put("activityClassName", activityClassName)
        put("miniProgramLabel", miniProgramLabel)
        put("miniProgramIconHash", miniProgramIconHash)
        put("keywords", JSONArray(keywords))
        put("excludedKeywords", JSONArray(excludedKeywords))
        put("nodeSignature", JSONArray(nodeSignature))
        put("createdAtMs", createdAtMs)
    }

    private fun JSONObject.toAutoPageProfile(): AutoPageProfile? {
        val id = optString("id")
        val packageName = optString("packageName")
        if (id.isBlank() || packageName.isBlank()) return null
        return AutoPageProfile(
            id = id,
            name = optString("name").ifBlank { packageName },
            enabled = optBoolean("enabled", true),
            packageName = packageName,
            activityClassName = optString("activityClassName"),
            miniProgramLabel = optString("miniProgramLabel"),
            miniProgramIconHash = optString("miniProgramIconHash"),
            keywords = optJSONArray("keywords").toStringList(),
            excludedKeywords = optJSONArray("excludedKeywords").toStringList(),
            nodeSignature = optJSONArray("nodeSignature").toStringList(),
            createdAtMs = optLong("createdAtMs"),
        )
    }
}

object AutoPageMatcher {
    fun canMatchIdentity(
        profile: AutoPageProfile,
        observedPackageName: String,
        observedActivityClassName: String,
    ): Boolean {
        if (profile.packageName != observedPackageName) return false
        if (observedActivityClassName.isBlank()) return true
        return if (profile.packageName == SourceIconResolver.WECHAT_PACKAGE) {
            isWechatMiniProgramActivity(observedActivityClassName)
        } else {
            !profile.activityClassName.startsWith(profile.packageName) ||
                !observedActivityClassName.startsWith(profile.packageName) ||
                profile.activityClassName == observedActivityClassName
        }
    }

    fun isDefiniteIdentityExit(
        profile: AutoPageProfile,
        observedPackageName: String,
        observedActivityClassName: String,
    ): Boolean {
        // Moving the app to the background suspends the page session; it does not leave it.
        if (profile.packageName != observedPackageName) return false
        if (observedActivityClassName.isBlank()) return false
        return if (profile.packageName == SourceIconResolver.WECHAT_PACKAGE) {
            !isWechatMiniProgramActivity(observedActivityClassName)
        } else {
            profile.activityClassName.startsWith(profile.packageName) &&
                profile.activityClassName != observedActivityClassName
        }
    }

    fun rank(
        observation: PageObservationSnapshot,
        profiles: List<AutoPageProfile>,
    ): List<AutoPageMatch> {
        val searchable = observation.stableKeywords.joinToString(" ") { normalizePageText(it) }
        val normalizedMiniProgramLabel = normalizePageText(observation.miniProgramLabel)
        return profiles.mapNotNull { profile ->
            if (profile.packageName != observation.packageName) return@mapNotNull null
            if (profile.packageName == SourceIconResolver.WECHAT_PACKAGE) {
                if (!observation.isWechatMiniProgram ||
                    !isWechatMiniProgramActivity(observation.activityClassName)
                ) return@mapNotNull null
                val hasLearnedMiniProgramIdentity = profile.miniProgramIconHash.isNotBlank() ||
                    profile.miniProgramLabel.isNotBlank()
                if (hasLearnedMiniProgramIdentity) {
                    val iconMatches = profile.miniProgramIconHash.isNotBlank() &&
                        observation.miniProgramIconHash.isNotBlank() &&
                        profile.miniProgramIconHash == observation.miniProgramIconHash
                    val labelMatches = profile.miniProgramLabel.isNotBlank() &&
                        observation.miniProgramLabel.isNotBlank() &&
                        normalizePageText(profile.miniProgramLabel) == normalizedMiniProgramLabel
                    if (!iconMatches && !labelMatches) return@mapNotNull null
                }
            } else if (
                profile.activityClassName.startsWith(profile.packageName) &&
                observation.activityClassName.isNotBlank() &&
                profile.activityClassName != observation.activityClassName
            ) {
                return@mapNotNull null
            }

            val normalizedKeywords = profile.keywords.map(::normalizePageText)
            val keywordHits = normalizedKeywords.count(searchable::contains)
            val excludedKeywordHit = profile.excludedKeywords
                .map(::normalizePageText)
                .any(searchable::contains)
            val signatureHits = profile.nodeSignature.count {
                searchable.contains(normalizePageText(it))
            }
            val textMatched = normalizedKeywords.isNotEmpty() &&
                keywordHits == normalizedKeywords.size &&
                !excludedKeywordHit
            val identityScore = when {
                profile.miniProgramIconHash.isNotBlank() &&
                    profile.miniProgramIconHash == observation.miniProgramIconHash -> 0.35
                profile.miniProgramLabel.isNotBlank() &&
                    normalizePageText(profile.miniProgramLabel) == normalizedMiniProgramLabel -> 0.30
                else -> 0.15
            }
            val score = identityScore +
                (keywordHits.toDouble() / max(1, profile.keywords.size))
                    .coerceAtMost(1.0) * 0.50 +
                (signatureHits.toDouble() / max(1, profile.nodeSignature.size))
                    .coerceAtMost(1.0) * 0.15
            AutoPageMatch(profile, score, textMatched)
        }.sortedByDescending(AutoPageMatch::score)
    }
}

private val PICKUP_CONTEXT_KEYWORDS = listOf(
    "取餐码",
    "取餐号",
    "取餐",
    "取货码",
    "取货号",
    "提货码",
    "提货号",
    "自提码",
    "核销码",
    "到店取餐",
)

class AutoTriggerCoordinator {
    private var activeProfileId: String? = null
    private var attempts = 0
    private var lastContentSignature = ""
    private var fired = false

    @Synchronized
    fun canEvaluate(profileId: String): Boolean =
        activeProfileId == null || activeProfileId == profileId

    @Synchronized
    fun activeProfileId(): String? = activeProfileId

    @Synchronized
    fun isFired(): Boolean = fired

    @Synchronized
    fun canAttempt(profileId: String, contentSignature: String): Boolean {
        if (fired || attempts >= 2) return false
        if (activeProfileId != null && activeProfileId != profileId) return false
        if (attempts == 1 && contentSignature == lastContentSignature) return false
        return true
    }

    @Synchronized
    fun onAttempt(profileId: String, contentSignature: String) {
        activeProfileId = profileId
        attempts++
        lastContentSignature = contentSignature
    }

    @Synchronized
    fun onSuccess(profileId: String) {
        activeProfileId = profileId
        fired = true
    }

    @Synchronized
    fun suppressUntilExit(profileId: String) {
        activeProfileId = profileId
        attempts = 2
        fired = false
    }

    @Synchronized
    fun reset() {
        activeProfileId = null
        attempts = 0
        lastContentSignature = ""
        fired = false
    }

    @Synchronized
    fun stateForTest(): Triple<String?, Int, Boolean> = Triple(activeProfileId, attempts, fired)

    @Synchronized
    fun diagnosticSnapshot(): AutoTriggerSnapshot = AutoTriggerSnapshot(
        activeProfileId = activeProfileId,
        attempts = attempts,
        lastContentSignature = lastContentSignature,
        fired = fired,
    )
}

object IconFingerprint {
    private const val HASH_WIDTH = 9
    private const val HASH_HEIGHT = 8

    fun hash(bitmap: Bitmap): String = java.lang.Long.toUnsignedString(dHash(bitmap), 16)

    private fun dHash(bitmap: Bitmap): Long {
        var hash = 0L
        var bit = 0
        repeat(HASH_HEIGHT) { y ->
            repeat(HASH_WIDTH - 1) { x ->
                val first = luminance(sample(bitmap, x, y))
                val second = luminance(sample(bitmap, x + 1, y))
                if (first > second) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    private fun sample(bitmap: Bitmap, x: Int, y: Int): Int {
        val px = (((x + 0.5f) * bitmap.width / HASH_WIDTH).toInt())
            .coerceIn(0, bitmap.width - 1)
        val py = (((y + 0.5f) * bitmap.height / HASH_HEIGHT).toInt())
            .coerceIn(0, bitmap.height - 1)
        return bitmap.getPixel(px, py)
    }

    private fun luminance(color: Int): Int {
        val red = color shr 16 and 0xff
        val green = color shr 8 and 0xff
        val blue = color and 0xff
        return (red * 30 + green * 59 + blue * 11) / 100
    }
}

internal data class PageNodeSnapshot(
    val stableKeywords: List<String>,
    val miniProgramIconBounds: Rect? = null,
)

internal fun collectPageNodeSnapshot(
    root: AccessibilityNodeInfo?,
    includeMiniProgramIcon: Boolean = false,
): PageNodeSnapshot {
    if (root == null) return PageNodeSnapshot(emptyList())
    val rootBounds = Rect().also(root::getBoundsInScreen)
        .takeIf { it.width() > 0 && it.height() > 0 }
    val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
    val keywords = linkedSetOf<String>()
    var bestIcon: Pair<Int, Rect>? = null
    var visited = 0
    while (queue.isNotEmpty() && visited < 2000) {
        val node = queue.removeFirst()
        visited++
        val bounds = Rect().also(node::getBoundsInScreen)
        val isVisibleNode = runCatching { node.isVisibleToUser }.getOrDefault(false) &&
            bounds.width() > 0 &&
            bounds.height() > 0 &&
            (rootBounds == null || Rect.intersects(rootBounds, bounds))
        if (isVisibleNode) {
            sequenceOf(node.text, node.contentDescription, node.hintText)
                .mapNotNull { it?.toString() }
                .flatMap { it.split('\n').asSequence() }
                .map(::normalizePageText)
                .filter(String::isNotBlank)
                .forEach { text ->
                    if (!isDynamicNormalizedPageText(text) && text.length in 2..48) {
                        keywords += text
                    }
                }
        }
        if (includeMiniProgramIcon && isVisibleNode) {
            val width = bounds.width()
            val height = bounds.height()
            if (width in 36..320 && height in 36..320) {
                val descriptor = buildString {
                    append(node.viewIdResourceName.orEmpty())
                    append(' ')
                    append(node.contentDescription?.toString().orEmpty())
                    append(' ')
                    append(node.className?.toString().orEmpty())
                }.lowercase()
                val keywordScore = MINI_PROGRAM_ICON_KEYWORDS.count(descriptor::contains) * 40
                val imageScore = if (descriptor.contains("image")) 25 else 0
                val squareScore = if (max(width, height).toFloat() / minOf(width, height) <= 1.35f) 15 else 0
                val edgePenalty = if (bounds.top < 260 && bounds.left > 800) 80 else 0
                val score = keywordScore + imageScore + squareScore - edgePenalty
                if (score >= 60 && (bestIcon == null || score > bestIcon.first)) {
                    bestIcon = score to Rect(bounds)
                }
            }
        }
        repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
    }
    return PageNodeSnapshot(keywords.take(80), bestIcon?.second)
}

private val NORMALIZE_WHITESPACE_REGEX = Regex("\\s+")
private val NORMALIZE_PUNCTUATION_REGEX = Regex("[：:·•]+")
private val KEYWORD_SEPARATOR_REGEX = Regex("[\\n,，;；|]+")
private val MINI_PROGRAM_ICON_KEYWORDS = listOf(
    "appbrand", "mini", "avatar", "head", "logo", "icon", "小程序", "头像",
)

internal fun normalizePageText(value: String): String = value
    .trim()
    .lowercase()
    .replace(NORMALIZE_WHITESPACE_REGEX, " ")
    .replace(NORMALIZE_PUNCTUATION_REGEX, " ")
    .trim()

internal fun isDynamicPageText(value: String): Boolean {
    val normalized = normalizePageText(value)
    return isDynamicNormalizedPageText(normalized)
}

private fun isDynamicNormalizedPageText(normalized: String): Boolean {
    if (normalized.isBlank()) return true
    if (normalized.count(Char::isDigit) >= 2) return true
    if (normalized.contains('¥') || normalized.contains('￥')) return true
    return normalized.length > 48
}

internal fun JSONArray?.toStringList(): List<String> = this?.let { array ->
    (0 until array.length()).map(array::optString).filter(String::isNotBlank)
}.orEmpty()
