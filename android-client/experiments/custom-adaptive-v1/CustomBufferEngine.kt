package com.example.wifiaudiospeaker

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Deterministic, local Custom-buffer controller.
 *
 * The slider is the user's base target. Network/device headroom is added within
 * bounded limits; the AudioTrack hardware queue is deliberately NOT set to this
 * value. The selected target controls receiver-side PCM reserve and correction.
 */
internal object CustomBufferEngine {
    const val PROFILE = "custom_buffer_v1"
    const val MIN_TARGET_MS = 10
    const val MAX_TARGET_MS = 2_000

    data class Decision(
        val requestedMs: Int,
        val effectiveTargetMs: Int,
        val networkMarginMs: Int,
        val devicePenaltyMs: Int,
        val lowWatermarkMs: Int,
        val highWatermarkMs: Int,
        val preRollTargetMs: Int,
        val preRollTimeoutMs: Long,
        val persistenceMs: Long,
        val ringCapacityMs: Int
    )

    fun decide(
        requestedMs: Int,
        rttMs: Long,
        jitterMs: Double,
        devicePenaltyMs: Int
    ): Decision {
        val requested = requestedMs.coerceIn(MIN_TARGET_MS, MAX_TARGET_MS)
        val safeRtt = rttMs.coerceAtLeast(0L)
        val safeJitter = jitterMs.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0

        // Jitter is the dominant network term. RTT contributes only a small
        // excess-delay penalty; raw RTT is not treated as packet jitter.
        val jitterMargin = (safeJitter * 1.35).roundToInt().coerceIn(0, 300)
        val rttMargin = ((safeRtt - 60L).coerceAtLeast(0L) * 0.08)
            .roundToInt().coerceIn(0, 120)
        // A USB/Ethernet label is not proof of a congestion-free path.
        // Use observed jitter and RTT as-is on every transport.
        val networkMargin = (jitterMargin + rttMargin).coerceIn(0, 350)
        val devicePenalty = devicePenaltyMs.coerceIn(0, 120)

        // Keep the selected value meaningful while adding a bounded safety margin.
        val effective = (requested + networkMargin + devicePenalty)
            .coerceIn(50, MAX_TARGET_MS)
        val lowerGuard = max(30, (effective * 0.20).roundToInt())
        val upperGuard = max(80, (effective * 0.25).roundToInt())
        val low = (effective - lowerGuard).coerceAtLeast(30)
        val high = (effective + upperGuard).coerceAtMost(2_600)
            .coerceAtLeast(low + 60)

        // The native ring has 1 second of reserve beyond the effective target,
        // bounded by its existing 3-second adaptive allocation.
        val ringCapacity = (requested + 1_000).coerceIn(1_500, 3_000)
        val timeout = (effective + 1_200L).coerceIn(1_500L, 4_000L)
        val persistence = (450L + effective / 4L).coerceIn(450L, 900L)

        return Decision(
            requestedMs = requested,
            effectiveTargetMs = effective,
            networkMarginMs = networkMargin,
            devicePenaltyMs = devicePenalty,
            lowWatermarkMs = low,
            highWatermarkMs = high,
            preRollTargetMs = effective,
            preRollTimeoutMs = timeout,
            persistenceMs = persistence,
            ringCapacityMs = ringCapacity
        )
    }
}
