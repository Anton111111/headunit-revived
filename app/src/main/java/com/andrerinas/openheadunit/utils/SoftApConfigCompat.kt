package com.andrerinas.openheadunit.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Compatibility helper for Android 11+ (API 30) hotspot configuration using
 * the SoftApConfiguration API via reflection.
 */
object SoftApConfigCompat {
    private const val TAG = "SoftApConfigCompat"

    /**
     * Enables a Wi‑Fi hotspot with a configurable SSID and a default WPA2‑PSK password.
     * Returns true if the hotspot was successfully configured, false otherwise.
     *
     * The caller must ensure that Wi‑Fi is disabled beforehand (handled by HotspotManager).
     */
    fun enableHotspot(context: Context, enabled: Boolean): Boolean {
        if (!enabled) return false // disabling handled by legacy path
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        // Ensure location permission (required for hotspot config on API 30+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasLocation) {
                AppLog.e("SoftApConfigCompat: Missing ACCESS_FINE_LOCATION permission – cannot configure hotspot")
                return false
            }
        }

        // Log entry for debugging
        AppLog.i("SoftApConfigCompat: enableHotspot called (API=${Build.VERSION.SDK_INT})")
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            // Build SoftApConfiguration via reflection
            val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
            val builder = builderClass.getDeclaredConstructor().newInstance()

            // Retrieve SSID from Settings (fallback to default)
            val ssid = Settings(context).autoStartWifiSsid.ifEmpty { "HeadunitHotspot" }
            builderClass.getMethod("setSsid", String::class.java).invoke(builder, ssid)

            // Retrieve password from Settings (fallback to default)
            val password = Settings(context).hotspotPassword.ifEmpty { "12345678" }
            // 0 = WPA2_PSK as per SoftApConfiguration constants
            builderClass.getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType)
                .invoke(builder, password, 1) // 1 = WPA2_PSK

            // Build the configuration object
            val buildMethod = builderClass.getMethod("build")
            val softApConfig = buildMethod.invoke(builder)

            // Apply the configuration via WifiManager#setSoftApConfiguration
            val setConfigMethod = wifiManager.javaClass.getMethod(
                "setSoftApConfiguration",
                Class.forName("android.net.wifi.SoftApConfiguration")
            )
            val result = setConfigMethod.invoke(wifiManager, softApConfig) as Boolean
            AppLog.i("SoftApConfiguration applied (SSID=$ssid). Success=$result")
            result
        } catch (e: Exception) {
            // Log full exception for diagnostics
            AppLog.e("Failed to enable hotspot via SoftApConfiguration", e)
            false
        }
    }
}
