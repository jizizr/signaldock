package com.jizizr.signaldock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiPickupPolicyTest {
    @Test
    fun onlyCurrentDialogAndItsMemoryFetchMayReceiveTheImage() {
        assertTrue(isPickupDialog("abc123", "abc123"))
        assertTrue(isPickupDialog("abc123", "abc123-MemoryPush-42283"))
        for (dialog in listOf("", "unrelated", "abc1234-MemoryPush-42", "abc123-MemoryPush-",
            "abc123-MemoryPush-42-other", "abc123-OtherPush-42")) {
            assertFalse(dialog, isPickupDialog("abc123", dialog))
        }
        assertFalse(isPickupDialog("", ""))
    }

    @Test
    fun pickupRequiresIndependentWebSessionAndIgnoresTheOtherProvidersExpertMode() {
        assertTrue(isXiaomiConfigurationReady(true, XiaoAiMode.EXPERT, true, true, false))
        assertFalse(isXiaomiConfigurationReady(true, XiaoAiMode.FAST, true, false, true))
        assertFalse(isXiaomiConfigurationReady(true, XiaoAiMode.FAST, false, true, true))
    }

    @Test
    fun fastAndExpertKeepTheirExistingCredentialRequirements() {
        assertTrue(isXiaomiConfigurationReady(false, XiaoAiMode.FAST, true, false, false))
        assertFalse(isXiaomiConfigurationReady(false, XiaoAiMode.EXPERT, true, true, false))
        assertTrue(isXiaomiConfigurationReady(false, XiaoAiMode.EXPERT, true, false, true))
        assertFalse(isXiaomiConfigurationReady(false, XiaoAiMode.EXPERT, false, false, true))
    }
}
