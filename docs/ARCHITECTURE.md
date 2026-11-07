# Architecture and data flow

## Android client

- `MainActivity.kt` and the other Activities provide connection, setup, and settings UI.
- `AudioStreamingService.kt` owns persistent streaming state, network-route observation, connection/reconnection, profile selection, telemetry, audio focus, and AudioTrack lifecycle.
- `BufferModeConfig.kt` stores the named mode and user-selected Custom target.
- `wifi_audio_native.cpp` owns the UDP socket and native PCM ring; JNI exposes connection, receive, and statistics operations to Kotlin.
- `network_handler.cpp` contains native discovery/network handling.
- `audio_processor.cpp` contains a JNI audio processing function.

## Windows sender

- `wifi_audio_server.py` captures loopback/system audio through available Windows backends.
- Audio is converted to mono PCM16 and sent as 10 ms frames. The packet format includes a 4-byte sequence header followed by a 960-byte PCM payload for 480 samples at 48 kHz.
- The server receives connection/profile announcements and telemetry from the Android client.
- Windows media-session and mute controls are optional integrations and depend on OS/runtime support.

## Latency model

End-to-end delay is influenced by capture callback scheduling, server-side queues, network delivery, the Android kernel receive queue, the native PCM ring, Kotlin playback policy, AudioTrack buffering, and the device audio HAL. Increasing every queue independently can stack latency. The buffer profiles therefore use different policies rather than assuming a single queue size is appropriate for every mode.

## Profile boundaries

- Golden Ultra Low has its own original fast-forward path and minimal server queue.
- Low has a bounded lip-sync-oriented policy.
- Medium prioritizes balance and short jitter tolerance.
- High prioritizes continuity and preserves queued PCM.
- Custom's adaptive policy is kept as a separate experiment until physical-device validation is complete.

## Custom experiment

The first Custom candidate uses explainable bounded calculations rather than a remote AI call in the audio loop. The slider expresses a target; network/device measurements influence the effective receiver-side reserve. Android AudioTrack allocation remains constrained by actual device/HAL behaviour so a large slider setting does not automatically force a multi-second hardware output queue.
