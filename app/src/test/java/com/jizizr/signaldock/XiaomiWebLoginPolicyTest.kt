package com.jizizr.signaldock

import org.junit.Assert.*
import org.junit.Test

class XiaomiWebLoginPolicyTest {
    @Test fun loginNavigationRejectsExternalOriginsAndCredentialUrls() {
        assertTrue(isXiaomiLoginUrlAllowed("https://account.xiaomi.com/pass/serviceLogin?sid=ai-service"))
        assertTrue(isXiaomiLoginUrlAllowed("https://account.ai.xiaomi.com/oauthcallback"))
        for (url in listOf("http://account.xiaomi.com/", "https://account.xiaomi.com.evil.test/",
            "https://account.xiaomi.com@evil.test/", "https://user@account.xiaomi.com/",
            "https://account.xiaomi.com:8443/", "javascript:alert(1)", "file:///data/local/tmp/test")) {
            assertFalse(url, isXiaomiLoginUrlAllowed(url))
        }
    }

    @Test fun onlyTargetServiceTicketIsAccepted() {
        val credential = xiaomiWebServiceCredential("serviceToken=test==; userId=123", "passToken=not-used")!!
        assertEquals("test==", credential.token)
        assertEquals("123", credential.userId)
        assertNull(xiaomiWebServiceCredential(null, "serviceToken=wrong-service; userId=123"))
        assertNull(xiaomiWebServiceCredential("serviceToken=EXPIRED; userId=123", null))
        assertNull(xiaomiWebServiceCredential("serviceToken=test; userId=bad\r\nCookie:evil", null))
        assertNull(xiaomiWebServiceCredential("serviceToken=test\r\nevil; userId=123", null))
    }
}
