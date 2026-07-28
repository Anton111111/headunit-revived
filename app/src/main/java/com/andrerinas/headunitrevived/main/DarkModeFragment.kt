package com.andrerinas.headunitrevived.main

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.main.settings.SettingItem
import com.andrerinas.headunitrevived.main.settings.SettingsAdapter
import com.andrerinas.headunitrevived.utils.AppThemeManager
import com.andrerinas.headunitrevived.utils.Settings
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class DarkModeFragment : Fragment(), SensorEventListener {
    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    private var saveButton: MaterialButton? = null

    // Pending dark mode settings
    private var pendingAppTheme: Settings.AppTheme? = null
    private var pendingAppThemeThresholdLux: Int? = null
    private var pendingAppThemeThresholdBrightness: Int? = null
    private var pendingAppThemeManualStart: Int? = null
    private var pendingAppThemeManualEnd: Int? = null
    private var pendingMonochromeIcons: Boolean? = null
    private var pendingUseExtremeDarkMode: Boolean? = null
    private var pendingUseGradientBackground: Boolean? = null

    // Pending night mode settings (Android Auto)
    private var pendingNightMode: Settings.NightMode? = null
    private var pendingThresholdLux: Int? = null
    private var pendingThresholdBrightness: Int? = null
    private var pendingManualStart: Int? = null
    private var pendingManualEnd: Int? = null

    // Shared sunrise/sunset location source (used by both app theme and Android Auto Auto modes)
    private var pendingUseFixedSunriseLocation: Boolean? = null

    // Pending AA monochrome settings
    private var pendingAaMonochromeEnabled: Boolean? = null
    private var pendingAaDesaturationLevel: Int? = null

    // View mode (needed for GLES dialog)
    private var pendingViewMode: Settings.ViewMode? = null

    // Sensor for live lux reading
    private var cachedLux: Float = -1f
    private var sensorManager: SensorManager? = null

    // Live screen brightness reading (mirrors how the manager reads it)
    private var cachedBrightness: Int = -1
    private val brightnessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val newBrightness = readBrightness()
            if (newBrightness != cachedBrightness) {
                cachedBrightness = newBrightness
                scheduleListRefresh()
            }
        }
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable {
        if (isAdded && ::settingsAdapter.isInitialized) {
            updateSettingsList()
        }
    }

    private var requiresRestart = false
    private var hasChanges = false
    private val SAVE_ITEM_ID = 1001

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_dark_mode, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        // Initialize pending state from current values
        pendingAppTheme = settings.appTheme
        pendingAppThemeThresholdLux = settings.appThemeThresholdLux
        pendingAppThemeThresholdBrightness = settings.appThemeThresholdBrightness
        pendingAppThemeManualStart = settings.appThemeManualStart
        pendingAppThemeManualEnd = settings.appThemeManualEnd
        pendingMonochromeIcons = settings.monochromeIcons
        pendingUseExtremeDarkMode = settings.useExtremeDarkMode
        pendingUseGradientBackground = settings.useGradientBackground

        pendingNightMode = settings.nightMode
        pendingUseFixedSunriseLocation = settings.useFixedSunriseLocation
        pendingThresholdLux = settings.nightModeThresholdLux
        pendingThresholdBrightness = settings.nightModeThresholdBrightness
        pendingManualStart = settings.nightModeManualStart
        pendingManualEnd = settings.nightModeManualEnd

        pendingAaMonochromeEnabled = settings.aaMonochromeEnabled
        pendingAaDesaturationLevel = settings.aaDesaturationLevel

        pendingViewMode = settings.viewMode

        // Intercept system back button
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })

        toolbar = view.findViewById(R.id.toolbar)
        settingsAdapter = SettingsAdapter()
        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = settingsAdapter

        updateSettingsList()
        setupToolbar()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            val newLux = event.values[0]
            if (kotlin.math.abs(newLux - cachedLux) >= 1.0f || cachedLux < 0f) {
                cachedLux = newLux
                scheduleListRefresh()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            handleBackPress()
        }

        val saveItem = toolbar.menu.add(0, SAVE_ITEM_ID, 0, getString(R.string.save))
        saveItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        saveItem.setActionView(R.layout.layout_save_button)

        saveButton = saveItem.actionView?.findViewById(R.id.save_button_widget)
        saveButton?.setOnClickListener {
            saveSettings()
        }

        updateSaveButtonState()
    }

    private fun handleBackPress() {
        if (hasChanges) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.unsaved_changes)
                .setMessage(R.string.unsaved_changes_message)
                .setPositiveButton(R.string.discard) { _, _ ->
                    navigateBack()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            navigateBack()
        }
    }

    private fun navigateBack() {
        try {
            val navController = findNavController()
            if (!navController.navigateUp()) {
                requireActivity().finish()
            }
        } catch (e: Exception) {
            requireActivity().finish()
        }
    }

    private fun updateSaveButtonState() {
        saveButton?.isEnabled = hasChanges
        saveButton?.text = if (requiresRestart) getString(R.string.save_and_restart) else getString(R.string.save)
    }

    private fun saveSettings() {
        // Detect changes BEFORE saving values to SharedPreferences
        val themeChanged = pendingAppTheme != settings.appTheme
        val sunriseLocationChanged = pendingUseFixedSunriseLocation != settings.useFixedSunriseLocation
        val appThemeThresholdChanged = pendingAppThemeThresholdLux != settings.appThemeThresholdLux ||
                pendingAppThemeThresholdBrightness != settings.appThemeThresholdBrightness ||
                pendingAppThemeManualStart != settings.appThemeManualStart ||
                pendingAppThemeManualEnd != settings.appThemeManualEnd ||
                // A change to the shared sunrise/sunset location affects AUTO_SUNRISE too.
                sunriseLocationChanged
        val gradientChanged = pendingUseGradientBackground != settings.useGradientBackground
        val extremeDarkChanged = pendingUseExtremeDarkMode != settings.useExtremeDarkMode
        val monochromeIconsChanged = pendingMonochromeIcons != settings.monochromeIcons
        val viewModeChanged = pendingViewMode != settings.viewMode

        // Save night mode settings
        pendingNightMode?.let { settings.nightMode = it }
        pendingUseFixedSunriseLocation?.let { settings.useFixedSunriseLocation = it }
        pendingThresholdLux?.let { settings.nightModeThresholdLux = it }
        pendingThresholdBrightness?.let { settings.nightModeThresholdBrightness = it }
        pendingManualStart?.let { settings.nightModeManualStart = it }
        pendingManualEnd?.let { settings.nightModeManualEnd = it }

        // Save app theme settings
        pendingAppThemeThresholdLux?.let { settings.appThemeThresholdLux = it }
        pendingAppThemeThresholdBrightness?.let { settings.appThemeThresholdBrightness = it }
        pendingAppThemeManualStart?.let { settings.appThemeManualStart = it }
        pendingAppThemeManualEnd?.let { settings.appThemeManualEnd = it }
        pendingMonochromeIcons?.let { settings.monochromeIcons = it }
        pendingUseExtremeDarkMode?.let { settings.useExtremeDarkMode = it }
        pendingUseGradientBackground?.let { settings.useGradientBackground = it }

        // Save AA monochrome settings
        pendingAaMonochromeEnabled?.let { settings.aaMonochromeEnabled = it }
        pendingAaDesaturationLevel?.let { settings.aaDesaturationLevel = it }

        // Save view mode if changed (from GLES dialog)
        if (viewModeChanged) {
            pendingViewMode?.let { settings.viewMode = it }
        }

        pendingAppTheme?.let { newTheme ->
            settings.appTheme = newTheme
            if (themeChanged || (appThemeThresholdChanged && !AppThemeManager.isStaticMode(newTheme))) {
                // Stop existing auto theme manager
                App.appThemeManager?.stop()
                App.appThemeManager = null

                if (AppThemeManager.isStaticMode(newTheme)) {
                    AppThemeManager.applyStaticTheme(settings)
                } else {
                    val manager = AppThemeManager(requireContext().applicationContext, settings)
                    App.appThemeManager = manager
                    manager.start()
                }
            }
        }

        // Notify Service about Night Mode changes immediately
        val nightModeUpdateIntent = Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE)
        nightModeUpdateIntent.setPackage(requireContext().packageName)
        requireContext().sendBroadcast(nightModeUpdateIntent)

        if (requiresRestart) {
            if (App.provide(requireContext()).commManager.isConnected) {
                Toast.makeText(context, getString(R.string.stopping_service), Toast.LENGTH_SHORT).show()
                val stopServiceIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(requireContext(), stopServiceIntent)
            }
        }

        // Reset change tracking
        hasChanges = false
        requiresRestart = false
        updateSaveButtonState()

        Toast.makeText(context, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()

        // Signal visual change so all activities (including MainActivity) pick up changes
        if (gradientChanged || extremeDarkChanged || monochromeIconsChanged || themeChanged ||
            (appThemeThresholdChanged && pendingAppTheme?.let { !AppThemeManager.isStaticMode(it) } == true)) {
            AppThemeManager.signalVisualChange()
        }
    }

    private fun checkChanges() {
        val anyChange = pendingAppTheme != settings.appTheme ||
                pendingAppThemeThresholdLux != settings.appThemeThresholdLux ||
                pendingAppThemeThresholdBrightness != settings.appThemeThresholdBrightness ||
                pendingAppThemeManualStart != settings.appThemeManualStart ||
                pendingAppThemeManualEnd != settings.appThemeManualEnd ||
                pendingMonochromeIcons != settings.monochromeIcons ||
                pendingUseExtremeDarkMode != settings.useExtremeDarkMode ||
                pendingUseGradientBackground != settings.useGradientBackground ||
                pendingNightMode != settings.nightMode ||
                pendingUseFixedSunriseLocation != settings.useFixedSunriseLocation ||
                pendingThresholdLux != settings.nightModeThresholdLux ||
                pendingThresholdBrightness != settings.nightModeThresholdBrightness ||
                pendingManualStart != settings.nightModeManualStart ||
                pendingManualEnd != settings.nightModeManualEnd ||
                pendingAaMonochromeEnabled != settings.aaMonochromeEnabled ||
                pendingAaDesaturationLevel != settings.aaDesaturationLevel ||
                pendingViewMode != settings.viewMode

        hasChanges = anyChange

        // View mode change requires restart
        requiresRestart = pendingViewMode != settings.viewMode

        updateSaveButtonState()
    }

    /** True if any saved place forces a theme covering the given system (app or Android Auto). */
    private fun hasThemeGeofenceFor(app: Boolean): Boolean =
        settings.geofenceLocations.any {
            it.overrideTheme && (if (app) it.scope.coversApp() else it.scope.coversAndroidAuto())
        }

    /** The theme-forcing place the device is currently inside, or null. */
    private fun currentThemeGeofence(): com.andrerinas.headunitrevived.location.GeofenceLocation? {
        val areas = settings.geofenceLocations.filter { it.overrideTheme }
        if (areas.isEmpty()) return null
        val loc = com.andrerinas.headunitrevived.location.LocationHolder
            .currentLocation(requireContext()) ?: return null
        return areas.firstOrNull { it.contains(loc) }
    }

    /**
     * Top "Location" section, shared by both theming systems: the sunrise/sunset location
     * source (only shown when some system uses Auto/sunrise), the saved places entry, and a
     * live "currently inside" status.
     */
    private fun buildLocationSection(items: MutableList<SettingItem>) {
        items.add(SettingItem.CategoryHeader("location", R.string.location_section))

        // Conflict #1: the sunrise/sunset location only matters for Auto/sunrise modes.
        val usesSunrise = pendingAppTheme == Settings.AppTheme.AUTO_SUNRISE ||
                pendingNightMode == Settings.NightMode.AUTO
        if (usesSunrise) {
            val useFixed = pendingUseFixedSunriseLocation == true
            items.add(SettingItem.SettingEntry(
                stableId = "sunriseLocation",
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
                            pendingUseFixedSunriseLocation = which == 1
                            checkChanges()
                            dialog.dismiss()
                            updateSettingsList()
                        }
                        .show()
                }
            ))
            // Conflict #2: "pick on map" only when the fixed point source is selected.
            if (useFixed) {
                items.add(SettingItem.SettingEntry(
                    stableId = "sunrisePickMap",
                    nameResId = R.string.geofence_pick_on_map,
                    value = "",
                    onClick = { _ ->
                        findNavController().navigate(
                            R.id.action_darkModeFragment_to_mapPickerFragment,
                            androidx.core.os.bundleOf(MapPickerFragment.ARG_MODE to MapPickerFragment.MODE_POINT)
                        )
                    }
                ))
            }
        }

        // Saved places (each forces a theme and/or gates automation per its own settings).
        items.add(SettingItem.SettingEntry(
            stableId = "geofenceLocations",
            nameResId = R.string.geofence_locations_title,
            value = run {
                val n = settings.geofenceLocations.size
                if (n == 0) getString(R.string.geofence_none) else getString(R.string.geofence_count_summary, n)
            },
            onClick = { _ ->
                findNavController().navigate(R.id.action_darkModeFragment_to_locationsFragment)
            }
        ))

        // Live status: currently inside a theme-forcing place.
        currentThemeGeofence()?.let { g ->
            val state = getString(if (g.forceNight) R.string.geofence_mode_dark else R.string.geofence_mode_light)
            val place = g.name.ifBlank { getString(R.string.geofence_unnamed) }
            items.add(SettingItem.SettingEntry(
                stableId = "geofenceLiveStatus",
                nameResId = R.string.geofence_current_status_label,
                value = getString(R.string.geofence_current_status, state, place),
                onClick = { _ -> }
            ))
        }
    }

    private fun updateSettingsList() {
        val scrollState = recyclerView.layoutManager?.onSaveInstanceState()
        val items = mutableListOf<SettingItem>()

        // --- Location (shared by the app theme and Android Auto night mode) ---
        buildLocationSection(items)

        // --- App Theme ---
        items.add(SettingItem.CategoryHeader("appTheme", R.string.app_theme))

        val appThemeTitles = resources.getStringArray(R.array.app_theme)
        items.add(SettingItem.SettingEntry(
            stableId = "appTheme",
            nameResId = R.string.app_theme,
            value = appThemeTitles[pendingAppTheme!!.value],
            onClick = { _ ->
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_app_theme)
                    .setSingleChoiceItems(appThemeTitles, pendingAppTheme!!.value) { dialog, which ->
                        pendingAppTheme = Settings.AppTheme.fromInt(which)
                        if (pendingAppTheme == Settings.AppTheme.EXTREME_DARK) {
                            pendingMonochromeIcons = true
                        } else if (pendingAppTheme == Settings.AppTheme.CLEAR) {
                            pendingMonochromeIcons = false
                        }
                        // Reset useExtremeDarkMode for static modes
                        if (pendingAppTheme == Settings.AppTheme.CLEAR ||
                            pendingAppTheme == Settings.AppTheme.DARK ||
                            pendingAppTheme == Settings.AppTheme.EXTREME_DARK) {
                            pendingUseExtremeDarkMode = false
                        }
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // "Use Extreme Dark" toggle for auto modes
        if (pendingAppTheme != Settings.AppTheme.CLEAR &&
            pendingAppTheme != Settings.AppTheme.DARK &&
            pendingAppTheme != Settings.AppTheme.EXTREME_DARK) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "useExtremeDarkMode",
                nameResId = R.string.use_extreme_dark,
                descriptionResId = R.string.use_extreme_dark_description,
                isChecked = pendingUseExtremeDarkMode!!,
                onCheckedChanged = { isChecked ->
                    pendingUseExtremeDarkMode = isChecked
                    if (isChecked) pendingUseGradientBackground = false
                    checkChanges()
                    updateSettingsList()
                }
            ))
        }

        // Monochrome icons toggle (for all dark-capable modes)
        if (pendingAppTheme != Settings.AppTheme.CLEAR) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "monochromeIcons",
                nameResId = R.string.monochrome_icons,
                descriptionResId = R.string.monochrome_icons_description,
                isChecked = pendingMonochromeIcons!!,
                onCheckedChanged = { isChecked ->
                    pendingMonochromeIcons = isChecked
                    checkChanges()
                    updateSettingsList()
                }
            ))
        }

        // Gradient background toggle (hidden for Extreme Dark)
        if (pendingAppTheme != Settings.AppTheme.EXTREME_DARK) {
            val isAutoMode = pendingAppTheme != Settings.AppTheme.CLEAR &&
                             pendingAppTheme != Settings.AppTheme.DARK
            val gradientEnabled = !isAutoMode || pendingUseExtremeDarkMode != true
            val isGradientOn = pendingUseGradientBackground == true
            val descResId = when {
                isGradientOn && isAutoMode -> R.string.use_gradient_background_description_on_auto
                isGradientOn -> R.string.use_gradient_background_description_on
                isAutoMode -> R.string.use_gradient_background_description_off_auto
                else -> R.string.use_gradient_background_description_off
            }

            val gradientName = if (pendingAppTheme == Settings.AppTheme.CLEAR) {
                getString(R.string.use_white_background)
            } else null

            items.add(SettingItem.ToggleSettingEntry(
                stableId = "useGradientBackground",
                nameResId = R.string.use_gradient_background,
                descriptionResId = descResId,
                isChecked = pendingUseGradientBackground!!,
                isEnabled = gradientEnabled,
                nameOverride = gradientName,
                onCheckedChanged = { isChecked ->
                    pendingUseGradientBackground = isChecked
                    checkChanges()
                    updateSettingsList()
                }
            ))
        }

        // App theme sub-options: threshold slider for Light Sensor / Screen Brightness
        if (pendingAppTheme == Settings.AppTheme.LIGHT_SENSOR || pendingAppTheme == Settings.AppTheme.SCREEN_BRIGHTNESS) {
            val isSensor = pendingAppTheme == Settings.AppTheme.LIGHT_SENSOR
            val currentValue = if (isSensor) pendingAppThemeThresholdLux else pendingAppThemeThresholdBrightness
            val title = getString(if (isSensor) R.string.threshold_light_title else R.string.threshold_brightness_title)
            val hint = getString(if (isSensor) R.string.threshold_light_hint else R.string.threshold_brightness_hint)
            val currentReading = if (isSensor) {
                if (cachedLux >= 0) getString(R.string.current_light_reading, cachedLux.toInt()) else ""
            } else {
                if (cachedBrightness >= 0) getString(R.string.current_brightness_reading, cachedBrightness) else ""
            }
            val displayValue = if (isSensor) {
                val base = "${currentValue ?: 0} Lux"
                if (currentReading.isNotEmpty()) "$base ($currentReading)" else base
            } else {
                val base = "${currentValue ?: 0} / 255"
                if (currentReading.isNotEmpty()) "$base ($currentReading)" else base
            }

            items.add(SettingItem.SettingEntry(
                stableId = "appThemeThreshold",
                nameResId = if (isSensor) R.string.threshold_light_title else R.string.threshold_brightness_title,
                value = displayValue,
                onClick = { _ ->
                    if (isSensor) {
                        showLuxSliderDialog(
                            title = title,
                            message = hint,
                            initialLux = currentValue ?: 0,
                            currentReading = currentReading,
                            onConfirm = { newLux ->
                                pendingAppThemeThresholdLux = newLux
                                checkChanges()
                                updateSettingsList()
                            }
                        )
                    } else {
                        showSliderDialog(
                            title = title,
                            message = hint,
                            initialPercentage = currentValue ?: 100,
                            minLabel = "0",
                            maxLabel = "255",
                            formatValue = { v -> "$v" },
                            currentReading = currentReading,
                            sliderMax = 255,
                            onConfirm = { newVal ->
                                pendingAppThemeThresholdBrightness = newVal
                                checkChanges()
                                updateSettingsList()
                            }
                        )
                    }
                }
            ))

        }

        // App theme sub-options: time pickers for Manual Time
        if (pendingAppTheme == Settings.AppTheme.MANUAL_TIME) {
            val formatTime = { minutes: Int -> "%02d:%02d".format(minutes / 60, minutes % 60) }

            items.add(SettingItem.SettingEntry(
                stableId = "appThemeStart",
                nameResId = R.string.night_mode_start,
                value = formatTime(pendingAppThemeManualStart!!),
                onClick = { _ ->
                    TimePickerDialog(requireContext(), { _, hour, minute ->
                        pendingAppThemeManualStart = hour * 60 + minute
                        checkChanges()
                        updateSettingsList()
                    }, pendingAppThemeManualStart!! / 60, pendingAppThemeManualStart!! % 60, true).show()
                }
            ))

            items.add(SettingItem.SettingEntry(
                stableId = "appThemeEnd",
                nameResId = R.string.night_mode_end,
                value = formatTime(pendingAppThemeManualEnd!!),
                onClick = { _ ->
                    TimePickerDialog(requireContext(), { _, hour, minute ->
                        pendingAppThemeManualEnd = hour * 60 + minute
                        checkChanges()
                        updateSettingsList()
                    }, pendingAppThemeManualEnd!! / 60, pendingAppThemeManualEnd!! % 60, true).show()
                }
            ))
        }

        // Precedence note: a saved place that forces a theme for the app overrides the mode above.
        if (hasThemeGeofenceFor(app = true)) {
            items.add(SettingItem.InfoBanner("appOverriddenNote", R.string.geofence_overridden_note))
        }

        // --- Android Auto Night Mode ---
        items.add(SettingItem.CategoryHeader("aaNightMode", R.string.night_mode))

        // Night Mode (Android Auto)
        items.add(SettingItem.SettingEntry(
            stableId = "nightMode",
            nameResId = R.string.night_mode,
            value = run {
                val base = resources.getStringArray(R.array.night_mode)[pendingNightMode!!.value]
                if (pendingNightMode == Settings.NightMode.AUTO) {
                    val info = com.andrerinas.headunitrevived.utils.NightMode(settings, true).getCalculationInfo()
                    "$base ($info)"
                } else {
                    base
                }
            },
            onClick = { _ ->
                val nightModeTitles = resources.getStringArray(R.array.night_mode)

                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.night_mode)
                    .setSingleChoiceItems(nightModeTitles, pendingNightMode!!.value) { dialog, which ->
                        pendingNightMode = Settings.NightMode.fromInt(which)!!
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // Night mode sub-options: threshold slider for Light Sensor / Screen Brightness
        if (pendingNightMode == Settings.NightMode.LIGHT_SENSOR || pendingNightMode == Settings.NightMode.SCREEN_BRIGHTNESS) {
            val isSensor = pendingNightMode == Settings.NightMode.LIGHT_SENSOR
            val currentValue = if (isSensor) pendingThresholdLux else pendingThresholdBrightness
            val title = getString(if (isSensor) R.string.threshold_light_title else R.string.threshold_brightness_title)
            val hint = getString(if (isSensor) R.string.threshold_light_hint else R.string.threshold_brightness_hint)
            val nmCurrentReading = if (isSensor) {
                if (cachedLux >= 0) getString(R.string.current_light_reading, cachedLux.toInt()) else ""
            } else {
                if (cachedBrightness >= 0) getString(R.string.current_brightness_reading, cachedBrightness) else ""
            }
            val displayValue = if (isSensor) {
                val base = "${currentValue ?: 0} Lux"
                if (nmCurrentReading.isNotEmpty()) "$base ($nmCurrentReading)" else base
            } else {
                val base = "${currentValue ?: 0} / 255"
                if (nmCurrentReading.isNotEmpty()) "$base ($nmCurrentReading)" else base
            }

            items.add(SettingItem.SettingEntry(
                stableId = "nightModeThreshold",
                nameResId = if (isSensor) R.string.threshold_light_title else R.string.threshold_brightness_title,
                value = displayValue,
                onClick = { _ ->
                    if (isSensor) {
                        showLuxSliderDialog(
                            title = title,
                            message = hint,
                            initialLux = currentValue ?: 0,
                            currentReading = nmCurrentReading,
                            onConfirm = { newLux ->
                                pendingThresholdLux = newLux
                                checkChanges()
                                updateSettingsList()
                            }
                        )
                    } else {
                        showSliderDialog(
                            title = title,
                            message = hint,
                            initialPercentage = currentValue ?: 100,
                            minLabel = "0",
                            maxLabel = "255",
                            formatValue = { v -> "$v" },
                            currentReading = nmCurrentReading,
                            sliderMax = 255,
                            onConfirm = { newVal ->
                                pendingThresholdBrightness = newVal
                                checkChanges()
                                updateSettingsList()
                            }
                        )
                    }
                }
            ))

        }

        // Night mode sub-options: time pickers for Manual Time
        if (pendingNightMode == Settings.NightMode.MANUAL_TIME) {
            val formatTime = { minutes: Int -> "%02d:%02d".format(minutes / 60, minutes % 60) }

            items.add(SettingItem.SettingEntry(
                stableId = "nightModeStart",
                nameResId = R.string.night_mode_start,
                value = formatTime(pendingManualStart!!),
                onClick = { _ ->
                    TimePickerDialog(requireContext(), { _, hour, minute ->
                        pendingManualStart = hour * 60 + minute
                        checkChanges()
                        updateSettingsList()
                    }, pendingManualStart!! / 60, pendingManualStart!! % 60, true).show()
                }
            ))

            items.add(SettingItem.SettingEntry(
                stableId = "nightModeEnd",
                nameResId = R.string.night_mode_end,
                value = formatTime(pendingManualEnd!!),
                onClick = { _ ->
                    TimePickerDialog(requireContext(), { _, hour, minute ->
                        pendingManualEnd = hour * 60 + minute
                        checkChanges()
                        updateSettingsList()
                    }, pendingManualEnd!! / 60, pendingManualEnd!! % 60, true).show()
                }
            ))
        }

        // Precedence note: while inside a saved place that forces a theme for Android Auto,
        // that place wins over the mode selected above.
        if (hasThemeGeofenceFor(app = false)) {
            items.add(SettingItem.InfoBanner("aaOverriddenNote", R.string.geofence_overridden_note))
        }

        // AA Monochrome toggle — hidden when Night Mode is DAY
        if (pendingNightMode != Settings.NightMode.DAY) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "aaMonochrome",
                nameResId = R.string.aa_monochrome,
                descriptionResId = R.string.aa_monochrome_description,
                isChecked = pendingAaMonochromeEnabled!!,
                onCheckedChanged = { isChecked ->
                    if (isChecked && pendingViewMode != Settings.ViewMode.GLES) {
                        // Set to true so the submitted list matches the visual switch state.
                        // This way DiffUtil can detect the revert to false on cancel.
                        pendingAaMonochromeEnabled = true
                        updateSettingsList()
                        // Show GLES required dialog
                        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                            .setTitle(R.string.gles_required_title)
                            .setMessage(R.string.gles_required_message)
                            .setPositiveButton(R.string.enable_gles) { _, _ ->
                                pendingViewMode = Settings.ViewMode.GLES
                                pendingAaMonochromeEnabled = true
                                checkChanges()
                                updateSettingsList()
                            }
                            .setNegativeButton(R.string.cancel) { _, _ ->
                                pendingAaMonochromeEnabled = false
                                checkChanges()
                                updateSettingsList()
                            }
                            .setOnCancelListener {
                                pendingAaMonochromeEnabled = false
                                checkChanges()
                                updateSettingsList()
                            }
                            .show()
                    } else {
                        pendingAaMonochromeEnabled = isChecked
                        checkChanges()
                        updateSettingsList()
                    }
                }
            ))

            // Desaturation slider — only visible when AA monochrome is ON
            if (pendingAaMonochromeEnabled == true) {
                items.add(SettingItem.SliderSettingEntry(
                    stableId = "aaDesaturation",
                    nameResId = R.string.aa_desaturation,
                    value = "${pendingAaDesaturationLevel}%",
                    sliderValue = pendingAaDesaturationLevel!!.toFloat(),
                    valueFrom = 0f,
                    valueTo = 100f,
                    stepSize = 0f,
                    onValueChanged = { value ->
                        pendingAaDesaturationLevel = value.toInt()
                        checkChanges()
                        updateSettingsList()
                    }
                ))
            }
        }

        settingsAdapter.submitList(items) {
            scrollState?.let { recyclerView.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor != null) {
            sensorManager?.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // Observe screen brightness changes so the threshold dialog/list show a live value
        cachedBrightness = readBrightness()
        val cr = requireContext().contentResolver
        cr.registerContentObserver(
            SystemSettings.System.getUriFor(SystemSettings.System.SCREEN_BRIGHTNESS),
            false, brightnessObserver
        )
        if (Build.VERSION.SDK_INT >= 28) {
            cr.registerContentObserver(
                SystemSettings.System.getUriFor("screen_brightness_float"),
                false, brightnessObserver
            )
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        try { context?.contentResolver?.unregisterContentObserver(brightnessObserver) } catch (_: Exception) {}
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun readBrightness(): Int {
        val ctx = context ?: return -1
        val cr = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val f = SystemSettings.System.getFloat(cr, "screen_brightness_float")
                if (!f.isNaN()) return (f * 255f).toInt().coerceIn(0, 255)
            } catch (_: Exception) { /* fall through */ }
        }
        return try {
            SystemSettings.System.getInt(cr, SystemSettings.System.SCREEN_BRIGHTNESS)
                .coerceIn(0, 255)
        } catch (_: Exception) { -1 }
    }


    private fun scheduleListRefresh() {
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 500)
    }

    private fun showSliderDialog(
        title: String,
        message: String,
        initialPercentage: Int,
        minLabel: String,
        maxLabel: String,
        formatValue: (Int) -> String,
        currentReading: String = "",
        sliderMax: Int = 100,
        onConfirm: (Int) -> Unit
    ) {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val padding = (24 * density).toInt()

        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, (8 * density).toInt(), padding, 0)
        }

        val hint = android.widget.TextView(context).apply {
            text = message
            textSize = 14f
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }
        layout.addView(hint)

        val label = android.widget.TextView(context).apply {
            text = formatValue(initialPercentage.coerceIn(0, sliderMax))
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            val topMargin = (16 * density).toInt()
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = topMargin
            layoutParams = lp
        }
        layout.addView(label)

        val seekBar = android.widget.SeekBar(context).apply {
            max = sliderMax
            progress = initialPercentage.coerceIn(0, sliderMax)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = formatValue(progress)
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        }
        layout.addView(seekBar)

        val rangeRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }
        val minText = android.widget.TextView(context).apply {
            text = minLabel
            textSize = 12f
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            val lp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        val maxText = android.widget.TextView(context).apply {
            text = maxLabel
            textSize = 12f
            gravity = android.view.Gravity.END
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            val lp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        rangeRow.addView(minText)
        rangeRow.addView(maxText)
        layout.addView(rangeRow)

        if (currentReading.isNotEmpty()) {
            val readingLabel = android.widget.TextView(context).apply {
                text = currentReading
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                    setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (16 * density).toInt()
                layoutParams = lp
            }
            layout.addView(readingLabel)
        }

        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                onConfirm(seekBar.progress)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    private fun showLuxSliderDialog(
        title: String,
        message: String,
        initialLux: Int,
        currentReading: String = "",
        onConfirm: (Int) -> Unit
    ) {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val padding = (24 * density).toInt()

        var currentMax = if (initialLux <= LUX_MAX_FINE) LUX_MAX_FINE else LUX_MAX

        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, (8 * density).toInt(), padding, 0)
        }

        val hint = android.widget.TextView(context).apply {
            text = message
            textSize = 14f
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }
        layout.addView(hint)

        val label = android.widget.TextView(context).apply {
            text = "$initialLux Lux"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (16 * density).toInt()
            layoutParams = lp
        }
        layout.addView(label)

        val seekBar = android.widget.SeekBar(context).apply {
            max = currentMax
            progress = initialLux.coerceIn(0, currentMax)
        }

        val minText = android.widget.TextView(context).apply {
            text = "0 Lux"
            textSize = 12f
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val maxText = android.widget.TextView(context).apply {
            text = "${currentMax} Lux"
            textSize = 12f
            gravity = android.view.Gravity.END
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = "$progress Lux"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        layout.addView(seekBar)

        val rangeRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        rangeRow.addView(minText)
        rangeRow.addView(maxText)
        layout.addView(rangeRow)

        val checkBox = android.widget.CheckBox(context).apply {
            text = getString(R.string.enable_fine_lux_control)
            isChecked = currentMax == LUX_MAX_FINE
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (12 * density).toInt()
            layoutParams = lp
        }
        checkBox.setOnCheckedChangeListener { _, isChecked ->
            val oldProgress = seekBar.progress
            currentMax = if (isChecked) LUX_MAX_FINE else LUX_MAX
            seekBar.max = currentMax
            seekBar.progress = oldProgress.coerceIn(0, currentMax)
            maxText.text = "${currentMax} Lux"
            label.text = "${seekBar.progress} Lux"
        }
        layout.addView(checkBox)

        if (currentReading.isNotEmpty()) {
            val readingLabel = android.widget.TextView(context).apply {
                text = currentReading
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                    setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (16 * density).toInt()
                layoutParams = lp
            }
            layout.addView(readingLabel)
        }

        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                onConfirm(seekBar.progress)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    companion object {
        private const val LUX_MAX = 10000
        private const val LUX_MAX_FINE = 100
    }
}
