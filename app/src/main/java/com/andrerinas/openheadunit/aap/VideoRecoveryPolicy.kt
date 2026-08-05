package com.andrerinas.openheadunit.aap

/**
 * Pacing rules for [AapVideo]'s stream recovery.
 *
 * Recovering from a corrupt or dropped frame means asking the phone for a fresh keyframe, which
 * [AapTransport] implements as a video-focus loss/regain cycle. That is expensive and briefly
 * visible, so requests are rate limited. But the same recovery also locks out every P-frame until
 * a keyframe arrives, and a lockout with no request behind it can only end on the phone's own
 * keyframe cadence - seconds of discarded video. These two rules keep the throttle from stranding
 * a lockout: one decides when a request may go out, the other when a request the throttle
 * suppressed has become due.
 */
object VideoRecoveryPolicy {

    /** Minimum spacing between keyframe requests, in milliseconds. */
    const val KEYFRAME_REQUEST_THROTTLE_MS = 1000L

    /** Whether enough time has passed since [lastRequestMs] to send another keyframe request. */
    fun canRequestKeyframe(nowMs: Long, lastRequestMs: Long): Boolean =
        nowMs - lastRequestMs > KEYFRAME_REQUEST_THROTTLE_MS

    /**
     * Whether a request that [canRequestKeyframe] previously suppressed should now be sent.
     *
     * [deferred] is the caller's record that it armed a P-frame lockout without being allowed to
     * ask for the keyframe that ends it.
     */
    fun isDeferredRequestDue(nowMs: Long, lastRequestMs: Long, deferred: Boolean): Boolean =
        deferred && canRequestKeyframe(nowMs, lastRequestMs)
}
