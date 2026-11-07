package com.example.wifiaudiospeaker

import android.content.SharedPreferences

/**
 * Single source of truth for the receiver-side jitter/playback buffer selection.
 * The values are starting presets intended for device testing, not guarantees
 * of latency or artifact-free playback on every route.
 */
object BufferModeConfig {
    const val PREF_MODE = "buffer_mode"
    const val PREF_BUFFER_MS = "buffer_ms"
    const val PREF_CUSTOM_MS = "custom_buffer_ms"
    const val MIN_CUSTOM_MS = 10
    const val MAX_CUSTOM_MS = 2000
    const val DEFAULT_CUSTOM_MS = 500

    enum class Mode(
        val key: String,
        val title: String,
        val presetMs: Int,
        val description: String
    ) {
        ULTRA_LOW(
            "ultra_low", "Ultra Low", 10,
            "Golden pipeline: capture-paced 10 ms frames, immediate playback, a 1-second native ring and 40 ms stale-audio cutoff. Best on a stable route."
        ),
        LOW(
            "low", "Low buffer", 10,
            "Lowest delay for games and video. Needs a stable, low-jitter connection."
        ),
        MEDIUM(
            "medium", "Medium buffer", 250,
            "Balanced delay and protection against short network jitter."
        ),
        HIGH(
            "high", "High buffer", 1500,
            "More tolerance for audio hiccups. Best when stability matters more than delay."
        ),
        CUSTOM(
            "custom", "Custom", DEFAULT_CUSTOM_MS,
            "Choose a buffer from 10 to 2000 ms to suit your route and listening needs."
        );

        companion object {
            fun fromKey(key: String?): Mode =
                values().firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: MEDIUM

            fun fromLegacyPremiumFree(isPremium: Boolean): Mode = if (isPremium) LOW else HIGH

            fun fromMilliseconds(ms: Int): Mode = when {
                // 10 ms alone cannot distinguish Ultra Low from Low. Ultra Low
                // must be selected explicitly by its persisted mode key.
                ms <= LOW.presetMs -> LOW
                ms <= MEDIUM.presetMs -> MEDIUM
                ms >= HIGH.presetMs -> HIGH
                else -> CUSTOM
            }
        }
    }

    data class Selection(val mode: Mode, val bufferMs: Int, val customMs: Int)

    /** Load and migrate the former Premium/Free preference into the selectable buffer profiles. */
    fun load(prefs: SharedPreferences): Selection {
        val mode = when {
            prefs.contains(PREF_MODE) -> Mode.fromKey(prefs.getString(PREF_MODE, null))
            prefs.contains("button_premium_free") ->
                Mode.fromLegacyPremiumFree(prefs.getBoolean("button_premium_free", false))
            else -> Mode.MEDIUM
        }
        val customMs = prefs.getInt(PREF_CUSTOM_MS, DEFAULT_CUSTOM_MS)
            .coerceIn(MIN_CUSTOM_MS, MAX_CUSTOM_MS)
        val bufferMs = if (mode == Mode.CUSTOM) customMs else mode.presetMs

        // Keep the canonical values and legacy alias synchronized so older code
        // cannot restore a stale Premium/Free value over the four-mode selection.
        save(prefs, mode, customMs)
        return Selection(mode, bufferMs, customMs)
    }

    fun save(prefs: SharedPreferences, mode: Mode, customMs: Int = DEFAULT_CUSTOM_MS): Selection {
        val safeCustomMs = customMs.coerceIn(MIN_CUSTOM_MS, MAX_CUSTOM_MS)
        val bufferMs = if (mode == Mode.CUSTOM) safeCustomMs else mode.presetMs
        val profile = when (mode) {
            Mode.ULTRA_LOW -> "ultra_low_golden_v1"
            Mode.LOW -> "premium_test"       // Legacy compatibility only.
            Mode.HIGH -> "free_test"         // Legacy compatibility only.
            Mode.MEDIUM, Mode.CUSTOM -> "buffer_${mode.key}"
        }
        prefs.edit()
            .putString(PREF_MODE, mode.key)
            .putInt(PREF_BUFFER_MS, bufferMs)
            .putInt(PREF_CUSTOM_MS, safeCustomMs)
            .putBoolean("button_premium_free", mode == Mode.LOW)
            .putString("user_profile", profile)
            .apply()
        return Selection(mode, bufferMs, safeCustomMs)
    }
}
