package com.andrerinas.headunitrevived.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.andrerinas.headunitrevived.R
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * Reusable DPI picker: a live [DpiPreviewView] graphic, a value label, a slider and
 * Small/Medium/Large tabs, all kept in sync both ways. Used by the onboarding wizard and
 * the DPI settings sub-screen.
 */
class DpiPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private val preview: DpiPreviewView
    private val valueText: TextView
    private val slider: Slider
    private val tabs: MaterialButtonToggleGroup

    private var isBinding = false
    private var onDpiChanged: ((Int) -> Unit)? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_dpi_picker, this, true)
        preview = findViewById(R.id.dpi_preview)
        valueText = findViewById(R.id.dpi_value)
        slider = findViewById(R.id.dpi_slider)
        tabs = findViewById(R.id.dpi_tabs)

        slider.addOnChangeListener { _, value, _ ->
            if (!isBinding) applyDpi(value.toInt(), fromSlider = true)
        }
        tabs.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked && !isBinding) {
                val rep = when (checkedId) {
                    R.id.dpi_tab_small -> SMALL_REP
                    R.id.dpi_tab_large -> LARGE_REP
                    else -> MEDIUM_REP
                }
                applyDpi(rep, fromSlider = false)
            }
        }

        // Initialise the label/preview/tab from the slider's default value.
        applyDpi(slider.value.toInt(), fromSlider = true, silent = true)
    }

    var dpi: Int
        get() = slider.value.toInt()
        set(value) = applyDpi(value, fromSlider = false, silent = true)

    fun setOnDpiChanged(cb: (Int) -> Unit) { onDpiChanged = cb }

    fun setPanelResolution(widthPx: Int, heightPx: Int) = preview.setPanelResolution(widthPx, heightPx)

    fun setPickerEnabled(enabled: Boolean) {
        slider.isEnabled = enabled
        for (i in 0 until tabs.childCount) tabs.getChildAt(i).isEnabled = enabled
        alpha = if (enabled) 1f else 0.5f
    }

    private fun applyDpi(raw: Int, fromSlider: Boolean, silent: Boolean = false) {
        val v = snap(raw)
        isBinding = true
        if (!fromSlider) slider.value = v.toFloat()
        tabs.check(tabFor(v))
        preview.setDpi(v)
        valueText.text = context.getString(R.string.dpi_value_format, v)
        isBinding = false
        if (!silent) onDpiChanged?.invoke(v)
    }

    private fun snap(value: Int): Int {
        val stepped = ((value - MIN) / STEP.toFloat()).roundToInt() * STEP + MIN
        return stepped.coerceIn(MIN, MAX)
    }

    private fun tabFor(v: Int): Int = when {
        v < MIN + (MAX - MIN) / 3 -> R.id.dpi_tab_small
        v < MIN + 2 * (MAX - MIN) / 3 -> R.id.dpi_tab_medium
        else -> R.id.dpi_tab_large
    }

    companion object {
        private const val MIN = 110
        private const val MAX = 240
        private const val STEP = 2
        private const val SMALL_REP = 132
        private const val MEDIUM_REP = 175
        private const val LARGE_REP = 218
    }
}
