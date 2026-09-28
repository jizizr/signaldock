package com.jizizr.signaldock

internal const val RECOGNIZING_ISLAND_PROPERTY = 0
internal const val RESULT_ISLAND_PROPERTY = 2

/** HyperOS has no documented infinite sentinel: 0 uses a default and -1 expires early. */
internal data class IslandLifetime(
    val islandSeconds: Int,
    val notificationMinutes: Int,
) {
    // Android 16 replaces zero with a default three-day TTL.
    val notificationMillis: Long get() = notificationMinutes.toLong() * 60_000L
}

// The protocol's longest island lifetime is about 68 years. No renewal timer is needed.
internal val RESULT_ISLAND_LIFETIME = IslandLifetime(
    islandSeconds = Int.MAX_VALUE,
    notificationMinutes = (Int.MAX_VALUE.toLong() + 59L).div(60L).toInt(),
)
internal val RECOGNIZING_ISLAND_LIFETIME = IslandLifetime(120, 2)
internal val TEST_ISLAND_LIFETIME = IslandLifetime(60, 1)

/** Results remain actionable until acknowledged; recognition alone is a one-shot island. */
internal fun islandPropertyFor(statusOnly: Boolean): Int =
    if (statusOnly) RECOGNIZING_ISLAND_PROPERTY else RESULT_ISLAND_PROPERTY

/** Give SystemUI time to render the first island even for a cached or very fast result. */
internal fun recognizingRemainingDisplayMs(postedAtMs: Long, nowMs: Long): Long =
    (600L - (nowMs - postedAtMs).coerceAtLeast(0L)).coerceIn(0L, 600L)
