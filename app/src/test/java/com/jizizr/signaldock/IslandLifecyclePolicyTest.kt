package com.jizizr.signaldock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandLifecyclePolicyTest {
    @Test
    fun fastResultWaitsOnlyForTheRemainingRecognitionDisplayTime() {
        assertEquals(600L, recognizingRemainingDisplayMs(1_000L, 1_000L))
        assertEquals(450L, recognizingRemainingDisplayMs(1_000L, 1_150L))
        assertEquals(0L, recognizingRemainingDisplayMs(1_000L, 1_600L))
        assertEquals(0L, recognizingRemainingDisplayMs(1_000L, 5_000L))
    }
    @Test
    fun resultUsesOperationIslandWhileRecognitionIsTransient() {
        assertEquals(2, islandPropertyFor(statusOnly = false))
        assertEquals(0, islandPropertyFor(statusOnly = true))
    }

    @Test
    fun resultUsesLongestProtocolLifetimeWithoutZeroOrNegativeSentinels() {
        assertEquals(Int.MAX_VALUE, RESULT_ISLAND_LIFETIME.islandSeconds)
        val notificationSeconds = RESULT_ISLAND_LIFETIME.notificationMinutes.toLong() * 60L
        assertTrue(notificationSeconds >= RESULT_ISLAND_LIFETIME.islandSeconds)
        assertTrue(notificationSeconds - RESULT_ISLAND_LIFETIME.islandSeconds < 60L)
        assertEquals(notificationSeconds * 1000L, RESULT_ISLAND_LIFETIME.notificationMillis)
        assertTrue(RESULT_ISLAND_LIFETIME.notificationMillis > Int.MAX_VALUE.toLong())
    }

    @Test
    fun transientIslandsStillExpireAndUseSeparateTimeUnits() {
        assertEquals(IslandLifetime(120, 2), RECOGNIZING_ISLAND_LIFETIME)
        assertEquals(IslandLifetime(60, 1), TEST_ISLAND_LIFETIME)
    }

    @Test
    fun clearedSessionCannotBeRepostedRegardlessOfForegroundAnchor() {
        for (foreground in listOf(false, true)) {
            for (anchor in listOf(7, 8)) {
                assertEquals(
                    ResultNotificationPublishDecision.DROP,
                    resultNotificationPublishDecision(foreground, anchor, 7, false),
                )
            }
        }
    }

    @Test
    fun islandTapOpensSourceButPullDownShowsQr() {
        assertTrue(shouldOpenIslandSource(QrResultActivity.MODE_IMAGE, false, true))
        assertFalse(shouldOpenIslandSource(QrResultActivity.MODE_IMAGE, true, true))
    }

    @Test
    fun standardQrButtonShowsQrInFullscreen() {
        assertFalse(shouldOpenIslandSource(QrResultActivity.MODE_IMAGE, false, false))
        assertFalse(shouldOpenIslandSource(QrResultActivity.MODE_IMAGE, true, false))
    }

    @Test
    fun islandWithoutQrAlwaysOpensSource() {
        assertTrue(shouldOpenIslandSource(QrResultActivity.MODE_OPEN_SOURCE, false, true))
        assertTrue(shouldOpenIslandSource(QrResultActivity.MODE_OPEN_SOURCE, true, true))
    }
}
