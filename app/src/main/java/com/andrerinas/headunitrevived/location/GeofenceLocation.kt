package com.andrerinas.headunitrevived.location

import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A user-defined geo-fenced area (e.g. "Home", "Work").
 *
 * Each area is a circle (center + radius). It can:
 *  - force a day/night appearance while the device's live location is inside it
 *    ([forceNight]), overriding the global night mode; and/or
 *  - gate automation ([gateAutomation]): when enabled, auto-start / auto-connect
 *    is only allowed while inside this area.
 */
data class GeofenceLocation(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    /** true -> force NIGHT inside the area, false -> force DAY inside the area. */
    val forceNight: Boolean = true,
    /** true -> only allow auto-start / auto-connect while inside this area. */
    val gateAutomation: Boolean = false
) {
    /** Distance in meters from this area's center to [location]. */
    fun distanceTo(location: Location): Float {
        val center = Location("").apply {
            latitude = this@GeofenceLocation.latitude
            longitude = this@GeofenceLocation.longitude
        }
        return center.distanceTo(location)
    }

    /** Whether [location] falls within [radiusMeters] of this area's center. */
    fun contains(location: Location): Boolean = distanceTo(location) <= radiusMeters

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("lat", latitude)
        put("lon", longitude)
        put("radius", radiusMeters.toDouble())
        put("forceNight", forceNight)
        put("gate", gateAutomation)
    }

    companion object {
        const val DEFAULT_RADIUS_METERS = 150f

        fun fromJson(o: JSONObject): GeofenceLocation = GeofenceLocation(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", ""),
            latitude = o.optDouble("lat", 0.0),
            longitude = o.optDouble("lon", 0.0),
            radiusMeters = o.optDouble("radius", DEFAULT_RADIUS_METERS.toDouble()).toFloat(),
            forceNight = o.optBoolean("forceNight", true),
            gateAutomation = o.optBoolean("gate", false)
        )

        fun listFromJson(json: String?): List<GeofenceLocation> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { fromJson(it) }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun listToJson(list: List<GeofenceLocation>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}
