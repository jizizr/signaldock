package com.jizizr.signaldock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRecognitionPolicyTest {
    @Test fun failedAutomaticRecognitionOnlyRetriesAfterPageChangeAndStopsAfterTwoAttempts() {
        val coordinator = AutoTriggerCoordinator()
        coordinator.onAttempt("profile", "first-page")
        assertFalse(coordinator.onResult("profile", succeeded = false))
        assertFalse(coordinator.isFired())
        assertFalse(coordinator.canAttempt("profile", "first-page"))
        assertTrue(coordinator.canAttempt("profile", "updated-page"))
        coordinator.onAttempt("profile", "updated-page")
        assertFalse(coordinator.onResult("profile", succeeded = false))
        assertFalse(coordinator.canAttempt("profile", "third-page"))
        coordinator.reset()
        assertTrue(coordinator.canAttempt("profile", "third-page"))
    }

    @Test fun successfulAutomaticRecognitionSuppressesFurtherPageChanges() {
        val coordinator = AutoTriggerCoordinator()
        coordinator.onAttempt("profile", "first-page")
        assertTrue(coordinator.onResult("profile", succeeded = true))
        assertTrue(coordinator.isFired())
        assertFalse(coordinator.canAttempt("profile", "updated-page"))
    }

    @Test fun disabledDiagnosticsDoNotEvaluateExpensiveMessageArguments() {
        assertFalse(BuildConfig.AUTO_PAGE_DIAGNOSTICS)
        var evaluated = false
        AutoPageDiagnostics.log {
            evaluated = true
            "expensive page fingerprint"
        }
        assertFalse(evaluated)
    }
}
