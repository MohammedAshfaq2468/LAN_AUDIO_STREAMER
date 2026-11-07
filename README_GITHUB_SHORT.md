# Wi-Fi Audio Speaker — Portfolio Summary

An in-development Android + Windows audio streaming project built with Kotlin, JNI/C++, Python, Qt, UDP, and Windows audio capture APIs.

The Android client receives 48 kHz mono PCM audio from a Windows desktop sender. The project explores low-latency streaming, bounded PCM buffering, device-aware AudioTrack configuration, network route recovery, multiple capture backends, telemetry, and five playback modes.

The repository contains a curated source snapshot and an isolated Custom adaptive-engine experiment. It does not yet include the original Gradle project root, manifest, wrapper, and complete Android resources, so it is not currently a self-contained APK build. See the main README for architecture, validation status, and the exact missing build components.
