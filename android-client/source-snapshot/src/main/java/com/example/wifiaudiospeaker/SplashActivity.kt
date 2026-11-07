package com.example.wifiaudiospeaker

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.Locale

class SplashActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SplashActivity"
        private const val PREFS = "wifi_audio_state"
        private const val ACTION_APP_LAUNCH_BOOTSTRAP =
            "com.example.wifiaudiospeaker.ACTION_APP_LAUNCH_BOOTSTRAP"
        private const val ACTION_SETUP_AUTO_CONNECT =
            "com.example.wifiaudiospeaker.ACTION_SETUP_AUTO_CONNECT"
        private const val SPLASH_MIN_VISIBLE_MS = 700L
        private const val SPLASH_MAX_BOOTSTRAP_WAIT_MS = 1800L
    }

    private val splashScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A Recents restore is the ONLY allowed path that bypasses the custom
        // launcher splash. Normally Android restores the previous Activity
        // directly; this guard covers the case where SplashActivity itself is
        // revived from task history.
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            Log.i(TAG, "↩️ Recents restore detected — bypassing SplashActivity UI")
            finish()
            return
        }

        setContentView(R.layout.activity_splash)
        val splashStartedAt = SystemClock.elapsedRealtime()

        splashScope.launch {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            val autoConnect = prefs.getBoolean(
                "auto_connect",
                prefs.getBoolean("auto_reconnect", true)
            )
            val autoMode = prefs.getBoolean("auto_mode", true)
            val streamingMode =
                if (prefs.getString("streaming_mode", "wireless") == "wired") {
                    "wired"
                } else {
                    "wireless"
                }

            val legacyDevice =
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1 ||
                        Build.MODEL.contains("A6020", ignoreCase = true) ||
                        Build.DEVICE.contains("A6020", ignoreCase = true)
            val directMode = if (prefs.contains("button_premium_free")) {
                prefs.getBoolean("button_premium_free", !legacyDevice)
            } else {
                !legacyDevice
            }

            val bufferMs = when {
                streamingMode == "wired" -> 2
                directMode -> 10
                else -> 1500
            }

            // START THE SERVICE ON EVERY NORMAL LAUNCH.
            // This is intentionally independent of transport availability and
            // independent of the auto-connect preference. onCreate() immediately
            // posts the foreground waiting/offline notification.
            startNotificationBootstrapService(autoConnect, autoMode, streamingMode, bufferMs)

            val transportReady = transportReady(streamingMode)
            Log.i(
                TAG,
                "🚀 Launch routing: autoConnect=$autoConnect mode=$streamingMode " +
                        "method=${if (autoMode) "AUTO" else "MANUAL"} " +
                        "transportReady=$transportReady"
            )

            if (autoConnect && transportReady) {
                try {
                    ContextCompat.startForegroundService(
                        this@SplashActivity,
                        Intent(this@SplashActivity, AudioStreamingService::class.java).apply {
                            action = ACTION_SETUP_AUTO_CONNECT
                            putExtra("AUTO_RECONNECT", true)
                            putExtra("AUTO_MODE", autoMode)
                            putExtra("IS_WIRED", streamingMode == "wired")
                            putExtra("BUFFER_MS", bufferMs)
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Splash could not start Service auto-connect: ${e.message}")
                }
            }

            // Do not leave the custom splash on screen for only a few frames.
            // Keep it visible for the minimum visual window, then hand off to
            // the correct destination. The Service continues loading/connecting
            // independently in the background.
            val elapsed = SystemClock.elapsedRealtime() - splashStartedAt
            val remaining = SPLASH_MIN_VISIBLE_MS - elapsed
            if (remaining > 0) delay(remaining)

            if (autoConnect && transportReady) {
                // Give the foreground Service a bounded extra window to install
                // its notification/bootstrap state before the dashboard appears.
                val deadline = SystemClock.elapsedRealtime() + SPLASH_MAX_BOOTSTRAP_WAIT_MS
                while (SystemClock.elapsedRealtime() < deadline &&
                        !AudioStreamingService.isServiceRunning) {
                    delay(40L)
                }

                startActivity(
                    Intent(this@SplashActivity, MainActivity::class.java).apply {
                        putExtra("BOOTSTRAP_AUTO", true)
                        addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    }
                )
            } else {
                // Auto-connect OFF OR selected transport unavailable:
                // show SetupActivity only after the splash has been visibly shown.
                startActivity(
                    Intent(this@SplashActivity, SetupActivity::class.java).apply {
                        putExtra("BOOTSTRAP_SETUP", true)
                    }
                )
            }

            finish()
        }
    }

    private fun startNotificationBootstrapService(
        autoConnect: Boolean,
        autoMode: Boolean,
        streamingMode: String,
        bufferMs: Int
    ) {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AudioStreamingService::class.java).apply {
                    action = ACTION_APP_LAUNCH_BOOTSTRAP
                    putExtra("AUTO_RECONNECT", autoConnect)
                    putExtra("AUTO_MODE", autoMode)
                    putExtra("IS_WIRED", streamingMode == "wired")
                    putExtra("BUFFER_MS", bufferMs)
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not start notification bootstrap Service: ${e.message}")
        }
    }

    private fun transportReady(mode: String): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.any { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@any false
                val iface = cm.getLinkProperties(network)?.interfaceName?.lowercase(Locale.US).orEmpty()
                if (mode == "wireless") {
                    val wifi = getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wifi.isWifiEnabled &&
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                } else {
                    val usbInterface = iface.contains("rndis") || iface.contains("usb") ||
                            iface.contains("usb0") || iface.contains("ecm") || iface.contains("ncm")
                    val tetherTransport =
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    usbInterface && tetherTransport
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Splash transport check failed: ${e.message}")
            false
        }
    }

    override fun onDestroy() {
        splashScope.cancel()
        super.onDestroy()
    }
}
