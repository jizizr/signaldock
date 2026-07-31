package com.jizizr.signaldock

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** Metadata-only tracing for temporary automatic-page diagnostic builds. */
internal object AutoPageDiagnostics {
    private const val TAG = "AutoPageDebug"

    val enabled: Boolean
        get() = BuildConfig.AUTO_PAGE_DIAGNOSTICS

    fun log(message: String) {
        if (enabled) {
            AppLog.i(TAG, "auto-debug uptime=${SystemClock.uptimeMillis()} $message")
        }
    }

    fun profileRef(profileId: String): String = fingerprint(profileId)

    fun fingerprint(value: String): String {
        if (value.isBlank()) return "-"
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return buildString(12) {
            repeat(6) { index -> append("%02x".format(Locale.US, bytes[index].toInt() and 0xff)) }
        }
    }

    fun eventTypeName(eventType: Int): String = when (eventType) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT"
        else -> "0x${eventType.toString(16)}"
    }

    fun describeProfile(profile: AutoPageProfile): String = buildString {
        append("ref=")
        append(profileRef(profile.id))
        append(" enabled=")
        append(profile.enabled)
        append(" package=")
        append(profile.packageName)
        append(" activity=")
        append(profile.activityClassName)
        append(" miniLabelHash=")
        append(fingerprint(normalizePageText(profile.miniProgramLabel)))
        append(" miniIconHash=")
        append(profile.miniProgramIconHash.take(12).ifBlank { "-" })
        append(" required=")
        append(profile.keywords.joinToString(prefix = "[", postfix = "]") {
            fingerprint(normalizePageText(it))
        })
        append(" excluded=")
        append(profile.excludedKeywords.joinToString(prefix = "[", postfix = "]") {
            fingerprint(normalizePageText(it))
        })
        append(" locked=")
        append(AutoPageNotificationLockStore.isLocked(profile.id))
    }

    fun describeMatch(
        observation: PageObservationSnapshot,
        profile: AutoPageProfile,
        rankedMatch: AutoPageMatch?,
    ): String {
        val searchable = observation.stableKeywords.joinToString(" ") { normalizePageText(it) }
        val required = profile.keywords.map { keyword ->
            val normalized = normalizePageText(keyword)
            "${fingerprint(normalized)}:${searchable.contains(normalized)}"
        }
        val excluded = profile.excludedKeywords.map { keyword ->
            val normalized = normalizePageText(keyword)
            "${fingerprint(normalized)}:${searchable.contains(normalized)}"
        }
        val labelMatches = profile.miniProgramLabel.isNotBlank() &&
            observation.miniProgramLabel.isNotBlank() &&
            normalizePageText(profile.miniProgramLabel) ==
            normalizePageText(observation.miniProgramLabel)
        val iconMatches = profile.miniProgramIconHash.isNotBlank() &&
            observation.miniProgramIconHash.isNotBlank() &&
            profile.miniProgramIconHash == observation.miniProgramIconHash
        return buildString {
            append("profile=")
            append(profileRef(profile.id))
            append(" packageMatch=")
            append(profile.packageName == observation.packageName)
            append(" expectedActivity=")
            append(profile.activityClassName)
            append(" observedActivity=")
            append(observation.activityClassName)
            append(" observedMini=")
            append(observation.isWechatMiniProgram)
            append(" miniLabelMatch=")
            append(labelMatches)
            append(" miniIconMatch=")
            append(iconMatches)
            append(" nodes=")
            append(observation.stableKeywords.size)
            append(" nodeSetHash=")
            append(fingerprint(searchable))
            append(" required=")
            append(required)
            append(" excluded=")
            append(excluded)
            append(" ranked=")
            append(rankedMatch != null)
            append(" textMatched=")
            append(rankedMatch?.textMatched ?: false)
            append(" score=")
            append(rankedMatch?.score?.let { "%.3f".format(Locale.US, it) } ?: "-")
        }
    }
}
