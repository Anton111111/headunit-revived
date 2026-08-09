package com.andrerinas.openheadunit.aap

import java.util.UUID

/**
 * Which of the phone's Bluetooth records the Native AA wake poke may reach for, and what to call
 * them in a log.
 *
 * The poke raises an ACL connection so the phone notices a wireless-capable head unit. It is
 * load-bearing — some units never connect without it — so nothing here switches it off.
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

    /** The records a poke tries, in order — openautolink's ConnectProfile fallback chain. */
    val POKE_TARGETS: List<UUID> = listOf(HFP_AG_UUID, HSP_AG_UUID)

    /** Reader-facing name for a target, so a log says what was touched rather than a UUID. */
    fun profileName(uuid: UUID): String = when (uuid) {
        HFP_AG_UUID -> "HFP-AG"
        HSP_AG_UUID -> "HSP-AG"
        else -> uuid.toString()
    }
}
