package com.jizizr.signaldock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiSessionMergeTest {
    private fun session(
        independent: Boolean = true,
        userId: String = "account-a",
        expiresAt: Long = System.currentTimeMillis() / 1000 + 3600,
    ) = XiaomiSession(
        accessToken = if (independent) "web-token" else "system-token",
        deviceId = if (independent) "web-device" else "phone-device",
        expiresAtSeconds = expiresAt,
        expertToken = if (independent) "" else "expert-token",
        userId = userId,
        cUserId = "encrypted-user",
        independentDevice = independent,
        refreshToken = if (independent) "web-refresh" else "",
    )

    @Test fun expertImportKeepsWorkingWebIdentityForTheSameAccount() {
        val web = session()
        val merged = mergeXiaomiSessions(web, session(independent = false))
        assertTrue(merged.independentDevice)
        assertEquals(web.accessToken, merged.accessToken)
        assertEquals(web.deviceId, merged.deviceId)
        assertEquals(web.expiresAtSeconds, merged.expiresAtSeconds)
        assertEquals(web.refreshToken, merged.refreshToken)
        assertEquals("expert-token", merged.expertToken)
    }

    @Test fun importNeverMixesDifferentOrUnknownAccounts() {
        for (userId in listOf("account-b", "")) {
            val imported = session(independent = false, userId = userId)
            assertSame(imported, mergeXiaomiSessions(session(), imported))
        }
        val imported = session(independent = false, userId = "")
        assertSame(imported, mergeXiaomiSessions(session(userId = ""), imported))
    }

    @Test fun expiredWebSessionDoesNotOverrideUsableImportedSession() {
        val imported = session(independent = false)
        assertSame(imported, mergeXiaomiSessions(session(expiresAt = 1), imported))
        assertSame(imported, mergeXiaomiSessions(null, imported))
    }

    @Test fun sessionMustOutliveTheRequestSafetyWindow() {
        val now = System.currentTimeMillis() / 1000
        assertFalse(session(expiresAt = now + 59).isUsable)
        assertTrue(session(expiresAt = now + 120).isUsable)
    }
}
