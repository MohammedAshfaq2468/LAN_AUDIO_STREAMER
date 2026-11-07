
package com.example.wifiaudiospeaker

import android.content.*
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.appcompat.widget.SwitchCompat
import android.speech.tts.TextToSpeech
import java.util.*

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var toolbar: Toolbar

    // CRITICAL: Flag to prevent infinite spinner loops
    private var isInitializing = true

    // UI Components
    private lateinit var switchTtsMute: SwitchCompat
    private lateinit var spinnerDarkMode: Spinner
    private lateinit var spinnerTheme: Spinner
    private lateinit var switchAutoConnect: SwitchCompat
    private lateinit var spinnerBufferModeSettings: Spinner
    private lateinit var seekCustomBufferMsSettings: SeekBar
    private lateinit var tvCustomBufferMsSettingsValue: TextView
    private lateinit var tvBufferModeSettingsSummary: TextView
    private lateinit var switchBatteryOptimization: SwitchCompat

    private lateinit var seekBarVolume: SeekBar

    private lateinit var tvVolumeValue: TextView
    private lateinit var btnResetSettings: Button
    private lateinit var btnBackup: Button
    private lateinit var btnRestore: Button
    private lateinit var tvVersionInfo: TextView

    // Settings values
    private var isTtsMuted = false
    private var darkModePosition = 3 // System default
    private var themePosition = 0
    private var autoConnect = true
    private var volume = 60
    private var bufferModeKey = BufferModeConfig.Mode.MEDIUM.key
    private var selectedBufferMs = BufferModeConfig.Mode.MEDIUM.presetMs
    private var customBufferMs = BufferModeConfig.DEFAULT_CUSTOM_MS

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme settings early
        prefs = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)

        val mode = prefs.getInt("dark_mode", 3)
        darkModePosition = mode

        val night = when (mode) {
            0 -> AppCompatDelegate.MODE_NIGHT_NO
            1, 2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

        AppCompatDelegate.setDefaultNightMode(night)

        if (night == AppCompatDelegate.MODE_NIGHT_YES) {
            when (mode) {
                1 -> {
                    try {
                        theme.applyStyle(R.style.ThemeOverlay_WifiAudio_GreyNight, true)
                    } catch (e: Exception) { }
                }
                2 -> {
                    try {
                        theme.applyStyle(R.style.ThemeOverlay_WifiAudio_DeepBlack, true)
                    } catch (e: Exception) { }
                }
            }
        }

        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { status ->             if (status == TextToSpeech.SUCCESS) {
            tts?.setLanguage(Locale.US)
            ttsReady = true
        }
        }

        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)

        initViews()
        setupToolbar()
        loadSettings()
        setupListeners()
        updateVersionInfo()
        setupBackPressedHandler()
    }

    private fun safeSpeak(text: String) {
        if (!isTtsMuted && ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "SettingsTTS")
        }
    }

    private fun setupBackPressedHandler() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                saveSettings()
                finish()
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar_settings)

        // Existing UI components
        switchTtsMute = findViewById(R.id.switch_tts_mute)
        spinnerDarkMode = findViewById(R.id.spinner_dark_mode)
        spinnerTheme = findViewById(R.id.spinner_theme)
        switchAutoConnect = findViewById(R.id.switch_auto_connect)
        spinnerBufferModeSettings = findViewById(R.id.spinnerBufferModeSettings)
        seekCustomBufferMsSettings = findViewById(R.id.seekCustomBufferMsSettings)
        tvCustomBufferMsSettingsValue = findViewById(R.id.tvCustomBufferMsSettingsValue)
        tvBufferModeSettingsSummary = findViewById(R.id.tvBufferModeSettingsSummary)

        seekBarVolume = findViewById(R.id.seekbar_volume)

        tvVolumeValue = findViewById(R.id.tv_volume_value)
        switchBatteryOptimization = findViewById(R.id.switch_battery_optimization)
        btnResetSettings = findViewById(R.id.btn_reset_settings)
        btnBackup = findViewById(R.id.btn_backup_settings)
        btnRestore = findViewById(R.id.btn_restore_settings)
        tvVersionInfo = findViewById(R.id.tv_version_info)

        setupSpinners()
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = " ⚙ Settings "
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupSpinners() {
        // Dark Mode Spinner
        val darkModeOptions = arrayOf(
            "💡 Light Mode", // 0
            "🌙 Dark Mode (Grey)", // 1
            "⚫ Deep Black", // 2
            "📱 System Default" // 3
        )
        val darkModeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, darkModeOptions)
        darkModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDarkMode.adapter = darkModeAdapter

        // Theme Spinner
        val themeOptions = arrayOf(
            "🎨 Default Theme",
            "🌈 Gradient Theme",
            "🎯 Minimal Theme"
        )
        val themeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, themeOptions)
        themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTheme.adapter = themeAdapter

        // Shared five-option buffer selector, matching MainActivity's dialog.
        val bufferModeLabels = arrayOf(
            "⚡ Ultra Low · Golden pipeline · 10 ms",
            "Low buffer · 10 ms",
            "Medium buffer · 250 ms",
            "High buffer · 1500 ms",
            "Custom buffer"
        )
        val bufferModeAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            bufferModeLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerBufferModeSettings.adapter = bufferModeAdapter

    }

    private fun readPersistedAutoReconnectPreference(): Boolean {
        val saved = when {
            prefs.contains("auto_reconnect") -> prefs.getBoolean("auto_reconnect", true)
            prefs.contains("auto_connect") -> prefs.getBoolean("auto_connect", true)
            else -> true
        }
        // Migrate both historical keys to one consistent value.
        prefs.edit().putBoolean("auto_reconnect", saved).putBoolean("auto_connect", saved).apply()
        return saved
    }

    private fun notifyRunningServiceOfAutoReconnectChange(enabled: Boolean) {
        if (!AudioStreamingService.isServiceRunning) return
        val modePrefs = getSharedPreferences("wifi_audio_state", Context.MODE_PRIVATE)
        val wired = modePrefs.getString("streaming_mode", "wireless") == "wired"
        try {
            startService(Intent(this, AudioStreamingService::class.java).apply {
                action = AudioStreamingService.ACTION_SETUP_CONFIG_UPDATE
                putExtra("AUTO_RECONNECT", enabled)
                putExtra("AUTO_MODE", modePrefs.getBoolean("auto_mode", true))
                putExtra("IS_WIRED", wired)
            })
        } catch (e: Exception) {
            android.util.Log.e("SettingsActivity", "Failed to apply Auto Reconnect toggle to Service", e)
        }
    }

    private fun loadSettings() {
        isInitializing = true

        // Load existing settings
        isTtsMuted = prefs.getBoolean("tts_muted", false)
        switchTtsMute.isChecked = isTtsMuted

        darkModePosition = prefs.getInt("dark_mode", 3)
        themePosition = prefs.getInt("theme", 0)
        spinnerDarkMode.setSelection(darkModePosition, false)
        spinnerTheme.setSelection(themePosition, false)

        autoConnect = readPersistedAutoReconnectPreference()

        switchAutoConnect.isChecked = autoConnect

        val bufferSelection = BufferModeConfig.load(prefs)
        bufferModeKey = bufferSelection.mode.key
        selectedBufferMs = bufferSelection.bufferMs
        customBufferMs = bufferSelection.customMs
        spinnerBufferModeSettings.setSelection(settingsBufferSpinnerPosition(bufferSelection.mode), false)
        seekCustomBufferMsSettings.max = BufferModeConfig.MAX_CUSTOM_MS - BufferModeConfig.MIN_CUSTOM_MS
        seekCustomBufferMsSettings.progress = (customBufferMs - BufferModeConfig.MIN_CUSTOM_MS)
            .coerceIn(0, seekCustomBufferMsSettings.max)
        updateBufferModeSettingsUi()

        // The settings slider mirrors Android's shared media volume; it is
        // not an app-local gain setting.
        volume = getSystemMediaVolumePercent()

        seekBarVolume.progress = volume

        updateVolumeText()
        switchBatteryOptimization.isChecked = isBatteryOptimizationEnabled()

        isInitializing = false
    }

    private fun setupListeners() {
        // Existing listeners
        switchTtsMute.setOnCheckedChangeListener { _, isChecked ->
            isTtsMuted = isChecked
            saveSettings()
            toast(if (isChecked) "🔇 Voice Guide Muted" else "🔊 Voice Guide Enabled")
        }

        spinnerDarkMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (isInitializing) return
                if (darkModePosition != pos) {
                    darkModePosition = pos
                    applyDarkMode(pos)
                    saveSettings()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (isInitializing) return
                if (themePosition != position) {
                    themePosition = position
                    applyTheme(position)
                    saveSettings()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        switchAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            // loadSettings()/reset/restore may set isChecked programmatically. That
            // must not overwrite a saved preference during Activity restoration.
            if (isInitializing) return@setOnCheckedChangeListener
            autoConnect = isChecked
            saveSettings()
            notifyRunningServiceOfAutoReconnectChange(isChecked)
            safeSpeak(if (isChecked) "Auto reconnect enabled" else "Auto reconnect disabled")
            toast(if (isChecked) "📡 Auto Reconnect Enabled" else "📡 Auto Reconnect Disabled")
        }

        spinnerBufferModeSettings.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (isInitializing) return
                val mode = settingsBufferModeFromSpinnerPosition(position)
                if (mode.key != bufferModeKey) {
                    applyBufferModeSettings(mode, customBufferMs, announce = true)
                } else {
                    updateBufferModeSettingsUi()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        seekCustomBufferMsSettings.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = BufferModeConfig.MIN_CUSTOM_MS + progress
                tvCustomBufferMsSettingsValue.text = "Custom buffer: ${value} ms"
                if (fromUser) customBufferMs = value
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (isInitializing) return
                val value = BufferModeConfig.MIN_CUSTOM_MS + (seekBar?.progress ?: 0)
                // Dragging the always-visible slider automatically changes the dropdown
                // to Custom; no separate Custom selection is required.
                spinnerBufferModeSettings.setSelection(
                    settingsBufferSpinnerPosition(BufferModeConfig.Mode.CUSTOM),
                    false
                )
                if (bufferModeKey != BufferModeConfig.Mode.CUSTOM.key || selectedBufferMs != value) {
                    applyBufferModeSettings(BufferModeConfig.Mode.CUSTOM, value, announce = true)
                } else {
                    customBufferMs = value
                    updateBufferModeSettingsUi()
                }
            }
        })

        seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    volume = progress.coerceIn(0, 100)
                    setSystemMediaVolumePercent(volume)
                    updateVolumeText()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                volume = getSystemMediaVolumePercent()
                seekBarVolume.progress = volume
                saveSettings()
                safeSpeak("System media volume set to $volume percent")
                toast("🔊 System media volume set to ${volume}%")
            }
        })

        switchBatteryOptimization.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                safeSpeak("Opening battery optimization settings")
                requestBatteryOptimizationDisable()
            }
        }

        btnResetSettings.setOnClickListener {
            safeSpeak("Are you sure you want to reset settings?")
            showResetConfirmDialog()
        }

        btnBackup.setOnClickListener {
            backupSettings()
        }

        btnRestore.setOnClickListener {
            restoreSettings()
        }

    }

    private fun applyDarkMode(position: Int) {
        val night = when (position) {
            0 -> AppCompatDelegate.MODE_NIGHT_NO
            1, 2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

        AppCompatDelegate.setDefaultNightMode(night)

        val modeText = when (position) {
            0 -> "Light Mode"
            1 -> "Dark Mode (Grey)"
            2 -> "Deep Black Mode"
            else -> "System Default"
        }

        safeSpeak("Applied $modeText")
        toast("🎨 Applied $modeText")
        setResult(RESULT_OK, Intent().apply {
            putExtra("theme_changed", true)
            putExtra("theme_position", position)
        })
    }

    private fun applyTheme(position: Int) {
        val text = when (position) {
            0 -> "🎨 Default Theme Applied"
            1 -> "🌈 Gradient Theme Applied"
            else -> "🎯 Minimal Theme Applied"
        }
        safeSpeak(text)
        toast(text)
        setResult(RESULT_OK, Intent().apply {
            putExtra("theme_changed", true)
            putExtra("theme_position", position)
        })
    }


    private fun settingsBufferSpinnerPosition(mode: BufferModeConfig.Mode): Int = when (mode) {
        BufferModeConfig.Mode.ULTRA_LOW -> 0
        BufferModeConfig.Mode.LOW -> 1
        BufferModeConfig.Mode.MEDIUM -> 2
        BufferModeConfig.Mode.HIGH -> 3
        BufferModeConfig.Mode.CUSTOM -> 4
    }

    private fun settingsBufferModeFromSpinnerPosition(position: Int): BufferModeConfig.Mode = when (position) {
        0 -> BufferModeConfig.Mode.ULTRA_LOW
        1 -> BufferModeConfig.Mode.LOW
        3 -> BufferModeConfig.Mode.HIGH
        4 -> BufferModeConfig.Mode.CUSTOM
        else -> BufferModeConfig.Mode.MEDIUM
    }

    private fun updateBufferModeSettingsUi() {
        val mode = BufferModeConfig.Mode.fromKey(bufferModeKey)

        // The custom slider and value label intentionally remain visible for every mode.
        tvCustomBufferMsSettingsValue.visibility = View.VISIBLE
        seekCustomBufferMsSettings.visibility = View.VISIBLE
        tvCustomBufferMsSettingsValue.text =
            "Custom buffer: ${customBufferMs} ms · drag to switch to Custom"

        tvBufferModeSettingsSummary.text = when (mode) {
            BufferModeConfig.Mode.ULTRA_LOW ->
                "Ultra Low uses the Golden streaming strategy: capture-paced 10 ms frames, immediate playback and tight lip-sync control. The slider remains available; dragging it selects Custom."
            BufferModeConfig.Mode.LOW ->
                "Low buffer · ${selectedBufferMs} ms. Lowest-delay adaptive option for gaming/video. Drag the slider to switch to Custom."
            BufferModeConfig.Mode.MEDIUM ->
                "Medium buffer · ${selectedBufferMs} ms. Balanced delay and tolerance for short jitter. Drag the slider to switch to Custom."
            BufferModeConfig.Mode.HIGH ->
                "High buffer · ${selectedBufferMs} ms. More protection against hiccups with more playback delay. Drag the slider to switch to Custom."
            BufferModeConfig.Mode.CUSTOM ->
                "Custom buffer · ${selectedBufferMs} ms. Drag the slider from ${BufferModeConfig.MIN_CUSTOM_MS} to ${BufferModeConfig.MAX_CUSTOM_MS} ms; the value applies when released."
        }
    }

    private fun applyBufferModeSettings(
        mode: BufferModeConfig.Mode,
        customMs: Int,
        announce: Boolean
    ) {
        val selection = BufferModeConfig.save(prefs, mode, customMs)
        bufferModeKey = selection.mode.key
        selectedBufferMs = selection.bufferMs
        customBufferMs = selection.customMs

        isInitializing = true
        spinnerBufferModeSettings.setSelection(settingsBufferSpinnerPosition(selection.mode), false)
        seekCustomBufferMsSettings.max = BufferModeConfig.MAX_CUSTOM_MS - BufferModeConfig.MIN_CUSTOM_MS
        seekCustomBufferMsSettings.progress = (customBufferMs - BufferModeConfig.MIN_CUSTOM_MS)
            .coerceIn(0, seekCustomBufferMsSettings.max)
        tvCustomBufferMsSettingsValue.text = "Custom buffer: ${customBufferMs} ms · drag to switch to Custom"
        isInitializing = false
        updateBufferModeSettingsUi()
        saveSettings()

        sendBroadcast(
            Intent(MainActivity.ACTION_UI_SYNC_QUALITY_MODE)
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
                android.util.Log.e("SettingsActivity", "Failed to apply live buffer mode", e)
                toast("Buffer saved; live update will apply on the next connection")
            }
        }

        if (announce) toast("🎚️ ${selection.mode.title}: ${selection.bufferMs} ms")
    }

    private fun pushSavedBufferToRunningService() {
        if (!AudioStreamingService.isServiceRunning) return
        val selection = BufferModeConfig.load(prefs)
        try {
            startService(Intent(this, AudioStreamingService::class.java).apply {
                putExtra("SET_BUFFER_SIZE", selection.bufferMs)
                putExtra("BUFFER_MODE", selection.mode.key)
                putExtra("CUSTOM_BUFFER_MS", selection.customMs)
            })
        } catch (e: Exception) {
            android.util.Log.e("SettingsActivity", "Could not restore buffer selection into Service", e)
        }
    }

    private fun getSystemMediaVolumePercent(): Int {
        return try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            ((audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f) / maxVolume)
                .toInt().coerceIn(0, 100)
        } catch (e: Exception) {
            android.util.Log.w("SettingsActivity", "Unable to read system media volume: ${e.message}")
            prefs.getInt("volume", 60).coerceIn(0, 100)
        }
    }

    private fun setSystemMediaVolumePercent(percent: Int) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val target = ((percent.coerceIn(0, 100) / 100f) * maxVolume).toInt().coerceIn(0, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: Exception) {
            android.util.Log.e("SettingsActivity", "Unable to set system media volume: ${e.message}", e)
            toast("Unable to change system media volume")
        }
    }

    private fun updateVolumeText() {
        tvVolumeValue.text = "${volume}%"
    }

    private fun isBatteryOptimizationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            return !pm.isIgnoringBatteryOptimizations(packageName)
        } else {
            return false
        }
    }

    private fun requestBatteryOptimizationDisable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (ex: Exception) {
                    toast("Please disable battery optimization manually in Settings")
                }
            }
        }
    }

    private fun showResetConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Reset App Settings")
            .setMessage("This will reset all  settings to default values. Are you sure?")
            .setPositiveButton("Reset") { _, _ ->
                resetToDefaults()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetToDefaults() {
        // Reset all values to optimized defaults
        isTtsMuted = false
        darkModePosition = 3
        themePosition = 0
        autoConnect = true
        volume = 60

        prefs.edit().clear().apply()
        loadSettings()
        applyBufferModeSettings(BufferModeConfig.Mode.MEDIUM, BufferModeConfig.DEFAULT_CUSTOM_MS, announce = false)
        notifyRunningServiceOfAutoReconnectChange(autoConnect)
        applyDarkMode(darkModePosition)
        applyTheme(themePosition)

        safeSpeak("Settings reset to defaults")
        toast("✅ App settings reset to optimal defaults")
    }

    private fun backupSettings() {
        try {
            val settingsMap = prefs.all
            val backup = StringBuilder()
            backup.append("#  WiFi Audio Speaker Settings Backup\n")
            backup.append("# Generated on: ${Date()}\n")
            backup.append("# Optimized Configuration\n\n")

            for ((key, value) in settingsMap) {
                backup.append("$key=$value\n")
            }

            AlertDialog.Builder(this)
                .setTitle("📋  Settings Backup")
                .setMessage("Copy this  backup string:\n\n${backup}")
                .setPositiveButton("Copy") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(" Settings Backup", backup.toString())
                    clipboard.setPrimaryClip(clip)
                    toast("📋 Settings  backup copied to clipboard")
                }
                .setNegativeButton("Close", null)
                .show()
        } catch (e: Exception) {
            toast("❌ Settings backup failed: ${e.message}")
        }
    }

    private fun restoreSettings() {
        val input = EditText(this)
        input.hint = "Paste  backup string here..."

        AlertDialog.Builder(this)
            .setTitle("📥 Restore  Settings")
            .setMessage("Paste your backup string:")
            .setView(input)
            .setPositiveButton("Restore") { _, _ ->
                val backupText = input.text.toString()
                try {
                    val editor = prefs.edit()
                    val lines = backupText.split("\n")

                    for (line in lines) {
                        if (line.startsWith("#") || line.trim().isEmpty()) continue
                        val parts = line.split("=", limit = 2)
                        if (parts.size == 2) {
                            val key = parts[0].trim()
                            val value = parts[1].trim()

                            when {
                                value.equals("true", ignoreCase = true) -> editor.putBoolean(key, true)
                                value.equals("false", ignoreCase = true) -> editor.putBoolean(key, false)
                                value.toIntOrNull() != null -> editor.putInt(key, value.toInt())
                                value.toFloatOrNull() != null -> editor.putFloat(key, value.toFloat())
                                else -> editor.putString(key, value)
                            }
                        }
                    }

                    editor.apply()
                    loadSettings()
                    // loadSettings migrates both legacy buffer and reconnect keys.
                    notifyRunningServiceOfAutoReconnectChange(autoConnect)
                    pushSavedBufferToRunningService()
                    toast("✅ App Settings Restored Successfully")

                } catch (e: Exception) {
                    toast("❌ Settings restore failed: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateVersionInfo() {
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val version = pInfo.versionName
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }

            tvVersionInfo.text = " WiFi Audio Speaker v$version (Build $versionCode)\n Optimized for Zero Latency Streaming"
        } catch (e: Exception) {
            tvVersionInfo.text = " WiFi Audio Speaker - Zero Latency Edition"
        }
    }

    private fun saveSettings() {
        prefs.edit().apply {
            putBoolean("tts_muted", isTtsMuted)
            putInt("dark_mode", darkModePosition)
            putInt("theme", themePosition)
            // auto_reconnect is the canonical key; mirror auto_connect for
            // backward compatibility with older releases/screens.
            putBoolean("auto_reconnect", autoConnect)
            putBoolean("auto_connect", autoConnect)
            putString(BufferModeConfig.PREF_MODE, bufferModeKey)
            putInt(BufferModeConfig.PREF_BUFFER_MS, selectedBufferMs)
            putInt(BufferModeConfig.PREF_CUSTOM_MS, customBufferMs.coerceIn(
                BufferModeConfig.MIN_CUSTOM_MS, BufferModeConfig.MAX_CUSTOM_MS
            ))
            putBoolean("button_premium_free", bufferModeKey == BufferModeConfig.Mode.LOW.key)
            putString("user_profile", when (bufferModeKey) {
                BufferModeConfig.Mode.ULTRA_LOW.key -> "ultra_low_golden_v1"
                BufferModeConfig.Mode.LOW.key -> "premium_test" // legacy compatibility
                BufferModeConfig.Mode.HIGH.key -> "free_test"   // legacy compatibility
                else -> "buffer_$bufferModeKey"
            })

            putInt("volume", volume)

            apply()

        }

        setResult(RESULT_OK, Intent().apply {
            putExtra("settings_changed", true)
        })
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        // TTS shutdown can block on the Google TTS Binder. Do not make the
        // Activity main thread wait for it during navigation/backgrounding.
        val ttsToShutdown = tts
        tts = null
        ttsReady = false
        super.onDestroy()

        if (ttsToShutdown != null) {
            Thread {
                try {
                    ttsToShutdown.shutdown()
                } catch (_: Throwable) {
                    // Best-effort cleanup only.
                }
            }.start()
        }
    }
}