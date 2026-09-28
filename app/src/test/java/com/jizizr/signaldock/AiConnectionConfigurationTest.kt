package com.jizizr.signaldock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConnectionConfigurationTest {
    @Test fun endpointValidationAcceptsCustomPathsAndLocalServers() {
        for (url in listOf("https://api.example.com/v1", "http://127.0.0.1:8080/v1/", "https://[::1]:8443/v1")) {
            assertTrue(url, isValidAiBaseUrl(url))
        }
    }

    @Test fun endpointValidationRejectsAmbiguousAndCredentialBearingAddresses() {
        for (url in listOf("", "api.example.com", "ftp://api.example.com", "https://", "https://api.example.com:99999", "https://name:secret@example.com/v1", "https://api.example.com?token=secret", "https://api.example.com/#fragment")) {
            assertFalse(url, isValidAiBaseUrl(url))
        }
    }
}
