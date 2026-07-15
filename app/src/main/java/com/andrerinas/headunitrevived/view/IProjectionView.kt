package com.andrerinas.headunitrevived.view

import android.view.Surface

interface IProjectionView {
    interface Callbacks {
        fun onSurfaceCreated(surface: Surface)
        fun onSurfaceDestroyed(surface: Surface)
        fun onSurfaceChanged(surface: Surface, width: Int, height: Int)
    }

    fun addCallback(callback: Callbacks)
    fun removeCallback(callback: Callbacks)
    fun setVideoSize(width: Int, height: Int)
    fun setVideoScale(scaleX: Float, scaleY: Float)

    /**
     * elapsedRealtime() of the last frame this view's display consumer actually drew,
     * or 0 if none yet. Returns -1 when the backend cannot report it (a plain SurfaceView
     * is composited by SurfaceFlinger with no draw callback). The projection watchdog uses
     * this to detect a frozen display while the decoder keeps producing frames (issue #650):
     * decoder still releasing frames but consumer not drawing means the picture is stuck.
     */
    fun lastFrameDrawnMs(): Long
}
