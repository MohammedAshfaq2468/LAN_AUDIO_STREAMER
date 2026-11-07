package com.example.wifiaudiospeaker

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

class SetupActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_UI_CONNECTED = "com.example.wifiaudiospeaker.ACTION_UI_CONNECTED"
        private const val ACTION_UI_RECONNECTING = "com.example.wifiaudiospeaker.ACTION_UI_RECONNECTING"
        private const val ACTION_UI_TRANSPORT_UNAVAILABLE = "com.example.wifiaudiospeaker.ACTION_UI_TRANSPORT_UNAVAILABLE"
        private const val ACTION_SETUP_AUTO_CONNECT = "com.example.wifiaudiospeaker.ACTION_SETUP_AUTO_CONNECT"
        private const val PREFS = "wifi_audio_state"
        private const val READY_MESSAGE = "Please turn on Wi-Fi or enable USB tethering to start streaming"
    }

    private lateinit var tvDeviceName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvRadarCaption: TextView
    private lateinit var radarRingOuter: View
    private lateinit var radarRingMiddle: View
    private lateinit var radarRingInner: View
    private lateinit var radarCenterIcon: TextView
    private lateinit var radarCenterImage: ImageView
    private lateinit var rgTransport: RadioGroup
    private lateinit var rbWireless: RadioButton
    private lateinit var rbWired: RadioButton
    private lateinit var rgMode: RadioGroup
    private lateinit var rbAuto: RadioButton
    private lateinit var rbManual: RadioButton
    private lateinit var btnScanQr: Button
    private lateinit var btnAutoDiscover: Button
    private lateinit var btnManualConnect: Button
    private lateinit var btnOpenNetworkSettings: Button
    private lateinit var manualPanel: View
    private lateinit var etIp: EditText
    private lateinit var etPort: EditText

    private lateinit var btnClearEndpoint : Button
    private lateinit var rbSetupHomeWifiPath: RadioButton
    private lateinit var rbSetupPcHotspotPath: RadioButton
    private lateinit var rbSetupMobileHotspotPath: RadioButton
    private lateinit var rbSetupUsbTetherPath: RadioButton
    private lateinit var tvSetupWirelessGateway: TextView

    private var selectedMode = "wireless"
    private var selectedAutoMode = true
    private var forceAutoDiscovery = false
    private var networkChanged = false
    private val radarAnimators = mutableListOf<AnimatorSet>()
    private var wiredRadarAnimator: AnimatorSet? = null

    // SetupActivity is a live network-topology screen. ConnectivityManager
    // callbacks catch normal route changes, while this lightweight foreground
    // poll catches OEM SoftAP/USB interfaces that are created without exposing
    // a corresponding Network callback. It is stopped as soon as Setup leaves
    // the foreground, so it never becomes a background scanner.
    private val topologyHandler = Handler(Looper.getMainLooper())
    private var topologyCallback: ConnectivityManager.NetworkCallback? = null
    private var topologyPoll: Runnable? = null
    private var transportWaitRunnable: Runnable? = null

    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.trim()?.let { handleQr(it) }
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) requestCameraAndScan()
            else tvStatus.text = "📷 Camera permission is required for QR Connect"
        }

    private val uiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UI_CONNECTED -> openDashboard()
                ACTION_UI_RECONNECTING -> {
                    tvStatus.text = "🔄 Connecting… ${intent.getLongExtra("delay_seconds", 2)}s"
                    setRadarState(RadarState.CONNECTING)
                    syncSetupControls()
                }
                ACTION_UI_TRANSPORT_UNAVAILABLE -> {
                    tvStatus.text = READY_MESSAGE
                    setRadarState(RadarState.FAILED)
                    syncSetupControls()
                }
            }
        }
    }

    private enum class RadarState { IDLE, DISCOVERING, MANUAL_READY, CONNECTING, CONNECTED, DISCONNECTED, FAILED }

    private fun readStringPrefCompat(
        prefs: android.content.SharedPreferences,
        key: String,
        defaultValue: String = ""
    ): String {
        return try {
            prefs.getString(key, defaultValue) ?: defaultValue
        } catch (_: ClassCastException) {
            // Older builds stored server_port as an Int. Read legacy values
            // without crashing the SetupActivity.
            try {
                prefs.getInt(key, defaultValue.toIntOrNull() ?: 0).toString()
            } catch (_: Exception) {
                defaultValue
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        tvDeviceName = findViewById(R.id.setupDeviceName)
        tvStatus = findViewById(R.id.setupStatus)
        tvRadarCaption = findViewById(R.id.setupRadarCaption)
        radarRingOuter = findViewById(R.id.setupRadarRingOuter)
        radarRingMiddle = findViewById(R.id.setupRadarRingMiddle)
        radarRingInner = findViewById(R.id.setupRadarRingInner)
        radarCenterIcon = findViewById(R.id.setupRadarCenterIcon)
        radarCenterImage = findViewById(R.id.setupRadarCenterImage)
        rbWireless = findViewById(R.id.rbSetupWireless)
        rbWired = findViewById(R.id.rbSetupWired)
        rgTransport = findViewById(R.id.rgSetupTransport)
        rbAuto = findViewById(R.id.rbSetupAuto)
        rbManual = findViewById(R.id.rbSetupManual)
        rgMode = findViewById(R.id.rdGrpSetupMode)
        btnScanQr = findViewById(R.id.btnSetupScanQr)
        btnAutoDiscover = findViewById(R.id.btnSetupAutoDiscover)
        btnManualConnect = findViewById(R.id.btnSetupManualConnect)
        btnOpenNetworkSettings = findViewById(R.id.btnSetupOpenNetworkSettings)
        manualPanel = findViewById(R.id.setupManualPanel)
        etIp = findViewById(R.id.etIp)
        etPort = findViewById(R.id.etPort)
        btnClearEndpoint = findViewById(R.id.btnClearEndpoint)
        rbSetupHomeWifiPath = findViewById(R.id.rbSetupHomeWifiPath)
        rbSetupPcHotspotPath = findViewById(R.id.rbSetupPcHotspotPath)
        rbSetupMobileHotspotPath = findViewById(R.id.rbSetupMobileHotspotPath)
        rbSetupUsbTetherPath = findViewById(R.id.rbSetupUsbTetherPath)
        tvSetupWirelessGateway = findViewById(R.id.tvSetupWirelessGateway)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        selectedMode = if (prefs.getString("streaming_mode", "wireless") == "wired") "wired" else "wireless"
        selectedAutoMode = prefs.getBoolean("auto_mode", true)
        networkChanged = intent.getBooleanExtra("NETWORK_CHANGED", false)
        forceAutoDiscovery = intent.getBooleanExtra("FORCE_AUTO_DISCOVERY", false)
        if (forceAutoDiscovery) selectedAutoMode = true

        tvDeviceName.text = DeviceNameHelper.getDeviceName(this)
        // Never expose the previously saved endpoint merely by opening Setup.
        // It remains stored in preferences and can be explicitly reused via the prompt.
        etPort.setText("")
        etIp.setText("")
        rbWireless.isChecked = selectedMode == "wireless"
        rbWired.isChecked = selectedMode == "wired"
        rbAuto.isChecked = selectedAutoMode
        rbManual.isChecked = !selectedAutoMode

        rgTransport.setOnCheckedChangeListener { _, id ->
            selectedMode = if (id == R.id.rbSetupWired) "wired" else "wireless"
            saveSelection()
            syncRadarCenterForMode()
            updateTransportStatus()

            // Selecting an unavailable transport opens its matching phone
            // settings immediately; no connection is started until the route
            // is actually usable.
            if (!isSelectedTransportReady()) {
                openSelectedTransportSettings()
            }
        }

        rgMode.setOnCheckedChangeListener { _, id ->
            selectedAutoMode = id == R.id.rbSetupAuto
            saveSelection()
            refreshConnectionMethodUi()
            setRadarState(if (selectedAutoMode) RadarState.IDLE else RadarState.MANUAL_READY)
            updateTransportStatus()
            if (!selectedAutoMode && !networkChanged) {
                promptSavedEndpointForManualMode()
            }
        }

        btnScanQr.setOnClickListener { requestCameraAndScan() }
        btnAutoDiscover.setOnClickListener { startAutoDiscovery() }
        btnManualConnect.setOnClickListener { startManual() }
        btnOpenNetworkSettings.setOnClickListener { openSelectedTransportSettings() }
        btnClearEndpoint.setOnClickListener {
            etIp.setText("")
            etPort.setText("")

            return@setOnClickListener

        }


        refreshConnectionMethodUi()
        if (forceAutoDiscovery) {
            rbAuto.isChecked = true
            rbManual.isChecked = false
            tvStatus.text = "🌐 Network changed — Auto Discover on the current network"
            tvRadarCaption.text = "Auto Discover recommended"
        }
        syncRadarCenterForMode()
        updateTransportStatus()
        updateLiveTopologyCard()
        syncSetupControls()

        if (forceAutoDiscovery) {
            // Give the Setup screen one frame to render so the user sees the
            // explicit Auto mode / Auto Discover state before discovery starts.
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed && !AudioStreamingService.isConnectedState) {
                    startAutoDiscovery()
                }
            }, 250L)
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, uiReceiver, IntentFilter().apply {
            addAction(ACTION_UI_CONNECTED)
            addAction(ACTION_UI_RECONNECTING)
            addAction(ACTION_UI_TRANSPORT_UNAVAILABLE)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        registerTopologyMonitoring()
        startTopologyPolling()
        if (AudioStreamingService.isConnectedState) openDashboard()
    }

    override fun onResume() {
        super.onResume()
        if (!::tvStatus.isInitialized) return
        refreshConnectionMethodUi()
        syncRadarCenterForMode()
        updateTransportStatus()
        updateLiveTopologyCard()
        syncSetupControls()
        startTopologyPolling()
    }

    override fun onStop() {
        transportWaitRunnable?.let { topologyHandler.removeCallbacks(it) }
        transportWaitRunnable = null
        stopTopologyPolling()
        unregisterTopologyMonitoring()
        try { unregisterReceiver(uiReceiver) } catch (_: Exception) {}
        stopRadarAnimations()
        super.onStop()
    }

    override fun onDestroy() {
        stopRadarAnimations()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!::tvStatus.isInitialized) return
        if (hasFocus) {
            syncRadarCenterForMode()
        } else {
            stopRadarAnimations()
        }
    }

    private fun saveSelection() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("streaming_mode", selectedMode)
            .putBoolean("auto_mode", selectedAutoMode)
            .apply()

        // If the persistent Service is already alive (for example while it is
        // waiting for Wi-Fi or USB tethering), immediately update its selected
        // transport/method so its next network callback uses the new pipeline.
        if (AudioStreamingService.isServiceRunning) {
            try {
                val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val autoReconnect = prefs.getBoolean(
                    "auto_reconnect",
                    prefs.getBoolean("auto_connect", true)
                )
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, AudioStreamingService::class.java).apply {
                        action = AudioStreamingService.ACTION_SETUP_CONFIG_UPDATE
                        putExtra("AUTO_RECONNECT", autoReconnect)
                        putExtra("AUTO_MODE", selectedAutoMode)
                        putExtra("IS_WIRED", selectedMode == "wired")
                    }
                )
            } catch (e: Exception) {
                android.util.Log.w("SetupActivity", "⚠️ Could not update Service transport selection: ${e.message}")
            }
        }
    }

    private fun requestCameraAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        qrLauncher.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Point camera at the WiFi Audio Server QR Code")
            setCameraId(0)
            setBeepEnabled(false)
            setBarcodeImageEnabled(false)
            setOrientationLocked(true)
            setCaptureActivity(CustomScannerActivity::class.java)
            addExtra("SCAN_TYPE", 2)
            addExtra("TRY_HARDER", true)
        })
    }

    private data class QrIdentity(val ip: String, val port: Int, val name: String, val backend: String)

    private fun parseQr(data: String): QrIdentity? {
        if (!data.startsWith("WIFI_AUDIO_SERVER:")) return null
        val core = data.substringBefore("|").split(":")
        if (core.size < 5) return null
        val ip = core[1].trim()
        val port = core[2].trim().toIntOrNull() ?: return null
        if (ip.isBlank() || port <= 0) return null
        return QrIdentity(ip, port, core[3].trim().ifBlank { "Unknown" }, core[4].trim().ifBlank { "Unknown" })
    }

    private fun handleQr(data: String) {
        val identity = parseQr(data)
        if (identity == null) {
            tvStatus.text = "❌ Invalid WiFi Audio Server QR"
            setRadarState(RadarState.FAILED)
            return
        }
        selectedAutoMode = false
        rbManual.isChecked = true
        rbAuto.isChecked = false
        saveSelection()
        refreshConnectionMethodUi()
        etIp.setText(identity.ip)
        etPort.setText(identity.port.toString())
        tvStatus.text = "📷 QR loaded: ${identity.name}"
        setRadarState(RadarState.CONNECTING)
        startConnection(identity.ip, identity.port, identity.name, identity.backend, false)
    }

    private fun startManual() {
        val ip = etIp.text.toString().trim()
        val port = etPort.text.toString().trim().toIntOrNull() ?: 0
        if (ip.isBlank() || port <= 0) {
            tvStatus.text = "❌ Enter a valid server IP and port"
            Toast.makeText(this , "❌ Enter a valid server IP and port", Toast.LENGTH_SHORT).show()
            return
        }
        startConnection(ip, port, "Unknown", "Unknown", false)
    }

    private fun waitForSelectedTransport(onReady: () -> Unit) {
        if (isSelectedTransportReady()) {
            onReady()
            return
        }

        // OEMs can report USB tethering in Settings before rndis0/usb0/eth* has
        // an IPv4 address. Do not make the user press Auto Discover repeatedly;
        // wait briefly for the actual PC-facing interface to materialize.
        tvStatus.text = if (selectedMode == "wired") {
            "⏳ USB tethering enabled — waiting for RNDIS network…"
        } else {
            "⏳ Waiting for Wi-Fi / hotspot network…"
        }
        tvRadarCaption.text = "Waiting for selected transport…"
        setRadarState(RadarState.CONNECTING)
        syncSetupControls()

        transportWaitRunnable?.let { topologyHandler.removeCallbacks(it) }
        val deadline = SystemClock.elapsedRealtime() + 10000L
        val poll = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                if (isSelectedTransportReady()) {
                    transportWaitRunnable = null
                    onReady()
                    return
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    transportWaitRunnable = null
                    showTransportUnavailable()
                    return
                }
                topologyHandler.postDelayed(this, 350L)
            }
        }
        transportWaitRunnable = poll
        topologyHandler.post(poll)
    }

    private fun startAutoDiscovery() {
        selectedAutoMode = true
        rbAuto.isChecked = true
        rbManual.isChecked = false
        saveSelection()

        waitForSelectedTransport {
            if (isFinishing || isDestroyed) return@waitForSelectedTransport
            setRadarState(RadarState.DISCOVERING)
            tvStatus.text = if (selectedMode == "wired") {
                "🔎 Searching USB tether/RNDIS network for the server…"
            } else {
                "🔎 Searching Wi-Fi/hotspot network for the server…"
            }

            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val legacyDevice = Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1 ||
                    Build.MODEL.contains("A6020", true) || Build.DEVICE.contains("A6020", true)
            val premium = if (prefs.contains("button_premium_free")) {
                prefs.getBoolean("button_premium_free", !legacyDevice)
            } else !legacyDevice
            val bufferMs = when {
                selectedMode == "wired" -> 2
                premium -> 10
                else -> 1500
            }

            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove("pending_server_ip").apply()

            val intent = Intent(this, AudioStreamingService::class.java).apply {
                action = ACTION_SETUP_AUTO_CONNECT
                putExtra("AUTO_MODE", true)
                // Auto Discover is an explicit one-shot user request.
                putExtra("AUTO_RECONNECT", false)
                putExtra("FORCE_MANUAL_CONNECT", true)
                putExtra("IS_WIRED", selectedMode == "wired")
                putExtra("BUFFER_MS", bufferMs)
                putExtra("DEVICE_MANUFACTURER", Build.MANUFACTURER ?: "Unknown")
                putExtra("DEVICE_MODEL", Build.MODEL ?: "Unknown")
                putExtra("DEVICE_NAME", DeviceNameHelper.getDeviceName(this@SetupActivity))
            }
            ContextCompat.startForegroundService(this, intent)
            syncSetupControls()
        }
    }

    private fun startConnection(ip: String, port: Int, serverName: String, backend: String, autoMode: Boolean) {
        if (ip.isBlank() || port <= 0) {
            tvStatus.text = "❌ Enter a valid server IP and port"
            Toast.makeText(this, "❌ Enter a valid server IP and port", Toast.LENGTH_SHORT).show()
            return
        }

        selectedAutoMode = autoMode
        rbAuto.isChecked = autoMode
        rbManual.isChecked = !autoMode
        saveSelection()

        waitForSelectedTransport {
            if (isFinishing || isDestroyed) return@waitForSelectedTransport

            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("server_ip", ip)
                .putInt("server_port", port)
                .putString("server_name", serverName)
                .apply()

            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val legacyDevice = Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1 ||
                    Build.MODEL.contains("A6020", true) || Build.DEVICE.contains("A6020", true)
            val premium = if (prefs.contains("button_premium_free")) {
                prefs.getBoolean("button_premium_free", !legacyDevice)
            } else !legacyDevice
            val bufferMs = when {
                selectedMode == "wired" -> 2
                premium -> 10
                else -> 1500
            }

            tvStatus.text = "🔄 Connecting to $ip:$port…"
            setRadarState(RadarState.CONNECTING)
            val persistentAutoReconnect = prefs.getBoolean(
                "auto_reconnect",
                prefs.getBoolean("auto_connect", true)
            )
            val intent = Intent(this, AudioStreamingService::class.java).apply {
                putExtra("IP", ip)
                putExtra("PORT", port)
                putExtra("SERVER_NAME", serverName)
                putExtra("SERVER_BACKEND", backend)
                putExtra("AUTO_RECONNECT", persistentAutoReconnect)
                putExtra("AUTO_MODE", autoMode)
                putExtra("IS_WIRED", selectedMode == "wired")
                putExtra("BUFFER_MS", bufferMs)
                putExtra("DEVICE_MANUFACTURER", Build.MANUFACTURER ?: "Unknown")
                putExtra("DEVICE_MODEL", Build.MODEL ?: "Unknown")
                putExtra("DEVICE_NAME", DeviceNameHelper.getDeviceName(this@SetupActivity))
            }
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun updateLiveTopologyCard() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // The transport selector is authoritative for this card.  In
            // particular, selecting Wired means "show me the USB tether path",
            // not "show me whatever provides the phone's Internet upstream".
            // A phone can legitimately have Wi-Fi Internet enabled while its
            // PC connection is RNDIS/ECM/NCM over USB.
            if (selectedMode == "wired") {
                val usb = findActiveUsbInterface()
                rbSetupHomeWifiPath.isChecked = false
                rbSetupPcHotspotPath.isChecked = false
                rbSetupMobileHotspotPath.isChecked = false
                rbSetupUsbTetherPath.isChecked = usb != null

                if (usb != null) {
                    val (iface, localIp) = usb
                    tvSetupWirelessGateway.text =
                        "Current: 🔌 USB Tethering\n" +
                                "Interface: $iface • Local IP: ${localIp.ifBlank { "—" }}\n" +
                                "PC connected through USB • Wi-Fi may still provide Internet"
                } else {
                    tvSetupWirelessGateway.text =
                        "Current: 🔌 USB Tethering selected\n" +
                                "Waiting for USB/RNDIS/ECM/NCM interface…\n" +
                                "Your Wi-Fi Internet can remain ON; it is not the PC transport"
                }
                return
            }

            // Wireless mode: first prefer a real SoftAP interface or a known
            // SoftAP gateway address. Some OEMs (including devices that expose
            // the SoftAP as wlan0) do NOT use ap0/wlan1 names.
            val hotspot = findActiveMobileHotspotInterface()
            if (hotspot != null) {
                val (iface, localIp) = hotspot
                rbSetupHomeWifiPath.isChecked = false
                rbSetupPcHotspotPath.isChecked = false
                rbSetupMobileHotspotPath.isChecked = true
                rbSetupUsbTetherPath.isChecked = false
                tvSetupWirelessGateway.text =
                    "Current: 📱 Mobile Hotspot\n" +
                            "Interface: $iface • Local IP: ${localIp.ifBlank { "—" }}\n" +
                            "PC should be connected to this phone's hotspot Only"
                return
            }

            // PC-hosted Windows hotspot is identified from its well-known
            // 192.168.137.0/24 network. Otherwise a Wi-Fi transport is simply
            // the normal Home Wi-Fi/LAN path.
            val candidates = cm.allNetworks.mapNotNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                val lp = cm.getLinkProperties(network) ?: return@mapNotNull null
                val iface = lp.interfaceName.orEmpty().lowercase(Locale.US)
                val localIp = lp.linkAddresses.mapNotNull { it.address as? Inet4Address }
                    .firstOrNull()?.hostAddress.orEmpty()
                val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                    ?.gateway?.hostAddress.orEmpty()
                if (localIp.isBlank() && gateway.isBlank()) return@mapNotNull null
                if (!(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || isHotspotInterface(iface))) {
                    return@mapNotNull null
                }
                Triple(caps, iface, "$localIp|$gateway")
            }

            val selected = candidates.firstOrNull()
            val iface = selected?.second.orEmpty()
            val parts = selected?.third?.split('|') ?: listOf("", "")
            val localIp = parts.getOrNull(0).orEmpty()
            val gateway = parts.getOrNull(1).orEmpty()

            val pcHotspot = gateway == "192.168.137.1" || localIp.startsWith("192.168.137.")
            val label = if (pcHotspot) "💻 PC Hotspot" else "🏠 Home Wi-Fi / LAN"

            rbSetupHomeWifiPath.isChecked = label.startsWith("🏠")
            rbSetupPcHotspotPath.isChecked = label.startsWith("💻")
            rbSetupMobileHotspotPath.isChecked = false
            rbSetupUsbTetherPath.isChecked = false
            tvSetupWirelessGateway.text = if (localIp.isNotBlank() || gateway.isNotBlank()) {
                "Current: $label\n" +
                        "Interface: ${iface.ifBlank { "—" }} • Local IP: ${localIp.ifBlank { "—" }} • " +
                        "Gateway: ${gateway.ifBlank { "—" }}"
            } else {
                "Current: 🛜 Wireless selected\nScanning Wi-Fi / PC hotspot topology…"
            }
        } catch (e: Exception) {
            tvSetupWirelessGateway.text = "Topology unavailable (${e.message ?: "unknown"})"
        }
    }

    private fun isUsbInterface(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        // Do NOT include rmnet*: that is the cellular modem/data path, not the
        // PC-facing USB tether interface. Also do not treat wlan/wifi as USB
        // merely because the physical Wi-Fi adapter itself is a USB device.
        if (n.contains("wlan") || n.contains("wifi") || n.contains("802.11")) return false
        return n.contains("rndis") || n.contains("usb0") || n.contains("usbnet") ||
                n.contains("ecm") || n.contains("ncm") || n.contains("tether") ||
                n.contains("cdc") || n == "eth0" || n.startsWith("eth")
    }

    private fun isHotspotInterface(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.contains("ap0") || n.contains("softap") || n.contains("swlan") ||
                n.contains("wlan1") || n.contains("wlan2") || n.contains("tether") ||
                n.contains("hotspot")
    }

    private fun isMobileHotspotAddress(localIp: String, gateway: String): Boolean {
        val ranges = listOf(
            "192.168.43.", "192.168.234.", "192.168.44.", "192.168.45.",
            "192.168.49.", "192.168.6.", "10.185.37.", "10.0.0.", "172.20.10."
        )
        return ranges.any { localIp.startsWith(it) || gateway.startsWith(it) }
    }

    private fun findActiveUsbInterface(): Pair<String, String>? {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces()).firstNotNullOfOrNull { iface ->
                val name = iface.name.lowercase(Locale.US)
                val up = try { iface.isUp } catch (_: Exception) { false }
                if (!up || !isUsbInterface(name)) return@firstNotNullOfOrNull null
                val ip = Collections.list(iface.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress.orEmpty()
                if (ip.isBlank()) null else iface.name to ip
            }
        } catch (_: Exception) { null }
    }

    private fun hasActiveMobileHotspotInterface(): Boolean =
        findActiveMobileHotspotInterface() != null

    private fun findActiveMobileHotspotInterface(): Pair<String, String>? {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces()).firstNotNullOfOrNull { iface ->
                val name = iface.name.lowercase(Locale.US)
                val up = try { iface.isUp } catch (_: Exception) { false }
                if (!up) return@firstNotNullOfOrNull null
                val ip = Collections.list(iface.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress.orEmpty()
                if (ip.isBlank()) return@firstNotNullOfOrNull null
                if (isHotspotInterface(name) || isMobileHotspotAddress(ip, "")) {
                    iface.name to ip
                } else null
            }
        } catch (_: Exception) { null }
    }

    private fun isSelectedTransportReady(): Boolean {
        return try {
            if (selectedMode == "wireless") {
                val wm = getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val wifiReady = wm.isWifiEnabled && currentNetworkMatchesMode()
                val hotspotReady = hasActiveMobileHotspotInterface()
                wifiReady || hotspotReady
            } else {
                getLikelyTetherInterface() != null || currentNetworkMatchesMode()
            }
        } catch (_: Exception) { false }
    }

    private fun currentNetworkMatchesMode(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.any { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@any false
                val iface = cm.getLinkProperties(network)?.interfaceName?.lowercase(Locale.US).orEmpty()
                if (selectedMode == "wireless") {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || isHotspotInterface(iface)
                } else {
                    val tetherInterface = isUsbInterface(iface)
                    val tetherTransport =
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    // Wired mode is valid only on the actual USB tether/RNDIS
                    // network; ordinary Wi-Fi must never satisfy this test.
                    tetherTransport && tetherInterface
                }
            }
        } catch (_: Exception) { false }
    }


    private fun promptSavedEndpointForManualMode() {
        if (etIp.text?.isNotBlank() == true || etPort.text?.isNotBlank() == true) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ip = prefs.getString("server_ip", prefs.getString("last_ip", ""))?.trim().orEmpty()
        val port = try { prefs.getInt("server_port", 0) } catch (_: ClassCastException) { 0 }
            .takeIf { it > 0 } ?: prefs.getString("last_port", "")?.toIntOrNull().orZero()
        if (ip.isBlank() || port <= 0) return
        AlertDialog.Builder(this)
            .setTitle("Use last connected server?")
            .setMessage("Use the saved server $ip:$port for this manual connection?")
            .setPositiveButton("Yes") { _, _ ->
                etIp.setText(ip)
                etPort.setText(port.toString())
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun getLikelyTetherInterface(): NetworkInterface? {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces()).firstOrNull { iface ->
                val name = iface.name.lowercase(Locale.US)
                val up = try { iface.isUp } catch (_: Exception) { false }
                up && isUsbInterface(name) &&
                        Collections.list(iface.inetAddresses).any {
                            it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress
                        }
            }
        } catch (_: Exception) { null }
    }

    private fun registerTopologyMonitoring() {
        try {
            unregisterTopologyMonitoring()
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = android.net.NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            topologyCallback = object : ConnectivityManager.NetworkCallback() {
                private fun refresh() {
                    topologyHandler.post {
                        if (!isFinishing && !isDestroyed) {
                            updateLiveTopologyCard()
                            updateTransportStatus()
                            syncSetupControls()
                        }
                    }
                }
                override fun onAvailable(network: android.net.Network) = refresh()
                override fun onLost(network: android.net.Network) = refresh()
                override fun onCapabilitiesChanged(network: android.net.Network, networkCapabilities: NetworkCapabilities) = refresh()
                override fun onLinkPropertiesChanged(network: android.net.Network, linkProperties: android.net.LinkProperties) = refresh()
            }
            cm.registerNetworkCallback(request, topologyCallback!!)
        } catch (e: Exception) {
            android.util.Log.w("SetupActivity", "⚠️ Topology callback registration failed: ${e.message}")
        }
    }

    private fun unregisterTopologyMonitoring() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            topologyCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        topologyCallback = null
    }

    private fun startTopologyPolling() {
        stopTopologyPolling()
        topologyPoll = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                updateLiveTopologyCard()
                updateTransportStatus()
                syncSetupControls()
                topologyHandler.postDelayed(this, 600L)
            }
        }
        topologyHandler.post(topologyPoll!!)
    }

    private fun stopTopologyPolling() {
        topologyPoll?.let { topologyHandler.removeCallbacks(it) }
        topologyPoll = null
    }

    private fun showTransportUnavailable() {
        tvStatus.text = READY_MESSAGE
        tvRadarCaption.text = "Transport not ready"
        tvRadarCaption.setTextColor(Color.RED)
        setRadarState(RadarState.FAILED)
        syncSetupControls()
    }

    private fun updateTransportStatus() {
        if (AudioStreamingService.isConnectedState) return
        tvStatus.text = when {
            selectedMode == "wireless" && isSelectedTransportReady() -> if (hasActiveMobileHotspotInterface()) "📱 Mobile Hotspot is ON — Wireless transport ready" else "🛜 Wi-Fi is ON — Wireless transport ready"
            selectedMode == "wired" && isSelectedTransportReady() -> "🔌 USB tethering is ON — Wired transport ready"
            else -> READY_MESSAGE
        }
    }

    private fun refreshConnectionMethodUi() {
        manualPanel.visibility = if (selectedAutoMode) View.GONE else View.VISIBLE
        btnAutoDiscover.visibility = if (selectedAutoMode) View.VISIBLE else View.GONE
        btnManualConnect.visibility = if (selectedAutoMode) View.GONE else View.VISIBLE
    }

    private fun syncSetupControls() {
        val locked = AudioStreamingService.isConnectedState || AudioStreamingService.isReconnectingState
        rgTransport.isEnabled = !locked
        rgMode.isEnabled = !locked
        rbWireless.isEnabled = !locked
        rbWired.isEnabled = !locked
        rbAuto.isEnabled = !locked
        rbManual.isEnabled = !locked
        btnScanQr.isEnabled = !locked
        btnAutoDiscover.isEnabled = !locked
        btnManualConnect.isEnabled = !locked
        rgTransport.alpha = if (locked) 0.45f else 1f
        rgMode.alpha = if (locked) 0.45f else 1f
    }

    private fun openSelectedTransportSettings() {
        try {
            startActivity(if (selectedMode == "wireless") Intent(Settings.ACTION_WIFI_SETTINGS) else Intent("android.settings.TETHER_SETTINGS"))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun syncRadarCenterForMode() {
        val wired = selectedMode == "wired"
        radarCenterIcon.visibility = if (wired) View.GONE else View.VISIBLE
        radarCenterImage.visibility = if (wired) View.VISIBLE else View.GONE
        if (wired) {
            stopLanRadarAnimation()
            startWiredRadarAnimation()
        } else {
            stopWiredRadarAnimation()
            startLanRadarAnimation()
        }
    }

    private fun setRadarState(state: RadarState) {
        when (state) {
            RadarState.IDLE -> {
                tvRadarCaption.text = "Idle"
                tvRadarCaption.setTextColor(Color.LTGRAY)
                if (!isFinishing) syncRadarCenterForMode()
            }
            RadarState.DISCOVERING -> {
                tvRadarCaption.text = if (selectedMode == "wired") "Searching USB / RNDIS…" else "Searching LAN…"
                tvRadarCaption.setTextColor(Color.CYAN)
                if (selectedMode == "wireless") {
                    radarCenterIcon.text = "📡"
                }
                updateRadarSpeed(1100L)
            }
            RadarState.MANUAL_READY -> {
                tvRadarCaption.text = "Manual Mode"
                tvRadarCaption.setTextColor(Color.LTGRAY)
                if (selectedMode == "wireless") radarCenterIcon.text = "🛜"
                updateRadarSpeed(2200L)
            }
            RadarState.CONNECTING -> {
                tvRadarCaption.text = "Connecting…"
                tvRadarCaption.setTextColor(Color.YELLOW)
                if (selectedMode == "wireless") radarCenterIcon.text = "🔄"
                updateRadarSpeed(650L)
            }
            RadarState.CONNECTED -> {
                tvRadarCaption.text = "Connected"
                tvRadarCaption.setTextColor(Color.parseColor("#00E676"))
                stopLanRadarAnimation()
            }
            RadarState.DISCONNECTED -> {
                tvRadarCaption.text = "Disconnected"
                tvRadarCaption.setTextColor(Color.LTGRAY)
                updateRadarSpeed(1800L)
            }
            RadarState.FAILED -> {
                tvRadarCaption.text = "Server / Transport unavailable"
                tvRadarCaption.setTextColor(Color.RED)
                if (selectedMode == "wireless") radarCenterIcon.text = "❌"
                stopLanRadarAnimation()
                stopWiredRadarAnimation()
            }
        }
    }

    private fun buildPulseAnimator(target: View, durationMs: Long, startDelayMs: Long, fromScale: Float, toScale: Float, fromAlpha: Float): AnimatorSet {
        val scaleX = ObjectAnimator.ofFloat(target, View.SCALE_X, fromScale, toScale).apply {
            duration = durationMs; startDelay = startDelayMs; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART; interpolator = AccelerateDecelerateInterpolator()
        }
        val scaleY = ObjectAnimator.ofFloat(target, View.SCALE_Y, fromScale, toScale).apply {
            duration = durationMs; startDelay = startDelayMs; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART; interpolator = AccelerateDecelerateInterpolator()
        }
        val alpha = ObjectAnimator.ofFloat(target, View.ALPHA, fromAlpha, 0f).apply {
            duration = durationMs; startDelay = startDelayMs; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART; interpolator = AccelerateDecelerateInterpolator()
        }
        return AnimatorSet().apply { playTogether(scaleX, scaleY, alpha) }
    }

    private fun updateRadarSpeed(duration: Long) {
        stopLanRadarAnimation()
        radarAnimators.clear()
        radarAnimators += buildPulseAnimator(radarRingOuter, duration, 0, 0.72f, 1.20f, 0.55f)
        radarAnimators += buildPulseAnimator(radarRingMiddle, duration, duration / 4, 0.74f, 1.18f, 0.65f)
        radarAnimators += buildPulseAnimator(radarRingInner, duration, duration / 2, 0.78f, 1.16f, 0.80f)
        if (selectedMode == "wireless") {
            radarAnimators += buildPulseAnimator(radarCenterIcon, (duration / 2).coerceAtLeast(600L), 0, 1f, 1.08f, 1f)
            startLanRadarAnimation()
        }
    }

    private fun startLanRadarAnimation() {
        radarAnimators.forEach { animator ->
            if (animator.isRunning) animator.cancel()
            animator.start()
        }
    }

    private fun stopLanRadarAnimation() {
        radarAnimators.forEach { if (it.isRunning) it.cancel() }
    }

    private fun startWiredRadarAnimation() {
        if (wiredRadarAnimator?.isRunning == true) return
        val scaleX = ObjectAnimator.ofFloat(radarCenterImage, View.SCALE_X, 0.88f, 1.08f)
        val scaleY = ObjectAnimator.ofFloat(radarCenterImage, View.SCALE_Y, 0.88f, 1.08f)
        val alpha = ObjectAnimator.ofFloat(radarCenterImage, View.ALPHA, 0.65f, 1f)
        wiredRadarAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha); duration = 900L; interpolator = AccelerateDecelerateInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (selectedMode == "wired" && !isFinishing && !isDestroyed) { wiredRadarAnimator = null; startWiredRadarAnimation() }
                }
            }); start()
        }
    }

    private fun stopWiredRadarAnimation() {
        wiredRadarAnimator?.cancel(); wiredRadarAnimator = null
        radarCenterImage.scaleX = 1f; radarCenterImage.scaleY = 1f; radarCenterImage.alpha = 1f
    }

    private fun stopRadarAnimations() {
        stopLanRadarAnimation(); radarAnimators.clear(); stopWiredRadarAnimation()
    }

    private fun openDashboard() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("FROM_SETUP_CONNECTION", true)
        })
        finish()
    }
}
