# Device and transport test matrix

Use this matrix for repeatable manual runs. Record a clean baseline, one profile per run, and both client Logcat and Windows server telemetry. Do not compare cumulative counters across runs without resetting them or recording their start values.

## Devices

| Device class | Example tested device | Current evidence |
|---|---|---|
| Older Android hardware | Lenovo Vibe K5 | Profile selection, AudioTrack setup, and network-jitter behaviour observed in historical logs |
| Modern Android hardware | Realme GT 6T | Profile selection, AudioTrack setup, and network-jitter behaviour observed in historical logs |
| Additional Android device | Samsung M10s | Historical test logs exist; latest candidate not revalidated |

## Transport coverage

| Route | 2.4 GHz | 5 GHz | USB/RNDIS | Acceptance status |
|---|---|---|---|---|
| Home Wi-Fi | Planned/retest | Planned/retest | N/A | In progress |
| Windows PC hotspot | Adapter dependent | Adapter dependent | N/A | In progress |
| Android mobile hotspot | Device dependent | Device dependent | N/A | In progress |
| USB tethering | N/A | N/A | Planned/retest | In progress |

## Per-profile capture checklist

- Record device model, Android version, Windows version, audio backend, and transport.
- Keep the same PC audio source and output volume across comparable runs.
- Avoid switching profiles during a measurement interval.
- Capture profile name, user target, effective target, low/high watermarks, queue drops, silence frames, native ring timeout, Android AudioTrack underrun count, packet drops, RTT, and jitter.
- Measure actual audio/video sync externally when assessing lip-sync; an application heuristic is not a measurement of true sync offset.
- Repeat each condition at least three times before changing a tuning constant.

## First Custom run

Start with Realme GT 6T, Home Wi-Fi, Custom 500 ms, five uninterrupted minutes. Repeat on Lenovo Vibe K5 under the same PC/network conditions. Then compare 250, 1,000, and 2,000 ms. Test different routes only after the initial baseline is understood.
