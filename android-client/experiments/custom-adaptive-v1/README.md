# Custom Adaptive Engine v1 — experimental candidate

This directory preserves a separate candidate patch for the Custom profile. It is deliberately not merged into the baseline source snapshot.

## Intent

- Keep the user-selected Custom target distinct from the effective runtime reserve.
- Calculate bounded receiver-side targets from the requested value, measured network conditions, and Android device pressure.
- Maintain valid low/high watermark ordering and hysteresis.
- Give Custom a distinct native profile, Windows sender identity, and telemetry fields.
- Keep Golden, Low, Medium, and High profile branches isolated.

## Files

- `CustomBufferEngine.kt`: policy/controller implementation.
- `AudioStreamingService.kt`: full candidate replacement for the baseline service.
- `wifi_audio_native.cpp`: full candidate replacement for the baseline native source.
- `wifi_audio_server.py`: full candidate replacement for the Windows server.
- `PATCH.diff`: source diff used to prepare the candidate.
- `validation/`: static checks and policy self-test.

## Important limitation

The candidate's static arithmetic/source checks do not prove audible quality, actual A/V synchronisation, or physical-device reliability. It has not been confirmed by a full Android Gradle/NDK build or physical-device acceptance run. Integrate on a separate branch, build all JNI components together, then test on the Lenovo Vibe K5 and Realme GT 6T before merging.
