package com.sail.udpstreamer360

import android.net.Uri
import android.net.wifi.WifiManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "UDPStreamer360"
        private const val STREAM_URL = "udp://@:1234"
        private const val RECONNECT_DELAY_MS = 500L
    }

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var sphereRenderer: SphereRenderer

    @Volatile private var pendingSurface: Surface? = null
    @Volatile private var surfaceWidth: Int = 0
    @Volatile private var surfaceHeight: Int = 0
    @Volatile private var vlcSurfaceAttached = false

    private val vlcReady = mutableStateOf(false)
    private val isPlaying = mutableStateOf(false)
    private val hasVideo = mutableStateOf(false)

    // Reconnect logic
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var reconnectEnabled = true
    private val reconnectRunnable = Runnable {
        if (reconnectEnabled) {
            Log.i(TAG, "reconnect: attempting to reconnect to $STREAM_URL")
            playStream()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d(TAG, "onCreate: Activity starting")

        setContentView(R.layout.activity_main)

        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("udpstreamer360").apply {
            setReferenceCounted(true)
            acquire()
        }
        Log.d(TAG, "onCreate: Multicast lock acquired=${multicastLock?.isHeld}")

        glSurfaceView = findViewById(R.id.gl_surface_view)
        glSurfaceView.setEGLContextClientVersion(2)
        sphereRenderer = SphereRenderer()
        sphereRenderer.onSurfaceReady = { surface, w, h ->
            pendingSurface = surface
            surfaceWidth = w
            surfaceHeight = h
            vlcSurfaceAttached = false
            runOnUiThread {
                mediaPlayer?.let { mp ->
                    if (!vlcSurfaceAttached) attachVlcSurface(mp, surface, w, h)
                }
            }
        }
        sphereRenderer.onSizeChanged = { w, h ->
            runOnUiThread { mediaPlayer?.vlcVout?.setWindowSize(w, h) }
        }
        glSurfaceView.setRenderer(sphereRenderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        sphereRenderer.requestRender = { glSurfaceView.requestRender() }

        // Swipe to look around the 360 sphere
        var lastTouchX = 0f
        var lastTouchY = 0f
        val TOUCH_SENSITIVITY = 0.15f   // degrees per pixel
        val PITCH_LIMIT = 85f

        @Suppress("ClickableViewAccessibility")
        glSurfaceView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    sphereRenderer.yaw   = (sphereRenderer.yaw   - dx * TOUCH_SENSITIVITY) % 360f
                    sphereRenderer.pitch = (sphereRenderer.pitch + dy * TOUCH_SENSITIVITY)
                        .coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
                    glSurfaceView.requestRender()
                }
            }
            true
        }

        val composeView = findViewById<ComposeView>(R.id.compose_overlay)
        composeView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        composeView.setContent {
            val ready by vlcReady
            val playing by isPlaying
            val video by hasVideo
            StatusOverlay(vlcReady = ready, isPlaying = playing, hasVideo = video)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val vlcOptions = arrayListOf(
                "--network-caching=1500",      // buffer for network jitter (ms)
                "--live-caching=1500",         // live-stream demux buffer (ms)
                // --file-caching omitted: not applicable to UDP live streams
                "--clock-jitter=250000",       // 250 ms — enough for WiFi bursts without adding 1 s of latency
                "--clock-synchro=0",           // disable PCR-based clock sync (unreliable on live UDP)
                "--no-ts-trust-pcr",           // ignore PCR timestamps that may be wrong after packet loss
                "--no-ts-cc-check",            // don't abort on continuity-counter errors from dropped packets
                "--udp-timeout=2000",          // fire EndReached after 2 s of silence → triggers reconnect
                "--udp-buffer=2097152",        // 2 MB socket receive buffer — absorbs WiFi burst drops
                "--avcodec-error-resilience=3",// attempt to recover from corrupted NAL units
                "--avcodec-skip-frame=0",      // decode every frame (no quality shortcuts)
                "--avcodec-workaround-bugs=1", // enable ffmpeg bug workarounds for encoder quirks
                "--avcodec-threads=0"          // let ffmpeg choose optimal thread count for the device
                // --no-drop-late-frames / --no-skip-frames removed:
                //   on mobile these can cause the decode queue to grow unboundedly when decoding lags;
                //   re-add only if you observe visible dropped frames under normal load
                // --avcodec-skip-idct=0 removed: deprecated in modern ffmpeg builds
            )
            val vlc = LibVLC(this@MainActivity, vlcOptions)
            Log.d(TAG, "onCreate: LibVLC initialized")
            val mp = MediaPlayer(vlc)
            Log.d(TAG, "onCreate: MediaPlayer created")

            mp.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        Log.i(TAG, "VLC Event: Playing")
                        isPlaying.value = true
                        hasVideo.value = true
                        mainHandler.removeCallbacks(reconnectRunnable)
                    }
                    MediaPlayer.Event.Stopped -> {
                        Log.i(TAG, "VLC Event: Stopped")
                        isPlaying.value = false
                        hasVideo.value = false
                        scheduleReconnect()
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        Log.e(TAG, "VLC Event: EncounteredError")
                        isPlaying.value = false
                        hasVideo.value = false
                        scheduleReconnect()
                    }
                    MediaPlayer.Event.EndReached -> {
                        Log.i(TAG, "VLC Event: EndReached")
                        isPlaying.value = false
                        hasVideo.value = false
                        scheduleReconnect()
                    }
                    MediaPlayer.Event.Opening  -> Log.i(TAG, "VLC Event: Opening")
                    MediaPlayer.Event.Buffering -> Log.d(TAG, "VLC Event: Buffering ${event.buffering}%")
                    MediaPlayer.Event.Vout      -> Log.i(TAG, "VLC Event: Vout count=${event.voutCount}")
                    MediaPlayer.Event.ESAdded   -> Log.i(TAG, "VLC Event: ESAdded")
                    MediaPlayer.Event.ESDeleted -> Log.i(TAG, "VLC Event: ESDeleted")
                }
            }

            launch(Dispatchers.Main) {
                libVLC = vlc
                mediaPlayer = mp
                val surface = pendingSurface
                if (surface != null && !vlcSurfaceAttached) {
                    attachVlcSurface(mp, surface, surfaceWidth, surfaceHeight)
                }
                vlcReady.value = true
                // Auto-connect on startup
                reconnectEnabled = true
                playStream()
            }
        }
    }

    private fun scheduleReconnect() {
        if (!reconnectEnabled) return
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
        Log.i(TAG, "scheduleReconnect: will retry in ${RECONNECT_DELAY_MS}ms")
    }

    private fun attachVlcSurface(mp: MediaPlayer, surface: Surface, w: Int, h: Int) {
        val vout = mp.vlcVout
        vout.removeCallback(voutCallback)
        if (vout.areViewsAttached()) vout.detachViews()
        vout.setVideoSurface(surface, null)
        vout.setWindowSize(w, h)
        vout.addCallback(voutCallback)
        vout.attachViews(videoLayoutListener)
        vlcSurfaceAttached = true
        Log.d(TAG, "attachVlcSurface: IVLCVout attached ${w}x${h}")
    }

    private val voutCallback = object : IVLCVout.Callback {
        override fun onSurfacesCreated(vlcVout: IVLCVout) {
            Log.d(TAG, "IVLCVout: onSurfacesCreated")
        }
        override fun onSurfacesDestroyed(vlcVout: IVLCVout) {
            Log.d(TAG, "IVLCVout: onSurfacesDestroyed")
        }
    }

    private val videoLayoutListener = IVLCVout.OnNewVideoLayoutListener { _, width, height, visibleWidth, visibleHeight, _, _ ->
        if (width == 0 || height == 0) return@OnNewVideoLayoutListener
        Log.d(TAG, "onNewVideoLayout: ${width}x${height} visible ${visibleWidth}x${visibleHeight}")
        glSurfaceView.queueEvent { sphereRenderer.updateVideoSize(width, height) }
        runOnUiThread { mediaPlayer?.vlcVout?.setWindowSize(visibleWidth, visibleHeight) }
    }

    private fun playStream() {
        val mp = mediaPlayer ?: return
        val vlc = libVLC ?: return
        Log.i(TAG, "playStream: url=$STREAM_URL")
        mp.stop()

        val media = Media(vlc, Uri.parse(STREAM_URL))
        media.setHWDecoderEnabled(true, false)  // HW decode: fast enough for 4K; CC-check disabled so SPS/PPS loss no longer crashes it
        media.addOption(":network-caching=1500")
        media.addOption(":live-caching=1500")
        media.addOption(":clock-jitter=1000000")
        media.addOption(":no-ts-cc-check")
        media.addOption(":avcodec-threads=4")
        media.addOption(":avcodec-error-resilience=3")
        media.addOption(":avcodec-workaround-bugs=1")
        mp.media = media
        media.release()
        mp.play()
        Log.i(TAG, "playStream: play() called")
    }


    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        // Resume playback when coming back to foreground
        if (vlcReady.value && !isPlaying.value) {
            reconnectEnabled = true
            playStream()
        }
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
    }

    override fun onStop() {
        super.onStop()
        reconnectEnabled = false
        mainHandler.removeCallbacks(reconnectRunnable)
        mediaPlayer?.stop()
        mediaPlayer?.vlcVout?.let {
            it.removeCallback(voutCallback)
            it.detachViews()
        }
    }

    override fun onRestart() {
        super.onRestart()
        reconnectEnabled = true
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectEnabled = false
        mainHandler.removeCallbacks(reconnectRunnable)
        sphereRenderer.release()
        mediaPlayer?.release()
        libVLC?.release()
        multicastLock?.let { if (it.isHeld) it.release() }
    }
}

@Composable
fun StatusOverlay(
    vlcReady: Boolean,
    isPlaying: Boolean,
    hasVideo: Boolean
) {
    Box(modifier = Modifier.fillMaxSize()) {

        // Centre message when no video
        if (!hasVideo) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!vlcReady) {
                    CircularProgressIndicator(color = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Initializing…", color = Color.Gray, fontSize = 16.sp)
                } else {
                    Icon(
                        Icons.Default.VideocamOff,
                        contentDescription = "No feed",
                        tint = Color.Gray,
                        modifier = Modifier.size(80.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Connecting to stream…", color = Color.Gray, fontSize = 16.sp)
                    Text("udp://@:1234", color = Color.DarkGray, fontSize = 11.sp)
                }
            }
        }
    }
}

