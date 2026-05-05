package com.andrerinas.headunitrevived.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.andrerinas.headunitrevived.aap.protocol.proto.Wireless
import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.*
import android.os.Build
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
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private val AA_UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
        private val HFP_UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")
        private val A2DP_SOURCE_UUID = UUID.fromString("00001112-0000-1000-8000-00805f9b34fb")

        fun checkCompatibility(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) 
                    != PackageManager.PERMISSION_GRANTED) {
                    AppLog.w("NativeAA: Compatibility Check skipped - Missing BLUETOOTH_CONNECT")
                    return false
                }
            }
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
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

    private var aaServerSocket: BluetoothServerSocket? = null
    private var hfpServerSocket: BluetoothServerSocket? = null
    private var isRunning = false

    private var currentSsid: String? = null
    private var currentPsk: String? = null
    private var currentIp: String? = null
    private var currentBssid: String? = null
    private var pokeJob: Job? = null

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

        isRunning = true
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            AppLog.e("NativeAA: Bluetooth adapter not available or disabled")
            return
        }

        AppLog.i("NativeAA: Starting Bluetooth Handshake Servers...")

        // Start AA RFCOMM Server
        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-RfcommServer")) {
            try {
                aaServerSocket = adapter.listenUsingRfcommWithServiceRecord("AA BT Listener", AA_UUID)
                AppLog.i("NativeAA: ACTIVELY LISTENING on Android Auto UUID ($AA_UUID)... Waiting for phone to connect back!")
                while (isRunning && isActive) {
                    val socket = aaServerSocket?.accept()
                    if (socket != null) {
                        AppLog.i("NativeAA: Connection accepted from ${socket.remoteDevice.name}")
                        // [FIX] Launch handshake in a separate coroutine so the server can accept the next connection!
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Handshake-${socket.remoteDevice.address}")) {
                            handleHandshake(socket)
                        }
                    }
                }
            } catch (e: IOException) {
                if (isRunning) AppLog.d("NativeAA: AA Server socket closed: ${e.message}")
            }
        }

        // Start HFP RFCOMM Server (Required by some phones to detect HU)
        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpServer")) {
            try {
                hfpServerSocket = adapter.listenUsingRfcommWithServiceRecord("Hands-Free Unit", HFP_UUID)
                while (isRunning && isActive) {
                    val socket = hfpServerSocket?.accept()
                    if (socket != null) {
                        // Just consume and close, HFP is only a "presence" signal for us
                        scope.launch(Dispatchers.IO) {
                            try {
                                val buf = ByteArray(1024)
                                socket.inputStream.read(buf)
                            } catch (e: Exception) {}
                            finally { try { socket.close() } catch (e: Exception) {} }
                        }
                    }
                }
            } catch (e: IOException) {
                if (isRunning) AppLog.d("NativeAA: HFP Server socket closed: ${e.message}")
            }
        }

    }

    /**
     * Wakes up the phone by attempting a brief connection to the A2DP profile.
     * This acts as a signal for the phone to start looking for the headunit.
     */
    fun triggerPoke() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED) {
                AppLog.w("NativeAA: Missing BLUETOOTH_CONNECT. Cannot triggerPoke.")
                return
            }
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val settings = com.andrerinas.headunitrevived.App.provide(context).settings
        val lastMac = settings.autoStartBluetoothDeviceMac

        pokeJob?.cancel()
        pokeJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Wakeup")) {
            AppLog.d("NativeAA: triggerPoke() delay starting (2s)...")
            delay(2000) // Small safety delay before connecting

            val devicesToPoke = if (lastMac.isNotEmpty()) {
                listOf(adapter.getRemoteDevice(lastMac))
            } else {
                AppLog.w("NativeAA: No 'Auto Start BT Device' selected in settings. Poking all paired devices as fallback...")
                adapter.bondedDevices.toList()
            }

            if (devicesToPoke.isEmpty()) {
                AppLog.w("NativeAA: No paired Bluetooth devices found to poke.")
                return@launch
            }

            for (device in devicesToPoke) {
                if (!isRunning || !isActive) break
                AppLog.i("NativeAA: Attempting active A2DP poke to device: ${device.name} (${device.address})...")
                try {
                    val socket = device.createRfcommSocketToServiceRecord(A2DP_SOURCE_UUID)
                    AppLog.i("NativeAA: Calling socket.connect() for ${device.name}...")
                    socket.connect()
                    AppLog.i("NativeAA: Successfully poked ${device.name}. Keeping socket alive for 15s...")
                    delay(15000)
                    socket.close()
                    AppLog.i("NativeAA: Poke socket for ${device.name} closed.")
                } catch (e: Exception) {
                    AppLog.d("NativeAA: Poke for ${device.name} failed (normal if device disconnected): ${e.message}")
                }
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
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return
        try {
            val device = adapter.getRemoteDevice(address)
            AppLog.i("NativeAA: Manual poke requested for ${device.name} ($address)")
            
            pokeJob?.cancel()
            pokeJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-ManualWakeup")) {
                AppLog.i("NativeAA: Attempting manual A2DP poke to ${device.name}...")
                try {
                    val socket = device.createRfcommSocketToServiceRecord(A2DP_SOURCE_UUID)
                    AppLog.i("NativeAA: Calling socket.connect() for ${device.name}...")
                    socket.connect()
                    AppLog.i("NativeAA: Successfully poked ${device.name}. Keeping socket alive for 20s...")
                    delay(20000)
                    socket.close()
                    AppLog.i("NativeAA: Manual poke socket for ${device.name} closed.")
                } catch (e: Exception) {
                    AppLog.d("NativeAA: Manual poke for ${device.name} failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            AppLog.e("NativeAA: Manual poke error", e)
        }
    }

    private suspend fun handleHandshake(socket: BluetoothSocket) = withContext(Dispatchers.IO) {
        try {
            val device = socket.remoteDevice
            AppLog.i("NativeAA: Handling handshake for ${device.name} (${device.address})")
            
            // Auto-save this device as the last successful one for future pokes
            val settings = com.andrerinas.headunitrevived.App.provide(context).settings
            if (settings.autoStartBluetoothDeviceMac != device.address) {
                AppLog.i("NativeAA: Saving ${device.address} (${device.name}) as the new default auto-start device.")
                settings.autoStartBluetoothDeviceMac = device.address
                settings.autoStartBluetoothDeviceName = device.name ?: "Unknown Device"
                com.andrerinas.headunitrevived.utils.Settings.syncAutoStartBtMacToDeviceStorage(context, device.address)
            }

            val input = DataInputStream(socket.inputStream)
            val output = socket.outputStream

            AppLog.i("NativeAA: Phone connected. Current credentials state: SSID=${currentSsid ?: "<null>"}, IP=${currentIp ?: "<null>"}")
            AppLog.i("NativeAA: Waiting for WiFi credentials to be ready (Max 60s)...")
            
            // Wait up to 60 seconds for credentials (P2P group creation can be slow)
            var attempts = 0
            while ((currentSsid == null || currentIp == null) && attempts < 120 && isRunning && isActive) {
                if (attempts % 10 == 0 && attempts > 0) {
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
            val bssid = currentBssid ?: ""

            AppLog.i("NativeAA: Initializing Handshake Sequence...")
            AppLog.i("  - Group SSID: $ssid")
            AppLog.i("  - Group IP: $ip")
            AppLog.i("  - Group BSSID: $bssid")
            AppLog.i("  - Group PSK: ${if (psk.isNotEmpty()) "****" else "<empty>"}")

            AppLog.i("NativeAA: Sending WifiStartRequest (Type 1) to $ip:5288")
            sendWifiStartRequest(output, ip, 5288)

            AppLog.i("NativeAA: Waiting for response from phone...")
            val response = readProtobuf(input)
            AppLog.i("NativeAA: Received response Type ${response.type} from phone (size: ${response.payload.size})")

            if (response.type == 2) {
                AppLog.i("NativeAA: Phone requested security info (Ready for WiFi association).")
                AppLog.i("NativeAA: Sending WifiInfoResponse (Type 3) with full credentials in 500ms...")
                delay(500)
                sendWifiSecurityResponse(output, ssid, psk, bssid)
                AppLog.i("NativeAA: Handshake completed successfully on Bluetooth side.")
                // Instead of closing after 20 seconds, keep the socket open indefinitely
                // as long as the phone remains connected.
                while (isRunning && isActive && socket.isConnected) {
                    delay(2000)
                }
                AppLog.i("NativeAA: Handshake coroutine finishing (isRunning=$isRunning, isConnected=${socket.isConnected})")
            } else {
                AppLog.w("NativeAA: Unexpected response type from phone: ${response.type}. Expected Type 2.")
            }

        } catch (e: Exception) {
            AppLog.e("NativeAA: Handshake error: ${e.message}", e)
        } finally {
            try { socket.close() } catch (e: Exception) {}
            AppLog.i("NativeAA: BT Handshake socket closed.")
        }
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
        aaServerSocket = null
        hfpServerSocket = null
        currentSsid = null
        currentIp = null
        currentPsk = null
        currentBssid = null
        pokeJob?.cancel()
        pokeJob = null
    }
}
