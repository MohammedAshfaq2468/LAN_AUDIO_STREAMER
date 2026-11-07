# Static Validation

- [x] Custom Kotlin policy compiles independently.
- [x] 28 self-test combinations cover 10–2,000 ms targets, clean/high-jitter RTT cases, device penalties, watermark ordering and ring/pre-roll bounds.
- [x] Python server syntax compiles.
- [x] C++17 native source passes host-side syntax-only compilation with JNI headers and an Android log stub.
- [x] Kotlin service and policy delimiter/structure scans pass.
- [x] Custom JNI setter and native ring-drop getter are declared and implemented.
- [x] Custom has distinct native ring, pre-roll, watermark/hysteresis, stale-skip and telemetry paths.
- [x] Custom route pings refresh about every 9 seconds; telemetry refreshes every 10 seconds; probes stay outside the 10 ms audio loop.
- [x] Windows Custom sender has its own named profile and source queue policy.
- [x] PyAudio/WASAPI, SoundDevice and SoundCard Custom capture overflows are counted separately from generic underruns.
- [x] Golden native 10 ms reader wait and Golden server sender branch remain intact.
- [x] Low, Medium and High profile-specific branches remain intact.
- [x] Custom slider range remains 10–2,000 ms; Android AudioTrack is not forced to the slider target.

## Build limitations

- The workspace did not contain the complete Android Studio project root or Gradle wrapper.
- No full Android Gradle/NDK build or APK packaging was run.
- No phone playback or four-transport test was run.
- These are static validations, not proof of actual sound quality or lip-sync.
