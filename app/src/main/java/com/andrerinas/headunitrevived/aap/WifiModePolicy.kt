package com.andrerinas.headunitrevived.aap

/**
 * Whether a given WiFi mode/strategy combination uses [com.andrerinas.headunitrevived.connection.WifiDirectManager]
 * to run a WiFi Direct P2P group. Shared between [AapService.initWifiMode] (stop it on a
 * *settings change*) and [AapService.onDisconnected] (stop it on a *user disconnect*) so the
 * two call sites can't drift out of sync.
 */
object WifiModePolicy {
    fun usesWifiDirect(mode: Int, strategy: Int): Boolean = (mode == 3) || (mode == 2 && strategy == 1)
}
