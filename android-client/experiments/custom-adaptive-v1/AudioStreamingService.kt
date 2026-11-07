package com.example.wifiaudiospeaker

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.*
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet4Address
import java.net.SocketTimeoutException
import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import org.json.JSONObject
import java.net.NetworkInterface
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import java.net.InetSocketAddress
import java.util.Locale
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong


class AudioStreamingService : Service() {

    companion object {
        private const val TAG = "AudioStreamingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = MyApplication.CHANNEL_ID
        private const val ACTION_DISCONNECT = "ACTION_DISCONNECT"
        private const val ACTION_DISCONNECT_CLOSE = "ACTION_DISCONNECT_CLOSE"
        const val ACTION_SETUP_AUTO_CONNECT =
            "com.example.wifiaudiospeaker.ACTION_SETUP_AUTO_CONNECT"
        private const val ACTION_APP_LAUNCH_BOOTSTRAP =
            "com.example.wifiaudiospeaker.ACTION_APP_LAUNCH_BOOTSTRAP"
        const val ACTION_SETUP_CONFIG_UPDATE =
            "com.example.wifiaudiospeaker.ACTION_SETUP_CONFIG_UPDATE"
        private const val ACTION_UI_TRANSPORT_UNAVAILABLE =
            "com.example.wifiaudiospeaker.ACTION_UI_TRANSPORT_UNAVAILABLE"
        const val ACTION_NOTIFICATION_PC_MEDIA =
            "com.example.wifiaudiospeaker.ACTION_NOTIFICATION_PC_MEDIA"
        const val ACTION_NOTIFICATION_PC_MUTE =
            "com.example.wifiaudiospeaker.ACTION_NOTIFICATION_PC_MUTE"
        private const val PC_CONTROL_MEDIA = "PC_MEDIA_PLAY_PAUSE"
        private const val PC_CONTROL_MUTE = "PC_AUDIO_MUTE"
        private const val PC_STATE_REQUEST = "PC_STATE_REQUEST"
        private const val PC_STATE_PREFIX = "PC_STATE:"
        const val ACTION_UI_PC_STATE = "com.example.wifiaudiospeaker.ACTION_UI_PC_STATE"
        private const val ACTION_UI_RESET = "com.example.wifiaudiospeaker.ACTION_UI_RESET"
        const val ACTION_UI_CONNECTION_PATH_CHANGED =
            "com.example.wifiaudiospeaker.ACTION_UI_CONNECTION_PATH_CHANGED"
        const val ACTION_UI_TRANSPORT_MODE_CHANGED =
            "com.example.wifiaudiospeaker.ACTION_UI_TRANSPORT_MODE_CHANGED"

        private const val ACTION_UI_CONNECTED = "com.example.wifiaudiospeaker.ACTION_UI_CONNECTED"
        private const val ACTION_UI_CLOSE = "com.example.wifiaudiospeaker.ACTION_UI_CLOSE"


        const val ACTION_NOTIFICATION_STOP =
            "com.example.wifiaudiospeaker.ACTION_NOTIFICATION_STOP"

        const val ACTION_NOTIFICATION_RESUME =
            "com.example.wifiaudiospeaker.ACTION_NOTIFICATION_RESUME"

        const val ACTION_NOTIFICATION_CLOSE_APP =
            "com.example.wifiaudiospeaker.ACTION_NOTIFICATION_CLOSE_APP"

        @JvmStatic external fun nativeServiceInit(): Boolean
        @JvmStatic external fun nativeServiceConnect(ip: String, port: Int): Boolean
        @JvmStatic external fun nativeServiceSetPreferredLocalIp(ip: String): Boolean
        @JvmStatic external fun nativeServiceStartStreaming(): Boolean
        @JvmStatic external fun nativeServiceGetAudioData(buffer: ShortArray, maxSamples: Int): Int
        @JvmStatic external fun nativeServiceStopStreaming()
        @JvmStatic external fun nativeServiceCleanup()
        @JvmStatic external fun nativeServiceWasForceDisconnected(): Boolean
        @JvmStatic external fun nativeServiceGetOptimalBufferSize(sampleRate: Int, channels: Int): Int
        @JvmStatic external fun nativeSendDisconnect() // 🎯 Added Disconnect Signal

        @JvmStatic external fun nativeServiceSendHeartbeat(): Boolean
        @JvmStatic external fun nativeServiceSetJitterBuffer(bufferMs: Int): Boolean
        @JvmStatic external fun nativeServiceSetUltraLowProfile(enabled: Boolean): Boolean
        @JvmStatic external fun nativeServiceSetHighBufferProfile(enabled: Boolean): Boolean
        @JvmStatic external fun nativeServiceSetMediumBufferProfile(enabled: Boolean): Boolean
        @JvmStatic external fun nativeServiceSetLowBufferProfile(enabled: Boolean): Boolean
        @JvmStatic external fun nativeServiceSetCustomBufferProfile(enabled: Boolean, targetMs: Int): Boolean
        @JvmStatic external fun nativeServiceGetCustomBufferRingDrops(): Long
        @JvmStatic external fun nativeServiceGetBufferStats(stats: IntArray): Boolean
        @JvmStatic external fun nativeServiceSendHandshake(mfg: String, mdl: String, dev: String): String?

        var isServiceRunning = false
        var isConnectedState = false
        @JvmStatic var isReconnectingState = false
        @JvmStatic var isPausedState = false
        var instance: AudioStreamingService? = null
    }

    private fun readPersistedAutoReconnectPreference(
        prefs: android.content.SharedPreferences,
        defaultValue: Boolean = true
    ): Boolean {
        // "auto_reconnect" is the canonical current key. "auto_connect" is
        // retained as a synchronized compatibility alias for older builds.
        return try {
            when {
                prefs.contains("auto_reconnect") -> prefs.getBoolean("auto_reconnect", defaultValue)
                prefs.contains("auto_connect") -> prefs.getBoolean("auto_connect", defaultValue)
                else -> defaultValue
            }
        } catch (_: ClassCastException) {
            defaultValue
        }
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

    fun getTargetIp(): String = targetIp
    fun getTargetPort(): Int = targetPort
    fun getServerDisplayName(): String = serverDisplayName

    data class ConnectionStats(
        val packetsReceived: Int,
        val packetsDropped: Int,
        val bufferUnderruns: Int,
        val bufferLevel: Int
    )

    data class NetworkQuality(
        val signalStrength: Int,
        val latency: Long,
        val signalQuality: String,
        val jitter: Double = 0.0
    )

    inner class LocalBinder : Binder() {
        fun getService(): AudioStreamingService = this@AudioStreamingService
    }

    // Binder to expose the service
    private val binder = LocalBinder()

    // App-provided extras
    private var targetIp = ""
    private var targetPort = 0
    private var deviceManufacturer = "Android"
    private var deviceModel = "Device"
    private var deviceName = "Android-Device"
    private var serverDisplayName = "Unknown"
    @Volatile private var jitterBufferMs = 250
    @Volatile private var bufferModeKey = BufferModeConfig.Mode.MEDIUM.key
    @Volatile private var customBufferMs = BufferModeConfig.DEFAULT_CUSTOM_MS
    @Volatile private var isWiredMode = false

    // Audio params (stereo end-to-end)
    private val sampleRate = 48000     // 🎯 🎯 REVERT TO 48kHz to unlock Android FLAG_FAST hardware path!
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val playbackBlockSamples = 480 // 10 ms at 48 kHz

    // Add these constants at the top of the class:
    private val bytesPerMonoSample = 2 // 16-bit MONO


    // Runtime
    private var audioTrack: AudioTrack? = null
    private var streamJob: Job? = null
    private var shouldStream = false
    private var currentAudioTrackBufferSize = 0  // 🎯 FIXED: Declared missing variable

    private var reconnectJob: Job? = null
    private var initialConnectJob: Job? = null

    // Service-lifetime coroutine scopes. Every reconnect/network job is cancelled
    // automatically when this foreground Service is destroyed.
    private val serviceSupervisor = SupervisorJob()
    private val serviceMainScope = CoroutineScope(serviceSupervisor + Dispatchers.Main.immediate)
    private val serviceIoScope = CoroutineScope(serviceSupervisor + Dispatchers.IO)

    private var monitorJob: Job? = null
    private var transportWatchJob: Job? = null
    private var isConnected = false
    // The service watches BOTH physical transport families continuously. The
    // user-selected mode remains the current mode until Android's live link
    // state shows that the other family was newly enabled/changed, or the
    // selected family disappeared. This lets system Settings drive failover.
    @Volatile private var transportFamiliesInitialized = false
    @Volatile private var lastObservedWiredFamilySignature = ""
    @Volatile private var lastObservedWirelessFamilySignature = ""
    @Volatile private var lastAutomaticTransportSwitchAtMs = 0L
    // A radio selection made inside the app is an explicit choice. If its
    // interface is not ready yet, wait for that transport instead of instantly
    // flipping back merely because the old interface is still present.
    @Volatile private var explicitTransportSelectionWaitingForReady = false

    // 🛡️ SERVICE-OWNED NETWORK STATE
    // The Service is the persistent owner of the network route used by
    // native UDP/audio traffic. MainActivity must not bind/unbind the
    // process-wide network anymore.
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var boundNetworkHandle = -1L
    private var networkMonitoringInitialized = false
    private var networkReconnectJob: Job? = null
    private var boundNetwork: Network? = null
    @Volatile private var lastObservedTransportSignature: String = ""

    // 🛡️ CONNECTION STATE MANAGEMENT
    private var serverBackendName = "Unknown"
    private var isCurrentlyConnecting = false

    // 🛡️ Serializes native connect/handshake/start operations.
// Only ONE connection attempt may touch the native socket at a time.
    private val connectionLock = Any()

    private var currentServerAddress = ""

    private val connectionCooldownMs = 3000L // 3 second cooldown

    private var connectionAttempts = 0
    private var lastConnectionTime = 0L

    private var networkQuality = NetworkQuality(0, 0L, "Unknown")

    //   AUTO-RECONNECTION: Progressive backoff system
    private val maxReconnectAttempts = 10
    private val reconnectDelays = listOf(250L, 500L, 1000L, 2000L, 5000L) // Progressive delays
    private var currentReconnectDelay = 0
    @Volatile private var autoReconnectEnabled = true
    private var hasStopped = false

    // Notification controller state.
    // true = stream is intentionally stopped but the foreground
    // service remains alive so the user can Resume.
    private var isUserPaused = false

    // Whether notification should expose a Resume action.
    private var resumeAvailable = false


    // Forced server disconnect has a different recovery action from a user pause.
    // It uses the same Service-owned PendingIntent, but the visible label is
    // "ReConnect" instead of "Resume".
    private var reconnectAvailable = false

    // Persisted so a START_STICKY Service restart after a process death can
    // recover a session that was previously connected without requiring the
    // Activity to come back first.
    private var sessionWasConnected = false

    private var autoConnectMode = true
    private var isUserDisconnected = false

    // Prevent duplicate native teardown calls when multiple failure paths converge.
    private var nativeDisconnectIssued = false
    private var nativeStopIssued = false

    // Serialize native/audio teardown. Network callbacks and the streaming
    // coroutine can both converge on stopStreaming() during route changes.
    private val nativeLifecycleLock = Any()

    // AudioFocus is the correct Android mechanism for coexistence with phone
    // calls and other audio. No MANAGE_OWN_CALLS permission is required.
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusHeld = false
    private var audioDucked = false
    private val audioDuckGain = 0.35f

    // One telemetry worker belongs to one streaming session. A generation
    // token prevents an old worker from surviving a fast reconnect and
    // reporting the previous session's buffer/mode to the PC.
    private val telemetryGeneration = AtomicLong(0L)
    @Volatile private var telemetryThread: Thread? = null
    @Volatile private var telemetrySocketRef: DatagramSocket? = null

    private val audioFocusChangeListener =
        AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    audioDucked = false
                    if (!isUserPaused && !isUserDisconnected && isConnected && shouldStream) {
                        try {
                            // User volume belongs to Android STREAM_MUSIC. AudioTrack
                            // gain is used only for temporary focus ducking.
                            audioTrack?.setVolume(1.0f)
                            audioTrack?.play()
                        } catch (e: Throwable) {
                            Log.w(TAG, "⚠️ AudioTrack restore after focus gain failed: ${e.message}")
                        }
                        Log.i(TAG, "🎧 Audio focus regained — restored stream playback gain")
                        updateNotification("🎧 Streaming… ${bufferMsForNotification()} \u2009ms • $serverDisplayName")
                    }
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // The user explicitly wants the stream to remain audible at a
                    // lower level during ringing/notifications. Do not pause the
                    // AudioTrack or flush the native queue for any focus loss.
                    if (isConnected && shouldStream) {
                        audioDucked = true
                        try {
                            audioTrack?.setVolume(audioDuckGain)
                        } catch (e: Throwable) {
                            Log.w(TAG, "⚠️ AudioTrack duck failed: ${e.message}")
                        }
                        Log.i(TAG, "🔉 Audio focus interruption (${focusChange}) — ducked to ${(audioDuckGain * 100).toInt()}%; stream remains running")
                        updateNotification("🔉 Stream ducked during phone/notification audio…")
                    }
                }
            }
        }

    private fun bufferMsForNotification(): Int = jitterBufferMs

    //Notification
    private lateinit var openPendingIntent: PendingIntent
    private lateinit var stopPendingIntent: PendingIntent
    private lateinit var resumePendingIntent: PendingIntent
    private lateinit var disconnectClosePendingIntent: PendingIntent
    private lateinit var closeAppPendingIntent: PendingIntent
    private lateinit var pcMediaPendingIntent: PendingIntent
    private lateinit var pcMutePendingIntent: PendingIntent

    // Authoritative PC state as reported by the Windows server.
    // These are updated by the lightweight PC_STATE_REQUEST/PC_STATE response
    // path and are independent of the audio streaming pipeline.
    private var pcMediaPlaying = true
    private var pcMediaStateKnown = false
    private var pcAudioMuted = false
    private var pcAudioStateKnown = false
    private var pcStateSyncJob: Job? = null
    private val pcStateRequestMutex = Mutex()
    // PC state is polled every ~1.2s for UI freshness. Log only real state changes,
    // plus a 30-second heartbeat, so Logcat retains connection/audio diagnostics.
    @Volatile private var lastLoggedPcStateKey = ""
    @Volatile private var lastPcStateLogAtMs = 0L
    @Volatile private var lastAudioUnderrunLogAtMs = 0L
    @Volatile private var lastHighBufferGapLogAtMs = 0L
    private var lastMediumBufferGapLogAtMs = 0L
    @Volatile private var lowLipSyncSkippedBlocks = 0L
    @Volatile private var customStaleBlocksSkipped = 0L
    @Volatile private var cachedCustomBufferDecision: CustomBufferEngine.Decision? = null
    @Volatile private var lastCustomDecisionRefreshAtMs = 0L
    @Volatile private var customProcessCpuPercent = 0
    private var lastCustomCpuWallAtMs = 0L
    private var lastCustomProcessCpuMs = 0L
    private val customDecisionLock = Any()
    @Volatile private var lastStreamExceptionLogAtMs = 0L
    @Volatile private var audioTrackRebuildRequested = false

    // True when AUTO/reconnect was requested but the selected transport is not
    // currently usable. The Service waits for NetworkCallback.onAvailable().
    private var connectionWaitingForTransport = false

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        instance = this

        Log.i(TAG, "🚀  AudioStreamingService Created - :$deviceManufacturer , $deviceName , $deviceModel,")

        deviceName = DeviceNameHelper.getDeviceName(this)


        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        stopPendingIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AudioStreamingService::class.java).apply {
                action = ACTION_NOTIFICATION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        resumePendingIntent = PendingIntent.getService(
            this, 4,
            Intent(this, AudioStreamingService::class.java).apply {
                action = ACTION_NOTIFICATION_RESUME
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        disconnectClosePendingIntent = PendingIntent.getService(
            this, 2,
            Intent(this, AudioStreamingService::class.java).apply {
                action = ACTION_DISCONNECT_CLOSE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        closeAppPendingIntent = PendingIntent.getService(
            this, 5,
            Intent(this, AudioStreamingService::class.java).apply {
                action = ACTION_NOTIFICATION_CLOSE_APP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        val savedPrefs = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)

        targetIp = savedPrefs.getString("server_ip", savedPrefs.getString("last_ip", ""))?.trim().orEmpty()
        targetPort = readIntPrefCompat(savedPrefs, "server_port", 0).takeIf { it > 0 }
            ?: savedPrefs.getString("last_port", "")?.toIntOrNull()
                    ?: 0
        serverDisplayName = savedPrefs.getString("server_name", "Unknown")?.trim().orEmpty().ifBlank { "Unknown" }
        serverBackendName = savedPrefs.getString("server_backend", "Unknown")?.trim().orEmpty().ifBlank { "Unknown" }
        val savedBufferSelection = BufferModeConfig.load(savedPrefs)
        bufferModeKey = savedBufferSelection.mode.key
        jitterBufferMs = savedBufferSelection.bufferMs
        customBufferMs = savedBufferSelection.customMs
        autoConnectMode = savedPrefs.getBoolean("auto_mode", true)
        autoReconnectEnabled = readPersistedAutoReconnectPreference(savedPrefs, true)
        // Migrate/repair any legacy mismatch once. Both keys stay synchronized;
        // otherwise SettingsActivity and the service could restore opposite values.
        savedPrefs.edit()
            .putBoolean("auto_reconnect", autoReconnectEnabled)
            .putBoolean("auto_connect", autoReconnectEnabled)
            .apply()
        isWiredMode = (savedPrefs.getString("streaming_mode", "wireless") == "wired")
        isUserDisconnected = savedPrefs.getBoolean("user_disconnected", false)
        isUserPaused = savedPrefs.getBoolean("user_paused", false)
        reconnectAvailable = savedPrefs.getBoolean("notification_reconnect", false)
        sessionWasConnected = savedPrefs.getBoolean("session_was_connected", false)

        // Recovery mode has priority over the normal user-pause action.
        resumeAvailable = isUserPaused && !reconnectAvailable
        isPausedState = isUserPaused
        isReconnectingState = false

        // Ensure native stack is initialized only once. Apply the persisted
        // buffer profile before any connection starts so the native ring is
        // allocated/operated with the correct mode from its first packet.
        nativeServiceInit()
        nativeServiceSetJitterBuffer(jitterBufferMs)
        nativeServiceSetUltraLowProfile(
            bufferModeKey == BufferModeConfig.Mode.ULTRA_LOW.key
        )
        nativeServiceSetHighBufferProfile(
            bufferModeKey == BufferModeConfig.Mode.HIGH.key
        )
        nativeServiceSetMediumBufferProfile(
            bufferModeKey == BufferModeConfig.Mode.MEDIUM.key
        )
        nativeServiceSetLowBufferProfile(
            bufferModeKey == BufferModeConfig.Mode.LOW.key
        )
        nativeServiceSetCustomBufferProfile(
            bufferModeKey == BufferModeConfig.Mode.CUSTOM.key,
            customBufferMs
        )
        if (bufferModeKey == BufferModeConfig.Mode.CUSTOM.key) {
            currentCustomBufferDecision(forceRefresh = true)
        }

        // 🛜 CRITICAL:
        // The foreground Service owns persistent network routing.
        // This must be registered before onStartCommand() performs
        // nativeServiceConnect().
        setupServiceNetworkMonitoring()

        // Poll interface signatures as a fallback for OEMs that create/remove a
        // SoftAP or USB tether interface without a ConnectivityManager callback.
        // 250ms polling targets prompt recovery initiation; the OS/server still
        // control when a successful association/handshake is physically possible.
        transportWatchJob?.cancel()
        transportWatchJob = serviceIoScope.launch {
            while (isActive) {
                delay(250L)
                // Do not limit observation to isWiredMode. Android Settings can
                // enable/disable USB tethering or Wi-Fi/SoftAP while the Activity
                // is backgrounded; the newly changed family must be noticed even
                // when it is not the currently selected family.
                runCatching { monitorTransportFamiliesAndAutoSelect() }
                    .onFailure { Log.w(TAG, "Transport-family monitor failed: ${it.message}") }
                val ready = runCatching { isSelectedTransportReady() }.getOrDefault(false)
                if (ready && explicitTransportSelectionWaitingForReady) {
                    explicitTransportSelectionWaitingForReady = false
                    Log.i(TAG, "✅ Explicitly selected transport is ready; returning to normal auto-detection")
                }
                val signature = if (ready) currentSelectedTransportSignature() else ""
                // Include a recoverable session that is between sockets/handshakes.
                // During a mode switch isConnected/shouldStream can be false briefly
                // while the Service tears down the old route; it must still watch for
                // the replacement wired/wireless interface.
                val activeSession = isConnected || isCurrentlyConnecting || shouldStream ||
                        sessionWasConnected || connectionWaitingForTransport || isReconnectingState

                if (connectionWaitingForTransport) {
                    if (ready && !isUserPaused && !isUserDisconnected && !hasStopped) {
                        if (signature.isNotBlank()) lastObservedTransportSignature = signature
                        // A mode-switch recovery job owns teardown/bind/start while
                        // active. Don't race it with a second discovery attempt.
                        if (networkReconnectJob?.isActive != true && reconnectJob?.isActive != true) {
                            connectionWaitingForTransport = false
                            if (!isConnected && !isCurrentlyConnecting && !shouldStream && autoReconnectEnabled) {
                                Log.i(TAG, "🔌 Selected transport became ready without a Network callback — starting recovery")
                                scheduleInitialServiceConnection()
                            } else if (isConnected || shouldStream) {
                                isReconnectingState = false
                            }
                        }
                    } else if (!ready) {
                        lastObservedTransportSignature = ""
                    }
                    continue
                }

                if (!ready) {
                    if (lastObservedTransportSignature.isNotBlank() && activeSession &&
                        !isUserPaused && !isUserDisconnected && !hasStopped) {
                        lastObservedTransportSignature = ""
                        Log.w(TAG, "🛜 Selected transport disappeared from interface table")
                        handleDetectedTransportLoss()
                    } else if (!activeSession) {
                        lastObservedTransportSignature = ""
                    }
                    continue
                }

                if (signature.isBlank()) continue
                val oldSignature = lastObservedTransportSignature
                if (oldSignature.isBlank()) {
                    lastObservedTransportSignature = signature
                    continue
                }
                if (signature != oldSignature) {
                    lastObservedTransportSignature = signature
                    Log.i(TAG, "🔄 Transport signature changed: $oldSignature -> $signature")
                    publishConnectionPathChanged()
                    if (activeSession && !isUserPaused && !isUserDisconnected && !hasStopped) {
                        if (autoReconnectEnabled) scheduleNetworkRouteReconnect()
                        else makeNetworkRouteReconnectAvailable()
                    }
                }
            }
        }

        showForegroundNotification("🔄 Waiting to connect…")
    }


    private fun setupServiceNetworkMonitoring() {

        try {

            val cm =
                getSystemService(Context.CONNECTIVITY_SERVICE)
                        as ConnectivityManager

            val request =
                android.net.NetworkRequest.Builder()
                    // Keep this broad enough for local LAN / hotspot use.
                    // We intentionally do NOT require NET_CAPABILITY_INTERNET.
                    .addTransportType(
                        NetworkCapabilities.TRANSPORT_WIFI
                    )
                    .addTransportType(
                        NetworkCapabilities.TRANSPORT_ETHERNET
                    )
                    // USB tethering can be reported as TRANSPORT_CELLULAR with
                    // an RNDIS/ECM/NCM interface on Android OEM builds.
                    .addTransportType(
                        NetworkCapabilities.TRANSPORT_CELLULAR
                    )
                    .build()

            networkCallback =
                object : ConnectivityManager.NetworkCallback() {

                    override fun onAvailable(network: Network) {

                        try {

                            val capabilities =
                                cm.getNetworkCapabilities(network)
                                    ?: return

                            val linkProperties =
                                cm.getLinkProperties(network)

                            val interfaceName =
                                linkProperties
                                    ?.interfaceName
                                    ?.lowercase(Locale.US)
                                    .orEmpty()

                            // Match the existing application's mode logic.
                            //
                            // Wired can appear as Ethernet OR as USB/RNDIS-like
                            // interfaces depending on the Android device.
                            val hostedHotspot = if (!isWiredMode) {
                                MobileHotspotDatabase.detect(
                                    applicationContext,
                                    MobileHotspotDatabase.WirelessPath.MOBILE_HOTSPOT
                                )
                            } else null
                            val networkIsHostedHotspot = hostedHotspot?.isReady == true &&
                                (linkProperties?.linkAddresses?.any {
                                    it.address.hostAddress == hostedHotspot.localIpv4
                                } == true || interfaceName.equals(hostedHotspot.interfaceName, ignoreCase = true))
                            val requestedEndpoint = targetIp.trim()
                            val endpointUsesHostedHotspot = hostedHotspot?.isReady == true && (
                                requestedEndpoint.isBlank() || isIpv4InSubnet(
                                    requestedEndpoint,
                                    hostedHotspot.localIpv4.orEmpty(),
                                    hostedHotspot.prefixLength ?: 24
                                )
                            )
                            val validForMode =
                                if (isWiredMode) {
                                    isWiredNetworkCandidate(interfaceName, capabilities)
                                } else if (endpointUsesHostedHotspot) {
                                    // AUTO (no endpoint yet) or a target on the SoftAP subnet
                                    // uses kernel routing unless Android exposes that SoftAP as
                                    // a Network. A manual/QR target on another Wi-Fi subnet must
                                    // still be allowed to use its actual Wi-Fi client Network.
                                    networkIsHostedHotspot
                                } else {
                                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                            isLikelyHotspotInterface(interfaceName)
                                }

                            if (!validForMode) {

                                Log.d(
                                    TAG,
                                    "🛜 Ignoring network ${network.networkHandle} " +
                                            "because it does not match current " +
                                            if (isWiredMode) "wired" else "wireless" +
                                                    " mode"
                                )

                                return
                            }

                            val newHandle = network.networkHandle
                            val oldHandle = boundNetworkHandle

                            // --------------------------------------------------
                            // FIRST CALLBACK
                            // --------------------------------------------------
                            //
                            // The first callback after registration establishes
                            // the correct route. It does NOT trigger reconnect.
                            //
                            if (!networkMonitoringInitialized) {

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                                    cm.bindProcessToNetwork(network)

                                } else if (
                                    Build.VERSION.SDK_INT >=
                                    Build.VERSION_CODES.LOLLIPOP
                                ) {

                                    ConnectivityManager
                                        .setProcessDefaultNetwork(network)
                                }

                                boundNetworkHandle = newHandle
                                boundNetwork = network
                                networkMonitoringInitialized = true
                                lastObservedTransportSignature = currentSelectedTransportSignature()
                                publishConnectionPathChanged()

                                Log.i(
                                    TAG,
                                    "🛜 Initial network bound by Service: " +
                                            "$newHandle"
                                )

                                // Initial connection is also Service-owned. This is
                                // deliberately separate from route-change recovery.
                                scheduleInitialServiceConnection()
                                return
                            }

                            // --------------------------------------------------
                            // SAME NETWORK
                            // --------------------------------------------------
                            if (oldHandle == newHandle) {
                                return
                            }

                            // --------------------------------------------------
                            // REAL NETWORK ROUTE CHANGE
                            // --------------------------------------------------
                            //
                            // IMPORTANT:
                            // Bind the replacement network FIRST.
                            // Only then ask the existing reconnect controller
                            // to rebuild the UDP/native connection.
                            //
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                                val bound =
                                    cm.bindProcessToNetwork(network)

                                if (!bound) {
                                    Log.w(
                                        TAG,
                                        "⚠️ Service failed to bind replacement network: " +
                                                "$newHandle"
                                    )
                                    return
                                }

                            } else if (
                                Build.VERSION.SDK_INT >=
                                Build.VERSION_CODES.LOLLIPOP
                            ) {

                                ConnectivityManager
                                    .setProcessDefaultNetwork(network)
                            }

                            boundNetworkHandle = newHandle
                            boundNetwork = network
                            lastObservedTransportSignature = currentSelectedTransportSignature()
                            publishConnectionPathChanged()

                            Log.i(
                                TAG,
                                "🔄 Service network route changed: " +
                                        "old=$oldHandle new=$newHandle"
                            )

                            // --------------------------------------------------
                            // USER-INTENT GUARDS / MANUAL RECOVERY MODE
                            // --------------------------------------------------
                            // Explicit user pause/disconnect always wins.
                            if (isUserPaused || isUserDisconnected || hasStopped) {
                                Log.d(
                                    TAG,
                                    "⏸️ Network changed but explicit user stop/pause suppresses recovery " +
                                            "(userPaused=$isUserPaused, userDisconnected=$isUserDisconnected, hasStopped=$hasStopped)"
                                )
                                return
                            }

                            // Auto-reconnect OFF is not a dead-end anymore: keep
                            // the Service alive and surface a ReConnect action.
                            // This works with MainActivity alive OR killed.
                            if (!autoReconnectEnabled) {
                                makeNetworkRouteReconnectAvailable()
                                return
                            }

                            // --------------------------------------------------
                            // ONLY RECONNECT A LIVE / ACTIVE SESSION
                            // --------------------------------------------------
                            //
                            // Initial server discovery remains owned by the UI
                            // when required. This path is specifically for an
                            // already-running stream whose route changed.
                            //
                            if (connectionWaitingForTransport) {
                                connectionWaitingForTransport = false
                                Log.i(TAG, "✅ Selected transport is now available — resuming pending connection")
                                scheduleInitialServiceConnection()
                                return
                            }

                            if (
                                !isConnected &&
                                !isCurrentlyConnecting &&
                                !shouldStream &&
                                !sessionWasConnected &&
                                !connectionWaitingForTransport &&
                                !isReconnectingState
                            ) {

                                Log.d(
                                    TAG,
                                    "🛡️ Route changed but Service is not " +
                                            "currently streaming; no reconnect needed"
                                )

                                return
                            }

                            scheduleNetworkRouteReconnect()

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "❌ Service network onAvailable failed: " +
                                        "${e.message}",
                                e
                            )
                        }
                    }

                    override fun onLost(network: Network) {

                        // Ignore stale/lost callbacks belonging to an older
                        // network that is no longer the Service's active route.
                        if (network.networkHandle != boundNetworkHandle) {
                            return
                        }

                        Log.w(TAG, "🛜 Service network lost: ${network.networkHandle}")

                        // bindProcessToNetwork is process-wide. Clearing only the
                        // Service's reference leaves Java/JNI sockets pinned to the
                        // dead Network and produces ENONET during route transitions.
                        try {
                            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                cm.bindProcessToNetwork(null)
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                ConnectivityManager.setProcessDefaultNetwork(null)
                            }
                            Log.i(TAG, "🌐 Cleared process network binding after selected Network was lost")
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ Could not clear process network binding after loss: ${e.message}")
                        }

                        boundNetworkHandle = -1L
                        boundNetwork = null
                        lastObservedTransportSignature = ""
                        publishConnectionPathChanged()

                        // An endpoint discovered on the lost route is not safe
                        // to reuse on the replacement subnet. AUTO mode must
                        // obtain a fresh endpoint after transport restoration.
                        if (autoConnectMode) {
                            targetIp = ""
                            targetPort = 0
                            Log.i(TAG, "🔎 Lost route: invalidated cached AUTO endpoint before rediscovery")
                        }

                        val activeSession = isConnected || isCurrentlyConnecting || shouldStream ||
                                sessionWasConnected || connectionWaitingForTransport || isReconnectingState
                        if (!activeSession) return

                        if (isUserPaused || isUserDisconnected || hasStopped) {
                            Log.i(TAG, "⏸️ Network lost while user-stopped; preserving explicit stop state")
                            return
                        }

                        if (!autoReconnectEnabled) {
                            // Auto-reconnect OFF: do not restart in the background,
                            // but tear down the dead native session immediately so
                            // the UI cannot remain frozen in the old Connected state.
                            Log.i(TAG, "🛑 Network lost with auto-reconnect OFF — stopping dead session without reconnect")
                            connectionWaitingForTransport = false
                            isReconnectingState = false
                            stopStreaming(notifyUi = false, cancelReconnect = false)
                            sendUiCommand(ACTION_UI_RESET)
                            return
                        }

                        // Auto-reconnect ON: tear down immediately and wait for the
                        // replacement transport. The next onAvailable() callback
                        // performs exactly one fresh Service-owned connect/discovery.
                        connectionWaitingForTransport = true
                        isReconnectingState = true
                        sendBroadcast(
                            Intent("com.example.wifiaudiospeaker.ACTION_UI_RECONNECTING")
                                .setPackage(packageName)
                                .putExtra("delay_seconds", 0L)
                                .putExtra("attempt", 1)
                                .putExtra("max", maxReconnectAttempts)
                        )
                        updateNotification("🔄 Network lost — waiting for replacement network…")

                        stopStreaming(notifyUi = false, cancelReconnect = false)
                    }
                }

            cm.registerNetworkCallback(
                request,
                networkCallback!!
            )

            Log.i(
                TAG,
                "🛜 Service network monitoring registered"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed to register Service network monitor: " +
                        "${e.message}",
                e
            )
        }
    }



    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent == null) {
            // START_STICKY can restart the Service without recreating the
            // Activity. If the last session was connected and the user did not
            // explicitly pause/disconnect, let the Service recover it itself.
            if (
                sessionWasConnected &&
                autoReconnectEnabled &&
                !isUserPaused &&
                !isUserDisconnected &&
                !isConnected &&
                !isCurrentlyConnecting
            ) {
                Log.i(TAG, "♻️ START_STICKY restart — recovering previous connected session")
                scheduleInitialServiceConnection()
            }
            return START_STICKY
        }

        val previousWiredMode = isWiredMode
        val previousAutoConnectMode = autoConnectMode
        val previousAutoReconnectEnabled = autoReconnectEnabled
        autoConnectMode = intent.getBooleanExtra("AUTO_MODE", autoConnectMode)
        autoReconnectEnabled = intent.getBooleanExtra("AUTO_RECONNECT", autoReconnectEnabled)
        if (intent.hasExtra("IS_WIRED")) {
            isWiredMode = intent.getBooleanExtra("IS_WIRED", isWiredMode)
        }
        if (intent.hasExtra("BUFFER_MODE") && !intent.hasExtra("SET_BUFFER_SIZE")) {
            val requestedMode = BufferModeConfig.Mode.fromKey(intent.getStringExtra("BUFFER_MODE"))
            customBufferMs = intent.getIntExtra("CUSTOM_BUFFER_MS", customBufferMs)
                .coerceIn(BufferModeConfig.MIN_CUSTOM_MS, BufferModeConfig.MAX_CUSTOM_MS)
            val prefs = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
            val selection = BufferModeConfig.save(prefs, requestedMode, customBufferMs)
            bufferModeKey = selection.mode.key
            customBufferMs = selection.customMs
            jitterBufferMs = if (intent.hasExtra("BUFFER_MS")) {
                intent.getIntExtra("BUFFER_MS", selection.bufferMs)
                    .coerceIn(BufferModeConfig.MIN_CUSTOM_MS, BufferModeConfig.MAX_CUSTOM_MS)
            } else selection.bufferMs
        } else if (!intent.hasExtra("SET_BUFFER_SIZE") &&
            intent.hasExtra("BUFFER_MS") &&
            !getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE).contains(BufferModeConfig.PREF_MODE)) {
            // Narrow legacy fallback only. Once migrated, older hard-coded BUFFER_MS
            // extras must not override the user's saved four-choice setting.
            jitterBufferMs = intent.getIntExtra("BUFFER_MS", jitterBufferMs)
                .coerceIn(BufferModeConfig.MIN_CUSTOM_MS, BufferModeConfig.MAX_CUSTOM_MS)
            bufferModeKey = BufferModeConfig.Mode.fromMilliseconds(jitterBufferMs).key
        }

        Log.d(TAG, "AudioStreamingService on Start Command called with action: ${intent.action}" )

        when (intent.action) {
            ACTION_APP_LAUNCH_BOOTSTRAP -> {
                // Every normal launcher start reaches the Service first so the
                // foreground notification exists before MainActivity/SetupActivity
                // is constructed. This branch is deliberately UI/bootstrap-only:
                // it NEVER starts a network connection by itself.
                isReconnectingState = false
                updateNotification(
                    when {
                        reconnectAvailable -> "⚡ Disconnected by Server • Tap ReConnect"
                        resumeAvailable -> "⏸️ Stopped • Tap Resume to reconnect"
                        else -> "🔄 Waiting to connect…"
                    }
                )
                return START_STICKY
            }

            ACTION_SETUP_CONFIG_UPDATE -> {
                // SharedPreferences is authoritative for the persisted Auto Reconnect
                // switch. MainActivity instances can be stale after Activity recreation;
                // don't let a stale intent silently disable Service recovery.
                val connectionPrefs = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                val persistedAutoReconnect = readPersistedAutoReconnectPreference(
                    connectionPrefs,
                    previousAutoReconnectEnabled
                )
                connectionPrefs.edit()
                    .putBoolean("auto_reconnect", persistedAutoReconnect)
                    .putBoolean("auto_connect", persistedAutoReconnect)
                    .apply()
                if (autoReconnectEnabled != persistedAutoReconnect) {
                    Log.w(
                        TAG,
                        "🔁 Reconciling Auto Reconnect intent=${autoReconnectEnabled} " +
                                "with saved preference=${persistedAutoReconnect}"
                    )
                }
                autoReconnectEnabled = persistedAutoReconnect

                val transportChanged = previousWiredMode != isWiredMode
                val methodChanged = previousAutoConnectMode != autoConnectMode
                if (transportChanged) {
                    explicitTransportSelectionWaitingForReady =
                        autoReconnectEnabled && !isUserPaused && !isUserDisconnected && !hasStopped
                }
                Log.i(
                    TAG,
                    "⚙️ Setup configuration updated: mode=${if (isWiredMode) "WIRED" else "WIRELESS"}, " +
                            "method=${if (autoConnectMode) "AUTO" else "MANUAL"}, " +
                            "autoReconnect=$autoReconnectEnabled, transportChanged=$transportChanged"
                )

                // A mode can change while teardown/retry is in progress. Treat that
                // recoverable state as an active session, but preserve explicit Stop,
                // Pause and Disconnect guards below.
                val activeSession = isConnected || isCurrentlyConnecting || shouldStream ||
                        sessionWasConnected || connectionWaitingForTransport || isReconnectingState

                // Apply a SettingsActivity toggle immediately to the long-lived
                // service, not only when the user later changes transport.
                if (!autoReconnectEnabled) {
                    reconnectJob?.cancel()
                    networkReconnectJob?.cancel()
                    if (!isConnected && !shouldStream) {
                        connectionWaitingForTransport = false
                        isReconnectingState = false
                    }
                } else if (
                    !previousAutoReconnectEnabled && activeSession &&
                    !isConnected && !shouldStream && !isUserPaused &&
                    !isUserDisconnected && !hasStopped
                ) {
                    Log.i(TAG, "🔁 Auto Reconnect enabled while a prior session is recoverable")
                    if (isSelectedTransportReady()) {
                        scheduleInitialServiceConnection()
                    } else {
                        connectionWaitingForTransport = true
                        publishTransportUnavailable()
                    }
                }

                if (transportChanged && activeSession && !isUserPaused && !isUserDisconnected && !hasStopped) {
                    if (autoReconnectEnabled) {
                        isReconnectingState = true
                        connectionWaitingForTransport = true
                        if (autoConnectMode) {
                            targetIp = ""
                            targetPort = 0
                            currentServerAddress = ""
                        }
                        sendBroadcast(Intent("com.example.wifiaudiospeaker.ACTION_UI_RECONNECTING")
                            .setPackage(packageName)
                            .putExtra("delay_seconds", 0L)
                            .putExtra("attempt", 1)
                            .putExtra("max", -1))
                        updateNotification("🔄 Transport mode changed — reconnecting on ${if (isWiredMode) "USB tether" else "wireless"}…")
                        networkReconnectJob?.cancel()
                        networkReconnectJob = serviceIoScope.launch {
                            val owningJob = currentCoroutineContext()[Job]
                            try {
                                stopStreaming(notifyUi = false, cancelReconnect = true)
                                hasStopped = false
                                isUserDisconnected = false
                                isUserPaused = false
                                connectionWaitingForTransport = !bindCurrentModeNetworkNow()
                                if (!connectionWaitingForTransport) {
                                    lastObservedTransportSignature = currentSelectedTransportSignature()
                                    scheduleInitialServiceConnection()
                                } else {
                                    publishTransportUnavailable()
                                }
                            } finally {
                                if (networkReconnectJob === owningJob) networkReconnectJob = null
                            }
                        }
                    } else {
                        Log.i(TAG, "🔕 Transport mode changed while a session was active, but Auto Reconnect is OFF")
                        makeNetworkRouteReconnectAvailable()
                    }
                } else if (transportChanged && (isUserPaused || isUserDisconnected || hasStopped)) {
                    Log.i(
                        TAG,
                        "⏹️ Mode changed while explicitly stopped; not auto-connecting " +
                                "(paused=$isUserPaused, disconnected=$isUserDisconnected, stopped=$hasStopped)"
                    )
                } else if (connectionWaitingForTransport && autoReconnectEnabled && !isUserPaused && !isUserDisconnected && !hasStopped) {
                    scheduleInitialServiceConnection()
                }
                updateNotification("✅ 📱 $deviceName • setup updated")
                return START_STICKY
            }

            ACTION_SETUP_AUTO_CONNECT -> {
                // SetupActivity asks the Service to perform discovery using the
                // currently bound network. This is an explicit user action, so
                // it must never inherit a stale endpoint from the previous LAN.
                // The top-level intent extras have already updated AUTO_MODE and
                // AUTO_RECONNECT; force the mode here as an additional guard.
                autoConnectMode = true
                targetIp = ""
                targetPort = 0
                currentServerAddress = ""
                isUserDisconnected = false
                isUserPaused = false
                resumeAvailable = false
                reconnectAvailable = false
                hasStopped = false
                sessionWasConnected = false
                getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("user_paused", false)
                    .putBoolean("user_disconnected", false)
                    .putBoolean("notification_reconnect", false)
                    .putBoolean("session_was_connected", false)
                    .apply()

                val explicitOneShot = intent.getBooleanExtra("FORCE_MANUAL_CONNECT", false)
                if (explicitOneShot) {
                    autoReconnectEnabled = false
                    autoConnectMode = true
                }
                scheduleInitialServiceConnection(forceManual = explicitOneShot)
                return START_STICKY
            }

            ACTION_DISCONNECT -> {
                Log.i(TAG, " 📴 Notification requested Disconnect ")
                isUserPaused = false
                isUserDisconnected = true
                isPausedState = false
                isReconnectingState = false
                reconnectAvailable = false
                resumeAvailable = false
                sessionWasConnected = false
                getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("user_paused", false)
                    .putBoolean("user_disconnected", true)
                    .putBoolean("notification_reconnect", false)
                    .putBoolean("session_was_connected", false)
                    .apply()

                stopStreaming(notifyUi = false)
                sendUiCommand(ACTION_UI_RESET)

                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_NOTIFICATION_STOP -> {
                Log.i(TAG, "⏸️ Notification  PAUSE|STOP requested")
                pauseByUser()

                return START_STICKY
            }
            ACTION_NOTIFICATION_RESUME -> {
                Log.i(TAG, "▶️ Notification requested Resume — Service owns the resume")
                resumeStreamingFromNotification()
                return START_STICKY
            }
            ACTION_NOTIFICATION_CLOSE_APP -> {
                Log.i(TAG, "🛑 Notification requested Close App")
                isUserPaused = false
                isUserDisconnected = true
                isPausedState = false
                isReconnectingState = false
                reconnectAvailable = false
                resumeAvailable = false
                sessionWasConnected = false
                getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("user_paused", false)
                    .putBoolean("user_disconnected", true)
                    .putBoolean("notification_reconnect", false)
                    .putBoolean("session_was_connected", false)
                    .apply()
                stopStreaming(notifyUi = false)
                sendUiCommand(ACTION_UI_CLOSE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISCONNECT_CLOSE -> {
                Log.i(TAG, "📴 Notification requested Disconnect & Close")
                isUserPaused = false
                isUserDisconnected = true
                isPausedState = false
                isReconnectingState = false
                reconnectAvailable = false
                resumeAvailable = false
                sessionWasConnected = false
                getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("user_paused", false)
                    .putBoolean("user_disconnected", true)
                    .putBoolean("notification_reconnect", false)
                    .putBoolean("session_was_connected", false)
                    .apply()
                stopStreaming(notifyUi = false)
                sendUiCommand(ACTION_UI_CLOSE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_NOTIFICATION_PC_MEDIA -> {
                if (!isConnected || !shouldStream) {
                    Log.w(TAG, "🖥️ PC media control ignored — no active streaming session")
                    return START_STICKY
                }

                val controlJob = sendPcControlToServer(PC_CONTROL_MEDIA)
                requestPcStateAfterControl("MEDIA", controlJob)
                return START_STICKY
            }

            ACTION_NOTIFICATION_PC_MUTE -> {
                if (!isConnected || !shouldStream) {
                    Log.w(TAG, "🖥️ PC mute control ignored — no active streaming session")
                    return START_STICKY
                }

                val controlJob = sendPcControlToServer(PC_CONTROL_MUTE)
                requestPcStateAfterControl("MUTE", controlJob)
                return START_STICKY
            }
            "ACTION_TOGGLE_MUTE" -> {
                Log.i(TAG, "📴 Notification requested Toggle Mute")

                val systemAudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val currentStreamVolume = systemAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVolume = systemAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val currentlyMuted = currentStreamVolume == 0
                val audioPrefs = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                if (currentlyMuted) {
                    val rememberedPercent = audioPrefs.getFloat(
                        "last_volume",
                        audioPrefs.getInt("volume", 75).toFloat()
                    ).coerceIn(1f, 100f)
                    val restoreLevel = ((rememberedPercent / 100f) * maxVolume)
                        .toInt().coerceIn(1, maxVolume)
                    systemAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreLevel, 0)
                } else {
                    // Remember the pre-mute system media level so the notification
                    // toggle and dashboard toggle restore the same shared volume.
                    val currentPercent = ((currentStreamVolume * 100f) / maxVolume)
                        .toInt().coerceIn(1, 100)
                    audioPrefs.edit()
                        .putFloat("last_volume", currentPercent.toFloat())
                        .putInt("volume", currentPercent)
                        .apply()
                    systemAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                }
                updateNotification(lastNotificationText)

                // Sync the UI if MainActivity happens to be open
                sendBroadcast(Intent("com.example.wifiaudiospeaker.ACTION_UI_SYNC_MUTE")
                    .setPackage(packageName)
                    .putExtra("is_muted", !currentlyMuted))

                return START_STICKY
            }
        }

        // ==========================================================
        // CONTROL-ONLY INTENTS (DO NOT TOUCH CONNECTION STATE)
        // ==========================================================

        // MainActivity may restore settings after the Activity itself is
        // recreated. This updates the Service owner only; it never performs
        // the reconnect work from Activity code.
        if (
            !intent.hasExtra("IP") &&
            !intent.hasExtra("PORT") &&
            (intent.hasExtra("AUTO_RECONNECT") ||
                    intent.hasExtra("AUTO_MODE") ||
                    intent.hasExtra("IS_WIRED"))
        ) {
            autoReconnectEnabled = intent.getBooleanExtra("AUTO_RECONNECT", autoReconnectEnabled)
            autoConnectMode = intent.getBooleanExtra("AUTO_MODE", autoConnectMode)
            isWiredMode = intent.getBooleanExtra("IS_WIRED", isWiredMode)

            if (!autoReconnectEnabled) {
                reconnectJob?.cancel()
                reconnectJob = null
                initialConnectJob?.cancel()
                initialConnectJob = null
                isReconnectingState = false
                updateNotification("⏹️ Auto-reconnect disabled")
            } else if (
                !isUserPaused &&
                !isUserDisconnected &&
                !isConnected &&
                !isCurrentlyConnecting
            ) {
                scheduleInitialServiceConnection()
            }

            return START_STICKY
        }

        // Buffer/jitter update intent
        if (intent.hasExtra("SET_VOLUME")  &&
            !intent.hasExtra("IP") &&
            !intent.hasExtra("PORT")) {

            val vol = intent.getFloatExtra("SET_VOLUME", 1.0f).coerceIn(0f, 1f)
            val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, (vol * maxVolume).toInt().coerceIn(0, maxVolume), 0)

            intent.getStringExtra("SERVER_NAME")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                serverDisplayName = it
            }
            intent.getStringExtra("SERVER_BACKEND")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                serverBackendName = it
            }

            updateNotification(lastNotificationText)
            return START_STICKY
        }

        if (intent.hasExtra("SET_BUFFER_SIZE") &&
            !intent.hasExtra("IP") &&
            !intent.hasExtra("PORT")) {

            // IMPORTANT: this snapshot must happen before saving the requested mode.
            // The generic BUFFER_MODE parser above deliberately skips SET_BUFFER_SIZE
            // intents, otherwise it overwrites bufferModeKey and hides the transition.
            val previousBufferModeKey = bufferModeKey
            val previousBufferMs = jitterBufferMs
            val newMs = intent.getIntExtra("SET_BUFFER_SIZE", jitterBufferMs)
                .coerceIn(BufferModeConfig.MIN_CUSTOM_MS, BufferModeConfig.MAX_CUSTOM_MS)
            val requestedMode = intent.getStringExtra("BUFFER_MODE")?.let {
                BufferModeConfig.Mode.fromKey(it)
            } ?: BufferModeConfig.Mode.fromMilliseconds(newMs)
            customBufferMs = intent.getIntExtra("CUSTOM_BUFFER_MS", if (requestedMode == BufferModeConfig.Mode.CUSTOM) newMs else customBufferMs)
                .coerceIn(BufferModeConfig.MIN_CUSTOM_MS, BufferModeConfig.MAX_CUSTOM_MS)
            val savedSelection = BufferModeConfig.save(
                getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE), requestedMode, customBufferMs
            )
            bufferModeKey = savedSelection.mode.key
            customBufferMs = savedSelection.customMs
            val appliedMs = if (intent.hasExtra("BUFFER_MODE")) savedSelection.bufferMs else newMs
            Log.i(TAG, "🎛️ Applying ${savedSelection.mode.title} jitter buffer update: ${appliedMs}ms")

            val isHighBuffer = savedSelection.mode == BufferModeConfig.Mode.HIGH
            val isMediumBuffer = savedSelection.mode == BufferModeConfig.Mode.MEDIUM
            val isLowBuffer = savedSelection.mode == BufferModeConfig.Mode.LOW
            val isUltraLowBuffer = savedSelection.mode == BufferModeConfig.Mode.ULTRA_LOW
            if (isConnected && shouldStream && savedSelection.mode == BufferModeConfig.Mode.CUSTOM) {
                try {
                    drainNativeAudioBacklog(256)
                    Log.d(TAG, "🧹 Cleared native audio backlog before Custom profile switch")
                } catch (e: Throwable) {
                    Log.w(TAG, "⚠️ Could not flush audio backlog during Custom mode switch: ${e.message}")
                }
            } else if (isConnected && shouldStream && isUltraLowBuffer) {
                // Golden Ultra Low owns its own 40ms stale-frame fast-forward.
                // Do not pre-drain the native ring here; that extra transition-time
                // deletion was not part of the original Golden playback pipeline.
                Log.i(TAG, "⚡ Golden transition: preserve native PCM; Golden fast-forward will manage stale backlog")
            } else if (isConnected && shouldStream && isLowBuffer) {
                try {
                    val trimmed = trimNativeAudioBacklogToMs(120)
                    Log.i(TAG, "🎯 Low buffer selected: retained recent PCM; trimmed ${trimmed} stale samples toward 120ms lip-sync target")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Low buffer catch-up skipped: ${e.message}")
                }
            } else if (isConnected && shouldStream && isHighBuffer) {
                Log.i(TAG, "🔊 High buffer selected: preserving queued PCM; continuity takes priority over lip-sync")
            } else if (isConnected && shouldStream && isMediumBuffer) {
                Log.i(TAG, "⚖️ Medium buffer selected: preserving queued PCM for balanced pre-roll and jitter tolerance")
            }

            jitterBufferMs = appliedMs
            nativeServiceSetJitterBuffer(appliedMs)
            val isUltraLow = savedSelection.mode == BufferModeConfig.Mode.ULTRA_LOW
            nativeServiceSetUltraLowProfile(isUltraLow)
            nativeServiceSetHighBufferProfile(isHighBuffer)
            nativeServiceSetMediumBufferProfile(isMediumBuffer)
            nativeServiceSetLowBufferProfile(isLowBuffer)
            nativeServiceSetCustomBufferProfile(
                savedSelection.mode == BufferModeConfig.Mode.CUSTOM,
                customBufferMs
            )
            cachedCustomBufferDecision = null
            lastCustomDecisionRefreshAtMs = 0L
            if (savedSelection.mode == BufferModeConfig.Mode.CUSTOM) {
                currentCustomBufferDecision(forceRefresh = true)
            }
            val profileChanged = previousBufferModeKey != bufferModeKey
            val bufferValueChanged = previousBufferMs != appliedMs
            if (isConnected && shouldStream) {
                if (profileChanged) {
                    // Rebuild for ALL profile transitions, not only Low/Medium/High.
                    // Ultra Low must re-enter its original FLAG_LOW_LATENCY track
                    // configuration; retaining a 1500ms High AudioTrack defeats Golden.
                    audioTrackRebuildRequested = true
                    Log.i(
                        TAG,
                        "🎚️ Audio profile transition $previousBufferModeKey -> $bufferModeKey " +
                            "(buffer ${previousBufferMs}ms -> ${appliedMs}ms); AudioTrack rebuild queued at next 10ms block boundary"
                    )
                } else if (bufferValueChanged) {
                    applyAudioTrackBufferPolicy()
                }
                sendBufferProfileTelemetryNow(currentServerAddress.substringBefore(":").trim())
                if (savedSelection.mode == BufferModeConfig.Mode.CUSTOM) {
                    refreshCustomNetworkStateAsync("slider release")
                }
            }
            updateNotification("🎛️ ${savedSelection.mode.title}: ${jitterBufferMs}ms")
            Log.i(
                TAG,
                "🎚️ Streaming profile=${when { isUltraLow -> "golden_ultra_low_v1"; isHighBuffer -> "high_continuity_v1"; isMediumBuffer -> "medium_balanced_v1"; isLowBuffer -> "low_lipsync_v1"; savedSelection.mode == BufferModeConfig.Mode.CUSTOM -> CustomBufferEngine.PROFILE; else -> "adaptive_v2" }}, " +
                    "mode=${savedSelection.mode.key}, buffer=${appliedMs}ms"
            )
            return START_STICKY
        }

        if (intent.hasExtra("SERVER_NAME") &&
            !intent.hasExtra("IP") &&
            !intent.hasExtra("PORT")) {

            val newServerName = intent.getStringExtra("SERVER_NAME")?.trim().orEmpty()
            val newBackend = intent.getStringExtra("SERVER_BACKEND")?.trim().orEmpty()
            if (newServerName.isNotEmpty()) {
                serverDisplayName = newServerName
            }
            if (newBackend.isNotEmpty()) {
                serverBackendName = newBackend
            }
            updateNotification("\uD83D\uDEDC Connected to $serverDisplayName")

            return START_STICKY
        }

        if (!intent.hasExtra("IP") && !intent.hasExtra("PORT")) {
            Log.d(TAG, "Ignoring control-only intent; no connection start requested")
            return START_STICKY
        }

        // Set/Update   connection inputs - only after control-only intents are handled

        val newTargetIp = intent.getStringExtra("IP") ?: ""
        val newTargetPort = intent.getIntExtra("PORT", 0)
        val newServerAddress = "$newTargetIp:$newTargetPort"

        intent.getStringExtra("SERVER_NAME")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { serverDisplayName = it
                updateNotification("\uD83D\uDEDC Connected to $serverDisplayName")
            }

        // 🛡️ DUPLICATE CONNECTION PREVENTION and Allow immediate reconnection to same server if disconnected
        if ( isCurrentlyConnecting || isConnected && currentServerAddress == newServerAddress ) {
            if (currentServerAddress == newServerAddress) {
                Log.i(
                    TAG,
                    "🛡️ Already connecting/connected to $newServerAddress - ignoring duplicate request")
                updateNotification("✅ Already connected to $newServerAddress")
                isCurrentlyConnecting = false
                return START_STICKY
            }

        }
        // FIXED: Different server or Switching server - allow immediate connection after cleanup stop immediately, then reconnect
        if (currentServerAddress != newServerAddress && currentServerAddress.isNotEmpty()) {
            Log.i(TAG, "🔄 Different server requested OR  Switching server:($currentServerAddress → $newServerAddress)")
            // Allow connection to different server after stopping current one
            stopStreaming()
            // Avoid blocking the main thread; post delayed on handler
            Handler(Looper.getMainLooper()).postDelayed({ /* no-op */ }, 200) // Brief pause Shorter cleanup delay
        }


        // 🛡️ CONNECTION COOLDOWN
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastConnectionTime < connectionCooldownMs) {
            val wait = connectionCooldownMs - (currentTime - lastConnectionTime)
            Log.w(TAG, "🛡️ Connection cooldown active $wait ms before reconnect")
            updateNotification("⏳ Connection cooldown active...")
            isCurrentlyConnecting = false
            return START_STICKY
        }

        // Explicit user/manual/QR/auto-discovery connection is authoritative.
        initialConnectJob?.cancel()
        initialConnectJob = null
        isUserDisconnected = false
        isUserPaused = false
        isPausedState = false
        isReconnectingState = false

        // Update connection inputs
        targetIp = newTargetIp
        targetPort = newTargetPort
        currentServerAddress = newServerAddress
        lastConnectionTime = currentTime
        saveServiceTargetState()



        intent.getStringExtra("DEVICE_MANUFACTURER")?.let { deviceManufacturer = it }
        intent.getStringExtra("DEVICE_MODEL")?.let { deviceModel = it }
        intent.getStringExtra("DEVICE_NAME")?.let { deviceName = it }
        // All connection entry points (Main, Setup, QR/manual, and startup) use
        // the canonical saved selection. Only an explicit four-mode payload may
        // override it; legacy hard-coded 120ms/2ms extras are intentionally ignored.
        val storedBufferSelection = BufferModeConfig.load(
            getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
        )
        if (intent.hasExtra("BUFFER_MODE")) {
            val requestedMode = BufferModeConfig.Mode.fromKey(intent.getStringExtra("BUFFER_MODE"))
            customBufferMs = intent.getIntExtra("CUSTOM_BUFFER_MS", storedBufferSelection.customMs)
                .coerceIn(BufferModeConfig.MIN_CUSTOM_MS, BufferModeConfig.MAX_CUSTOM_MS)
            val explicitSelection = BufferModeConfig.save(
                getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE), requestedMode, customBufferMs
            )
            bufferModeKey = explicitSelection.mode.key
            jitterBufferMs = explicitSelection.bufferMs
            customBufferMs = explicitSelection.customMs
        } else {
            bufferModeKey = storedBufferSelection.mode.key
            jitterBufferMs = storedBufferSelection.bufferMs
            customBufferMs = storedBufferSelection.customMs
        }

        isWiredMode = intent.getBooleanExtra("IS_WIRED", isWiredMode)

        Log.i(TAG, "Device Info :> Manufacturer = $deviceManufacturer, Model = $deviceModel, Name = $deviceName")

        // Start/Restart streaming through the same Service-owned retry path as
        // Auto Discover. This is important for QR/Manual USB connections because
        // some OEMs create rndis0/eth*/ecm* a few hundred milliseconds after the
        // UI reports USB tethering as enabled.
        if (targetIp.isNotEmpty() && targetPort > 0) {
            autoConnectMode = false
            scheduleInitialServiceConnection(forceManual = true)
        } else {
            Log.d(TAG, "Ignoring control-only intent; no connection start requested")
            updateNotification("❌ Missing IP/Port")
        }

        return START_STICKY
    }

    private fun showForegroundNotification(text: String) {
        ensureNotificationPendingIntents()
        postNotification(text, foreground = true)
    }

    private fun ensureNotificationPendingIntents() {
        // Return to the screen the user was actually using when the
        // notification was last refreshed, instead of always opening MainActivity.
        val openIntent = MyApplication.currentVisibleActivityIntent(this)

        openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        stopPendingIntent = PendingIntent.getService(
            this, 1, Intent(this, AudioStreamingService::class.java).apply {
                action = ACTION_NOTIFICATION_STOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        resumePendingIntent = PendingIntent.getService(
            this, 4, Intent(this, AudioStreamingService::class.java).apply {
                action = ACTION_NOTIFICATION_RESUME
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        disconnectClosePendingIntent = PendingIntent.getService(
            this, 2, Intent(this, AudioStreamingService::class.java).apply {
                action = ACTION_DISCONNECT_CLOSE
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        closeAppPendingIntent = PendingIntent.getService(
            this, 5, Intent(this, AudioStreamingService::class.java).apply {
                action = ACTION_NOTIFICATION_CLOSE_APP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        pcMediaPendingIntent = PendingIntent.getService(
            this, 6, Intent(this, AudioStreamingService::class.java).apply {
                action = ACTION_NOTIFICATION_PC_MEDIA
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        pcMutePendingIntent = PendingIntent.getService(
            this, 7, Intent(this, AudioStreamingService::class.java).apply {
                action = ACTION_NOTIFICATION_PC_MUTE
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    // ============================================================
    // NOTIFICATION IDENTITY
    // ============================================================
    private fun buildNotificationTitle(): String {
        // Before a real SERVER_ACK has been accepted, the saved server name is
        // only historical state. Never present it as the current connection.
        val identity = if (isConnected && shouldStream) {
            serverDisplayName.trim()
                .takeIf { it.isNotEmpty() && !it.equals("Unknown", ignoreCase = true) }
                ?: deviceName
        } else {
            deviceName
        }
        return "$identity 🎵၊၊||၊🎶🔊🎶🎧  ⋆⋆✮♪♫\uD83C\uDFA7♫♪✮⋆⋆"
    }

    private fun buildNotificationSubtitle(): String {
        return "Streaming ｡♪˚♬ﾟ🎻🎶🎻🎵  ⋆.˚✮\uD83C\uDFA7✮˚.⋆ to 📱 $deviceName "
    }

    private fun postNotification(text: String, foreground: Boolean = false) {
        val notificationTitle = buildNotificationTitle()
        val activelyStreaming = isConnected && shouldStream

        // Force the app title/header block text to black color format
        val blackTitle = SpannableString(notificationTitle).apply {
            setSpan(ForegroundColorSpan(android.graphics.Color.BLACK), 0, length, 0)
        }
        val notificationSubtitle = if (activelyStreaming) {
            buildNotificationSubtitle()
        } else {
            ""
        }

        fun buildRemoteViews(layoutId: Int): RemoteViews {
            val views = RemoteViews(packageName, layoutId)

            val isCompactLayout = layoutId == R.layout.notification_compact
            val compactIdentity = if (activelyStreaming) {
                serverDisplayName.trim().ifBlank { deviceName }
            } else {
                deviceName
            }
            val compactSubtitle = ""
            val compactStatus = text
                .replace(Regex("[^A-Za-z0-9 %./:→↓•()_-]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()

            views.setTextViewText(
                R.id.notif_identity,
                if (isCompactLayout) compactIdentity else notificationTitle
            )
            views.setTextViewText(
                R.id.notif_subtitle,
                if (isCompactLayout) compactSubtitle else notificationSubtitle
            )

            // Keep the compact layout text short/plain so Lenovo-class small
            // notification widths do not squeeze out the two PC buttons.
            views.setTextViewText(
                R.id.notif_status,
                if (isCompactLayout) compactStatus
                else if (activelyStreaming) " ⏲\uFE0F $text" else text
            )

            views.setViewVisibility(
                R.id.notif_subtitle,
                if (activelyStreaming) android.view.View.VISIBLE else android.view.View.GONE
            )

            // PC media icon reflects the last authoritative Windows state.
            // Unknown state falls back to Pause rather than inventing a stop.
            val mediaIcon = if (pcMediaStateKnown && !pcMediaPlaying) {
                android.R.drawable.ic_media_play
            } else {
                android.R.drawable.ic_media_pause
            }
            views.setImageViewResource(R.id.notif_pc_media, mediaIcon)

            // PC mute icon reflects the authoritative Windows master-mute state.
            // Resolve platform mute/unmute drawables by name so old Android SDK
            // levels do not need a compile-time resource symbol.
            val muteDrawableName =
                if (pcAudioMuted) "ic_lock_silent_mode"
                else "ic_lock_silent_mode_off"
            val muteIcon = resources.getIdentifier(
                muteDrawableName,
                "drawable",
                "android"
            ).takeIf { it != 0 }
                ?: android.R.drawable.ic_lock_silent_mode
            views.setImageViewResource(R.id.notif_pc_mute, muteIcon)

            views.setViewVisibility(
                R.id.notif_pc_media,
                if (activelyStreaming) android.view.View.VISIBLE else android.view.View.GONE
            )
            views.setViewVisibility(
                R.id.notif_pc_mute,
                if (activelyStreaming) android.view.View.VISIBLE else android.view.View.GONE
            )

            if (activelyStreaming) {
                views.setOnClickPendingIntent(R.id.notif_pc_media, pcMediaPendingIntent)
                views.setOnClickPendingIntent(R.id.notif_pc_mute, pcMutePendingIntent)
            }

            return views
        }

        val compactViews = buildRemoteViews(R.layout.notification_compact)
        val expandedViews = buildRemoteViews(R.layout.notification_expanded)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPendingIntent)
            .setCustomContentView(compactViews)
            .setCustomBigContentView(expandedViews)
            // Keep the normal NotificationCompat action strip in addition to
            // our custom RemoteViews. This is what restores the legacy
            // PAUSE / Android-mute / Disconnect+Close controls on OEMs that
            // otherwise suppress Builder actions for plain custom views.
            // The PC media/mute controls remain embedded in the custom view.
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        /* Forces the system standard header app title row color to black
        .setColor(android.graphics.Color.BLACK)
        .setColorized(true)*/


        fun spanText(t: String): CharSequence {
            return SpannableString(t).apply {
                setSpan(ForegroundColorSpan(android.graphics.Color.BLACK), 0, length, 0)
            }
        }



        when {
            isConnected && shouldStream -> {
                val isMuted = try {
                    (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                        .getStreamVolume(AudioManager.STREAM_MUSIC) == 0
                } catch (_: Exception) { false }
                val muteText = if (isMuted) "📱 🔇" else "📱 🔊"
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    spanText("Pause"),
                    stopPendingIntent
                ).addAction(
                    0,
                    spanText(muteText),
                    createMutePendingIntent()
                ).addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    spanText("Disconnect & Close"),
                    disconnectClosePendingIntent
                )
            }
            reconnectAvailable -> {
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    spanText("ReConnect"),
                    resumePendingIntent
                ).addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    spanText("Close App"),
                    closeAppPendingIntent
                )
            }
            resumeAvailable -> {
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    spanText("Resume"),
                    resumePendingIntent
                ).addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    spanText("Close App"),
                    closeAppPendingIntent
                )
            }
            else -> {
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    spanText("Close App"),
                    closeAppPendingIntent
                )
            }
        }

        val notification = builder.build()
        try {
            if (foreground) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else if (hasNotificationPermission()) {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "Notification operation blocked by permission", se)
        }
    }

    private var lastNotificationText = "Waiting to connect…"

    private fun updateNotification(text: String) {
        lastNotificationText = text
        ensureNotificationPendingIntents()
        postNotification(text, foreground = false)
    }

    private fun createMutePendingIntent(): PendingIntent =
        PendingIntent.getService(
            this, 3,
            Intent(this, AudioStreamingService::class.java).apply {
                action = "ACTION_TOGGLE_MUTE"
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Rebuilds the notification content PendingIntent after the user moves
     * between Main/Setup/Settings. */
    fun refreshNotificationTarget() {
        try {
            ensureNotificationPendingIntents()
            postNotification(lastNotificationText, foreground = false)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Notification target refresh failed: ${e.message}")
        }
    }

    private fun hideForegroundNotification() {
        try {
            if (hasNotificationPermission()) {
                NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
            }
        } catch (_: SecurityException) {
            // ignore
        }
    }

    private fun sendUiCommand(action: String, forceDisconnected: Boolean = false, userRequested: Boolean = false) {
        sendBroadcast(Intent(action)
            .setPackage(packageName)
            .putExtra("force_disconnected", forceDisconnected)
            .putExtra("user_requested", userRequested))
    }

    private fun getPreferredLocalIpForCurrentTransport(destinationIp: String): String? {
        return try {
            if (isWiredMode) {
                return findActiveUsbTransportDetails()?.second
            }

            val hotspot = MobileHotspotDatabase.detect(
                applicationContext,
                MobileHotspotDatabase.WirelessPath.MOBILE_HOTSPOT
            )
            if (hotspot.isReady && isIpv4InSubnet(
                    destinationIp,
                    hotspot.localIpv4.orEmpty(),
                    hotspot.prefixLength ?: 24
                )) {
                return hotspot.localIpv4
            }

            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // Prefer the local IPv4 address whose directly-connected subnet
            // contains the destination. This prevents a simultaneously active
            // phone-hosted SoftAP from being selected for a manual/QR endpoint
            // that actually belongs to Home Wi-Fi or the PC Hotspot.
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val lp = cm.getLinkProperties(network) ?: continue
                for (linkAddress in lp.linkAddresses) {
                    val local = linkAddress.address as? Inet4Address ?: continue
                    val localIp = local.hostAddress.orEmpty()
                    if (local.isLoopbackAddress || local.isLinkLocalAddress) continue
                    if (isIpv4InSubnet(destinationIp, localIp, linkAddress.prefixLength)) {
                        return localIp
                    }
                }
            }

            // If no direct subnet match is visible, use the service-bound route
            // before considering an interface-only phone-hosted SoftAP.
            boundNetwork?.let { network ->
                cm.getLinkProperties(network)?.linkAddresses
                    ?.mapNotNull { it.address as? Inet4Address }
                    ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    ?.hostAddress
                    ?.let { return it }
            }
            if (hotspot.isReady) return hotspot.localIpv4

            cm.activeNetwork?.let { network ->
                cm.getLinkProperties(network)?.linkAddresses
                    ?.mapNotNull { it.address as? Inet4Address }
                    ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    ?.hostAddress
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not determine preferred local IP for $destinationIp: ${e.message}")
            null
        }
    }

    private fun startNativeStreaming(ip: String, port: Int,bufferMs: Int) {

        // 🛡️ PREVENT CONCURRENT CONNECTIONS

        synchronized(connectionLock){
            if (isCurrentlyConnecting) {
                Log.w(TAG, "🛡️ Already connecting, ignoring new connection request")
                return
            }

            isCurrentlyConnecting = true

            // Reset these flags only after the duplicate-request guard.
            // A rejected concurrent connection request must never reset the
            // teardown bookkeeping of the connection that is already running.
            nativeDisconnectIssued = false
            nativeStopIssued = false
        }

        isUserPaused = false
        resumeAvailable = false
        reconnectAvailable = false
        isPausedState = false
        isReconnectingState = false

        // A new session must re-read the REAL PC state from Windows rather
        // than inheriting stale notification-local values.
        pcMediaStateKnown = false
        pcAudioStateKnown = false
        publishPcStateToUi()

        getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("user_paused", false)
            .putBoolean("user_disconnected", false)
            .putBoolean("notification_reconnect", false)
            .apply()
        hasStopped = false
        shouldStream = true
        connectionAttempts++
        streamJob?.cancel()
        streamJob = serviceIoScope.launch {
            try {

                Log.d(TAG, "Attempting to connect to $ip:$port")
                updateNotification("🔌 Connecting $ip:$port …")

                // Make the native UDP socket use the same local interface that
                // discovery used. This is essential for OEM SoftAP/RNDIS paths
                // that do not expose a normal ConnectivityManager Network.
                val preferredLocalIp = getPreferredLocalIpForCurrentTransport(ip)
                if (!preferredLocalIp.isNullOrBlank()) {
                    nativeServiceSetPreferredLocalIp(preferredLocalIp)
                    Log.i(TAG, "🌐 Native UDP preferred local IP=$preferredLocalIp")
                } else {
                    nativeServiceSetPreferredLocalIp("")
                }

                // CRITICAL: Connect first
                if (!nativeServiceConnect(ip, port)) {
                    Log.e(TAG, "Failed to connect to server from $deviceName")
                    updateNotification(" ❌ Connect failed from 📱 $deviceName to $ip : $port")
                    isCurrentlyConnecting = false
                    stopStreaming(notifyUi = false, cancelReconnect = false)
                    handleConnectionFailure()
                    return@launch
                }
                Log.d(TAG, "Connected to server successfully from $deviceName")

                lastConnectionTime = System.currentTimeMillis()
                jitterBufferMs = bufferMs.coerceIn(
                    BufferModeConfig.MIN_CUSTOM_MS,
                    BufferModeConfig.MAX_CUSTOM_MS
                )
                nativeServiceSetJitterBuffer(jitterBufferMs)
                nativeServiceSetUltraLowProfile(
                    bufferModeKey == BufferModeConfig.Mode.ULTRA_LOW.key
                )
                nativeServiceSetHighBufferProfile(
                    bufferModeKey == BufferModeConfig.Mode.HIGH.key
                )
                nativeServiceSetMediumBufferProfile(
                    bufferModeKey == BufferModeConfig.Mode.MEDIUM.key
                )
                nativeServiceSetLowBufferProfile(
                    bufferModeKey == BufferModeConfig.Mode.LOW.key
                )
                nativeServiceSetCustomBufferProfile(
                    bufferModeKey == BufferModeConfig.Mode.CUSTOM.key,
                    customBufferMs
                )
                if (bufferModeKey == BufferModeConfig.Mode.CUSTOM.key) {
                    cachedCustomBufferDecision = null
                    lastCustomDecisionRefreshAtMs = 0L
                    currentCustomBufferDecision(forceRefresh = true)
                }
                updateNotification(" 🤝 Connected to endpoint - validating server… ")

                /*isConnected = true
                isConnectedState = true
                connectionAttempts = 0  // Reset on successful connection
                currentReconnectDelay = 0  */

                // Service must abort if handshake fails
                val ackResponse = nativeServiceSendHandshake(deviceManufacturer, deviceModel, deviceName)
                if (ackResponse == null) {
                    Log.e(TAG, "❌ Server handshake failed for $deviceName")

                    updateNotification("❌ Server not reachable from 📱 $deviceName ➡💻")

                    isConnected = false
                    isConnectedState = false
                    shouldStream = false
                    isCurrentlyConnecting = false

                    stopStreaming(notifyUi = false, cancelReconnect = false)
                    handleConnectionFailure()
                    return@launch
                }

                // Parse the live server identity from the acknowledged endpoint.
                // Manual IP/QR inputs may only contain an endpoint; the handshake
                // is the authoritative source for the PC name and capture backend.
                val ackPayload = ackResponse.trim().removePrefix("SERVER_ACK:")
                val ackParts = ackPayload.split(":", limit = 2)
                val ackName = ackParts.getOrNull(0)?.trim().orEmpty()
                val ackBackend = ackParts.getOrNull(1)?.trim().orEmpty()
                serverDisplayName = ackName.takeIf { it.isNotBlank() && !it.equals("Unknown", true) }
                    ?: serverDisplayName.takeIf { it.isNotBlank() && !it.equals("Unknown", true) }
                    ?: "PC at $ip"
                serverBackendName = ackBackend.takeIf { it.isNotBlank() && !it.equals("Unknown", true) }
                    ?: serverBackendName.takeIf { it.isNotBlank() && !it.equals("Unknown", true) }
                    ?: "Unknown"
                saveServiceTargetState()

                // Announce the selected streaming profile before audio starts.
                // The Windows server can shrink its capture queue and switch to
                // the Golden capture-paced sender before the first PCM frames.
                sendBufferProfileTelemetryNow(ip)

                updateNotification("\uD83D\uDEDC Connected to $serverDisplayName")

                Log.i(
                    TAG,
                    "🤝 SERVER_ACK confirmed for " +
                            "$deviceManufacturer:$deviceModel:$deviceName. Server: $serverDisplayName, Backend: $serverBackendName"
                )

                // Prepare AudioTrack MONO
                val optimalSamples = max(
                    nativeServiceGetOptimalBufferSize(sampleRate, 1) * 6,
                    2048
                )

                setupAudioTrack(optimalSamples)

                // Reset the Low-only playback catch-up counter for this stream session.
                lowLipSyncSkippedBlocks = 0L

                // CRITICAL: Start native streaming thread now
                if (!nativeServiceStartStreaming()) {
                    Log.e(TAG, "Failed to start native streaming for $deviceName")
                    updateNotification("❌ Failed to start streaming for 📱 $deviceName")
                    isConnected = false
                    isCurrentlyConnecting = false
                    stopStreaming(notifyUi = false, cancelReconnect = false)
                    handleConnectionFailure()
                    return@launch
                }
                Log.d(TAG, "Native streaming started successfully for $deviceName")

                requestPlaybackAudioFocus()

                sessionWasConnected = true
                saveLastSuccessfulNetworkSignature()
                getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("session_was_connected", true)
                    .apply()

                // The Service is only considered CONNECTED after the native
                // streaming thread is actually running. This keeps notification
                // actions correct during handshake/setup.
                isConnected = true
                isConnectedState = true
                isReconnectingState = false
                isCurrentlyConnecting = false
                connectionAttempts = 0
                currentReconnectDelay = 0

                val connectedPath = currentConnectionPathLabel()
                getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                    .edit().putString("last_connection_path", connectedPath).apply()

                sendBroadcast(
                    Intent(ACTION_UI_CONNECTED)
                        .setPackage(packageName)
                        .putExtra("IP", ip)
                        .putExtra("PORT", port)
                        .putExtra("SERVER_NAME", serverDisplayName)
                        .putExtra("SERVER_BACKEND", serverBackendName)
                        .putExtra("CONNECTION_PATH", connectedPath)
                )

                Log.i(TAG, "✅ UI CONNECTED state published for $ip:$port after native stream start")

                // Start the authoritative Windows-PC state synchronization only
                // after the native audio stream is genuinely active.
                startPcStateSync()

                thread(
                    name = "ClientHeartbeatThread",
                    isDaemon = true
                ) {
                    while (shouldStream && isConnected) {
                        try {
                            nativeServiceSendHeartbeat()
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ Heartbeat failed: ${e.message}")
                        }

                        try {
                            Thread.sleep(1000L)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }

                updateNotification("🎧 Streaming… ${bufferMs}ms • $serverDisplayName")

                val telemetryServerIp = currentServerAddress.substringBefore(":").trim()
                if (telemetryServerIp.isNotBlank()) {
                    networkQuality = assessNetworkQuality(telemetryServerIp)
                    if (bufferModeKey == BufferModeConfig.Mode.CUSTOM.key) currentCustomBufferDecision()
                    updateNotification("\uD83D\uDEDC Connected to $serverDisplayName")
                }

                // Invalidate/close any previous telemetry worker before creating
                // the one for this exact streaming session.
                stopTelemetryThread()
                val telemetryGenerationId = telemetryGeneration.get()

                telemetryThread = thread(name = "TelemetryThread", isDaemon = true) {
                    var telemetrySocket: DatagramSocket? = null
                    try {
                        if (telemetryServerIp.isBlank()) return@thread
                        if (telemetryGeneration.get() != telemetryGenerationId) return@thread

                        telemetrySocket = DatagramSocket()
                        telemetrySocketRef = telemetrySocket
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            try {
                                boundNetwork?.bindSocket(telemetrySocket)
                            } catch (e: Exception) {
                                Log.w(TAG, "⚠️ Could not bind telemetry socket to Service network: ${e.message}")
                            }
                        }

                        while (isConnected && telemetryGeneration.get() == telemetryGenerationId) {
                            val connType = detectTelemetryConnectionType()

                            val isWifiPath =
                                connType == "HOME_WIFI" ||
                                        connType == "HOTSPOT"

                            val rssi: Int
                            val quality: Int

                            if (isWifiPath) {
                                val wifiManager =
                                    applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                                val wifiInfo = wifiManager.connectionInfo
                                rssi = wifiInfo.rssi
                                quality = WifiManager.calculateSignalLevel(rssi, 100)
                            } else {
                                // RSSI is not a meaningful Wi-Fi metric for
                                // USB tethering or Ethernet.
                                rssi = 0
                                quality = 0
                            }
                            val stats = getConnectionStats()
                            val audioTrackHardwareUnderruns = getAudioTrackHardwareUnderruns()
                            val customDecision = if (bufferModeKey == BufferModeConfig.Mode.CUSTOM.key) {
                                currentCustomBufferDecision()
                            } else null
                            val customNativeRingDrops = if (bufferModeKey == BufferModeConfig.Mode.CUSTOM.key) {
                                try { nativeServiceGetCustomBufferRingDrops() } catch (_: Throwable) { -1L }
                            } else 0L
                            // Read volatile live settings on every report. Do not
                            // capture the bufferMs argument from connection startup:
                            // otherwise server telemetry stays stale after a live switch.
                            val activeModeKey = bufferModeKey
                            val activeBufferMs = jitterBufferMs
                            val streamingProfile = when (activeModeKey) {
                                BufferModeConfig.Mode.ULTRA_LOW.key -> "golden_ultra_low_v1"
                                BufferModeConfig.Mode.HIGH.key -> "high_continuity_v1"
                                BufferModeConfig.Mode.MEDIUM.key -> "medium_balanced_v1"
                                BufferModeConfig.Mode.LOW.key -> "low_lipsync_v1"
                                BufferModeConfig.Mode.CUSTOM.key -> CustomBufferEngine.PROFILE
                                else -> "adaptive_v2"
                            }
                            val syncScore = (100
                                    - (stats.packetsDropped * 4)
                                    - (stats.bufferUnderruns * 3)
                                    - if (activeBufferMs >= 500) 25 else 0
                                    ).coerceIn(0, 100)

                            val obj = JSONObject()
                            obj.put("type", "TELEMETRY")
                            obj.put("quality", quality)
                            obj.put("rtt", networkQuality.latency)
                            obj.put("jitter", networkQuality.jitter)
                            obj.put("conn", connType)
                            obj.put("rssi", rssi)
                            obj.put("mode", activeModeKey.uppercase(Locale.ROOT))
                            obj.put("buffer_mode", activeModeKey)
                            obj.put("buffer_ms", activeBufferMs)
                            obj.put("streaming_profile", streamingProfile)
                            obj.put("custom_buffer_ms", if (activeModeKey == BufferModeConfig.Mode.CUSTOM.key) customBufferMs else 0)
                            obj.put("custom_effective_ms", customDecision?.effectiveTargetMs ?: 0)
                            obj.put("custom_network_margin_ms", customDecision?.networkMarginMs ?: 0)
                            obj.put("custom_device_penalty_ms", customDecision?.devicePenaltyMs ?: 0)
                            obj.put("custom_device_cpu_percent", customProcessCpuPercent)
                            obj.put("custom_low_watermark_ms", customDecision?.lowWatermarkMs ?: 0)
                            obj.put("custom_high_watermark_ms", customDecision?.highWatermarkMs ?: 0)
                            obj.put("custom_stale_blocks_skipped", customStaleBlocksSkipped)
                            obj.put("custom_native_ring_drops", customNativeRingDrops)
                            obj.put("low_lipsync_skipped_blocks", lowLipSyncSkippedBlocks)
                            obj.put("packets_received", stats.packetsReceived)
                            obj.put("packets_dropped", stats.packetsDropped)
                            obj.put("buffer_underruns", stats.bufferUnderruns)
                            obj.put("audio_track_underruns", audioTrackHardwareUnderruns)
                            obj.put("buffer_level", stats.bufferLevel)
                            obj.put("sync_score", syncScore)

                            val data = obj.toString().toByteArray(Charsets.UTF_8)
                            val packet = DatagramPacket(data, data.size, InetAddress.getByName(telemetryServerIp), 50009)
                            telemetrySocket.send(packet)

                            Thread.sleep(if (bufferModeKey == BufferModeConfig.Mode.CUSTOM.key) 10_000L else 30_000L)
                        }
                    } catch (e: java.lang.InterruptedException) {
                        // Expected when stopTelemetryThread() interrupts a
                        // worker during pause/disconnect/reconnect. Do not
                        // report normal lifecycle cancellation as an error.
                        Thread.currentThread().interrupt()
                        Log.d(TAG, "🛑 Telemetry worker interrupted — stopping cleanly")
                    } catch (e: java.io.IOException) {
                        if (!isConnected || e.message?.contains("ENETUNREACH", ignoreCase = true) == true) {
                            Log.w(TAG, "🛜 Telemetry socket ended during network handoff: ${e.message}")
                        } else {
                            Log.e(TAG, "Telemetry thread error", e)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Telemetry thread error", e)
                    } finally {
                        try { telemetrySocket?.close() } catch (_: Exception) {}
                        if (telemetryGeneration.get() == telemetryGenerationId) {
                            telemetrySocketRef = null
                            telemetryThread = null
                        }
                    }
                }

                startPerformanceMonitoring()
                // Start the main streaming loop / Reader loop with warm-up
                val forceDisconnected = nativeStreamLoop(optimalSamples)
                if (forceDisconnected) {
                    // The server forced the disconnect; this is not the same
                    // state as a user pause. Present an explicit ReConnect action.
                    reconnectAvailable = true
                    resumeAvailable = false
                    isUserPaused = false
                    isUserDisconnected = true
                    isPausedState = false
                    isReconnectingState = false
                    sessionWasConnected = false

                    getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("user_paused", false)
                        .putBoolean("user_disconnected", true)
                        .putBoolean("notification_reconnect", true)
                        .putBoolean("session_was_connected", false)
                        .apply()

                    updateNotification("⚡ Disconnected by Server • Tap ReConnect")
                    sendUiCommand(
                        ACTION_UI_RESET,
                        forceDisconnected = true,
                        userRequested = true
                    )
                }

                stopStreaming(
                    forceDisconnected = forceDisconnected,
                    notifyUi = false,
                    cancelReconnect = false
                )

            } catch (e: CancellationException) {
                Log.d(TAG, "🛑 Streaming coroutine cancelled cleanly")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Exception | error : in streaming for $deviceName: ${e.message}", e)
                updateNotification(" ❌ Streaming error on 📱 $deviceName: ${e.message}")
                stopStreaming(notifyUi = false, cancelReconnect = false)
                handleConnectionFailure()
            } finally {
                synchronized(connectionLock){
                    isCurrentlyConnecting = false
                }
            }
        }
    }


    private fun setupAudioTrack(optimalSamples: Int) {

        audioTrack?.release()

        // High uses a real 1500ms continuity queue. Medium uses 250ms; Low uses
        // a bounded 100ms low-latency queue. Golden/Custom construction is unchanged.
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val isHighBuffer = bufferModeKey == BufferModeConfig.Mode.HIGH.key
        val isMediumBuffer = bufferModeKey == BufferModeConfig.Mode.MEDIUM.key
        val isLowBuffer = bufferModeKey == BufferModeConfig.Mode.LOW.key
        val isCustomBuffer = bufferModeKey == BufferModeConfig.Mode.CUSTOM.key
        val requestedBufferedMs = when {
            isHighBuffer -> jitterBufferMs.coerceAtLeast(1500)
            isMediumBuffer -> 250
            isLowBuffer -> 100
            else -> 0
        }
        val requestedBufferedBytes = (sampleRate * requestedBufferedMs / 1000) * bytesPerMonoSample
        val finalBufferSize = if (isHighBuffer || isMediumBuffer || isLowBuffer) max(minBytes, requestedBufferedBytes) else minBytes

        val blockSamples = 480 // 10ms at 48kHz
        val blockBytes = blockSamples * bytesPerMonoSample
        val alignedBufferSize = ((finalBufferSize + blockBytes - 1) / blockBytes) * blockBytes
        currentAudioTrackBufferSize = alignedBufferSize


        // 🔊 LOG AUDIOTRACK SETUP

        Log.d(TAG, "🎵 LOW LATENCY CLIENT AUDIOTRACK SETUP 🎧 :")

        Log.d(TAG, " - Channel Config: MONO (${channelConfig})")
        Log.d(TAG, " - Audio Format: 16-bit PCM (${audioFormat})")
        Log.d(TAG, " - Min Buffer: $minBytes bytes")
        Log.d(TAG, " - Final Buffer (requested): $finalBufferSize bytes")
        Log.d(TAG, " - Final Buffer (aligned):   $alignedBufferSize bytes")
        Log.d(TAG, " - Calculated Latency: ${(alignedBufferSize/2 * 1000)/sampleRate}ms")
        Log.d(TAG, "   Device: $deviceModel")


        val attributesBuilder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        // Golden and Low retain low-latency attributes. Medium/High use normal
        // media tracks so Android can honour their larger PCM queues.
        if (!isHighBuffer && !isMediumBuffer) attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributesBuilder.build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)  // MONO
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(alignedBufferSize)
            .setPerformanceMode(
                if (isHighBuffer || isMediumBuffer) AudioTrack.PERFORMANCE_MODE_NONE
                else AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
            )
            .build()

        // Golden retains its original frame policy. Low/Medium/High receive their
        // profile-specific queue sizes; Custom keeps the adaptive/HAL policy.
        applyAudioTrackBufferPolicy()

        // Low and Medium start after a small real-PCM pre-roll. This avoids the
        // launch-time underrun burst while keeping Low's added startup delay bounded.
        if (!isMediumBuffer && !isLowBuffer && !isCustomBuffer) audioTrack?.play()

        // MainActivity's volume slider controls Android STREAM_MUSIC globally.
        // AudioTrack stays at unity gain except for explicit mute and focus ducking.
        audioTrack?.setVolume(if (audioDucked) audioDuckGain else 1.0f)

        val activeTrackProfile = when {
            isHighBuffer -> "high_continuity_v1"
            isMediumBuffer -> "medium_balanced_v1"
            isLowBuffer -> "low_lipsync_v1"
            isCustomBuffer -> CustomBufferEngine.PROFILE
            bufferModeKey == BufferModeConfig.Mode.ULTRA_LOW.key -> "golden_ultra_low_v1"
            else -> "adaptive_v2"
        }
        Log.i(TAG, "AudioTrack ready: profile=$activeTrackProfile, allocated≈${alignedBufferSize / bytesPerMonoSample * 1000L / sampleRate}ms, sampleRate=$sampleRate Hz mono PCM16")
    }

    private fun requestPlaybackAudioFocus(): Boolean {
        val manager = audioManager ?: (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
        ?: return false

        audioManager = manager

        return try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                    .also { audioFocusRequest = it }

                manager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }

            audioFocusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!audioFocusHeld) {
                // Do not stop draining PCM or pause AudioTrack merely because a
                // ringtone/notification currently owns focus. Lower playback gain
                // while Android resolves the competing sound, then restore it on GAIN.
                audioDucked = true
                try { audioTrack?.setVolume(audioDuckGain) } catch (_: Throwable) {}
                Log.w(TAG, "🔉 Audio focus not granted — stream continues ducked to ${(audioDuckGain * 100).toInt()}%")
            } else {
                Log.d(TAG, "🎧 Playback audio focus granted")
            }
            true
        } catch (e: Throwable) {
            // Audio focus is a coexistence feature, not a reason to tear down the
            // network stream. Keep streaming and let AudioTrack behave normally.
            Log.w(TAG, "⚠️ Audio focus request failed: ${e.message}")
            true
        }
    }

    private fun abandonPlaybackAudioFocus() {
        val manager = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(audioFocusChangeListener)
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Audio focus abandon skipped: ${e.message}")
        } finally {
            audioFocusHeld = false
            audioDucked = false
        }
    }

    private fun drainNativeAudioBacklog(maxBlocks: Int = 128): Int {
        val scratch = ShortArray(playbackBlockSamples)
        var drainedSamples = 0
        repeat(maxBlocks.coerceAtLeast(1)) {
            val n = try {
                nativeServiceGetAudioData(scratch, scratch.size)
            } catch (e: Throwable) {
                Log.w(TAG, "⚠️ Native backlog drain failed: ${e.message}")
                return@repeat
            }
            if (n <= 0) return@repeat
            drainedSamples += n
        }
        return drainedSamples
    }

    private fun trimNativeAudioBacklogToMs(targetMs: Int, maxBlocks: Int = 256): Int {
        val targetSamples = (targetMs.coerceAtLeast(20) * sampleRate) / 1000
        val scratch = ShortArray(playbackBlockSamples)
        var drainedSamples = 0
        for (blockIndex in 0 until maxBlocks.coerceAtLeast(1)) {
            val stats = IntArray(4)
            if (!nativeServiceGetBufferStats(stats) || stats[3] <= targetSamples) break
            val n = nativeServiceGetAudioData(scratch, scratch.size)
            if (n <= 0) break
            drainedSamples += n
        }
        return drainedSamples
    }

    private fun isSelectedTransportReady(): Boolean {
        if (findCurrentModeNetwork() != null) return true
        return if (isWiredMode) {
            hasUsableLocalUsbTetherInterface()
        } else {
            findActiveMobileHotspotLocalIp() != null || hasUsableLocalWirelessInterface()
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

    private fun isLikelyHotspotInterface(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("ap0") || n.contains("softap") ||
                n.contains("swlan") || n.contains("wlan1") ||
                n.contains("wlan2") || n.contains("tether") ||
                n.contains("hotspot")
    }

    private fun isClearlyWirelessInterface(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("wlan") || n.contains("wifi") || n.contains("wi-fi") ||
                n.contains("wireless") || n.contains("802.11") || n.startsWith("wl")
    }

    private fun isLikelyUsbInterface(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        // rmnet*/ccmni* are mobile-data interfaces, not the PC-facing tether.
        // Explicitly exclude Wi-Fi, even if the physical Wi-Fi adapter is USB.
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

        // TRANSPORT_ETHERNET is direct OS evidence even if this OEM labels its
        // USB tether interface eth1/enx…/a vendor name instead of rndis0.
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return true

        // Some OEM builds expose tethering as CELLULAR/WIFI capabilities. In
        // that case require a USB/tether-like interface; never accept rmnet data.
        return isLikelyUsbInterface(n) && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    }

    private fun findActiveMobileHotspotLocalIp(): String? {
        return try {
            val detection = MobileHotspotDatabase.detect(
                applicationContext,
                MobileHotspotDatabase.WirelessPath.MOBILE_HOTSPOT
            )
            detection.localIpv4?.takeIf { detection.isReady }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Hosted Mobile Hotspot interface detection failed: ${e.message}")
            null
        }
    }

    private fun currentWiredTransportSignature(): String {
        val wired = findActiveUsbTransportDetails() ?: return ""
        return "wired|${wired.first}|${wired.second}"
    }

    private fun currentWirelessTransportSignature(): String {
        return try {
            val hotspot = MobileHotspotDatabase.detect(
                applicationContext,
                MobileHotspotDatabase.WirelessPath.MOBILE_HOTSPOT
            )
            val client = MobileHotspotDatabase.detect(
                applicationContext,
                MobileHotspotDatabase.WirelessPath.HOME_WIFI
            )
            val parts = mutableListOf<String>()
            if (hotspot.isReady) {
                parts += "softap:${hotspot.interfaceName.orEmpty()}:${hotspot.localIpv4.orEmpty()}:${hotspot.prefixLength ?: 0}"
            }
            if (client.isReady) {
                val path = if (client.gatewayIpv4 == "192.168.137.1" ||
                    client.localIpv4.orEmpty().startsWith("192.168.137.")) "pc_hotspot" else "wifi_client"
                parts += "$path:${client.interfaceName.orEmpty()}:${client.localIpv4.orEmpty()}:${client.gatewayIpv4.orEmpty()}"
            }

            // SoftAP/client route APIs differ across OEM Android builds. If neither
            // resolver exposes a gateway, still use a live Wi-Fi-like local IPv4
            // interface as a *transport-presence* signal. Discovery remains the
            // authority for whether the PC server can actually be reached.
            if (parts.isEmpty()) {
                val fallback = Collections.list(NetworkInterface.getNetworkInterfaces())
                    .asSequence()
                    .filter { iface ->
                        val name = iface.name.lowercase(Locale.US)
                        val up = try { iface.isUp } catch (_: Exception) { false }
                        val wifiLike = isClearlyWirelessInterface(name) || isLikelyHotspotInterface(name)
                        up && wifiLike
                    }
                    .mapNotNull { iface ->
                        val ip = Collections.list(iface.inetAddresses)
                            .filterIsInstance<Inet4Address>()
                            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                            ?.hostAddress ?: return@mapNotNull null
                        "iface:${iface.name}:$ip"
                    }
                    .toList()
                parts += fallback
            }
            parts.distinct().sorted().joinToString("|")
        } catch (e: Exception) {
            Log.d(TAG, "Wireless transport signature unavailable: ${e.message}")
            ""
        }
    }

    /**
     * Detects system-driven Wireless <-> Wired changes independently of the
     * user-selected mode. A newly appearing/changed alternate transport wins;
     * if the selected family disappears, a ready alternate is used as fallback.
     * This runs in the foreground Service and therefore continues while either
     * Activity is backgrounded. It never overrides Pause/Stop/Disconnect.
     */
    private fun monitorTransportFamiliesAndAutoSelect() {
        val wiredSignature = currentWiredTransportSignature()
        val wirelessSignature = currentWirelessTransportSignature()

        if (!transportFamiliesInitialized) {
            lastObservedWiredFamilySignature = wiredSignature
            lastObservedWirelessFamilySignature = wirelessSignature
            transportFamiliesInitialized = true
            Log.i(
                TAG,
                "🧭 Transport monitor baseline: wired=${wiredSignature.ifBlank { "unavailable" }}, " +
                    "wireless=${wirelessSignature.ifBlank { "unavailable" }}"
            )
            // If restoring a recoverable session whose saved transport has vanished,
            // select the only available family instead of waiting for a user mode tap.
            val recoverable = isConnected || isCurrentlyConnecting || shouldStream ||
                    sessionWasConnected || connectionWaitingForTransport || isReconnectingState
            if (recoverable && autoReconnectEnabled && !isUserPaused && !isUserDisconnected &&
                !hasStopped && !explicitTransportSelectionWaitingForReady
            ) {
                if (isWiredMode && wiredSignature.isBlank() && wirelessSignature.isNotBlank()) {
                    switchToDetectedTransport(false, "saved Wired transport unavailable; Wireless is ready")
                } else if (!isWiredMode && wirelessSignature.isBlank() && wiredSignature.isNotBlank()) {
                    switchToDetectedTransport(true, "saved Wireless transport unavailable; Wired is ready")
                }
            }
            return
        }

        val wiredChanged = wiredSignature != lastObservedWiredFamilySignature
        val wirelessChanged = wirelessSignature != lastObservedWirelessFamilySignature
        lastObservedWiredFamilySignature = wiredSignature
        lastObservedWirelessFamilySignature = wirelessSignature

        if (!autoReconnectEnabled || isUserPaused || isUserDisconnected || hasStopped) return
        val activeSession = isConnected || isCurrentlyConnecting || shouldStream ||
                sessionWasConnected || connectionWaitingForTransport || isReconnectingState
        if (!activeSession) return

        // Re-evaluate availability even on unchanged snapshots: a session can
        // become recoverable after the watcher baseline was first captured.
        // However, an explicit in-app mode choice waits for its requested
        // interface to become ready while the user visits Android Settings.
        if (!explicitTransportSelectionWaitingForReady) {
            if (isWiredMode && wiredSignature.isBlank() && wirelessSignature.isNotBlank()) {
                switchToDetectedTransport(false, "selected Wired path unavailable; Wireless is ready")
                return
            }
            if (!isWiredMode && wirelessSignature.isBlank() && wiredSignature.isNotBlank()) {
                switchToDetectedTransport(true, "selected Wireless path unavailable; Wired is ready")
                return
            }
        }

        if (!wiredChanged && !wirelessChanged) return

        Log.i(
            TAG,
            "🧭 Transport families changed: wired=${wiredSignature.ifBlank { "unavailable" }}, " +
                "wireless=${wirelessSignature.ifBlank { "unavailable" }}"
        )

        val targetWiredMode: Boolean? = when {
            // A newly available Wired route wins if the app is currently Wireless.
            !isWiredMode && wiredChanged && wiredSignature.isNotBlank() -> true
            // If the selected Wired path is still waiting, allow an actual new
            // Wireless event to select Wireless, but don't treat the unchanged
            // old Wi-Fi route as a reason to undo the user's explicit choice.
            isWiredMode && wirelessChanged && wirelessSignature.isNotBlank() &&
                    (wiredSignature.isBlank() || !wiredChanged) -> false
            else -> null
        }
        if (targetWiredMode != null && targetWiredMode != isWiredMode) {
            switchToDetectedTransport(
                targetWiredMode,
                if (targetWiredMode) "USB tether/RNDIS became available or changed"
                else "Wi-Fi/SoftAP became available or changed"
            )
        }
    }

    private fun switchToDetectedTransport(targetWiredMode: Boolean, reason: String) {
        if (targetWiredMode == isWiredMode) return
        if (!autoReconnectEnabled || isUserPaused || isUserDisconnected || hasStopped) return

        val now = SystemClock.elapsedRealtime()
        // OEMs can briefly create multiple interfaces while tethering switches.
        // A short debounce prevents mode flapping; a later stable interface change
        // is still observed by the continuously running monitor.
        val currentFamilyReady = if (isWiredMode) {
            currentWiredTransportSignature().isNotBlank()
        } else {
            currentWirelessTransportSignature().isNotBlank()
        }
        val targetFamilyReady = if (targetWiredMode) {
            currentWiredTransportSignature().isNotBlank()
        } else {
            currentWirelessTransportSignature().isNotBlank()
        }
        if (lastAutomaticTransportSwitchAtMs > 0L &&
            now - lastAutomaticTransportSwitchAtMs < 900L &&
            currentFamilyReady && targetFamilyReady
        ) {
            Log.d(TAG, "🛡️ Ignoring transient transport-mode flip during debounce")
            return
        }
        lastAutomaticTransportSwitchAtMs = now

        isWiredMode = targetWiredMode
        explicitTransportSelectionWaitingForReady = false
        val modeValue = if (targetWiredMode) "wired" else "wireless"
        getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE).edit()
            .putString("streaming_mode", modeValue)
            .putBoolean("auto_reconnect", autoReconnectEnabled)
            .putBoolean("auto_connect", autoReconnectEnabled)
            .apply()

        if (autoConnectMode) {
            targetIp = ""
            targetPort = 0
            currentServerAddress = ""
        }

        Log.i(TAG, "🔀 Auto-selected ${modeValue.uppercase(Locale.US)} transport: $reason")
        sendBroadcast(
            Intent(ACTION_UI_TRANSPORT_MODE_CHANGED)
                .setPackage(packageName)
                .putExtra("IS_WIRED", targetWiredMode)
                .putExtra("streaming_mode", modeValue)
                .putExtra("reason", reason)
        )
        publishConnectionPathChanged()
        sendBroadcast(
            Intent("com.example.wifiaudiospeaker.ACTION_UI_RECONNECTING")
                .setPackage(packageName)
                .putExtra("delay_seconds", 0L)
                .putExtra("attempt", 1)
                .putExtra("max", -1)
        )
        updateNotification("🔄 Auto-switched to ${if (targetWiredMode) "USB tether" else "Wireless"} — reconnecting…")

        // Reuse the existing serialized route-reconnect path: stop the stale
        // native UDP socket, bind/resolve the new route, and discover afresh.
        // Cancel work that may still be targeting the old transport. Job-identity
        // guards in each finally block prevent old cancelled jobs clearing newer ones.
        initialConnectJob?.cancel()
        reconnectJob?.cancel()
        networkReconnectJob?.cancel()
        connectionWaitingForTransport = true
        isReconnectingState = true
        scheduleNetworkRouteReconnect()
    }

    private fun currentSelectedTransportSignature(): String =
        if (isWiredMode) currentWiredTransportSignature() else currentWirelessTransportSignature()

    private fun handleDetectedTransportLoss() {
        if (isUserPaused || isUserDisconnected || hasStopped) return
        if (!autoReconnectEnabled) {
            connectionWaitingForTransport = false
            isReconnectingState = false
            stopStreaming(notifyUi = false, cancelReconnect = false)
            sendUiCommand(ACTION_UI_RESET, forceDisconnected = false, userRequested = false)
            updateNotification("🌐 Transport lost • Tap ReConnect")
            return
        }

        connectionWaitingForTransport = true
        isReconnectingState = true
        sendBroadcast(
            Intent("com.example.wifiaudiospeaker.ACTION_UI_RECONNECTING")
                .setPackage(packageName)
                .putExtra("delay_seconds", 0L)
                .putExtra("attempt", 1)
                .putExtra("max", -1)
        )
        updateNotification("🔄 Transport lost — waiting for the replacement path…")
        stopStreaming(notifyUi = false, cancelReconnect = false)
    }

    private fun findCurrentModeNetwork(): Network? {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // If the phone itself is the SoftAP, prefer the Network object whose
            // interface/address belongs to that SoftAP. Do not accidentally bind
            // the process to cellular or an unrelated Wi-Fi network.
            val hotspotIp = if (!isWiredMode) findActiveMobileHotspotLocalIp() else null

            val networks = cm.allNetworks.toList()
            val ordered = if (hotspotIp != null) {
                networks.sortedByDescending { network ->
                    val lp = cm.getLinkProperties(network)
                    lp?.linkAddresses?.any { it.address.hostAddress == hotspotIp } == true ||
                            isLikelyHotspotInterface(lp?.interfaceName.orEmpty())
                }
            } else {
                networks
            }

            ordered.firstOrNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
                val lp = cm.getLinkProperties(network) ?: return@firstOrNull false
                val iface = lp.interfaceName?.lowercase(Locale.US).orEmpty()
                val hasIpv4 = lp.linkAddresses.any { it.address is Inet4Address && !it.address.isLoopbackAddress }
                if (!hasIpv4) return@firstOrNull false

                if (isWiredMode) {
                    isWiredNetworkCandidate(iface, caps)
                } else {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            isLikelyHotspotInterface(iface)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Transport readiness check failed: ${e.message}")
            null
        }
    }



    private fun networkMatchesCurrentMode(network: Network): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                    as ConnectivityManager

            val capabilities = cm.getNetworkCapabilities(network)
                ?: return false

            val linkProperties = cm.getLinkProperties(network)
                ?: return false

            val interfaceName = linkProperties.interfaceName
                .orEmpty()
                .lowercase(Locale.US)

            val hasUsableIpv4 = linkProperties.linkAddresses.any { linkAddress ->
                val address = linkAddress.address
                address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress
            }

            if (!hasUsableIpv4) return false

            if (isWiredMode) {
                isWiredNetworkCandidate(interfaceName, capabilities)
            } else {
                // Wireless mode accepts Wi-Fi networks only.
                // Cellular and VPN networks are not valid Wi-Fi transports.
                capabilities.hasTransport(
                    NetworkCapabilities.TRANSPORT_WIFI
                )
            }
        } catch (e: Exception) {
            Log.w(
                TAG,
                "⚠️ Network transport matching failed: ${e.message}"
            )
            false
        }
    }

    private fun transportUnavailableMessage(): String {
        if (isWiredMode) {
            return "Please enable USB tethering and connect the PC to start streaming."
        }
        val hotspotReady = findActiveMobileHotspotLocalIp() != null
        val wifiEnabled = try {
            (getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
        } catch (_: Exception) { false }
        return when {
            hotspotReady -> "Mobile Hotspot is active — waiting for its PC-facing route to become usable."
            wifiEnabled -> "Wi-Fi is enabled, but no usable Home Wi-Fi / PC Hotspot route is ready. Connect to a Wi-Fi network or enable Mobile Hotspot."
            else -> "Please turn on Wi-Fi or Mobile Hotspot or enable USB tethering to start streaming."
        }
    }

    private fun publishTransportUnavailable() {
        connectionWaitingForTransport = autoConnectMode && autoReconnectEnabled && !isUserPaused && !isUserDisconnected

        // This is a WAITING state, not an active reconnect attempt. Keep the
        // transport selector unlockable so the user can switch Wi-Fi <-> USB
        // tethering while the Service remains alive.
        isReconnectingState = false

        updateNotification("⏳ ${transportUnavailableMessage()}")
        sendBroadcast(
            Intent(ACTION_UI_TRANSPORT_UNAVAILABLE)
                .setPackage(packageName)
                .putExtra("wired", isWiredMode)
                .putExtra("message", transportUnavailableMessage())
        )
    }

    // ============================================================
    // WINDOWS PC STATE SYNCHRONIZATION
    // ============================================================
    // This control-plane path is intentionally separate from the native
    // audio stream. It asks the Windows server for its actual Media Session
    // playback state and master-mute state, then refreshes the notification.

    private fun startPcStateSync() {
        stopPcStateSync()

        pcStateSyncJob = serviceIoScope.launch {
            // Get an immediate authoritative state, then keep the notification
            // synchronized while the stream remains active.
            requestPcStateFromServer()

            while (isActive && isConnected && shouldStream) {
                delay(1200L)
                requestPcStateFromServer()
            }
        }
    }

    private fun stopPcStateSync() {
        pcStateSyncJob?.cancel()
        pcStateSyncJob = null
    }

    private fun requestPcStateAfterControl(control: String, controlJob: Job?) {
        serviceIoScope.launch {
            // Ensure the UDP toggle packet has actually been sent before
            // asking the Windows server for the post-toggle state.
            controlJob?.join()

            // Windows media/mute toggles are asynchronous enough that an
            // immediate read can still return the pre-toggle state. Use two
            // short reads, then let the normal 1.2s sync loop take over.
            delay(100L)
            requestPcStateFromServer()
            delay(250L)
            requestPcStateFromServer()
            Log.d(TAG, "🖥️ Requested authoritative PC state after $control control")
        }
    }

    private fun publishPcStateToUi() {
        sendBroadcast(
            Intent(ACTION_UI_PC_STATE)
                .setPackage(packageName)
                .putExtra("media_known", pcMediaStateKnown)
                .putExtra("media_playing", pcMediaPlaying)
                .putExtra("audio_known", pcAudioStateKnown)
                .putExtra("audio_muted", pcAudioMuted)
        )
    }

    private fun applyPcStateResponse(response: String): Boolean {
        if (!response.startsWith(PC_STATE_PREFIX)) return false

        return try {
            val jsonText = response.removePrefix(PC_STATE_PREFIX).trim()
            val obj = JSONObject(jsonText)

            val mediaState = obj.optString("media", "UNKNOWN").uppercase(Locale.US)
            val hasMedia = mediaState == "PLAYING" ||
                    mediaState == "PAUSED" ||
                    mediaState == "STOPPED"
            val hasMute = obj.has("muted") && !obj.isNull("muted")
            val mutedValue = if (hasMute) obj.optBoolean("muted") else false

            if (hasMedia) {
                pcMediaPlaying = mediaState == "PLAYING"
                pcMediaStateKnown = true
            }
            if (hasMute) {
                pcAudioMuted = mutedValue
                pcAudioStateKnown = true
            }

            publishPcStateToUi()
            val loggedMedia = if (hasMedia) mediaState else "UNKNOWN"
            val loggedMute = if (hasMute) mutedValue.toString() else "UNKNOWN"
            val stateKey = "$loggedMedia|$loggedMute"
            val logNow = SystemClock.elapsedRealtime()
            if (stateKey != lastLoggedPcStateKey || logNow - lastPcStateLogAtMs >= 30_000L) {
                lastLoggedPcStateKey = stateKey
                lastPcStateLogAtMs = logNow
                Log.i(TAG, "🖥️ Authoritative PC state: media=$loggedMedia, muted=$loggedMute")
            }
            hasMedia || hasMute
        } catch (e: Exception) {
            Log.d(TAG, "🖥️ Invalid PC-state payload: ${e.message}")
            false
        }
    }

    private suspend fun requestPcStateFromServer() {
        if (!isConnected || !shouldStream || targetIp.isBlank() || targetPort <= 0) return

        pcStateRequestMutex.withLock {
            if (!isConnected || !shouldStream || targetIp.isBlank() || targetPort <= 0) return

            val ip = targetIp
            val port = targetPort
            val network = boundNetwork

            try {
                withContext(Dispatchers.IO) {
                    DatagramSocket().use { socket ->
                        socket.soTimeout = 900
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            network?.let {
                                try { it.bindSocket(socket) }
                                catch (e: Throwable) {
                                    Log.w(TAG, "⚠️ Could not bind PC-state socket to Service network: ${e.message}")
                                }
                            }
                        }

                        val requestBytes = PC_STATE_REQUEST.toByteArray(Charsets.UTF_8)
                        val address = InetAddress.getByName(ip)
                        socket.send(DatagramPacket(requestBytes, requestBytes.size, address, port))

                        val responseBuffer = ByteArray(1024)
                        val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                        socket.receive(responsePacket)
                        val response = String(
                            responsePacket.data,
                            0,
                            responsePacket.length,
                            Charsets.UTF_8
                        ).trim()
                        applyPcStateResponse(response)
                    }
                }
            } catch (_: SocketTimeoutException) {
                Log.d(TAG, "🖥️ PC-state poll timed out; keeping last known state")
            } catch (e: Exception) {
                Log.d(TAG, "🖥️ PC-state poll failed: ${e.message}")
            }
        }
    }

    private fun sendPcControlToServer(command: String): Job? {
        if (!isConnected || targetIp.isBlank() || targetPort <= 0) {
            Log.w(TAG, "🖥️ PC control ignored — no active server session")
            return null
        }

        val ip = targetIp
        val port = targetPort
        val network = boundNetwork

        return serviceIoScope.launch {
            pcStateRequestMutex.withLock {
                try {
                    DatagramSocket().use { socket ->
                        socket.soTimeout = 900
                        network?.let {
                            try { it.bindSocket(socket) }
                            catch (e: Throwable) {
                                Log.w(TAG, "⚠️ Could not bind PC-control socket: ${e.message}")
                            }
                        }

                        val bytes = command.toByteArray(Charsets.UTF_8)
                        val address = InetAddress.getByName(ip)
                        socket.send(DatagramPacket(bytes, bytes.size, address, port))

                        // Keep the SAME ephemeral socket alive so the server's
                        // PC_STATE reply can return to this request socket.
                        val responseBuffer = ByteArray(1024)
                        val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                        try {
                            socket.receive(responsePacket)
                            val response = String(
                                responsePacket.data,
                                0,
                                responsePacket.length,
                                Charsets.UTF_8
                            ).trim()
                            if (!applyPcStateResponse(response)) {
                                Log.d(TAG, "🖥️ PC control reply was not a PC_STATE payload")
                            }
                            else{
                                Log.i(TAG, "🖥️ PC control sent: $command -> $ip:$port")
                            }
                        } catch (_: SocketTimeoutException) {
                            Log.d(TAG, "🖥️ No immediate PC-state reply after $command; periodic sync remains active")
                        }
                    }
                    Log.i(TAG, "🖥️ PC control sent: $command -> $ip:$port")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ PC control send failed: ${e.message}")
                }
            }
        }
    }

    private fun formatIpv4Address(address: Int): String {
        val a = address and 0xFF
        val b = (address ushr 8) and 0xFF
        val c = (address ushr 16) and 0xFF
        val d = (address ushr 24) and 0xFF

        return "$a.$b.$c.$d"
    }

    private fun detectTelemetryConnectionType(): String {
        return try {
            val cm =
                applicationContext.getSystemService(
                    Context.CONNECTIVITY_SERVICE
                ) as ConnectivityManager

            // The Service-owned bound network is authoritative. Android's
            // default/active network can remain cellular or Wi-Fi while the
            // audio socket is bound to USB tethering.
            val activeNetwork = boundNetwork ?: cm.activeNetwork

            val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val linkProperties = activeNetwork?.let { cm.getLinkProperties(it) }

            val interfaceName =
                linkProperties
                    ?.interfaceName
                    ?.lowercase(Locale.US)
                    .orEmpty()

            // Wired mode is the strongest signal.
            // ------------------------------------------------------------
            // WIRED MODE:
            // The user-selected mode is authoritative, but we still verify
            // that Android exposes a wired/USB-like network path.
            //
            // USB tethering can appear as:
            //   rndis0 / rndis*
            //   usb0 / usb*
            //   ecm*
            //   ncm*
            //   Ethernet transport
            //
            // Do NOT classify a normal Wi-Fi connection as USB just because
            // the user previously selected Wired mode.
            // ------------------------------------------------------------
            val looksLikeUsbInterface =
                isLikelyUsbInterface(interfaceName) ||
                        interfaceName.contains("eth") ||
                        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true

            if (isWiredMode && looksLikeUsbInterface) {
                return "USB_TETHERING"
            }

            if (capabilities?.hasTransport(
                    NetworkCapabilities.TRANSPORT_WIFI) == true) {

                val wifiManager =
                    applicationContext.getSystemService(
                        Context.WIFI_SERVICE
                    ) as WifiManager

                val wifiInfo =
                    wifiManager.connectionInfo

                val ip = linkProperties?.linkAddresses
                    ?.mapNotNull { it.address as? Inet4Address }
                    ?.firstOrNull()?.hostAddress
                    ?: formatIpv4Address(wifiInfo.ipAddress)

                val hotspotRanges = listOf(
                    "192.168.43.", "192.168.137.", "192.168.234.",
                    "192.168.44.", "192.168.45.", "192.168.49.",
                    "192.168.6.", "10.0.0.", "10.185.37.",
                    "172.20.10."
                )

                if (
                    hotspotRanges.any {
                        ip.startsWith(it)
                    }
                ) {
                    return "HOTSPOT"
                }

                return "HOME_WIFI"
            }

            if (
                capabilities?.hasTransport(
                    NetworkCapabilities.TRANSPORT_ETHERNET
                ) == true
            ) {
                return "ETHERNET"
            }

            if (
                capabilities?.hasTransport(
                    NetworkCapabilities.TRANSPORT_CELLULAR
                ) == true
            ) {
                return "MOBILE_DATA"
            }

            "UNKNOWN"

        } catch (e: Exception) {
            Log.w(
                TAG,
                "⚠️ Connection type detection failed: ${e.message}"
            )
            "UNKNOWN"
        }
    }

    private fun applyAudioTrackBufferPolicy() {
        val track = audioTrack ?: return
        val minFrames = (currentAudioTrackBufferSize / bytesPerMonoSample)
            .coerceAtLeast(playbackBlockSamples * 2)
        val targetFrames = when (bufferModeKey) {
            BufferModeConfig.Mode.ULTRA_LOW.key -> max(
                minFrames,
                (sampleRate * jitterBufferMs.coerceAtLeast(20) / 1000)
                    .coerceAtLeast(playbackBlockSamples * 2)
            )
            BufferModeConfig.Mode.HIGH.key -> max(
                minFrames,
                (sampleRate * jitterBufferMs.coerceAtLeast(1500) / 1000)
                    .coerceAtLeast(playbackBlockSamples * 2)
            )
            BufferModeConfig.Mode.MEDIUM.key -> max(
                minFrames,
                (sampleRate * 250 / 1000).coerceAtLeast(playbackBlockSamples * 2)
            )
            BufferModeConfig.Mode.LOW.key -> max(
                minFrames,
                (sampleRate * 100 / 1000).coerceAtLeast(playbackBlockSamples * 2)
            )
            else -> minFrames
        }
        try {
            track.bufferSizeInFrames = targetFrames
            val profileText = when (bufferModeKey) {
                BufferModeConfig.Mode.ULTRA_LOW.key -> "Golden Ultra Low"
                BufferModeConfig.Mode.HIGH.key -> "High continuity"
                BufferModeConfig.Mode.MEDIUM.key -> "Medium balanced"
                BufferModeConfig.Mode.LOW.key -> "Low lip-sync"
                BufferModeConfig.Mode.CUSTOM.key -> "Custom adaptive (HAL-minimum hardware queue)"
                else -> "adaptive"
            }
            val actualFrames = track.bufferSizeInFrames
            Log.i(
                TAG,
                "🎯 AudioTrack $profileText policy: requested=$targetFrames frames, actual=$actualFrames frames " +
                    "(~${(actualFrames * 1000L) / sampleRate}ms), HAL/allocated minimum=$minFrames frames"
            )
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "⚠️ AudioTrack rejected $targetFrames-frame buffer size: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "⚠️ AudioTrack buffer policy update skipped: ${e.message}")
        }
    }

    /**
     * Estimate receiver headroom from cheap local device signals. This is a guard
     * term, not a benchmark or a claim that CPU/RAM fully characterize a phone.
     */
    @Suppress("NewApi")
    private fun estimateCustomDevicePenaltyMs(): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        var penalty = when {
            cores <= 4 -> 20
            cores <= 6 -> 10
            else -> 0
        }
        // Process CPU is sampled once per controller refresh and normalized by
        // logical cores; this is a load hint, not a hardware benchmark.
        try {
            val wallNow = SystemClock.elapsedRealtime()
            val cpuNow = android.os.Process.getElapsedCpuTime()
            if (lastCustomCpuWallAtMs > 0L && wallNow > lastCustomCpuWallAtMs) {
                val wallDelta = wallNow - lastCustomCpuWallAtMs
                val cpuDelta = (cpuNow - lastCustomProcessCpuMs).coerceAtLeast(0L)
                customProcessCpuPercent = ((cpuDelta * 100L) / (wallDelta * cores)).toInt().coerceIn(0, 100)
            }
            lastCustomCpuWallAtMs = wallNow
            lastCustomProcessCpuMs = cpuNow
            penalty += when {
                customProcessCpuPercent >= 80 -> 25
                customProcessCpuPercent >= 60 -> 12
                else -> 0
            }
        } catch (_: Throwable) { }
        try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memory = android.app.ActivityManager.MemoryInfo()
            manager?.getMemoryInfo(memory)
            if (memory.availMem < 512L * 1024L * 1024L) penalty += 25
            else if (memory.availMem < 1024L * 1024L * 1024L) penalty += 10
        } catch (_: Throwable) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val power = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                penalty += when (power?.currentThermalStatus) {
                    android.os.PowerManager.THERMAL_STATUS_SEVERE,
                    android.os.PowerManager.THERMAL_STATUS_CRITICAL,
                    android.os.PowerManager.THERMAL_STATUS_EMERGENCY,
                    android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> 80
                    android.os.PowerManager.THERMAL_STATUS_MODERATE -> 35
                    android.os.PowerManager.THERMAL_STATUS_LIGHT -> 10
                    else -> 0
                }
            } catch (_: Throwable) { }
        }
        return penalty.coerceIn(0, 120)
    }

    /** Run one immediate route assessment after the user releases the Custom slider. */
    private fun refreshCustomNetworkStateAsync(reason: String) {
        val serverIp = currentServerAddress.substringBefore(":").trim()
        if (serverIp.isBlank() || !isConnected || bufferModeKey != BufferModeConfig.Mode.CUSTOM.key) return
        serviceIoScope.launch(Dispatchers.IO) {
            try {
                val measured = assessNetworkQuality(serverIp)
                if (!isConnected || bufferModeKey != BufferModeConfig.Mode.CUSTOM.key) return@launch
                networkQuality = measured
                cachedCustomBufferDecision = null
                lastCustomDecisionRefreshAtMs = 0L
                val decision = currentCustomBufferDecision(forceRefresh = true)
                Log.i(
                    TAG,
                    "🧠 Custom network refresh ($reason): RTT=${measured.latency}ms, jitter=${"%.1f".format(measured.jitter)}ms, " +
                        "effective=${decision.effectiveTargetMs}ms, watermarks=${decision.lowWatermarkMs}/${decision.highWatermarkMs}ms"
                )
                sendBufferProfileTelemetryNow(serverIp)
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Custom network refresh failed ($reason): ${e.message}")
            }
        }
    }

    /** Recompute at most once per second; the audio loop only reads the cached policy. */
    private fun currentCustomBufferDecision(forceRefresh: Boolean = false): CustomBufferEngine.Decision {
        val now = SystemClock.elapsedRealtime()
        val existing = cachedCustomBufferDecision
        if (!forceRefresh && existing != null && now - lastCustomDecisionRefreshAtMs < 1_000L) {
            return existing
        }
        synchronized(customDecisionLock) {
            val secondCheck = cachedCustomBufferDecision
            val lockedNow = SystemClock.elapsedRealtime()
            if (!forceRefresh && secondCheck != null && lockedNow - lastCustomDecisionRefreshAtMs < 1_000L) {
                return secondCheck
            }
            val decision = CustomBufferEngine.decide(
                requestedMs = customBufferMs,
                rttMs = networkQuality.latency,
                jitterMs = networkQuality.jitter,
                devicePenaltyMs = estimateCustomDevicePenaltyMs()
            )
            cachedCustomBufferDecision = decision
            lastCustomDecisionRefreshAtMs = lockedNow
            return decision
        }
    }

    private suspend fun awaitCustomPcmPreroll(
        decision: CustomBufferEngine.Decision,
        timeoutOverrideMs: Long? = null
    ): Int {
        val targetSamples = (decision.preRollTargetMs * sampleRate) / 1000
        val timeoutMs = timeoutOverrideMs ?: decision.preRollTimeoutMs
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var bufferedSamples = 0
        while (shouldStream && isConnected && SystemClock.elapsedRealtime() < deadline) {
            val stats = IntArray(4)
            if (nativeServiceGetBufferStats(stats)) bufferedSamples = stats[3]
            if (bufferedSamples >= targetSamples) break
            delay(5L)
        }
        return bufferedSamples
    }

    /**
     * Android's AudioTrack hardware underrun counter is separate from native ring
     * read timeouts (the existing buffer_underruns statistic). Returns -1 when
     * the API/device cannot provide the hardware counter.
     */
    private fun getAudioTrackHardwareUnderruns(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return -1
        return try {
            audioTrack?.underrunCount ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    /** Send a one-shot profile announcement to UDP telemetry port 50009. */
    private fun sendBufferProfileTelemetryNow(serverIp: String) {
        val destinationIp = serverIp.trim()
        if (destinationIp.isBlank()) return

        val modeKey = bufferModeKey
        val activeBufferMs = jitterBufferMs
        val streamingProfile = when (modeKey) {
            BufferModeConfig.Mode.ULTRA_LOW.key -> "golden_ultra_low_v1"
            BufferModeConfig.Mode.HIGH.key -> "high_continuity_v1"
            BufferModeConfig.Mode.MEDIUM.key -> "medium_balanced_v1"
            BufferModeConfig.Mode.LOW.key -> "low_lipsync_v1"
            BufferModeConfig.Mode.CUSTOM.key -> CustomBufferEngine.PROFILE
            else -> "adaptive_v2"
        }
        val customDecision = if (modeKey == BufferModeConfig.Mode.CUSTOM.key) {
            currentCustomBufferDecision()
        } else null
        val customNativeRingDrops = if (modeKey == BufferModeConfig.Mode.CUSTOM.key) {
            try { nativeServiceGetCustomBufferRingDrops() } catch (_: Throwable) { -1L }
        } else 0L
        val payload = try {
            val stats = try { getConnectionStats() } catch (_: Throwable) {
                ConnectionStats(0, 0, 0, 0)
            }
            val audioTrackHardwareUnderruns = getAudioTrackHardwareUnderruns()
            JSONObject().apply {
                put("type", "TELEMETRY")
                put("quality", 0)
                put("rtt", networkQuality.latency)
                put("jitter", networkQuality.jitter)
                put("conn", detectTelemetryConnectionType())
                put("rssi", 0)
                put("mode", modeKey.uppercase(Locale.ROOT))
                put("buffer_mode", modeKey)
                put("buffer_ms", activeBufferMs)
                put("streaming_profile", streamingProfile)
                put("custom_buffer_ms", if (modeKey == BufferModeConfig.Mode.CUSTOM.key) customBufferMs else 0)
                put("custom_effective_ms", customDecision?.effectiveTargetMs ?: 0)
                put("custom_network_margin_ms", customDecision?.networkMarginMs ?: 0)
                put("custom_device_penalty_ms", customDecision?.devicePenaltyMs ?: 0)
                put("custom_device_cpu_percent", customProcessCpuPercent)
                put("custom_low_watermark_ms", customDecision?.lowWatermarkMs ?: 0)
                put("custom_high_watermark_ms", customDecision?.highWatermarkMs ?: 0)
                put("custom_stale_blocks_skipped", customStaleBlocksSkipped)
                put("custom_native_ring_drops", customNativeRingDrops)
                put("low_lipsync_skipped_blocks", lowLipSyncSkippedBlocks)
                put("packets_received", stats.packetsReceived)
                put("packets_dropped", stats.packetsDropped)
                put("buffer_underruns", stats.bufferUnderruns)
                put("audio_track_underruns", audioTrackHardwareUnderruns)
                put("buffer_level", stats.bufferLevel)
                put("sync_score", 100)
            }.toString().toByteArray(Charsets.UTF_8)
        } catch (e: Throwable) {
            Log.w(TAG, "⚠️ Could not build buffer-profile telemetry: ${e.message}")
            return
        }

        thread(name = "BufferProfileTelemetry", isDaemon = true) {
            try {
                DatagramSocket().use { socket ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try { boundNetwork?.bindSocket(socket) } catch (e: Exception) {
                            Log.d(TAG, "Telemetry socket using current route: ${e.message}")
                        }
                    }
                    val packet = DatagramPacket(
                        payload,
                        payload.size,
                        InetAddress.getByName(destinationIp),
                        50009
                    )
                    socket.send(packet)
                }
                Log.i(
                    TAG,
                    "📡 Buffer profile announced to $destinationIp: $modeKey/$activeBufferMs ms ($streamingProfile)"
                )
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Buffer-profile telemetry send failed: ${e.message}")
            }
        }
    }

    private fun assessNetworkQuality(serverIp: String): NetworkQuality {
        val samples = mutableListOf<Long>()
        try {
            repeat(3) { index ->
                val startTime = System.nanoTime()
                DatagramSocket().use { socket ->
                    socket.soTimeout = 1500
                    val testData = "PING_TEST".toByteArray()
                    val packet = DatagramPacket(
                        testData,
                        testData.size,
                        InetAddress.getByName(serverIp),
                        50000
                    )
                    socket.send(packet)

                    val responseBuffer = ByteArray(64)
                    val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                    socket.receive(responsePacket)
                }

                val elapsedMs = ((System.nanoTime() - startTime) / 1_000_000L).coerceAtLeast(1L)
                samples.add(elapsedMs)

                if (index < 2) {
                    Thread.sleep(40)
                }
            }

            val avgLatency = samples.average().toLong().coerceAtLeast(1L)
            val jitterMs = if (samples.size > 1) {
                samples.zipWithNext { a, b -> abs(a - b).toDouble() }.average()
            } else {
                0.0
            }

            val quality = when {
                avgLatency < 50 -> "EXCELLENT"
                avgLatency < 100 -> "GOOD"
                avgLatency < 200 -> "FAIR"
                else -> "POOR"
            }

            return NetworkQuality(85, avgLatency, quality, jitterMs)

        } catch (e: Exception) {
            Log.w(TAG, "📊 : Network quality assessment failed: ${e.message}")
            return NetworkQuality(50, 999L, "UNKNOWN", 0.0)
        }
    }


    fun getConnectionStats(): ConnectionStats {
        val stats = IntArray(4) // [packets, drops, underruns, buffer_level]
        nativeServiceGetBufferStats(stats)
        return ConnectionStats(
            stats[0],
            stats[1],
            stats[2],
            stats[3])
    }
    fun getCurrentNetworkQuality(): NetworkQuality = networkQuality

    private suspend fun awaitMediumPcmPreroll(targetMs: Int = 200, timeoutMs: Long = 2500L): Int {
        val targetSamples = (targetMs * sampleRate) / 1000
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var bufferedSamples = 0
        while (shouldStream && isConnected && SystemClock.elapsedRealtime() < deadline) {
            val stats = IntArray(4)
            if (nativeServiceGetBufferStats(stats)) bufferedSamples = stats[3]
            if (bufferedSamples >= targetSamples) break
            delay(5L)
        }
        return bufferedSamples
    }

    private suspend fun awaitLowPcmPreroll(targetMs: Int = 60, timeoutMs: Long = 1500L): Int {
        val targetSamples = (targetMs * sampleRate) / 1000
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var bufferedSamples = 0
        while (shouldStream && isConnected && SystemClock.elapsedRealtime() < deadline) {
            val stats = IntArray(4)
            if (nativeServiceGetBufferStats(stats)) bufferedSamples = stats[3]
            if (bufferedSamples >= targetSamples) break
            delay(3L)
        }
        return bufferedSamples
    }

    // Replace nativeStreamLoop with format validation:
    private suspend fun nativeStreamLoop(bufferSize: Int): Boolean =
        withContext(Dispatchers.IO) {
            var track = audioTrack ?: return@withContext true

            Log.d(TAG, "Starting native stream loop with buffer size: $bufferSize for $deviceName")

            // 🎯 LOW-LATENCY: Use smaller buffer for reduced delay ... 🎧 LOG PLAYBACK LOOP START
            Log.d(TAG, "🎵 CLIENT RECEIVE FORMAT CHECK:")
            Log.d(TAG, " - Buffer Size: $bufferSize samples")
            Log.d(TAG, " - Expected Bytes per Frame: ${bufferSize * bytesPerMonoSample}")
            Log.d(TAG, "   Expected: 48000Hz MONO 16-bit")
            Log.d(TAG, "   Device: $deviceName")

            //🎯 FIXED: Pre-roll loop completely deleted. UDP demands immediate playback for 0-ms lag.

            // 🎯 **NEW: 10ms paced streaming with phase-locked timing**
            val blockSamples = playbackBlockSamples
            val buffer = ShortArray(blockSamples)

            var framePlayed = 0
            var underrunCount = 0
            var lastAudioDataAt = SystemClock.elapsedRealtime()
            var forceDisconnected = false
            var fastForwardActive = false
            var fastForwardAboveSinceMs = 0L
            var lastFastForwardLogMs = 0L

            Log.d(TAG, "🎵 Starting PURE HARDWARE paced streaming loop for lip-sync")

            // Startup trimming remains unchanged for adaptive Low/Custom. Medium uses a
            // short real-PCM pre-roll. Golden Ultra Low keeps immediate playback; High
            // uses the opposite policy: preserve every queued block and build a 1500ms cushion.
            when (bufferModeKey) {
                BufferModeConfig.Mode.ULTRA_LOW.key -> {
                    Log.i(TAG, "⚡ Golden Ultra Low: immediate playback enabled; adaptive startup trim is bypassed")
                }
                BufferModeConfig.Mode.HIGH.key -> {
                    val targetSamples = (jitterBufferMs.coerceAtLeast(1500) * sampleRate) / 1000
                    val deadlineMs = SystemClock.elapsedRealtime() + 8000L
                    var bufferedSamples = 0
                    while (shouldStream && isConnected && SystemClock.elapsedRealtime() < deadlineMs) {
                        val prerollStats = IntArray(4)
                        if (nativeServiceGetBufferStats(prerollStats)) bufferedSamples = prerollStats[3]
                        if (bufferedSamples >= targetSamples) break
                        delay(10L)
                    }
                    Log.i(TAG, "🔊 High buffer pre-roll: ${bufferedSamples} samples (~${bufferedSamples * 1000 / sampleRate}ms) / target ${targetSamples} samples; queued audio is preserved")
                    if (bufferedSamples < targetSamples) {
                        Log.w(TAG, "⚠️ High buffer pre-roll target not reached before 8s guard; starting without trimming queued PCM")
                    }
                }
                BufferModeConfig.Mode.MEDIUM.key -> {
                    // Medium starts with a modest real-PCM cushion and never trims
                    // the startup burst. The AudioTrack is still prepared, not playing.
                    val bufferedSamples = awaitMediumPcmPreroll()
                    val targetSamples = (200 * sampleRate) / 1000
                    Log.i(TAG, "⚖️ Medium pre-roll: ${bufferedSamples} samples (~${bufferedSamples * 1000 / sampleRate}ms) / target 200ms; startup PCM retained")
                    if (bufferedSamples < targetSamples) {
                        Log.w(TAG, "⚠️ Medium 200ms pre-roll target not reached within 2.5s; starting with available PCM (no startup trim)")
                    }
                }
                BufferModeConfig.Mode.LOW.key -> {
                    // Low uses only a short real-PCM prime. It is deliberately much
                    // shorter than Medium/High so the first audible frame stays aligned.
                    val bufferedSamples = awaitLowPcmPreroll()
                    val targetSamples = (60 * sampleRate) / 1000
                    Log.i(TAG, "🎯 Low pre-roll: ${bufferedSamples} samples (~${bufferedSamples * 1000 / sampleRate}ms) / target 60ms; startup PCM retained")
                    if (bufferedSamples < targetSamples) {
                        Log.w(TAG, "⚠️ Low 60ms pre-roll not reached within 1.5s; starting with available PCM")
                    }
                }
                BufferModeConfig.Mode.CUSTOM.key -> {
                    val decision = currentCustomBufferDecision(forceRefresh = true)
                    val bufferedSamples = awaitCustomPcmPreroll(decision)
                    Log.i(TAG, "🧠 Custom pre-roll: ${bufferedSamples} samples (~${bufferedSamples * 1000 / sampleRate}ms) / target ${decision.preRollTargetMs}ms, requested=${decision.requestedMs}ms, timeout=${decision.preRollTimeoutMs}ms")
                    if (bufferedSamples < (decision.preRollTargetMs * sampleRate) / 1000) {
                        Log.w(TAG, "⚠️ Custom pre-roll target not reached before timeout; starting with available real PCM")
                    }
                }
                else -> {
                    val startupKeepMs = when {
                        jitterBufferMs <= 10 && isWiredMode -> 25
                        jitterBufferMs <= 10 -> 50
                        else -> 250
                    }
                    val startupKeepSamples = (startupKeepMs * sampleRate) / 1000
                    try {
                        val startupStats = IntArray(4)
                        if (nativeServiceGetBufferStats(startupStats)) {
                            val startupBufferSamples = startupStats[3]
                            if (startupBufferSamples > startupKeepSamples) {
                                val droppedSamples = drainNativeAudioBacklog(128)
                                Log.i(TAG, "🧹 Startup backlog trimmed: before=${startupBufferSamples} samples (~${startupBufferSamples * 1000 / sampleRate}ms), drained=${droppedSamples} samples, keep≈${startupKeepMs}ms")
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "⚠️ Startup backlog check skipped: ${e.message}")
                    }
                }
            }

            if ((bufferModeKey == BufferModeConfig.Mode.MEDIUM.key ||
                    bufferModeKey == BufferModeConfig.Mode.LOW.key ||
                    bufferModeKey == BufferModeConfig.Mode.CUSTOM.key) &&
                track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
                val label = when (bufferModeKey) {
                    BufferModeConfig.Mode.LOW.key -> "Low"
                    BufferModeConfig.Mode.MEDIUM.key -> "Medium"
                    else -> "Custom"
                }
                Log.i(TAG, "▶️ $label AudioTrack started after real-PCM pre-roll")
            }

            while (shouldStream && isConnected) {
                try {
                    if (audioTrackRebuildRequested) {
                        audioTrackRebuildRequested = false
                        // Do not carry a stale FAST-FORWARD latch across profiles.
                        // Golden's own branch below remains unchanged and will arm
                        // its original 40ms rule only when the native ring requires it.
                        fastForwardActive = false
                        fastForwardAboveSinceMs = 0L
                        setupAudioTrack(bufferSize)
                        track = audioTrack ?: return@withContext true
                        if (bufferModeKey == BufferModeConfig.Mode.HIGH.key) {
                            val targetSamples = (jitterBufferMs.coerceAtLeast(1500) * sampleRate) / 1000
                            val deadlineMs = SystemClock.elapsedRealtime() + 8000L
                            var bufferedSamples = 0
                            while (shouldStream && isConnected && SystemClock.elapsedRealtime() < deadlineMs) {
                                val switchStats = IntArray(4)
                                if (nativeServiceGetBufferStats(switchStats)) bufferedSamples = switchStats[3]
                                if (bufferedSamples >= targetSamples) break
                                delay(10L)
                            }
                            Log.i(TAG, "🔊 High mode switch pre-roll: ${bufferedSamples} samples (~${bufferedSamples * 1000 / sampleRate}ms) / target 1500ms; queued PCM preserved")
                            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                        } else if (bufferModeKey == BufferModeConfig.Mode.MEDIUM.key) {
                            val bufferedSamples = awaitMediumPcmPreroll()
                            Log.i(TAG, "⚖️ Medium mode switch pre-roll: ${bufferedSamples} samples (~${bufferedSamples * 1000 / sampleRate}ms)")
                            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                        } else if (bufferModeKey == BufferModeConfig.Mode.LOW.key) {
                            fastForwardActive = false
                            fastForwardAboveSinceMs = 0L
                            val bufferedSamples = awaitLowPcmPreroll()
                            Log.i(TAG, "🎯 Low mode switch pre-roll: ${bufferedSamples} samples (~${bufferedSamples * 1000 / sampleRate}ms)")
                            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                        } else if (bufferModeKey == BufferModeConfig.Mode.CUSTOM.key) {
                            fastForwardActive = false
                            fastForwardAboveSinceMs = 0L
                            val decision = currentCustomBufferDecision(forceRefresh = true)
                            val bufferedSamples = awaitCustomPcmPreroll(decision)
                            Log.i(TAG, "🧠 Custom mode switch pre-roll: ${bufferedSamples} samples (~${bufferedSamples * 1000 / sampleRate}ms) / target=${decision.preRollTargetMs}ms")
                            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                        }
                        Log.i(TAG, "✅ AudioTrack reconfigured at a frame boundary for ${bufferModeKey}")
                    }

                    // Audio-focus interruptions use temporary AudioTrack gain ducking.
                    // Keep pulling PCM so no focus-loss path can mute or grow backlog.

                    val samplesRead = nativeServiceGetAudioData(buffer, blockSamples)
                    if (samplesRead > 0)  {
                        lastAudioDataAt = SystemClock.elapsedRealtime()

                        val bufferStats = IntArray(4)
                        val nativeStatsOk = try {
                            nativeServiceGetBufferStats(bufferStats)
                        } catch (_: Throwable) {
                            false
                        }
                        val nativeBufferLevelSamples =
                            if (nativeStatsOk) bufferStats[3] else 0

                        if (bufferModeKey == BufferModeConfig.Mode.ULTRA_LOW.key) {
                            // Golden pipeline behaviour: after each 10ms native read,
                            // inspect the 1-second native ring and immediately drop
                            // this playback block while backlog exceeds jitter+30ms.
                            // At the Ultra Low preset (10ms), the cutoff is 40ms.
                            // Reuse the stats snapshot already read above. A second JNI
                            // stats call here adds lock contention to every 10ms frame
                            // without improving the cutoff decision.
                            val maxAllowedSamples = ((jitterBufferMs + 30) * sampleRate) / 1000
                            if (nativeBufferLevelSamples > maxAllowedSamples) {
                                framePlayed++
                                if (framePlayed % 50 == 0) {
                                    Log.w(
                                        TAG,
                                        "⏩ GOLDEN FAST-FORWARD: Native buffer=$nativeBufferLevelSamples samples, " +
                                            "max=$maxAllowedSamples. Dropping stale playback block to preserve lip-sync."
                                    )
                                }
                                continue
                            }
                        } else if (bufferModeKey != BufferModeConfig.Mode.HIGH.key) {
                        // Recover lip-sync only from a material backlog. Older devices
                        // can legitimately retain more audio while WRITE_BLOCKING waits
                        // 🎯 ADAPTIVE LIP-SYNC HEADROOM
                        // Short packet/scheduler bursts are absorbed. We only enter
                        // stale-audio removal after the backlog remains above the
                        // high watermark long enough to represent a real sustained
                        // latency build-up. This greatly reduces false FAST-FORWARDs
                        // without changing the hardware-clock-based playback pacing.
                        // Do not compare the network ring against an ultra-tight 30/40ms
                        // threshold. AudioTrack itself has a negotiated hardware queue; a
                        // short scheduler/Wi-Fi burst should be absorbed instead of causing
                        // repeated 10ms stale-frame deletion. We still cap the queue tightly
                        // enough to prevent an unbounded latency drift.
                        val measuredJitterMs = networkQuality.jitter.toInt().coerceAtLeast(0)
                        val customPolicy = if (bufferModeKey == BufferModeConfig.Mode.CUSTOM.key) {
                            currentCustomBufferDecision()
                        } else null
                        val highWatermarkMs = when {
                            bufferModeKey == BufferModeConfig.Mode.CUSTOM.key -> customPolicy!!.highWatermarkMs
                            bufferModeKey == BufferModeConfig.Mode.MEDIUM.key ->
                                (600 + measuredJitterMs * 2).coerceIn(600, 800)
                            bufferModeKey == BufferModeConfig.Mode.LOW.key ->
                                (180 + measuredJitterMs).coerceIn(180, 300)
                            jitterBufferMs <= 10 && isWiredMode -> 90
                            jitterBufferMs <= 10 ->
                                (120 + measuredJitterMs * 2).coerceIn(120, 220)
                            else ->
                                (jitterBufferMs + 200).coerceAtMost(1800)
                        }
                        val lowWatermarkMs = when {
                            bufferModeKey == BufferModeConfig.Mode.CUSTOM.key -> customPolicy!!.lowWatermarkMs
                            bufferModeKey == BufferModeConfig.Mode.MEDIUM.key ->
                                (highWatermarkMs - 250).coerceAtLeast(350)
                            bufferModeKey == BufferModeConfig.Mode.LOW.key ->
                                (highWatermarkMs - 100).coerceAtLeast(80)
                            jitterBufferMs <= 10 && isWiredMode -> 45
                            jitterBufferMs <= 10 ->
                                (highWatermarkMs - 60).coerceAtLeast(60)
                            else ->
                                (highWatermarkMs - 200).coerceAtLeast(jitterBufferMs + 50)
                        }
                        val highWatermarkSamples = (highWatermarkMs * sampleRate) / 1000
                        val lowWatermarkSamples = (lowWatermarkMs * sampleRate) / 1000
                        val nowMs = SystemClock.elapsedRealtime()

                        if (!fastForwardActive) {
                            if (nativeBufferLevelSamples > highWatermarkSamples) {
                                if (fastForwardAboveSinceMs == 0L) {
                                    fastForwardAboveSinceMs = nowMs
                                }

                                val holdMs = when {
                                    bufferModeKey == BufferModeConfig.Mode.MEDIUM.key -> 1000L
                                    bufferModeKey == BufferModeConfig.Mode.LOW.key -> {
                                        // Allow transient Wi-Fi/OS scheduling bursts to settle.
                                        // Low remains bounded below Medium's 600-800ms watermarks.
                                        val severeBacklogMs = (nativeBufferLevelSamples * 1000L) / sampleRate
                                        if (severeBacklogMs >= 500L) 350L else 650L
                                    }
                                    bufferModeKey == BufferModeConfig.Mode.CUSTOM.key -> customPolicy!!.persistenceMs
                                    jitterBufferMs <= 10 -> 350L
                                    else -> 600L
                                }
                                if (nowMs - fastForwardAboveSinceMs >= holdMs) {
                                    fastForwardActive = true
                                    Log.i(
                                        TAG,
                                        "⏩ FAST-FORWARD armed: buffer=${nativeBufferLevelSamples} samples, high=${highWatermarkSamples} samples (${highWatermarkMs}ms), low=${lowWatermarkSamples} samples (${lowWatermarkMs}ms)"
                                    )
                                }
                            } else {
                                fastForwardAboveSinceMs = 0L
                            }
                        }

                        if (fastForwardActive) {
                            if (nativeBufferLevelSamples <= lowWatermarkSamples) {
                                fastForwardActive = false
                                fastForwardAboveSinceMs = 0L
                                Log.i(TAG, "✅ FAST-FORWARD released at ${nativeBufferLevelSamples} samples")
                            } else {
                                if (bufferModeKey == BufferModeConfig.Mode.LOW.key) {
                                    lowLipSyncSkippedBlocks++
                                } else if (bufferModeKey == BufferModeConfig.Mode.CUSTOM.key) {
                                    customStaleBlocksSkipped++
                                }
                                val fastForwardLogIntervalMs = when (bufferModeKey) {
                                    BufferModeConfig.Mode.LOW.key -> 3000L
                                    BufferModeConfig.Mode.CUSTOM.key -> 3000L
                                    else -> 500L
                                }
                                if (nowMs - lastFastForwardLogMs >= fastForwardLogIntervalMs) {
                                    lastFastForwardLogMs = nowMs
                                    Log.w(
                                        TAG,
                                        "⏩ FAST-FORWARD: Native buffer = $nativeBufferLevelSamples samples; target <= ${lowWatermarkSamples} (${lowWatermarkMs}ms). " +
                                            "Skipped=${when (bufferModeKey) { BufferModeConfig.Mode.LOW.key -> lowLipSyncSkippedBlocks; BufferModeConfig.Mode.CUSTOM.key -> customStaleBlocksSkipped; else -> "n/a" }}; stale playback blocks are being skipped to preserve lip-sync."
                                    )
                                }
                                continue
                            }
                        }

                        }

                        // High prioritizes audio continuity over lip-sync. Keep retrying
                        // any partial blocking write instead of discarding its unwritten tail.
                        if (bufferModeKey == BufferModeConfig.Mode.HIGH.key ||
                            bufferModeKey == BufferModeConfig.Mode.MEDIUM.key ||
                            bufferModeKey == BufferModeConfig.Mode.LOW.key) {
                            val writeModeName = when (bufferModeKey) {
                                BufferModeConfig.Mode.LOW.key -> "Low"
                                BufferModeConfig.Mode.MEDIUM.key -> "Medium"
                                else -> "High"
                            }
                            var offset = 0
                            var zeroWriteCount = 0
                            while (offset < samplesRead && shouldStream && isConnected) {
                                val written = track.write(buffer, offset, samplesRead - offset, AudioTrack.WRITE_BLOCKING)
                                if (written > 0) {
                                    offset += written
                                    zeroWriteCount = 0
                                } else if (written == 0) {
                                    zeroWriteCount++
                                    if (zeroWriteCount >= 100) {
                                        Log.w(TAG, "⚠️ $writeModeName AudioTrack write is waiting; retaining the pending PCM block")
                                        zeroWriteCount = 0
                                    }
                                    delay(2L)
                                } else {
                                    Log.e(TAG, "❌ $writeModeName AudioTrack write failed ($written); stopping rather than silently skipping PCM")
                                    shouldStream = false
                                    break
                                }
                            }
                            if (offset == samplesRead) framePlayed++
                        } else {
                            // Existing writer is preserved for Golden Ultra Low and Custom.
                            val samplesWritten = track.write(buffer, 0, samplesRead, AudioTrack.WRITE_BLOCKING)
                            if (samplesWritten > 0) {
                                framePlayed++
                                if (framePlayed % 5000 == 0) {
                                    Log.d(TAG, "📊 AudioTrack playback: $framePlayed blocks processed")
                                }
                            } else {
                                underrunCount++
                                val logNow = SystemClock.elapsedRealtime()
                                if (logNow - lastAudioUnderrunLogAtMs >= 3000L) {
                                    lastAudioUnderrunLogAtMs = logNow
                                    Log.w(TAG, "⚠️ AudioTrack write underruns observed: $underrunCount (repeat warning limited to 3s)")
                                }
                                if (underrunCount % 3 == 0) {
                                    java.util.Arrays.fill(buffer, 0.toShort())
                                    track.write(buffer, 0, blockSamples, AudioTrack.WRITE_BLOCKING)
                                }
                            }
                        }
                    } else {

                        if (nativeServiceWasForceDisconnected()) {
                            Log.w(
                                TAG,
                                "⚡ SERVER_FORCE_DISCONNECT received - ending playback loop"
                            )

                            forceDisconnected = true
                            shouldStream = false
                            isConnected = false
                            break
                        }

                        val idleMs = SystemClock.elapsedRealtime() - lastAudioDataAt
                        val idleTimeoutMs = if (bufferModeKey == BufferModeConfig.Mode.HIGH.key) 20_000L else 6000L
                        if (idleMs > idleTimeoutMs) {
                            Log.w(TAG, "⚠️ No audio packets received for ${idleMs}ms — server disconnected or network route changed. Initiating reconnect...")
                            stopStreaming(cancelReconnect = false)
                            handleConnectionFailure()
                            break
                        }

                        if (bufferModeKey == BufferModeConfig.Mode.HIGH.key) {
                            // High continuity mode keeps its verified no-silence policy.
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastHighBufferGapLogAtMs >= 5000L) {
                                lastHighBufferGapLogAtMs = now
                                Log.w(TAG, "⏳ High buffer waiting for the next real PCM block; no silence frame injected")
                            }
                            delay(5L)
                            continue
                        }
                        if (bufferModeKey == BufferModeConfig.Mode.MEDIUM.key) {
                            // Medium waits briefly for real PCM; do not add an extra block.
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastMediumBufferGapLogAtMs >= 5000L) {
                                lastMediumBufferGapLogAtMs = now
                                Log.w(TAG, "⏳ Medium buffer waiting briefly for real PCM; no synthetic block injected")
                            }
                            delay(3L)
                            continue
                        }
                        if (bufferModeKey == BufferModeConfig.Mode.LOW.key) {
                            // Low waits through a short receive gap rather than inserting
                            // synthetic PCM that would advance the audio timeline.
                            delay(2L)
                            continue
                        }

                        // Custom retains its existing 10ms silence-pacing fallback.
                        java.util.Arrays.fill(buffer, 0,blockSamples,0.toShort())
                        track.write(buffer, 0,blockSamples, AudioTrack.WRITE_BLOCKING)
                    }

                }  catch (e: Exception) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastStreamExceptionLogAtMs >= 5000L) {
                        lastStreamExceptionLogAtMs = now
                        Log.e(TAG, "Stream-loop error for $deviceName: ${e.message} (repeated errors limited to 5s)", e)
                    }
                    delay(5L)
                }
            }
            Log.d(
                TAG,
                "Streaming loop ended after $framePlayed blocks on $deviceName " +
                        "(forceDisconnected=$forceDisconnected)"
            )

            return@withContext forceDisconnected
        }

    private fun startPerformanceMonitoring() {
        var lastStatsPacketCount = 0
        var lastStatsSampleAtMs = SystemClock.elapsedRealtime()
        var loopCount = 0
        monitorJob?.cancel()
        monitorJob = serviceIoScope.launch {
            while (shouldStream && isConnected) {
                try {
                    delay(3000) // Monitor every 3 seconds
                    loopCount++

                    val stats = getConnectionStats()
                    val currentTime = System.currentTimeMillis()
                    val connectionDuration = currentTime - lastConnectionTime

                    val now = SystemClock.elapsedRealtime()
                    val deltaPackets = (stats.packetsReceived - lastStatsPacketCount).coerceAtLeast(0)
                    val deltaMs = (now - lastStatsSampleAtMs).coerceAtLeast(1L)
                    val bytesPerPacket = 964.0
                    val downKbps = (deltaPackets * bytesPerPacket * 1000.0 / deltaMs) / 1024.0

                    lastStatsPacketCount = stats.packetsReceived
                    lastStatsSampleAtMs = now

                    val dropRate = if (stats.packetsReceived > 0) {
                        (stats.packetsDropped.toFloat() / stats.packetsReceived.toFloat()) * 100f
                    } else 0f

                    // bufferUnderruns is a cumulative event count, not a percentage
                    // denominator. Treating it as a fraction of packets could produce
                    // impossible values such as 110% or 500%. Keep it as a raw count.
                    val underrunCount = stats.bufferUnderruns

                    // Custom re-measures its route about every 9s (3s monitor x 3).
                    // Other profiles retain the established 30s measurement cadence.
                    val pingCadenceLoops = if (bufferModeKey == BufferModeConfig.Mode.CUSTOM.key) 3 else 10
                    if (loopCount % pingCadenceLoops == 1) {
                        val serverIp = currentServerAddress.substringBefore(":").trim()
                        if (serverIp.isNotBlank()) {
                            networkQuality = assessNetworkQuality(serverIp)
                            if (bufferModeKey == BufferModeConfig.Mode.CUSTOM.key) currentCustomBufferDecision()
                        }
                        Log.i(TAG, "📊 Performance: Connected ${connectionDuration/1000}s, Drop: ${dropRate}%, Underruns: ${underrunCount}"  +
                                "Network: ${networkQuality.signalQuality}, RTT=${networkQuality.latency}ms,Jitter=${"%.1f".format(networkQuality.jitter)}ms")
                    }

                    updateNotification(
                        "↓ ${"%.1f".format(downKbps)} KB/s • ${networkQuality.signalQuality} • Drop ${"%.1f".format(dropRate)}%"
                    )

                } catch (e: CancellationException) {
                    // Expected when an intentional stop/reconnect cancels the monitor.
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, " ⚠️ : Performance monitoring error: ${e.message}")
                }
            }
        }
    }

    private data class ServiceDiscoveredServer(
        val ip: String,
        val port: Int,
        val name: String,
        val backend: String
    )

    private fun bindCurrentModeNetworkNow(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // A phone-hosted SoftAP may have no ConnectivityManager Network.
            // Use kernel route selection only when the destination belongs to its
            // subnet (or Auto mode has no destination yet). This avoids selecting
            // a stale/unused SoftAP when a manual endpoint is on Home/PC Wi-Fi.
            val hostedHotspot = if (!isWiredMode) MobileHotspotDatabase.detect(
                applicationContext,
                MobileHotspotDatabase.WirelessPath.MOBILE_HOTSPOT
            ) else null
            val requestedIp = targetIp.trim()
            val useHostedHotspotRoute = hostedHotspot?.isReady == true && (
                requestedIp.isBlank() || isIpv4InSubnet(
                    requestedIp,
                    hostedHotspot.localIpv4.orEmpty(),
                    hostedHotspot.prefixLength ?: 24
                )
            )
            if (useHostedHotspotRoute) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cm.bindProcessToNetwork(null)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ConnectivityManager.setProcessDefaultNetwork(null)
                }
                boundNetwork = null
                boundNetworkHandle = -1L
                networkMonitoringInitialized = true
                lastObservedTransportSignature = currentSelectedTransportSignature()
                publishConnectionPathChanged()
                Log.i(TAG, "📱 Mobile Hotspot SoftAP route selected — kernel routing for ${requestedIp.ifBlank { "discovery" }}")
                return true
            }

            val candidate = findCurrentModeNetwork()
            if (candidate == null) {
                // Do not keep a process-wide binding to the previous route while
                // using interface-only USB/SoftAP routing or waiting for a route.
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.bindProcessToNetwork(null)
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) ConnectivityManager.setProcessDefaultNetwork(null)
                } catch (e: Exception) {
                    Log.d(TAG, "No candidate Network; process unbind best-effort: ${e.message}")
                }
                boundNetwork = null
                boundNetworkHandle = -1L
                val interfaceOnlyReady = if (isWiredMode) {
                    hasUsableLocalUsbTetherInterface()
                } else {
                    findActiveMobileHotspotLocalIp() != null || hasUsableLocalWirelessInterface()
                }
                if (interfaceOnlyReady) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.bindProcessToNetwork(null)
                        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) ConnectivityManager.setProcessDefaultNetwork(null)
                    } catch (_: Exception) {}
                    boundNetwork = null
                    boundNetworkHandle = -1L
                    networkMonitoringInitialized = true
                    lastObservedTransportSignature = currentSelectedTransportSignature()
                    Log.i(TAG, "🛜 Service using interface-only ${if (isWiredMode) "USB tether" else "SoftAP/Wi-Fi"} transport")
                    return true
                }
                Log.w(TAG, "⏸️ No usable ${if (isWiredMode) "wired USB-tether" else "Wi-Fi"} transport is currently available")
                return false
            }

            val bound = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.bindProcessToNetwork(candidate)
            } else {
                ConnectivityManager.setProcessDefaultNetwork(candidate)
                true
            }

            if (bound) {
                boundNetwork = candidate
                boundNetworkHandle = candidate.networkHandle
                networkMonitoringInitialized = true
                lastObservedTransportSignature = currentSelectedTransportSignature()
                Log.i(
                    TAG,
                    "🛜 Service bound current ${if (isWiredMode) "wired" else "wireless"} network immediately: ${candidate.networkHandle}"
                )
            }
            bound
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Immediate Service network bind failed: ${e.message}")
            false
        }
    }

    private suspend fun awaitSelectedTransportReady(timeoutMs: Long = 10000L): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastState = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isUserPaused || isUserDisconnected || hasStopped) return false
            val ready = try { bindCurrentModeNetworkNow() } catch (_: Exception) { false }
            if (ready) {
                if (lastState != "ready") {
                    Log.i(TAG, "✅ Selected ${if (isWiredMode) "USB tether" else "wireless"} transport is ready")
                    lastState = "ready"
                }
                return true
            }
            if (lastState != "waiting") {
                Log.i(TAG, "⏳ Waiting for selected ${if (isWiredMode) "USB tether/RNDIS" else "Wi-Fi/SoftAP"} interface to become usable")
                lastState = "waiting"
            }
            delay(350L)
        }
        Log.w(TAG, "⏱️ Timed out waiting ${timeoutMs}ms for selected transport")
        return false
    }

    private fun scheduleInitialServiceConnection(forceManual: Boolean = false) {
        if (!forceManual && (!autoReconnectEnabled || isUserPaused || isUserDisconnected || hasStopped)) {
            return
        }
        if (forceManual && (isUserPaused || hasStopped)) {
            return
        }
        if (isConnected || isCurrentlyConnecting) {
            return
        }
        if (initialConnectJob?.isActive == true) {
            return
        }

        initialConnectJob = serviceMainScope.launch {
            val owningJob = currentCoroutineContext()[Job]
            try {
                delay(if (forceManual) 0L else 50L)

                if ((!forceManual && !autoReconnectEnabled) || isUserPaused || hasStopped) {
                    return@launch
                }
                if (!forceManual && isUserDisconnected) {
                    return@launch
                }
                if (isConnected || isCurrentlyConnecting) {
                    return@launch
                }

                if (!awaitSelectedTransportReady()) {
                    connectionWaitingForTransport = true
                    publishTransportUnavailable()
                    return@launch
                }

                connectionWaitingForTransport = false
                // AUTO mode means discover on the current route. Never fall back
                // to a persisted IP when discovery fails: that IP may belong to
                // the previous Wi-Fi/hotspot subnet (e.g. Lenovo 192.168.0.160).
                // MANUAL/QR mode deliberately retains the user's chosen endpoint.
                var ip = if (autoConnectMode) "" else targetIp.trim()
                var port = if (autoConnectMode) 0 else targetPort

                if (autoConnectMode) {
                    updateNotification("🔍 Searching for WiFi Audio Server on the current route…")
                    val found = discoverServerInService()
                    if (found != null) {
                        ip = found.ip
                        port = found.port
                        serverDisplayName = found.name.ifBlank { "Unknown" }
                        serverBackendName = found.backend.ifBlank { "Unknown" }
                        targetIp = ip
                        targetPort = port
                        saveServiceTargetState()
                        Log.i(TAG, "✅ Current-route discovery selected $ip:$port")
                    } else {
                        Log.w(TAG, "❌ Current-route discovery found no server; refusing stale saved endpoint ${targetIp}:${targetPort}")
                    }
                }

                if (ip.isBlank() || port <= 0) {
                    updateNotification("⏳ Server not found on this route — retrying discovery…")
                    if (autoConnectMode && !forceManual) {
                        // Discovery can race the PC interface switch. Retry it
                        // with the Service-owned capped backoff instead of
                        // leaving the service silently idle or reusing stale IP.
                        handleConnectionFailure()
                    }
                    return@launch
                }

                currentServerAddress = "$ip:$port"
                startNativeStreaming(ip, port, jitterBufferMs)

            } finally {
                if (initialConnectJob === owningJob) initialConnectJob = null
            }
        }
    }

    fun getCurrentConnectionPathLabel(): String = currentConnectionPathLabel()

    private fun isIpv4InSubnet(candidateIp: String, localIp: String, prefixLength: Int): Boolean {
        fun toIpv4Int(value: String): Long? {
            val parts = value.trim().split('.')
            if (parts.size != 4) return null
            var result = 0L
            for (part in parts) {
                val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
                result = (result shl 8) or octet.toLong()
            }
            return result
        }
        val candidate = toIpv4Int(candidateIp) ?: return false
        val local = toIpv4Int(localIp) ?: return false
        val prefix = prefixLength.coerceIn(0, 32)
        val mask = if (prefix == 0) 0L else (0xFFFF_FFFFL shl (32 - prefix)) and 0xFFFF_FFFFL
        return (candidate and mask) == (local and mask)
    }

    private fun currentConnectionPathLabel(): String {
        return try {
            // The selected transmission mode is authoritative for the UI.
            // Never display Home Wi-Fi merely because Wi-Fi is the phone's
            // Internet upstream while the PC is connected through USB.
            if (isWiredMode) {
                val usb = findActiveUsbTransportDetails()
                return if (usb != null) {
                    "🔌 USB Tethering • ${usb.first} • phone ↔ PC"
                } else {
                    "🔌 USB Tethering selected • waiting for USB/RNDIS"
                }
            }

            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val hotspot = MobileHotspotDatabase.detect(
                applicationContext,
                MobileHotspotDatabase.WirelessPath.MOBILE_HOTSPOT
            )
            val endpointIp = targetIp.trim()
            val endpointUsesHotspot = endpointIp.isBlank() || isIpv4InSubnet(
                endpointIp,
                hotspot.localIpv4.orEmpty(),
                hotspot.prefixLength ?: 24
            )
            if (hotspot.isReady && endpointUsesHotspot) {
                return "📱 Mobile Hotspot • PC ↔ phone hotspot"
            }

            val network = boundNetwork ?: cm.activeNetwork
            val lp = network?.let { cm.getLinkProperties(it) }
            val iface = lp?.interfaceName?.lowercase(Locale.US).orEmpty()
            val localIp = lp?.linkAddresses
                ?.mapNotNull { it.address as? Inet4Address }
                ?.firstOrNull()?.hostAddress.orEmpty()
            val gateway = lp?.routes
                ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress.orEmpty()

            if (gateway == "192.168.137.1" || localIp.startsWith("192.168.137.")) {
                "💻 PC Hotspot • Phone ↔ PC hotspot"
            } else {
                "🏠 Home Wi-Fi / LAN • Phone ↔ PC same Wi-Fi"
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Connection path detection failed: ${e.message}")
            if (isWiredMode) "🔌 USB Tethering selected • waiting for USB/RNDIS" else "🛜 Wireless"
        }
    }

    private fun findActiveUsbTransportDetails(): Pair<String, String>? {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val fromAndroidNetwork = cm.allNetworks.asSequence().mapNotNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                val lp = cm.getLinkProperties(network) ?: return@mapNotNull null
                val iface = lp.interfaceName.orEmpty()
                if (!isWiredNetworkCandidate(iface, caps)) return@mapNotNull null
                val ip = lp.linkAddresses.mapNotNull { it.address as? Inet4Address }
                    .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress
                if (ip.isNullOrBlank()) null else iface to ip
            }.firstOrNull()
            if (fromAndroidNetwork != null) return fromAndroidNetwork

            Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
                .filter { iface ->
                    val name = iface.name.lowercase(Locale.US)
                    val up = try { iface.isUp } catch (_: Exception) { false }
                    up && isLikelyUsbInterface(name)
                }
                .mapNotNull { iface ->
                    val ip = Collections.list(iface.inetAddresses)
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                        ?.hostAddress
                    if (ip.isNullOrBlank()) null else iface.name to ip
                }
                .firstOrNull()
        } catch (_: Exception) { null }
    }

    private fun publishConnectionPathChanged() {
        val path = currentConnectionPathLabel()
        getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
            .edit().putString("last_connection_path", path).apply()
        sendBroadcast(
            Intent(ACTION_UI_CONNECTION_PATH_CHANGED)
                .setPackage(packageName)
                .putExtra("CONNECTION_PATH", path)
        )
    }

    private fun saveLastSuccessfulNetworkSignature() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = boundNetwork ?: return
            val lp = cm.getLinkProperties(network) ?: return
            val iface = lp.interfaceName.orEmpty().lowercase(Locale.US)
            val localIp = lp.linkAddresses.mapNotNull { it.address as? Inet4Address }.firstOrNull()?.hostAddress.orEmpty()
            val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress.orEmpty()
            if (localIp.isBlank() && gateway.isBlank()) return
            getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE).edit()
                .putString("last_network_signature", "$iface|$localIp|$gateway")
                .apply()
            Log.i(TAG, "🌐 Saved successful network signature: $iface|$localIp|$gateway")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to save network signature: ${e.message}")
        }
    }

    private fun saveServiceTargetState() {
        getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
            .edit()
            .putString("server_ip", targetIp)
            .putString("last_ip", targetIp)
            .putInt("server_port", targetPort)
            .putString("last_port", targetPort.toString())
            .putString("server_name", serverDisplayName)
            .putString("server_backend", serverBackendName)
            .apply()
    }

    private suspend fun discoverServerInService(): ServiceDiscoveredServer? =
        withContext(Dispatchers.IO) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val payload = "DISCOVER_WIFI_AUDIO_REQUEST".toByteArray(Charsets.UTF_8)
            val ports = intArrayOf(50000, 32000, 50007)
            val deadline = SystemClock.elapsedRealtime() + 3000L

            data class Binding(val name: String, val local: Inet4Address, val broadcast: Inet4Address?)
            val bindings = mutableListOf<Binding>()
            try {
                Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { iface ->
                    val name = iface.name.lowercase(Locale.US)
                    val up = try { iface.isUp } catch (_: Exception) { false }
                    if (!up || name == "lo" || name.startsWith("tun") || name.startsWith("p2p")) return@forEach
                    val relevant = if (isWiredMode) isLikelyUsbInterface(name) else
                        (name.contains("wlan") || name.contains("wifi") || isLikelyHotspotInterface(name) || name.contains("eth"))
                    if (!relevant) return@forEach
                    iface.interfaceAddresses.forEach { ia ->
                        val local = ia.address as? Inet4Address ?: return@forEach
                        if (!local.isLoopbackAddress && !local.isLinkLocalAddress) bindings.add(Binding(iface.name, local, ia.broadcast as? Inet4Address))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Service discovery interface scan failed: ${e.message}")
            }

            boundNetwork?.let { network ->
                if (networkMatchesCurrentMode(network)) {
                    cm.getLinkProperties(network)?.let { lp ->
                        lp.linkAddresses.mapNotNull { it.address as? Inet4Address }.forEach { local ->
                            if (!bindings.any { it.local.hostAddress == local.hostAddress }) {
                                bindings.add(Binding(lp.interfaceName ?: "cm", local, null))
                            }
                        }
                    }
                }
            }

            if (bindings.isEmpty()) return@withContext null

            for (binding in bindings.distinctBy { "${it.name}|${it.local.hostAddress}" }) {
                if (SystemClock.elapsedRealtime() >= deadline) break
                val localIp = binding.local.hostAddress ?: continue
                val p = localIp.split('.')
                val targets = linkedSetOf<String>()
                binding.broadcast?.hostAddress?.let { targets.add(it) }
                if (p.size == 4) {
                    targets.add("${p[0]}.${p[1]}.${p[2]}.255")
                    targets.add("${p[0]}.${p[1]}.${p[2]}.1")
                    targets.add("${p[0]}.${p[1]}.${p[2]}.254")
                }
                runCatching {
                    cm.allNetworks.asSequence().mapNotNull { n ->
                        val lp = cm.getLinkProperties(n) ?: return@mapNotNull null
                        if (lp.interfaceName.equals(binding.name, true)) lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress else null
                    }.firstOrNull()
                }.getOrNull()?.let { targets.add(it) }

                Log.i(TAG, "🔎 Service discovery via ${binding.name} local=$localIp targets=${targets.joinToString()}")
                DatagramSocket(null).use { socket ->
                    socket.broadcast = true
                    socket.reuseAddress = true
                    socket.soTimeout = 250
                    val routeBound = try {
                        socket.bind(InetSocketAddress(binding.local, 0))
                        true
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Could not bind discovery socket to $localIp; skipping this interface: ${e.message}")
                        false
                    }
                    if (!routeBound) return@use
                    for (target in targets) for (port in ports) {
                        if (SystemClock.elapsedRealtime() >= deadline) break
                        try { socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(target), port)) } catch (_: Exception) {}
                    }
                    while (SystemClock.elapsedRealtime() < deadline) {
                        try {
                            val packet = DatagramPacket(ByteArray(1024), 1024)
                            socket.receive(packet)
                            parseServiceDiscoveryResponse(String(packet.data, 0, packet.length, Charsets.UTF_8).trim())?.let { discovered ->
                                val sourceIp = packet.address?.hostAddress?.trim().orEmpty()
                                val effectiveIp = if (sourceIp.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}"))) sourceIp else discovered.ip
                                Log.i(TAG, "🎉 Service discovery found $effectiveIp:${discovered.port} via ${binding.name}")
                                return@withContext discovered.copy(ip = effectiveIp)
                            }
                        } catch (_: SocketTimeoutException) {}
                        catch (_: Exception) { break }
                    }
                }
            }
            null
        }


    private fun parseServiceDiscoveryResponse(response: String): ServiceDiscoveredServer? {
        if (!response.startsWith("WIFI_AUDIO_SERVER:")) return null

        val body = response.substringBefore('|')
        val parts = body.split(':', limit = 5)
        if (parts.size < 5) return null

        val ip = parts[1].trim()
        val port = parts[2].trim().toIntOrNull() ?: return null
        val name = parts[3].trim()
        val backend = parts[4].trim()

        return if (ip.isNotBlank() && port > 0) {
            ServiceDiscoveredServer(ip, port, name, backend)
        } else {
            null
        }
    }

    private fun resumeStreamingFromNotification() {
        isUserPaused = false
        resumeAvailable = false
        reconnectAvailable = false
        isPausedState = false
        isUserDisconnected = false
        hasStopped = false
        isReconnectingState = true

        getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("user_paused", false)
            .putBoolean("user_disconnected", false)
            .putBoolean("notification_reconnect", false)
            .apply()

        if (isConnected || isCurrentlyConnecting) {
            updateNotification("🎧 Streaming…")
            return
        }

        if (!bindCurrentModeNetworkNow()) {
            publishTransportUnavailable()
            return
        }
        connectionWaitingForTransport = false

        sendBroadcast(
            Intent("com.example.wifiaudiospeaker.ACTION_UI_RECONNECTING")
                .setPackage(packageName)
                .putExtra("delay_seconds", 0L)
                .putExtra("attempt", 1)
                .putExtra("max", maxReconnectAttempts)
        )

        // IMPORTANT: notification Resume/ReConnect is an explicit USER action.
        // It must be allowed to perform one connection attempt even when the
        // persistent auto-reconnect preference is OFF. After a failed attempt,
        // the normal failure controller remains gated by autoReconnectEnabled.
        scheduleInitialServiceConnection(forceManual = true)
    }

    /**
     * Network route changed while persistent auto-reconnect is OFF.
     *
     * Keep the foreground Service alive, tear down the stale native session,
     * and expose a Service-owned ReConnect action. MainActivity is never
     * launched/relaunched by this path. Tapping ReConnect uses the existing
     * ACTION_NOTIFICATION_RESUME PendingIntent and therefore works even when
     * the Activity process/UI is not present.
     */
    private fun makeNetworkRouteReconnectAvailable() {
        if (isUserPaused || isUserDisconnected || hasStopped) {
            return
        }
        if (!(isConnected || isCurrentlyConnecting || shouldStream || sessionWasConnected)) {
            return
        }

        networkReconnectJob?.cancel()
        networkReconnectJob = serviceIoScope.launch {
            val owningJob = currentCoroutineContext()[Job]
            try {
                Log.i(TAG, "🌐 Network route changed while auto-reconnect is OFF — preparing manual ReConnect")

                // Stop the old socket/native receiver exactly once. Do not stop
                // the foreground Service and do not create an automatic retry.
                stopStreaming(
                    forceDisconnected = false,
                    notifyUi = false,
                    cancelReconnect = true
                )

                hasStopped = false
                isUserDisconnected = false
                isUserPaused = false
                isReconnectingState = false
                reconnectAvailable = true
                resumeAvailable = false
                sessionWasConnected = false

                // The old endpoint may belong to the previous subnet. ReConnect
                // will re-discover when AUTO mode is selected. Manual mode keeps
                // the saved endpoint as a fallback.
                if (autoConnectMode) {
                    targetIp = ""
                    targetPort = 0
                    currentServerAddress = ""
                }

                getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("user_paused", false)
                    .putBoolean("user_disconnected", false)
                    .putBoolean("notification_reconnect", true)
                    .putBoolean("session_was_connected", false)
                    .apply()

                updateNotification("🌐 Network changed • Tap ReConnect")
                sendUiCommand(ACTION_UI_RESET, forceDisconnected = false, userRequested = false)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to prepare manual network ReConnect: ${e.message}", e)
                reconnectAvailable = true
                resumeAvailable = false
                updateNotification("🌐 Network changed • Tap ReConnect")
            } finally {
                if (networkReconnectJob === owningJob) networkReconnectJob = null
            }
        }
    }

    private fun scheduleNetworkRouteReconnect() {

        if (
            isUserPaused ||
            isUserDisconnected ||
            hasStopped ||
            !autoReconnectEnabled
        ) {
            return
        }

        // Existing reconnect controller already has work pending.
        // Do not create another reconnect chain.
        if (reconnectJob?.isActive == true) {

            Log.d(
                TAG,
                "🛡️ Network route change: existing reconnectJob " +
                        "already active - ignoring duplicate trigger"
            )

            return
        }

        // A route-change event itself may fire more than once during
        // Wi-Fi/USB transition. Collapse those events into one delayed
        // hand-off to handleConnectionFailure().
        if (networkReconnectJob?.isActive == true) {

            Log.d(
                TAG,
                "🛡️ Network route reconnect already pending"
            )

            return
        }

        // Teardown can wait for the native receiver thread to finish.
        // Run the route-change handoff off the Service main thread so a
        // slow native pthread_join() cannot block notification/UI work.
        networkReconnectJob =
            serviceIoScope.launch {
                val owningJob = currentCoroutineContext()[Job]
                try {

                    // Allow Android a short moment to finish installing
                    // the replacement route before touching the UDP socket.
                    delay(50L)

                    if (
                        isUserPaused ||
                        hasStopped ||
                        !autoReconnectEnabled
                    ) {
                        return@launch
                    }

                    // Another failure may already have scheduled the normal
                    // reconnect during this 500 ms settling period.
                    if (reconnectJob?.isActive == true) {

                        Log.d(
                            TAG,
                            "🛡️ Route stabilized but normal reconnect " +
                                    "is already scheduled"
                        )

                        return@launch
                    }

                    Log.i(
                        TAG,
                        "🔄 Network route stabilized — handing control " +
                                "to Service reconnect controller"
                    )

                    if (autoConnectMode) {
                        // A saved endpoint can belong to the previous subnet.
                        // Do not let AUTO reconnect fall back to that stale
                        // address; discover again on the newly bound network.
                        targetIp = ""
                        targetPort = 0
                        Log.i(TAG, "🔎 AUTO mode: clearing previous endpoint before route-local discovery")
                    }

                    // Tear down the old native session once, then immediately
                    // start the selected-route readiness/discovery sequence. The
                    // normal retry backoff applies only if this attempt fails.
                    stopStreaming(notifyUi = false, cancelReconnect = false)
                    connectionWaitingForTransport = false
                    if (bindCurrentModeNetworkNow()) {
                        lastObservedTransportSignature = currentSelectedTransportSignature()
                        isReconnectingState = true
                        scheduleInitialServiceConnection()
                    } else {
                        connectionWaitingForTransport = true
                        publishTransportUnavailable()
                    }

                } finally {
                    if (networkReconnectJob === owningJob) networkReconnectJob = null
                }
            }
    }

    private fun handleConnectionFailure() {

        if (!isSelectedTransportReady()) {
            publishTransportUnavailable()
            return
        }

        if (isUserPaused || isUserDisconnected || hasStopped || !autoReconnectEnabled) {
            Log.i(
                TAG,
                "⏹️ Reconnect suppressed by user/service state " +
                        "(paused=$isUserPaused, disconnected=$isUserDisconnected, stopped=$hasStopped, enabled=$autoReconnectEnabled)"
            )
            return
        }

        // 🛡️ Only ONE reconnect timer may exist.
        if (reconnectJob?.isActive == true) {
            Log.d(TAG, "🛡️ Reconnect already scheduled - ignoring duplicate failure")
            return
        }

        shouldStream = false
        isConnected = false
        isConnectedState = false
        isCurrentlyConnecting = false
        isReconnectingState = true

        // Failure callers perform the single native cleanup before entering
        // this scheduler. This function only owns the reconnect state/timer.

        val delayIndex = minOf(
            currentReconnectDelay,
            reconnectDelays.size - 1
        )

        val delayMs = reconnectDelays[delayIndex]
        currentReconnectDelay++

        Log.w(
            TAG,
            "🔄 Scheduling ONE reconnection in ${delayMs}ms " +
                    "(attempt ${connectionAttempts + 1}; persistent capped backoff)"
        )

        updateNotification(
            "🔄 Reconnecting in ${delayMs / 1000}s…"
        )

        sendBroadcast(
            Intent("com.example.wifiaudiospeaker.ACTION_UI_RECONNECTING")
                .setPackage(packageName)
                .putExtra("delay_seconds", delayMs / 1000)
                .putExtra("attempt", connectionAttempts + 1)
                .putExtra("max", -1)
        )

        reconnectJob = serviceMainScope.launch {

            delay(delayMs)

            if (!autoReconnectEnabled) {
                return@launch
            }

            if (isUserPaused || isUserDisconnected || hasStopped) {
                reconnectJob = null
                isReconnectingState = false
                return@launch
            }

            if (!bindCurrentModeNetworkNow()) {
                reconnectJob = null
                publishTransportUnavailable()
                return@launch
            }

            if (isConnected || isCurrentlyConnecting) {
                Log.d(TAG, "🛡️ Reconnect skipped - connection already active")
                reconnectJob = null
                isReconnectingState = false
                return@launch
            }

            var reconnectIp = targetIp.trim()
            var reconnectPort = targetPort

            // On network switches the old server address may belong to the
            // previous subnet. Auto mode must discover again on the Service-
            // owned network before falling back to the last target.
            if (autoConnectMode) {
                updateNotification("🔍 Re-discovering WiFi Audio Server…")
                discoverServerInService()?.let { found ->
                    reconnectIp = found.ip
                    reconnectPort = found.port
                    serverDisplayName = found.name.ifBlank { "Unknown" }
                    serverBackendName = found.backend.ifBlank { "Unknown" }
                    targetIp = reconnectIp
                    targetPort = reconnectPort
                    saveServiceTargetState()
                }
            }

            if (reconnectIp.isBlank() || reconnectPort <= 0) {
                Log.w(TAG, "🛡️ Reconnect skipped - no valid target after Service discovery")
                reconnectJob = null
                handleConnectionFailure()
                return@launch
            }

            targetIp = reconnectIp
            targetPort = reconnectPort
            currentServerAddress = "$reconnectIp:$reconnectPort"

            Log.i(
                TAG,
                "🔄 Executing SINGLE Service-owned auto-reconnection to $reconnectIp:$reconnectPort"
            )

            reconnectJob = null
            startNativeStreaming(
                reconnectIp,
                reconnectPort,
                jitterBufferMs
            )
        }
    }

    fun pauseByUser() {

        Log.i(
            TAG,
            "⏸️ User requested STOP - ending current stream but keeping foreground service alive"
        )

        isUserPaused = true
        resumeAvailable = true
        reconnectAvailable = false
        isPausedState = true
        isReconnectingState = false
        isUserDisconnected = true
        sessionWasConnected = false

        initialConnectJob?.cancel()
        initialConnectJob = null
        networkReconnectJob?.cancel()
        networkReconnectJob = null
        transportWatchJob?.cancel()
        transportWatchJob = null
        reconnectJob?.cancel()
        reconnectJob = null

        getSharedPreferences(
            "wifi_audio_state",
            Context.MODE_PRIVATE
        )
            .edit()
            .putBoolean("user_paused", true)
            .putBoolean("user_disconnected", true)
            .putBoolean("notification_reconnect", false)
            .putBoolean("session_was_connected", false)
            .apply()

        // Stop the network/audio session.
        // The foreground service itself remains alive.
        stopStreaming(
            forceDisconnected = false,
            notifyUi = false,
            cancelReconnect = true
        )

        updateNotification(
            "⏸️ Stopped • Tap Resume to reconnect"
        )

        sendUiCommand(
            ACTION_UI_RESET,
            forceDisconnected = false,
            userRequested = true
        )
    }


    private fun stopTelemetryThread() {
        telemetryGeneration.incrementAndGet()

        try { telemetryThread?.interrupt() } catch (_: Exception) {}
        try { telemetrySocketRef?.close() } catch (_: Exception) {}

        telemetryThread = null
        telemetrySocketRef = null
    }

    fun stopStreaming(forceDisconnected: Boolean = false, notifyUi: Boolean = true,cancelReconnect: Boolean = true) {

        if (hasStopped && cancelReconnect) {
            Log.d(TAG, "🛑 stopStreaming() invoked — already stopped")
            return
        }
        hasStopped = cancelReconnect

        Log.d(TAG, "🛑 Stopping streaming")

        // Telemetry and PC-state polling are ancillary and must never outlive
        // the audio session. In particular, invalidate them before a rapid
        // reconnect can turn isConnected back to true for the next session.
        stopTelemetryThread()
        stopPcStateSync()

        shouldStream = false
        isConnected = false
        isConnectedState = false
        if (cancelReconnect || forceDisconnected) {
            isReconnectingState = false
        }
        currentServerAddress = "" // Clear server address

        // Keep the last acknowledged identity across an automatic network
        // transition so the UI can show which PC is being recovered. A deliberate
        // full stop clears it; the next successful handshake always replaces it.
        if (cancelReconnect || forceDisconnected || isUserDisconnected) {
            serverDisplayName = "Unknown"
            serverBackendName = "Unknown"
        }

        if(cancelReconnect) {
            connectionAttempts = 0
            currentReconnectDelay = 0
            reconnectJob?.cancel()
        }

        // Cancel all jobs
        if (cancelReconnect){
            // Intentional stop / explicit teardown.
            streamJob?.cancel()
            monitorJob?.cancel()


            streamJob = null
            reconnectJob = null
            monitorJob = null
        } else{
            // Failure cleanup:
            // do NOT cancel the currently executing stream coroutine,
            // because it must be allowed to reach handleConnectionFailure().
            monitorJob?.cancel()
            monitorJob = null
        }


        // 🎯 Serialize native + AudioTrack teardown. Both the route callback
        // and the streaming coroutine can reach this method almost together.
        // The old build occasionally performed two pthread_join() calls on the
        // same native thread, producing "Invalid argument".
        synchronized(nativeLifecycleLock) {
            if (!nativeDisconnectIssued) {
                try {
                    nativeSendDisconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ nativeSendDisconnect failed during stop: ${e.message}")
                }
                nativeDisconnectIssued = true
            }

            if (!nativeStopIssued) {
                try {
                    nativeServiceStopStreaming()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ nativeServiceStopStreaming failed during stop: ${e.message}")
                }
                nativeStopIssued = true
            }

            try {
                audioTrack?.let { track ->
                    try {
                        track.pause()
                    } catch (_: Throwable) {
                    }

                    try {
                        track.flush()
                    } catch (_: Throwable) {
                    }

                    try {
                        track.stop()
                    } catch (e: Throwable) {
                        Log.w(TAG, "⚠️ AudioTrack stop skipped safely: ${e.message}")
                    } finally {
                        try {
                            track.release()
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "⚠️ AudioTrack cleanup skipped safely: ${e.message}")
            } finally {
                audioTrack = null
            }

            abandonPlaybackAudioFocus()
        }


        if (notifyUi && (cancelReconnect || forceDisconnected)) {
            val text = if (forceDisconnected) "❌ Disconnected by Server" else "⏹️ Stopped"
            updateNotification(text)

            sendUiCommand(ACTION_UI_RESET, forceDisconnected)
        }

        Log.d(TAG, "✅  Streaming stopped and cleaned up completely")
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        }
    }

    override fun onDestroy() {

        // 🛑 First invalidate ALL Service-owned coroutines. The previous build
        // allowed reconnectJob to survive past Service destruction.
        initialConnectJob?.cancel()
        initialConnectJob = null
        networkReconnectJob?.cancel()
        networkReconnectJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        pcStateSyncJob?.cancel()
        pcStateSyncJob = null
        monitorJob?.cancel()
        monitorJob = null
        streamJob?.cancel()
        streamJob = null

        serviceSupervisor.cancel()

        try {
            abandonPlaybackAudioFocus()
        } catch (_: Throwable) {
        }

        try {
            networkCallback?.let { callback ->
                val cm =
                    getSystemService(Context.CONNECTIVITY_SERVICE)
                            as ConnectivityManager
                cm.unregisterNetworkCallback(callback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to unregister Service network callback: ${e.message}")
        }

        networkCallback = null
        boundNetwork = null
        boundNetworkHandle = -1L
        networkMonitoringInitialized = false

        try {
            val cm =
                getSystemService(Context.CONNECTIVITY_SERVICE)
                        as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cm.bindProcessToNetwork(null)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ConnectivityManager.setProcessDefaultNetwork(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to clear process network binding: ${e.message}")
        }

        isServiceRunning = false
        instance = null
        isConnected = false
        isConnectedState = false
        isReconnectingState = false
        isPausedState = false
        shouldStream = false

        Log.i(TAG, "AudioStreamingService Destroyed")

        stopStreaming(notifyUi = false, cancelReconnect = true)
        nativeServiceCleanup()
        hideForegroundNotification()

        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "🧹 App swiped from recents! Trying to keep audio alive...  on $deviceName" )
        // Do NOT call stopSelf(). Let the foreground service stay alive.
    }
}
