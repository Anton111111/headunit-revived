package com.andrerinas.headunitrevived.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * A lightweight, schematic preview of the head-unit layout. It is NOT a real
 * Android Auto render; it draws a mock launcher (a grid of tiles plus a bottom
 * bar) whose element size scales with the chosen DPI so the user can see, at a
 * glance, that a lower DPI fits more/smaller elements and a higher DPI makes
 * everything larger.
 */
class DpiPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var scaleFactor = 1f

    private val accent = resolveAttrColor(com.google.android.material.R.attr.colorPrimary, Color.parseColor("#009688"))
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(accent, 60) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0x33808080
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(accent, 130) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    /** 0.8 = small UI (low DPI), 1.0 = medium, 1.25 = large UI (high DPI). */
    fun setScale(factor: Float) {
        if (factor != scaleFactor) {
            scaleFactor = factor
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = dp(6f)
        val frame = RectF(pad, pad, width - pad, height - pad)
        val radius = dp(10f)
        canvas.drawRoundRect(frame, radius, radius, strokePaint)

        val inset = dp(10f)
        val left = frame.left + inset
        val top = frame.top + inset
        val right = frame.right - inset
        val bottom = frame.bottom - inset

        // Bottom bar (mock navigation) with three buttons.
        val barHeight = (bottom - top) * 0.18f
        val barRect = RectF(left, bottom - barHeight, right, bottom)
        val barRadius = barHeight / 2f
        canvas.drawRoundRect(barRect, barRadius, barRadius, barPaint)
        val cy = barRect.centerY()
        val dotR = barHeight * 0.18f
        val spacing = (right - left) / 4f
        for (i in 1..3) {
            canvas.drawCircle(left + spacing * i, cy, dotR, dotPaint)
        }

        // Grid of tiles above the bar. Cell size grows with the DPI scale.
        val gridBottom = barRect.top - dp(8f)
        val gap = dp(6f)
        val cell = dp(28f) * scaleFactor
        if (cell <= 0f) return
        val cols = maxOf(1, (((right - left) + gap) / (cell + gap)).toInt())
        val rows = maxOf(1, (((gridBottom - top) + gap) / (cell + gap)).toInt())
        val tileRadius = dp(6f)
        var y = top
        for (r in 0 until rows) {
            var x = left
            for (c in 0 until cols) {
                if (x + cell > right + 0.5f) break
                val tile = RectF(x, y, x + cell, y + cell)
                canvas.drawRoundRect(tile, tileRadius, tileRadius, tilePaint)
                canvas.drawRoundRect(tile, tileRadius, tileRadius, strokePaint)
                x += cell + gap
            }
            y += cell + gap
            if (y + cell > gridBottom + 0.5f) break
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun resolveAttrColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }
}
