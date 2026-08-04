package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHandoffPolicyTest {

    private val timeout = NativeHandoffPolicy.SETTLE_TIMEOUT_MS

    @Test
    fun `no handoff is settling before any credentials go out`() {
        assertFalse(NativeHandoffPolicy.isSettling(settlingSinceMs = 0L, nowMs = 10_000L))
    }

    @Test
    fun `settles for the whole window after credentials go out`() {
        assertTrue(NativeHandoffPolicy.isSettling(settlingSinceMs = 1_000L, nowMs = 1_000L))
        assertTrue(NativeHandoffPolicy.isSettling(settlingSinceMs = 1_000L, nowMs = 1_000L + timeout / 2))
        assertTrue(NativeHandoffPolicy.isSettling(settlingSinceMs = 1_000L, nowMs = 1_000L + timeout - 1))
    }

    @Test
    fun `settling expires so a missed reset cannot latch it true forever`() {
        assertFalse(NativeHandoffPolicy.isSettling(settlingSinceMs = 1_000L, nowMs = 1_000L + timeout))
        assertFalse(NativeHandoffPolicy.isSettling(settlingSinceMs = 1_000L, nowMs = 1_000L + timeout * 10))
    }

    @Test
    fun `a clock that went backwards does not count as settling`() {
        assertFalse(NativeHandoffPolicy.isSettling(settlingSinceMs = 10_000L, nowMs = 9_000L))
    }

    @Test
    fun `poke runs only when nothing else is using the radio`() {
        assertTrue(
            NativeHandoffPolicy.shouldPoke(
                settling = false, handshakeInFlight = false, sessionConnected = false
            )
        )
    }

    @Test
    fun `poke is blocked while a handoff is settling`() {
        // The #760 case: the phone joining the group re-delivers credentials, which re-invokes
        // triggerPoke() straight into the phone's DHCP exchange.
        assertFalse(
            NativeHandoffPolicy.shouldPoke(
                settling = true, handshakeInFlight = false, sessionConnected = false
            )
        )
    }

    @Test
    fun `poke is blocked during a handshake and once a session is up`() {
        assertFalse(
            NativeHandoffPolicy.shouldPoke(
                settling = false, handshakeInFlight = true, sessionConnected = false
            )
        )
        assertFalse(
            NativeHandoffPolicy.shouldPoke(
                settling = false, handshakeInFlight = false, sessionConnected = true
            )
        )
    }

    @Test
    fun `discovery restarts when a client leaves a non-native group`() {
        assertTrue(
            NativeHandoffPolicy.shouldRestartDiscovery(
                nativeAaMode = false, hadClient = true, hasClient = false
            )
        )
    }

    @Test
    fun `discovery never restarts on the native quiet-host path`() {
        assertFalse(
            NativeHandoffPolicy.shouldRestartDiscovery(
                nativeAaMode = true, hadClient = true, hasClient = false
            )
        )
    }

    @Test
    fun `discovery does not restart unless a client actually left`() {
        assertFalse(
            NativeHandoffPolicy.shouldRestartDiscovery(
                nativeAaMode = false, hadClient = false, hasClient = false
            )
        )
        assertFalse(
            NativeHandoffPolicy.shouldRestartDiscovery(
                nativeAaMode = false, hadClient = true, hasClient = true
            )
        )
    }
}
