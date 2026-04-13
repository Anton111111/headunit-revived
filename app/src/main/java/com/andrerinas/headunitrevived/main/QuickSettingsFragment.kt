package com.andrerinas.headunitrevived.main

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.main.settings.SettingItem
import com.andrerinas.headunitrevived.main.settings.SettingsAdapter
import com.andrerinas.headunitrevived.utils.Settings
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class QuickSettingsFragment : DialogFragment() {

    companion object {
        const val ACTION_SETTINGS_CHANGED = "com.andrerinas.headunitrevived.SETTINGS_CHANGED"
        const val EXTRA_NEEDS_VIEW_RECREATE = "needs_view_recreate"
        const val EXTRA_NEEDS_AUDIO_RESTART = "needs_audio_restart"
        const val EXTRA_SENSOR_REFRESH = "sensor_refresh"
    }

    private lateinit var settings: Settings
    private lateinit var settingsRecyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    
    private var originalViewMode: Settings.ViewMode? = null
    private var originalStretch: Boolean? = null
    private var originalScale: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_quick_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        settings = App.provide(requireContext()).settings
        originalViewMode = settings.viewMode
        originalStretch = settings.stretchToFill
        originalScale = settings.forcedScale

        toolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { dismiss() }

        settingsAdapter = SettingsAdapter()
        settingsRecyclerView = view.findViewById(R.id.settingsRecyclerView)
        settingsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        settingsRecyclerView.adapter = settingsAdapter

        updateSettingsList()
    }

    private fun updateSettingsList() {
        val items = mutableListOf<SettingItem>()

        // --- Audio Section ---
        items.add(SettingItem.CategoryHeader("audio", R.string.category_audio))
        
        items.add(SettingItem.SettingEntry(
            stableId = "audioVolumeOffsets",
            nameResId = R.string.audio_volume_offset,
            value = "${(100 + settings.mediaVolumeOffset)}% / ${(100 + settings.assistantVolumeOffset)}% / ${(100 + settings.navigationVolumeOffset)}%",
            onClick = { showAudioOffsetsDialog() }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "audioLatencyMultiplier",
            nameResId = R.string.audio_latency_multiplier,
            value = "${settings.audioLatencyMultiplier}x",
            onClick = { showAudioLatencyDialog() }
        ))

        // --- Display Section ---
        items.add(SettingItem.CategoryHeader("graphic", R.string.category_graphic))
        
        val nightModeTitles = resources.getStringArray(R.array.night_mode)
        items.add(SettingItem.SettingEntry(
            stableId = "nightMode",
            nameResId = R.string.night_mode_label,
            value = nightModeTitles[settings.nightMode.value],
            onClick = { showNightModeDialog() }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "stretchToFill",
            nameResId = R.string.pref_stretch_screen_title,
            descriptionResId = R.string.pref_stretch_screen_summary,
            isChecked = settings.stretchToFill,
            onCheckedChanged = { isChecked ->
                settings.stretchToFill = isChecked
                settings.commit()
                notifyChange(needsViewRecreate = true)
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "viewMode",
            nameResId = R.string.view_mode,
            value = settings.viewMode.name,
            onClick = { showViewModeDialog() }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "showFpsCounter",
            nameResId = R.string.show_fps_counter,
            descriptionResId = R.string.show_fps_counter_description,
            isChecked = settings.showFpsCounter,
            onCheckedChanged = { isChecked ->
                settings.showFpsCounter = isChecked
                settings.commit()
                notifyChange()
                updateSettingsList()
            }
        ))

        // --- System & Safety ---
        items.add(SettingItem.CategoryHeader("system", R.string.category_automation))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "fakeSpeed",
            nameResId = R.string.fake_speed_title,
            descriptionResId = R.string.fake_speed_description,
            isChecked = settings.fakeSpeed,
            onCheckedChanged = { isChecked ->
                settings.fakeSpeed = isChecked
                settings.commit()
                notifyChange(sensorRefresh = true)
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "killOnDisconnect",
            nameResId = R.string.kill_on_disconnect,
            descriptionResId = R.string.kill_on_disconnect_description,
            isChecked = settings.killOnDisconnect,
            onCheckedChanged = { isChecked ->
                settings.killOnDisconnect = isChecked
                settings.commit()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "exportLogs",
            nameResId = R.string.export_logs,
            value = getString(R.string.export_logs_description),
            onClick = { triggerLogExport() }
        ))

        settingsAdapter.submitList(items)
    }

    private fun notifyChange(needsViewRecreate: Boolean = false, needsAudioRestart: Boolean = false, sensorRefresh: Boolean = false) {
        val intent = Intent(ACTION_SETTINGS_CHANGED).apply {
            putExtra(EXTRA_NEEDS_VIEW_RECREATE, needsViewRecreate)
            putExtra(EXTRA_NEEDS_AUDIO_RESTART, needsAudioRestart)
            putExtra(EXTRA_SENSOR_REFRESH, sensorRefresh)
        }
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(intent)
    }

    private fun showAudioOffsetsDialog() {
        // Reuse dialog logic from SettingsFragment (best would be to move it to a shared helper)
        // For now, let's assume we can trigger a simplified version
    }

    private fun showAudioLatencyDialog() {
        val options = arrayOf("1x (Lowest Latency)", "2x (Low Latency)", "4x (High Latency)", "8x (Very High Latency)")
        val values = intArrayOf(1, 2, 4, 8)
        val currentIndex = values.indexOf(settings.audioLatencyMultiplier).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.audio_latency_multiplier)
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                settings.audioLatencyMultiplier = values[which]
                settings.commit()
                notifyChange(needsAudioRestart = true)
                dialog.dismiss()
                updateSettingsList()
            }
            .show()
    }

    private fun showNightModeDialog() {
        val nightModeTitles = resources.getStringArray(R.array.night_mode)
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.night_mode_label)
            .setSingleChoiceItems(nightModeTitles, settings.nightMode.value) { dialog, which ->
                settings.nightMode = Settings.NightMode.fromInt(which)
                settings.commit()
                notifyChange(sensorRefresh = true)
                dialog.dismiss()
                updateSettingsList()
            }
            .show()
    }

    private fun showViewModeDialog() {
        val viewModes = arrayOf("SurfaceView", "TextureView", "GLES20")
        val values = arrayOf(Settings.ViewMode.SURFACE, Settings.ViewMode.TEXTURE, Settings.ViewMode.GLES)
        val currentIdx = values.indexOf(settings.viewMode).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.view_mode)
            .setSingleChoiceItems(viewModes, currentIdx) { dialog, which ->
                settings.viewMode = values[which]
                settings.commit()
                notifyChange(needsViewRecreate = true)
                dialog.dismiss()
                updateSettingsList()
            }
            .show()
    }

    private fun triggerLogExport() {
        // Implementation for log export
    }
}