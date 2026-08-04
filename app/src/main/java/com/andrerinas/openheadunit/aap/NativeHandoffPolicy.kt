package com.andrerinas.openheadunit.aap

/**
 * The window between "we sent the phone our WiFi credentials (Type 3)" and "the phone's TCP
 * session actually landed", and what the rest of the app is allowed to do during it.
 *
 * The phone still has to associate, run WPS and get a DHCP lease after Type 3 goes out, and on
 * slower hardware that takes far longer than the handshake itself — 21 s in a known-good 3.1.1
 * log. Anything that touches the Bluetooth radio or takes the P2P group owner off-channel in
 * that window can make the phone abandon the join, which surfaces as "Obtaining IP address"
 * forever on the phone (#760).
 *
 * Split out as a pure object so the timing rules are unit-testable without Android, and so
 * [com.andrerinas.openheadunit.connection.NativeAaHandshakeManager] and
 * [com.andrerinas.openheadunit.connection.WifiDirectManager] share one definition instead of
 * re-deriving it.
 */
object NativeHandoffPolicy {

    /** How long to keep Bluetooth open waiting for the phone's TCP session before giving up on
     *  this handoff and letting the wake poke retry. Generous on purpose: the cost of waiting too
     *  long is one slow retry, the cost of waiting too little is a dead connection. */
    const val SETTLE_TIMEOUT_MS = 45_000L

    /** Longest a single credential exchange can legitimately take: the up-to-60 s wait for the
     *  P2P group's credentials, plus the 15 s wait for the phone's Type 2, plus slack. Past this
     *  the exchange is over however it ended, so treating it as live only suppresses recovery. */
    const val HANDSHAKE_TIMEOUT_MS = 90_000L

    /**
     * Whether we are still inside the post-Type-3 settling window.
     *
     * [settlingSinceMs] is an elapsed-realtime stamp, or 0 when no handoff is settling. The
     * window is bounded so a missed reset can't latch the state true forever.
     */
    fun isSettling(settlingSinceMs: Long, nowMs: Long, timeoutMs: Long = SETTLE_TIMEOUT_MS): Boolean =
        isWithinWindow(settlingSinceMs, nowMs, timeoutMs)

    /**
     * Whether a credential exchange is still in progress.
     *
     * [startedAtMs] is an elapsed-realtime stamp, or 0 when no handshake is running. Bounded for
     * the same reason [isSettling] is, and not merely as a belt-and-braces measure: on the #706
     * reporter's head unit, closing the Bluetooth socket does not unblock a pending
     * `readFully()`, so the handshake coroutine never reaches its own cleanup. A plain boolean
     * latched true there and stayed true for the life of the process, which stopped the wake
     * poke retrying and made `WifiDirectManager`'s join watchdog defer recovery forever.
     */
    fun isHandshaking(startedAtMs: Long, nowMs: Long, timeoutMs: Long = HANDSHAKE_TIMEOUT_MS): Boolean =
        isWithinWindow(startedAtMs, nowMs, timeoutMs)

    /** True when [sinceMs] is a live stamp (non-zero, not in the future) less than [timeoutMs] old. */
    private fun isWithinWindow(sinceMs: Long, nowMs: Long, timeoutMs: Long): Boolean {
        if (sinceMs == 0L) return false
        val elapsed = nowMs - sinceMs
        return elapsed >= 0 && elapsed < timeoutMs
    }

    /**
     * Whether the wake poke may run. The poke opens a real RFCOMM connection to the phone's
     * HFP/HSP service record, so it must stay off the radio while a handshake is exchanging
     * credentials, while a handoff is settling, and once a session is up (there is nothing left
     * to wake).
     */
    fun shouldPoke(settling: Boolean, handshakeInFlight: Boolean, sessionConnected: Boolean): Boolean =
        !settling && !handshakeInFlight && !sessionConnected

    /** How many wake pokes the phone may answer without ever opening the Android Auto channel
     *  before we say so in the log. Three is roughly 45 s of the retry cadence — long enough that
     *  a phone which is merely slow to react has had its chance. */
    const val SILENT_POKE_WARN_INTERVAL = 3

    /**
     * Whether to warn that the phone answers our wake pokes but never connects back.
     *
     * That combination has exactly one common cause, and it is not something this app can fix:
     * the phone's Android Auto is bound to a *different* Bluetooth device that also advertises
     * the Android Auto service record. On the #706 reporter's unit that was the head unit's own
     * OEM Bluetooth module ("CAR8032"), a separate chip that Android on the head unit cannot see,
     * still advertising the service after its OEM app stopped answering on it. Gearhead kept
     * reconnecting to it every 12 s and discarded every poke from our radio as
     * `IGNORE_DIFFERENT_BLUETOOTH_DEVICE`. Our own log showed successful pokes and nothing wrong.
     *
     * [everAccepted] gates it to units that have never once been reached, so a unit that connects
     * normally and then has a bad run never sees it. Periodic rather than one-shot so it is still
     * visible in a log exported long after the trouble started.
     */
    fun shouldWarnPhoneNeverCallsBack(pokesSinceLastAccept: Int, everAccepted: Boolean): Boolean =
        !everAccepted &&
            pokesSinceLastAccept > 0 &&
            pokesSinceLastAccept % SILENT_POKE_WARN_INTERVAL == 0

    /**
     * Whether a client leaving the P2P group should restart the peer-discovery loop.
     *
     * Never on the Native AA path: we run a *quiet* host there and the phone finds us by SSID
     * from the credentials we handed it over Bluetooth, never by discovery. `discoverPeers()`
     * takes the group owner off-channel every 10 s, which is precisely what stops a retrying
     * phone from ever completing DHCP.
     */
    fun shouldRestartDiscovery(nativeAaMode: Boolean, hadClient: Boolean, hasClient: Boolean): Boolean =
        hadClient && !hasClient && !nativeAaMode
}
