package com.andrerinas.headunitrevived.connection

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.utils.AppLog
import java.net.InetSocketAddress
import java.net.Socket

class WifiDirectManager(private val context: Context) : WifiP2pManager.ConnectionInfoListener, WifiP2pManager.GroupInfoListener {

@Volatile private var manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var isGroupOwner = false
    private var isConnected = false
    private val handler = Handler(Looper.getMainLooper())
    private var localDeviceAddress: String? = null
    private var lastKnownBssid: String? = null
    private var isReceiverRegistered = false
    private var discoveredInterface: String? = null

    private var onCredentialsReady: ((ssid: String, psk: String, ip: String, bssid: String) -> Unit)? = null

    fun setCredentialsListener(callback: (String, String, String, String) -> Unit) {
        this.onCredentialsReady = callback
    }

    private val discoveryRunnable = object : Runnable {
        override fun run() {
            if (!isConnected) {
                startDiscovery()
                handler.postDelayed(this, 10000L) // Repeat every 10s to stay visible
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    device?.let {
                        if (com.andrerinas.headunitrevived.App.provide(context).settings.wifiConnectionMode != 3) {
                            AppLog.i("WifiDirectManager: Local name: ${it.deviceName}, Address: ${it.deviceAddress}")
                        }
                        AapService.wifiDirectName.value = it.deviceName
                        localDeviceAddress = it.deviceAddress
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        AppLog.i("WifiDirectManager: Connected. Requesting info...")
                        // [FIX] Pre-fetch localDeviceAddress here so it's ready before
                        // onGroupInfoAvailable fires — reduces race condition window.
                        WifiDirectCompat.requestDeviceInfo(manager, channel) { address ->
                            if (localDeviceAddress == null || localDeviceAddress == "00:00:00:00:00:00" || localDeviceAddress == "02:00:00:00:00:00") {
                                AppLog.d("WifiDirectManager: Pre-fetched localDeviceAddress on connect: $address")
                                localDeviceAddress = address
                            }
                        }
                        manager?.requestConnectionInfo(channel, this@WifiDirectManager)
                    } else {
                        isConnected = false
                    }
                }
            }
        }
    }

    init {
        try {
            if (context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT)) {
                AppLog.i("WifiDirectManager: Device supports WiFi Direct. Initializing...")
                manager?.let { mgr ->
                    channel = mgr.initialize(context, context.mainLooper, null)
                    
                    WifiDirectCompat.requestDeviceInfo(manager, channel) { address ->
                        AppLog.i("WifiDirectManager: requestDeviceInfo success: $address")
                        localDeviceAddress = address
                    }

                    registerReceiverIfNeeded()
                } ?: run {
                    AppLog.e("WifiDirectManager: WIFI_P2P_SERVICE manager is NULL!")
                }
            } else {
                AppLog.e("WifiDirectManager: Device does NOT report FEATURE_WIFI_DIRECT!")
            }
        } catch (e: SecurityException) {
            AppLog.w("WifiDirectManager: WiFi Direct unavailable — permission denied: ${e.message}")
        } catch (e: Exception) {
            AppLog.e("WifiDirectManager: Unexpected error in init", e)
        }
    }

    private fun registerReceiverIfNeeded() {
        if (isReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            isReceiverRegistered = true
            AppLog.d("WifiDirectManager: BroadcastReceiver registered.")
        } catch (e: Exception) {
            AppLog.e("WifiDirectManager: Failed to register receiver", e)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        if (info.groupFormed) {
            isConnected = true
            isGroupOwner = info.isGroupOwner
            
            val goIp = info.groupOwnerAddress?.hostAddress ?: "unknown"
            AppLog.i("WifiDirectManager: Group formed. Owner: $isGroupOwner, GO IP: $goIp")

            if (isGroupOwner) {
                // [FIX] requestDeviceInfo is async — call requestGroupInfo only AFTER the callback
                // fires so that localDeviceAddress is guaranteed to be set before onGroupInfoAvailable
                // runs. This eliminates the race condition that caused empty BSSIDs on Android 12+.
                WifiDirectCompat.requestDeviceInfo(manager, channel) { address ->
                    AppLog.d("WifiDirectManager: Updated localDeviceAddress: $address")
                    localDeviceAddress = address
                    manager?.requestGroupInfo(channel, this@WifiDirectManager)
                }
                // Fallback: if requestDeviceInfo is not supported (< API 29), call directly
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    manager?.requestGroupInfo(channel, this)
                }
            } else if (info.groupOwnerAddress != null) {
                Thread {
                    var socket: Socket? = null
                    try {
                        AppLog.i("WifiDirectManager: Pinging Phone (GO) at $goIp to announce tablet...")
                        socket = Socket()
                        socket.connect(InetSocketAddress(info.groupOwnerAddress, 5289), 2000)
                    } catch (e: Exception) {
                        AppLog.w("WifiDirectManager: Ping to GO failed: ${e.message}")
                    } finally {
                        try { socket?.close() } catch (e: Exception) {}
                    }
                }.start()
            }
        } else {
            AppLog.d("WifiDirectManager: onConnectionInfoAvailable: group not formed yet")
        }
    }

    private var groupInfoRetries = 0

    @SuppressLint("MissingPermission")
    override fun onGroupInfoAvailable(group: android.net.wifi.p2p.WifiP2pGroup?) {
        if (group != null) {
            groupInfoRetries = 0
            val ssid = group.networkName
            val psk = group.passphrase ?: ""
            val isOwner = group.isGroupOwner

            // [FIX] Robust interface detection. group.interface is often null on Android 11+ (hidden API)
            var iface = group.`interface`
            if (iface.isNullOrEmpty()) {
                iface = getInterfaceByIp("192.168.49.1")
                if (iface != null) {
                    AppLog.i("WifiDirectManager: Discovered interface name by IP 192.168.49.1: $iface")
                }
            }
            discoveredInterface = iface

            var bssid = getWifiDirectMac(iface)

            // [FIX] Robust BSSID detection for masked MACs (00:00 or 02:00)
            if (bssid == "00:00:00:00:00:00" || bssid == "02:00:00:00:00:00") {
                // Fallback 1: Use last known valid BSSID
                if (!lastKnownBssid.isNullOrEmpty() && lastKnownBssid != "00:00:00:00:00:00" && lastKnownBssid != "02:00:00:00:00:00") {
                    AppLog.i("WifiDirectManager: BSSID masked, using lastKnownBssid: $lastKnownBssid")
                    bssid = lastKnownBssid!!
                }
                // Fallback 2: Use captured localDeviceAddress (from THIS_DEVICE_CHANGED or requestDeviceInfo)
                else if (!localDeviceAddress.isNullOrEmpty() && localDeviceAddress != "00:00:00:00:00:00" && localDeviceAddress != "02:00:00:00:00:00") {
                    AppLog.i("WifiDirectManager: BSSID masked, using localDeviceAddress: $localDeviceAddress")
                    bssid = localDeviceAddress!!
                } 
                // Fallback 3: Use group.owner.deviceAddress
                else {
                    val ownerAddr = group.owner?.deviceAddress
                    if (!ownerAddr.isNullOrEmpty() && ownerAddr != "00:00:00:00:00:00" && ownerAddr != "02:00:00:00:00:00") {
                        AppLog.i("WifiDirectManager: BSSID masked, using group.owner.deviceAddress: $ownerAddr")
                        bssid = ownerAddr
                    } 
                    // Fallback 4: Shell command or sysfs
                    else {
                        val shellMac = getMacFromShell(iface)
                        if (shellMac != null) {
                            AppLog.i("WifiDirectManager: BSSID masked, using shell/sysfs fallback: $shellMac")
                            bssid = shellMac
                        }
                    }
                }
            }

            if (bssid != "00:00:00:00:00:00" && bssid != "02:00:00:00:00:00") {
                lastKnownBssid = bssid
            }

            // Try to get frequency via reflection (hidden field in WifiP2pGroup)
            var frequency = 0
            try {
                // Try several common field names used by different OEMs
                val fieldNames = arrayOf("frequency", "mFrequency")
                for (name in fieldNames) {
                    try {
                        val field = group.javaClass.getDeclaredField(name)
                        field.isAccessible = true
                        frequency = field.getInt(group)
                        if (frequency > 0) break
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {}

            val band = if (frequency > 4000) "5GHz" else if (frequency > 0) "2.4GHz" else "unknown"
            AppLog.i("WifiDirectManager: onGroupInfoAvailable: SSID: $ssid, BSSID: $bssid, GO: $isOwner, IFACE: ${iface ?: "null"}, Freq: $frequency MHz ($band)")

            if (ssid.isNotEmpty()) {
                // Wait for the IP address to be assigned to the interface
                Thread {
                    try {
                        var ip = getWifiDirectIp(iface)
                        var retries = 0
                        while (ip == null && retries < 15) {
                            AppLog.d("WifiDirectManager: Waiting for IP on interface ${iface ?: "any p2p"} (Attempt ${retries + 1}/15)...")
                            Thread.sleep(1000)
                            ip = getWifiDirectIp(iface)
                            retries++
                        }

                        // For Native AA, we almost always expect 192.168.49.1 if we are GO
                        val finalIp = ip ?: (if (isOwner) "192.168.49.1" else null)
                        if (finalIp != null) {
                            AppLog.i("WifiDirectManager: SUCCESS - Providing credentials to listener. SSID=$ssid, IP=$finalIp, BSSID=$bssid")
                            onCredentialsReady?.invoke(ssid, psk, finalIp, bssid)
                        } else {
                            AppLog.e("WifiDirectManager: FAILED to get valid IP for credentials delivery.")
                        }
                    } catch (e: Exception) {
                        AppLog.e("WifiDirectManager: Error in credential delivery thread", e)
                    }
                }.start()
            }
        } else {
            if (groupInfoRetries < 20) {
                groupInfoRetries++
                AppLog.w("WifiDirectManager: Group info was null! Retrying in 1s (Attempt $groupInfoRetries/20)...")
                handler.postDelayed({
                    channel?.let { ch ->
                        manager?.requestGroupInfo(ch, this)
                    }
                }, 1000L)
            } else {
                AppLog.e("WifiDirectManager: FATAL: Group info remained null after 20 retries.")
            }
        }
    }

    private fun getInterfaceByIp(targetIp: String): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr.hostAddress == targetIp) return iface.name
                }
            }
        } catch (e: Exception) {}
        return null
    }

    private fun getWifiDirectMac(ifaceName: String?): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                
                // If we have a name, it must match.
                if (ifaceName != null && iface.name != ifaceName) continue
                
                // If we don't have a name, look for common P2P interface patterns
                if (ifaceName == null) {
                    val name = iface.name.lowercase()
                    if (!name.contains("p2p") && !name.contains("wlan") && !name.contains("ap")) continue
                }

                val mac = iface.hardwareAddress
                if (mac != null) {
                    val sb = StringBuilder()
                    for (i in mac.indices) {
                        sb.append(String.format("%02X%s", mac[i], if (i < mac.size - 1) ":" else ""))
                    }
                    val result = sb.toString()
                    if (result != "00:00:00:00:00:00" && result != "02:00:00:00:00:00") {
                        return result
                    }
                }
            }
        } catch (e: Exception) {}
        return "00:00:00:00:00:00"
    }

    private fun getWifiDirectIp(ifaceName: String?): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        // Prioritize explicitly requested interface
                        if (ifaceName != null && iface.name == ifaceName) return addr.hostAddress
                        
                        // Fallback: search for 192.168.49.1 (Standard P2P GO IP)
                        if (addr.hostAddress == "192.168.49.1") return addr.hostAddress

                        // Fallback: search for any interface with "p2p" in name
                        if (ifaceName == null && iface.name.lowercase().contains("p2p")) return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("WifiDirectManager: Error getting local IP", e)
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun makeVisible() {
        val mgr = manager ?: return
        val ch = channel ?: return

        // Ensure WiFi is enabled (Required for P2P)
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (!wifiManager.isWifiEnabled) {
            AppLog.w("WifiDirectManager: WiFi is disabled. Cannot start P2P discovery.")
            Toast.makeText(context, context.getString(R.string.wifi_disabled_info), Toast.LENGTH_LONG).show()
            return
        }

        // Reflection Hack to set name
        try {
            val method = mgr.javaClass.getMethod("setDeviceName", WifiP2pManager.Channel::class.java, String::class.java, WifiP2pManager.ActionListener::class.java)
            method.invoke(mgr, ch, "HURev", object : WifiP2pManager.ActionListener {
                override fun onSuccess() { AppLog.i("WifiDirectManager: Name set to HURev") }
                override fun onFailure(reason: Int) {}
            })
        } catch (e: Exception) {}

        // 1. Stop any ongoing discovery and remove group to start fresh
        mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { removeGroupAndCreate() }
            override fun onFailure(reason: Int) { removeGroupAndCreate() }
        })
    }

    @SuppressLint("MissingPermission")
    private fun removeGroupAndCreate() {
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { delayedCreateGroup(0) }
            override fun onFailure(reason: Int) { delayedCreateGroup(0) }
        })
    }

    private fun delayedCreateGroup(retryCount: Int) {
        handler.postDelayed({ createNewGroup(retryCount) }, 500L)
    }

    @SuppressLint("MissingPermission")
    private fun createNewGroup(retryCount: Int) {
        val mgr = manager ?: return
        val ch = channel ?: return
        
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("WifiDirectManager: P2P Group created.")
                isGroupOwner = true
                startDiscoveryLoop()
            }
            override fun onFailure(reason: Int) {
                if (reason == 2 && retryCount < 3) { // 2 = BUSY
                    AppLog.w("WifiDirectManager: Chip is BUSY, retrying in 2s...")
                    handler.postDelayed({ createNewGroup(retryCount + 1) }, 2000L)
                } else {
                    AppLog.e("WifiDirectManager: createGroup failed: $reason")
                }
            }
        })
    }

    private fun startDiscoveryLoop() {
        handler.removeCallbacks(discoveryRunnable)
        handler.post(discoveryRunnable)
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val ch = channel
        if (ch != null) {
            manager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { AppLog.d("WifiDirectManager: Discovery active") }
                override fun onFailure(reason: Int) { AppLog.w("WifiDirectManager: Discovery failed: $reason") }
            })
        }
    }

    /**
     * Boomerang Hack: Briefly triggers system WiFi settings to wake up the radio.
     * Currently not used by default but kept in code for future use.
     */
    private fun triggerWifiSettings() {
        try {
            val intent = Intent().apply {
                component = android.content.ComponentName("com.android.settings", "com.android.settings.Settings\$WifiP2pSettingsActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {}
        }

        handler.postDelayed({
            try {
                val intent = Intent(context, com.andrerinas.headunitrevived.main.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                context.startActivity(intent)
            } catch (e: Exception) {}
        }, 800L)
    }

    @SuppressLint("MissingPermission")
    fun startNativeAaQuietHost() {
        var mgr = manager
        var ch = channel

        if (mgr == null || ch == null) {
            AppLog.w("WifiDirectManager: manager ($mgr) or channel ($ch) is null. Attempting re-init...")
            try {
                val newManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                val newChannel = newManager?.initialize(context, context.mainLooper, null)
                if (newManager != null && newChannel != null) {
                    manager = newManager
                    channel = newChannel
                    mgr = newManager
                    ch = newChannel
                    AppLog.i("WifiDirectManager: Re-init successful and fields updated.")
                    registerReceiverIfNeeded()
                } else {
                    AppLog.e("WifiDirectManager: Re-init failed. Cannot start Quiet Host.")
                    return
                }
            } catch (e: Exception) {
                AppLog.e("WifiDirectManager: Exception during re-init", e)
                return
            }
        }

        // Ensure WiFi is enabled (Required for P2P)
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (!wifiManager.isWifiEnabled) {
            AppLog.i("WifiDirectManager: WiFi is disabled but needed for Native AA. Attempting to enable...")
            if (Build.VERSION.SDK_INT < 29) {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = true
            } else {
                Toast.makeText(context, "Native AA requires Wi-Fi. Please turn it on.", Toast.LENGTH_LONG).show()
                // We return for now, the user must turn it on. In the future we could open settings.
                return
            }
            // Wait a bit for WiFi to wake up
            handler.postDelayed({ startNativeAaQuietHost() }, 2000L)
            return
        }

        AppLog.i("WifiDirectManager: startNativeAaQuietHost() requested. Removing old group if any...")
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.d("WifiDirectManager: removeGroup SUCCESS. Creating quiet group...")
                delayedCreateQuietGroup(0)
            }
            override fun onFailure(reason: Int) {
                AppLog.d("WifiDirectManager: removeGroup failed (reason=$reason). This is expected if no group existed. Creating quiet group anyway...")
                delayedCreateQuietGroup(0)
            }
        })
    }

    private fun delayedCreateQuietGroup(retryCount: Int) {
        handler.postDelayed({ createQuietGroup(retryCount) }, 500L)
    }

    @SuppressLint("MissingPermission")
    private fun createQuietGroup(retryCount: Int) {
        val mgr = manager ?: return
        val ch = channel ?: return

        AppLog.i("WifiDirectManager: Attempting createGroup for Native AA (Attempt $retryCount)...")

        // 5GHz Hack: Try to force 5GHz band using reflection
        try {
            val configClass = Class.forName("android.net.wifi.p2p.WifiP2pConfig")
            val config = configClass.newInstance()

            val groupOwnerIntentField = configClass.getDeclaredField("groupOwnerIntent")
            groupOwnerIntentField.isAccessible = true
            groupOwnerIntentField.set(config, 15)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val setGroupOperatingBandMethod = configClass.getMethod("setGroupOperatingBand", Int::class.javaPrimitiveType)
                setGroupOperatingBandMethod.invoke(config, 2) // 2 = 5GHz band
            }

            // The hidden method signature is createGroup(Channel, WifiP2pConfig, ActionListener)
            val createGroupMethod = mgr.javaClass.getMethod("createGroup",
                WifiP2pManager.Channel::class.java,
                configClass,
                WifiP2pManager.ActionListener::class.java)

            createGroupMethod.invoke(mgr, ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    AppLog.i("WifiDirectManager: 5GHz Forced createGroup SUCCESS!")
                    isGroupOwner = true
                    handler.postDelayed({
                        mgr.requestConnectionInfo(ch, this@WifiDirectManager)
                        mgr.requestGroupInfo(ch, this@WifiDirectManager)
                    }, 1000L)
                }
                override fun onFailure(reason: Int) {
                    val reasonStr = getP2pErrorString(reason)
                    AppLog.w("WifiDirectManager: 5GHz Forced createGroup failed ($reasonStr), falling back to standard...")
                    standardCreateGroup(mgr, ch, retryCount)
                }
            })
            return
        } catch (e: Exception) {
            AppLog.w("WifiDirectManager: 5GHz Hack failed: ${e.message}. Using standard createGroup.")
        }

        standardCreateGroup(mgr, ch, retryCount)
    }

    private fun getP2pErrorString(reason: Int): String {
        return when(reason) {
            0 -> "ERROR (Internal Error)"
            1 -> "P2P_UNSUPPORTED"
            2 -> "BUSY (System is busy, retry needed)"
            else -> "UNKNOWN ($reason)"
        }
    }

    @SuppressLint("MissingPermission")
    private fun standardCreateGroup(mgr: WifiP2pManager, ch: WifiP2pManager.Channel, retryCount: Int) {
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("WifiDirectManager: Standard createGroup SUCCESS!")
                isGroupOwner = true
                handler.postDelayed({
                    mgr.requestConnectionInfo(ch, this@WifiDirectManager)
                    mgr.requestGroupInfo(ch, this@WifiDirectManager)
                }, 1000L)
            }
            override fun onFailure(reason: Int) {
                val reasonStr = getP2pErrorString(reason)
                if (reason == 2 && retryCount < 3) {
                    AppLog.w("WifiDirectManager: createGroup failed ($reasonStr), removing group and retrying in 2s...")
                    mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { delayedCreateQuietGroup(retryCount + 1) }
                        override fun onFailure(r: Int) { delayedCreateQuietGroup(retryCount + 1) }
                    })
                } else {
                    AppLog.e("WifiDirectManager: createQuietGroup failed completely! Reason: $reasonStr")
                }
            }
        })
    }

    private fun getMacFromShell(iface: String?): String? {
        // Fallback: If iface is null, try to find a p2p interface name
        val targetIface = iface ?: getInterfaceByIp("192.168.49.1") ?: discoveredInterface
        if (targetIface == null) {
            AppLog.w("WifiDirectManager: getMacFromShell: No interface name available for shell/sysfs lookup.")
            return null
        }
        
        // Try reading directly from sysfs (often allowed even when ip link is not)
        try {
            val file = java.io.File("/sys/class/net/$targetIface/address")
            if (file.exists()) {
                val mac = file.readText().trim().lowercase()
                if (mac.isNotEmpty() && mac != "00:00:00:00:00:00" && mac != "02:00:00:00:00:00") {
                    AppLog.i("WifiDirectManager: MAC retrieved via sysfs ($targetIface): $mac")
                    return mac
                }
            }
        } catch (e: Exception) {
            AppLog.w("WifiDirectManager: Failed to read MAC from sysfs for $targetIface: ${e.message}")
        }

        return try {
            val process = Runtime.getRuntime().exec("ip link show $targetIface")
            val reader = process.inputStream.bufferedReader()
            var line: String?
            var mac: String? = null
            while (reader.readLine().also { line = it } != null) {
                // Look for "link/ether aa:bb:cc:dd:ee:ff"
                val match = Regex("link/ether (([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2})").find(line ?: "")
                if (match != null) {
                    mac = match.groupValues[1]
                    break
                }
            }
            process.waitFor()
            if (mac == "00:00:00:00:00:00" || mac == "02:00:00:00:00:00") null else mac
        } catch (e: Exception) {
            null
        }
    }

    fun stop() {
        AppLog.i("WifiDirectManager: Stopping and cleaning up...")
        handler.removeCallbacks(discoveryRunnable)
        try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        if (isGroupOwner) {
            manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { AppLog.d("WifiDirectManager: Final group removal success") }
                override fun onFailure(reason: Int) { AppLog.d("WifiDirectManager: Final group removal failed: $reason") }
            })
        }
    }
}