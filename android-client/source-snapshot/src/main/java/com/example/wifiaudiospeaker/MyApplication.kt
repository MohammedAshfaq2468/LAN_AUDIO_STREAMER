package com.example.wifiaudiospeaker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class MyApplication : Application() {

    companion object {
        const val CHANNEL_ID = "WifiAudioChannel"
        private const val PREFS = "wifi_audio_state"
        private const val KEY_LAST_VISIBLE_ACTIVITY = "last_visible_activity"

        /**
         * Returns the activity that was most recently visible to the user.
         * The foreground Service uses this when rebuilding the notification
         * content PendingIntent, so tapping the notification returns the user
         * to SetupActivity/SettingsActivity/MainActivity instead of always
         * forcing MainActivity.
         */
        fun currentVisibleActivityIntent(context: android.content.Context): android.content.Intent {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val className = prefs.getString(KEY_LAST_VISIBLE_ACTIVITY, "")
                .orEmpty()
                .trim()

            val target = when (className) {
                SetupActivity::class.java.name -> SetupActivity::class.java
                SettingsActivity::class.java.name -> SettingsActivity::class.java
                else -> MainActivity::class.java
            }

            return android.content.Intent(context, target).apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Native library is loaded once by MyApplication,
        // before Activity or Service initialization.
        try {
            System.loadLibrary("wifiaudiospeaker")

            android.util.Log.i(
                "MyApplication",
                "✅ Native library loaded: wifiaudiospeaker"
            )

        } catch (e: UnsatisfiedLinkError) {

            android.util.Log.e(
                "MyApplication",
                "❌ Failed to load native library: ${e.message}",
                e
            )
        }
        createNotificationChannel()
        registerVisibleActivityTracker()
    }

    private fun registerVisibleActivityTracker() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                // Splash/scanner are transient implementation surfaces. The
                // notification should return to the real screen the user was
                // using: Setup, Settings, or Main.
                val target = when (activity) {
                    is SetupActivity -> activity::class.java.name
                    is SettingsActivity -> activity::class.java.name
                    is MainActivity -> activity::class.java.name
                    else -> return
                }
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_VISIBLE_ACTIVITY, target)
                    .apply()
                android.util.Log.d(
                    "MyApplication",
                    "👁️ Last visible activity = $target"
                )
                AudioStreamingService.instance?.refreshNotificationTarget()
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "WiFi Audio Streaming",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows when audio is streaming"
                    setSound(null, null)
                    enableLights(false)
                    enableVibration(false)
                    setShowBadge(false)
                }

                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            } catch (e: Exception) {
                android.util.Log.e("MyApplication", "Failed to create notification channel", e)
            }
        }
    }
}
