package com.example.wifiaudiospeaker

import android.Manifest
import android.media.AudioManager
import android.app.*
import android.content.*
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator

import android.view.animation.AccelerateDecelerateInterpolator
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.util.TypedValue
import android.widget.*

import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import kotlin.math.roundToInt

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import android.net.ConnectivityManager
import android.widget.TextView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import android.provider.Settings

class MainActivity : AppCompatActivity() {


    companion object {
        private const val TAG = "MainActivity"
        const val ACTION_UI_RESET = "com.example.wifiaudiospeaker.ACTION_UI_RESET"
        const val ACTION_UI_CLOSE = "com.example.wifiaudiospeaker.ACTION_UI_CLOSE"
        const val ACTION_UI_RECONNECTING = "com.example.wifiaudiospeaker.ACTION_UI_RECONNECTING"
        const val ACTION_UI_CONNECTED = "com.example.wifiaudiospeaker.ACTION_UI_CONNECTED"
        const val ACTION_UI_SYNC_MUTE = "com.example.wifiaudiospeaker.ACTION_UI_SYNC_MUTE"
        const val ACTION_UI_PC_STATE = "com.example.wifiaudiospeaker.ACTION_UI_PC_STATE"
        const val ACTION_UI_SYNC_QUALITY_MODE = "com.example.wifiaudiospeaker.ACTION_UI_SYNC_QUALITY_MODE"
        const val ACTION_UI_CONNECTION_PATH_CHANGED =
            "com.example.wifiaudiospeaker.ACTION_UI_CONNECTION_PATH_CHANGED"
        const val ACTION_UI_TRANSPORT_MODE_CHANGED =
            "com.example.wifiaudiospeaker.ACTION_UI_TRANSPORT_MODE_CHANGED"


        // Native discovery only (Service owns streaming)
        @JvmStatic external fun nativeDiscoverServer(foundPort: IntArray): String?
        @JvmStatic external fun nativeGetConnectionHealth(): Int
    }

    // UI
    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var btnClearEndpoint: Button
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvStatusHint: TextView
    private lateinit var tvPackets: TextView
    private lateinit var tvServerInfo: TextView
    private lateinit var tvConnectionPath: TextView
    private lateinit var seekVolume: SeekBar
    private lateinit var tvDeviceName: TextView
    private lateinit var tvLatency: TextView
    private lateinit var tvJitter: TextView
    private lateinit var tvSignal: TextView


    private lateinit var lanRadarPanel: FrameLayout
    private lateinit var radarRingOuter: View
    private lateinit var radarRingMiddle: View
    private lateinit var radarRingInner: View
    private lateinit var radarCenterIcon: TextView
    private lateinit var radarCenterImage: ImageView
    private lateinit var tvRadarCaption: TextView
    private val radarAnimators = mutableListOf<AnimatorSet>()
    private lateinit var rbManual: RadioButton
    private lateinit var rbAuto: RadioButton
    private lateinit var btnScanQr: Button
    private lateinit var btnMute: Button
    private lateinit var btnSync: Button
    private lateinit var tvConnectionQuality: TextView

    // PC controls are created in Kotlin so activity_main.xml remains untouched.
    private var btnPcMedia: Button? = null
    private var btnPcMute: Button? = null
    private lateinit var drawerLayout: DrawerLayout

    private lateinit var pbLatency: ProgressBar
    private lateinit var pbJitter: ProgressBar
    private lateinit var pbSignal: ProgressBar

    private lateinit var drawerToggle: ActionBarDrawerToggle
    private lateinit var navigationView: NavigationView
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar

    // App state
    private var isMuted = false
    private var lastVolume = 75f
    private var directMode = false
    private var bufferModeKey = BufferModeConfig.Mode.MEDIUM.key
    private var selectedBufferMs = BufferModeConfig.Mode.MEDIUM.presetMs
    private var customBufferMs = BufferModeConfig.DEFAULT_CUSTOM_MS
    private var audioDelay = 10

    private var streamingMode = "wireless"
    private var launchedFromSetupConnection = false
    private var isWifiEnabledOnLaunch = true
    private var lastConnectedIp = ""
    private var lastConnectedPort = 0
    private var detectedServerBackend = "Unknown"
    private var serverDisplayName = "Unknown"
    private var serverNetworkType = "Unknown"
    private var serverAdapterName = "Unknown"
    private var serverSsid = "Unknown"
    private var isHotspotConnection = false
    private var isUserDisconnected = false // 🎯 Prevents looping after intentional disconnect
    private var wasDisconnectedWhileBackgrounded = false // 🎯 Rule > Reconnects on fresh return from background

    // True when the user intentionally stopped the current session
    // but wants the foreground notification/controller to remain available.
    private var isUserPaused = false
    private var activeNetworkHandle: Long = -1L // 🎯 Prevents false network switch triggers on registration
    private var isNetworkMonitoringInitialized = false
    private var lastRenderedConnectionPath = "Path: —"


    private enum class ConnectionUiState { IDLE,
        CONNECTING,
        CONNECTED,
        FAILED
    }

    private enum class RadarState {
        IDLE,
        DISCOVERING,
        MANUAL_READY,
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        FAILED
    }

    private data class SavedServerTarget(val ip: String, val port: Int)

    private var connectionUiState = ConnectionUiState.IDLE
    private val connectionWatchdog = Handler(Looper.getMainLooper())
    private var connectionTimeoutRunnable: Runnable? = null
    private var lastStatsPacketCount = 0
    private var lastStatsSampleAtMs = 0L

    private var autoReconnect = true

    // Prevent onResume/network callbacks from reconnecting with
    // temporary/default values before restoreAppState() finishes.
    private var appStateRestored = false
    private var isConnected = false

    private var isStreaming = false

    private var currentRadarState = RadarState.IDLE


    private val deviceBoard = Build.BOARD ?: "Unknown"
    private lateinit var deviceName: String
    private val deviceBrand =  Build.BRAND?:"Unknown"


    //Settings
    private val SETTINGS_REQUEST_CODE = 1002
    private var currentTheme = 0
    private var currentDarkMode = 2

    // TTS
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isTtsMuted = false

    // Stats polling
    private var audioStreamingService: AudioStreamingService? = null
    private var isBound = false
    private var statsHandler: Handler? = null
    private var statsRunnable: Runnable? = null
    private val POLL_INTERVAL = 1000L
    private lateinit var connectivityManager: android.net.ConnectivityManager
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private data class ServerIdentity(
        val ip: String,
        val port: Int,
        val name: String,
        val backend: String,
        val networkType: String = "Unknown",
        val adapter: String = "Unknown",
        val ssid: String = "Unknown",
        val serverIp: String = ""
    )

    private fun parseServerDiscoveryResponse(
        response: String
    ): ServerIdentity? {

        return try {
            if (!response.startsWith("WIFI_AUDIO_SERVER:")) {
                return null
            }

            val sections =
                response.split("|", limit = 2)

            val core =
                sections[0].split(":")

            if (core.size < 5) {
                return null
            }

            val ip = normalizeServerIp(core[1]) ?: run {
                Log.w(
                    TAG,
                    "🛡️ Ignoring invalid discovery IP: '${core[1]}'"
                )

                return null
            }
            val port = core[2].toIntOrNull() ?: return null
            val name = core[3].ifBlank { "Unknown" }
            val backend = core[4].ifBlank { "Unknown" }

            val metadata = mutableMapOf<String, String>()

            if (sections.size == 2) {
                sections[1]
                    .split("|")
                    .forEach { item ->
                        val index = item.indexOf("=")

                        if (index > 0) {
                            val key =
                                item.substring(
                                    0,
                                    index
                                ).trim()

                            val value =
                                item.substring(
                                    index + 1
                                ).trim()

                            metadata[key] = value
                        }
                    }
            }

            ServerIdentity(
                ip = ip,
                port = port,
                name = name,
                backend = backend,
                networkType =
                    metadata["NET"] ?: "Unknown",
                adapter =
                    metadata["ADAPTER"] ?: "Unknown",
                ssid =
                    metadata["SSID"] ?: "Unknown",
                serverIp =
                    metadata["SERVER_IP"] ?: ip
            )

        } catch (e: Exception) {
            Log.w(
                TAG,
                "⚠️ Discovery response parse failed: ${e.message}"
            )
            null
        }
    }

    private fun normalizeServerIp(rawIp: String): String? {

        val candidates =
            Regex("""(?<!\d)(?:\d{1,3}\.){3}\d{1,3}(?!\d)""")
                .findAll(rawIp)
                .map { it.value }
                .filter { ip ->
                    ip.split(".")
                        .all { part ->
                            part.toIntOrNull()?.let {
                                it in 0..255
                            } == true
                        }
                }
                .distinct()
                .toList()

        if (candidates.isEmpty()) {
            return null
        }

        // Prefer the candidate on the same /24 as the active local IPv4.
        val localIp =
            try {
                connectivityManager
                    .getLinkProperties(
                        connectivityManager.activeNetwork
                    )
                    ?.linkAddresses
                    ?.map { it.address }
                    ?.filterIsInstance<java.net.Inet4Address>()
                    ?.firstOrNull()
                    ?.hostAddress
            } catch (_: Exception) {
                null
            }

        val localPrefix =
            localIp?.substringBeforeLast(".")

        if (localPrefix != null) {
            candidates.firstOrNull {
                it.substringBeforeLast(".") == localPrefix
            }?.let {
                return it
            }
        }

        return candidates.first()
    }

    private fun isModeSelectionLocked(): Boolean {
        return isConnected ||
                connectionUiState == ConnectionUiState.CONNECTING ||
                AudioStreamingService.isReconnectingState
    }

    private fun showModeLockedMessage(modeView: RadioButton? = null) {
        val modeText = modeView?.text?.toString()?.trim().orEmpty()
        if (modeText.isNotEmpty()) {
            safeSpeak(modeText)
        }

        toast("🔒 Disconnect first, or enable Auto Reconnect to switch Wireless/Wired during a session.")
    }

    // 🎯 QR Scanner Result Handler // Embedded in-app scanner launcher (Opens camera inside the app, scans, and connects immediately)
    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val scanData = result.contents.trim()
            if (scanData.startsWith("WIFI_AUDIO_SERVER:")) {
                val identity =
                    parseServerDiscoveryResponse(scanData)

                if (identity != null) {

                    etIp.setText(identity.ip)
                    etPort.setText(identity.port.toString())

                    updateServerInfoCard(identity)

                    rbManual.isChecked = true
                    rbAuto.isChecked = false

                    syncModeButtonsEnabled()

                    Log.i(TAG, "🔍 QR Code Successfully Parsed:")
                    Log.i(TAG, "   - Server IP: ${identity.ip}")
                    Log.i(TAG, "   - Port: ${identity.port}")
                    Log.i(TAG, "   - Server Name: ${identity.name}")
                    Log.i(TAG, "   - Backend: ${identity.backend}")
                    Log.i(TAG, "   - PC Network: ${identity.networkType}")
                    Log.i(TAG, "   - PC Adapter: ${identity.adapter}")
                    Log.i(TAG, "   - PC SSID: ${identity.ssid}")

                    toast("✅ Connected via QR Code | Server information loaded")
                    safeSpeak("Connecting to ${identity.name}")
                    startStreaming()
                }
            } else {
                toast("❌ Invalid WiFi Audio Server QR")
                safeSpeak("Invalid QR Code")
            }
        }
    }


    private val serviceUiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {

                ACTION_UI_CONNECTED -> {
                    val connectedIp = intent.getStringExtra("IP").orEmpty()
                    val connectedPort = intent.getIntExtra("PORT", 0)
                    val connectedServerName =
                        intent.getStringExtra("SERVER_NAME")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: "Unknown"

                    val connectedBackend =
                        intent.getStringExtra("SERVER_BACKEND")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: "Unknown"
                    val connectionPath =
                        intent.getStringExtra("CONNECTION_PATH")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: "${if (streamingMode == "wired") "🔌 USB Tethering" else "🛜 Wireless"}"

                    runOnUiThread {
                        // ✅ Cancel the connection watchdog immediately.
                        connectionTimeoutRunnable?.let {
                            connectionWatchdog.removeCallbacks(it)
                        }
                        connectionTimeoutRunnable = null

                        isConnected = true
                        isStreaming = true // 🎯 FIX: Sync UI streaming state
                        connectionUiState = ConnectionUiState.CONNECTED

                        serverDisplayName = connectedServerName
                        detectedServerBackend = connectedBackend

                        tvStatus.text =
                            "✅ Connected to $connectedIp:$connectedPort"

                        tvStatusHint.text =
                            "Server: $connectedServerName"

                        tvServerInfo.text =
                            "Server : $connectedServerName"
                        lastRenderedConnectionPath = "Path: $connectionPath"
                        tvConnectionPath.text =
                            "Path: $connectionPath\nEndpoint: $connectedIp:$connectedPort"
                        getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                            .edit()
                            .putString("last_connection_path", connectionPath)
                            .apply()

                        btnConnect.isEnabled = false
                        btnDisconnect.isEnabled = true

                        setRadarState(RadarState.CONNECTED)
                        syncModeButtonsEnabled()
                        updatePcControlButtons(false, true, false, false)

                        Log.i(
                            "MainActivity",
                            "✅ UI confirmed CONNECTED -> $connectedIp:$connectedPort"
                        )
                    }
                }
                ACTION_UI_TRANSPORT_MODE_CHANGED -> {
                    val mode = intent.getStringExtra("streaming_mode")
                        ?.trim()
                        ?.lowercase(Locale.US)
                        ?: if (intent.getBooleanExtra("IS_WIRED", false)) "wired" else "wireless"
                    runOnUiThread {
                        if (mode != streamingMode) {
                            Log.i(TAG, "🧭 Service auto-detected transport mode=$mode")
                            setStreamingMode(mode, userInitiated = false, announce = false)
                        }
                        if (::navigationView.isInitialized) {
                            navigationView.setCheckedItem(
                                if (mode == "wired") R.id.nav_wired_mode else R.id.nav_wireless_mode
                            )
                        }
                        syncModeButtonsEnabled()
                    }
                }
                ACTION_UI_CONNECTION_PATH_CHANGED -> {
                    val path = intent.getStringExtra("CONNECTION_PATH")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: return
                    runOnUiThread {
                        lastRenderedConnectionPath = "Path: $path"
                        if (::tvConnectionPath.isInitialized) {
                            tvConnectionPath.text = lastRenderedConnectionPath
                        }
                        getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                            .edit()
                            .putString("last_connection_path", path)
                            .apply()
                    }
                }
                ACTION_UI_SYNC_MUTE -> {
                    val muted = intent.getBooleanExtra("is_muted", false)
                    runOnUiThread {
                        isMuted = muted
                        btnMute.text = if (isMuted) " 📱〰🔇 " else " 📱〰🔊 "
                        if (isMuted) seekVolume.progress = 0 else seekVolume.progress = lastVolume.toInt()
                        saveAppState()
                    }
                }

                ACTION_UI_PC_STATE -> {
                    val mediaKnown = intent.getBooleanExtra("media_known", false)
                    val mediaPlaying = intent.getBooleanExtra("media_playing", true)
                    val audioKnown = intent.getBooleanExtra("audio_known", false)
                    val audioMuted = intent.getBooleanExtra("audio_muted", false)
                    runOnUiThread {
                        updatePcControlButtons(mediaKnown, mediaPlaying, audioKnown, audioMuted)
                    }
                }

                ACTION_UI_SYNC_QUALITY_MODE -> {
                    val prefs = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                    if (intent.hasExtra("buffer_mode")) {
                        val mode = BufferModeConfig.Mode.fromKey(intent.getStringExtra("buffer_mode"))
                        val customMs = intent.getIntExtra(
                            "custom_buffer_ms",
                            prefs.getInt(BufferModeConfig.PREF_CUSTOM_MS, BufferModeConfig.DEFAULT_CUSTOM_MS)
                        )
                        syncBufferState(BufferModeConfig.save(prefs, mode, customMs))
                    } else if (intent.hasExtra("button_premium_free")) {
                        // Compatibility with a broadcast from an older settings screen.
                        val mode = BufferModeConfig.Mode.fromLegacyPremiumFree(
                            intent.getBooleanExtra("button_premium_free", directMode)
                        )
                        syncBufferState(BufferModeConfig.save(
                            prefs, mode, prefs.getInt(BufferModeConfig.PREF_CUSTOM_MS, BufferModeConfig.DEFAULT_CUSTOM_MS)
                        ))
                    } else {
                        syncBufferState(BufferModeConfig.load(prefs))
                    }
                }

                ACTION_UI_RESET -> {
                    val forceDisconnected = intent.getBooleanExtra("force_disconnected", false)
                    val userRequested = intent.getBooleanExtra("user_requested", false)

                    runOnUiThread {
                        // Broadcasts can be queued while a reconnect finishes. A stale
                        // internal reset must never overwrite the service's live CONNECTED state.
                        if (!userRequested && !forceDisconnected && AudioStreamingService.isConnectedState) {
                            isConnected = true
                            isStreaming = true
                            connectionUiState = ConnectionUiState.CONNECTED
                            val ip = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                                .getString("server_ip", lastConnectedIp).orEmpty()
                            val port = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                                .getInt("server_port", lastConnectedPort)
                            tvStatus.text = if (ip.isNotBlank() && port > 0) {
                                "✅ Connected to $ip:$port"
                            } else {
                                "✅ Connected"
                            }
                            tvServerInfo.text = "Server : $serverDisplayName"
                            btnConnect.isEnabled = false
                            btnDisconnect.isEnabled = true
                            setRadarState(RadarState.CONNECTED)
                            syncModeButtonsEnabled()
                            return@runOnUiThread
                        }
                        connectionTimeoutRunnable?.let { connectionWatchdog.removeCallbacks(it) }
                        connectionTimeoutRunnable = null

                        if (userRequested || forceDisconnected) {
                            isUserPaused = !forceDisconnected
                            isUserDisconnected = true
                            wasDisconnectedWhileBackgrounded = false
                            clearDisconnectedUi(closeApp = false)
                            setRadarState(RadarState.DISCONNECTED)

                            if (forceDisconnected) {
                                tvStatus.text = "❌ Disconnected by Server"
                                tvStatusHint.text = "Server Administrator disconnected this device"
                            } else {
                                tvStatus.text = "⏸️ Stopped"
                                tvStatusHint.text = "Tap Resume in the notification to reconnect"
                            }
                        } else if (AudioStreamingService.isReconnectingState) {
                            // INTERNAL Service failure/reset: update the UI only.
                            // Never call stopStreaming()/stopService() here.
                            isConnected = false
                            isStreaming = false
                            connectionUiState = ConnectionUiState.CONNECTING
                            btnConnect.isEnabled = false
                            btnDisconnect.isEnabled = true
                            tvStatus.text = "🔄 Connection lost — Service is reconnecting…"
                            tvStatusHint.text = "Reconnection is running in background"
                            setRadarState(RadarState.CONNECTING)
                        } else {
                            // Keep every visual status in sync. The previous code
                            // reset buttons/status text but left the radar caption at
                            // "Connected", producing the split state in the screenshot.
                            isConnected = false
                            isStreaming = false
                            connectionUiState = ConnectionUiState.IDLE
                            tvStatus.text = "❌ Disconnected"
                            tvStatusHint.text = if (autoReconnect) {
                                "Waiting for a usable transport or tap Connect to retry"
                            } else {
                                "Tap Connect to start streaming"
                            }
                            setRadarState(RadarState.DISCONNECTED)
                            resetButtonStates()
                            syncModeButtonsEnabled()
                        }
                    }
                }

                ACTION_UI_CLOSE -> {
                    // The Service has already stopped itself; Activity only closes UI.
                    isUserDisconnected = true
                    isUserPaused = false
                    saveAppState()
                    clearDisconnectedUi(closeApp = true)
                }
                "com.example.wifiaudiospeaker.ACTION_UI_TOGGLE_MUTE" -> {
                    runOnUiThread {
                        btnMute.performClick()
                    }
                }
                ACTION_UI_RECONNECTING -> {
                    val attempt = intent.getIntExtra("attempt", 1)
                    val max = intent.getIntExtra("max", 10)
                    val delaySec = intent.getLongExtra("delay_seconds", 2)
                    runOnUiThread {
                        // The reconnect notification may arrive after a successful ACK.
                        if (AudioStreamingService.isConnectedState) return@runOnUiThread
                        isConnected = false
                        isStreaming = false
                        tvStatus.text = "🔄 Connection lost — retrying in ${delaySec}s…"
                        tvStatusHint.text = if (max <= 0) {
                            "Attempt $attempt • automatic recovery continues until connected"
                        } else {
                            "Attempt $attempt of $max"
                        }
                        connectionUiState = ConnectionUiState.CONNECTING
                        btnConnect.isEnabled = false
                        btnDisconnect.isEnabled = true
                        setRadarState(RadarState.CONNECTING)
                        syncModeButtonsEnabled()
                    }
                }

            }
        }
    }

    // Service connection
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioStreamingService.LocalBinder
            audioStreamingService = binder.getService()
            isBound = true
            startPollingConnectionStats()
            updatePcControlButtons(false, true, false, false)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            AudioStreamingService.isConnectedState = false
            isConnected = false
            isBound = false
            stopPollingConnectionStats()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceName = DeviceNameHelper.getDeviceName(this)
        setContentView(R.layout.activity_main)

        // Log device info for debugging
        Log.i("MainActivity", "Device Info: $deviceName, Board: $deviceBoard")
        Log.i("MainActivity", "Device-specific settings: delay=$audioDelay ms, ")

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        launchedFromSetupConnection = intent.getBooleanExtra("FROM_SETUP_CONNECTION", false)

        initViews()
        initTTS()
        setupListeners()
        createNotificationChannel()
        setupNetworkMonitoring()
        restoreAppState()
        requestAudioPermissions()
        setupNavigationDrawer()
        registerServiceUiReceiver()
        handleNotificationActivityIntent(intent)
        lifecycleScope.launch {
            //delay(600L)
            safeSpeak("WiFi Audio Speaker ready")
            //delay(1400L)

            setupLanRadarAnimation()
            setRadarState(RadarState.IDLE)

        }



    }

    private fun initViews() {
        rbManual = findViewById(R.id.rbManual)
        rbAuto = findViewById(R.id.rbAuto)
        etIp = findViewById(R.id.etIp)
        etPort = findViewById(R.id.etPort)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnClearEndpoint = findViewById(R.id.btnClearEndpoint)

        btnScanQr = findViewById(R.id.btnScanQR) // Ensure this ID exists in your activity_main.xml

        // Auto/Manual selection now lives on SetupActivity. Keep these IDs for
        // state compatibility, but never expose the dashboard selector.
        findViewById<View>(R.id.rdgrpMode).visibility = View.GONE

        tvStatus = findViewById(R.id.tvStatus)
        tvStatusHint = findViewById(R.id.tvStatusHint)
        tvPackets = findViewById(R.id.tvPackets)
        seekVolume = findViewById(R.id.seekVolume)


        btnMute = findViewById(R.id.btnMute)
        btnSync = findViewById(R.id.btnSync)
        setupPcControlButtons()

        tvConnectionQuality = findViewById(R.id.tvConnectionQuality)
        tvServerInfo = findViewById(R.id.tvServerInfo)
        tvConnectionPath = findViewById(R.id.tvConnectionPath)
        drawerLayout = findViewById(R.id.drawer_layout)

        tvLatency = findViewById(R.id.tvLatency)
        tvJitter = findViewById(R.id.tvJitter)
        tvSignal = findViewById(R.id.tvSignal)

        pbLatency = findViewById(R.id.pbLatency)
        pbJitter = findViewById(R.id.pbJitter)
        pbSignal = findViewById(R.id.pbSignal)
        tvDeviceName = findViewById(R.id.tvDeviceName)
        tvDeviceName.text = "📱 $deviceName"

        lanRadarPanel = findViewById(R.id.lanRadarPanel)
        radarRingOuter = findViewById(R.id.radarRingOuter)
        radarRingMiddle = findViewById(R.id.radarRingMiddle)
        radarRingInner = findViewById(R.id.radarRingInner)
        radarCenterIcon = findViewById(R.id.radarCenterIcon)
        radarCenterImage = findViewById(R.id.radarCenterImage)
        tvRadarCaption = findViewById(R.id.tvRadarCaption)


        navigationView = findViewById(R.id.nav_view)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        seekVolume.max = 100
        seekVolume.progress = getSystemMediaVolumePercent()
        tvConnectionQuality.text = "First Connect to the Server"

        syncModeButtonsEnabled()
        resetButtonStates()
    }

    private fun setupPcControlButtons() {
        val parent = btnSync.parent as? ViewGroup ?: return
        if (btnPcMedia != null || btnPcMute != null) return

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "pc_control_row"
        }

        val media = Button(this).apply {
            text = "⏯ PC"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            minWidth = 0
            minHeight = 0
            setOnClickListener {
                startService(Intent(this@MainActivity, AudioStreamingService::class.java).apply {
                    action = AudioStreamingService.ACTION_NOTIFICATION_PC_MEDIA
                })
            }
        }

        val mute = Button(this).apply {
            text = "🔊 PC"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            minWidth = 0
            minHeight = 0
            setOnClickListener {
                startService(Intent(this@MainActivity, AudioStreamingService::class.java).apply {
                    action = AudioStreamingService.ACTION_NOTIFICATION_PC_MUTE
                })
            }
        }

        row.addView(media, LinearLayout.LayoutParams(dp(92), dp(42)).apply { marginEnd = dp(4) })
        row.addView(mute, LinearLayout.LayoutParams(dp(92), dp(42)))

        if (parent is RelativeLayout) {
            parent.addView(row, RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.BELOW, btnSync.id)
                topMargin = dp(2)
            })
        } else {
            parent.addView(row)
        }

        btnPcMedia = media
        btnPcMute = mute
        updatePcControlButtons(false, true, false, false)
    }

    private fun updatePcControlButtons(
        mediaKnown: Boolean,
        mediaPlaying: Boolean,
        audioKnown: Boolean,
        audioMuted: Boolean
    ) {
        val active = AudioStreamingService.isConnectedState && isStreaming
        btnPcMedia?.let { button ->
            button.visibility = if (active) View.VISIBLE else View.GONE
            button.isEnabled = active
            button.text = when {
                !mediaKnown -> "⏯ PC"
                mediaPlaying -> "⏸ PC"
                else -> "▶ PC"
            }
        }
        btnPcMute?.let { button ->
            button.visibility = if (active) View.VISIBLE else View.GONE
            button.isEnabled = active
            button.text = if (audioKnown && audioMuted) "🔇 PC" else "🔊 PC"
        }
    }

    private fun setupListeners() {
        btnSync.setOnClickListener {
            showBufferModeDialog()
        }

        rbManual.setOnClickListener {
            if (isModeSelectionLocked()) {
                showModeLockedMessage(rbManual)
                return@setOnClickListener
            }

            safeSpeak("Manually Connect Server")
            toast("Manual Connect mode selected")

            rbManual.isChecked = true
            rbAuto.isChecked = false

            resetButtonStates()
            syncModeButtonsEnabled()
        }

        rbAuto.setOnClickListener {
            if (isModeSelectionLocked()) {
                showModeLockedMessage(rbAuto)
                return@setOnClickListener
            }

            safeSpeak("Auto Discover Server")
            toast("Auto discovery Mode selected")

            rbAuto.isChecked = true
            rbManual.isChecked = false

            resetButtonStates()
            syncModeButtonsEnabled()
        }

        btnConnect.setOnClickListener {
            // TEST-4 / TEST-E gate.  This decision must NOT depend on
            // isUserDisconnected: a network-loss callback can legitimately clear
            // that flag before the user returns to MainActivity.  With persistent
            // Auto-reconnect OFF, an empty Connect request means "use the last
            // successful server only if it belongs to the CURRENT transport".
            // A gateway/interface change is a hard boundary: never try the old IP.
            if (!autoReconnect) {
                val savedServerTarget = readSavedServerTarget()
                val enteredIp = etIp.text.toString().trim()
                val enteredPort = etPort.text.toString().trim().toIntOrNull() ?: 0
                val refersToSavedTarget = savedServerTarget != null &&
                        (enteredIp.isBlank() ||
                                (enteredIp == savedServerTarget.ip && enteredPort == savedServerTarget.port))

                if (refersToSavedTarget) {
                    if (lastSuccessfulNetworkSignatureMatchesCurrent()) {
                        promptUseLastSavedServer(savedServerTarget!!) { target ->
                            isUserDisconnected = false
                            etIp.setText(target.ip)
                            etPort.setText(target.port.toString())
                            startManualConnect(target.ip)
                        }
                    } else {
                        Log.i(TAG, "🛑 Saved endpoint belongs to a different network; opening SetupActivity Auto Discover")
                        openSetupForNetworkChange()
                    }
                    return@setOnClickListener
                }
            }

            isUserDisconnected = false // 🎯 User intentionally wants to connect
            safeSpeak("Connecting to server")
            setRadarState(RadarState.CONNECTING)
            if (isConnected  || connectionUiState == ConnectionUiState.CONNECTING) {
                toast("Already other device is Connecting/Connected - Disconnect that Device from Server first")

                if (!isStreaming) {
                    safeSpeak("one device is already connected to $serverDisplayName - disconnect it first")
                }

                tvStatusHint.text = "first Disconnect the   Device 📱  connected to the server $serverDisplayName"
                return@setOnClickListener
            }

            if (rbAuto.isChecked) {
                // Auto discovery is Service-owned.  MainActivity must not run a
                // second UDP discovery controller with an unbound DatagramSocket.
                tvStatus.text = "🔍 Searching for server..."
                tvStatusHint.text = "Discovering on the current network..."
                safeSpeak("Searching for server")
                setRadarState(RadarState.DISCOVERING)
                requestOneShotServiceAutoDiscovery()
            } else {
                val manualIp = etIp.text.toString().trim()
                val manualPort = etPort.text.toString().trim()

                // When Auto-reconnect is OFF, an explicit Connect from MainActivity
                // must first decide whether the saved endpoint belongs to the current
                // network. Never silently reuse a stale endpoint after a subnet/gateway change.
                if (manualIp.isBlank() && manualPort.isBlank()) {
                    val savedServerTarget = readSavedServerTarget()
                    if (savedServerTarget != null) {
                        if (lastSuccessfulNetworkSignatureMatchesCurrent()) {
                            promptUseLastSavedServer(savedServerTarget) { target ->
                                etIp.setText(target.ip)
                                etPort.setText(target.port.toString())
                                startManualConnect(target.ip)
                            }
                        } else {
                            openSetupForNetworkChange()
                        }
                    } else {
                        toast("Enter valid IP and Port No")
                        safeSpeak("Enter correct IP address and port Number of your audio server")
                    }
                    return@setOnClickListener
                }

                if (manualIp.isBlank() || manualPort.isBlank() || manualPort != "50007") {
                    // 🎯 FIX: Prompt if IP is blank,regardless of whether port has default 50007 or user enters wrong port
                    val savedServerTarget = readSavedServerTarget()

                    if ( savedServerTarget != null && savedServerTarget.ip.isNotBlank()) {
                        promptUseLastSavedServer(savedServerTarget) { target ->
                            etIp.setText(target.ip)
                            etPort.setText(target.port.toString())
                            startManualConnect(target.ip)
                        }
                    } else {
                        toast("Enter valid IP and Port No")
                        safeSpeak(
                            "Enter correct IP address and port Number of your audio server"
                        )
                    }

                    return@setOnClickListener
                }

                if (manualIp.isBlank()) {
                    toast("Enter valid IP address")
                    safeSpeak("Enter correct IP address of your audio server")
                    return@setOnClickListener
                }

                if (manualPort.isBlank() || manualPort.toIntOrNull() == null) {
                    toast("Enter valid Port No")
                    safeSpeak("Enter correct port Number of your audio server")
                    return@setOnClickListener
                }

                startManualConnect(manualIp)
            }
        }



        btnDisconnect.setOnClickListener {
            isUserPaused = true
            isUserDisconnected = true // 🎯 User intentionally wants to stop
            wasDisconnectedWhileBackgrounded = false

            saveAppState()

            safeSpeak("Stopping Audio Streaming ")

            val service = AudioStreamingService.instance

            if (service != null) {
                service.pauseByUser()
            } else {
                Log.w(TAG, "⚠️ AudioStreamingService instance unavailable while stopping from Main UI")

                clearDisconnectedUi(closeApp = false)
            }

        }


        btnScanQr.setOnClickListener {
          if (!isConnected) {

                safeSpeak("Opening camera to scan QR code")
                val options = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Point camera at the Server QR Code")
                    setCameraId(0) // Back camera
                    setBeepEnabled(true)
                    setBarcodeImageEnabled(false)
                    // 🎯 FIX: Force orientation lock to true, and use our custom Portrait activity
                    setOrientationLocked(true)
                    setCaptureActivity(CustomScannerActivity::class.java)
                    // 🎯 Pass mixed-mode & try-harder flags to the intent
                    addExtra("SCAN_TYPE", 2)
                    addExtra("TRY_HARDER", true)
                }
                qrScannerLauncher.launch(options)
            } else{
                safeSpeak("Already Streaming")
                toast("Already Streaming")
            }
            if (isModeSelectionLocked()) {
                showModeLockedMessage()
                return@setOnClickListener
            }
        }
        // Volume control with enhanced range
        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    if (progress > 0 && isMuted) {
                        isMuted = false
                        btnMute.text = "🔊"
                    }
                    setTrackVolume(progress / 100f)
                    if (!isStreaming && progress % 25 == 0) {
                        safeSpeak("Volume $progress percent")
                    }
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                saveAppState()
            }
        })


        btnMute.setOnClickListener {
            toggleMute()
        }

        btnClearEndpoint.setOnClickListener {
            etIp.text?.clear()
            etPort.text?.clear()
            etIp.requestFocus()
            toast("IP/Port cleared")
            safeSpeak("IP and Port cleared")
        }

    }


    private fun startManualConnect(manualIp: String) {
        // Manual mode must not depend on the discovery service.  A valid manual
        // endpoint is already the user's authority; the real proof of reachability
        // is the Service UDP handshake (CLIENT_INFO -> SERVER_ACK).  The old code
        // incorrectly required a response from discovery ports 50000/32000 first,
        // which made a perfectly valid manual/QR connection fail when discovery
        // packets were filtered or the server was reached through a non-broadcast path.
        tvStatus.text = "🔄 Connecting to $manualIp:${etPort.text}…"
        tvStatusHint.text = "Validating server with UDP handshake…"
        safeSpeak("Connecting to server")
        serverDisplayName = "PC at $manualIp"
        detectedServerBackend = "Awaiting server handshake"
        tvServerInfo.text = "Server : resolving identity from live handshake…"
        if (::tvConnectionPath.isInitialized) {
            tvConnectionPath.text = "Path: —"
        }
        startStreaming()
    }

    private fun openWirelessSettingsIfWifiDisabled() {
        // Called as part of a mode switch, not from an explicit Settings button.
        // Never launch Wi-Fi Settings automatically here: older OEMs can hide a
        // phone-hosted SoftAP from ConnectivityManager, so that launch can break
        // the very hotspot route the user is trying to use.
        if (streamingMode != "wireless") return
        if (hasActiveMobileHotspotInterface()) {
            Log.i(TAG, "📱 Mobile Hotspot interface is available; Wi-Fi client settings are not needed")
            return
        }

        val wifiEnabled = try {
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
        } catch (_: Exception) { false }
        if (wifiEnabled) {
            Log.d(TAG, "🛜 Wireless mode selected; Wi-Fi is already enabled")
            return
        }

        Log.w(TAG, "🛜 Wireless mode selected while Wi-Fi client radio is off; leaving Mobile Hotspot/interface detection undisturbed")
        tvStatus.text = "📱🛜 Wi-Fi client route is not visible — checking Mobile Hotspot. Enable Wi-Fi only for Home Wi-Fi or PC Hotspot."
        tvStatusHint.text = "Use Open Network Settings if you want to turn Wi-Fi on manually."
        toast("Wi-Fi is off. Mobile Hotspot can still be available; open network settings manually if needed.")
    }

    private fun openUsbTetheringSettings() {
        val tetherIntent = Intent("android.settings.TETHER_SETTINGS")

        try {
            startActivity(tetherIntent)
            Log.i(TAG, "🔌 Opened Android USB tethering / hotspot settings")
            safeSpeak("Opening Android USB tethering / hotspot settings")
            return
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Direct tethering settings unavailable: ${e.message}")
            safeSpeak("Direct tethering settings unavailable")
        }

        try {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            Log.i(TAG, "🔌 Opened Android wireless settings as tethering fallback")
            safeSpeak("Opening wireless settings")
            return
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Wireless settings fallback failed: ${e.message}")
        }

        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (e: Exception) {
            Log.e(TAG, "❌ Unable to open Android settings: ${e.message}", e)
        }
    }

    private fun startStreaming() {
        val rawIp = etIp.text.toString().trim()
        val ip = normalizeServerIp(rawIp).orEmpty()
        val port = etPort.text.toString().trim().toIntOrNull() ?: 0
        if (ip.isBlank() || port <= 0) {
            toast("Enter valid IP & Port")
            safeSpeak("Enter Correct Address and port of Your Audio Server  ")
            return
        }

        // Do not start the connection/watchdog until the selected transport is usable.
        if (!isTransportReadyForStreaming()) {
            showTransportUnavailable()
            return
        }

        // Replace any composite/legacy value shown in the UI
        // before it can reach the native layer.
        if (rawIp != ip) {
            etIp.setText(ip)
        }
        isHotspotConnection = ip.startsWith("10.") || ip.startsWith("172.") || ip.contains("192.168.43") || ip.contains("192.168.137")
        lastConnectedIp = ip
        lastConnectedPort = port
        isUserPaused = false
        isUserDisconnected = false
        wasDisconnectedWhileBackgrounded = false

        saveAppState()

        btnConnect.isEnabled = false
        btnDisconnect.isEnabled = true
        connectionUiState = ConnectionUiState.CONNECTING
        lastStatsPacketCount = 0
        lastStatsSampleAtMs = 0L
        syncModeButtonsEnabled()

        // FIXED: Clear previous status before showing connecting
        tvStatus.text = "🔄 Connecting to server at $ip:$port…"
        tvStatusHint.text = "Waiting for first audio packet..."

        startConnectionWatchdog(ip, port)

        // MainActivity.kt – call before starting the service (e.g., in startStreaming())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
                // Return early; start the service after permission result.
                return
            }
        }

        // Start and bind service
        val svc = Intent(this, AudioStreamingService::class.java).apply {
            putExtra("IP", ip)
            putExtra("PORT", port)
            putExtra("SERVER_NAME", serverDisplayName)
            putExtra("SERVER_BACKEND", detectedServerBackend)
            putExtra("AUTO_RECONNECT", autoReconnect)
            putExtra("AUTO_MODE",rbAuto.isChecked)

            putExtra("IS_HOTSPOT", isHotspotConnection)


            putExtra("DEVICE_MANUFACTURER", Build.MANUFACTURER ?: "Unknown")
            // 🎯 FIX: Send the translated friendly name as the model so the Server UI looks clean
            putExtra("DEVICE_MODEL", Build.MODEL ?: "Unknown")
            putExtra("DEVICE_NAME", deviceName)

            // Use the user's selected four-mode buffer on every transport.
            putExtra("BUFFER_MS", selectedBufferMs)
            putExtra("BUFFER_MODE", bufferModeKey)
            putExtra("CUSTOM_BUFFER_MS", customBufferMs)
            putExtra("IS_WIRED", streamingMode == "wired")
        }

        ContextCompat.startForegroundService(this, svc)

        // Do not apply a saved app-local volume on connection. The slider now
        // controls Android STREAM_MUSIC directly; the connection intent above
        // already carries endpoint/server metadata.

        bindService(svc, connection, Context.BIND_AUTO_CREATE)

        // UI will be updated dynamically in monitorHeartbeat once packets arrive.
    }

    private fun stopStreaming() {
        val svc = Intent(this, AudioStreamingService::class.java)
        try {
            stopService(svc)
        } catch (_: Throwable) {}
        if (isBound) {
            try { unbindService(connection) } catch (_: Throwable) {}
            isBound = false
        }
        audioStreamingService = null

        isConnected = false
        isStreaming = false
        connectionUiState = ConnectionUiState.IDLE

        stopPollingConnectionStats()

        serverDisplayName = "Unknown"
        detectedServerBackend = "Unknown"

        resetButtonStates()
        syncModeButtonsEnabled()
        tvStatus.text = "❌ Disconnected"
        tvStatusHint.text = ""

        tvPackets.text = "Packets: 0"
        tvLatency.text = "Latency: —"
        tvJitter.text = "Jitter: —"
        tvSignal.text = "Signal: —"

        pbLatency.progress = 0
        pbJitter.progress = 0
        pbSignal.progress = 0

        tvConnectionQuality.text = "Connect with WifiAudioServer"


        // Foreground notification is owned by AudioStreamingService.
        // Activity recreation must never cancel it.
        saveAppState()

    }


    override fun onNewIntent(intent: Intent?) {

        super.onNewIntent(intent)

        setIntent(intent)

        handleNotificationActivityIntent(
            intent
        )
    }

    override fun onPause() {
        // The Service owns audio/network work. The Activity must become as
        // render-light as possible while a system surface (especially the
        // phone-call UI) is taking focus. This device has a documented
        // RenderThread/EGL abort in exactly that transition.
        stopLanRadarAnimation()
        stopWiredRadarAnimation()

        super.onPause()
        if (isUserDisconnected) {
            wasDisconnectedWhileBackgrounded = true
        }
    }


    private fun setRadarState(state: RadarState) {
        // Always re-apply the label/icon even when the enum is unchanged.
        // The prior early return allowed the XML's initial "Connected" caption
        // to survive an initial DISCONNECTED state, causing the split-status
        // screen visible in the user's screenshot.
        currentRadarState = state

        when(state){

            RadarState.IDLE -> {

                tvRadarCaption.text = "Idle"
                tvRadarCaption.setTextColor(Color.LTGRAY)
                radarCenterIcon.text = "🛜"
                radarCenterIcon.alpha = 0.6f
                updateRadarSpeed(2600L)

            }

            RadarState.DISCOVERING -> {

                tvRadarCaption.text = "Searching LAN..."
                tvRadarCaption.setTextColor(Color.CYAN)
                radarCenterIcon.text = "📡"
                radarCenterIcon.alpha = 1f
                updateRadarSpeed(1100L)

            }

            RadarState.MANUAL_READY -> {

                tvRadarCaption.text = "Manual Mode"
                radarCenterIcon.text = "🛜"
                radarCenterIcon.alpha = 1f
                updateRadarSpeed(2200L)

            }

            RadarState.CONNECTING -> {

                tvRadarCaption.text = "Connecting..."
                radarCenterIcon.text = "🔄"
                radarCenterIcon.alpha = 1f
                tvRadarCaption.setTextColor(Color.YELLOW)
                updateRadarSpeed(650L)

            }

            RadarState.CONNECTED -> {

                tvRadarCaption.text = "Connected"
                tvRadarCaption.setTextColor(Color.parseColor("#00E676"))
                radarCenterIcon.text = "💻🔗📱 ✅"
                radarCenterIcon.alpha = 1f
                stopLanRadarAnimation()

                radarRingOuter.alpha = 1f
                radarRingMiddle.alpha = 1f
                radarRingInner.alpha = 1f

            }

            RadarState.DISCONNECTED -> {

                tvRadarCaption.text = "Disconnected"
                tvRadarCaption.setTextColor(Color.LTGRAY)
                radarCenterIcon.text= "💻🔗📱 ❎ "
                radarCenterIcon.alpha = 1f
                updateRadarSpeed(1800L)
                startLanRadarAnimation()

            }

            RadarState.FAILED -> {

                tvRadarCaption.text = "Server Offline"
                tvRadarCaption.setTextColor(Color.RED)
                radarCenterIcon.text = "❌"
                radarCenterIcon.alpha = 1f
                updateRadarSpeed(450L)


            }

        }

    }

    private var wiredRadarAnimator: AnimatorSet? = null

    private fun syncRadarCenterForMode() {
        val wired = streamingMode == "wired"
        radarCenterIcon.visibility = if (wired) View.GONE else View.VISIBLE
        radarCenterImage.visibility = if (wired) View.VISIBLE else View.GONE

        if (wired) {
            // The wireless TextView is hidden in wired mode; stop its animation
            // so both center visuals never animate simultaneously.
            stopLanRadarAnimation()
            startWiredRadarAnimation()
        } else {
            stopWiredRadarAnimation()
        }
    }

    private fun startWiredRadarAnimation() {
        if (wiredRadarAnimator?.isRunning == true) return

        val scaleX = ObjectAnimator.ofFloat(radarCenterImage, View.SCALE_X, 0.88f, 1.08f)
        val scaleY = ObjectAnimator.ofFloat(radarCenterImage, View.SCALE_Y, 0.88f, 1.08f)
        val alpha = ObjectAnimator.ofFloat(radarCenterImage, View.ALPHA, 0.65f, 1.0f)

        wiredRadarAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 900L
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (streamingMode == "wired" && !isFinishing && !isDestroyed) {
                        wiredRadarAnimator = null
                        startWiredRadarAnimation()
                    }
                }
            })
            start()
        }
    }

    private fun stopWiredRadarAnimation() {
        wiredRadarAnimator?.cancel()
        wiredRadarAnimator = null
        radarCenterImage.scaleX = 1f
        radarCenterImage.scaleY = 1f
        radarCenterImage.alpha = 1f
    }

    private fun registerServiceUiReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_UI_RESET)
            addAction(ACTION_UI_CLOSE)
            addAction(ACTION_UI_RECONNECTING)
            addAction(ACTION_UI_CONNECTED)
            addAction(ACTION_UI_CONNECTION_PATH_CHANGED)
            addAction(ACTION_UI_TRANSPORT_MODE_CHANGED)
            addAction(ACTION_UI_SYNC_MUTE)
            addAction(ACTION_UI_PC_STATE)
            addAction(ACTION_UI_SYNC_QUALITY_MODE)
            addAction("com.example.wifiaudiospeaker.ACTION_UI_TOGGLE_MUTE")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceUiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(serviceUiReceiver, filter)
        }
    }

    private fun clearDisconnectedUi(closeApp: Boolean) {
        runOnUiThread {
            connectionUiState = ConnectionUiState.IDLE
            isConnected = false
            isStreaming = false // 🎯 FIX: Reset streaming state
            serverDisplayName = "Unknown"
            detectedServerBackend = "Unknown"

            tvStatus.text = "❌ Disconnected"
            tvStatusHint.text = ""
            tvServerInfo.text = "Server : Unknown"
            val savedPath = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                .getString("last_connection_path", "")
                .orEmpty()
            lastRenderedConnectionPath = if (savedPath.isNotBlank()) "Path: $savedPath" else "Path: —"
            tvConnectionPath.text = lastRenderedConnectionPath

            tvPackets.text = "Packets: 0"
            tvLatency.text = "Latency: —"
            tvJitter.text = "Jitter: —"
            tvSignal.text = "Signal: —"

            pbLatency.progress = 0
            pbJitter.progress = 0
            pbSignal.progress = 0

            tvConnectionQuality.text = "Connect to WifiAudioServer"
            resetButtonStates()
            syncModeButtonsEnabled()
            updatePcControlButtons(false, true, false, false)
            stopPollingConnectionStats()

            if (closeApp) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    finishAndRemoveTask()
                } else {
                    finishAffinity()
                }
            }
        }
    }


    private fun resetButtonStates() {
        btnConnect.isEnabled = true
        btnDisconnect.isEnabled = false
    }



    private fun readIntPrefCompat(
        prefs: android.content.SharedPreferences,
        key: String,
        defaultValue: Int = 0
    ): Int {
        return try {
            prefs.getInt(key, defaultValue)
        } catch (_: ClassCastException) {
            try {
                prefs.getString(key, null)?.toIntOrNull() ?: defaultValue
            } catch (_: Exception) {
                defaultValue
            }
        }
    }

    private fun readSavedServerTarget(): SavedServerTarget? {
        val prefs = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
        val rawSavedIp = prefs.getString("server_ip", prefs.getString("last_ip", ""))?.trim().orEmpty()
        val savedIp = normalizeServerIp(rawSavedIp).orEmpty()
        val portFromInt = readIntPrefCompat(prefs, "server_port", 0)
        val savedPort = if (portFromInt > 0) {
            portFromInt
        } else {
            prefs.getString("last_port", null)?.toIntOrNull() ?: 0
        }

        return if (savedIp.isNotBlank() && savedPort > 0) {
            SavedServerTarget(savedIp, savedPort)
        } else {
            null
        }
    }

    private fun currentNetworkSignature(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networks = cm.allNetworks.toList()
            val parts = networks.mapNotNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                val lp = cm.getLinkProperties(network) ?: return@mapNotNull null
                val iface = lp.interfaceName.orEmpty().lowercase(Locale.US)
                val isRelevant = if (streamingMode == "wired") {
                    iface.contains("rndis") || iface.contains("usb") || iface.contains("usb0") || iface.contains("ecm") || iface.contains("ncm")
                } else {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || isLikelyHotspotInterface(iface)
                }
                if (!isRelevant) return@mapNotNull null
                val localIp = lp.linkAddresses.mapNotNull { it.address as? Inet4Address }.firstOrNull()?.hostAddress.orEmpty()
                val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress.orEmpty()
                if (localIp.isBlank() && gateway.isBlank()) null else "$iface|$localIp|$gateway"
            }.sorted()
            if (parts.isNotEmpty()) parts.joinToString(";") else ""
        } catch (e: Exception) {
            Log.w(TAG, "Unable to determine current network signature: ${e.message}")
            ""
        }
    }

    private fun isLikelyHotspotInterface(iface: String): Boolean {
        return iface.contains("ap0") || iface.contains("softap") || iface.contains("swlan") ||
                iface.contains("wlan1") || iface.contains("wlan2") ||
                iface.contains("tether") || iface.contains("hotspot")
    }

    private fun hasActiveMobileHotspotInterface(): Boolean {
        // Use the shared live topology resolver. Do not infer SoftAP readiness
        // from a short hard-coded list of OEM subnet prefixes.
        return try {
            MobileHotspotDatabase.detect(
                this,
                MobileHotspotDatabase.WirelessPath.MOBILE_HOTSPOT
            ).isReady
        } catch (e: Exception) {
            Log.w(TAG, "Unable to resolve phone-hosted hotspot interface: ${e.message}")
            false
        }
    }

    private fun lastSuccessfulNetworkSignatureMatchesCurrent(): Boolean {
        val prefs = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
        val saved = prefs.getString("last_network_signature", "").orEmpty()
        val current = currentNetworkSignature()
        if (saved.isBlank() || current.isBlank()) {
            Log.w(TAG, "Network signature unavailable; requiring SetupActivity instead of guessing.")
            return false
        }

        fun networkKey(signature: String): Pair<String, String> {
            val parts = signature.split('|')
            val iface = parts.getOrNull(0).orEmpty()
            val gateway = parts.getOrNull(2).orEmpty()
            return iface to gateway
        }

        val (savedIface, savedGateway) = networkKey(saved)
        return current.split(';').any { candidate ->
            val (iface, gateway) = networkKey(candidate)
            // Interface + gateway is intentionally used instead of the exact
            // local DHCP address.  A DHCP renewal can change the phone IP while
            // it is still on the same LAN; a gateway change identifies the real
            // Home-WiFi <-> PC-Hotspot transition we care about.
            savedIface.isNotBlank() &&
                    iface == savedIface &&
                    savedGateway.isNotBlank() &&
                    gateway == savedGateway
        }
    }

    private fun openSetupForNetworkChange() {
        val intent = Intent(this, SetupActivity::class.java).apply {
            putExtra("NETWORK_CHANGED", true)
            putExtra("FORCE_AUTO_DISCOVERY", true)
        }
        startActivity(intent)
        tvStatusHint.text = "Network changed — use Auto Discover on the current network"
    }

    private fun promptUseLastSavedServer(target: SavedServerTarget, onUse: (SavedServerTarget) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Use last saved server?")
            .setMessage("Connect to ${target.ip}:${target.port} from the last saved session?")
            .setPositiveButton("Yes") { _, _ -> onUse(target) }
            .setNegativeButton("No") { _, _ ->
                // Explicit user choice: do not open SetupActivity and do not
                // depend on the Auto-Reconnect preference. Run one silent
                // discovery directly from MainActivity on the current network.
                silentAutoDiscoverFromMain()
            }
            .show()
    }

    private fun silentAutoDiscoverFromMain() {
        isUserDisconnected = false
        rbAuto.isChecked = true
        rbManual.isChecked = false
        tvStatus.text = "🔎 Searching current network…"
        tvStatusHint.text = "Auto-discovering WiFi Audio Server"
        setRadarState(RadarState.DISCOVERING)
        btnConnect.isEnabled = false
        btnDisconnect.isEnabled = true

        discoverServer { ip, port ->
            if (isConnected || connectionUiState == ConnectionUiState.CONNECTING) return@discoverServer
            etIp.setText(ip)
            etPort.setText(port.toString())
            startStreaming()
        }
    }
    // Discovery with hotspot gateway/broadcast probe first
    private fun discoverServer(onFound: (String, Int) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {

            Log.i("Discovery", "🎯 Starting enhanced discovery for dynamic hotspot ranges...")


            // Step 1) Dynamic subnet detection (covers 80% of cases)
            if (probeGatewayAndBroadcast(onFound)) return@launch

            // Step 2: Multi-subnet fallback (covers remaining 18% of cases)
            if (fallbackMultiSubnetDiscovery(onFound)) return@launch

            // Step 3) Fallback to native comprehensive discovery (final 2% edge cases)
            try {
                val portOut = IntArray(1)
                val nativeResult = nativeDiscoverServer(portOut)
                if (nativeResult != null && portOut[0] > 0) {

                    val nativeParts = nativeResult.split("|", limit = 3)
                    val nativeIp = nativeParts[0]

                    if (nativeParts.size >= 2 && nativeParts[1].isNotBlank()) {
                        serverDisplayName = nativeParts[1]
                    }

                    if (nativeParts.size >= 3 && nativeParts[2].isNotBlank()) {
                        detectedServerBackend = nativeParts[2]
                    }

                    withContext(Dispatchers.Main) {
                        Log.i("Discovery", "🎉 NATIVE DISCOVERY SUCCESS: $nativeIp :${portOut[0]} ($serverDisplayName)")
                        tvServerInfo.text = " Server : $serverDisplayName"
                        onFound(nativeIp, portOut[0])
                    }
                    return@launch
                }
            } catch (e: Exception) {
                Log.e("Discovery", "❌ Native discovery failed: ${e.message}")
            }

            // All discovery methods failed
            withContext(Dispatchers.Main) {
                toast("❌ No server found in your Network")
                tvStatus.text = "❌ Discovery failed - Check server IP or try Manual mode"
                btnConnect.isEnabled = true
                btnDisconnect.isEnabled = false
                setRadarState(RadarState.FAILED)
                safeSpeak("No WiFi audio server found. Try manual connection.")
            }
        }
    }

    private fun updateServerInfoCard(
        identity: ServerIdentity
    ) {
        serverDisplayName = identity.name
        detectedServerBackend = identity.backend

        serverNetworkType = identity.networkType
        serverAdapterName = identity.adapter
        serverSsid = identity.ssid

        tvServerInfo.text =
            "Server PC: ${identity.name}\n" +
                    "PC Network: ${identity.networkType}\n" +
                    "Adapter: ${identity.adapter}\n" +
                    "SSID: ${identity.ssid}"
    }

    private fun probeManualServerIdentity(hostIp: String): ServerIdentity? {
        return try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 1200
                val payload = "DISCOVER_WIFI_AUDIO_REQUEST".toByteArray()
                val discoveryPorts = intArrayOf(50000, 32000)

                for (discoveryPort in discoveryPorts) {
                    try {
                        val request = DatagramPacket(
                            payload,
                            payload.size,
                            InetAddress.getByName(hostIp),
                            discoveryPort
                        )
                        sock.send(request)

                        val buf = ByteArray(512)
                        val response = DatagramPacket(buf, buf.size)
                        sock.receive(response)

                        val resp = String(response.data, 0, response.length).trim()
                        val identity =
                            parseServerDiscoveryResponse(resp)

                        if (identity != null) {
                            return identity
                        }
                        // The shared parser above already handles both:
                        // 1) legacy WIFI_AUDIO_SERVER responses
                        // 2) current responses containing pipe-separated metadata.
                        //
                        // No second parser is required here.

                    } catch (_: Exception) {
                        // try next discovery port
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w("Discovery", "Manual probe failed: ${e.message}")
            null
        }
    }

    private fun getLikelyTetherInterface(): NetworkInterface? {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .firstOrNull { iface ->
                    val name = iface.name.lowercase(Locale.US)
                    val up = try { iface.isUp } catch (_: Exception) { false }
                    up && isLikelyUsbInterface(name) &&
                            Collections.list(iface.inetAddresses).any {
                                it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress
                            }
                }
        } catch (e: Exception) {
            Log.w(
                "Discovery",
                "⚠️ USB tether interface detection failed: ${e.message}"
            )
            null
        }
    }

    private fun hasUsableLocalUsbTetherInterface(): Boolean {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces()).any { iface ->
                val name = iface.name.lowercase(Locale.US)
                val up = try { iface.isUp } catch (_: Exception) { false }
                up && isLikelyUsbInterface(name) &&
                        Collections.list(iface.inetAddresses).any { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            }
        } catch (_: Exception) { false }
    }

    private fun hasUsableLocalWirelessInterface(): Boolean {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces()).any { iface ->
                val name = iface.name.lowercase(Locale.US)
                val up = try { iface.isUp } catch (_: Exception) { false }
                val wifiLike = name.contains("wlan") || name.contains("wifi") || isLikelyHotspotInterface(name)
                up && wifiLike && Collections.list(iface.inetAddresses).any { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            }
        } catch (_: Exception) { false }
    }

    private fun findCurrentModeNetwork(): Network? {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.firstOrNull { networkMatchesCurrentMode(it) }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not select current $streamingMode network: ${e.message}")
            null
        }
    }

    private fun probeGatewayAndBroadcast(onFound: (String, Int) -> Unit): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val payload = "DISCOVER_WIFI_AUDIO_REQUEST".toByteArray(Charsets.UTF_8)
            val ports = intArrayOf(50000, 32000, 50007)

            data class Binding(val name: String, val local: Inet4Address, val broadcast: Inet4Address?)
            val bindings = mutableListOf<Binding>()
            Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { iface ->
                val name = iface.name.lowercase(Locale.US)
                val up = try { iface.isUp } catch (_: Exception) { false }
                if (!up || name == "lo" || name.startsWith("tun") || name.startsWith("p2p")) return@forEach
                val relevant = if (streamingMode == "wired") {
                    isLikelyUsbInterface(name)
                } else {
                    name.contains("wlan") || name.contains("wifi") || isLikelyHotspotInterface(name)
                }
                if (!relevant) return@forEach
                iface.interfaceAddresses.forEach { ia ->
                    val local = ia.address as? Inet4Address ?: return@forEach
                    if (!local.isLoopbackAddress && !local.isLinkLocalAddress) {
                        bindings.add(Binding(iface.name, local, ia.broadcast as? Inet4Address))
                    }
                }
            }

            // Also include a ConnectivityManager route only when it matches
            // the selected transport. In Wired mode, ordinary Wi-Fi must never
            // leak into discovery just because it is the default Internet route.
            cm.allNetworks.forEach { n ->
                if (!networkMatchesCurrentMode(n)) return@forEach
                val lp = cm.getLinkProperties(n) ?: return@forEach
                lp.linkAddresses.mapNotNull { it.address as? Inet4Address }.forEach { local ->
                    if (!local.isLoopbackAddress && !local.isLinkLocalAddress && bindings.none { it.local.hostAddress == local.hostAddress }) {
                        bindings.add(Binding(lp.interfaceName ?: "cm", local, null))
                    }
                }
            }

            if (bindings.isEmpty()) {
                Log.w("Discovery", "No local discovery interface available")
                return false
            }

            for (binding in bindings.distinctBy { "${it.name}|${it.local.hostAddress}" }) {
                val localIp = binding.local.hostAddress ?: continue
                val parts = localIp.split('.')
                val fallbackBroadcast = if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.255" else null
                val targets = linkedSetOf<String>()
                binding.broadcast?.hostAddress?.let { targets.add(it) }
                fallbackBroadcast?.let { targets.add(it) }
                if (parts.size == 4) {
                    targets.add("${parts[0]}.${parts[1]}.${parts[2]}.1")
                    targets.add("${parts[0]}.${parts[1]}.${parts[2]}.254")
                    targets.add("${parts[0]}.${parts[1]}.${parts[2]}.100")
                    targets.add("${parts[0]}.${parts[1]}.${parts[2]}.170")
                    targets.add("${parts[0]}.${parts[1]}.${parts[2]}.2")
                }

                runCatching {
                    cm.allNetworks.asSequence().mapNotNull { n ->
                        val lp = cm.getLinkProperties(n) ?: return@mapNotNull null
                        if (lp.interfaceName.equals(binding.name, true)) {
                            lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
                        } else null
                    }.firstOrNull()
                }.getOrNull()?.let { targets.add(it) }

                Log.i("Discovery", "🌐 Interface-bound discovery: ${binding.name} local=$localIp targets=${targets.joinToString()}")

                DatagramSocket(null).use { sock ->
                    sock.broadcast = true
                    sock.reuseAddress = true
                    sock.soTimeout = 150
                    val routeBound = try {
                        sock.bind(java.net.InetSocketAddress(binding.local, 0))
                        true
                    } catch (e: Exception) {
                        Log.w("Discovery", "⚠️ Could not bind ${binding.name}; skipping this interface: ${e.message}")
                        false
                    }
                    if (!routeBound) return@use
                    for (target in targets) for (port in ports) {
                        try { sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName(target), port)) } catch (_: Exception) {}
                    }
                    val deadline = SystemClock.elapsedRealtime() + 1000L
                    while (SystemClock.elapsedRealtime() < deadline) {
                        try {
                            val pkt = DatagramPacket(ByteArray(1024), 1024)
                            sock.receive(pkt)
                            val identity = parseServerDiscoveryResponse(String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim()) ?: continue
                            serverDisplayName = identity.name
                            detectedServerBackend = identity.backend
                            runOnUiThread { updateServerInfoCard(identity); onFound(identity.ip, identity.port) }
                            Log.i("Discovery", "🎉 Interface discovery success via ${binding.name}: ${identity.ip}:${identity.port}")
                            return true
                        } catch (_: java.net.SocketTimeoutException) {}
                    }
                }
            }
            false
        } catch (e: Exception) {
            Log.e("Discovery", "Dynamic interface discovery failed: ${e.message}")
            false
        }
    } // Returns true if found



    private fun isClearlyWirelessInterface(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("wlan") || n.contains("wifi") || n.contains("wi-fi") ||
                n.contains("wireless") || n.contains("802.11") || n.startsWith("wl")
    }

    private fun isLikelyUsbInterface(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        if (isClearlyWirelessInterface(n) || n.startsWith("rmnet") || n.startsWith("ccmni") ||
            n.startsWith("tun") || n == "lo") return false
        return n.contains("rndis") || n.contains("remote_ndis") || n.contains("usb") ||
                n.contains("usbnet") || n.contains("ecm") || n.contains("ncm") ||
                n.contains("tether") || n.contains("cdc") || n.contains("ethernet") ||
                n == "eth0" || n.startsWith("eth") || n == "en0" ||
                n.startsWith("enx") || n.startsWith("enp") || n.startsWith("ens")
    }

    private fun isWiredNetworkCandidate(
        interfaceName: String,
        capabilities: NetworkCapabilities
    ): Boolean {
        val n = interfaceName.lowercase(Locale.US)
        if (isClearlyWirelessInterface(n) || n.startsWith("rmnet") || n.startsWith("ccmni") ||
            n.startsWith("tun") || n == "lo") return false
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return true
        return isLikelyUsbInterface(n) && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    }


    // 🔄 FALLBACK: Multi-subnet scanning when dynamic detection fails
    private fun fallbackMultiSubnetDiscovery(onFound: (String, Int) -> Unit): Boolean {
        Log.i("Discovery", "🔄 Starting fallback multi-subnet discovery...")

        val commonSubnets = listOf(
            "192.168.43",   // Classic Android hotspot
            "192.168.42",   // Android USB tethering default
            "192.168.44",   // OEM USB tethering
            "192.168.49",   // OEM USB tethering
            "192.168.234",  // Samsung hotspot variant
            "192.168.1",    // Router default
            "192.168.0",    // Alternative router
            "10.0.0",       // iOS hotspot
            "10.42.0",      // Linux hotspot
            "10.53.148",    // Realme GT range
            "192.168.6",    // Samsung hotspot
            "172.20.10",    // iOS alternative
            "192.168.137",  // Windows mobile hotspot
            "10.185.37"     // OnePlus range
        )

        return try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 1500
                val payload = "DISCOVER_WIFI_AUDIO_REQUEST".toByteArray()

                commonSubnets.forEach { subnet ->
                    Log.d("Discovery", "🔍 Scanning subnet: $subnet.x")

                    // Priority IPs in each subnet
                    val priorityIPs = listOf(1, 170, 2, 100, 254)
                    val ports = intArrayOf(50000, 32000)

                    priorityIPs.forEach { lastOctet ->
                        val targetIP = "$subnet.$lastOctet"
                        ports.forEach { port ->
                            try {
                                sock.send(DatagramPacket(payload, payload.size,
                                    InetAddress.getByName(targetIP), port))
                            } catch (_: Exception) {}
                        }
                    }

                    // Quick check for response
                    try {
                        val buf = ByteArray(512)
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)

                        val resp = String(pkt.data, 0, pkt.length).trim()
                        if (resp.startsWith("WIFI_AUDIO_SERVER:")) {

                            val identity =
                                parseServerDiscoveryResponse(resp)

                            if (identity != null) {

                                serverDisplayName = identity.name
                                detectedServerBackend = identity.backend

                                Log.i(
                                    "Discovery",
                                    "🎉 FALLBACK SUCCESS on $subnet.x: " +
                                            "${identity.ip}:${identity.port} " +
                                            "(${identity.name})"
                                )

                                runOnUiThread {

                                    updateServerInfoCard(identity)

                                    onFound(
                                        identity.ip,
                                        identity.port
                                    )
                                }

                                return true
                            }
                        }
                    } catch (_: Exception) {
                        // Continue to next subnet
                    }
                }

                false
            }
        } catch (e: Exception) {
            Log.e("Discovery", "❌ Fallback discovery failed: ${e.message}")
            false
        }
    }


    private fun updateRadarSpeed(duration:Long){

        stopLanRadarAnimation()

        radarAnimators.clear()

        radarAnimators.add(
            buildPulseAnimator(
                radarRingOuter,
                duration,
                0,
                0.72f,
                1.20f,
                0.55f
            )
        )

        radarAnimators.add(
            buildPulseAnimator(
                radarRingMiddle,
                duration,
                duration/4,
                0.74f,
                1.18f,
                0.65f
            )
        )

        radarAnimators.add(
            buildPulseAnimator(
                radarRingInner,
                duration,
                duration/2,
                0.78f,
                1.16f,
                0.80f
            )
        )

        radarAnimators.add(
            buildPulseAnimator(
                radarCenterIcon,
                (duration / 2).coerceAtLeast(600L),
                0L,
                1.00f,
                1.08f,
                1.00f
            )
        )

        startLanRadarAnimation()

    }

    private fun showBufferModeDialog() {
        val prefs = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
        syncBufferState(BufferModeConfig.load(prefs))

        val content = layoutInflater.inflate(R.layout.dialog_buffer_selection, null)
        val modeSpinner = content.findViewById<Spinner>(R.id.spinnerBufferModeDialog)
        val modeSummary = content.findViewById<TextView>(R.id.tvBufferModeSummaryDialog)
        val customSeek = content.findViewById<SeekBar>(R.id.seekCustomBufferMsDialog)
        val customValue = content.findViewById<TextView>(R.id.tvCustomBufferValueDialog)
        val customHint = content.findViewById<TextView>(R.id.tvCustomBufferHintDialog)

        val modes = listOf(
            BufferModeConfig.Mode.ULTRA_LOW,
            BufferModeConfig.Mode.LOW,
            BufferModeConfig.Mode.MEDIUM,
            BufferModeConfig.Mode.HIGH,
            BufferModeConfig.Mode.CUSTOM
        )
        val labels = modes.map { mode ->
            when (mode) {
                BufferModeConfig.Mode.ULTRA_LOW -> "⚡ Ultra Low · Golden pipeline · 10 ms"
                BufferModeConfig.Mode.LOW -> "Low buffer · 10 ms"
                BufferModeConfig.Mode.MEDIUM -> "Medium buffer · 250 ms"
                BufferModeConfig.Mode.HIGH -> "High buffer · 1500 ms"
                BufferModeConfig.Mode.CUSTOM -> "Custom buffer"
            }
        }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        modeSpinner.adapter = adapter

        customSeek.max = BufferModeConfig.MAX_CUSTOM_MS - BufferModeConfig.MIN_CUSTOM_MS
        customSeek.progress = (customBufferMs - BufferModeConfig.MIN_CUSTOM_MS)
            .coerceIn(0, customSeek.max)

        var selectedMode = BufferModeConfig.Mode.fromKey(bufferModeKey)
        var initialSpinnerCallback = true

        fun updateBufferDialogDetails() {
            modeSummary.text = when (selectedMode) {
                BufferModeConfig.Mode.ULTRA_LOW ->
                    "Golden pipeline: capture-paced 10 ms frames, immediate playback and tight lip-sync control."
                BufferModeConfig.Mode.LOW ->
                    "Low delay for games and video. Best on a stable, low-jitter connection."
                BufferModeConfig.Mode.MEDIUM ->
                    "Balanced delay and tolerance for short network jitter."
                BufferModeConfig.Mode.HIGH ->
                    "More protection against audio hiccups, with increased playback delay."
                BufferModeConfig.Mode.CUSTOM ->
                    "Custom mode uses the slider value below. Drag the slider to adjust from 10 to 2000 ms."
            }
            customValue.text = "Custom buffer: ${customBufferMs} ms"
            customHint.text = "The slider is always available. Dragging it automatically selects Custom."
        }

        modeSpinner.setSelection(modes.indexOf(selectedMode).coerceAtLeast(0), false)
        modeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (initialSpinnerCallback) return
                selectedMode = modes.getOrElse(position) { BufferModeConfig.Mode.MEDIUM }
                updateBufferDialogDetails()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        modeSpinner.post { initialSpinnerCallback = false }

        customSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    customBufferMs = BufferModeConfig.MIN_CUSTOM_MS + progress
                    customValue.text = "Custom buffer: ${customBufferMs} ms"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                selectedMode = BufferModeConfig.Mode.CUSTOM
                modeSpinner.setSelection(modes.indexOf(BufferModeConfig.Mode.CUSTOM), false)
                updateBufferDialogDetails()
            }
        })
        updateBufferDialogDetails()

        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val customMs = BufferModeConfig.MIN_CUSTOM_MS + customSeek.progress
                applyBufferModeSelection(selectedMode, customMs, announce = true)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun syncBufferState(selection: BufferModeConfig.Selection) {
        bufferModeKey = selection.mode.key
        selectedBufferMs = selection.bufferMs
        customBufferMs = selection.customMs
        directMode = selection.mode == BufferModeConfig.Mode.LOW // legacy alias only
        if (::btnSync.isInitialized) updateSyncButton()
    }

    private fun syncBufferStateFromPreferences(prefs: android.content.SharedPreferences) {
        syncBufferState(BufferModeConfig.load(prefs))
    }

    private fun applyBufferModeSelection(
        mode: BufferModeConfig.Mode,
        customMs: Int = customBufferMs,
        announce: Boolean = false
    ) {
        val prefs = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
        val selection = BufferModeConfig.save(prefs, mode, customMs)
        syncBufferState(selection)
        saveAppState()

        sendBroadcast(
            Intent(ACTION_UI_SYNC_QUALITY_MODE)
                .setPackage(packageName)
                .putExtra("buffer_mode", selection.mode.key)
                .putExtra("buffer_ms", selection.bufferMs)
                .putExtra("custom_buffer_ms", selection.customMs)
                .putExtra("button_premium_free", selection.mode == BufferModeConfig.Mode.LOW)
        )

        if (AudioStreamingService.isServiceRunning) {
            try {
                startService(Intent(this, AudioStreamingService::class.java).apply {
                    putExtra("SET_BUFFER_SIZE", selection.bufferMs)
                    putExtra("BUFFER_MODE", selection.mode.key)
                    putExtra("CUSTOM_BUFFER_MS", selection.customMs)
                })
            } catch (e: Exception) {
                Log.e(TAG, "Could not update live buffer mode: ${e.message}", e)
                toast("Buffer preference saved; live update will apply on the next connection")
            }
        }

        updateSyncButton()
        if (announce) {
            toast("🎚️ ${selection.mode.title}: ${selection.bufferMs} ms")
            safeSpeak("Buffer set to ${selection.mode.title}, ${selection.bufferMs} milliseconds")
        }
    }

    private fun toggleMute() {
        if (isMuted) {
            isMuted = false
            btnMute.text = "📱〰🔊"
            seekVolume.progress = lastVolume.toInt()
            setTrackVolume(lastVolume / 100f)
            if (!isStreaming) safeSpeak("Unmuted")
        } else {
            isMuted = true
            // Removed the lastVolume overwrite here so it remembers the real volume!
            btnMute.text = "📱〰🔇"
            seekVolume.progress = 0
            setTrackVolume(0f)
            if (!isStreaming) safeSpeak("Muted")
        }
        saveAppState()
    }

    private fun requestAudioPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissions = arrayOf(
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.MODIFY_AUDIO_SETTINGS,
                Manifest.permission.RECORD_AUDIO
            )

            // In MainActivity.onCreate() or requestPermissions()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1235)
                }
            }
            requestPermissions(permissions, 1234)
        }
    }

    private fun startPollingConnectionStats() {
        stopPollingConnectionStats()
        statsHandler = Handler(Looper.getMainLooper())
        statsRunnable = object : Runnable {
            override fun run() {
                try {
                    val svc = audioStreamingService
                    if (svc != null) {
                        val livePath = try { svc.getCurrentConnectionPathLabel() } catch (_: Exception) { "" }
                        if (livePath.isNotBlank() && livePath != lastRenderedConnectionPath.removePrefix("Path: ").trim()) {
                            lastRenderedConnectionPath = "Path: $livePath"
                            tvConnectionPath.text = lastRenderedConnectionPath
                            getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                                .edit().putString("last_connection_path", livePath).apply()
                        }

                        // ✅ FIX: Prevent ghost stats from updating UI after disconnect
                        if (!AudioStreamingService.isConnectedState) {
                            if (connectionUiState == ConnectionUiState.CONNECTED) {
                                clearDisconnectedUi(false)
                            }
                            // Keep polling loop alive, but skip stats UI update
                            statsHandler?.postDelayed(this, POLL_INTERVAL)
                            return
                        }
                        val stats = svc.getConnectionStats()
                        val quality = svc.getCurrentNetworkQuality()

                        tvPackets.text = "Received: ${stats.packetsReceived}, Dropped: ${stats.packetsDropped}\nUnderruns: ${stats.bufferUnderruns}, Buffer Level: ${stats.bufferLevel}"
                        tvLatency.text = "Latency: ${quality.latency} ms"

                        if (streamingMode != "wired") {
                            tvSignal.text = "Signal: ${quality.signalStrength} dBm"
                            tvJitter.text = "Jitter: ${"%.1f".format(quality.jitter)} ms"
                        }

                        tvServerInfo.text = if (serverDisplayName != "Unknown") {
                            "Server : $serverDisplayName"
                        } else {
                            "Server Backend: $detectedServerBackend"
                        }

                        if (stats.packetsReceived > 0) {
                            if (connectionUiState != ConnectionUiState.CONNECTED) {
                                connectionUiState = ConnectionUiState.CONNECTED
                                connectionTimeoutRunnable?.let { connectionWatchdog.removeCallbacks(it) }
                                tvStatus.text = "✅ Connected to $lastConnectedIp:$lastConnectedPort"
                                setRadarState(RadarState.CONNECTED)

                                val speakName = if (serverDisplayName != "Unknown") {
                                    serverDisplayName.substringBefore(" (").trim()
                                } else {
                                    lastConnectedIp
                                }
                                safeSpeak("Connected to $speakName ")
                                tvConnectionQuality.text = "Quality: Real-Time"
                            }

                            val now = SystemClock.elapsedRealtime()
                            if (lastStatsSampleAtMs > 0L) {
                                val deltaPackets = (stats.packetsReceived - lastStatsPacketCount).coerceAtLeast(0)
                                val deltaMs = (now - lastStatsSampleAtMs).coerceAtLeast(1L)
                                val bytesPerPacket = 964.0
                                val downKbps = (deltaPackets * bytesPerPacket * 1000.0 / deltaMs) / 1024.0
                                tvStatusHint.text = "↓ ${"%.1f".format(downKbps)} KB/s"
                            }
                            lastStatsPacketCount = stats.packetsReceived
                            lastStatsSampleAtMs = now
                        }

                        val latencyScore = (100 - (quality.latency / 2).toInt()).coerceIn(0, 100)
                        val jitterScore = (100 - (quality.jitter * 4).toInt()).coerceIn(0, 100)
                        val signalScore = quality.signalStrength.coerceIn(0, 100)

                        pbLatency.progress = latencyScore
                        if(streamingMode != "wired") {
                            pbJitter.progress = jitterScore
                            pbSignal.progress = signalScore
                        }
                        val health = nativeGetConnectionHealth()
                        val label = when {
                            health > 90 -> "🟢 EXCELLENT"
                            health > 75 -> "🟡 GOOD"
                            health > 50 -> "🟠 FAIR"
                            else -> "🔴 POOR"
                        }
                        tvConnectionQuality.text = "Quality: $label | ServerBackend: $detectedServerBackend"
                    }
                } catch (_: Throwable) { }
                statsHandler?.postDelayed(this, POLL_INTERVAL)
            }
        }
        statsHandler?.post(statsRunnable!!)
    }

    private fun stopPollingConnectionStats() {
        statsRunnable?.let { statsHandler?.removeCallbacks(it) }
        statsRunnable = null
        statsHandler = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MyApplication.CHANNEL_ID,
                "WiFi Audio Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when audio is streaming"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }



    private fun setupLanRadarAnimation() {
        radarCenterIcon.text = "🛜"
        radarCenterIcon.alpha = 1f

        radarAnimators.clear()
        radarAnimators.add(buildPulseAnimator(radarRingOuter, 2200L, 0L, 0.72f, 1.20f, 0.55f))
        radarAnimators.add(buildPulseAnimator(radarRingMiddle, 2200L, 550L, 0.74f, 1.18f, 0.65f))
        radarAnimators.add(buildPulseAnimator(radarRingInner, 2200L, 1100L, 0.78f, 1.16f, 0.80f))
        radarAnimators.add(buildPulseAnimator(radarCenterIcon, 1200L, 0L, 1.00f, 1.08f, 1.00f))
        startLanRadarAnimation()
    }

    private fun buildPulseAnimator(
        target: View,
        durationMs: Long,
        startDelayMs: Long,
        fromScale: Float,
        toScale: Float,
        fromAlpha: Float
    ): AnimatorSet {
        val scaleX = ObjectAnimator.ofFloat(target, View.SCALE_X, fromScale, toScale).apply {
            duration = durationMs
            startDelay = startDelayMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }

        val scaleY = ObjectAnimator.ofFloat(target, View.SCALE_Y, fromScale, toScale).apply {
            duration = durationMs
            startDelay = startDelayMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }

        val alpha = ObjectAnimator.ofFloat(target, View.ALPHA, fromAlpha, 0f).apply {
            duration = durationMs
            startDelay = startDelayMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
        }

        return AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
        }
    }

    private fun startLanRadarAnimation() {
        radarAnimators.forEach { animator ->
            if (animator.isRunning) animator.cancel()
            animator.start()
        }
    }

    private fun stopLanRadarAnimation() {
        radarAnimators.forEach { animator ->
            if (animator.isRunning) animator.cancel()
        }
    }

    private fun setupNavigationDrawer() {
        drawerToggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )

        drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()

        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_wireless_mode, R.id.nav_wired_mode -> {
                    if (isModeSelectionLocked() && !autoReconnect) {
                        showModeLockedMessage()
                    } else {
                        val requestedMode = if (item.itemId == R.id.nav_wired_mode) "wired" else "wireless"
                        setStreamingMode(requestedMode, userInitiated = true)
                    }
                    drawerLayout.closeDrawers()
                    true
                }
                R.id.nav_settings -> {
                    openSettingsActivity()
                    drawerLayout.closeDrawers()
                    true
                }
                R.id.nav_about -> {
                    showAboutDialog()
                    drawerLayout.closeDrawers()
                    true
                }
                else -> false
            }
        }
    }

    private fun networkMatchesCurrentMode(cm: ConnectivityManager): Boolean {
        return try {
            cm.allNetworks.any { network -> networkMatchesCurrentMode(network) }
        } catch (_: Exception) {
            false
        }
    }

    private fun networkMatchesCurrentMode(network: Network): Boolean {
        return try {
            val capabilities =
                connectivityManager.getNetworkCapabilities(network)
                    ?: return false

            val linkProperties =
                connectivityManager.getLinkProperties(network)
                    ?: return false

            val interfaceName = linkProperties.interfaceName
                ?.lowercase(Locale.US)
                .orEmpty()

            if (streamingMode == "wired") {
                // Don't mark USB transport ready until the PC-facing route has
                // received a usable IPv4 address. Ethernet capabilities can
                // appear shortly before tether DHCP/address setup completes.
                val hasUsableIpv4 = linkProperties.linkAddresses.any { linkAddress ->
                    val address = linkAddress.address
                    address is Inet4Address &&
                            !address.isLoopbackAddress &&
                            !address.isLinkLocalAddress
                }
                return hasUsableIpv4 && isWiredNetworkCandidate(interfaceName, capabilities)
            }

            // Wireless mode accepts ordinary Wi-Fi plus phone SoftAP/mobile-hotspot
            // interfaces. Some OEMs do not expose the SoftAP as TRANSPORT_WIFI.
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    isLikelyHotspotInterface(interfaceName)

        } catch (e: Exception) {
            Log.w(
                TAG,
                "⚠️ Network mode check failed: ${e.message}"
            )
            false
        }
    }
    private fun setupNetworkMonitoring() {
        try {
            val req = NetworkRequest.Builder()
                //.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                // 🎯 FIX: Removed NET_CAPABILITY_INTERNET so Hotspots without data are detected!
                // 🎯 Keep this disabled so local-only Wi-Fi / hotspot
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {

                    if (!networkMatchesCurrentMode(network)) {
                        Log.d(
                            TAG,
                            "🛜 Ignoring network ${network.networkHandle} " +
                                    "because it does not match $streamingMode mode"
                        )
                        return
                    }

                    /* ======================================================
                    // IMPORTANT:
                    //
                    // MainActivity NO LONGER binds the process-wide
                    // network here.

                    // 🎯 FIX: Force Android OS to route all native C++ UDP traffic through this specific network!
                    // This permanently solves "Network is unreachable" and auto-discovery failures on Hotspots.
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            connectivityManager.bindProcessToNetwork(network)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            ConnectivityManager.setProcessDefaultNetwork(network)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to bind process to network: ${e.message}")
                    }

                     // AudioStreamingService owns that binding now.
                    // ======================================================*/
                    val netHandle = network.networkHandle

                    // 🎯 FIX: Ignore initial callback upon registration
                    if (!isNetworkMonitoringInitialized) {
                        isNetworkMonitoringInitialized = true
                        activeNetworkHandle = netHandle
                        Log.d(TAG,"🛜 Activity network monitor initialized: " +
                        "$netHandle")
                        return

                    }

                    /* 🎯 FIX: Ignore redundant capability updates on the exact same network if not lost
                    Ignore duplicate callbacks for the same network.*/
                    if (activeNetworkHandle == netHandle) {
                        return
                    }

                    // 🎯 Actual physical network route change or recovery
                    // ======================================================
                    // UI-ONLY NETWORK STATE
                    //
                    // No:
                    //   stopService()
                    //   stopSelf()
                    //   handleDisconnection()
                    //   restart connection
                    // ======================================================
                    val wasLost = (activeNetworkHandle == -1L)
                    Log.i("MainActivity", "🔄 Network route changed or restored (wasLost=$wasLost, old=$activeNetworkHandle, new=$netHandle)")
                    activeNetworkHandle = netHandle
                    isUserDisconnected = false
                    wasDisconnectedWhileBackgrounded = false

                    if (!isUserPaused) {

                        isUserDisconnected = false
                        wasDisconnectedWhileBackgrounded = false

                    } else {

                        Log.d(TAG,
                            "⏸️ Network changed while user-stopped - " + " preserving paused state")
                    }

                    /* ======================================================
                    // DO NOT reconnect from Activity.
                    //

                    if (autoReconnect) {
                        runOnUiThread {
                            Log.i("MainActivity", "🔄 Network route changed, initiating auto-reconnect...")
                            // Cleanly reconnect to adapt to new subnet/gateway
                            // 🎯 Direct termination bypasses Android 8.0+ Background Service limits
                            AudioStreamingService.instance?.let {
                                it.stopStreaming(forceDisconnected = false, notifyUi = false, cancelReconnect = true)
                                it.stopSelf()
                            }
                            handleDisconnection(reason = "network switch", reconnectAfter = true, closeApp = false)
                        }
                    }
                    // AudioStreamingService owns persistent reconnection.
                    // ======================================================*/
                }
                override fun onLost(network: Network) {
                    Log.w(TAG, "🛜 Network lost: ${network.networkHandle}")
                    if (network.networkHandle == activeNetworkHandle || activeNetworkHandle == -1L) {
                        activeNetworkHandle = -1L
                        // AudioStreamingService owns process/network binding and route recovery.
                    }
                    if (isBound) {
                        runOnUiThread {
                            // Wi-Fi radio-off does not imply that the phone-hosted
                            // Mobile Hotspot or USB tether route is unavailable.
                            when {
                                AudioStreamingService.isConnectedState -> {
                                    isConnected = true
                                    isStreaming = true
                                    connectionUiState = ConnectionUiState.CONNECTED
                                    tvStatus.text = "✅ Connected — network change observed; validating active path…"
                                }
                                AudioStreamingService.isReconnectingState -> {
                                    tvStatus.text = "🔄 Connection lost — service is reconnecting on the selected path…"
                                    tvStatusHint.text = "Recovery continues in the background"
                                }
                                hasActiveMobileHotspotInterface() -> {
                                    tvStatus.text = "📱 Mobile Hotspot is active — waiting for the PC-facing route…"
                                }
                                streamingMode == "wired" -> {
                                    tvStatus.text = "🔌 USB network changed — checking USB tethering…"
                                }
                                isWifiEnabledNow() -> {
                                    tvStatus.text = "🛜 Network changed — checking Home Wi-Fi / PC Hotspot…"
                                }
                                autoReconnect -> {
                                    tvStatus.text = "⏳ Wi-Fi is off; waiting for the selected transport…"
                                }
                                else -> {
                                    tvStatus.text = "❌ Selected network is unavailable. Enable Wi-Fi, Mobile Hotspot, or USB tethering."
                                }
                            }
                        }
                    }
                }
            }
            connectivityManager.registerNetworkCallback(req, networkCallback!!)
        } catch (e: Throwable) {
            Log.e(TAG, "❌ MainActivity network monitoring registration failed: " + "${e.message}", e)
        }
    }

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale.US)
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
            } else {
                ttsReady = false
            }
        }
    }

    private fun syncModeButtonsEnabled() {
        val locked = isModeSelectionLocked()
        val alpha = if (locked) 0.45f else 1f

        rbAuto.alpha = alpha
        rbManual.alpha = alpha

        // Transport/discovery controls are locked for the lifetime of the
        // CONNECTING/CONNECTED session. The guard in the click listeners is
        // retained as a second line of protection.
        rbAuto.isEnabled = !locked
        rbManual.isEnabled = !locked

        // A transport switch can trigger service-owned recovery when Auto
        // Reconnect is enabled. Keep it available in that case; the explicit
        // Disconnect path remains authoritative when Auto Reconnect is off.
        navigationView.menu.findItem(R.id.nav_wireless_mode)?.isEnabled = !locked || autoReconnect
        navigationView.menu.findItem(R.id.nav_wired_mode)?.isEnabled = !locked || autoReconnect
    }

    private fun safeSpeak(text: String) {
        try {
            if (isTtsMuted) return
            if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "tts-${System.currentTimeMillis()}")
        } catch (_: Throwable) { }
    }

    private fun autoConnect() {

        if (!appStateRestored) {
            Log.d(
                TAG,
                "🛡️ Auto-connect blocked - app state not restored"
            )
            return
        }

        if (!autoReconnect) {
            return
        }

        if (!rbAuto.isChecked) {
            return
        }

        if (isConnected ||
            connectionUiState == ConnectionUiState.CONNECTING
        ) {
            Log.d(
                TAG,
                "🛡️ Auto-connect ignored - already connecting/connected"
            )
            return
        }

        Log.i(
            TAG,
            "🔍 Auto-Discovery connection attempt started"
        )

        discoverServer { ip, port ->

            if (isUserDisconnected ||
                isConnected
            ) {
                Log.d(
                    TAG,
                    "🛡️ Discovery result ignored - connection was cancelled"
                )
                return@discoverServer
            }

            etIp.setText(ip)
            etPort.setText(port.toString())

            startStreaming()
        }
    }


    private fun requestOneShotServiceAutoDiscovery() {
        if (!isTransportReadyForStreaming()) {
            showTransportUnavailable()
            return
        }

        // A one-shot Connect click means "perform this discovery now"; it must
        // NOT silently disable the user's persistent Auto Reconnect preference.
        // Keep the same preference semantics as SetupActivity: allow one manual
        // attempt when the preference is OFF, but retain automatic recovery when
        // the preference is ON.
        val connectionPrefs = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
        val persistentAutoReconnect = when {
            connectionPrefs.contains("auto_reconnect") -> connectionPrefs.getBoolean("auto_reconnect", autoReconnect)
            connectionPrefs.contains("auto_connect") -> connectionPrefs.getBoolean("auto_connect", autoReconnect)
            else -> autoReconnect
        }
        connectionPrefs.edit()
            .putBoolean("auto_reconnect", persistentAutoReconnect)
            .putBoolean("auto_connect", persistentAutoReconnect)
            .apply()
        autoReconnect = persistentAutoReconnect

        Log.i(
            TAG,
            "🔎 MainActivity one-shot discovery requested: " +
                    "autoReconnect=$persistentAutoReconnect, " +
                    "forceOneShot=${!persistentAutoReconnect}, transport=$streamingMode"
        )

        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AudioStreamingService::class.java).apply {
                    action = AudioStreamingService.ACTION_SETUP_AUTO_CONNECT
                    putExtra("AUTO_RECONNECT", persistentAutoReconnect)
                    putExtra("AUTO_MODE", true)
                    putExtra("FORCE_MANUAL_CONNECT", !persistentAutoReconnect)
                    putExtra("IS_WIRED", streamingMode == "wired")
                    putExtra("BUFFER_MS", selectedBufferMs)
                    putExtra("BUFFER_MODE", bufferModeKey)
                    putExtra("CUSTOM_BUFFER_MS", customBufferMs)
                    putExtra("DEVICE_MANUFACTURER", Build.MANUFACTURER ?: "Unknown")
                    putExtra("DEVICE_MODEL", Build.MODEL ?: "Unknown")
                    putExtra("DEVICE_NAME", deviceName)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Could not start Service-owned one-shot discovery: ${e.message}", e)
            connectionUiState = ConnectionUiState.FAILED
            setRadarState(RadarState.FAILED)
            resetButtonStates()
        }
    }

    private fun requestServiceAutoConnection() {
        if (!appStateRestored || !autoReconnect || isUserPaused || isUserDisconnected) {
            return
        }

        // Service owns reconnects, but the Activity must not ask it to start a
        // connection while the currently selected transport is unavailable.
        if (!isTransportReadyForStreaming()) {
            showTransportUnavailable()
            return
        }

        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AudioStreamingService::class.java).apply {
                    putExtra("AUTO_RECONNECT", autoReconnect)
                    putExtra("AUTO_MODE", rbAuto.isChecked)
                    putExtra("IS_WIRED", streamingMode == "wired")
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not hand auto-connection request to Service: ${e.message}")
        }
    }

    private fun startConnectionWatchdog(ip: String, port: Int) {
        connectionTimeoutRunnable?.let { connectionWatchdog.removeCallbacks(it) }

        connectionTimeoutRunnable = Runnable {
            if (connectionUiState == ConnectionUiState.CONNECTING && !isConnected && !AudioStreamingService.isConnectedState) {

                handleDisconnection(
                    reason = "connect timeout",
                    reconnectAfter = false,
                    closeApp = false
                )
                connectionUiState = ConnectionUiState.FAILED
                tvStatus.text = "❌ Connection failed"
                tvStatusHint.text = "Could not reach $ip:$port"
                setRadarState(RadarState.FAILED)
                safeSpeak("Connection failed Because Server could not be reached with given Address")
                btnConnect.isEnabled = true
                btnDisconnect.isEnabled = false
            }
        }

        val watchdogMs = if (isHotspotConnection) 8000L else 3500L
        connectionWatchdog.postDelayed(connectionTimeoutRunnable!!, watchdogMs)
    }

    private fun handleDisconnection( reason: String = "",
                                     reconnectAfter: Boolean = false,
                                     closeApp: Boolean = false ) {
        Log.i(
            "MainActivity",
            "handleDisconnection(reason=$reason, reconnectAfter=$reconnectAfter, closeApp=$closeApp)"
        )

        connectionTimeoutRunnable?.let { connectionWatchdog.removeCallbacks(it) }
        connectionTimeoutRunnable = null

        connectionUiState = ConnectionUiState.IDLE
        stopStreaming()

        if (closeApp) {
            clearDisconnectedUi(closeApp = true)
            return
        }

        clearDisconnectedUi(closeApp = false)
        setRadarState(RadarState.DISCONNECTED)
        if (reason.contains("server force disconnect", ignoreCase = true)) {
            isUserDisconnected = true // Prevent reconnecting if admin kicked us
            saveAppState()
            tvStatus.text = " Disconnected ❌ from Server   💻 🔗 📱 ⚡"
            tvStatusHint.text = "Server Administrator Disconnected this $deviceName 📱"
            safeSpeak("$deviceBrand $deviceName was Force Disconnected by Administrator")
            toast(" Admin disconnected this $deviceName from Server")
        }

        // ============================================================
        // NETWORK-SWITCH AUTO-RECONNECT REMOVED FROM MAINACTIVITY
        // ============================================================
        //
        // Keep handleDisconnection() for:
        //   • manual disconnect
        //   • server force-disconnect
        //   • connect timeout
        //   • other normal UI-driven teardown
        //
        // But do NOT let a network callback create another reconnect
        // controller here.
        /* 🎯 Rock-solid Handler survives UI coroutine suspensions during heavy C++ socket teardowns
        // 🎯 FIXED: Handler is now heavily guarded. Will NEVER fire if user clicked Disconnect.
        if (reconnectAfter &&
            reason.equals("network switch", ignoreCase = true) &&
            autoReconnect && !isUserDisconnected && !isConnected &&
            connectionUiState != ConnectionUiState.CONNECTING) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (autoReconnect && !AudioStreamingService.isConnectedState && !isUserDisconnected) {
                    if (rbAuto.isChecked) {
                        autoConnect()
                    } else {
                        startStreaming()
                    }
                }
            }, 1500L) // 1.5s delay guarantees old C++ socket is fully un-bound
        }
        // AudioStreamingService now owns persistent network-route
        // recovery and reconnect scheduling.*/
    }


    private fun readPersistedAutoReconnectPreference(): Boolean {
        val prefs = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
        val saved = when {
            prefs.contains("auto_reconnect") -> prefs.getBoolean("auto_reconnect", autoReconnect)
            prefs.contains("auto_connect") -> prefs.getBoolean("auto_connect", autoReconnect)
            else -> autoReconnect
        }
        // Keep the old key in sync so older screens/builds never restore a
        // contradictory Auto Reconnect value.
        prefs.edit().putBoolean("auto_reconnect", saved).putBoolean("auto_connect", saved).apply()
        return saved
    }

    private fun setStreamingMode(
        mode: String,
        userInitiated: Boolean = true,
        announce: Boolean = true
    ) {
        if (streamingMode != mode) {
            // SharedPreferences is the source of truth. An Activity instance can
            // be stale after returning from Settings or after process recreation.
            // Refresh BEFORE enforcing the mode-switch guard and before notifying
            // the Service, otherwise UI=ON and Service=OFF can diverge.
            autoReconnect = readPersistedAutoReconnectPreference()

            // A transport switch during a live session is handled by the Service
            // when Auto Reconnect is enabled. Explicit Stop/Disconnect remains
            // authoritative; switching mode must never override a user stop.
            if (userInitiated && isModeSelectionLocked() && !autoReconnect) {
                showModeLockedMessage()
                syncModeButtonsEnabled()
                return
            }

            var openWirelessSettingsAfterNotify = false
            var openUsbSettingsAfterNotify = false
            streamingMode = mode
            when (mode) {
                "wireless" -> {
                    if (announce) {
                        safeSpeak("Switched to wireless mode")
                        toast("🛜 Wireless Mode - WiFi, Hotspot, Mobile tether")
                    }
                    tvStatus.text = if (announce) "🛜 Wireless Mode Active" else if (AudioStreamingService.isConnectedState) "✅ Connected — Wireless transport active" else "🛜 Wireless transport detected — reconnecting…"

                    rbAuto.isEnabled = true
                    tvJitter.visibility = View.VISIBLE
                    tvSignal.visibility = View.VISIBLE
                    pbJitter.visibility = View.VISIBLE
                    pbSignal.visibility = View.VISIBLE
                    // Defer opening Settings until the foreground Service has
                    // received the selected transport and started recovery.
                    openWirelessSettingsAfterNotify = userInitiated
                }
                "wired" -> {
                    if (announce) {
                        safeSpeak(
                            "Switched to wired mode. Connect the USB cable first, then enable USB tethering."
                        )
                        toast("🔌 Wired Mode: connect USB cable first, then turn ON USB tethering")
                    }

                    tvStatus.text = if (announce) "🔌 Wired Mode - Waiting for USB tethering..." else if (AudioStreamingService.isConnectedState) "✅ Connected — USB tether transport active" else "🔌 USB tether detected — reconnecting…"

                    tvJitter.visibility = View.GONE
                    tvSignal.visibility = View.GONE
                    pbJitter.visibility = View.GONE
                    pbSignal.visibility = View.GONE

                    // Deliver the mode change first; then launch system Settings.
                    // The Service's 250ms watcher and NetworkCallback can already
                    // wait for the tether interface as the user enables it.
                    openUsbSettingsAfterNotify = userInitiated
                }
            }

            saveAppState()
            val persistedAutoReconnect = readPersistedAutoReconnectPreference()
            autoReconnect = persistedAutoReconnect

            if (userInitiated && AudioStreamingService.isServiceRunning) {
                try {
                    startService(Intent(this, AudioStreamingService::class.java).apply {
                        action = AudioStreamingService.ACTION_SETUP_CONFIG_UPDATE
                        putExtra("AUTO_RECONNECT", persistedAutoReconnect)
                        putExtra("AUTO_MODE", rbAuto.isChecked)
                        putExtra("IS_WIRED", streamingMode == "wired")
                    })
                    Log.i(
                        TAG,
                        "🔀 Mode switch sent to Service: mode=$streamingMode, " +
                                "autoReconnect=$persistedAutoReconnect"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send live transport-mode change to Service: ${e.message}", e)
                    toast("Mode saved, but service could not start recovery: ${e.message}")
                }
            }

            if (openWirelessSettingsAfterNotify) openWirelessSettingsIfWifiDisabled()
            if (openUsbSettingsAfterNotify) openUsbTetheringSettings()
        }

        syncRadarCenterForMode()
        syncModeButtonsEnabled()
    }

    private fun openSettingsActivity() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivityForResult(intent, SETTINGS_REQUEST_CODE)
    }

    private fun showAboutDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("About WiFi Audio Speaker - ")
            .setMessage("Version 1.0  Edition\n\nMulti-backend audio streaming with intelligent server compatibility.\n\nModes:\n• Wireless: WiFi, Hotspot, Mobile tether\n• Wired: USB tethering only\n\n Features:\n• Multi-backend server support\n• PyAudioWPatch compatibility\n• SoundDevice integration\n• SoundCard fallback\n• Intelligent quality adaptation\n• Enhanced buffer management\n• Real-time connection monitoring")
            .setPositiveButton("OK", null)
            .create()
        dialog.show()
    }

    private fun isTransportReadyForStreaming(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (streamingMode == "wireless") {
                val wifiReady = isWifiEnabledNow() && networkMatchesCurrentMode(cm)
                val hotspotReady = hasActiveMobileHotspotInterface() || hasUsableLocalWirelessInterface()
                wifiReady || hotspotReady
            } else {
                networkMatchesCurrentMode(cm) || hasUsableLocalUsbTetherInterface()
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Transport readiness check failed: ${e.message}")
            false
        }
    }

    private fun showTransportUnavailable() {
        val hotspotReady = hasActiveMobileHotspotInterface()
        val message = when {
            streamingMode == "wired" -> "Please enable USB tethering and connect the PC through USB."
            hotspotReady -> "Mobile Hotspot is active — waiting for the PC-facing route."
            isWifiEnabledNow() -> "Wi-Fi is on, but no usable route is ready. Connect to Home Wi-Fi or the PC Hotspot."
            else -> "Please turn on Wi-Fi or Mobile Hotspot or enable USB tethering to start streaming"
        }
        tvStatus.text = message
        tvStatusHint.text = if (streamingMode == "wired") {
            "🔌 Wired mode requires USB tethering/RNDIS/ECM first"
        } else if (hotspotReady) {
            "📱 Phone-hosted Mobile Hotspot detected"
        } else {
            "🛜 Wireless mode uses Home Wi-Fi, PC Hotspot, or Mobile Hotspot"
        }
        toast(message)
        safeSpeak(message)
        connectionUiState = ConnectionUiState.FAILED
        setRadarState(RadarState.FAILED)
        resetButtonStates()
        syncModeButtonsEnabled()
    }

    private fun isWifiEnabledNow(): Boolean {
        return try {
            val wifiManager =
                applicationContext.getSystemService(
                    Context.WIFI_SERVICE
                ) as WifiManager

            wifiManager.isWifiEnabled

        } catch (e: Exception) {
            Log.w(
                TAG,
                "⚠️ Unable to determine Wi-Fi state: ${e.message}"
            )
            false
        }
    }


    private fun checkWifiStatusOnLaunch() {
        // This is status detection only, not a settings-launch action. Some OEMs
        // turn off the Wi-Fi client radio while hosting Mobile Hotspot and expose
        // SoftAP/RNDIS without a ConnectivityManager Network. Opening Wi-Fi
        // settings automatically can disrupt that working route (notably Lenovo).
        if (!appStateRestored || streamingMode != "wireless") return

        try {
            isWifiEnabledOnLaunch = isWifiEnabledNow()
            if (hasActiveMobileHotspotInterface()) {
                Log.i(TAG, "📱 Launch check: Mobile Hotspot interface exists; Wi-Fi-client radio state is not authoritative")
                return
            }
            if (isWifiEnabledOnLaunch) {
                Log.i(TAG, "🛜 Launch check: Wi-Fi is enabled")
                return
            }

            Log.w(TAG, "🛜 Wi-Fi client radio is disabled; preserving foreground and checking SoftAP/interface routes (not opening Settings automatically)")
            tvStatus.text = "📱🛜 Wi-Fi client route is not visible — checking Mobile Hotspot. Enable Wi-Fi only for Home Wi-Fi or PC Hotspot."
            tvStatusHint.text = "For USB, select Wired; for this phone's hotspot, keep Mobile Hotspot enabled."
        } catch (e: Exception) {
            Log.e(TAG, "❌ Wi-Fi launch status check failed: ${e.message}", e)
        }
    }



    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onStart() {
        super.onStart()
        // Bind so stats are available when connected
        val intent = Intent(this, AudioStreamingService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }


    private fun handleNotificationActivityIntent(
        intent: Intent?
    ) {
        when (intent?.action) {
            AudioStreamingService.ACTION_NOTIFICATION_RESUME -> {
                // Compatibility for old notifications: forward to Service only.
                Log.i(TAG, "▶️ Forwarding legacy notification Resume to Service")
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, AudioStreamingService::class.java).apply {
                        action = AudioStreamingService.ACTION_NOTIFICATION_RESUME
                    }
                )
            }

            AudioStreamingService.ACTION_NOTIFICATION_CLOSE_APP -> {
                // Compatibility for old notifications: forward to Service only.
                Log.i(TAG, "🛑 Forwarding legacy notification Close App to Service")
                startService(
                    Intent(this, AudioStreamingService::class.java).apply {
                        action = AudioStreamingService.ACTION_NOTIFICATION_CLOSE_APP
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (appStateRestored) {
            autoReconnect = readPersistedAutoReconnectPreference()
            val savedTransport = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                .getString("streaming_mode", streamingMode) ?: streamingMode
            if (savedTransport != streamingMode) {
                setStreamingMode(savedTransport, userInitiated = false, announce = false)
            }
            if (::navigationView.isInitialized) {
                navigationView.setCheckedItem(
                    if (streamingMode == "wired") R.id.nav_wired_mode else R.id.nav_wireless_mode
                )
            }
        }
        if (!isMuted) seekVolume.progress = getSystemMediaVolumePercent()

        // Restart only the UI animation layer. The Service remains the sole
        // owner of connection/reconnect/audio state.
        syncRadarCenterForMode()
        if (streamingMode == "wired") {
            startWiredRadarAnimation()
        } else {
            startLanRadarAnimation()
        }

        // Activity lifecycle is UI-only. The foreground Service owns all
        // connection/reconnection work.
        when {
            AudioStreamingService.isConnectedState -> {
                isConnected = true
                isStreaming = true
                connectionUiState = ConnectionUiState.CONNECTED
                isUserPaused = false
                connectionTimeoutRunnable?.let { connectionWatchdog.removeCallbacks(it) }
                connectionTimeoutRunnable = null
                btnConnect.isEnabled = false
                btnDisconnect.isEnabled = true
                setRadarState(RadarState.CONNECTED)
                syncModeButtonsEnabled()

                val svc = audioStreamingService ?: AudioStreamingService.instance
                if (svc != null) {
                    val ip = svc.getTargetIp()
                    val port = svc.getTargetPort()
                    val name = svc.getServerDisplayName()
                    if (ip.isNotEmpty() && port > 0) {
                        tvStatus.text = "✅ Connected to $ip:$port"
                        tvServerInfo.text = "Server : $name"
                        tvStatusHint.text = "Server: $name"
                        val livePath = try { svc.getCurrentConnectionPathLabel() } catch (_: Exception) { "" }
                        if (livePath.isNotBlank()) {
                            lastRenderedConnectionPath = "Path: $livePath"
                            tvConnectionPath.text = "Path: $livePath\nEndpoint: $ip:$port"
                            getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                                .edit().putString("last_connection_path", livePath).apply()
                        }
                    }
                }
            }

            AudioStreamingService.isReconnectingState -> {
                isConnected = false
                isStreaming = false
                connectionUiState = ConnectionUiState.CONNECTING
                btnConnect.isEnabled = false
                btnDisconnect.isEnabled = true
                tvStatus.text = "🔄 Connection lost — Service is reconnecting…"
                tvStatusHint.text = "Reconnection is running in background"
                setRadarState(RadarState.CONNECTING)
            }

            AudioStreamingService.isPausedState || isUserPaused -> {
                isConnected = false
                isStreaming = false
                connectionUiState = ConnectionUiState.IDLE
                tvStatus.text = "⏸️ Stopped"
                tvStatusHint.text = "Tap Resume in the notification to reconnect"
                setRadarState(RadarState.DISCONNECTED)
                resetButtonStates()
                syncModeButtonsEnabled()
            }

            else -> {
                isConnected = false
                isStreaming = false
                connectionUiState = ConnectionUiState.IDLE
                tvStatus.text = "❌ Disconnected"
                tvStatusHint.text = "Connect to WifiAudioServer"
                resetButtonStates()
                syncModeButtonsEnabled()
            }
        }
    }


    private fun saveAppState() {
        try {
            // Service broadcasts can reach this Activity while SettingsActivity is
            // foregrounded. Re-read the persisted toggle before any UI save so an
            // older Activity instance cannot overwrite the user's latest choice.
            autoReconnect = readPersistedAutoReconnectPreference()
            val prefs = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)

            val ip = etIp.text?.toString()?.trim().orEmpty()
            val port = etPort.text?.toString()?.trim()?.toIntOrNull() ?: 50007

            prefs.edit().apply {
                // Current schema

                // 🎯 FIX: Never overwrite saved server IP with an empty string
                if (ip.isNotBlank()) {
                    putString("server_ip", ip)
                    putString("last_ip", ip)
                }
                if (port > 0) {
                    putInt("server_port", port)
                    putString("last_port", port.toString())
                }

                putString(BufferModeConfig.PREF_MODE, bufferModeKey)
                putInt(BufferModeConfig.PREF_BUFFER_MS, selectedBufferMs)
                putInt(BufferModeConfig.PREF_CUSTOM_MS, customBufferMs.coerceIn(
                    BufferModeConfig.MIN_CUSTOM_MS, BufferModeConfig.MAX_CUSTOM_MS
                ))
                putBoolean("button_premium_free", bufferModeKey == BufferModeConfig.Mode.LOW.key)
                putBoolean("is_muted", isMuted)
                putInt("volume", seekVolume.progress.coerceIn(0, 100))
                putString("server_name", serverDisplayName)
                putString("server_backend", detectedServerBackend)
                putBoolean("user_disconnected",isUserDisconnected)
                putString("streaming_mode", streamingMode)
                putBoolean("user_paused",isUserPaused)

                // Legacy/backward-compatible keys. Preserve a previously saved endpoint
                // when the current UI fields are intentionally blank.
                if (ip.isNotBlank()) putString("last_ip", ip)
                if (port > 0) putString("last_port", port.toString())
                putBoolean("auto_mode", rbAuto.isChecked)
                putBoolean("auto_connect",autoReconnect)
                putBoolean("auto_reconnect", autoReconnect)
                putInt("audio_delay", audioDelay)
                putFloat("last_volume", lastVolume.coerceIn(0f, 100f))

                // Product/testing profile
                putString("user_profile", when (bufferModeKey) {
                    BufferModeConfig.Mode.ULTRA_LOW.key -> "ultra_low_golden_v1"
                    BufferModeConfig.Mode.LOW.key -> "premium_test" // legacy compatibility
                    BufferModeConfig.Mode.HIGH.key -> "free_test"   // legacy compatibility
                    else -> "buffer_$bufferModeKey"
                })

                apply()
            }

            Log.d("MainActivity", "✅ App state saved")
        } catch (e: Exception) {
            Log.e("MainActivity", "❌ saveAppState failed", e)
        }
    }


    private fun restoreAppState() {
        lifecycleScope.launch {
            delay(if (intent.getBooleanExtra("BOOTSTRAP_AUTO", false)) 40L else 80L) // Splash already completed bootstrap

            try {
                val prefs = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)

                runOnUiThread {
                    val lastIp = prefs.getString("server_ip", prefs.getString("last_ip", "")) ?: ""

                    val portFromInt = readIntPrefCompat(prefs, "server_port", 0)
                    val lastPort = if (portFromInt > 0) {
                        portFromInt.toString()
                    } else {
                        prefs.getString("last_port", "50007") ?: "50007"
                    }

                    if (lastIp.isNotEmpty()) {
                        etIp.setText(lastIp)
                    }

                    if (lastPort.isNotEmpty()) {
                        etPort.setText(lastPort)
                    }

                    val savedPath = prefs.getString("last_connection_path", "").orEmpty().trim()
                    if (savedPath.isNotBlank()) {
                        lastRenderedConnectionPath = "Path: $savedPath"
                        tvConnectionPath.text = lastRenderedConnectionPath
                    }

                    val autoMode = prefs.getBoolean("auto_mode", rbAuto.isChecked)
                    rbAuto.isChecked = autoMode
                    rbManual.isChecked = !autoMode
                    syncModeButtonsEnabled()

                    // Migrate and restore the canonical four-choice buffer mode.
                    // Existing Premium maps to Low; existing Free maps to High.
                    syncBufferStateFromPreferences(prefs)

                    isUserDisconnected = prefs.getBoolean("user_disconnected", false)

                    audioDelay = prefs.getInt("audio_delay", audioDelay)

                    updateSyncButton()

                    isMuted = prefs.getBoolean("is_muted", false)
                    val savedVolume = prefs.getInt(
                        "volume",
                        prefs.getFloat("last_volume", 75f).toInt()
                    ).coerceIn(0, 100)

                    lastVolume = prefs.getFloat("last_volume", savedVolume.toFloat())
                        .coerceIn(0f, 100f)

                    serverDisplayName = prefs.getString("server_name", "") ?: ""
                    detectedServerBackend = prefs.getString("server_backend", "") ?: ""

                    if (AudioStreamingService.isConnectedState && serverDisplayName.isNotEmpty()) {
                        tvServerInfo.text = "Server : $serverDisplayName"
                    } else {
                        tvServerInfo.text = "Server : Unknown"
                    }

                    val systemVolumePercent = getSystemMediaVolumePercent()
                    if (isMuted) {
                        btnMute.text = "📱〰🔇"
                        seekVolume.progress = 0
                    } else {
                        btnMute.text = "📱〰🔊"
                        seekVolume.progress = systemVolumePercent
                    }

                    val savedMode = prefs.getString("streaming_mode", "wireless") ?: "wireless"

                    streamingMode = "" // Force update override

                    // Restore saved UI state silently.
                    // Do not launch Wi-Fi or USB tethering settings during app startup.
                    setStreamingMode(savedMode,userInitiated = false)
                    if (savedMode == "wired") {
                        navigationView.setCheckedItem(R.id.nav_wired_mode)
                    } else {
                        navigationView.setCheckedItem(R.id.nav_wireless_mode)
                    }

                    autoReconnect = readPersistedAutoReconnectPreference()

                    isUserPaused = prefs.getBoolean("user_paused",false)
                    // ✅ All persisted connection settings are now loaded.
                    appStateRestored = true

                    val restoredIp =
                        etIp.text?.toString()?.trim().orEmpty()

                    val restoredPort =
                        etPort.text?.toString()?.trim().orEmpty()

                    Log.i(
                        TAG,
                        "✅ App state restored: " +
                                "mode=${if (rbAuto.isChecked) "AUTO_DISCOVERY" else "MANUAL"}, " +
                                "autoReconnect=$autoReconnect, " +
                                "target=$restoredIp:$restoredPort"
                    )
                    // ✅ Now the saved mode is authoritative.
                    // Check Wi-Fi only when the restored mode is Wireless.
                    checkWifiStatusOnLaunch()
                    resetButtonStates()
                    toast("✅ Settings restored")

                    if (AudioStreamingService.isConnectedState) {
                        Log.i(TAG, "🛡️ Service already connected; MainActivity will not start a duplicate auto-connection")
                    } else if (launchedFromSetupConnection) {
                        Log.i(TAG, "🛡️ Opened by explicit Setup connection; skipping duplicate initial auto-connect")
                    } else if (autoReconnect && !isUserDisconnected) {
                        // Service owns initial auto-connect/reconnect.
                        requestServiceAutoConnection()
                    } else {
                        Log.i(TAG, "Auto-connect disabled or user-disconnected; waiting for Manual Connect")
                        if (!autoReconnect) {
                            toast(" Auto-connect is disabled Hence Waiting for Manual Connect to Server")
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    resetButtonStates()
                    toast("Using default settings")
                }
            }
        }
    }

    private fun getSystemMediaVolumePercent(): Int {
        return try {
            val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            ((manager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f) / maxVolume)
                .roundToInt().coerceIn(0, 100)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read system media volume: ${e.message}")
            75
        }
    }

    private fun setTrackVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 1f)
        val percent = (clamped * 100f).roundToInt().coerceIn(0, 100)
        try {
            val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val systemVolume = ((percent / 100f) * maxVolume).roundToInt().coerceIn(0, maxVolume)
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, systemVolume, 0)
            if (!isMuted && percent > 0) lastVolume = percent.toFloat()
            seekVolume.progress = percent
            // Do not send SET_VOLUME to AudioStreamingService: the Android
            // system media stream now owns the user's volume setting.
        } catch (e: Exception) {
            Log.e(TAG, "System media volume adjustment failed", e)
            toast("Unable to adjust system media volume: ${e.message}")
        }
    }

    private fun updateSyncButton() {
        if (!::btnSync.isInitialized) return
        runOnUiThread {
            val mode = BufferModeConfig.Mode.fromKey(bufferModeKey)
            btnSync.text = if (mode == BufferModeConfig.Mode.ULTRA_LOW) {
                "⚡ ULTRA LOW · GOLDEN"
            } else {
                "🎚️ ${mode.key.uppercase(Locale.getDefault())} · ${selectedBufferMs}ms"
            }
            btnSync.contentDescription = "Current buffer: ${mode.title}, ${selectedBufferMs} milliseconds. Tap to change buffer mode."
            // Keep the existing theme-provided background/tint so this control
            // remains consistent with the app's gradient and dark/light themes.
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 2001) {
            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startStreaming()
            } else {
                connectionUiState = ConnectionUiState.FAILED
                resetButtonStates()
                syncModeButtonsEnabled()

                toast("Notification permission is required for streaming service")
                safeSpeak("Notification permission is required for streaming")
            }
            return
        }

        if (requestCode == 1234) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                lifecycleScope.launch {
                    checkWifiStatusOnLaunch()
                }
            } else {
                toast("WiFi permission denied.  app features limited.")
                safeSpeak("WiFi permission denied. Some features may not work.")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            1001 -> {
                lifecycleScope.launch {
                    delay(1000L)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            if (checkSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
                                toast("WiFi permission needed to check WiFi status")
                                return@launch
                            }
                        }

                        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        if (wifiManager.isWifiEnabled) {
                            runOnUiThread {
                                toast("✅ WiFi enabled -  functionality available")
                                safeSpeak("WiFi enabled - ready for  connections")
                                isWifiEnabledOnLaunch = true
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            toast("⚠️ Error checking WiFi: ${e.message}")
                        }
                    }
                }
            }
            SETTINGS_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    reloadSettingsFromPreferences()
                    toast("✅  Settings Updated")
                    safeSpeak("settings updated")
                }
            }
        }
    }

    private fun reloadSettingsFromPreferences() {
        val prefs = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
        val wasTtsMuted = isTtsMuted
        val wasAutoReconnect = autoReconnect

        isUserDisconnected = prefs.getBoolean("user_disconnected", false)
        isTtsMuted = prefs.getBoolean("tts_muted", false)
        syncBufferStateFromPreferences(prefs)
        updateSyncButton()

        val newAudioDelay = prefs.getInt("audio_delay", 0)
        if (newAudioDelay != audioDelay) {
            audioDelay = newAudioDelay
        }

        // Android STREAM_MUSIC is the single source of truth. Do not overwrite
        // the device's media volume with a previously saved app-local slider value.
        if (!isMuted) {
            seekVolume.progress = getSystemMediaVolumePercent()
        }

        autoReconnect = readPersistedAutoReconnectPreference()

        startService(Intent(this, AudioStreamingService::class.java).apply {
            putExtra("AUTO_RECONNECT", autoReconnect)
            putExtra("AUTO_MODE", rbAuto.isChecked)
            putExtra("IS_WIRED", streamingMode == "wired")
        })

        if (!wasAutoReconnect && autoReconnect && !isUserDisconnected) {
            requestServiceAutoConnection()
        }

        currentTheme = prefs.getInt("theme", 0)
        currentDarkMode = prefs.getInt("dark_mode", 2)

        if (wasTtsMuted != isTtsMuted) {
            if (!isTtsMuted) {
                safeSpeak(" voice guide enabled from App settings")
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            stopLanRadarAnimation()
            stopWiredRadarAnimation()
        } else if (!isFinishing && !isDestroyed) {
            syncRadarCenterForMode()
            if (streamingMode == "wired") {
                startWiredRadarAnimation()
            } else {
                startLanRadarAnimation()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            try { unbindService(connection) } catch (_: Throwable) {}
            isBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLanRadarAnimation()
        stopWiredRadarAnimation()
        try {
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (_: Throwable) {}
        try {
            unregisterReceiver(serviceUiReceiver) } catch (_: Throwable){}
        // 🎯 FIX: Never block Main Thread with IPC TTS shutdown on older Android kernels
        val ttsToClose = tts
        tts = null
        Thread {
            try { ttsToClose?.shutdown() } catch (_: Throwable) {}
        }.start()
    }
}
