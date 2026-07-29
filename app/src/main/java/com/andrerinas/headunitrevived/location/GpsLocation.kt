package com.andrerinas.headunitrevived.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.PermissionChecker
import com.andrerinas.headunitrevived.contract.LocationUpdateIntent
import com.andrerinas.headunitrevived.utils.AppLog

class GpsLocation constructor(private val context: Context): LocationListener {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var requested: Boolean = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (requested) {
            return
        }
        AppLog.i("Request location updates")
        if (PermissionChecker.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PermissionChecker.PERMISSION_GRANTED
                && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 400, 5.0f, this)
            val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            AppLog.i("Last known location:  ${location?.toString() ?: "Unknown"}")
            requested = true
        }
    }

    override fun onLocationChanged(location: Location) {
        LocationHolder.update(location)
        context.sendBroadcast(LocationUpdateIntent(location).setPackage(context.packageName))
    }

    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
        AppLog.i("$provider: $status")
    }

    override fun onProviderEnabled(provider: String) {
        AppLog.i(provider)
    }

    override fun onProviderDisabled(provider: String) {
        AppLog.i(provider)
    }

    fun stop() {
        AppLog.i("Remove location updates")
        requested = false
        locationManager.removeUpdates(this)
    }
}
