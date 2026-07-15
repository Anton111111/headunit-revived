package com.andrerinas.headunitrevived.view

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import com.andrerinas.headunitrevived.utils.AppLog

class TextureProjectionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr), IProjectionView, TextureView.SurfaceTextureListener {

    private val callbacks = mutableListOf<IProjectionView.Callbacks>()
    private var surface: Surface? = null

    private var videoWidth = 0
    private var videoHeight = 0

    // elapsedRealtime() of the last frame the TextureView actually displayed. Read by the
    // projection watchdog to detect a stalled display consumer (issue #650).
    @Volatile
    private var lastFrameDrawnMsValue: Long = 0L

    init {
        surfaceTextureListener = this
    }

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    override fun setVideoSize(width: Int, height: Int) {
        if (videoWidth == width && videoHeight == height) return
        AppLog.i("TextureProjectionView", "Video size set to ${width}x$height")
        videoWidth = width
        videoHeight = height
        ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
    }

    override fun setVideoScale(scaleX: Float, scaleY: Float) {
        this.scaleX = scaleX
        this.scaleY = scaleY
        this.translationX = 0f
        this.translationY = 0f
    }

    // ----------------------------------------------------------------
    // Lifecycle & SurfaceTextureListener
    // ----------------------------------------------------------------

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        AppLog.i("TextureProjectionView: Surface available: ${width}x$height")
        surface = Surface(surfaceTexture)
        surface?.let {
            callbacks.forEach { cb -> cb.onSurfaceCreated(it) }
            // The width and height of the view are passed here, but the decoder should
            // use the actual video dimensions it parses from the SPS.
            callbacks.forEach { cb -> cb.onSurfaceChanged(it, width, height) }
        }
        ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        AppLog.i("TextureProjectionView: Surface size changed: ${width}x$height")
        ProjectionViewScaler.updateScale(this, videoWidth, videoHeight)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        AppLog.i("TextureProjectionView: Surface destroyed")
        surface?.let {
            callbacks.forEach { cb -> cb.onSurfaceDestroyed(it) }
        }
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        // Fired each time a new frame is displayed. Used as the display-consumer
        // liveness signal for stall detection (issue #650).
        lastFrameDrawnMsValue = SystemClock.elapsedRealtime()
    }

    override fun lastFrameDrawnMs(): Long = lastFrameDrawnMsValue

    // ----------------------------------------------------------------
    // Callbacks
    // ----------------------------------------------------------------

    override fun addCallback(callback: IProjectionView.Callbacks) {
        callbacks.add(callback)
        // If surface is already available, notify immediately.
        surface?.let {
            callback.onSurfaceCreated(it)
            callback.onSurfaceChanged(it, width, height)
        }
    }

    override fun removeCallback(callback: IProjectionView.Callbacks) {
        callbacks.remove(callback)
    }
}
