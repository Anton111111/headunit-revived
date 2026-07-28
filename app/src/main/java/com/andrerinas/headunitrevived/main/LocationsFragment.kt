package com.andrerinas.headunitrevived.main

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
import com.andrerinas.headunitrevived.main.settings.SettingItem
import com.andrerinas.headunitrevived.main.settings.SettingsAdapter
import com.andrerinas.headunitrevived.utils.Settings
import com.google.android.material.appbar.MaterialToolbar

/**
 * Lists the user's saved geofenced areas (Home, Work, ...). Each row opens the
 * map picker to edit it; the "Add" button opens the picker for a new area.
 * CRUD is persisted directly to [Settings.geofenceLocations].
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
        // Refresh after returning from the editor so new/edited/deleted areas show.
        updateList()
    }

    private fun updateList() {
        val items = mutableListOf<SettingItem>()
        items.add(SettingItem.CategoryHeader("geofences", R.string.geofence_locations_title))

        val locations = settings.geofenceLocations
        if (locations.isEmpty()) {
            items.add(SettingItem.InfoBanner("geofenceEmpty", R.string.geofence_empty_hint))
        } else {
            for (loc in locations) {
                items.add(SettingItem.SettingEntry(
                    stableId = "geo_${loc.id}",
                    nameResId = R.string.geofence_locations_title,
                    nameOverride = loc.name.ifBlank { getString(R.string.geofence_unnamed) },
                    value = summary(loc),
                    onClick = { openEditor(loc.id) }
                ))
            }
        }

        items.add(SettingItem.ActionButton(
            stableId = "geofenceAdd",
            textResId = R.string.geofence_add,
            onClick = { openEditor(null) }
        ))

        adapter.submitList(items)
    }

    private fun summary(loc: com.andrerinas.headunitrevived.location.GeofenceLocation): String {
        val parts = mutableListOf(getString(R.string.geofence_radius_summary, loc.radiusMeters.toInt()))
        if (loc.overrideTheme) {
            val mode = getString(if (loc.forceNight) R.string.geofence_mode_dark else R.string.geofence_mode_light)
            val scopeLabel = getString(
                when (loc.scope) {
                    com.andrerinas.headunitrevived.location.GeofenceLocation.Scope.APP -> R.string.geofence_scope_app
                    com.andrerinas.headunitrevived.location.GeofenceLocation.Scope.ANDROID_AUTO -> R.string.geofence_scope_aa
                    com.andrerinas.headunitrevived.location.GeofenceLocation.Scope.BOTH -> R.string.geofence_scope_both
                }
            )
            parts.add("$mode ($scopeLabel)")
        }
        if (loc.gateAutomation) parts.add(getString(R.string.geofence_gate_on))
        return parts.joinToString(" · ")
    }

    private fun openEditor(geofenceId: String?) {
        findNavController().navigate(
            R.id.action_locationsFragment_to_mapPickerFragment,
            bundleOf(
                MapPickerFragment.ARG_MODE to MapPickerFragment.MODE_GEOFENCE,
                MapPickerFragment.ARG_GEOFENCE_ID to geofenceId
            )
        )
    }
}
