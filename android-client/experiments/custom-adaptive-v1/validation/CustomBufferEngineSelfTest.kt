package com.example.wifiaudiospeaker

fun main() {
    val sliderValues = listOf(10, 250, 500, 1000, 1500, 1750, 2000)
    val networkCases = listOf(
        Triple(0L, 0.0, 0),
        Triple(80L, 15.0, 20),
        Triple(250L, 120.0, 60),
        Triple(900L, 450.0, 120)
    )
    var cases = 0
    for (slider in sliderValues) {
        for ((rtt, jitter, devicePenalty) in networkCases) {
            val d = CustomBufferEngine.decide(
                requestedMs = slider,
                rttMs = rtt,
                jitterMs = jitter,
                devicePenaltyMs = devicePenalty
            )
            check(d.requestedMs in 10..2000)
            check(d.effectiveTargetMs in 50..2000)
            check(d.lowWatermarkMs < d.highWatermarkMs)
            check(d.highWatermarkMs - d.lowWatermarkMs >= 60)
            check(d.preRollTargetMs == d.effectiveTargetMs)
            check(d.ringCapacityMs in 1500..3000)
            check(d.preRollTimeoutMs in 1500L..4000L)
            cases++
        }
    }
    println("CustomBufferEngineSelfTest PASS: $cases policy cases; all watermarks and bounds valid")
}
