# Windows audio sender

`wifi_audio_server.py` contains the desktop sender, capture-backend selection, Qt control surface, discovery/telemetry handling, and Windows integration hooks.

## Environment

- Windows desktop with a supported audio input/loopback capture backend.
- Python environment capable of installing the packages in `requirements.txt`.
- Android client and Windows host on a mutually reachable local network, or a supported USB-tethered route.

## Setup

```powershell
py -m venv .venv
.\.venv\Scripts\Activate.ps1
py -m pip install --upgrade pip
py -m pip install -r requirements.txt
py wifi_audio_server.py
```

Some Windows integration features are optional and depend on the installed backend and Windows runtime packages. The server source has fallback handling when optional integrations are unavailable. Allow the required local UDP traffic through Windows Defender Firewall only for the trusted private network used for testing.

No test logs, device names, Wi-Fi SSIDs, local IP snapshots, or credentials are included in this repository.
