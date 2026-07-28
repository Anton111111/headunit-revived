package com.andrerinas.headunitrevived.location

import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A user-defined geo-fenced area (e.g. "Home", "Work").
 *
 * Each area is a circle (center + radius) with two independent capabilities:
 *  - [overrideTheme]: while inside, force a day/night appearance ([forceNight]) for the
 *    systems selected by [scope] (the app UI, Android Auto, or both), overriding the
 *    normal mode; and/or
 *  - [gateAutomation]: while enabled, auto-start / auto-connect is only allowed inside
 *    this area.
 *
 * An area with neither capability enabled does nothing and is not offered for saving.
 */
data class GeofenceLocation(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    /** Whether this area forces a day/night appearance while inside. */
    val overrideTheme: Boolean = true,
    /** true -> force NIGHT inside, false -> force DAY inside. Only used if [overrideTheme]. */
    val forceNight: Boolean = true,
    /** Which theming systems the override applies to. Only used if [overrideTheme]. */
    val scope: Scope = Scope.BOTH,
    /** true -> only allow auto-start / auto-connect while inside this area. */
    val gateAutomation: Boolean = false
) {
    enum class Scope {
        APP, ANDROID_AUTO, BOTH;

        fun coversApp() = this == APP || this == BOTH
        fun coversAndroidAuto() = this == ANDROID_AUTO || this == BOTH

        companion object {
            fun fromName(name: String?): Scope =
                values().firstOrNull { it.name == name } ?: BOTH
        }
    }

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
        put("overrideTheme", overrideTheme)
        put("forceNight", forceNight)
        put("scope", scope.name)
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
            // Tolerant migration: entries written before this field default to overriding both.
            overrideTheme = o.optBoolean("overrideTheme", true),
            forceNight = o.optBoolean("forceNight", true),
            scope = Scope.fromName(o.optString("scope", Scope.BOTH.name)),
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
