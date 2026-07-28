package com.andrerinas.headunitrevived.main

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.location.GeofenceLocation
import com.andrerinas.headunitrevived.location.LocationHolder
import com.andrerinas.headunitrevived.main.settings.SettingItem
import com.andrerinas.headunitrevived.main.settings.SettingsAdapter
import com.andrerinas.headunitrevived.utils.AppThemeManager
import com.andrerinas.headunitrevived.utils.Settings
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Self-contained "Location" screen. Two clearly-separated sections:
 *  - Sunrise/sunset reference: GPS (automatic) or a fixed point, used by both Auto modes
 *    so head units without GPS still get correct day/night (issue #647).
 *  - Places: geofenced areas that can force a light/dark look (with a scope) and/or gate
 *    auto-start, plus a live "currently inside" status.
 *
 * All changes persist immediately and re-apply the theme engines.
 */
class LocationsFragment : Fragment() {

    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SettingsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_locations, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = App.provide(requireContext()).settings

        view.findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        adapter = SettingsAdapter()
        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        updateList()
    }

    private fun updateList() {
        val items = mutableListOf<SettingItem>()

        // --- Sunrise / sunset reference (used by both Auto modes) ---
        items.add(SettingItem.CategoryHeader("sunrise", R.string.sunrise_section))
        val useFixed = settings.useFixedSunriseLocation
        items.add(SettingItem.SettingEntry(
            stableId = "sunriseSource",
            nameResId = R.string.sunrise_location_title,
            value = if (useFixed)
                getString(R.string.sunrise_location_fixed) +
                    " (%.3f, %.3f)".format(settings.fixedSunriseLatitude, settings.fixedSunriseLongitude)
            else getString(R.string.sunrise_location_gps),
            onClick = { _ ->
                val options = arrayOf(
                    getString(R.string.sunrise_location_gps),
                    getString(R.string.sunrise_location_fixed)
                )
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.sunrise_location_title)
                    .setSingleChoiceItems(options, if (useFixed) 1 else 0) { dialog, which ->
                        settings.useFixedSunriseLocation = which == 1
                        applyThemeConfigChange()
                        dialog.dismiss()
                        updateList()
                    }
                    .show()
            }
        ))
        if (useFixed) {
            items.add(SettingItem.SettingEntry(
                stableId = "sunrisePick",
                nameResId = R.string.geofence_pick_on_map,
                value = "",
                onClick = { openPicker(MapPickerFragment.MODE_POINT, null) }
            ))
        }

        // --- Places ---
        items.add(SettingItem.CategoryHeader("places", R.string.geofence_locations_title))
        items.add(SettingItem.InfoBanner("placesHint", R.string.location_places_hint))

        for (loc in settings.geofenceLocations) {
            items.add(SettingItem.SettingEntry(
                stableId = "geo_${loc.id}",
                nameResId = R.string.geofence_locations_title,
                nameOverride = loc.name.ifBlank { getString(R.string.geofence_unnamed) },
                value = summary(loc),
                onClick = { openPicker(MapPickerFragment.MODE_GEOFENCE, loc.id) }
            ))
        }

        items.add(SettingItem.ActionButton(
            stableId = "geofenceAdd",
            textResId = R.string.geofence_add,
            onClick = { openPicker(MapPickerFragment.MODE_GEOFENCE, null) }
        ))

        // Live status: currently inside a theme-forcing place.
        currentThemePlace()?.let { g ->
            val state = getString(if (g.forceNight) R.string.geofence_mode_dark else R.string.geofence_mode_light)
            val place = g.name.ifBlank { getString(R.string.geofence_unnamed) }
            items.add(SettingItem.SettingEntry(
                stableId = "liveStatus",
                nameResId = R.string.geofence_current_status_label,
                value = getString(R.string.geofence_current_status, state, place),
                onClick = { _ -> }
            ))
        }

        adapter.submitList(items)
    }

    private fun summary(loc: GeofenceLocation): String {
        val parts = mutableListOf(getString(R.string.geofence_radius_summary, loc.radiusMeters.toInt()))
        if (loc.overrideTheme) {
            val mode = getString(if (loc.forceNight) R.string.geofence_mode_dark else R.string.geofence_mode_light)
            val scopeLabel = getString(
                when (loc.scope) {
                    GeofenceLocation.Scope.APP -> R.string.geofence_scope_app
                    GeofenceLocation.Scope.ANDROID_AUTO -> R.string.geofence_scope_aa
                    GeofenceLocation.Scope.BOTH -> R.string.geofence_scope_both
                }
            )
            parts.add("$mode ($scopeLabel)")
        }
        if (loc.gateAutomation) parts.add(getString(R.string.geofence_gate_on))
        return parts.joinToString(" · ")
    }

    private fun currentThemePlace(): GeofenceLocation? {
        val areas = settings.geofenceLocations.filter { it.overrideTheme }
        if (areas.isEmpty()) return null
        val loc = LocationHolder.geofenceFix(requireContext()) ?: return null
        return areas.firstOrNull { it.contains(loc) }
    }

    private fun openPicker(mode: String, geofenceId: String?) {
        findNavController().navigate(
            R.id.action_locationsFragment_to_mapPickerFragment,
            bundleOf(
                MapPickerFragment.ARG_MODE to mode,
                MapPickerFragment.ARG_GEOFENCE_ID to geofenceId
            )
        )
    }

    /** Re-applies both theme engines after a location/place change. */
    private fun applyThemeConfigChange() {
        AppThemeManager.reapply(requireContext(), settings)
        requireContext().sendBroadcast(
            Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE).setPackage(requireContext().packageName)
        )
    }
}
