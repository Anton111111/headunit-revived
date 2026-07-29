package com.andrerinas.headunitrevived.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import com.andrerinas.headunitrevived.R
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import kotlin.math.roundToInt

/**
 * Reusable DPI picker: a live [DpiPreviewView] graphic, a slider, an editable value field and
 * Small/Medium/Large tabs, all kept in sync. Used by the onboarding wizard and the DPI
 * settings sub-screen.
 *
 * The slider covers the full range of real device densities (up to [MAX]) so "Automatic"
 * values like 440 DPI are reachable. The text field lets the user type any exact value; the
 * tabs are handy presets and only light up when the current value matches one.
 */
class DpiPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private val preview: DpiPreviewView
    private val slider: Slider
    private val input: TextInputEditText
    private val tabs: MaterialButtonToggleGroup

    private var isBinding = false
    private var onDpiChanged: ((Int) -> Unit)? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_dpi_picker, this, true)
        preview = findViewById(R.id.dpi_preview)
        slider = findViewById(R.id.dpi_slider)
        input = findViewById(R.id.dpi_input)
        tabs = findViewById(R.id.dpi_tabs)

        slider.addOnChangeListener { _, value, _ ->
            if (!isBinding) applyDpi(value.toInt(), fromSlider = true)
        }
        tabs.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked && !isBinding) {
                val rep = when (checkedId) {
                    R.id.dpi_tab_small -> SMALL_REP
                    R.id.dpi_tab_large -> LARGE_REP
                    R.id.dpi_tab_huge -> HUGE_REP
                    else -> MEDIUM_REP
                }
                applyDpi(rep, fromSlider = false)
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { commitInput(); true } else false
        }
        input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitInput() }

        // Initialise the field/preview/tab from the slider's default value.
        applyDpi(slider.value.toInt(), fromSlider = true, silent = true)
    }

    var dpi: Int
        get() = slider.value.toInt()
        set(value) = applyDpi(value, fromSlider = false, silent = true)

    fun setOnDpiChanged(cb: (Int) -> Unit) { onDpiChanged = cb }

    fun setPanelResolution(widthPx: Int, heightPx: Int) = preview.setPanelResolution(widthPx, heightPx)

    fun setPickerEnabled(enabled: Boolean) {
        slider.isEnabled = enabled
        input.isEnabled = enabled
        for (i in 0 until tabs.childCount) tabs.getChildAt(i).isEnabled = enabled
        alpha = if (enabled) 1f else 0.5f
    }

    /** Parse the typed value, clamp it into range and apply; restore on invalid input. */
    private fun commitInput() {
        if (isBinding) return
        val typed = input.text?.toString()?.trim()?.toIntOrNull()
        if (typed == null) {
            isBinding = true
            input.setText(slider.value.toInt().toString())
            isBinding = false
            return
        }
        applyDpi(typed, fromSlider = false)
    }

    private fun applyDpi(raw: Int, fromSlider: Boolean, silent: Boolean = false) {
        val v = snap(raw)
        isBinding = true
        if (!fromSlider) slider.value = v.toFloat()
        tabs.check(tabFor(v))
        preview.setDpi(v)
        if (input.text?.toString() != v.toString()) input.setText(v.toString())
        isBinding = false
        if (!silent) onDpiChanged?.invoke(v)
    }

    private fun snap(value: Int): Int {
        val stepped = ((value - MIN) / STEP.toFloat()).roundToInt() * STEP + MIN
        return stepped.coerceIn(MIN, MAX)
    }

    /**
     * Classifies any value into a tab. Small covers everything below and Huge everything
     * above (so a high custom value like 440 reads as Huge); Medium and Large stay capped to
     * their narrow central bands.
     */
    private fun tabFor(v: Int): Int = when {
        v <= SMALL_MEDIUM_MAX -> R.id.dpi_tab_small
        v <= MEDIUM_LARGE_MAX -> R.id.dpi_tab_medium
        v <= LARGE_HUGE_MAX -> R.id.dpi_tab_large
        else -> R.id.dpi_tab_huge
    }

    companion object {
        private const val MIN = 110
        private const val MAX = 640
        private const val STEP = 2
        private const val SMALL_REP = 132
        private const val MEDIUM_REP = 175
        private const val LARGE_REP = 218
        private const val HUGE_REP = 320
        // Small covers everything below and Huge everything above; Medium and Large are
        // the narrow central bands. Boundaries are the midpoints between representatives.
        private const val SMALL_MEDIUM_MAX = (SMALL_REP + MEDIUM_REP) / 2   // 153
        private const val MEDIUM_LARGE_MAX = (MEDIUM_REP + LARGE_REP) / 2   // 196
        private const val LARGE_HUGE_MAX = (LARGE_REP + HUGE_REP) / 2       // 269
    }
}
