# Engineering status

Snapshot assembled: 2026-11-07  
Baseline source timestamps available: 2026-11-01

## Present in the source snapshot

- Kotlin Android foreground-service streaming architecture.
- JNI/C++ UDP receiver and PCM ring-buffer implementation.
- Windows Python desktop sender and Qt UI.
- Five selectable buffer modes in the current buffer configuration source.
- Route discovery, network-change monitoring, and reconnect logic.
- Server/client telemetry for network and buffering diagnostics.
- Separate experimental Custom engine candidate.

## Confirmed in historical device runs

- Golden Ultra Low profile selection and AudioTrack reconstruction were observed on the tested Lenovo and Realme devices.
- Low profile selection and a dedicated Low AudioTrack allocation were observed.
- Tests also exposed significant variability in RTT/jitter, native ring read timeouts, Android AudioTrack underruns, and server-generated silence under some conditions.

Those observations confirm profile execution and reveal remaining issues; they do not establish perfect synchronisation or uninterrupted audio under all network conditions.

## Not yet validated

- Complete Android Gradle build and APK/AAB packaging from this curated snapshot.
- Full Android NDK build for all target ABIs from the original project configuration.
- Custom candidate on physical Android devices.
- Full acceptance matrix across home Wi-Fi, PC hotspot, mobile hotspot, and USB tethering.
- Production release readiness, signing, privacy disclosures, Play Store data-safety declarations, and crash/ANR verification.

## Next engineering milestones

1. Restore the complete Android Studio project root and run a clean Gradle/NDK build.
2. Integrate the Custom candidate on a separate branch and test its 500 ms default on two devices.
3. Test 250, 1,000, and 2,000 ms Custom targets under repeatable network conditions.
4. Complete the four-transport acceptance matrix, including 2.4 GHz and 5 GHz Wi-Fi.
5. Investigate remaining Golden playback drops/cuts without changing its original algorithm until measurements identify the cause.
6. Redesign the Android media notification and output-device selection.
7. Prepare signed release artifacts and Play Console metadata only after release checks pass.
