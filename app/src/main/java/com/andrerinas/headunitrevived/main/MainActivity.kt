package com.andrerinas.headunitrevived.main

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.app.BaseActivity
import com.andrerinas.headunitrevived.connection.CommManager
import com.andrerinas.headunitrevived.utils.AppLog
import android.content.res.Configuration
import com.andrerinas.headunitrevived.utils.Settings
import android.os.SystemClock
import com.andrerinas.headunitrevived.utils.SetupWizard
import com.andrerinas.headunitrevived.utils.SystemUI
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : BaseActivity() {

    private var lastBackPressTime: Long = 0
    var keyListener: KeyListener? = null

    private var isOrientationReceiverRegistered = false
    private var isFinishReceiverRegistered = false

    private val viewModel: MainViewModel by viewModels()

    private var autoConnectWatchdog: Job? = null
    private var autoConnectKenBurnsAnim: ObjectAnimator? = null
    private var hasAdvancedToActiveState = false
    /**
     * Optional override for the loading-screen status text (e.g. "Connecting to
     * Pixel 8…" from the Nearby selector). When `null`, the default
     * `R.string.android_auto_starting` is used. Stored on the activity so the
     * value survives the show/recreate cycle as long as the static
     * [autoConnectInProgress] flag is set.
     */
    private var autoConnectStatusText: String? = null

    private val finishReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == "com.andrerinas.headunitrevived.ACTION_FINISH_ACTIVITIES") {
                AppLog.i("MainActivity: Received finish request. Closing.")
                finishAffinity()
            }
        }
    }

    interface KeyListener {
        fun onKeyEvent(event: KeyEvent?): Boolean
    }

    private val orientationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AapService.ACTION_ORIENTATION_CHANGED) {
                AppLog.i("MainActivity: Orientation change broadcast received. Updating.")
                requestedOrientation = Settings(this@MainActivity).screenOrientation.androidOrientation
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logLaunchSource()

        // If an Android Auto session is active, bring the projection activity to front
        if (App.provide(this).commManager.isConnected && !App.isPiPActive) {
            AppLog.i("MainActivity: Active session detected in onCreate, bringing projection to front")
            val aapIntent = AapProjectionActivity.intent(this).apply {
                putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(aapIntent)
            
            // If we are auto-forwarding, hide the splash immediately to avoid flashing it twice
            if (savedInstanceState == null) {
                findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE
            }
        }

        setTheme(R.style.AppTheme)
        val mainSettings = Settings(this)
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (mainSettings.appTheme == Settings.AppTheme.EXTREME_DARK ||
            (mainSettings.useExtremeDarkMode && isNightActive)) {
            theme.applyStyle(R.style.ThemeOverlay_ExtremeDark, true)
        } else if (mainSettings.useGradientBackground) {
            theme.applyStyle(R.style.ThemeOverlay_GradientBackground, true)
        }
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val appSettings = Settings(this)
        requestedOrientation = appSettings.screenOrientation.androidOrientation

        // Sync UsbAttachedActivity component state with the listen for USB devices setting.
        // This covers first install, app updates (manifest may reset component state),
        // and ensures the USB system modal only appears when the user has opted in to listen for ALL USB devices.
        lifecycleScope.launch(Dispatchers.IO) {
            Settings.setUsbAttachedActivityEnabled(applicationContext, appSettings.listenForUsbDevices)
        }

        // Start main service immediately to handle connections and wireless server
        val serviceIntent = Intent(this, AapService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        setFullscreen()

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.main_content) as androidx.navigation.fragment.NavHostFragment
        val navController = navHostFragment.navController

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navController.navigateUp()) {
                    return
                } else if (System.currentTimeMillis() - lastBackPressTime < 2000) {
                    finish()
                } else {
                    lastBackPressTime = System.currentTimeMillis()
                    Toast.makeText(this@MainActivity, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show()
                }
            }
        })

        if (savedInstanceState == null) {
            val elapsedSinceStart = SystemClock.elapsedRealtime() - App.appStartTime
            val targetTotalDuration = 1200L
            val actualDelay = (targetTotalDuration - elapsedSinceStart).coerceAtLeast(0L)
            
            showSplashWithDelay(actualDelay)
        } else {
            findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE
        }

        requestPermissions()
        viewModel.register()
        handleLaunchIntent(intent)
        setupWifiDirectInfo()

        ContextCompat.registerReceiver(
            this, finishReceiver,
            android.content.IntentFilter("com.andrerinas.headunitrevived.ACTION_FINISH_ACTIVITIES"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isFinishReceiverRegistered = true

        // USB auto-attach is the one auto-connect path that pre-launches MainActivity
        // before any state transition occurs. HomeFragment-driven auto-connects will
        // call beginAutoConnect() directly from their entry points.
        if (savedInstanceState == null &&
            intent?.getStringExtra(EXTRA_LAUNCH_SOURCE) == "USB auto-start") {
            beginAutoConnect("USB auto-start")
        }

        observeConnectionStateForOverlay()
    }

    /**
     * Mark that an automatic connection attempt has started and surface the loading
     * overlay over the home screen. Called from HomeFragment auto-connect paths and
     * from MainActivity itself when launched via USB auto-attach.
     *
     * @param reason Diagnostic label written to the log.
     * @param customStatusText Optional override for the loading-screen status text.
     *        If provided and the user has the show-text option enabled, this string
     *        is shown instead of the generic "Android Auto is starting…". Used by
     *        the Nearby selector to surface the picked device name.
     */
    @JvmOverloads
    fun beginAutoConnect(reason: String, customStatusText: String? = null) {
        if (autoConnectInProgress) return
        // If we are already past the connection phase, no overlay is needed.
        if (App.provide(this).commManager.isConnected) return
        AppLog.i("Auto-connect overlay: begin ($reason)")
        autoConnectInProgress = true
        hasAdvancedToActiveState = false
        autoConnectStatusText = customStatusText
        // Hand the status text off to AapProjectionActivity so its own loading
        // screen continues to show the same context-specific label after the
        // handshake completes and AAP takes over the UI. AAP reads and clears
        // this on its first launch; we always overwrite it here (even with
        // null) so a stale value from a prior attempt can't leak across.
        AapProjectionActivity.pendingStatusText = customStatusText
        showAutoConnectOverlay()
    }

    /**
     * Subscribes to [CommManager.connectionState] to drive the auto-connect overlay.
     *
     * Show: when the overlay is requested via [beginAutoConnect] and the connection
     * advances out of the initial Disconnected state.
     *
     * Hide: on terminal states (HandshakeComplete/TransportStarted = success — AAP
     * projection activity will take over with its own overlay; Error/Disconnected =
     * failure — drop back to HomeFragment so the user can intervene).
     */
    private fun observeConnectionStateForOverlay() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                App.provide(this@MainActivity).commManager.connectionState.collect { state ->
                    when (state) {
                        is CommManager.ConnectionState.Connecting,
                        is CommManager.ConnectionState.Connected,
                        is CommManager.ConnectionState.StartingTransport -> {
                            hasAdvancedToActiveState = true
                            // The overlay should already be visible (set when auto-connect
                            // was requested); ensure it is in case the request raced with
                            // setContentView.
                            if (autoConnectInProgress) {
                                showAutoConnectOverlay()
                            }
                        }
                        is CommManager.ConnectionState.HandshakeComplete,
                        is CommManager.ConnectionState.TransportStarted -> {
                            // AapProjectionActivity is launching (HandshakeComplete) or
                            // has launched (TransportStarted). Hide our overlay so we
                            // don't keep video/animation resources alive while AAP
                            // covers us.
                            if (autoConnectInProgress) {
                                AppLog.i("Auto-connect overlay: handshake complete, handing off to projection")
                                endAutoConnect(success = true)
                            }
                        }
                        is CommManager.ConnectionState.Error -> {
                            if (autoConnectInProgress) {
                                AppLog.w("Auto-connect overlay: connection error: ${state.message}")
                                endAutoConnect(success = false)
                            }
                        }
                        is CommManager.ConnectionState.Disconnected -> {
                            // Initial Disconnected on app launch is normal; only treat
                            // as failure if we previously advanced to an active state.
                            if (autoConnectInProgress && hasAdvancedToActiveState) {
                                AppLog.w("Auto-connect overlay: disconnected mid-attempt")
                                endAutoConnect(success = false)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun endAutoConnect(success: Boolean) {
        autoConnectWatchdog?.cancel()
        autoConnectWatchdog = null
        autoConnectInProgress = false
        hasAdvancedToActiveState = false
        autoConnectStatusText = null
        if (!success) {
            // Failure path: AAP is not going to launch, so clear the handover
            // value so it can't appear on a later, unrelated connection. On
            // success we leave it alone — AAP either already consumed it in
            // onCreate or is about to.
            AapProjectionActivity.pendingStatusText = null
        }
        if (success) {
            // On success the projection activity will cover our overlay almost
            // immediately. Hiding without an animation avoids the fade competing
            // with the activity transition.
            findViewById<View>(R.id.auto_connect_loading_overlay)?.visibility = View.GONE
            stopAutoConnectVideo()
            autoConnectKenBurnsAnim?.cancel()
            autoConnectKenBurnsAnim = null
        } else {
            hideAutoConnectOverlay()
        }
    }

    private fun startAutoConnectWatchdog() {
        autoConnectWatchdog?.cancel()
        autoConnectWatchdog = lifecycleScope.launch {
            delay(AUTO_CONNECT_WATCHDOG_MS)
            if (autoConnectInProgress) {
                AppLog.w("Auto-connect overlay: watchdog timeout, hiding")
                endAutoConnect(success = false)
            }
        }
    }

    private fun showAutoConnectOverlay() {
        // Watchdog is owned by this activity's lifecycleScope, so it must be
        // (re)started here to survive configuration changes that recreate the
        // activity while autoConnectInProgress remains true.
        if (autoConnectWatchdog?.isActive != true) {
            startAutoConnectWatchdog()
        }

        val overlay = findViewById<View>(R.id.auto_connect_loading_overlay) ?: return
        if (overlay.visibility == View.VISIBLE) return

        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        // Once the auto-connect overlay is up there is no point keeping the
        // launch splash around — they would just stack the same dark background.
        findViewById<View>(R.id.splash_overlay)?.visibility = View.GONE

        setupAutoConnectMedia()
    }

    private fun setupAutoConnectMedia() {
        val settings = App.provide(this).settings
        val mediaPath = settings.loadingScreenMediaPath
        val mediaType = settings.loadingScreenMediaType

        val defaultContent = findViewById<View>(R.id.auto_connect_loading_default_content)
        val customTextOverlay = findViewById<View>(R.id.auto_connect_loading_custom_text_overlay)
        val customImage = findViewById<ImageView>(R.id.auto_connect_loading_custom_image)
        val customVideo = findViewById<VideoView>(R.id.auto_connect_loading_custom_video)
        val overlay = findViewById<View>(R.id.auto_connect_loading_overlay)

        // Apply the optional custom status text (e.g. "Connecting to Pixel 8…").
        // Falls back to the default "Android Auto is starting…" when no override
        // is set. Both the default and custom-media text overlays share the same
        // string so the wording stays consistent regardless of media presence.
        val statusText = autoConnectStatusText ?: getString(R.string.android_auto_starting)
        findViewById<android.widget.TextView>(R.id.auto_connect_loading_default_text)?.text = statusText
        findViewById<android.widget.TextView>(R.id.auto_connect_loading_custom_text)?.text = statusText

        if (mediaPath.isEmpty() || mediaType.isEmpty()) {
            // No custom media — show default text + spinner over the dark backdrop.
            defaultContent?.visibility = View.VISIBLE
            customTextOverlay?.visibility = View.GONE
            customImage?.visibility = View.GONE
            customVideo?.visibility = View.GONE
            overlay?.setBackgroundColor(Color.parseColor("#CC000000"))
            return
        }

        val file = File(mediaPath)
        if (!file.exists()) {
            // Stored path is stale — reset and fall back to default.
            settings.loadingScreenMediaPath = ""
            settings.loadingScreenMediaType = ""
            defaultContent?.visibility = View.VISIBLE
            customTextOverlay?.visibility = View.GONE
            customImage?.visibility = View.GONE
            customVideo?.visibility = View.GONE
            overlay?.setBackgroundColor(Color.parseColor("#CC000000"))
            return
        }

        defaultContent?.visibility = View.GONE
        overlay?.setBackgroundColor(Color.BLACK)

        if (settings.loadingScreenShowText) {
            customTextOverlay?.visibility = View.VISIBLE
        } else {
            customTextOverlay?.visibility = View.GONE
        }

        val keepRatio = settings.loadingScreenKeepAspectRatio
        customImage?.scaleType = if (keepRatio) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.FIT_XY

        try {
            when (mediaType) {
                "image" -> {
                    customVideo?.visibility = View.GONE
                    customImage?.visibility = View.VISIBLE
                    if (customImage != null) {
                        Glide.with(this).load(file).into(customImage)
                        if (keepRatio) {
                            autoConnectKenBurnsAnim?.cancel()
                            val scaleAnim = ObjectAnimator.ofPropertyValuesHolder(
                                customImage,
                                PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.05f),
                                PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.05f)
                            )
                            scaleAnim.duration = 8000
                            scaleAnim.repeatMode = ObjectAnimator.REVERSE
                            scaleAnim.repeatCount = ObjectAnimator.INFINITE
                            scaleAnim.start()
                            autoConnectKenBurnsAnim = scaleAnim
                        }
                    }
                }
                "gif" -> {
                    customVideo?.visibility = View.GONE
                    customImage?.visibility = View.VISIBLE
                    if (customImage != null) {
                        Glide.with(this).asGif().load(file).into(customImage)
                    }
                }
                "video" -> {
                    customImage?.visibility = View.GONE
                    customVideo?.visibility = View.VISIBLE
                    customVideo?.setVideoPath(file.absolutePath)
                    customVideo?.setOnPreparedListener { mp ->
                        mp.isLooping = settings.loadingScreenLoopVideo
                        mp.setVolume(0f, 0f)

                        if (keepRatio) {
                            try {
                                val vw = mp.videoWidth
                                val vh = mp.videoHeight
                                if (vw > 0 && vh > 0 && overlay != null) {
                                    val cw = overlay.width
                                    val ch = overlay.height
                                    if (cw > 0 && ch > 0) {
                                        val videoRatio = vw.toFloat() / vh
                                        val containerRatio = cw.toFloat() / ch
                                        val lp = customVideo.layoutParams as FrameLayout.LayoutParams
                                        if (videoRatio > containerRatio) {
                                            lp.width = cw
                                            lp.height = (cw / videoRatio).toInt()
                                        } else {
                                            lp.height = ch
                                            lp.width = (ch * videoRatio).toInt()
                                        }
                                        lp.gravity = android.view.Gravity.CENTER
                                        customVideo.layoutParams = lp
                                    }
                                }
                            } catch (e: Exception) {
                                AppLog.w("Auto-connect overlay: could not resize video: ${e.message}")
                            }
                        }
                    }
                    customVideo?.setOnErrorListener { _, _, _ ->
                        AppLog.e("Auto-connect overlay: error playing custom video")
                        // Fall back to default text on video error.
                        findViewById<View>(R.id.auto_connect_loading_custom_video)?.visibility = View.GONE
                        customTextOverlay?.visibility = View.GONE
                        defaultContent?.visibility = View.VISIBLE
                        overlay?.setBackgroundColor(Color.parseColor("#CC000000"))
                        true
                    }
                    customVideo?.start()
                }
            }
        } catch (e: Exception) {
            AppLog.e("Auto-connect overlay: failed to load media: ${e.message}")
            customImage?.visibility = View.GONE
            customVideo?.visibility = View.GONE
            customTextOverlay?.visibility = View.GONE
            defaultContent?.visibility = View.VISIBLE
            overlay?.setBackgroundColor(Color.parseColor("#CC000000"))
        }
    }

    private fun hideAutoConnectOverlay() {
        val overlay = findViewById<View>(R.id.auto_connect_loading_overlay) ?: return
        if (overlay.visibility != View.VISIBLE) return

        // Stop video FIRST — VideoView's SurfaceView ignores parent alpha animations
        // and would otherwise stay visible during the fade-out.
        stopAutoConnectVideo()
        autoConnectKenBurnsAnim?.cancel()
        autoConnectKenBurnsAnim = null
        findViewById<View>(R.id.auto_connect_loading_custom_video)?.visibility = View.GONE
        findViewById<View>(R.id.auto_connect_loading_custom_image)?.visibility = View.GONE
        findViewById<View>(R.id.auto_connect_loading_custom_text_overlay)?.visibility = View.GONE

        val hasCustomVideo = App.provide(this).settings.loadingScreenMediaType == "video"
        if (hasCustomVideo) {
            overlay.visibility = View.GONE
        } else {
            overlay.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    overlay.visibility = View.GONE
                    overlay.alpha = 1f
                }
                .start()
        }
    }

    private fun stopAutoConnectVideo() {
        findViewById<VideoView>(R.id.auto_connect_loading_custom_video)?.let {
            try {
                if (it.isPlaying) it.stopPlayback()
                it.suspend()
            } catch (_: Exception) {}
        }
    }

    private fun showSplashWithDelay(delayMs: Long) {
        val overlay = findViewById<View>(R.id.splash_overlay) ?: return
        lifecycleScope.launch(Dispatchers.Main) {
            if (delayMs > 0) {
                delay(delayMs)
            }
            overlay.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    overlay.visibility = View.GONE
                }
                .start()
        }
    }

    private fun setupWifiDirectInfo() {
        val tvInfo = findViewById<android.widget.TextView>(R.id.wifi_direct_info)
        val settings = Settings(this)

        lifecycleScope.launch {
            AapService.wifiDirectName.collectLatest { name ->
                val isHelperMode = settings.wifiConnectionMode == 2
                if (isHelperMode && name != null) {
                    tvInfo.text = "WiFi Direct: $name"
                    tvInfo.visibility = View.VISIBLE
                } else {
                    tvInfo.visibility = View.GONE
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
        // Free media resources whenever the activity is backgrounded — covers user
        // pressing Home, AapProjectionActivity coming to front (success), and
        // navigating to SettingsActivity. The connection itself keeps running in
        // AapService; this only tears down the visual overlay. The watchdog is
        // intentionally NOT cancelled here so the flag is still cleared if every
        // remaining state transition happens while the activity is stopped.
        if (findViewById<View>(R.id.auto_connect_loading_overlay)?.visibility == View.VISIBLE) {
            stopAutoConnectVideo()
            autoConnectKenBurnsAnim?.cancel()
            autoConnectKenBurnsAnim = null
            findViewById<View>(R.id.auto_connect_loading_overlay)?.visibility = View.GONE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun logLaunchSource() {
        val source = intent?.getStringExtra(EXTRA_LAUNCH_SOURCE)
        if (source != null) {
            AppLog.i("App launched via: $source")
            return
        }

        val referrer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            referrer?.toString()
        } else null

        val isLauncherTap = intent?.action == Intent.ACTION_MAIN &&
                intent.hasCategory(Intent.CATEGORY_LAUNCHER)

        if (isLauncherTap) {
            AppLog.i("App launched by user tap (referrer: ${referrer ?: "none"})")
        } else if (referrer != null) {
            AppLog.i("App launched by third party: $referrer (action: ${intent?.action})")
        } else {
            AppLog.i("App launched, source unknown (action: ${intent?.action})")
        }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return

        AppLog.i("MainActivity: Processing launch intent: ${intent.action}, data: ${intent.data}")

        val intentData = intent.data
        val intentAction = intent.action

        if (intentAction == "com.andrerinas.headunitrevived.ACTION_EXIT") {
            AppLog.i("MainActivity: Received exit action")
            val exitIntent = Intent(this, AapService::class.java).apply {
                this.action = AapService.ACTION_STOP_SERVICE
            }
            ContextCompat.startForegroundService(this, exitIntent)
            finishAffinity()
            return
        }

        if (intentAction == AapService.ACTION_START_SELF_MODE || 
           (intentData?.scheme == "headunit" && intentData.host == "selfmode")) {
            AppLog.i("MainActivity: Forced self-mode start requested")
            HomeFragment.forceSelfModeLaunch = true
            val selfModeIntent = Intent(this, AapService::class.java).apply {
                this.action = AapService.ACTION_START_SELF_MODE
            }
            ContextCompat.startForegroundService(this, selfModeIntent)
        }

        if (intent.action == Intent.ACTION_VIEW) {
            if (intentData?.scheme == "headunit" && intentData.host == "connect") {
                val ip = intentData.getQueryParameter("ip")
                if (!ip.isNullOrEmpty()) {
                    AppLog.i("Received connect intent for IP: $ip")
                    ContextCompat.startForegroundService(this, Intent(this, AapService::class.java).apply {
                        action = AapService.ACTION_CONNECT_SOCKET
                    })
                    lifecycleScope.launch(Dispatchers.IO) { App.provide(this@MainActivity).commManager.connect(ip, 5277) }
                } else {
                    AppLog.i("Received connect intent without IP -> triggering last session auto-connect")
                    val autoIntent = Intent(this, AapService::class.java).apply {
                        action = AapService.ACTION_CHECK_USB
                    }
                    ContextCompat.startForegroundService(this, autoIntent)
                }
            } else if (intentData?.scheme == "headunit" && intentData.host == "disconnect") {
                AppLog.i("Received disconnect intent")
                val stopIntent = Intent(this, AapService::class.java).apply {
                    action = AapService.ACTION_DISCONNECT
                }
                ContextCompat.startForegroundService(this, stopIntent)
            } else if (intentData?.scheme == "headunit" && intentData.host == "exit") {
                AppLog.i("Received full exit intent via deep link")
                val exitIntent = Intent(this, AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(this, exitIntent)
                finishAffinity()
            }
        }
    }

    private fun requestPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Filter out permissions that are already granted
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            AppLog.i("Requesting missing permissions: $permissionsToRequest")
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                permissionRequestCode
            )
        } else {
            AppLog.d("All required permissions already granted.")
        }
    }

    private fun setFullscreen() {
        val root = findViewById<View>(R.id.root)
        val appSettings = Settings(this)
        SystemUI.apply(window, root, appSettings.fullscreenMode)
    }

    override fun onResume() {
        super.onResume()
        setFullscreen()

        checkSetupFlow()

        requestedOrientation = Settings(this).screenOrientation.androidOrientation
        ContextCompat.registerReceiver(this, orientationReceiver, android.content.IntentFilter(AapService.ACTION_ORIENTATION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        isOrientationReceiverRegistered = true

        // If an Android Auto session is active, bring the projection activity to front
        if (App.provide(this).commManager.isConnected && !App.isPiPActive && !AapProjectionActivity.isForeground) {
            AppLog.i("MainActivity: Active session detected, bringing projection to front")
            val aapIntent = AapProjectionActivity.intent(this).apply {
                putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(aapIntent)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isOrientationReceiverRegistered) {
            unregisterReceiver(orientationReceiver)
            isOrientationReceiverRegistered = false
        }
    }

    fun checkSetupFlow() {
        val appSettings = Settings(this)
        if (!appSettings.hasAcceptedDisclaimer) {
            SafetyDisclaimerDialog.show(supportFragmentManager)
        } else if (!appSettings.hasCompletedSetupWizard) {
            SetupWizard(this) {
                // Refresh activity after setup
                recreate()
            }.start()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setFullscreen()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        AppLog.i("dispatchKeyEvent: keyCode=%d, action=%d", event.keyCode, event.action)
        
        // Always give the KeymapFragment (if active) a chance to see the key
        val handled = keyListener?.onKeyEvent(event) ?: false
        
        // If the key was handled by our listener (e.g. in KeymapFragment), stop here
        if (handled) return true
        
        // Otherwise continue with standard handling
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishReceiverRegistered) {
            unregisterReceiver(finishReceiver)
            isFinishReceiverRegistered = false
        }
        if (isFinishing) {
            AppLog.i("MainActivity finishing, resetting auto-start flag.")
            HomeFragment.resetAutoStart()
        }
    }

    companion object {
        private const val permissionRequestCode = 97
        const val EXTRA_LAUNCH_SOURCE = "launch_source"

        /**
         * Hard upper bound for how long the auto-connect overlay may stay visible
         * without the connection state advancing through the success path. Covers
         * USB open hangs and silent AOA-mode-switch failures, which today produce
         * no event for the observer to react to.
         */
        private const val AUTO_CONNECT_WATCHDOG_MS = 30_000L

        /**
         * `true` while the loading overlay should be (or is) covering the home
         * screen during an automatic connection attempt. Set by entry points
         * that initiate an auto-connect; cleared by [endAutoConnect].
         */
        @Volatile var autoConnectInProgress: Boolean = false
    }
}
