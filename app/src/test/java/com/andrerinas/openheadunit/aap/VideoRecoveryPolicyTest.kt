package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.VideoRecoveryPolicy.KEYFRAME_REQUEST_THROTTLE_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRecoveryPolicyTest {

    @Test
    fun `a request is allowed once the throttle window has fully elapsed`() {
        val last = 10_000L
        assertFalse(VideoRecoveryPolicy.canRequestKeyframe(last, last))
        assertFalse(
            VideoRecoveryPolicy.canRequestKeyframe(last + KEYFRAME_REQUEST_THROTTLE_MS, last)
        )
        assertTrue(
            VideoRecoveryPolicy.canRequestKeyframe(last + KEYFRAME_REQUEST_THROTTLE_MS + 1, last)
        )
    }

    @Test
    fun `the first request of a session is always allowed`() {
        assertTrue(VideoRecoveryPolicy.canRequestKeyframe(nowMs = 12_345L, lastRequestMs = 0L))
    }

    @Test
    fun `a deferred request stays pending until the throttle expires`() {
        val last = 10_000L
        assertFalse(
            VideoRecoveryPolicy.isDeferredRequestDue(last + 500L, last, deferred = true)
        )
        assertTrue(
            VideoRecoveryPolicy.isDeferredRequestDue(
                last + KEYFRAME_REQUEST_THROTTLE_MS + 1, last, deferred = true
            )
        )
    }

    @Test
    fun `nothing is due when no request was deferred`() {
        val last = 10_000L
        assertFalse(
            VideoRecoveryPolicy.isDeferredRequestDue(
                last + KEYFRAME_REQUEST_THROTTLE_MS + 1, last, deferred = false
            )
        )
    }

    @Test
    fun `a deferred request never overtakes the throttle it was deferred by`() {
        // The pairing that matters: whenever a request is suppressed, the deferred check must
        // agree it is not yet due, so no path can send two requests inside one throttle window.
        val last = 10_000L
        for (offset in 0L..KEYFRAME_REQUEST_THROTTLE_MS) {
            val now = last + offset
            assertFalse(VideoRecoveryPolicy.canRequestKeyframe(now, last))
            assertFalse(VideoRecoveryPolicy.isDeferredRequestDue(now, last, deferred = true))
        }
    }
}
