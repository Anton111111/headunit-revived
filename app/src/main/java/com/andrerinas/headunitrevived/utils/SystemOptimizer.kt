package com.andrerinas.headunitrevived.utils

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.sqrt

class SystemOptimizer(private val context: Context) {

    data class OptimizationResult(
        val recommendedResolutionId: Int,
        val recommendedDpi: Int,
        val recommendedVideoCodec: String,
        val recommendedViewMode: Settings.ViewMode,
        val isWidescreen: Boolean,
        val suggestedOrientation: Settings.ScreenOrientation,
        val h265Support: Boolean
    )

    enum class DisplaySizePreset(val diagonalInch: Float) {
        PHONE_4_6(6.0f),
        SMALL_7_8(7.5f),
        STANDARD_9_10(10.0f),
        LARGE_11_PLUS(12.5f)
    }

    fun checkH265HardwareSupport(): Boolean {
        return com.andrerinas.headunitrevived.decoder.VideoDecoder.isHevcSupported()
    }

    fun calculateOptimalSettings(
        sizePreset: DisplaySizePreset,
        isPortraitTarget: Boolean
    ): OptimizationResult {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.getRealMetrics(metrics)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }

        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val densityDpi = metrics.densityDpi
        val pixelDiagonal = sqrt(width * width + height * height)
        val aspectRatio = if (width > height) width / height else height / width
        
        val hasH265 = checkH265HardwareSupport()
        
        // 1. Resolution Recommendation
        val recResId = when {
            width >= 2560 && hasH265 -> 4
            // Only recommend 1080p for genuinely >=1920-wide panels. The old rule also fired on
            // densityDpi >= 320, which mis-set 1080p on small high-DPI head units and made them
            // downscale every frame (issue #650). Small panels now default to 720p.
            width >= 1920 -> 3
            else -> 2
        }

        // 2. DPI Strategy
        val calculatedDpi = (pixelDiagonal / sizePreset.diagonalInch) * 1.2f
        var recDpi = calculatedDpi.toInt()

        // 3. View Mode Recommendation
        val recViewMode = when {
            // Very old devices (Android 4.x) often have distortion issues with SurfaceView,
            // so we recommend TextureView instead.
            Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP -> Settings.ViewMode.TEXTURE

            // 5.0 - 8.1 used to get GLES here, but sampling the hardware decoder's external
            // texture in-app via GLES forces a per-frame color conversion on some SoCs (notably
            // MediaTek's MDP), which collapses playback to a few fps (issue #650). When the
            // device can decode video in hardware, a direct SurfaceView avoids that path. GLES
            // is only kept for devices without hardware HEVC, which may fall back to the software
            // YUV sink that GLES provides.
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1 ->
                if (hasH265) Settings.ViewMode.SURFACE else Settings.ViewMode.GLES

            // Modern devices are usually fine with the default TextureView
            else -> Settings.ViewMode.TEXTURE
        }
        AppLog.i("SystemOptimizer: SoC=${Build.HARDWARE}/${Build.BOARD} API=${Build.VERSION.SDK_INT} hasHwHevc=$hasH265 -> recommended viewMode=$recViewMode")

        // 4. Apply orientation-based caps
        if (isPortraitTarget) {
            recDpi = recDpi.coerceAtMost(190)
        } else {
            recDpi = recDpi.coerceAtMost(240)
        }

        recDpi = recDpi.coerceAtLeast(110)

        return OptimizationResult(
            recommendedResolutionId = recResId,
            recommendedDpi = recDpi,
            recommendedVideoCodec = if (hasH265) "H.265" else "H.264",
            recommendedViewMode = recViewMode,
            isWidescreen = aspectRatio > 1.7f,
            suggestedOrientation = if (isPortraitTarget) Settings.ScreenOrientation.PORTRAIT else Settings.ScreenOrientation.LANDSCAPE,
            h265Support = hasH265
        )
    }

    companion object {
        /**
         * The largest standard video resolution that physically fits the panel (no upscaling
         * waste). Compared in landscape terms (long side x short side). Falls back to 480p.
         */
        fun recommendedResolution(realWidthPx: Int, realHeightPx: Int): Settings.Resolution {
            val longSide = maxOf(realWidthPx, realHeightPx)
            val shortSide = minOf(realWidthPx, realHeightPx)
            if (longSide <= 0) return Settings.Resolution._1280x720
            return Settings.Resolution.allResolutions
                .filter { it.width in 1..longSide && it.height in 1..shortSide }
                .maxByOrNull { it.width }
                ?: Settings.Resolution._800x480
        }
    }
}
