package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootLoopPolicyTest {

    /**
     * Replays a run of boot-started lifetimes in milliseconds and returns the strike count left
     * behind, the way the receiver and the service between them maintain it: every boot-started run
     * takes a strike on the way in, and clears the count if it survives long enough.
     */
    private fun strikesAfter(vararg runLengthsMs: Long): Int =
        runLengthsMs.fold(0) { strikes, runMs ->
            val taken = BootLoopPolicy.nextStrikes(strikes)
            if (BootLoopPolicy.clearsStrikes(runMs)) 0 else taken
        }

    @Test
    fun `strikes accumulate across runs that die young`() {
        assertEquals(1, strikesAfter(8_000))
        assertEquals(2, strikesAfter(8_000, 9_000))
        assertEquals(3, strikesAfter(8_000, 9_000, 10_000))
    }

    @Test
    fun `the third short run pauses wireless, the first two do not`() {
        assertFalse(BootLoopPolicy.shouldPauseWireless(strikesAfter(8_000)))
        assertFalse(BootLoopPolicy.shouldPauseWireless(strikesAfter(8_000, 9_000)))
        assertTrue(BootLoopPolicy.shouldPauseWireless(strikesAfter(8_000, 9_000, 10_000)))
    }

    @Test
    fun `a run that survives clears the count`() {
        assertEquals(0, strikesAfter(8_000, 9_000, 60_000))
        assertFalse(BootLoopPolicy.shouldPauseWireless(strikesAfter(8_000, 9_000, 60_000)))
    }

    @Test
    fun `the healthy threshold is a floor, not a target`() {
        assertFalse(BootLoopPolicy.clearsStrikes(BootLoopPolicy.HEALTHY_RUN_MS - 1))
        assertTrue(BootLoopPolicy.clearsStrikes(BootLoopPolicy.HEALTHY_RUN_MS))
    }

    @Test
    fun `the reported crash loop trips the guard`() {
        // Process lifetimes measured from the #774 logs: the first cycle reached a full session
        // with audio before the system died, the rest never got that far.
        assertTrue(BootLoopPolicy.shouldPauseWireless(strikesAfter(173_000, 8_000, 9_000, 15_000)))
        // Same unit on 3.2.1, three cycles.
        assertTrue(BootLoopPolicy.shouldPauseWireless(strikesAfter(48_000, 10_000, 15_000)))
    }

    @Test
    fun `an ordinary run of short trips does not trip it`() {
        // Every trip long enough to clear on its own, however many there are.
        assertEquals(0, strikesAfter(45_000, 90_000, 31_000, 120_000))
    }

    @Test
    fun `a single bad boot between good ones is forgiven`() {
        assertEquals(0, strikesAfter(60_000, 5_000, 60_000))
        assertFalse(BootLoopPolicy.shouldPauseWireless(strikesAfter(60_000, 5_000, 60_000)))
    }

    @Test
    fun `a corrupt or absent stored count starts a fresh run`() {
        assertEquals(1, BootLoopPolicy.nextStrikes(-4))
        assertEquals(1, BootLoopPolicy.nextStrikes(0))
    }
}
