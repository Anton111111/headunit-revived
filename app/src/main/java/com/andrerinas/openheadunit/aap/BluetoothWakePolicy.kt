package com.andrerinas.openheadunit.aap

import java.util.UUID

/**
 * Rules for the Native AA wake poke: which of the phone's Bluetooth records it may reach for, and
 * when it may connect at all.
 *
 * The poke raises an ACL connection so the phone notices a wireless-capable head unit. It is
 * load-bearing — some units never connect without it — so nothing here switches it off. What the
 * rules decide is when a poke would cost more than it gains.
 *
 * Pure and unit-tested; the sockets live in `NativeAaHandshakeManager`.
 */
object BluetoothWakePolicy {

    /** Handsfree Profile — Audio Gateway. The phone's hands-free side; a call rides on this. */
    val HFP_AG_UUID: UUID = UUID.fromString("0000111f-0000-1000-8000-00805f9b34fb")

    /**
     * Headset Profile — Audio Gateway. Long stored under the name `A2DP_SOURCE_UUID`, which it never
     * was: A2DP Source is `0000110a`. Both values are confirmed against
     * nisargjhaveri/WirelessAndroidAutoDongle and mossyhub/openautolink.
     */
    val HSP_AG_UUID: UUID = UUID.fromString("00001112-0000-1000-8000-00805f9b34fb")

    /**
     * The records a poke tries, in order — openautolink's ConnectProfile fallback chain.
     *
     * Deliberately not a setting. A selectable HSP-AG-only mode was built and removed once the rig
     * measured a successful HSP-AG poke leaving this unit's hands-free link down for three minutes,
     * the same outcome as HFP-AG: a phone serves both records from one headset connection, so which
     * one is asked for was never the lever. [shouldPoke] is.
     */
    val POKE_TARGETS: List<UUID> = listOf(HFP_AG_UUID, HSP_AG_UUID)

    /** Reader-facing name for a target, so a log says what was touched rather than a UUID. */
    fun profileName(uuid: UUID): String = when (uuid) {
        HFP_AG_UUID -> "HFP-AG"
        HSP_AG_UUID -> "HSP-AG"
        else -> uuid.toString()
    }

    /** What this head unit's own Bluetooth stack says about its hands-free link. */
    enum class HandsFreeLink {
        /** A link is up. Poking would take the phone's slot away from it. */
        CONNECTED,

        /** No link. Nothing for a poke to displace. */
        ABSENT,

        /** The adapter would not say. Pokes anyway — see [shouldPoke]. */
        UNREADABLE;

        companion object {
            /** Maps [com.andrerinas.openheadunit.utils.BluetoothHelper.handsFreeLinkState]'s
             *  three-valued answer, so the null case is named rather than implied. */
            fun of(connected: Boolean?): HandsFreeLink = when (connected) {
                true -> CONNECTED
                false -> ABSENT
                null -> UNREADABLE
            }
        }
    }

    /**
     * Whether the wake poke may run, given what this head unit's own hands-free link is doing.
     *
     * Measured, not theorised: a poke that connects takes the phone's single hands-free slot and
     * this unit's own client is dropped to make room. `HfpClientConnectionService` logged its
     * disconnect from the same peer 4 ms after `socket.connect()` returned, and the link stayed down
     * eight minutes until a Bluetooth adapter cycle. The user sees a head unit reporting Bluetooth
     * disconnected while the phone reports it connected, and calls coming out of the phone.
     *
     * Skipping costs nothing, because a live hands-free link *is* the ACL connection a poke exists
     * to create. Units where the poke is load-bearing have no such link to read, so they keep
     * today's behaviour — as does [HandsFreeLink.UNREADABLE], since an adapter that will not report
     * its profiles must not silently disable a mechanism some units cannot connect without.
     */
    fun shouldPoke(handsFreeLink: HandsFreeLink): Boolean = handsFreeLink != HandsFreeLink.CONNECTED
}
