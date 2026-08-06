package com.andrerinas.openheadunit.aap

/** Which network the Native AA mode puts the phone on. */
enum class NativeTransport {
    /** A WiFi Direct P2P group with this head unit as group owner. The default. */
    WIFI_DIRECT,

    /** This head unit's own WPA2 access point, as the OEM ZLink app uses. Experimental. */
    HOTSPOT;

    companion object {
        /** [Settings.nativeApTransport][com.andrerinas.openheadunit.utils.Settings.nativeApTransport]
         *  as a transport, defaulting to [WIFI_DIRECT] for any value we do not recognise. */
        fun fromSetting(value: Int): NativeTransport = if (value == 1) HOTSPOT else WIFI_DIRECT
    }
}

/** What the framework says about this device's own access point. */
enum class SoftApState {
    /** An access point is running. */
    ENABLED,

    /** The framework is sure there is not one — off, still coming up, or failed. */
    NOT_ENABLED,

    /** Could not be asked. `getWifiApState()` is not public API and is blocked on some devices. */
    UNKNOWN
}

/** What to do when the credentials we are about to send carry no usable BSSID. */
enum class UnusableBssidAction {
    /** Do not send them at all. */
    ABORT,

    /** Send them with the BSSID field left out entirely. */
    OMIT_AND_CONTINUE
}

/**
 * Whether a set of credentials is fit to hand the phone. The interesting case is a missing BSSID,
 * where the answer differs by transport:
 *
 * - **WiFi Direct** aborts. Measured, not cautious: a masked BSSID there means the phone rejects
 *   the credentials anyway, and the usual cause (location services off) is fixable once the log
 *   says so rather than showing a healthy-looking handshake and a phone that never arrives.
 * - **Hotspot** omits the field and sends. aa-proxy-rs and ZLink both ship without one, so an
 *   ordinary AP is identified by SSID; aborting would kill the route on every device with a
 *   masked AP MAC, which is most of them.
 *
 * That last justification is now known to be too strong. A current Gearhead joins with a
 * `WifiNetworkSpecifier`, which matches SSID *and* BSSID under a full `ff:ff:ff:ff:ff:ff` mask: it
 * answered credentials with no BSSID with `WIFI_INVALID_BSSID` and a type 6 `status=-3`, every
 * attempt, and never fell back to matching on the name. Measured 2026-08-05, phone-to-phone.
 *
 * Sending anyway is still the better of the two, and deliberately kept: the field is optional in the
 * protocol, other clients do accept it missing, and a rejection arrives as a message we can explain
 * — where aborting produces nothing to read at all. What changed is that the omission is now the
 * first suspect when the phone refuses, so the handshake says so rather than leaving it implied.
 */
object NativeCredentialsPolicy {

    /** Whether [bssid] is a real address rather than absent, blank or a masking placeholder. */
    fun isUsableBssid(bssid: String?): Boolean = SoftApBssidPolicy.isUsable(bssid)

    /** What to do when [isUsableBssid] said no. */
    fun onUnusableBssid(transport: NativeTransport): UnusableBssidAction = when (transport) {
        NativeTransport.WIFI_DIRECT -> UnusableBssidAction.ABORT
        NativeTransport.HOTSPOT -> UnusableBssidAction.OMIT_AND_CONTINUE
    }

    /**
     * Whether to advertise a network the framework says is not running.
     *
     * The failure this prevents, measured on a Unisoc head unit: with the real access point down,
     * the cellular bridge `seth_lte0` was the only interface up with a private address, so the app
     * paired the right network name with an address no phone could reach and logged SUCCESS. Name
     * exclusions alone cannot fix that class of thing — the list has needed extending three times —
     * because it is a guess about which unknown interfaces are not access points.
     *
     * Two exemptions, and only two:
     *
     * - [SoftApState.UNKNOWN] never refuses. `getWifiApState()` is not public API and is blocked
     *   outright on some devices, and a question we could not ask is not a question answered no.
     * - [interfaceNamedByUser] never refuses. Naming the interface is a specific claim about which
     *   network is up, and it is the escape hatch for a vendor that starts hostapd outside the
     *   framework — where the state read is silent while the access point is plainly there.
     *
     * A hand-typed *SSID* is deliberately not an exemption, though it was once: it names a network,
     * not an interface, and it is required on any device that refuses the config-read API — so
     * exempting it disabled this check on exactly the hardware that needed it.
     */
    fun shouldPublishCredentials(apState: SoftApState, interfaceNamedByUser: Boolean): Boolean =
        apState != SoftApState.NOT_ENABLED || interfaceNamedByUser
}
