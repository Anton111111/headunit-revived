package com.andrerinas.headunitrevived.utils

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.andrerinas.headunitrevived.utils.AppLog

object SystemUI {

    fun apply(window: Window, root: View, mode: Settings.FullscreenMode, onInsetsChanged: (() -> Unit)? = null) {
        // Always keep screen on for Headunit functionality
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val params = window.attributes
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = params
        }

        val controllerCompat = WindowInsetsControllerCompat(window, window.decorView)

        // Handle Immersive Mode using modern Compat API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

        when (mode) {
            Settings.FullscreenMode.IMMERSIVE, Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH -> {
                controllerCompat.hide(WindowInsetsCompat.Type.systemBars())
                controllerCompat.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            Settings.FullscreenMode.STATUS_ONLY -> {
                controllerCompat.hide(WindowInsetsCompat.Type.statusBars())
                controllerCompat.show(WindowInsetsCompat.Type.navigationBars())
                controllerCompat.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            Settings.FullscreenMode.NONE -> {
                controllerCompat.show(WindowInsetsCompat.Type.systemBars())
            }
        }

        // Legacy Flags (Layout stability & Fallback for hide)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            @Suppress("DEPRECATION")
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or 
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or 
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            
            when (mode) {
                Settings.FullscreenMode.IMMERSIVE, Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH -> {
                    flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    } else {
                        // Older than KitKat: use FLAG_FULLSCREEN for reliability
                        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                        flags = flags or View.SYSTEM_UI_FLAG_LOW_PROFILE
                    }
                }
                Settings.FullscreenMode.STATUS_ONLY -> {
                    flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                        flags = flags or View.SYSTEM_UI_FLAG_LOW_PROFILE
                    }
                }
                Settings.FullscreenMode.NONE -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    }
                }
            }
            
            window.decorView.systemUiVisibility = flags
        }

        // Fix for Non-Immersive: Force black bars on older devices
        if (mode != Settings.FullscreenMode.IMMERSIVE && mode != Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                if (Build.VERSION.SDK_INT < 35) {
                    window.statusBarColor = Color.BLACK
                    if (mode == Settings.FullscreenMode.NONE || mode == Settings.FullscreenMode.STATUS_ONLY) {
                        window.navigationBarColor = Color.BLACK
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                if (mode == Settings.FullscreenMode.NONE || mode == Settings.FullscreenMode.STATUS_ONLY) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                }
            }
            controllerCompat.isAppearanceLightStatusBars = false
            controllerCompat.isAppearanceLightNavigationBars = false
        }

        val settings = Settings(root.context)

        // IMMEDIATE APPLICATION
        val manualL = settings.insetLeft
        val manualT = settings.insetTop
        val manualR = settings.insetRight
        val manualB = settings.insetBottom

        root.setPadding(manualL, manualT, manualR, manualB)
        HeadUnitScreenConfig.updateInsets(manualL, manualT, manualR, manualB)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // Legacy Fallback for Android 4.x
            root.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val rect = android.graphics.Rect()
                    window.decorView.getWindowVisibleDisplayFrame(rect)
                    
                    @Suppress("DEPRECATION")
                    val display = window.windowManager.defaultDisplay
                    val size = android.graphics.Point()
                    display.getSize(size)
                    
                    val insetT = rect.top
                    val insetB = size.y - rect.bottom
                    val insetL = rect.left
                    val insetR = size.x - rect.right
                    
                    AppLog.d("[UI_DEBUG] Legacy SystemUI: Detected Insets L$insetL T$insetT R$insetR B$insetB")
                    HeadUnitScreenConfig.updateInsets(manualL + insetL, manualT + insetT, manualR + insetR, manualB + insetB)
                    onInsetsChanged?.invoke()
                    root.viewTreeObserver.removeGlobalOnLayoutListener(this)
                }
            })
        }

        // Set up listener for dynamic system bars (API 21+)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insetsCompat ->
            var typeMask = 0
            
            when (mode) {
                Settings.FullscreenMode.IMMERSIVE -> {
                    typeMask = 0 // Standard Immersive: Overlay everything (Notch included)
                }
                Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH -> {
                    // This is now the "Avoid Notch" mode (ID 3)
                    if (Build.VERSION.SDK_INT >= 28) {
                        typeMask = WindowInsetsCompat.Type.displayCutout()
                    }
                }
                Settings.FullscreenMode.STATUS_ONLY -> {
                    typeMask = WindowInsetsCompat.Type.navigationBars()
                    if (Build.VERSION.SDK_INT >= 28) {
                        typeMask = typeMask or WindowInsetsCompat.Type.displayCutout()
                    }
                }
                else -> {
                    typeMask = WindowInsetsCompat.Type.systemBars()
                    if (Build.VERSION.SDK_INT >= 28) {
                        typeMask = typeMask or WindowInsetsCompat.Type.displayCutout()
                    }
                }
            }

            val bars = if (typeMask != 0) {
                insetsCompat.getInsets(typeMask)
            } else {
                androidx.core.graphics.Insets.NONE
            }
            
            v.setPadding(bars.left + manualL, bars.top + manualT, bars.right + manualR, bars.bottom + manualB)
            HeadUnitScreenConfig.updateInsets(bars.left + manualL, bars.top + manualT, bars.right + manualR, bars.bottom + manualB)
            
            onInsetsChanged?.invoke()
            WindowInsetsCompat.CONSUMED
        }

        ViewCompat.requestApplyInsets(root)
        root.requestLayout()
    }
}
