package com.andrerinas.headunitrevived.main

import android.content.Context
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorManager
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

/**
 * Intelligent, device-aware first-run wizard. Replaces the old
 * SafetyDisclaimerDialog + SetupWizard dialog chain with a single guided flow.
 *
 * Shown once to everyone (including upgraders) when the stored onboardingVersion
 * is older than CURRENT_ONBOARDING_VERSION, and re-launchable from Settings.
 *
 * Steps: Welcome(+language) / Safety(mandatory) / Connection / Display scan /
 * Appearance / Ready.
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

        buildStepperDots()
        bindSteps()

        backBtn.setOnClickListener { if (step > 0) { step--; render() } }
        nextBtn.setOnClickListener { onNext() }
        skipBtn.setOnClickListener { onSkip() }

        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, step)
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
        // Welcome: language picker
        findViewById<MaterialButton>(R.id.onb_language_button).apply {
            text = currentLanguageLabel()
            setOnClickListener { showLanguageDialog() }
        }

        // Safety: disclaimer text + accept checkbox
        findViewById<TextView>(R.id.onb_safety_text).text =
            Html.fromHtml(getString(R.string.disclaimer_text))
        findViewById<MaterialCheckBox>(R.id.onb_safety_accept).apply {
            isChecked = settings.hasAcceptedDisclaimer
            setOnCheckedChangeListener { _, checked ->
                if (step == STEP_SAFETY) nextBtn.isEnabled = checked
            }
        }

        // Connection: sets primaryConnection, updates the detail hint
        val connGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_conn_group)
        isBinding = true
        when (settings.primaryConnection) {
            Settings.ConnectionKind.USB_CABLE -> connGroup.check(R.id.onb_conn_cable)
            Settings.ConnectionKind.USB_WIRELESS_ADAPTER -> connGroup.check(R.id.onb_conn_adapter)
            Settings.ConnectionKind.WIFI, Settings.ConnectionKind.NATIVE_AA -> connGroup.check(R.id.onb_conn_wifi)
            else -> {}
        }
        isBinding = false
        updateConnectionDetail()
        connGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isBinding) return@addOnButtonCheckedListener
            settings.primaryConnection = when (checkedId) {
                R.id.onb_conn_cable -> Settings.ConnectionKind.USB_CABLE
                R.id.onb_conn_adapter -> Settings.ConnectionKind.USB_WIRELESS_ADAPTER
                else -> Settings.ConnectionKind.WIFI
            }
            updateConnectionDetail()
        }

        // Display: show detected panel info, size + orientation pickers, apply button
        findViewById<TextView>(R.id.onb_display_detected).text = detectedDisplayText()
        val sizeGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_size_group)
        sizeGroup.check(R.id.onb_size_standard)
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
        findViewById<MaterialButton>(R.id.onb_display_run).setOnClickListener { runOptimization() }

        // Appearance: app theme (live) + Android Auto night mode
        val themeGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_theme_group)
        isBinding = true
        themeGroup.check(
            when (settings.appTheme) {
                Settings.AppTheme.CLEAR -> R.id.onb_theme_light
                Settings.AppTheme.DARK, Settings.AppTheme.EXTREME_DARK -> R.id.onb_theme_dark
                else -> R.id.onb_theme_auto
            }
        )
        isBinding = false
        themeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isBinding) return@addOnButtonCheckedListener
            val newTheme = when (checkedId) {
                R.id.onb_theme_light -> Settings.AppTheme.CLEAR
                R.id.onb_theme_dark -> Settings.AppTheme.DARK
                else -> Settings.AppTheme.AUTOMATIC
            }
            if (newTheme != settings.appTheme) {
                settings.appTheme = newTheme
                AppThemeManager.applyStaticTheme(settings)
            }
        }

        findViewById<TextView>(R.id.onb_night_recommendation).text = nightRecommendationText()
        val nightGroup = findViewById<MaterialButtonToggleGroup>(R.id.onb_night_group)
        isBinding = true
        nightGroup.check(
            when (settings.nightMode) {
                Settings.NightMode.DAY -> R.id.onb_night_day
                Settings.NightMode.NIGHT -> R.id.onb_night_night
                else -> R.id.onb_night_auto
            }
        )
        isBinding = false
        nightGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isBinding) return@addOnButtonCheckedListener
            settings.nightMode = when (checkedId) {
                R.id.onb_night_day -> Settings.NightMode.DAY
                R.id.onb_night_night -> Settings.NightMode.NIGHT
                else -> if (hasLightSensor()) Settings.NightMode.LIGHT_SENSOR else Settings.NightMode.AUTO
            }
        }
    }

    private fun render() {
        flipper.displayedChild = step
        backBtn.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        skipBtn.visibility = if (step == STEP_COUNT - 1) View.INVISIBLE else View.VISIBLE
        nextBtn.text = getString(if (step == STEP_COUNT - 1) R.string.onb_ready_finish else R.string.onb_next)
        nextBtn.isEnabled = if (step == STEP_SAFETY)
            findViewById<MaterialCheckBox>(R.id.onb_safety_accept).isChecked else true
        if (step == STEP_READY) findViewById<TextView>(R.id.onb_ready_summary).text = summaryText()
        updateStepperDots()
    }

    private fun onNext() {
        if (step == STEP_SAFETY) settings.hasAcceptedDisclaimer = true
        if (step == STEP_COUNT - 1) finishOnboarding() else { step++; render() }
    }

    private fun onSkip() {
        // Safety is mandatory: skipping still requires accepting the terms.
        if (!settings.hasAcceptedDisclaimer) {
            step = STEP_SAFETY
            render()
            return
        }
        finishOnboarding()
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
            Settings.ConnectionKind.USB_CABLE -> getString(R.string.onb_connection_cable_detail)
            Settings.ConnectionKind.USB_WIRELESS_ADAPTER -> getString(R.string.onb_connection_adapter_detail)
            Settings.ConnectionKind.WIFI, Settings.ConnectionKind.NATIVE_AA -> getString(R.string.onb_connection_wifi_detail)
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

    private fun detectedDisplayText(): String {
        val m = realMetrics()
        val hevc = VideoDecoder.isHevcSupported()
        val codec = if (hevc) "H.265" else "H.264"
        return getString(R.string.onb_display_detected, m.widthPixels, m.heightPixels, m.densityDpi, codec)
    }

    private fun runOptimization() {
        val result = SystemOptimizer(this).calculateOptimalSettings(selectedSize, selectedPortrait)
        settings.resolutionId = result.recommendedResolutionId
        settings.videoCodec = result.recommendedVideoCodec
        settings.viewMode = result.recommendedViewMode
        settings.dpiPixelDensity = result.recommendedDpi
        settings.screenOrientation = result.suggestedOrientation
        settings.commit()
        findViewById<TextView>(R.id.onb_display_result).apply {
            visibility = View.VISIBLE
            text = getString(
                R.string.onb_display_result,
                Settings.Resolution.fromId(result.recommendedResolutionId)?.resName ?: "-",
                result.recommendedDpi,
                result.recommendedVideoCodec,
                result.recommendedViewMode.name
            )
        }
    }

    // --- Appearance ---

    private fun hasLightSensor(): Boolean {
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        return sm?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
    }

    private fun nightRecommendationText(): String =
        getString(if (hasLightSensor()) R.string.onb_night_light_sensor_available else R.string.onb_night_no_light_sensor)

    private fun summaryText(): String {
        val conn = when (settings.primaryConnection) {
            Settings.ConnectionKind.USB_CABLE -> getString(R.string.connection_kind_usb_cable)
            Settings.ConnectionKind.USB_WIRELESS_ADAPTER -> getString(R.string.connection_kind_usb_wireless_adapter)
            Settings.ConnectionKind.WIFI, Settings.ConnectionKind.NATIVE_AA -> getString(R.string.connection_kind_wifi)
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
        private const val KEY_STEP = "onb_step"
        private const val STEP_COUNT = 6
        private const val STEP_SAFETY = 1
        private const val STEP_READY = 5
    }
}
