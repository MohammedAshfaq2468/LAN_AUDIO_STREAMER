# AudioStreamingAPP — Custom Adaptive Engine v1

This patch adds an independent, deterministic Custom buffering policy. It is based on the latest available source snapshots dated 2026-11-01 and changes only the Custom path plus shared telemetry/profile dispatch needed to isolate it.

## Files to replace / add

- Replace `AudioStreamingService.kt`.
- Replace `wifi_audio_native.cpp` and rebuild the Android native library.
- Replace `wifi_audio_server.py` on Windows.
- Add `CustomBufferEngine.kt` beside `AudioStreamingService.kt` in package `com.example.wifiaudiospeaker`.

No MainActivity, SettingsActivity, XML, BufferModeConfig, network_handler or audio_processor file is changed.

## What Custom v1 does

- Keeps the user slider at 10–2,000 ms (default remains 500 ms).
- Computes a bounded adaptive target from requested buffer, measured jitter, a small excess-RTT term, route classification for diagnostics (no automatic discount for USB/Ethernet), Android process CPU, available memory, CPU core count and thermal state.
- Uses the adaptive target for real-PCM startup pre-roll and hysteresis watermarks.
- Guarantees `lowWatermark < highWatermark`, including 1,750–2,000 ms selections.
- Keeps Android AudioTrack at the device/HAL minimum for Custom; the target is held in the receiver-side native PCM ring, not a 2-second hardware playback queue.
- Adds a Custom native ring profile with 1.5–3.0 seconds of bounded capacity (`requested slider + 1,000 ms`, clamped).
- Adds a 60 ms ring-full grace period, then evicts one 10 ms oldest frame to prevent unbounded latency under sustained overload.
- Adds a named Windows `custom_buffer_v1` profile with a 12-frame source queue, 25 ms capture wait, local PC CPU/RAM/core/backend sampling and a sustained-backlog trim guard that reacts to source-queue and host pressure. It does not make the Windows queue as large as the phone's chosen target, avoiding duplicate latency.
- Reports requested/effective target, network margin, device penalty, watermarks, Custom stale skips, native ring evictions, source-queue drops and silence frames.
- Custom requests one fresh network assessment after the user releases the slider, then refreshes RTT/jitter roughly every 9 seconds and sends telemetry every 10 seconds; all probes stay outside the real-time audio loop. Other profiles keep their existing 30-second cadence.

## Important limits

This is a deterministic adaptive controller, not a trained AI model. Windows sender samples system/process CPU, available RAM, logical cores, capture backend and source queue pressure locally; this is not yet a bidirectional PC hardware telemetry/control protocol. Android device profiling samples app CPU, available memory, CPU-core count and thermal state. Those signals cannot guarantee audio performance or exact lip-sync.

The 10–2,000 ms slider is a receiver buffering target, not a guarantee of end-to-end latency. Larger values trade latency for resilience. No software can guarantee perfect playback under arbitrary Wi-Fi congestion, router interference, PC capture stalls, Android thermal throttling or OS scheduling delays.

## Golden / Low / Medium / High isolation

- Golden client playback and sender branch are not edited. The new Custom setter is called with `false` for all non-Custom profiles. Golden keeps its existing 10 ms native read wait and 4-frame server queue.
- Low's 30 ms read wait, 30 ms overflow grace, 16-frame server queue and Low sender branch are unchanged.
- Medium and High policies and sender branches are unchanged.
- Generic adaptive-v2 remains for legacy/unknown clients.

## Build / install

1. Back up the three existing source files.
2. Replace the Kotlin service and add `CustomBufferEngine.kt`.
3. Rebuild the Android app and NDK library together so the new JNI method exists in the packaged `.so`.
4. Replace/restart the Windows Python server.
5. First test Custom at 500 ms, not 2,000 ms. Capture 3–5 minutes on Realme GT 6T Home Wi-Fi, then Lenovo. Repeat on USB tethering only after Wi-Fi baseline is recorded.

## Validation

The patch includes static checks. No full Android Gradle/NDK build or physical-device playback test was possible because the complete Gradle project root/wrapper was not present in the workspace.
