package com.andrerinas.openheadunit.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.NativeHandoffPolicy
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.*
import android.os.Build
import android.os.SystemClock
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.*

/**
 * Manages the official Android Auto Wireless Bluetooth handshake.
 * This class implements the RFCOMM server protocol to exchange WiFi credentials with the phone.
 */
class NativeAaHandshakeManager(
    private val context: AapService,
    private val scope: CoroutineScope
) {
    companion object {
        private val AA_UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
        private val HFP_UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")
        // Phone-wake targets, tried HFP then HSP (mirrors openautolink's ConnectProfile
        // fallback chain). HSP_AG_UUID is the old "A2DP_SOURCE_UUID" - despite that name it was
        // never A2DP Source (real assigned number 0000110a-...); both UUIDs are confirmed
        // against nisargjhaveri/WirelessAndroidAutoDongle and mossyhub/openautolink, which use
        // the same pair for this exact purpose.
        private val HSP_AG_UUID = UUID.fromString("00001112-0000-1000-8000-00805f9b34fb") // Headset Profile AG
        private val HFP_AG_UUID = UUID.fromString("0000111f-0000-1000-8000-00805f9b34fb") // Hands-Free Profile AG
        private const val HANDSHAKE_RESPONSE_TIMEOUT_MS = 15_000L

        /** Which of [allServiceNames] are secondary Bluetooth radios, i.e. not [primaryServiceName]
         *  (dual-Bluetooth-radio head units). Pure and unit-testable: identity is by system
         *  service name, not MAC address, since BluetoothAdapter.getAddress() returns the fixed
         *  placeholder "02:00:00:00:00:00" for any non-privileged app on every device since
         *  Android 6.0 (API 23), so every real adapter instance looks identical by address alone. */
        internal fun filterSecondaryServiceNames(
            primaryServiceName: String,
            allServiceNames: List<String>
        ): List<String> {
            val primary = primaryServiceName.ifEmpty { "bluetooth_manager" }
            return allServiceNames.filter { it != primary }.distinct()
        }

        fun checkCompatibility(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) 
                    != PackageManager.PERMISSION_GRANTED) {
                    AppLog.w("NativeAA: Compatibility Check skipped - Missing BLUETOOTH_CONNECT")
                    return false
                }
            }
            val adapter = BluetoothHelper.getBluetoothAdapter(context) ?: return false
            if (!adapter.isEnabled) return false
            return try {
                val socket = adapter.listenUsingRfcommWithServiceRecord("Compatibility Check", AA_UUID)
                socket.close()
                AppLog.i("NativeAA: Compatibility Check SUCCESS")
                true
            } catch (e: Exception) {
                AppLog.w("NativeAA: Compatibility Check FAILED: ${e.message}")
                false
            }
        }
    }

    private val settings = com.andrerinas.openheadunit.App.provide(context).settings
    private val commManager = com.andrerinas.openheadunit.App.provide(context).commManager
    private var aaServerSocket: BluetoothServerSocket? = null
    private var hfpServerSocket: BluetoothServerSocket? = null
    // Extra RFCOMM listeners opened on secondary Bluetooth radios (dual-Bluetooth head units).
    // Split by UUID so a successful handoff can close just the AA listeners (see
    // closeAaListeners()) without taking down the HFP ones too.
    private val extraAaServerSockets = java.util.Collections.synchronizedList(mutableListOf<BluetoothServerSocket>())
    private val extraHfpServerSockets = java.util.Collections.synchronizedList(mutableListOf<BluetoothServerSocket>())
    private var isRunning = false
    // Set by closeAaListeners() so the AA accept loops can tell "we closed this on purpose
    // after a successful handoff" apart from a real socket error, for logging only.
    @Volatile private var aaListenersClosedForSession = false

    private var currentSsid: String? = null
    private var currentPsk: String? = null
    private var currentIp: String? = null
    private var currentBssid: String? = null
    private var pokeJob: Job? = null
    // Last (ssid, ip, bssid) triggerPoke() restarted for - dedupes redundant restarts when
    // WifiDirectManager redelivers the same credentials, which was starving the poke before it
    // could ever finish.
    private var lastPokeTriggerCredentials: Triple<String, String, String>? = null
    // elapsedRealtime() when handleHandshake() started, or 0 when no exchange is running; lets
    // WifiDirectManager's join watchdog know a real exchange is in progress.
    //
    // [BUG_FIX] #706: a stamp rather than a boolean because handleHandshake() cannot be relied on
    // to clear it. On the reporter's ROCO K706 the socket close that is supposed to unblock the
    // wait for Type 2 does not, so the coroutine never reaches its finally — three failed
    // handshakes produced zero "BT Handshake socket closed." lines and left the old boolean true
    // for the rest of the process. See NativeHandoffPolicy.isHandshaking.
    @Volatile private var handshakeStartedAt = 0L
    // True for the duration of a single pokeDevice() attempt (its socket.connect() call itself
    // can fire an OS-level ACL_CONNECTED broadcast before any real handshake starts) - see
    // isAttemptInFlight().
    @Volatile private var pokeAttemptInFlight = false
    // elapsedRealtime() when the last WifiInfoResponse (Type 3) went out, or 0 when no handoff is
    // settling. The phone spends the next several seconds associating, doing WPS and getting a
    // DHCP lease; see isHandoffSettling() and NativeHandoffPolicy.
    @Volatile private var handoffSettlingSince = 0L
    // The socket of the handshake currently being served. Kept so a phone that gives up and
    // reconnects over Bluetooth during a settle supersedes the stale one instead of running a
    // second handleHandshake() alongside it.
    @Volatile private var activeHandshakeSocket: BluetoothSocket? = null
    // Name of the primary Bluetooth radio we listen and poke on, captured in start(). A field
    // rather than a local so the diagnostic below can name the radio the phone is ignoring.
    @Volatile private var localRadioName: String = "?"
    // [BUG_FIX] #706: how many wake pokes the phone has answered without ever opening the AA
    // channel, and whether it ever has. The pair exists because "poke succeeds, nothing comes
    // back" produced a head unit log with no sign of trouble at all — successful pokes and
    // nothing else — while the phone was in fact reconnecting every 12 s to the head unit's own
    // OEM Bluetooth module, which still advertises the Android Auto service record. See
    // NativeHandoffPolicy.shouldWarnPhoneNeverCallsBack.
    @Volatile private var pokesSinceLastAccept = 0
    @Volatile private var everAcceptedAaConnection = false

    /**
     * Updates the WiFi credentials that will be sent to the phone during the next handshake.
     */
    fun updateWifiCredentials(ssid: String, psk: String, ip: String, bssid: String) {
        AppLog.i("NativeAA: Credentials updated. SSID=$ssid, IP=$ip, BSSID=$bssid")
        currentSsid = ssid
        currentPsk = psk
        currentIp = ip
        currentBssid = bssid
    }

    /** Clears cached credentials so an in-progress wait doesn't hand out stale ones for a group
     *  that's about to be torn down. */
    fun invalidateCredentials() {
        currentSsid = null
        currentPsk = null
        currentIp = null
        currentBssid = null
    }

    // isRunning alone isn't enough once closeAaListeners() can close the AA_UUID listener while
    // leaving the manager otherwise running (HFP stays up) — callers like AutoStartReceiver's
    // BT-reconnect re-arm need to know whether a connection can actually be accepted right now,
    // not just whether the manager was start()ed. See the "Re-arm on Bluetooth reconnect" fix
    // this restores the invariant for: isActive() must mean "genuinely able to accept," not
    // "believed to be running."
    fun isActive(): Boolean = isRunning && !aaListenersClosedForSession

    fun isHandshakeInFlight(): Boolean =
        NativeHandoffPolicy.isHandshaking(handshakeStartedAt, SystemClock.elapsedRealtime())

    /**
     * True between delivering the WiFi credentials (Type 3) and the phone's TCP session actually
     * landing — the window in which it is still associating, doing WPS and getting a DHCP lease.
     *
     * [isHandshakeInFlight] deliberately goes false the instant Type 3 is written, because the
     * *credential exchange* is done at that point. The phone's work isn't: on the #760 reporter's
     * hardware it joined the group 0.73 s after Type 3 and was still without an IP 2.4 s later.
     * Anything that must not disturb the phone mid-join — the wake poke, the BT auto-start
     * re-arm, WifiDirectManager's join watchdog — has to check this, not isHandshakeInFlight().
     */
    fun isHandoffSettling(): Boolean =
        NativeHandoffPolicy.isSettling(handoffSettlingSince, SystemClock.elapsedRealtime())

    // True while either a wake-up poke's socket.connect() or a real handshake is in progress, or
    // a delivered handoff is still settling. AutoStartReceiver's own poke can generate the
    // ACL_CONNECTED broadcast that re-triggers AapService's BT auto-start re-arm; callers
    // deciding whether it's safe to force-reinit should check this instead of isActive() alone.
    fun isAttemptInFlight(): Boolean = isHandshakeInFlight() || pokeAttemptInFlight || isHandoffSettling()

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED) {
                AppLog.e("NativeAA: Missing BLUETOOTH_CONNECT permission. Handshake server cannot start.")
                return
            }
        }

        val adapter = BluetoothHelper.getBluetoothAdapter(context)
        if (adapter == null || !adapter.isEnabled) {
            // Leave isRunning false — isActive() callers (e.g. AapService's BT auto-start
            // re-arm check) need to see this as genuinely stopped so they retry later,
            // instead of believing the listener sockets are up when nothing was ever opened.
            AppLog.e("NativeAA: Bluetooth adapter not available or disabled")
            return
        }

        isRunning = true
        aaListenersClosedForSession = false
        // Local Bluetooth radio name; logged on every accept so a dual-radio head unit's logs
        // show which radio the phone actually reached (compare with the HU name in the phone's
        // log). Uses adapter.name, not adapter.address: getAddress() returns the fixed masked
        // placeholder "02:00:00:00:00:00" for any non-privileged app since Android 6.0 (API 23),
        // but getName() returns the real radio name (confirmed on-device: e.g. "Navegadortz2").
        localRadioName = try { adapter.name ?: "?" } catch (e: Exception) { "?" }
        AppLog.i("NativeAA: Starting Bluetooth Handshake Servers (primary radio [$localRadioName])...")

        // Start AA RFCOMM Server
        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-RfcommServer")) {
            try {
                aaServerSocket = adapter.listenUsingRfcommWithServiceRecord("AA BT Listener", AA_UUID)
                AppLog.i("NativeAA: ACTIVELY LISTENING on Android Auto UUID ($AA_UUID) on radio [$localRadioName]... Waiting for phone to connect back!")
                while (isRunning && isActive) {
                    val socket = aaServerSocket?.accept()
                    if (socket != null) {
                        AppLog.i("NativeAA: Connection accepted from ${socket.remoteDevice.name} (${socket.remoteDevice.address}) on local radio [$localRadioName]")
                        // [FIX] Launch handshake in a separate coroutine so the server can accept the next connection!
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Handshake-${socket.remoteDevice.address}")) {
                            handleHandshake(socket, localRadioName)
                        }
                    }
                }
            } catch (e: Exception) {
                if (aaListenersClosedForSession) {
                    AppLog.d("NativeAA: AA Server socket closed after successful handoff.")
                } else if (isRunning) {
                    AppLog.e("NativeAA: AA Server socket error: ${e.message}", e)
                } else {
                    AppLog.d("NativeAA: AA Server socket closed cleanly.")
                }
            }
        }

        // Start HFP RFCOMM Server (Required by some phones to detect HU)
        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpServer")) {
            try {
                hfpServerSocket = adapter.listenUsingRfcommWithServiceRecord("Hands-Free Unit", HFP_UUID)
                while (isRunning && isActive) {
                    val socket = hfpServerSocket?.accept()
                    if (socket != null) {
                        AppLog.i("NativeAA: HFP connection accepted from ${socket.remoteDevice.name}. Starting responder.")
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpResponder-${socket.remoteDevice.address}")) {
                            handleHfp(socket)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    AppLog.e("NativeAA: HFP Server socket error: ${e.message}", e)
                } else {
                    AppLog.d("NativeAA: HFP Server socket closed cleanly.")
                }
            }
        }

        // Some head units have two Bluetooth radios (e.g. "K706" and "CAR8032"). The phone may
        // be bonded to whichever one isn't the primary, so it never reaches the listener above.
        // Match radios by system service name, not MAC address: BluetoothAdapter.getAddress()
        // returns the fixed placeholder "02:00:00:00:00:00" for any non-privileged app since
        // Android 6.0 (API 23), on every device - primary and secondary always look identical
        // by address alone (see andreknieriem/headunit-revived#706).
        val handles = try {
            BluetoothHelper.getAllBluetoothAdapterHandles(context)
        } catch (e: Exception) { emptyList() }

        // Manual fallback: some ROMs' second radio isn't discoverable via
        // ServiceManager.listServices() at all (blocked, or named without "bluetooth"), so
        // automatic enumeration never finds it. Let the user force it by exact system service
        // name instead.
        val manualServiceName = settings.manualSecondaryBluetoothServiceName
        val allHandles = if (manualServiceName.isNotEmpty() && handles.none { it.serviceName == manualServiceName }) {
            val manualHandle = try { BluetoothHelper.getAdapterHandleForService(context, manualServiceName) } catch (e: Exception) { null }
            if (manualHandle != null) {
                AppLog.i("NativeAA: Manual secondary Bluetooth service '$manualServiceName' resolved successfully.")
                handles + manualHandle
            } else {
                AppLog.w("NativeAA: Manual secondary Bluetooth service '$manualServiceName' could not be resolved to a working adapter.")
                handles
            }
        } else handles

        val secondaryNames = filterSecondaryServiceNames(
            settings.bluetoothManagerServiceName,
            allHandles.map { it.serviceName }
        ).toSet()
        val secondaries = allHandles.filter { it.serviceName in secondaryNames }
        if (secondaries.isNotEmpty()) {
            AppLog.i("NativeAA: Opening AA listeners on ${secondaries.size} secondary Bluetooth radio(s) for dual-radio head units: ${secondaries.joinToString { it.serviceName }}")
            secondaries.forEach { launchExtraServers(it.serviceName, it.adapter) }
        }
    }

    /**
     * Open supplementary AA + HFP RFCOMM listeners on a secondary Bluetooth radio, so a phone
     * bonded to that radio (dual-Bluetooth head units) can still reach us. Experimental, and
     * fully guarded so a bad radio cannot affect the primary listener.
     */
    private fun launchExtraServers(serviceName: String, extra: BluetoothAdapter) {
        // extra.name, not extra.address - see the comment on localRadioName in start(); the
        // address is always the masked placeholder, the name is the real, useful identifier.
        val radioName = try { extra.name ?: "?" } catch (e: Exception) { "?" }
        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-RfcommServer-2")) {
            try {
                val server = extra.listenUsingRfcommWithServiceRecord("AA BT Listener", AA_UUID)
                extraAaServerSockets.add(server)
                AppLog.i("NativeAA: ACTIVELY LISTENING on Android Auto UUID on secondary radio '$serviceName' [$radioName]")
                while (isRunning && isActive) {
                    val socket = server.accept()
                    if (socket != null) {
                        AppLog.i("NativeAA: Connection accepted (secondary radio '$serviceName' [$radioName]) from ${socket.remoteDevice.name} (${socket.remoteDevice.address})")
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Handshake-${socket.remoteDevice.address}")) {
                            handleHandshake(socket, radioName)
                        }
                    }
                }
            } catch (e: Exception) {
                if (aaListenersClosedForSession) AppLog.d("NativeAA: Secondary AA server closed after successful handoff ['$serviceName' $radioName].")
                else if (isRunning) AppLog.e("NativeAA: Secondary AA server error ['$serviceName' $radioName]: ${e.message}", e)
                else AppLog.d("NativeAA: Secondary AA server closed cleanly ['$serviceName' $radioName].")
            }
        }
        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpServer-2")) {
            try {
                val server = extra.listenUsingRfcommWithServiceRecord("Hands-Free Unit", HFP_UUID)
                extraHfpServerSockets.add(server)
                while (isRunning && isActive) {
                    val socket = server.accept()
                    if (socket != null) {
                        AppLog.i("NativeAA: HFP connection accepted (secondary radio '$serviceName') from ${socket.remoteDevice.name}.")
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpResponder-${socket.remoteDevice.address}")) {
                            handleHfp(socket)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) AppLog.e("NativeAA: Secondary HFP server error ['$serviceName' $radioName]: ${e.message}", e)
                else AppLog.d("NativeAA: Secondary HFP server closed cleanly ['$serviceName' $radioName].")
            }
        }
    }

    /**
     * Stop accepting new AA_UUID connections (primary + any secondary radios) after a
     * successful handoff to WiFi. Closing just the client socket isn't enough: the phone reads
     * that as an unexpected drop and immediately retries, and with the listener still up we'd
     * accept, bail out (already connected), and close again — a tight reconnect storm (confirmed
     * on-device: hundreds of accept/close cycles a second, indistinguishable from a Bluetooth
     * pairing loop). HFP listeners are left running. Re-opened the next time start() runs, which
     * AapService already does on disconnect.
     */
    private fun closeAaListeners() {
        aaListenersClosedForSession = true
        try { aaServerSocket?.close() } catch (e: Exception) {}
        synchronized(extraAaServerSockets) {
            extraAaServerSockets.forEach { try { it.close() } catch (e: Exception) {} }
            extraAaServerSockets.clear()
        }
    }

    /**
     * Minimal HFP responder to satisfy phones that require a stable HFP connection
     * during the Android Auto Wireless handshake.
     */
    private suspend fun handleHfp(socket: BluetoothSocket) = withContext(Dispatchers.IO) {
        try {
            val input = socket.inputStream
            val output = socket.outputStream
            val buf = ByteArray(1024)
            
            AppLog.i("NativeAA: HFP responder active for ${socket.remoteDevice.name}")
            
            while (isRunning && isActive && socket.isConnected) {
                if (input.available() > 0) {
                    val read = input.read(buf)
                    if (read == -1) break
                    
                    val cmd = String(buf, 0, read, Charsets.US_ASCII).trim()
                    AppLog.d("NativeAA: HFP RX: $cmd")
                    
                    // Respond to standard HFP initialization commands
                    when {
                        cmd.contains("AT+BRSF") -> {
                            output.write("+BRSF: 20\r\n".toByteArray())
                            output.write("OK\r\n".toByteArray())
                        }
                        cmd.contains("AT+CIND=?") -> {
                            output.write("+CIND: (\"service\",(0,1)),(\"call\",(0,1))\r\n".toByteArray())
                            output.write("OK\r\n".toByteArray())
                        }
                        cmd.contains("AT+CIND?") -> {
                            output.write("+CIND: 1,0\r\n".toByteArray())
                            output.write("OK\r\n".toByteArray())
                        }
                        else -> {
                            output.write("OK\r\n".toByteArray())
                        }
                    }
                    output.flush()
                }
                delay(200)
            }
        } catch (e: Exception) {
            AppLog.d("NativeAA: HFP responder error: ${e.message}")
        } finally {
            try { socket.close() } catch (e: Exception) {}
            AppLog.i("NativeAA: HFP socket for ${socket.remoteDevice.address} closed.")
        }
    }

    /**
     * Tries HFP_AG_UUID first, falling back to HSP_AG_UUID, holding whichever connects for
     * [holdMs]. Returns true if either connected. Mirrors openautolink's ConnectProfile
     * fallback chain (HFP_AG_UUID -> HSP_AG_UUID).
     */
    private suspend fun pokeDevice(device: BluetoothDevice, holdMs: Long): Boolean {
        pokeAttemptInFlight = true
        try {
            for (uuid in listOf(HFP_AG_UUID, HSP_AG_UUID)) {
                var socket: BluetoothSocket? = null
                try {
                    socket = device.createRfcommSocketToServiceRecord(uuid)
                    AppLog.i("NativeAA: Calling socket.connect() for ${device.name} via $uuid...")
                    socket.connect()
                    AppLog.i("NativeAA: Successfully poked ${device.name} via $uuid. Holding ${holdMs}ms...")
                    // Counted before the hold, so a poke that is cancelled mid-hold still counts:
                    // the phone answered, which is the whole point of the count.
                    pokesSinceLastAccept++
                    delay(holdMs)
                    return true
                } catch (e: CancellationException) {
                    // Rethrow instead of falling through to the next UUID: a cancelled poke (e.g.
                    // handleHandshake()'s pokeJob?.cancel() once a real handshake lands) must stop
                    // immediately, not fire another real, blocking socket.connect() on the same
                    // physical radio right as the critical WifiStartRequest send is about to happen.
                    throw e
                } catch (e: Exception) {
                    AppLog.d("NativeAA: Poke via $uuid to ${device.name} failed: ${e.message}")
                } finally {
                    try { socket?.close() } catch (e: Exception) {}
                }
            }
            return false
        } finally {
            pokeAttemptInFlight = false
        }
    }

    /**
     * Wakes up the phone by attempting a brief connection to an HFP/HSP profile, signaling it
     * to start looking for the head unit. Retried every 15s (matching the retry cadence of both
     * nisargjhaveri/WirelessAndroidAutoDongle and mossyhub/openautolink) until a real handshake
     * starts or another session (USB/etc.) takes over, instead of giving up after a single pass.
     *
     * Never runs while a handoff is settling: AapService re-invokes this on every credential
     * re-delivery, and the phone *joining our group* is itself a P2P connection change, hence a
     * re-delivery. That turned into a real RFCOMM connect() landing in the middle of the phone's
     * DHCP exchange (#760).
     */
    fun triggerPoke() {
        if (isHandoffSettling()) {
            // Info, not debug: this line is the evidence that the #760 fix is doing its job, and
            // reporter logs default to INFO.
            AppLog.i("NativeAA: Handoff still settling — not starting a poke that would compete with the phone's WiFi association.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                AppLog.w("NativeAA: Missing BLUETOOTH_CONNECT. Cannot triggerPoke.")
                return
            }
        }
        val adapter = BluetoothHelper.getBluetoothAdapter(context) ?: return

        val credentials = Triple(currentSsid ?: "", currentIp ?: "", currentBssid ?: "")
        if (pokeJob?.isActive == true && credentials == lastPokeTriggerCredentials) {
            AppLog.d("NativeAA: triggerPoke() called again with unchanged credentials while a poke is already running - not restarting it.")
            return
        }
        lastPokeTriggerCredentials = credentials

        pokeJob?.cancel()
        pokeJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Wakeup")) {
            AppLog.d("NativeAA: triggerPoke() delay starting (2s)...")
            delay(2000) // Small safety delay before connecting

            while (isRunning && isActive) {
                val settling = isHandoffSettling()
                val handshaking = isHandshakeInFlight()
                val sessionUp = commManager.isConnected ||
                    commManager.connectionState.value is CommManager.ConnectionState.Connecting
                if (!NativeHandoffPolicy.shouldPoke(settling, handshaking, sessionUp)) {
                    AppLog.i(
                        "NativeAA: Stopping poke retry loop " +
                            "(settling=$settling, handshake=$handshaking, session=$sessionUp)."
                    )
                    break
                }

                val lastMacs = settings.autoStartBluetoothDeviceMacs
                val devicesToPoke = if (lastMacs.isNotEmpty()) {
                    lastMacs.mapNotNull { mac ->
                        try {
                            adapter.getRemoteDevice(mac)
                        } catch (e: Exception) {
                            null
                        }
                    }
                } else {
                    AppLog.w("NativeAA: No 'Auto Start BT Device' selected in settings. Poking all paired devices as fallback...")
                    adapter.bondedDevices.toList()
                }

                if (devicesToPoke.isEmpty()) {
                    AppLog.w("NativeAA: No paired Bluetooth devices found to poke.")
                    return@launch
                }

                for (device in devicesToPoke) {
                    if (!isRunning || !isActive || isHandshakeInFlight() || isHandoffSettling()) break
                    if (commManager.isConnected) {
                        AppLog.i("NativeAA: USB/other session became active mid-poke. Stopping poke loop.")
                        break
                    }
                    AppLog.i("NativeAA: Attempting active poke to device: ${device.name} (${device.address})...")
                    pokeDevice(device, holdMs = 15000)
                }

                // [BUG_FIX] #706: say out loud that the phone is answering but never calling back.
                // Without this the log of a head unit in that state is indistinguishable from a
                // healthy one waiting for the user — successful pokes and nothing else — which is
                // what let the reporter's real cause (his phone's Android Auto was bound to the
                // head unit's own OEM Bluetooth module, still advertising the AA service record
                // after its OEM app stopped answering) go unnoticed for weeks. Warning, not info,
                // so it survives a reporter log exported at the default level.
                if (NativeHandoffPolicy.shouldWarnPhoneNeverCallsBack(
                        pokesSinceLastAccept, everAcceptedAaConnection
                    )
                ) {
                    AppLog.w(
                        "NativeAA: The phone has answered $pokesSinceLastAccept wake pokes but has " +
                            "never opened the Android Auto channel on radio [$localRadioName]. Its " +
                            "Android Auto is most likely bound to a different Bluetooth device that " +
                            "also advertises the Android Auto service — typically this head unit's " +
                            "own OEM/factory Bluetooth module (a second name alongside this one in " +
                            "the phone's paired list), or another car. Remove that device from the " +
                            "phone's Bluetooth paired list and retry. If this phone has never " +
                            "projected wirelessly to any head unit, check that it supports wireless " +
                            "Android Auto first."
                    )
                }

                delay(15000) // retry cadence, matches both reference implementations' 15-20s interval
            }
        }
    }

    /**
     * Start a manual poke (wakeup) for a specific Bluetooth device.
     */
    fun manualPoke(address: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED) {
                AppLog.w("NativeAA: Missing BLUETOOTH_CONNECT. Cannot manualPoke.")
                return
            }
        }
        val adapter = BluetoothHelper.getBluetoothAdapter(context) ?: return
        try {
            val device = adapter.getRemoteDevice(address)
            AppLog.i("NativeAA: Manual poke requested for ${device.name} ($address)")
            
            pokeJob?.cancel()
            pokeJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-ManualWakeup")) {
                AppLog.i("NativeAA: Attempting manual poke to ${device.name}...")
                pokeDevice(device, holdMs = 20000)
                AppLog.i("NativeAA: Manual poke to ${device.name} finished.")
            }
        } catch (e: Exception) {
            AppLog.e("NativeAA: Manual poke error", e)
        }
    }

    private suspend fun handleHandshake(socket: BluetoothSocket, localRadio: String? = null) = withContext(Dispatchers.IO) {
        // The phone reached us. Recorded here rather than at either accept site so both the
        // primary and the secondary-radio loops are covered by one statement.
        everAcceptedAaConnection = true
        pokesSinceLastAccept = 0

        // The wake poke is deliberately left running here. It used to be cancelled on entry, on
        // the reasoning that a real AA_UUID connection means the poke has done its job and is now
        // just competing for radio time — but cancelling it closes the HFP/HSP socket, and #706's
        // phone-side Gearhead log shows the phone reacting to that within milliseconds:
        //   GH.BtConnectionTracker: profile connection removed
        //   GH.CurrentCarTracker:   current car bluetooth connection is lost / is gone
        //   ...WIRELESS_SETUP_CAR_BLUETOOTH_DISAPPEAR
        // and then, when its own first-message timer expires 12 s later, refusing to retry:
        //   GH.WIRELESS.SETUP: WiFi Projection Protocol cannot start as HU is not present.
        // Real head units hold the profile link across the exchange, so hold it too, until the
        // credentials are actually delivered (see the Type 3 branch) or this handshake ends.

        // The listener stays open across the settling window, so the phone can reconnect over
        // Bluetooth while an earlier handoff is still settling. That reconnect means the earlier
        // one failed: retire it rather than serving both from the same manager state.
        activeHandshakeSocket?.let { previous ->
            if (previous !== socket) {
                AppLog.i("NativeAA: A new handshake arrived while one was still settling — closing the previous session.")
                handoffSettlingSince = 0L
                try { previous.close() } catch (_: Exception) {}
            }
        }
        activeHandshakeSocket = socket
        // Stamped after claiming ownership above, so a superseded handshake's cleanup — which
        // only fires when it still owns activeHandshakeSocket — can't wipe this one's stamp.
        handshakeStartedAt = SystemClock.elapsedRealtime()
        try {
            val device = socket.remoteDevice
            AppLog.i("NativeAA: Handling handshake for ${device.name} (${device.address}) on local radio [${localRadio ?: "?"}]")

            if (commManager.isConnected ||
                commManager.connectionState.value is CommManager.ConnectionState.Connecting) {
                AppLog.i("NativeAA: USB/other session already active. Aborting BT handshake so phone does not start a parallel wireless attempt.")
                try { socket.close() } catch (_: Exception) {}
                return@withContext
            }

            val macs = settings.autoStartBluetoothDeviceMacs
            if (!macs.contains(device.address)) {
                AppLog.i("NativeAA: Saving ${device.address} (${device.name}) to the list of auto-start devices.")
                val newMacs = macs + device.address
                settings.autoStartBluetoothDeviceMacs = newMacs
                settings.autoStartBluetoothDeviceName = device.name ?: "Unknown Device"
                com.andrerinas.openheadunit.utils.Settings.syncAutoStartBtMacsToDeviceStorage(context, newMacs)
            }

            val input = DataInputStream(socket.inputStream)
            val output = socket.outputStream

            AppLog.i("NativeAA: Phone connected. Current credentials state: SSID=${currentSsid ?: "<null>"}, IP=${currentIp ?: "<null>"}")
            AppLog.i("NativeAA: Waiting for WiFi credentials to be ready (Max 60s)...")
            
            // Wait up to 60 seconds for credentials (P2P group creation can be slow)
            var attempts = 0
            while ((currentSsid == null || currentIp == null) && attempts < 120 && isRunning && isActive) {
                if (attempts % 20 == 0 && attempts > 0) {
                    AppLog.w("NativeAA: Still waiting for credentials after ${attempts / 2}s. Requesting P2P refresh...")
                    context.triggerWifiDirectRefresh()
                } else if (attempts % 10 == 0 && attempts > 0) {
                    AppLog.d("NativeAA: Still waiting... SSID=${currentSsid != null}, IP=${currentIp != null} (Attempt $attempts/120)")
                }
                delay(500)
                attempts++
            }

            if (currentSsid == null || currentIp == null) {
                AppLog.e("NativeAA: Handshake failed - No WiFi credentials available after 60s wait. Missing: ${if(currentSsid == null) "SSID " else ""}${if(currentIp == null) "IP" else ""}")
                return@withContext
            }

            val ip = currentIp!!
            val ssid = currentSsid!!
            val psk = currentPsk ?: ""
            var bssid = currentBssid ?: ""

            // [FIX] Ensure BSSID is uppercase and not zeroed if possible
            bssid = bssid.uppercase()
            if (bssid.isEmpty() || bssid == "00:00:00:00:00:00" || bssid == "02:00:00:00:00:00") {
                AppLog.e("NativeAA: BSSID is still masked/empty ($bssid) at Type 3 time — phone WILL reject these credentials. Aborting handshake. PLEASE CHECK IF LOCATION (GPS) IS ENABLED ON THIS DEVICE!")
                // Triggering a P2P refresh so the next attempt has a valid BSSID
                context.triggerWifiDirectRefresh()
                return@withContext
            }

            AppLog.i("NativeAA: Starting Handshake Exchange:")
            AppLog.i("  > Target SSID: $ssid")
            AppLog.i("  > Target IP:   $ip:5288")
            AppLog.i("  > BSSID:       $bssid")

            // Some Bluetooth stacks report the RFCOMM socket "connected" slightly before the
            // underlying channel is actually ready to carry data - writing immediately can be
            // silently dropped on such hardware (a known real class of RFCOMM race: see the
            // kernel's "Move pending packets from RFCOMM socket to TTY" fix for the same
            // symptom on the HFP profile). A short delay costs nothing against either side's
            // timeout budget (phone's own first-message timeout is ~12s, ours is 15s) but gives
            // a flaky chip a moment to settle before the one message that matters most.
            delay(300)

            AppLog.i("NativeAA: [TX] Sending WifiStartRequest (Type 1)")
            sendWifiStartRequest(output, ip, 5288)

            AppLog.i("NativeAA: Waiting for response from phone...")
            // There is no BluetoothSocket.setSoTimeout(), so the read has to be bounded from the
            // outside. This used to be a watchdog that closed the socket to unblock readFully().
            //
            // [BUG_FIX] #706: that only works on stacks where close() actually interrupts a
            // pending read, and the reporter's does not — his log has three "No response from
            // phone" lines and not one "BT Handshake socket closed.", i.e. this function never
            // unwound. Everything downstream of it stalled with it: the wake poke stopped
            // retrying and the P2P join watchdog deferred recovery for the rest of the session.
            // Time out the wait instead of the socket, by running the blocking read in its own
            // coroutine and awaiting it — that resumes on schedule whether or not the read ever
            // returns. The close is still attempted, for the stacks where it does help.
            val reader = scope.async(Dispatchers.IO) { readProtobuf(input) }
            val response = withTimeoutOrNull(HANDSHAKE_RESPONSE_TIMEOUT_MS) { reader.await() }
            if (response == null) {
                AppLog.e("NativeAA: Handshake failed - No response from phone ${device.name} (${device.address}) on radio [${localRadio ?: "?"}] within ${HANDSHAKE_RESPONSE_TIMEOUT_MS / 1000}s of sending WifiStartRequest. Closing socket.")
                try { socket.close() } catch (e: Exception) {}
                reader.cancel()
                return@withContext
            }
            AppLog.i("NativeAA: [RX] Received Type ${response.type} (Payload size: ${response.payload.size})")

            if (response.type == 2) {
                AppLog.i("NativeAA: Phone ready for WiFi association. Delivering credentials...")
                AppLog.i("NativeAA: [TX] Sending WifiInfoResponse (Type 3) with full credentials in 1000ms...")
                delay(1000) // [FIX] Increased delay to give phone more processing time
                sendWifiSecurityResponse(output, ssid, psk, bssid)
                AppLog.i("NativeAA: Handshake completed successfully on Bluetooth side.")
                // The credential exchange is done, but the phone's work has only just started —
                // it now has to associate, run WPS and get a DHCP lease. See isHandoffSettling().
                handshakeStartedAt = 0L
                handoffSettlingSince = SystemClock.elapsedRealtime()
                // The phone has what it needs, so the wake poke held open since before this
                // handshake has nothing left to wake. Release it now — it is an RFCOMM channel on
                // the same physical radio the phone is about to associate over, and #760 is what
                // happens when Bluetooth work overlaps the join.
                pokeJob?.cancel()

                // Release the Bluetooth connection once the handoff has actually happened, rather
                // than holding it indefinitely. The real Android Auto protocol closes Bluetooth
                // after the WiFi credential exchange — confirmed via a reference wireless-dongle
                // implementation (nisargjhaveri/WirelessAndroidAutoDongle#17/#18), where holding
                // it open caused the same "confusion, especially with phone calls" symptom this
                // repo has seen reported.
                //
                // [BUG_FIX] #760: this used to be a flat delay(3000). That is a race, not a grace
                // period — the phone needs however long it needs. In the reporter's logs the
                // phone joined the group 0.73s after Type 3 and then left it 0.17s after this
                // close fired at T+3.0s, never having got an IP; on 3.1.1, which never closed
                // Bluetooth at all, the same phone took 21s to associate and connected fine; on
                // 3.2.0-beta4 the TCP session landed 110ms before the close and it worked. Wait
                // for the session instead of guessing at it.
                val landed = awaitWifiHandoff()
                handoffSettlingSince = 0L
                if (landed) {
                    AppLog.i("NativeAA: WiFi session landed. Handshake session ending, releasing Bluetooth connection.")
                    // Stop accepting new AA_UUID connections too, not just this socket —
                    // otherwise the phone's immediate reconnect-retry gets accepted, bounced
                    // (already connected), and retried again in a tight loop. See
                    // closeAaListeners() kdoc.
                    closeAaListeners()
                } else {
                    // The handoff never completed, so there is no session for a reconnect to
                    // collide with — the reconnect storm closeAaListeners() guards against can't
                    // happen here. Leave the listener up so the phone's own retry can be
                    // accepted (start() early-returns while isRunning, so a close here would
                    // strand us until AapService stopped and restarted the manager), and restart
                    // the poke, which was cancelled once the credentials went out.
                    AppLog.w("NativeAA: No WiFi session within ${NativeHandoffPolicy.SETTLE_TIMEOUT_MS / 1000}s of delivering credentials — keeping the AA listener open and resuming the wake poke.")
                    triggerPoke()
                }
            } else {
                AppLog.w("NativeAA: Handshake failed - Unexpected response type ${response.type}. Expected Type 2.")
            }

        } catch (e: Exception) {
            AppLog.e("NativeAA: Handshake error: ${e.message}", e)
        } finally {
            // Only clear the stamps if this handshake still owns them — a superseding handshake
            // has already taken over and set its own.
            if (activeHandshakeSocket === socket) {
                activeHandshakeSocket = null
                handshakeStartedAt = 0L
                handoffSettlingSince = 0L
            }
            try { socket.close() } catch (e: Exception) {}
            AppLog.i("NativeAA: BT Handshake socket closed.")
        }
    }

    /**
     * Suspends until the phone's projection session actually reaches us over WiFi, or
     * [NativeHandoffPolicy.SETTLE_TIMEOUT_MS] elapses. Returns true if it landed.
     *
     * [CommManager.isConnected] covers Connected/StartingTransport, and the Connecting state
     * covers the moment WirelessServer hands an accepted socket over — either is proof the phone
     * finished associating, which is the only thing we were waiting for.
     */
    private suspend fun awaitWifiHandoff(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + NativeHandoffPolicy.SETTLE_TIMEOUT_MS
        while (isRunning && SystemClock.elapsedRealtime() < deadline) {
            if (commManager.isConnected ||
                commManager.connectionState.value is CommManager.ConnectionState.Connecting) {
                return true
            }
            delay(250)
        }
        return commManager.isConnected ||
            commManager.connectionState.value is CommManager.ConnectionState.Connecting
    }

    private fun sendWifiStartRequest(output: OutputStream, ip: String, port: Int) {
        val request = Wireless.WifiStartRequest.newBuilder()
            .setIpAddress(ip)
            .setPort(port)
            .setStatus(0)
            .build()
        sendProtobuf(output, request.toByteArray(), 1)
    }

    private fun sendWifiSecurityResponse(output: OutputStream, ssid: String, key: String, bssid: String) {
        val response = Wireless.WifiInfoResponse.newBuilder()
            .setSsid(ssid)
            .setKey(key)
            .setBssid(bssid)
            .setSecurityMode(Wireless.SecurityMode.WPA2_PERSONAL)
            .setAccessPointType(Wireless.AccessPointType.STATIC)
            .build()
        sendProtobuf(output, response.toByteArray(), 3)
    }

    private fun sendProtobuf(output: OutputStream, data: ByteArray, type: Short) {
        val buffer = ByteBuffer.allocate(data.size + 4)
        buffer.put((data.size shr 8).toByte())
        buffer.put((data.size and 0xFF).toByte())
        buffer.putShort(type)
        buffer.put(data)
        output.write(buffer.array())
        output.flush()
        AppLog.i("NativeAA: Successfully delivered Protobuf TYPE $type (size ${data.size}) over Bluetooth!")
    }

    private fun readProtobuf(input: DataInputStream): ProtobufMessage {
        val header = ByteArray(4)
        input.readFully(header)
        val size = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        val type = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val payload = if (size > 0) {
            val p = ByteArray(size)
            input.readFully(p)
            p
        } else ByteArray(0)
        return ProtobufMessage(type, payload)
    }

    data class ProtobufMessage(val type: Int, val payload: ByteArray)

    fun stop() {
        isRunning = false
        try { aaServerSocket?.close() } catch (e: Exception) {}
        try { hfpServerSocket?.close() } catch (e: Exception) {}
        synchronized(extraAaServerSockets) {
            extraAaServerSockets.forEach { try { it.close() } catch (e: Exception) {} }
            extraAaServerSockets.clear()
        }
        synchronized(extraHfpServerSockets) {
            extraHfpServerSockets.forEach { try { it.close() } catch (e: Exception) {} }
            extraHfpServerSockets.clear()
        }
        aaServerSocket = null
        hfpServerSocket = null
        currentSsid = null
        currentIp = null
        currentPsk = null
        currentBssid = null
        pokeJob?.cancel()
        pokeJob = null
        lastPokeTriggerCredentials = null
        // Neither a handshake nor a settle can outlive the manager: leaving these set would keep
        // isAttemptInFlight() true across a restart, blocking the very poke the next start()
        // needs.
        handshakeStartedAt = 0L
        handoffSettlingSince = 0L
        activeHandshakeSocket = null
        // Only the per-attempt count resets: everAcceptedAaConnection is deliberately kept, so a
        // unit that has connected before is not warned just because the manager was re-armed.
        pokesSinceLastAccept = 0
    }
}
