package com.andrerinas.headunitrevived.main

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.app.BaseActivity
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.utils.AppThemeManager
import com.andrerinas.headunitrevived.utils.LocaleHelper
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.SystemOptimizer
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.sqrt

/**
 * Intelligent, device-aware first-run wizard. Replaces the old
 * SafetyDisclaimerDialog + SetupWizard dialog chain with a single guided flow.
 *
 * Shown once to everyone (including upgraders) when the stored onboardingVersion
 * is older than CURRENT_ONBOARDING_VERSION, and re-launchable from Settings.
 *
 * Steps: Welcome(+language) / Safety(mandatory) / Connection / Display scan /
 * Appearance / Automation / Location / Ready.
 *
 * Every step reuses the real settings and their strings, and deep-links into the
 * full settings sub-screens for advanced configuration.
 */
class OnboardingActivity : BaseActivity() {

    private val settings by lazy { App.provide(this).settings }
    private lateinit var flipper: ViewFlipper
    private lateinit var backBtn: MaterialButton
    private lateinit var nextBtn: MaterialButton
    private lateinit var skipBtn: MaterialButton
    private lateinit var stepper: LinearLayout

    private var step = 0
    private var isBinding = false

    private var selectedSize = SystemOptimizer.DisplaySizePreset.STANDARD_9_10
    private var selectedPortrait = false
    private var dpiBucket = 1 // 0 = small UI (low DPI), 1 = medium, 2 = large UI (high DPI)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        step = savedInstanceState?.getInt(KEY_STEP, 0) ?: 0

        flipper = findViewById(R.id.onb_flipper)
        backBtn = findViewById(R.id.onb_back)
        nextBtn = findViewById(R.id.onb_next)
        skipBtn = findViewById(R.id.onb_skip)
        stepper = findViewById(R.id.onb_stepper)

        selectedPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        selectedSize = estimateSizePreset()

        buildStepperDots()
        bindSteps()

        backBtn.setOnClickListener { if (step > 0) { step--; render() } }
        nextBtn.setOnClickListener { onNext() }
        skipBtn.setOnClickListener { onDoItLater() }

        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step)
    }

    override fun onResume() {
        super.onResume()
        // Reflect any changes made in deep-linked settings screens (theme, automation).
        updateThemeButtonText()
        updateNightButtonText()
    }

    private fun buildStepperDots() {
        stepper.removeAllViews()
        for (i in 0 until STEP_COUNT) {
            val dot = View(this)
            val h = (5 * resources.displayMetrics.density).toInt()
            val lp = LinearLayout.LayoutParams(h, h)
            lp.marginEnd = (6 * resources.displayMetrics.density).toInt()
            dot.layoutParams = lp
            stepper.addView(dot)
        }
    }

    private fun updateStepperDots() {
        val density = resources.displayMetrics.density
        val active = resolveAttrColor(com.google.android.material.R.attr.colorPrimary)
        val inactive = 0x33808080
        for (i in 0 until STEP_COUNT) {
            val dot = stepper.getChildAt(i) ?: continue
            val lp = dot.layoutParams as LinearLayout.LayoutParams
            lp.width = ((if (i == step) 26 else 8) * density).toInt()
            dot.layoutParams = lp
            val bg = android.graphics.drawable.GradientDrawable()
            bg.cornerRadius = 5 * density
            bg.setColor(if (i <= step) active else inactive)
            dot.background = bg
        }
    }

    private fun bindSteps() {
        // --- Welcome: language ---
        findViewById<MaterialButton>(R.id.onb_language_button).apply {
            text = currentLanguageLabel()
            setOnClickListener { showLanguageDialog() }
        }

        // --- Safety ---
        findViewById<TextView>(R.id.onb_safety_text).text =
            Html.fromHtml(getString(R.string.disclaimer_text))
        findViewById<MaterialCheckBox>(R.id.onb_safety_accept).apply {
            isChecked = settings.hasAcceptedDisclaimer
            setOnCheckedChangeListener { _, checked ->
                // Persist immediately so "Do it later" works once the terms are accepted.
                settings.hasAcceptedDisclaimer = checked
                if (step == STEP_SAFETY) nextBtn.isEnabled = checked
            }
        }

        // --- Connection: USB (cable or USB adapter) vs WiFi ---
        val connGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_conn_group)
        isBinding = true
        when (settings.primaryConnection) {
            Settings.ConnectionKind.WIFI, Settings.ConnectionKind.NATIVE_AA -> connGroup.check(R.id.onb_conn_wifi)
            Settings.ConnectionKind.USB_CABLE, Settings.ConnectionKind.USB_WIRELESS_ADAPTER -> connGroup.check(R.id.onb_conn_usb)
            else -> {}
        }
        isBinding = false
        updateConnectionDetail()
        connGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isBinding) return@addOnButtonCheckedListener
            settings.primaryConnection = if (checkedId == R.id.onb_conn_wifi)
                Settings.ConnectionKind.WIFI else Settings.ConnectionKind.USB_CABLE
            updateConnectionDetail()
        }

        // --- Display: detected panel + size/orientation, pre-selected from detection ---
        findViewById<TextView>(R.id.onb_display_detected).text = detectedDisplayText()
        val sizeGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_size_group)
        sizeGroup.check(sizeButtonId(selectedSize))
        sizeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            selectedSize = when (id) {
                R.id.onb_size_phone -> SystemOptimizer.DisplaySizePreset.PHONE_4_6
                R.id.onb_size_small -> SystemOptimizer.DisplaySizePreset.SMALL_7_8
                R.id.onb_size_large -> SystemOptimizer.DisplaySizePreset.LARGE_11_PLUS
                else -> SystemOptimizer.DisplaySizePreset.STANDARD_9_10
            }
        }
        val orientGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_orient_group)
        orientGroup.check(if (selectedPortrait) R.id.onb_orient_port else R.id.onb_orient_land)
        orientGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            selectedPortrait = id == R.id.onb_orient_port
        }

        // Graphical DPI picker: the preview shows smaller/more elements at a lower DPI
        // and larger elements at a higher DPI.
        val dpiPreview = findViewById<com.andrerinas.headunitrevived.view.DpiPreviewView>(R.id.onb_dpi_preview)
        val dpiGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_dpi_group)
        dpiGroup.check(dpiButtonId(dpiBucket))
        dpiPreview.setScale(dpiScale(dpiBucket))
        dpiGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            dpiBucket = when (id) {
                R.id.onb_dpi_small -> 0
                R.id.onb_dpi_large -> 2
                else -> 1
            }
            dpiPreview.setScale(dpiScale(dpiBucket))
        }

        // --- Appearance: full theme + night-mode pickers; more options live in Settings ---
        updateThemeButtonText()
        findViewById<MaterialButton>(R.id.onb_theme_button).setOnClickListener { showThemeDialog() }
        updateNightButtonText()
        findViewById<MaterialButton>(R.id.onb_night_button).setOnClickListener { showNightModeDialog() }

        // --- Automation: simple toggles inline, priority + advanced via the real screens ---
        findViewById<SwitchMaterial>(R.id.onb_autoconnect_last_switch).apply {
            isChecked = settings.autoConnectLastSession
            setOnCheckedChangeListener { _, v -> settings.autoConnectLastSession = v }
        }
        findViewById<SwitchMaterial>(R.id.onb_autoconnect_single_switch).apply {
            isChecked = settings.autoConnectSingleUsbDevice
            setOnCheckedChangeListener { _, v -> settings.autoConnectSingleUsbDevice = v }
        }
        // USB-only auto-start toggles (also written to device storage, like AutoStartFragment).
        findViewById<SwitchMaterial>(R.id.onb_autostart_usb_switch).apply {
            isChecked = settings.autoStartOnUsb
            setOnCheckedChangeListener { _, v ->
                settings.autoStartOnUsb = v
                Settings.syncAutoStartOnUsbToDeviceStorage(this@OnboardingActivity, v)
            }
        }
        findViewById<SwitchMaterial>(R.id.onb_reopen_switch).apply {
            isChecked = settings.reopenOnReconnection
            setOnCheckedChangeListener { _, v -> settings.reopenOnReconnection = v }
        }
        // WiFi-only auto-start toggle.
        findViewById<SwitchMaterial>(R.id.onb_autostart_wifi_switch).apply {
            isChecked = settings.autoStartOnWifi
            setOnCheckedChangeListener { _, v ->
                settings.autoStartOnWifi = v
                Settings.syncAutoStartOnWifiToDeviceStorage(this@OnboardingActivity, v)
            }
        }
        // --- Location: this device's GPS vs the connected phone's GPS ---
        val gpsGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_gps_group)
        isBinding = true
        gpsGroup.check(if (settings.useGpsForNavigation) R.id.onb_gps_device else R.id.onb_gps_phone)
        isBinding = false
        gpsGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isBinding) return@addOnButtonCheckedListener
            settings.useGpsForNavigation = checkedId == R.id.onb_gps_device
        }
    }

    private fun render() {
        flipper.displayedChild = step
        backBtn.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        // GONE (not INVISIBLE) so its dedicated row collapses on the final step.
        skipBtn.visibility = if (step == STEP_COUNT - 1) View.GONE else View.VISIBLE
        nextBtn.text = getString(if (step == STEP_COUNT - 1) R.string.onb_ready_finish else R.string.onb_next)
        nextBtn.isEnabled = if (step == STEP_SAFETY)
            findViewById<MaterialCheckBox>(R.id.onb_safety_accept).isChecked else true
        if (step == STEP_READY) findViewById<TextView>(R.id.onb_ready_summary).text = summaryText()
        if (step == STEP_AUTOMATION) applyAutomationVisibility()
        updateStepperDots()
    }

    /** Show only the auto-start toggles that match the chosen connection type. */
    private fun applyAutomationVisibility() {
        val isWifi = settings.primaryConnection == Settings.ConnectionKind.WIFI ||
            settings.primaryConnection == Settings.ConnectionKind.NATIVE_AA
        val usbVis = if (isWifi) View.GONE else View.VISIBLE
        findViewById<View>(R.id.onb_ac_single_row).visibility = usbVis
        findViewById<View>(R.id.onb_as_usb_row).visibility = usbVis
        findViewById<View>(R.id.onb_reopen_row).visibility = usbVis
        // Auto-start on WiFi is a legacy path that only applies up to Android 12L (API 32).
        findViewById<View>(R.id.onb_as_wifi_row).visibility =
            if (isWifi && Build.VERSION.SDK_INT <= 32) View.VISIBLE else View.GONE
    }

    private fun onNext() {
        if (step == STEP_SAFETY) settings.hasAcceptedDisclaimer = true
        // Apply the display recommendation + chosen DPI on Next, so the user does not
        // have to press "Apply recommended settings" for it to take effect.
        if (step == STEP_DISPLAY || step == STEP_DPI) applyDisplaySettings()
        if (step == STEP_COUNT - 1) finishOnboarding() else { step++; render() }
    }

    /**
     * "Do it later": close the wizard for now WITHOUT marking it complete, so it appears
     * again on the next app launch. The safety disclaimer is still mandatory, so if it has
     * not been accepted yet we route to that step first instead of leaving.
     */
    private fun onDoItLater() {
        if (!settings.hasAcceptedDisclaimer) {
            step = STEP_SAFETY
            render()
            return
        }
        deferredThisSession = true
        settings.commit()
        finish()
    }

    override fun onBackPressed() {
        // Back walks the steps; from the first step it behaves like "Do it later".
        if (step > 0) { step--; render() } else onDoItLater()
    }

    private fun finishOnboarding() {
        settings.hasAcceptedDisclaimer = true
        settings.hasCompletedSetupWizard = true
        settings.onboardingVersion = CURRENT_ONBOARDING_VERSION
        settings.commit()
        finish()
    }

    // --- Language ---

    private fun currentLanguageLabel(): String {
        val locale = LocaleHelper.stringToLocale(settings.appLanguage)
        return if (locale == null) getString(R.string.system_default)
        else LocaleHelper.getDisplayName(locale)
    }

    private fun showLanguageDialog() {
        val locales = LocaleHelper.getAvailableLocales(this)
        val labels = ArrayList<String>()
        labels.add(getString(R.string.system_default))
        locales.forEach { labels.add(LocaleHelper.getDisplayName(it)) }
        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.app_language)
            .setItems(labels.toTypedArray()) { _, which ->
                val newLang = if (which == 0) "" else LocaleHelper.localeToString(locales[which - 1])
                if (newLang != settings.appLanguage) {
                    settings.appLanguage = newLang
                    recreate()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Connection ---

    private fun updateConnectionDetail() {
        val detail = findViewById<TextView>(R.id.onb_conn_detail)
        detail.text = when (settings.primaryConnection) {
            Settings.ConnectionKind.WIFI, Settings.ConnectionKind.NATIVE_AA -> getString(R.string.onb_connection_wifi_detail)
            Settings.ConnectionKind.USB_CABLE, Settings.ConnectionKind.USB_WIRELESS_ADAPTER -> getString(R.string.onb_connection_usb_detail)
            else -> ""
        }
    }

    // --- Display scan ---

    private fun realMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.getRealMetrics(metrics)
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    /** Estimate the physical diagonal from real pixels and physical DPI, pick the closest preset. */
    private fun estimateSizePreset(): SystemOptimizer.DisplaySizePreset {
        val m = realMetrics()
        val xdpi = if (m.xdpi > 40f) m.xdpi else m.densityDpi.toFloat()
        val ydpi = if (m.ydpi > 40f) m.ydpi else m.densityDpi.toFloat()
        val wIn = m.widthPixels / xdpi
        val hIn = m.heightPixels / ydpi
        val diagonal = sqrt(wIn * wIn + hIn * hIn)
        return SystemOptimizer.DisplaySizePreset.values().minByOrNull {
            kotlin.math.abs(it.diagonalInch - diagonal)
        } ?: SystemOptimizer.DisplaySizePreset.STANDARD_9_10
    }

    private fun sizeButtonId(preset: SystemOptimizer.DisplaySizePreset): Int = when (preset) {
        SystemOptimizer.DisplaySizePreset.PHONE_4_6 -> R.id.onb_size_phone
        SystemOptimizer.DisplaySizePreset.SMALL_7_8 -> R.id.onb_size_small
        SystemOptimizer.DisplaySizePreset.LARGE_11_PLUS -> R.id.onb_size_large
        else -> R.id.onb_size_standard
    }

    private fun detectedDisplayText(): String {
        val m = realMetrics()
        val hevc = VideoDecoder.isHevcSupported()
        val codec = if (hevc) "H.265" else "H.264"
        return getString(R.string.onb_display_detected, m.widthPixels, m.heightPixels, m.densityDpi, codec)
    }

    private fun dpiButtonId(bucket: Int): Int = when (bucket) {
        0 -> R.id.onb_dpi_small
        2 -> R.id.onb_dpi_large
        else -> R.id.onb_dpi_medium
    }

    private fun dpiScale(bucket: Int): Float = when (bucket) {
        0 -> 0.8f
        2 -> 1.25f
        else -> 1.0f
    }

    /** Adjust the recommended DPI by the chosen size bucket, kept within sane bounds. */
    private fun adjustedDpi(base: Int): Int = when (dpiBucket) {
        0 -> (base * 0.85f).toInt()
        2 -> (base * 1.15f).toInt()
        else -> base
    }.coerceIn(110, 240)

    /** Writes the recommended display settings plus the chosen DPI. Called when leaving
     * the Display and Android Auto size steps, so no separate Apply tap is needed. */
    private fun applyDisplaySettings() {
        val result = SystemOptimizer(this).calculateOptimalSettings(selectedSize, selectedPortrait)
        settings.resolutionId = result.recommendedResolutionId
        settings.videoCodec = result.recommendedVideoCodec
        settings.viewMode = result.recommendedViewMode
        settings.dpiPixelDensity = adjustedDpi(result.recommendedDpi)
        settings.screenOrientation = result.suggestedOrientation
        settings.commit()
    }

    // --- Appearance (reuse the same option arrays as the settings screen) ---

    private fun updateThemeButtonText() {
        val labels = resources.getStringArray(R.array.app_theme)
        val idx = settings.appTheme.value.coerceIn(0, labels.size - 1)
        findViewById<MaterialButton>(R.id.onb_theme_button)?.text = labels[idx]
    }

    private fun updateNightButtonText() {
        val labels = resources.getStringArray(R.array.night_mode)
        val idx = settings.nightMode.value.coerceIn(0, labels.size - 1)
        findViewById<MaterialButton>(R.id.onb_night_button)?.text = labels[idx]
    }

    private fun showThemeDialog() {
        val labels = resources.getStringArray(R.array.app_theme)
        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.onb_appearance_theme_label)
            .setSingleChoiceItems(labels, settings.appTheme.value) { dialog, which ->
                val newTheme = Settings.AppTheme.values().firstOrNull { it.value == which }
                    ?: Settings.AppTheme.AUTOMATIC
                settings.appTheme = newTheme
                updateThemeButtonText()
                dialog.dismiss()
                AppThemeManager.applyStaticTheme(settings)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNightModeDialog() {
        val labels = resources.getStringArray(R.array.night_mode)
        MaterialAlertDialogBuilder(this, R.style.DarkAlertDialog)
            .setTitle(R.string.onb_appearance_night_label)
            .setSingleChoiceItems(labels, settings.nightMode.value) { dialog, which ->
                settings.nightMode = Settings.NightMode.fromInt(which) ?: Settings.NightMode.AUTO
                updateNightButtonText()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Ready ---

    private fun summaryText(): String {
        val conn = when (settings.primaryConnection) {
            Settings.ConnectionKind.WIFI, Settings.ConnectionKind.NATIVE_AA -> getString(R.string.connection_kind_wifi)
            Settings.ConnectionKind.USB_CABLE, Settings.ConnectionKind.USB_WIRELESS_ADAPTER -> getString(R.string.connection_kind_usb)
            else -> getString(R.string.connection_kind_unset)
        }
        val res = Settings.Resolution.fromId(settings.resolutionId)?.resName ?: getString(R.string.auto)
        return getString(R.string.onb_ready_summary, conn, res, settings.videoCodec)
    }

    private fun resolveAttrColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    companion object {
        const val CURRENT_ONBOARDING_VERSION = 2
        // Set when the user chooses "Do it later" so MainActivity does not immediately
        // relaunch the wizard this session. Resets on process restart (next app open).
        @Volatile
        var deferredThisSession = false
        private const val KEY_STEP = "onb_step"
        private const val STEP_COUNT = 9
        private const val STEP_SAFETY = 1
        private const val STEP_DISPLAY = 3
        private const val STEP_DPI = 4
        private const val STEP_AUTOMATION = 6
        private const val STEP_READY = 8
    }
}
