package com.andrerinas.headunitrevived.main

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.view.DpiPickerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Sub-screen to choose the Android Auto DPI with a slider, a live graphic and
 * Small/Medium/Large tabs. It does not save directly: it publishes the chosen value
 * (0 = Automatic) back to SettingsFragment via the nav result, so the existing
 * "Save (Reconnect needed)" flow applies it.
 */
class DpiSettingsFragment : Fragment() {

    private val settings by lazy { App.provide(requireContext()).settings }
    private lateinit var picker: DpiPickerView
    private lateinit var autoSwitch: SwitchMaterial

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_dpi_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { findNavController().navigateUp() }

        picker = view.findViewById(R.id.dpi_picker)
        autoSwitch = view.findViewById(R.id.dpi_auto_switch)

        val current = settings.dpiPixelDensity
        val auto = current == 0
        autoSwitch.isChecked = auto
        picker.dpi = if (auto) detectedDensityDpi() else current
        picker.setPickerEnabled(!auto)
        publish(if (auto) 0 else picker.dpi)

        autoSwitch.setOnCheckedChangeListener { _, checked ->
            picker.setPickerEnabled(!checked)
            publish(if (checked) 0 else picker.dpi)
        }
        picker.setOnDpiChanged { v ->
            if (!autoSwitch.isChecked) publish(v)
        }
    }

    /** Hand the chosen DPI (0 = Automatic) back to SettingsFragment. */
    private fun publish(value: Int) {
        findNavController().previousBackStackEntry?.savedStateHandle?.set(KEY_DPI_RESULT, value)
    }

    private fun detectedDensityDpi(): Int {
        val m = DisplayMetrics()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requireContext().display?.getRealMetrics(m)
            } else {
                @Suppress("DEPRECATION")
                (requireContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
            }
        } catch (_: Exception) {
        }
        return if (m.densityDpi > 0) m.densityDpi else 160
    }

    companion object {
        const val KEY_DPI_RESULT = "dpi_result"
    }
}
