package com.andrerinas.headunitrevived.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiModePolicyTest {

    @Test
    fun `native AA mode always uses wifi direct regardless of strategy`() {
        assertTrue(WifiModePolicy.usesWifiDirect(mode = 3, strategy = 0))
        assertTrue(WifiModePolicy.usesWifiDirect(mode = 3, strategy = 1))
        assertTrue(WifiModePolicy.usesWifiDirect(mode = 3, strategy = 2))
    }

    @Test
    fun `helper mode uses wifi direct only with strategy 1`() {
        assertTrue(WifiModePolicy.usesWifiDirect(mode = 2, strategy = 1))
        assertFalse(WifiModePolicy.usesWifiDirect(mode = 2, strategy = 0))
        assertFalse(WifiModePolicy.usesWifiDirect(mode = 2, strategy = 2))
    }

    @Test
    fun `server and other modes never use wifi direct`() {
        assertFalse(WifiModePolicy.usesWifiDirect(mode = 0, strategy = 0))
        assertFalse(WifiModePolicy.usesWifiDirect(mode = 1, strategy = 1))
    }
}
