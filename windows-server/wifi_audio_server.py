#!/usr/bin/env python3

import sys
import os
import platform
import psutil
import socket
import threading
import time
import queue
import getpass
import json
import subprocess
import ctypes
import struct
import asyncio
from datetime import datetime
from collections import deque
import numpy as np

# PySide6 imports
from PySide6.QtCore import Qt, QTimer, QUrl, Signal, QVariantAnimation
from PySide6.QtGui import QDesktopServices, QPixmap, QImage, QColor
from PySide6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QGridLayout,
    QLabel, QPushButton, QTextEdit, QScrollArea, QFrame, QRadioButton, QButtonGroup,
    QMessageBox, QDialog, QSizePolicy, QSpacerItem, QGraphicsDropShadowEffect
)


# Optional dependencies
PYCaw_AVAILABLE = False
try:
    from pycaw.pycaw import AudioUtilities
    PYCaw_AVAILABLE = True
except Exception:
    pass

WIN_MEDIA_AVAILABLE = False
GlobalSystemMediaTransportControlsSessionManager = None
WIN_MEDIA_IMPORT_ERROR = ""
try:
    from winsdk.windows.media.control import GlobalSystemMediaTransportControlsSessionManager
    WIN_MEDIA_AVAILABLE = True
except Exception as exc:
    WIN_MEDIA_IMPORT_ERROR = repr(exc)
    try:
        from winrt.windows.media.control import GlobalSystemMediaTransportControlsSessionManager
        WIN_MEDIA_AVAILABLE = True
        WIN_MEDIA_IMPORT_ERROR = ""
    except Exception as exc2:
        WIN_MEDIA_IMPORT_ERROR = repr(exc2)

QR_AVAILABLE = False
_qr_import_error = None
StyledPilImage = None
RoundedModuleDrawer = None
VerticalGradiantColorMask = None
try:
    import qrcode
    try:
        from qrcode.image.styledpil import StyledPilImage
    except ImportError:
        from qrcode.image.styledpil import StyledPILImage as StyledPilImage
    from qrcode.image.styles.moduledrawers.pil import RoundedModuleDrawer
    from qrcode.image.styles.colormasks import VerticalGradiantColorMask
    from PIL import Image
    QR_AVAILABLE = True
except Exception as exc:
    _qr_import_error = str(exc)
    try:
        import qrcode
        from PIL import Image
        QR_AVAILABLE = True
    except Exception as exc2:
        _qr_import_error = str(exc2)

# Audio backends
PYAUDIOWPATCH_AVAILABLE = False
try:
    import pyaudiowpatch as pyaudio
    PYAUDIOWPATCH_AVAILABLE = True
except Exception:
    pyaudio = None

SOUNDDEVICE_AVAILABLE = False
try:
    import sounddevice as sd
    SOUNDDEVICE_AVAILABLE = True
except Exception:
    sd = None

SOUNDCARD_AVAILABLE = False
try:
    import soundcard as sc
    SOUNDCARD_AVAILABLE = True
except Exception:
    sc = None

# Core DSP functions
_hp_filter_state = [0.0, 0.0]

def _hp_iir_20hz(x, state=_hp_filter_state):
    y = np.empty_like(x)
    alpha = 0.995
    prev_x = state[0]
    prev_y = state[1]
    for i, xi in enumerate(x):
        yi = alpha * (prev_y + xi - prev_x)
        y[i] = yi
        prev_x = xi
        prev_y = yi
    state[0] = prev_x
    state[1] = prev_y
    return y

def _soft_limiter(x, thr=0.9):
    a = np.abs(x)
    m = a > thr
    if np.any(m):
        x_lim = x.copy()
        x_lim[m] = np.sign(x[m]) * (thr + np.tanh((a[m] - thr) / (1 - thr)) * (1 - thr))
        return x_lim
    return x

def process_mono_int16(stereo_int16):
    f = stereo_int16.astype(np.float32) / 32768.0
    if f.ndim == 1:
        f = f.reshape(-1, 2)
    mono = (f[:, 0] + f[:, 1]) * 0.5
    mono = _hp_iir_20hz(mono)
    mono = _soft_limiter(mono, thr=0.9)
    mono = np.clip(mono, -1.0, 1.0)
    return (mono * 32767.0).astype(np.int16)

STYLE = """
QMainWindow { background: #090f1e; }
QWidget { color: #eef4ff; font-family: 'Segoe UI'; font-size: 10pt; }
QFrame#sidebar { background: rgba(16,24,43,0.98); border-right: 1px solid rgba(151,170,210,0.34); }
QFrame#topbar { background: rgba(13,22,42,0.96); border-bottom: 1px solid rgba(151,170,210,0.30); }
QFrame#card { background: rgba(21,31,55,0.94); border: 1px solid rgba(156,178,230,0.38); border-radius: 16px; }
QFrame#hero { background: qlineargradient(x1:0,y1:0,x2:1,y2:1, stop:0 rgba(33,53,99,0.96), stop:0.52 rgba(26,39,75,0.93), stop:1 rgba(19,29,54,0.92)); border: 1px solid rgba(130,166,255,0.58); border-radius: 18px; }
QFrame#section { background: rgba(18,27,48,0.96); border: 1px solid rgba(115,145,202,0.34); border-radius: 14px; }

/* Base Push Button Styles */
QPushButton { background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 rgba(24,37,65,0.96), stop: var(--shine-pos, 0) #80a3ff, stop:1 rgba(24,37,65,0.96)); color: #ffffff; border: 1px solid rgba(167,190,243,0.48); border-radius: 9px; padding: 9px 12px; font-weight: 600; }
QPushButton:hover { background: rgba(43,64,104,0.98); border: 1.5px solid #80a3ff; color: #ffffff; }
QPushButton:pressed { background: #10203c; }
QPushButton:disabled { background: #1f2533; color: #66758e; border-color: #34435f; }


/* Global Rule: Force all buttons to be explicitly bold */
QPushButton { font-weight: bold !important; }

/* Navigation & Tools Sidebar Buttons */
QPushButton#nav { text-align: left; border: 1px solid transparent; background: transparent; padding: 11px 13px; border-radius: 9px; }
QPushButton#nav:hover { background: rgba(39,59,102,0.62); }
QPushButton#nav:checked { background: #273b66; color: white; border: 1px solid rgba(126,166,255,0.45); }
QPushButton#sidebarAction { text-align: left; padding: 10px 12px; }
QPushButton#sidebarAction:hover { background: rgba(78, 124, 255, 0.15); border: 1.5px solid #4e7cff; }

/* START SERVER BUTTON (#primary) */
QPushButton#primary { background: #4e7cff; color: white; border: 1px solid #80a3ff; textstyle: bold; }
QPushButton#danger:hover { background: #dc2626; color: #ffffff; border: 1.5px solid #ef4444; }
QPushButton#primary:pressed { background: #2246b3; }

/* STOP & DISCONNECT BUTTONS (#danger) -> Dynamic Red Hover */
QPushButton#danger { background: #2a3144; color: #ff9aac; border-color: rgba(255,126,151,0.38); }
QPushButton#danger:hover { background: #dc2626; color: #ffffff; border-color: #ef4444; }
QPushButton#danger:pressed { background: #991b1b; }

/* Miscellaneous Headers & UI Items */
QPushButton#sectionHeader { text-align: left; background: #313a54; border: none; border-radius: 11px; padding: 9px 12px; color: #dce7ff; font-weight: 700; }
QPushButton#sectionHeader:hover { background: #3a4868; }

QPushButton#qrClose { background: transparent; border: none; color: #ff8ba3; padding: 2px 6px; font-size: 11pt; }
QPushButton#qrPop { background: #45475a; border: none; color: white; padding: 2px 6px; }
QLineEdit, QComboBox, QSpinBox { background: rgba(9,17,34,0.97); color: #ffffff; border: 1px solid rgba(93,135,207,0.90); border-radius: 9px; padding: 8px; }
QTextEdit { background: rgba(9,17,34,0.97); color: #00c9a7; border: 1px solid rgba(93,135,207,0.90); border-radius: 9px; padding: 10px; font-family: Consolas; font-size: 10pt; }
QTextEdit#clientText { color: #4facf7; }
QLabel#eyebrow { color: #83a7ff; font-weight: 700; font-size: 9pt; }
QLabel#muted { color: #a7b4cf; }
QLabel#field { color: #aab9d8; font-weight: 700; font-size: 8.5pt; }
QLabel#statusGood { color: #7fffd4; font-weight: 700; }
QRadioButton { color: #dbe5fa; font-weight: 600; }
QScrollArea { border: none; background: transparent; }
QScrollBar:vertical { width: 10px; background: rgba(7,14,28,0.72); border: 1px solid rgba(126,166,255,0.22); border-radius: 5px; }
QScrollBar::handle:vertical { background: rgba(128,165,255,0.82); border: 1px solid rgba(201,217,255,0.55); border-radius: 5px; min-height: 36px; }
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0px; }
QMessageBox { background: #10192a; color: #f4f7ff; }
QMessageBox QLabel { color: #f4f7ff; }
"""



def card() -> QFrame:
    widget = QFrame()
    widget.setObjectName("card")
    return widget

def field_label(text: str) -> QLabel:
    label = QLabel(text.upper())
    label.setObjectName("field")
    return label

class CollapsibleCard(QFrame):
    """Qt equivalent of the old Tk CollapsibleSection.

    The old server exposed four accordion sections. This keeps the same
    interaction model without importing Tkinter into the Qt server.
    """
    def __init__(self, parent=None, title="", default_open=True, on_toggle=None):
        super().__init__(parent)
        self.setObjectName("section")
        self.on_toggle = on_toggle
        self.base_title = title
        outer = QVBoxLayout(self)
        outer.setContentsMargins(4, 4, 4, 4)
        outer.setSpacing(4)

        self.header = QPushButton()
        self.header.setObjectName("sectionHeader")
        self.header.clicked.connect(self.toggle)
        outer.addWidget(self.header)

        self.content = QWidget()
        self.content_layout = QVBoxLayout(self.content)
        self.content_layout.setContentsMargins(8, 7, 8, 8)
        self.content_layout.setSpacing(7)
        outer.addWidget(self.content)
        self.set_expanded(default_open, notify=False)

    @property
    def show(self):
        return self.content.isVisible()

    def set_expanded(self, expanded, notify=True):
        self.content.setVisible(bool(expanded))
        self.header.setText(("▼ " if expanded else "▶ ") + self.base_title)
        if notify and self.on_toggle:
            try:
                self.on_toggle()
            except Exception:
                pass

    def toggle(self):
        self.set_expanded(not self.content.isVisible(), notify=True)


class WifiAudioServer(QMainWindow):
    # All log messages, including messages produced by worker/audio threads,
    # are marshalled onto the Qt GUI thread through this signal.
    log_signal = Signal(str)

    def __init__(self):
        super().__init__()
        self._log_history = deque(maxlen=4000)
        self.log_signal.connect(self._append_log_message)
        self.setWindowTitle("🚀 WiFi Audio Server - Multi-Backend Edition")
        self.resize(1024, 760)
        self.setMinimumSize(1024, 760)
        self.showMaximized()
    
        self.sequence_number = 0
        # Repeated capture-backlog warnings are rate-limited independently of
        # queue handling so logging cannot flood the UI during sustained load.
        self._last_capture_backlog_log_at = 0.0
        self._capture_backlog_log_interval_s = 30.0
        self._capture_backlog_log_lock = threading.Lock()
        self._rate_limited_log_lock = threading.Lock()
        self._rate_limited_log_times = {}
        self._telemetry_log_times = {}
        self._telemetry_log_states = {}
        self.capture_queue_overruns = 0
        self.medium_capture_queue_drops = 0
        self.low_capture_queue_drops = 0
        self.low_silence_injections = 0
        self._medium_backlog_since = 0.0
        self._low_backlog_since = 0.0
        self._last_medium_trim_at = 0.0
        self.packet_queue = deque(maxlen=100)
        self.clients = {}

        self.server_running = False
        self.discovery_running = False
        self.audio_port = 50007
        self.discovery_port = 50000
        self.legacy_discovery_port = 32000
        self.telemetry_port = 50009
        pc_name = (platform.node() or socket.gethostname() or "WiFiAudio-PC").strip()
        user_name = getpass.getuser()
        self.server_name = f"{user_name}'s ({pc_name})"
        self.server_socket = None
        self.discovery_socket = None
        self.discovery_socket_legacy = None
        self.telemetry_socket = None
        self.telemetry_thread = None

        self.windows_hotspot_state = False
        self.windows_hotspot_adapter = "N/A"
        self.windows_hotspot_ip = "N/A"
        self.windows_wifi_direct_state = False
        self.windows_wifi_direct_adapters = []
        self.active_server_ip = "127.0.0.1"
        self.active_network_mode = "Unknown"
        self.active_network_adapter = "N/A"
        self.active_network_ssid = "N/A"
        self.wifi_adapter = "WiFi"
        self.wifi_ssid = "N/A"
        self._wifi_receiver_info_cache = None
        self._windows_adapter_details = {}
        self._network_state_lock = threading.Lock()
        self._last_hotspot_state = None
        self._last_hotspot_ip = None
        self._last_displayed_server_ip = None
        # QR is endpoint-driven. This key lets us regenerate only when the
        # actual QR payload changes (IP/port/backend/server identity), rather
        # than rebuilding the QR image on every UI tick.
        self._last_qr_payload = None
        self._last_qr_endpoint_ip = None
        self._network_poll_ms = 250
        self._network_monitor_stop = threading.Event()
        self._network_monitor_thread = None

        self.client_socket = None
        self.client_address = None
        self.client_info = None
        self.client_connected_time = None
        self.client_last_seen = 0.0
        self.client_watchdog_timeout = 8.0
        self.last_client_telemetry_conn = None
        self.last_client_stats_text = None

        self.pc_media_state = "UNKNOWN"
        self._win_media_last_error = ""
        self.pc_muted = None
        self.pc_media_source = "UNAVAILABLE"
        self.pc_mute_source = "UNAVAILABLE"
        self.pc_state_lock = threading.Lock()
        self.pc_state_stop_event = threading.Event()
        self.pc_state_thread = None

        self.samplerate = 48000
        self.capture_blocksize = 480
        self.stream_blocksize = 480
        self.buffer_queue_size = 12
        self.min_buffer_level = 2
        self.audio_channels = 2
        self.packet_size = 960
        self.server_capture_format = "UNKNOWN"
        self.server_send_format = "MONO_16BIT"
        self.hp_prev_x = 0.0
        self.hp_prev_y = 0.0

        self.zero_latency = True
        self.adaptive_buffering = False
        self.current_backend = None
        self.backend_priority = ["pyaudiowpatch", "sounddevice", "soundcard", "vb_cable"]
        self.pyaudio_instance = None
        self.audio_stream = None
        self.capture_method = None
        self.capture_param = None
        self.local_ip = self.get_local_ip()

        self.silence_threshold = 0.0001
        self.consecutive_silence_count = 0
        self.max_consecutive_silence = 10
        self.real_audio_detected = False
        self.real_audio_frames = 0
        self.audio_level_history = []
        self.packets_sent = 0
        self.bytes_sent = 0
        self.last_stats_time = time.time()
        self.buffer_underruns = 0
        self.silence_injections = 0
        self.backend_switches = 0
        self.total_audio_processed = 0
        self.ping_response_count = 0
        self.audio_buffer_queue = queue.Queue(maxsize=self.buffer_queue_size)
        # Per-client streaming profile. The adaptive sender remains the default;
        # named profiles are enabled only after the active Android client announces them.
        self.client_streaming_profile = "adaptive_v2"
        self.client_buffer_mode = "medium"
        self._client_streaming_profile_lock = threading.Lock()

        self.right_sidebar_visible = True
        self.client_panel_expanded = True
        self.embedded_qr_visible = False
        self.qr_window = None

        self.setup_ui()
        self._flush_log_history()
        # Prime the Windows adapter-description cache BEFORE the startup
        # diagnostics. RNDIS devices are often named simply "Ethernet 4/5",
        # so InterfaceDescription is needed to avoid the Wi-Fi/USB ambiguity.
        try:
            self._refresh_windows_adapter_details()
        except Exception:
            pass
        self.refresh_network_state(log_change=False)
        self.log_system_info()
        self.start_network_identity_worker()
        self.display_ip()
        self.detect_best_backend()
        self.update_client_display()
        # No-client state starts with the QR embedded in Server Information.
        QTimer.singleShot(0, self._sync_qr_visibility)
        
    def enable_glass(self) -> None:
        self.setWindowOpacity(0.92)
        if sys.platform != "win32":
            return
        try:
            class AccentPolicy(ctypes.Structure):
                _fields_ = [("state", ctypes.c_int), ("flags", ctypes.c_int), ("color", ctypes.c_uint), ("animation", ctypes.c_int)]
            class WindowCompositionAttributeData(ctypes.Structure):
                _fields_ = [("attribute", ctypes.c_int), ("data", ctypes.POINTER(AccentPolicy)), ("size", ctypes.c_size_t)]
            policy = AccentPolicy(3, 0, 0, 0)
            data = WindowCompositionAttributeData(19, ctypes.pointer(policy), ctypes.sizeof(policy))
            compositor = ctypes.windll.user32.SetWindowCompositionAttribute
            compositor.argtypes = [ctypes.c_void_p, ctypes.POINTER(WindowCompositionAttributeData)]
            compositor.restype = ctypes.c_int
            compositor(ctypes.c_void_p(int(self.winId())), ctypes.byref(data))
        except Exception:
            pass

    def setup_ui(self):
        central = QWidget()
        root = QHBoxLayout(central)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        # ------------------------------------------------------------------
        # Left navigation/action sidebar — all server controls live here.
        # ------------------------------------------------------------------
        sidebar = QFrame()
        sidebar.setObjectName("sidebar")
        sidebar.setFixedWidth(265)
        sb = QVBoxLayout(sidebar)
        sb.setContentsMargins(15, 18, 15, 16)
        sb.setSpacing(7)

        logo = QLabel("🚀  WiFi Audio Server")
        logo.setStyleSheet("font-size: 15pt; font-weight: 800; color: #ffffff;")
        sb.addWidget(logo)
        sub = QLabel("MULTI-BACKEND EDITION")
        sub.setObjectName("eyebrow")
        sb.addWidget(sub)
        sb.addSpacing(15)

        nav = QLabel("SERVER CONTROLS")
        nav.setObjectName("field")
        sb.addWidget(nav)

        self.start_btn = QPushButton("🚀  START SERVER ")
        self.start_btn.setObjectName("primary")
        self.start_btn.clicked.connect(lambda: self.start_server(False))
        sb.addWidget(self.start_btn)

        self.stop_btn = QPushButton("⏹  STOP SERVER")
        self.stop_btn.setObjectName("danger")
        self.stop_btn.clicked.connect(self.stop_server)
        self.stop_btn.setEnabled(False)
        sb.addWidget(self.stop_btn)

        self.force_disconnect_btn = QPushButton("⚡  FORCE DISCONNECT")
        self.force_disconnect_btn.setObjectName("danger")
        self.force_disconnect_btn.clicked.connect(self.force_disconnect_client)
        self.force_disconnect_btn.setEnabled(False)
        sb.addWidget(self.force_disconnect_btn)

        sb.addSpacing(8)
        tools = QLabel("TOOLS")
        tools.setObjectName("field")
        sb.addWidget(tools)
        self.test_audio_btn = QPushButton("🎵  TEST AUDIO"); self.test_audio_btn.setObjectName("sidebarAction"); self.test_audio_btn.clicked.connect(self.test_audio_capture); sb.addWidget(self.test_audio_btn)
        self.setup_audio_btn = QPushButton("🔧  SETUP GUIDE"); self.setup_audio_btn.setObjectName("sidebarAction"); self.setup_audio_btn.clicked.connect(self.show_setup_guide); sb.addWidget(self.setup_audio_btn)
        self.backend_switch_btn = QPushButton("🔄  SWITCH BACKEND"); self.backend_switch_btn.setObjectName("sidebarAction"); self.backend_switch_btn.clicked.connect(self.switch_to_next_backend); sb.addWidget(self.backend_switch_btn)
        self.qr_btn = QPushButton("📱  SHOW QR"); self.qr_btn.setObjectName("sidebarAction"); self.qr_btn.clicked.connect(self.show_qr_code); sb.addWidget(self.qr_btn)

        sb.addSpacing(8)
        source = QLabel("AUDIO SOURCE")
        source.setObjectName("field")
        sb.addWidget(source)
        self.bg = QButtonGroup(self)
        self.auto_rb = QRadioButton("🚀 AUTO")
        self.pyaudio_rb = QRadioButton("🎯 PyAudioWPatch")
        self.sounddevice_rb = QRadioButton("🔊 SoundDevice")
        self.auto_rb.setChecked(True)
        self.bg.addButton(self.auto_rb); self.bg.addButton(self.pyaudio_rb); self.bg.addButton(self.sounddevice_rb)
        self.auto_rb.toggled.connect(lambda checked: self.set_auto_mode() if checked else None)
        self.pyaudio_rb.toggled.connect(lambda checked: self.set_pyaudiowpatch_mode() if checked else None)
        self.sounddevice_rb.toggled.connect(lambda checked: self.set_sounddevice_mode() if checked else None)
        sb.addWidget(self.auto_rb); sb.addWidget(self.pyaudio_rb); sb.addWidget(self.sounddevice_rb)

        sb.addSpacing(8)
        status_card = card()
        sc = QVBoxLayout(status_card); sc.setContentsMargins(12, 11, 12, 11); sc.setSpacing(5)
        sc.addWidget(QLabel("● SERVER STATUS", objectName="eyebrow"))
        self.sidebar_status = QLabel("Initializing…"); self.sidebar_status.setWordWrap(True); self.sidebar_status.setObjectName("muted"); sc.addWidget(self.sidebar_status)
        self.sidebar_backend = QLabel("Backend: detecting…"); self.sidebar_backend.setWordWrap(True); self.sidebar_backend.setObjectName("muted"); sc.addWidget(self.sidebar_backend)
        sb.addWidget(status_card)
        sb.addStretch(1)
        local = QLabel("LOCAL CONTROL PLANE\n ")
        local.setObjectName("muted"); local.setWordWrap(True); sb.addWidget(local)
        root.addWidget(sidebar)

        # ------------------------------------------------------------------
        # Main content + top bar.
        # ------------------------------------------------------------------
        content = QWidget()
        cl = QVBoxLayout(content); cl.setContentsMargins(0, 0, 0, 0); cl.setSpacing(0)
        top = QFrame(); top.setObjectName("topbar")
        tl = QHBoxLayout(top); tl.setContentsMargins(24, 14, 24, 14)
        title_box = QVBoxLayout(); title_box.setSpacing(2)
        self.page_title = QLabel("WiFi Audio Server "); self.page_title.setStyleSheet("font-size: 18pt; font-weight: 800;"); title_box.addWidget(self.page_title)
        self.page_subtitle = QLabel("Stream your PC audio to  Android Devices Over WiFi | LAN "); self.page_subtitle.setObjectName("muted"); title_box.addWidget(self.page_subtitle)
        tl.addLayout(title_box); tl.addStretch(1)
        self.engine_badge = QLabel("●  Engines detecting…"); self.engine_badge.setStyleSheet("background:#173a38; color:#8effdf; padding:8px 11px; border-radius:9px; font-weight:700;"); tl.addWidget(self.engine_badge)
        cl.addWidget(top)

        scroll = QScrollArea(); scroll.setWidgetResizable(True); scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        page = QWidget(); page.setStyleSheet("background: qradialgradient(cx:0.86, cy:0.02, radius:1.1, stop:0 #172746, stop:0.45 #0b1325, stop:1 #090f1e);")
        pl = QVBoxLayout(page); pl.setContentsMargins(22, 20, 22, 20); pl.setSpacing(12)


        # Server Information accordion, including the legacy styled QR.
        self.info_collapsible = CollapsibleCard(title="🔧 Server Information", default_open=True, on_toggle=self.auto_resize_window)
        info = self.info_collapsible.content
        # CollapsibleCard.content already owns a QVBoxLayout; reuse it.
        il = QHBoxLayout(); il.setContentsMargins(2, 2, 2, 2); il.setSpacing(10)
        details = QVBoxLayout(); details.setSpacing(4)
        self.ip_label = QLabel("IP Address: Loading..."); self.ip_label.setStyleSheet("color:#4facf7; font-weight:800;"); details.addWidget(self.ip_label)
        self.audio_port_label = QLabel(f"Audio Port: {self.audio_port}"); details.addWidget(self.audio_port_label)
        self.discovery_label = QLabel(f"Discovery: {self.discovery_port}, {self.legacy_discovery_port}"); details.addWidget(self.discovery_label)
        self.rate_label = QLabel(f"Sample Rate: {self.samplerate} Hz MONO"); details.addWidget(self.rate_label)
        self.capture_label = QLabel(f"Capture Block: {self.capture_blocksize} samples"); details.addWidget(self.capture_label)
        self.stream_label = QLabel(f"Stream Block: {self.stream_blocksize} samples"); details.addWidget(self.stream_label)
        self.queue_label = QLabel(f"Buffer Queue: {self.buffer_queue_size} buffers"); details.addWidget(self.queue_label)
        self.backend_label = QLabel("Active Backend: Detecting..."); self.backend_label.setStyleSheet("color:#ffb84d; font-weight:800;"); details.addWidget(self.backend_label)
        self.network_mode_label = QLabel("Network Mode: Detecting..."); details.addWidget(self.network_mode_label)
        self.network_adapter_label = QLabel("Network Adapter: Detecting..."); details.addWidget(self.network_adapter_label)
        self.network_ssid_label = QLabel("SSID: Detecting..."); details.addWidget(self.network_ssid_label)
        il.addLayout(details, 1)

        self.embedded_qr_frame = QFrame(); self.embedded_qr_frame.setObjectName("card"); self.embedded_qr_frame.setMinimumWidth(300); self.embedded_qr_frame.setVisible(False)
        qrl = QVBoxLayout(self.embedded_qr_frame); qrl.setContentsMargins(7, 7, 7, 7); qrl.setSpacing(4)
        qrh = QHBoxLayout(); qrh.addWidget(QLabel("     𝄃𝄂𝄀𝄁𝄃𝄂𝄂𝄃 QR Code ↔ 📱 Android Client", objectName="statusGood")); qrh.addStretch(1)
        close = QPushButton("✕"); close.setObjectName("qrClose"); close.setToolTip("Close QR"); close.clicked.connect(self.hide_embedded_qr); qrh.addWidget(close)
        qrl.addLayout(qrh)
        self.embedded_qr_lbl = QLabel(); self.embedded_qr_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter); self.embedded_qr_lbl.setMinimumSize(270, 270); qrl.addWidget(self.embedded_qr_lbl)
        self.embedded_qr_info_lbl = QLabel(); self.embedded_qr_info_lbl.setAlignment(Qt.AlignmentFlag.AlignCenter); self.embedded_qr_info_lbl.setWordWrap(True); qrl.addWidget(self.embedded_qr_info_lbl)
        il.addWidget(self.embedded_qr_frame, 0)
        self.info_collapsible.content_layout.addLayout(il)
        pl.addWidget(self.info_collapsible)

        self.stats_collapsible = CollapsibleCard(title="📊 Real-Time Multi-Backend Stats", default_open=False, on_toggle=self.auto_resize_window)
        stats_body = self.stats_collapsible.content
        self.stats_label = QLabel("Ready for multi-backend streaming…"); self.stats_label.setWordWrap(True); self.stats_label.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse); stats_body.layout().addWidget(self.stats_label)
        pl.addWidget(self.stats_collapsible)

        self.content_collapsible = CollapsibleCard(title="🎵 Audio Content Detection & Backend Status", default_open=False, on_toggle=self.auto_resize_window)
        content_body = self.content_collapsible.content
        self.content_label = QLabel("⏳ Waiting for audio detection…"); self.content_label.setWordWrap(True); content_body.layout().addWidget(self.content_label)
        self.backend_perf_label = QLabel("Backend Performance: Initializing…"); self.backend_perf_label.setObjectName("muted"); content_body.layout().addWidget(self.backend_perf_label)
        pl.addWidget(self.content_collapsible)

        self.log_collapsible = CollapsibleCard(title="📊 Server Logs", default_open=True, on_toggle=self.auto_resize_window)
        log_body = self.log_collapsible.content
        self.log_text = QTextEdit(); self.log_text.setReadOnly(True); self.log_text.setMinimumHeight(280); log_body.layout().addWidget(self.log_text)
        pl.addWidget(self.log_collapsible)

        scroll.setWidget(page); cl.addWidget(scroll, 1)
        root.addWidget(content, 1)

        # ------------------------------------------------------------------
        # Right diagnostics sidebar: vertically collapsible body + horizontal
        # hide/restore, matching the legacy UI behavior.
        # ------------------------------------------------------------------
        self.right_container = QFrame(); self.right_container.setObjectName("card"); self.right_container.setFixedWidth(360)
        rl = QVBoxLayout(self.right_container); rl.setContentsMargins(8, 8, 8, 8); rl.setSpacing(7)
        rh = QHBoxLayout()
        self.client_title_btn = QPushButton(" 👥  Client Status & Diagnostics 📱"); self.client_title_btn.setObjectName("sectionHeader"); self.client_title_btn.clicked.connect(self.toggle_client_panel_vertical); rh.addWidget(self.client_title_btn, 1)
        self.client_hide_btn = QPushButton("◀ Hide Panel"); self.client_hide_btn.clicked.connect(self.toggle_right_sidebar); rh.addWidget(self.client_hide_btn)
        rl.addLayout(rh)
        self.client_body = QWidget(); cbl = QVBoxLayout(self.client_body); cbl.setContentsMargins(0, 0, 0, 0)
        self.client_text = QTextEdit(); self.client_text.setObjectName("clientText"); self.client_text.setReadOnly(True); cbl.addWidget(self.client_text)
        rl.addWidget(self.client_body, 1)
        root.addWidget(self.right_container)

        self.right_expand_btn = QPushButton("▶\n\n\n👥\n\n\nA\nC\nT\nI\nV\nE\n\n📱\n\nC\nL\nI\nE\nN\nT\nS\n\n\n\n▶")
        self.right_expand_btn.setFixedWidth(38); self.right_expand_btn.clicked.connect(lambda: self.toggle_right_sidebar(force_show=True)); self.right_expand_btn.setVisible(False)
        root.addWidget(self.right_expand_btn)

        self.setCentralWidget(central)
        self._display_timer = QTimer(self)
        self._display_timer.timeout.connect(self.update_ui_snapshot)
        # 250 ms keeps the server card/QR responsive to Windows Hotspot,
        # Wi-Fi and USB/RNDIS address changes without touching the audio path.
        self._display_timer.start(250)


        # --- BORDER SHINE & HOVER GLOW ANIMATION CONTROLLER ---
        self.glow_effects = {}       
        buttons_to_glow = [
            (self.start_btn, "#4e7cff"),
            (self.stop_btn, "#dc2626"),
            (self.force_disconnect_btn, "#dc2626"),
            (self.test_audio_btn, "#4e7cff"),
            (self.setup_audio_btn, "#9bbaff"),
            (self.backend_switch_btn, "#9bbaff"),
            (self.client_hide_btn, "#9bbaff"),
            (self.client_title_btn, "#9bbaff"), 
            (self.qr_btn, "#9bbaff")
        ]
        
        self.border_shine_anim = QVariantAnimation(self)
        self.border_shine_anim.setDuration(1500)
        self.border_shine_anim.setStartValue(0.0)
        self.border_shine_anim.setEndValue(1.0)
        self.border_shine_anim.setLoopCount(-1)
        self.border_shine_anim.valueChanged.connect(self._update_border_shine)
        self.border_shine_anim.start()
        
        for btn, color_hex in buttons_to_glow:
            effect = QGraphicsDropShadowEffect(btn)
            effect.setBlurRadius(0)
            effect.setColor(QColor(color_hex))
            effect.setOffset(0, 0)
            btn.setGraphicsEffect(effect)
            self.glow_effects[btn] = (effect, color_hex)
        
             # Connect hover events to show glow only on mouse over
            btn.enterEvent = lambda event, b=btn: self._on_button_hover_enter(b)
            btn.leaveEvent = lambda event, b=btn: self._on_button_hover_leave(b)

    
    def _on_button_hover_enter(self, btn):
            if btn.isEnabled() and btn in self.glow_effects:
                effect, _ = self.glow_effects[btn]
                effect.setBlurRadius(15)

    def _on_button_hover_leave(self, btn):
        if btn in self.glow_effects:
            effect, _ = self.glow_effects[btn]
            effect.setBlurRadius(0)

    def _update_border_shine(self, val):
        pos_start = max(0.0, val - 0.15)
        pos_mid = val
        pos_end = min(1.0, val + 0.15)
        for btn in self.glow_effects.keys():
            if btn.underMouse() and btn.isEnabled():
                btn.setStyleSheet(f"QPushButton:hover {{ border: 1.5px solid qlineargradient(x1:0, y1:0, x2:1, y2:1, stop:0 transparent, stop:{pos_start:.2f} transparent, stop:{pos_mid:.2f} #ffffff, stop:{pos_end:.2f} transparent, stop:1 transparent); }}")
    
    def toggle_client_panel_vertical(self):
        self.client_panel_expanded = not self.client_panel_expanded
        self.client_body.setVisible(self.client_panel_expanded)
        self.client_title_btn.setText(("▼ " if self.client_panel_expanded else "▶ ") + "👥 Client Status & Diagnostics")
        self._keep_window_maximized()

    def update_ui_snapshot(self):
        try:
            # This is deliberately a lightweight IP/interface poll. SSID and
            # deep Windows adapter identification remain in the background
            # worker. It makes endpoint changes visible to the GUI immediately.
            network_changed = self.refresh_network_state(log_change=False)
            self.display_ip()
            self._refresh_qr_if_endpoint_changed(force=network_changed)
            self.update_client_display()
            self.update_stats_panel()
            self._sync_qr_visibility()
        except Exception as exc:
            self.log(f"⚠️ UI snapshot error: {exc}")

    def _refresh_qr_if_endpoint_changed(self, force=False):
        """Regenerate the embedded QR only when its payload actually changes."""
        if not QR_AVAILABLE:
            return
        try:
            # Do not rebuild a QR that is intentionally hidden while a client
            # is connected. _sync_qr_visibility() will recreate it after the
            # client disconnects.
            if getattr(self, "client_address", None) is not None:
                return

            primary_ip = self.get_primary_server_ip()
            payload = (
                f"WIFI_AUDIO_SERVER:{primary_ip}:{self.audio_port}:"
                f"{self.server_name}:{self.current_backend or 'Unknown'}"
            )

            if not force and payload == self._last_qr_payload:
                return

            self._last_qr_payload = payload
            self._last_qr_endpoint_ip = primary_ip

            # show_qr_code() rebuilds the existing embedded widget in-place.
            # Do not emit the full QR-generation log spam unless the endpoint
            # really changed after the initial render.
            self.show_qr_code(log_generation=not force)
        except Exception as exc:
            self.log(f"⚠️ Live QR refresh failed: {exc}")

    def update_stats_panel(self):
        try:
            queue_level = self.audio_buffer_queue.qsize()
        except Exception:
            queue_level = 0
        total = max(int(self.packets_sent) + 1, 1)
        audio_pct = (int(self.real_audio_frames) / total) * 100.0
        backend_perf = "Excellent" if self.real_audio_frames > int(self.silence_injections) else "Poor"
        text = (
            f"Packets Sent: {self.packets_sent:,}    Bytes Sent: {self.bytes_sent:,}\n"
            f"Real Audio Frames: {self.real_audio_frames:,}    Total Audio Processed: {self.total_audio_processed:,} samples\n"
            f"Buffer: {queue_level}/{self.buffer_queue_size}    Underruns: {self.buffer_underruns:,}    Low Queue Drops: {self.low_capture_queue_drops:,}    Low Silence: {self.low_silence_injections:,}    Silence Injections: {self.silence_injections:,}\n"
            f"Backend: {self.current_backend or 'None'}    Switches: {self.backend_switches}    Ping Responses: {self.ping_response_count}\n"
            f"Audio Content Ratio: {audio_pct:.1f}%    Backend Performance: {backend_perf}\n"
            f"Capture Format: {self.server_capture_format}    Send Format: {self.server_send_format}"
        )
        self.stats_label.setText(text)
        self.backend_perf_label.setText(f"Backend Performance: {backend_perf}  |  Capture: {self.server_capture_format}  |  Send: {self.server_send_format}")
        if self.current_backend:
            self.sidebar_backend.setText(f"Backend: {self.current_backend.upper()}")
        self.sidebar_status.setText("● Running" if self.server_running else "○ Stopped")


        def update_stats_panel(self):
            # Existing panel text generation code...
            self.stats_label.setText(text)
            self.backend_perf_label.setText(f"Backend Performance: {backend_perf}  |  Capture: {self.server_capture_format}  |  Send: {self.server_send_format}")
            if self.current_backend:
                self.sidebar_backend.setText(f"Backend: {self.current_backend.upper()}")
            self.sidebar_status.setText("● Running" if self.server_running else "○ Stopped")
            
            # --- NEW CODE: FORCE REMOVE GLOW FROM DISABLED BUTTONS ---
            for btn,(effect, _) in self.glow_effects.items():
                if not btn.isEnabled():
                    effect.setBlurRadius(0)

     

        
    def after(self, ms, func):
        QTimer.singleShot(ms, func)
        
    def _append_log_message(self, message):
        """GUI-thread sink for every server log message."""
        timestamp = time.strftime("%H:%M:%S")
        line = f"[{timestamp}] {message}"
        self._log_history.append(line)
        try:
            log_widget = getattr(self, "log_text", None)
            if log_widget is not None:
                log_widget.append(line)
                bar = log_widget.verticalScrollBar()
                bar.setValue(bar.maximum())
        except Exception:
            # Never allow diagnostics/UI logging to affect the audio pipeline.
            pass

    def _flush_log_history(self):
        """Flush any messages emitted before the log widget existed."""
        try:
            log_widget = getattr(self, "log_text", None)
            if log_widget is None:
                return
            log_widget.clear()
            log_widget.setPlainText("\n".join(self._log_history))
            bar = log_widget.verticalScrollBar()
            bar.setValue(bar.maximum())
        except Exception:
            pass

    def log(self, message):
        # Signal delivery is thread-safe and guarantees worker/audio messages
        # reach the visible Server Logs section, not only stdout/stderr.
        self.log_signal.emit(str(message))
        print(message, flush=True)

    def log_rate_limited(self, key, message, interval_s=5.0):
        """Emit the first diagnostic immediately, then at most once per interval."""
        now = time.monotonic()
        with self._rate_limited_log_lock:
            last = self._rate_limited_log_times.get(key, 0.0)
            if now - last < interval_s:
                return False
            self._rate_limited_log_times[key] = now
        self.log(message)
        return True
        
    def show_setup_guide(self):
        guide_text = """🚀 Multi-Backend Audio Setup Guide 🚀

BACKEND 1 — PyAudioWPatch:
• TRUE WASAPI loopback capture on Windows.
• Best first choice when a [Loopback] speaker endpoint is available.

BACKEND 2 — SoundDevice:
• Alternative WASAPI-compatible capture path.
• Useful when PyAudioWPatch cannot open the loopback device.

BACKEND 3 — SoundCard:
• Fallback loopback capture.
• Can use loopback speakers / Stereo Mix / VB-Cable endpoints.

MANUAL OPTIONS:
1. Enable Stereo Mix in Windows Recording devices if required.
2. VB-Cable can be used as a virtual audio route.
3. Play PC media before testing capture.

TESTING:
• Click TEST AUDIO and play music for 10 seconds.
• Use SWITCH BACKEND if capture is silent.
• The streaming protocol remains UDP, 48 kHz, 480-sample frames.
"""
        QMessageBox.information(self, "Setup Guide", guide_text)
        
    def test_audio_capture(self):
        if not self.current_backend:
            self.detect_best_backend()
        if not self.current_backend:
            QMessageBox.critical(self, "No Backend Available", "No audio backends available for testing!")
            return
        self.log(f"🔧 Testing audio capture with {self.current_backend} backend...")
        self.log("🎵 Play some music now to test!")
        threading.Thread(target=self._test_thread, daemon=True).start()
        
    def set_auto_mode(self):
        self.mode_var = "auto"
        self.log("🚀 AUTO mode selected — detecting the best available backend.")
        self.detect_best_backend()
        
    def set_pyaudiowpatch_mode(self):
        self.mode_var = "pyaudiowpatch"
        self.current_backend = "pyaudiowpatch"
        self.backend_label.setText("Active Backend: PYAUDIOWPATCH (Manual)")
        self.backend_label.setStyleSheet("color:#80a5ff; font-weight:800;")
        self.log("🎯 PyAudioWPatch mode selected — using WASAPI loopback.")
        
    def set_sounddevice_mode(self):
        self.mode_var = "sounddevice"
        self.current_backend = "sounddevice"
        self.backend_label.setText("Active Backend: SOUNDDEVICE (Manual)")
        self.backend_label.setStyleSheet("color:#80a5ff; font-weight:800;")
        self.log("🔊 SoundDevice mode selected — using SoundDevice WASAPI.")
        
    def _keep_window_maximized(self):
        """Keep the server window maximized after any internal UI resize/toggle."""
        try:
            if not self.isMaximized():
                self.setWindowState(self.windowState() | Qt.WindowMaximized)
            self.showMaximized()
        except Exception:
            pass

    def auto_resize_window(self):
        # Preserve the legacy callback name, but never let an accordion/layout
        # resize restore the QMainWindow to a normal window on Windows.
        self._keep_window_maximized()

    def display_ip(self):
        with self._network_state_lock:
            hotspot_on = bool(self.windows_hotspot_state)
            hotspot_ip = self.windows_hotspot_ip
            active_ip = self.active_server_ip
            mode = self.active_network_mode
            adapter = self.active_network_adapter
            ssid = self.active_network_ssid
        display_ip = self.get_primary_server_ip()
        self._last_displayed_server_ip = display_ip
        def _update():
            try:
                self.ip_label.setText(f"IP Address: {display_ip}")
                self.network_mode_label.setText(f"Network Mode: {mode}")
                self.network_adapter_label.setText(f"Network Adapter: {adapter}")
                self.network_ssid_label.setText(f"SSID: {ssid}")
                self.backend_label.setText(f"Active Backend: {self.current_backend or 'Detecting...'}")
                self.sidebar_status.setText("● Running" if self.server_running else "○ Stopped")
                self.sidebar_backend.setText(f"Backend: {(self.current_backend or 'detecting').upper()}")
                if hotspot_on:
                    self.network_mode_label.setText(f"Network Mode: {mode}  |  Hotspot: {hotspot_ip}")
            except Exception:
                pass
        self.after(0, _update)
            
    def hide_embedded_qr(self):
        self.embedded_qr_frame.setVisible(False)
        self.embedded_qr_visible = False
        self.auto_resize_window()

    def show_qr_code(self, log_generation=True):
        """Show/regenerate the connection QR inside Server Information only.

        log_generation=False is used by the live network monitor so the log
        panel does not get spammed every time Windows changes an interface.
        """
        if not QR_AVAILABLE:
            msg = "QR Code feature requires qrcode and Pillow.\n\npip install qrcode pillow"
            if _qr_import_error:
                msg += f"\n\nImport error: {_qr_import_error}"
            QMessageBox.critical(self, "Missing Library", msg)
            return
        try:
            img, primary_ip = self.generate_qr_image(box_size=5, border=2)
            import io
            data = io.BytesIO()
            img.save(data, format="PNG")
            qimg = QImage.fromData(data.getvalue(), "PNG")
            pix = QPixmap.fromImage(qimg)
            self.embedded_qr_lbl.setPixmap(
                pix.scaled(270, 270, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
            )
            self.embedded_qr_info_lbl.setText(
                f"Scan Using our Android App\nIP: {primary_ip} | Port: {self.audio_port}\nBackend: {self.current_backend or 'Unknown'}"
            )
            self.info_collapsible.set_expanded(True, notify=False)
            self.embedded_qr_frame.setVisible(True)
            self.embedded_qr_visible = True
            self._last_qr_endpoint_ip = primary_ip
            self._last_qr_payload = (
                f"WIFI_AUDIO_SERVER:{primary_ip}:{self.audio_port}:"
                f"{self.server_name}:{self.current_backend or 'Unknown'}"
            )
            if log_generation:
                self.log("🔍 Generating Connection QR Code...")
                self.log(f"   - Target IP: {primary_ip}")
                self.log(f"   - Target Port: {self.audio_port}")
                self.log(f"   - Embedded Backend: {self.current_backend}")
            self.auto_resize_window()
        except Exception as exc:
            self.log(f"❌ Error displaying QR: {exc}")
            QMessageBox.critical(self, "QR Error", str(exc))

    def _sync_qr_visibility(self):
        """No client => show embedded QR; active client => hide it.

        SHOW QR remains a manual override while a client is connected.
        """
        try:
            active_client = getattr(self, "client_address", None) is not None
            if active_client:
                if self.embedded_qr_visible:
                    self.hide_embedded_qr()
                return
            if not self.embedded_qr_visible:
                self.show_qr_code()
        except Exception as exc:
            self.log(f"⚠️ QR visibility sync failed: {exc}")

    def _find_existing_qr_window(self):
        # Kept only for compatibility with older callers; Qt QR is embedded now.
        return None

    def popout_qr_window(self):
        # Legacy compatibility hook: never create a second QR window.
        self.show_qr_code()

    def toggle_right_sidebar(self, force_show=None):
        if force_show is None:
            force_show = not self.right_sidebar_visible
        if force_show:
            self.right_container.setVisible(True)
            self.right_expand_btn.setVisible(False)
            self.right_sidebar_visible = True
            self.client_hide_btn.setText("◀ Hide Panel")
        else:
            self.right_container.setVisible(False)
            self.right_expand_btn.setVisible(True)
            self.right_sidebar_visible = False
        self._keep_window_maximized()
            
    def update_content_indicator(self, has_real_audio, rms):
        if has_real_audio:
            self.real_audio_detected = True
            self.content_label.setText(f"🎵 REAL AUDIO DETECTED!  Level: {rms:.4f}")
            self.content_label.setStyleSheet("color:#a6e3a1; font-weight:700;")
        else:
            self.content_label.setText(f"🔇 Silence detected.  Level: {rms:.4f}")
            self.content_label.setStyleSheet("color:#ffb84d; font-weight:700;")
            
    def run(self):
        self.after(1000, lambda: self.start_server(auto_start=True))
        self.refresh_network_state(log_change=True)
        self.start_network_identity_worker()
        self.display_ip()
        self.log("🚀 WiFi Audio Server - Multi-Backend Edition initializing...")
        self.log(f"🌐 Server IP: {self.get_primary_server_ip()}")
        self.log(f"🌐 All local IPv4 addresses: {self.local_ip}")
        self.log("📱 Ready for Android client connections")
        self.log("")
        self.log("🎯 Multi-Backend Features:")
        self.log(" • PyAudioWPatch - TRUE WASAPI loopback support")
        self.log(" • SoundDevice - Alternative WASAPI compatibility")
        self.log(" • SoundCard - Fallback loopback support")
        self.log(" • Automatic backend detection and scoring")
        self.log(" • Real-time backend switching")
        self.log(" • Enhanced audio content detection")
        self.log(" • Comprehensive fallback mechanisms")
        self.log(" • Multi-level buffer management")
        self.log("")
        self.log("💡 IMPORTANT: Click 'TEST AUDIO' to verify your setup!")
        self.log("💡 Use 'SWITCH BACKEND' if audio issues occur!")
        self.log("")
        self.log("🎵 Ready for multi-backend audio streaming! 🎵")
        self.showMaximized()
        self.enable_glass()
        
    def update_client_display(self):
        def _render():
            try:
                if self.client_info:
                    try:
                        if isinstance(self.client_connected_time, datetime):
                            uptime_td = datetime.now() - self.client_connected_time
                            connected_label = self.client_connected_time.strftime("%H:%M:%S")
                        else:
                            start_dt = datetime.fromtimestamp(float(self.client_connected_time))
                            uptime_td = datetime.now() - start_dt
                            connected_label = start_dt.strftime("%H:%M:%S")
                        uptime_str = str(uptime_td).split(".")[0]
                    except Exception:
                        connected_label = "Unknown"; uptime_str = "Unknown"

                    client_ip_str = getattr(self, "client_address", ("Unknown", 0))[0]
                    conn_network_type = self.classify_client_network_type(client_ip_str, getattr(self, "last_client_telemetry_conn", None))
                    server_ip_for_client = self.get_primary_server_ip(peer_ip=client_ip_str)
                    server_net_type, server_adapter, server_ssid = self.get_server_network_info(server_ip_for_client)
                    hotspot_enabled, hotspot_adapter, hotspot_ip = self.get_windows_hotspot_info()
                    windows_hotspot_text = (f"✅ ON | Adapter: {hotspot_adapter} | IP: {hotspot_ip}" if hotspot_enabled else "❌ OFF")
                    buffer_level = self.audio_buffer_queue.qsize()
                    buffer_status = f"{buffer_level}/{self.buffer_queue_size}"
                    min_level = self.min_buffer_level
                    quality_status = "🟢 EXCELLENT" if buffer_level >= max(min_level,3) else ("🟡 GOOD" if buffer_level >= 2 else "🔴 LOW")
                    total_frames = max(int(self.packets_sent) + 1, 1)
                    audio_percentage = (int(self.real_audio_frames) / total_frames) * 100.0
                    audio_quality = "🎵 EXCELLENT AUDIO" if audio_percentage > 80 else ("🎵 GOOD AUDIO" if audio_percentage > 50 else ("🔇 SOME AUDIO" if audio_percentage > 20 else "❌ MOSTLY SILENCE"))
                    backend_perf = "Excellent" if self.real_audio_frames > int(self.silence_injections) else "Poor"
                    client_info_txt = f"""📱 ACTIVE CLIENT CONNECTION

{self.client_info}
Connected: {connected_label}
Uptime: {uptime_str}

📊 STREAMING STATISTICS
Packets Sent: {self.packets_sent:,}
Bytes Sent: {self.bytes_sent:,}
Real Audio Frames: {self.real_audio_frames:,}
Buffer Underruns: {self.buffer_underruns:,}
Low Capture Queue Drops: {self.low_capture_queue_drops:,}
Low Silence Frames: {self.low_silence_injections:,}
Silence Injections (all profiles): {self.silence_injections:,}
Backend Switches: {self.backend_switches}

🎵 BACKEND STATUS
Active Backend: {self.current_backend or 'None'}
Backend Performance: {backend_perf}
Total Audio Processed: {self.total_audio_processed:,} samples

🎚️ QUALITY SNAPSHOT
Stream Quality: {quality_status}
Audio Content: {audio_quality}
Buffer Queue: {buffer_status}
Min Buffer Level: {min_level} buffers

🔧 SERVER CONFIGURATION
Sample Rate: {self.samplerate} Hz
Capture Block: {self.capture_blocksize} samples
Stream Block: {self.stream_blocksize} samples
Buffer Queue Size: {self.buffer_queue_size}

🌐 NETWORK INFO
Client Connection:
{conn_network_type}

Client IP:
{client_ip_str}

Server Connection Path:
{server_net_type}

Server Adapter:
{server_adapter}

Server Wi-Fi / SSID:
{server_ssid}

Windows Hotspot / ICS:
{windows_hotspot_text}

Server IP:
{server_ip_for_client}

Audio Port:
{self.audio_port}

Discovery Ports:
{self.discovery_port}, {self.legacy_discovery_port}
"""
                    self.last_client_stats_text = client_info_txt.replace("📱 ACTIVE CLIENT CONNECTION", "⚠️ DISCONNECTED - LAST KNOWN STATS (FROZEN)")
                    final_text = client_info_txt
                    self.force_disconnect_btn.setEnabled(True)
                else:
                    no_client_display = f"""❌ NO CLIENT CONNECTED

🔧 SERVER STATUS
State: {'Running' if self.server_running else 'Stopped'}
Backend: {self.current_backend or 'Not Selected'}

📊 BACKEND AVAILABILITY
PyAudioWPatch: {'✅' if PYAUDIOWPATCH_AVAILABLE else '❌'}
SoundDevice: {'✅' if SOUNDDEVICE_AVAILABLE else '❌'}
SoundCard: {'✅' if SOUNDCARD_AVAILABLE else '❌'}

🌐 NETWORK INFO
Server IP: {self.get_primary_server_ip()}
Audio Port: {self.audio_port}
Discovery Ports: {self.discovery_port}, {self.legacy_discovery_port}

📋 INSTRUCTIONS
1. Start this server
2. Open WiFi Audio on Android
3. Use Auto-discovery or enter:
   IP: {self.get_primary_server_ip()}
   Port: {self.audio_port}

🎯 FEATURES
✅ Real audio content detection
✅ Multiple audio source support
✅ VB-Cable & Stereo Mix integration
✅ WASAPI loopback detection
✅ Real-time audio monitoring
✅ Hotspot discovery
✅ Buffer underrun prevention
✅ Jitter resistance

🎵 TIPS
• Click TEST AUDIO to verify capture
• Try SWITCH BACKEND if capture is silent
• Play any media on this PC for loopback
"""
                    final_text = (self.last_client_stats_text + "\n\n" + "="*48 + "\n\n" + no_client_display) if self.last_client_stats_text else no_client_display
                    self.force_disconnect_btn.setEnabled(False)

                if self.client_text.toPlainText().strip() != final_text.strip():
                    self.client_text.setPlainText(final_text)
            except Exception:
                pass
        self.after(0, _render)

    def closeEvent(self, event):
        try:
            self._network_monitor_stop.set()
            if self.server_running:
                self.stop_server()
        except Exception:
            pass
        event.accept()

    def get_primary_server_ip(self, preferred_ip=None, peer_ip=None):
        """Return the IPv4 endpoint appropriate for the current connection.

        Route selection is IP/interface based. SSID is display-only metadata.
        The normal GUI/QR path never launches netsh/PowerShell.
        """
        if preferred_ip:
            try:
                socket.inet_aton(preferred_ip)
                return preferred_ip
            except OSError:
                pass

        # A connected peer tells us exactly which local NIC reaches Android.
        if peer_ip:
            try:
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                    s.connect((peer_ip, self.audio_port))
                    route_ip = s.getsockname()[0]
                    if route_ip and not route_ip.startswith("127."):
                        return route_ip
            except Exception:
                pass

        with self._network_state_lock:
            hotspot_on = bool(self.windows_hotspot_state)
            hotspot_ip = self.windows_hotspot_ip
            active_ip = self.active_server_ip

        if hotspot_on and hotspot_ip and hotspot_ip != "N/A":
            return hotspot_ip

        rndis_ip = self.get_rndis_server_ip()
        if rndis_ip:
            return rndis_ip

        if active_ip and active_ip != "127.0.0.1":
            return active_ip

        return "127.0.0.1"

    @staticmethod
    def _looks_like_usb_adapter(text):
        low = str(text or "").strip().lower()
        # Strong Windows driver/interface-description evidence only. A generic
        # "USB" token is deliberately NOT enough because the user's Realtek
        # RTL8188EU Wi-Fi receiver is itself a USB device.
        strong = (
            "remote ndis", "remote ndis based internet sharing device",
            "rndis", "internet sharing device", "usb ethernet",
            "usb network adapter", "android usb tether", "usb tether",
            "cdc-ecm", "cdc ecm", "cdc-ncm", "cdc ncm", "ecm", "ncm"
        )
        if any(x in low for x in strong):
            return True
        if any(x in low for x in ("wireless", "wi-fi", "wifi", "802.11", "wlan")):
            return False
        return False

    def _refresh_windows_adapter_details(self):
        """Deep Windows adapter scan, executed only by the background worker.

        Android RNDIS adapters are often named simply Ethernet 3/5/6. The
        InterfaceDescription exposes their real identity (for example,
        Remote NDIS based Internet Sharing Device).
        """
        if platform.system() != "Windows":
            return getattr(self, "_windows_adapter_details", {})
        try:
            cmd = [
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Get-NetAdapter -IncludeHidden | Select-Object Name,InterfaceDescription,ifIndex,Status | ConvertTo-Json -Compress"
            ]
            out = subprocess.check_output(cmd, text=True, stderr=subprocess.DEVNULL, timeout=1.5).strip()
            if not out:
                return getattr(self, "_windows_adapter_details", {})
            rows = json.loads(out)
            if isinstance(rows, dict):
                rows = [rows]
            details = {}
            for row in rows:
                if isinstance(row, dict):
                    name = str(row.get("Name") or "").strip()
                    if name:
                        details[name] = {
                            "description": str(row.get("InterfaceDescription") or "").strip(),
                            "status": str(row.get("Status") or "").strip()
                        }
            self._windows_adapter_details = details
            return details
        except Exception:
            return getattr(self, "_windows_adapter_details", {})

    def get_rndis_server_ip(self):
        """Return the active PC IPv4 address belonging to an RNDIS/USB NIC."""
        try:
            interfaces = psutil.net_if_addrs()
            stats = psutil.net_if_stats()
            details = getattr(self, "_windows_adapter_details", {}) or {}
            for iface_name, addrs in interfaces.items():
                low = iface_name.lower()
                description = str(details.get(iface_name, {}).get("description", ""))
                identity = f"{iface_name} {description}"
                if not (self._looks_like_usb_adapter(identity) or
                        any(x in low for x in ("rndis", "usb0", "usbnet", "ecm", "ncm"))):
                    continue
                state = stats.get(iface_name)
                if state is not None and not state.isup:
                    continue
                for addr in addrs:
                    if addr.family == socket.AF_INET and not addr.address.startswith(("127.", "169.254.")):
                        return addr.address
        except Exception:
            pass
        return None

    def generate_qr_image(self, box_size=8, border=2):
        """Generate the legacy server QR: H correction, rounded modules, vertical gradient."""
        peer_ip = None
        try:
            if getattr(self, "client_address", None):
                peer_ip = self.client_address[0]
        except Exception:
            peer_ip = None
        primary_ip = self.get_primary_server_ip(peer_ip=peer_ip)
        qr_data = f"WIFI_AUDIO_SERVER:{primary_ip}:{self.audio_port}:{self.server_name}:{self.current_backend}"
        qr = qrcode.QRCode(
            version=1,
            error_correction=qrcode.constants.ERROR_CORRECT_H,
            box_size=box_size,
            border=border,
        )
        qr.add_data(qr_data)
        qr.make(fit=True)
        try:
            if StyledPilImage and RoundedModuleDrawer and VerticalGradiantColorMask:
                img = qr.make_image(
                    image_factory=StyledPilImage,
                    module_drawer=RoundedModuleDrawer(),
                    color_mask=VerticalGradiantColorMask(
                        back_color=(30, 30, 46),
                        top_color=(0, 255, 200),
                        bottom_color=(180, 110, 255),
                    ),
                )
            else:
                img = qr.make_image(fill_color="#00c9a7", back_color="#1e1e2e")
        except Exception as exc:
            self.log(f"⚠️ Styled QR generation fallback: {exc}")
            img = qr.make_image(fill_color="#00c9a7", back_color="#1e1e2e")
        return img, primary_ip

    def get_local_ip(self):
        """Return a diagnostic list of usable IPv4 addresses. Startup only."""
        ips = []
        try:
            for iface_name, addrs in psutil.net_if_addrs().items():
                name_lower = iface_name.lower()
                if any(x in name_lower for x in ("vmware", "loopback", "bluetooth")):
                    continue
                for addr in addrs:
                    if (addr.family == socket.AF_INET
                        and not addr.address.startswith("127.")
                        and not addr.address.startswith("169.254.")
                        and addr.address not in ips):
                        ips.append(addr.address)
        except Exception:
            pass
        return " / ".join(ips) if ips else "127.0.0.1"

    def _select_physical_wifi_ip(self, interfaces):
        """Pick a physical Wi-Fi/LAN IPv4 without querying SSID/netsh."""
        preferred = []
        fallback = []
        for iface_name, addrs in interfaces.items():
            low = iface_name.lower()
            if any(x in low for x in ("vmware", "virtual", "loopback", "bluetooth")):
                continue
            ipv4s = [a.address for a in addrs if a.family == socket.AF_INET
                     and not a.address.startswith("127.")
                     and not a.address.startswith("169.254.")]
            if not ipv4s:
                continue
            is_wifi = ("wi-fi" in low or "wifi" in low or "wireless" in low
                       or low == "wlan" or low.startswith("wlan"))
            target = preferred if is_wifi else fallback
            for ip in ipv4s:
                if not ip.startswith("192.168.137."):
                    target.append((iface_name, ip))
        if preferred:
            return preferred[0]
        if fallback:
            return fallback[0]
        return ("N/A", "127.0.0.1")

    def refresh_network_state(self, log_change=True):
        """Fast IP-only network detection; never launches subprocesses."""
        try:
            interfaces = psutil.net_if_addrs()
            stats = psutil.net_if_stats()

            hotspot_adapter = None
            for iface_name, addrs in interfaces.items():
                ipv4s = [a.address for a in addrs if a.family == socket.AF_INET]
                if "192.168.137.1" not in ipv4s:
                    continue
                iface_stats = stats.get(iface_name)
                if iface_stats is not None and not iface_stats.isup:
                    continue
                hotspot_adapter = iface_name
                break

            hotspot_on = hotspot_adapter is not None
            if hotspot_on:
                active_ip = "192.168.137.1"
                active_mode = "Windows Mobile Hotspot / ICS"
                active_adapter = hotspot_adapter
            else:
                active_adapter, active_ip = self._select_physical_wifi_ip(interfaces)
                # If an Android USB/RNDIS NIC is present and clearly identified,
                # expose that endpoint in the server card. Actual client routing
                # still uses peer-IP selection/discovery, so this does not break
                # simultaneous Wi-Fi connectivity.
                rndis_ip = self.get_rndis_server_ip()
                if rndis_ip:
                    for iface_name, addrs in interfaces.items():
                        if rndis_ip in [a.address for a in addrs if a.family == socket.AF_INET]:
                            active_adapter = iface_name
                            active_ip = rndis_ip
                            active_mode = "USB Tethering / RNDIS"
                            break
                elif active_adapter != "N/A" and active_ip != "127.0.0.1":
                    active_mode = "Physical Wi-Fi / LAN"
                else:
                    active_mode = "LAN / Ethernet" if active_ip != "127.0.0.1" else "No usable network"

            with self._network_state_lock:
                old_hotspot = self.windows_hotspot_state
                old_ip = self.active_server_ip
                old_adapter = self.windows_hotspot_adapter

                # During a tether/Wi-Fi transition Windows can report a brief
                # all-adapters-down interval. Do not replace a valid endpoint
                # with 127.0.0.1 during that transient window; keep the last
                # real endpoint until a new usable interface appears.
                if active_ip == "127.0.0.1" and old_ip != "127.0.0.1":
                    active_ip = old_ip
                    active_mode = self.active_network_mode
                    active_adapter = self.active_network_adapter

                self.windows_hotspot_state = hotspot_on
                self.windows_hotspot_adapter = hotspot_adapter or "N/A"
                self.windows_hotspot_ip = "192.168.137.1" if hotspot_on else "N/A"
                self.active_server_ip = active_ip
                self.active_network_mode = active_mode
                self.active_network_adapter = active_adapter
                self.active_network_ssid = (
                    "N/A (PC Hotspot)" if hotspot_on
                    else ("N/A (Wired)" if active_mode.startswith("USB Tethering")
                          else getattr(self, "wifi_ssid", "N/A") or "N/A")
                )

            changed = (old_hotspot != hotspot_on or old_ip != active_ip
                       or old_adapter != (hotspot_adapter or "N/A"))
            if log_change and changed:
                if hotspot_on:
                    self.log(f"📡 Windows hotspot turned ON | IP=192.168.137.1 | Adapter={hotspot_adapter}")
                elif old_hotspot:
                    self.log(f"📡 Windows hotspot turned OFF | Active server IP={active_ip} | Adapter={active_adapter}")
                else:
                    self.log(f"🌐 Active server endpoint changed | IP={active_ip} | Mode={active_mode} | Adapter={active_adapter}")
            return changed
        except Exception as e:
            if log_change:
                self.log(f"⚠️ Fast network detection error: {e}")
            return False

    def get_logged_in_user(self):
        try:
            return getpass.getuser()
        except Exception:
            return os.environ.get("USERNAME") or os.environ.get("USER") or "Unknown"

    def get_friendly_cpu_name(self):
        if platform.system() != "Windows":
            return platform.processor() or platform.machine() or "Unknown CPU"

        commands = [
            r'powershell -NoProfile -Command "(Get-CimInstance Win32_Processor | Select-Object -ExpandProperty Name)"',
            r'wmic cpu get name'
        ]
        for cmd in commands:
            try:
                out = subprocess.check_output(cmd, shell=True, text=True, stderr=subprocess.DEVNULL).strip()
                lines = [line.strip() for line in out.splitlines() if line.strip() and line.strip().lower() != "name"]
                if lines:
                    return lines[0]
            except Exception:
                pass

        return platform.processor() or platform.machine() or "Unknown CPU"

    def classify_client_network_type(self, client_ip, telemetry_type=None):
        """
        Determine how the Android client is connected.

        Telemetry from Android is preferred because it can inspect
        its actual interface. IP ranges are fallback heuristics only.
        """

        if telemetry_type:
            telemetry_type = str(telemetry_type).upper().strip()

            if telemetry_type == "USB_TETHERING":
                return "🔌 USB Tethering (Wired)"

            if telemetry_type == "HOTSPOT":
                return "📱 Mobile Hotspot / PC Hotspot"

            if telemetry_type == "HOME_WIFI":
                return "🏠  Home WiFi / LAN"

            if telemetry_type == "ETHERNET":
                return "🔗 Ethernet / LAN"

            if telemetry_type == "MOBILE_DATA":
                return "📶 Mobile Data"

        ip = str(client_ip or "").strip()

        # Common Android USB tethering networks.
        usb_ranges = (
            "192.168.42.",
            "192.168.44.",
            "192.168.45.",
            "192.168.49.",
        )

        # Common hotspot / ICS networks.
        hotspot_ranges = (
            "192.168.43.",
            "192.168.137.",
            "192.168.234.",
            "10.0.0.",
            "10.185.37.",
            "172.20.10.",
        )

        if ip.startswith(usb_ranges):
            return "🔌 USB Tethering (Wired)"

        if ip.startswith(hotspot_ranges):
            return "📱 Mobile Hotspot / PC Hotspot"

        return "🏠  Home WiFi / LAN" 

    def get_wifi_receiver_info(self, force_refresh=False):
        """Return Wi-Fi adapter/SSID. SSID is display metadata only.

        The potentially blocking Windows query is only called by the background
        identity worker; it is never used for route detection or audio pacing.
        """
        cached = getattr(self, "_wifi_receiver_info_cache", None)
        if cached and not force_refresh:
            return cached
        if not force_refresh:
            return (getattr(self, "wifi_adapter", "WiFi"), getattr(self, "wifi_ssid", "N/A"))
        adapter_name = "WiFi"
        ssid = "N/A"
        try:
            for iface_name in psutil.net_if_addrs().keys():
                low = iface_name.lower()
                if "wi-fi" in low or "wifi" in low or "wireless" in low or low.startswith("wlan"):
                    adapter_name = iface_name
                    break
            if platform.system() == "Windows":
                out = subprocess.check_output(
                    "netsh wlan show interfaces", shell=True, text=True,
                    stderr=subprocess.DEVNULL, timeout=1.5
                )
                for raw in out.splitlines():
                    line = raw.strip()
                    low = line.lower()
                    if low.startswith("name") and ":" in line:
                        adapter_name = line.split(":", 1)[1].strip() or adapter_name
                    elif low.startswith("ssid") and "bssid" not in low and ":" in line:
                        ssid = line.split(":", 1)[1].strip() or ssid
                        break
        except Exception:
            pass
        result = (adapter_name, ssid)
        self._wifi_receiver_info_cache = result
        self.wifi_adapter, self.wifi_ssid = result
        return result

    def _network_identity_worker(self):
        """Background-only SSID/RNDIS identity refresh."""
        last_snapshot = None
        last_ssid_refresh = 0.0
        last_logged_identity = None
        while not self._network_monitor_stop.is_set():
            try:
                interfaces = psutil.net_if_addrs()
                snapshot = tuple(sorted(
                    (name, tuple(sorted(a.address for a in addrs if a.family == socket.AF_INET)))
                    for name, addrs in interfaces.items()
                ))
                snapshot_changed = snapshot != last_snapshot
                if snapshot_changed:
                    last_snapshot = snapshot
                    self._refresh_windows_adapter_details()
                    self.refresh_network_state(log_change=True)

                # SSID is display-only. Refresh it on adapter/IP changes and
                # periodically at a low rate so a same-IP Wi-Fi roam is visible.
                now = time.monotonic()
                if snapshot_changed or (now - last_ssid_refresh) >= 5.0:
                    self.get_wifi_receiver_info(force_refresh=True)
                    last_ssid_refresh = now
                    with self._network_state_lock:
                        if "Wi-Fi" in self.active_network_mode or "Mobile Hotspot" in self.active_network_mode:
                            self.active_network_ssid = self.wifi_ssid
                        identity = (self.active_network_mode, self.active_network_adapter, self.active_network_ssid, self.active_server_ip)
                    if identity != last_logged_identity:
                        last_logged_identity = identity
                        self.log(
                            f"📡📶 Wi-Fi Receiver: {self.wifi_adapter} | SSID: {self.wifi_ssid}"
                        )
                        self.log(
                            f"📡 Network identity: Mode={identity[0]} | Adapter={identity[1]} | "
                            f"SSID={identity[2]} | IP={identity[3]}"
                        )
            except Exception:
                pass
            self._network_monitor_stop.wait(0.5)

    def start_network_identity_worker(self):
        if self._network_monitor_thread and self._network_monitor_thread.is_alive():
            return
        self._network_monitor_stop.clear()
        self._network_monitor_thread = threading.Thread(
            target=self._network_identity_worker, name="NetworkIdentity", daemon=True
        )
        self._network_monitor_thread.start()

    def get_server_network_info(self, preferred_ip=None):
        """Classify the exact Windows NIC carrying preferred_ip.

        SSID is attached only for Wi-Fi. USB/RNDIS always reports N/A (Wired).
        Ethernet-named RNDIS devices are identified from cached adapter
        InterfaceDescription data, not from SSID.
        """
        network_type = "🔗 Ethernet / LAN"
        adapter_name = "Unknown"
        ssid = "N/A"
        try:
            interfaces = psutil.net_if_addrs()
            stats = psutil.net_if_stats()
            details = getattr(self, "_windows_adapter_details", {}) or {}
            preferred_iface = None
            for iface_name, addrs in interfaces.items():
                if preferred_ip and preferred_ip in [a.address for a in addrs if a.family == socket.AF_INET]:
                    preferred_iface = iface_name
                    break
            candidates = [preferred_iface] if preferred_iface else list(interfaces.keys())
            for iface_name in candidates:
                if not iface_name:
                    continue
                low = iface_name.lower()
                if any(x in low for x in ("vmware", "virtual", "loopback", "bluetooth")):
                    continue
                adapter_name = iface_name
                description = str(details.get(iface_name, {}).get("description", ""))
                identity = f"{iface_name} {description}"
                if self._looks_like_usb_adapter(identity):
                    return "🔌 USB Tethering / RNDIS", adapter_name, "N/A (Wired)"
                if preferred_ip and preferred_ip.startswith("192.168.137."):
                    return "📱 Windows Hotspot / ICS", adapter_name, "N/A (PC Hotspot)"
                mobile_ranges = ("192.168.42.", "192.168.234.", "192.168.43.",
                                 "192.168.44.", "192.168.45.", "192.168.49.",
                                 "172.20.10.", "10.212.76.", "10.185.37.", "10.0.0.")
                if preferred_ip and any(preferred_ip.startswith(x) for x in mobile_ranges):
                    _, ssid = self.get_wifi_receiver_info(force_refresh=False)
                    return "📱 Android | Mobile Hotspot", adapter_name, ssid
                if any(x in low for x in ("wi-fi", "wifi", "wireless")):
                    _, ssid = self.get_wifi_receiver_info(force_refresh=False)
                    return "📶 Wi-Fi", adapter_name, ssid
                if stats.get(iface_name) is not None:
                    network_type = "🔗 Ethernet / LAN"
                    ssid = "N/A (Wired)"
            return network_type, adapter_name, ssid
        except Exception as e:
            self.log(f"⚠️ Server network scan failed: {e}")
            return network_type, adapter_name, ssid

    def log_system_info(self):
        self.log("========================================")
        self.log(f"💻 PC Name: {self.server_name}")
        self.log(f"💻 OS: {platform.system()} {platform.release()} ({platform.version()})")
        self.log(f"👤 Current  Logged-in User: {self.get_logged_in_user()}")
        
        cpu_name = self.get_friendly_cpu_name()
        physical_cores = psutil.cpu_count(logical=False) or psutil.cpu_count() or 0
        logical_cores = psutil.cpu_count(logical=True) or psutil.cpu_count() or 0
        self.log(f"🧠  CPU:  {cpu_name} (Physical: {physical_cores}, Logical: {logical_cores})") 
        
        try:
            mem = psutil.virtual_memory()
            total_ram_gb = round(mem.total / (1024**3), 2)
            self.log(f"💾 RAM: {total_ram_gb} GB Total / {mem.available / (1024**2):.0f} MB Available")
            self.log(f"🔗 Network Interfaces: {list(psutil.net_if_addrs().keys())}")

            usb_candidates = []
            details = getattr(self, "_windows_adapter_details", {}) or {}
            rndis_ip = self.get_rndis_server_ip()

            for iface_name, addrs in psutil.net_if_addrs().items():
                low = iface_name.lower()
                description = str(details.get(iface_name, {}).get("description", ""))
                identity = f"{iface_name} {description}"
                iface_ips = [a.address for a in addrs if a.family == socket.AF_INET]
                if self._looks_like_usb_adapter(identity) or (rndis_ip and rndis_ip in iface_ips):
                    usb_candidates.append(iface_name)

            if usb_candidates:
                self.log(
                    f"🔌 USB Tethering Adapter(s) detected: {usb_candidates}"
                )
            else:
                self.log(
                    "🔌 USB Tethering Adapter: not currently detected"
                )
            
            self.wifi_adapter, self.wifi_ssid = self.get_wifi_receiver_info(force_refresh=False)
            self.log(f" 📡📶 Wi-Fi Receiver: {self.wifi_adapter} | SSID: {self.wifi_ssid} (background refresh active)")

            hotspot_enabled, hotspot_adapter, hotspot_ip = self.get_windows_hotspot_info()
            if hotspot_enabled:
                self.log(
                    f"📡 Windows Mobile Hotspot / ICS: ON | "
                    f"Adapter: {hotspot_adapter} | IP: {hotspot_ip}"
                )
            else:
                self.log("📡 Windows Mobile Hotspot / ICS: OFF")

            # Deliberately do NOT scan Wi-Fi Direct/Miracast here. The audio
            # server uses the IP endpoint only, and Windows Wi-Fi Direct can
            # share the same 192.168.137.1 address as Mobile Hotspot. Since the
            # Android streaming client is not expected to use screen casting
            # simultaneously, the IP state is the cleanest signal.
            self.log("ℹ️ Network detection mode: IP-only (no Wi-Fi Direct/SSID polling)")
        except Exception as e:
            self.log(f"⚠️ Could not read full system stats: {e}")
        self.log("========================================")

    def detect_best_backend(self):
        """Detect and score all available audio backends"""
        self.log("🔍 Backend detection starting...")
        backend_scores = {}

        # Test PyAudioWPatch
        if PYAUDIOWPATCH_AVAILABLE:
            score = self._test_pyaudiowpatch_backend()
            backend_scores["pyaudiowpatch"] = score
            self.log(f"🎯 PyAudioWPatch backend score: {score}/100")

        # Test SoundDevice
        if SOUNDDEVICE_AVAILABLE:
            score = self._test_sounddevice_backend()
            backend_scores["sounddevice"] = score
            self.log(f"🔊 SoundDevice backend score: {score}/100")

        # Test SoundCard
        if SOUNDCARD_AVAILABLE:
            score = self._test_soundcard_backend()
            backend_scores["soundcard"] = score
            self.log(f"🎧 SoundCard backend score: {score}/100")

        # Select best backend
        if backend_scores:
            best_backend = max(backend_scores, key=backend_scores.get)
            best_score = backend_scores[best_backend]
            
            if best_score > 50:  # Minimum acceptable score
                self.current_backend = best_backend
                self.log(f"✅ Backend selected: {best_backend} (score: {best_score})")
                self.backend_label.setText(f"Active Backend: {best_backend.upper()} ({best_score}%)")
                self.backend_label.setStyleSheet("color: green; background: transparent;")
            else:
                self.log("⚠️ No backend achieved acceptable score - will try all during streaming")
                self.backend_label.setText("Active Backend: FALLBACK MODE")
                self.backend_label.setStyleSheet("color: orange; background: transparent;")
        else:
            self.log("❌ No audio backends available!")
            self.backend_label.setText("Active Backend: NONE AVAILABLE")
            self.backend_label.setStyleSheet("color: red; background: transparent;")

    def _test_pyaudiowpatch_backend(self):
        """Test PyAudioWPatch backend and return score"""
        if not PYAUDIOWPATCH_AVAILABLE:
            return 0
        
        try:
            p = pyaudio.PyAudio()
            score = 0
            
            # Check for loopback devices
            try:
                default_loopback = p.get_default_wasapi_loopback()
                if default_loopback:
                    score += 50
                    channels = default_loopback.get('maxInputChannels', 2)
                    self.server_capture_format = f"WASAPI_{channels}CH_48KHZ"
                    self.log(f"✅ PyAudioWPatch default loopback: {default_loopback.get('name', 'Unknown')}")
                    self.log(f"Server Captured FORMAT > {self.server_capture_format}")
            except:
                pass
            
            # Check loopback device generator
            try:
                loopback_count = 0
                for loopback_info in p.get_loopback_device_info_generator():
                    loopback_count += 1
                    if loopback_count == 1:  # First device found
                        score += 30
                        channels = loopback_info.get('maxInputChannels', 2)
                        self.server_capture_format = f"WASAPI_{channels}CH_48KHZ"
                        self.log(f"✅ PyAudioWPatch found loopback: {loopback_info.get('name', 'Unknown')}")
                        self.log(f"Server Captured FORMAT > {self.server_capture_format}")
                    if loopback_count >= 2:  # Multiple devices
                        score += 20
                        break
            except:
                pass
                
            p.terminate()
            return min(score, 100)
            
        except Exception as e:
            self.log(f"⚠️ PyAudioWPatch test failed: {e}")
            return 0

    def _test_sounddevice_backend(self):
        """Test SoundDevice backend and return score"""
        if not SOUNDDEVICE_AVAILABLE:
            return 0
        
        try:
            devices = sd.query_devices()
            score = 0
            
            for i, device in enumerate(devices):
                if device['max_input_channels'] > 0:
                    device_name = device['name'].lower()
                    # Check for WASAPI loopback patterns
                    if 'loopback' in device_name:
                        score += 40
                        self.log(f"✅ SoundDevice loopback found: {device['name']}")
                    elif 'speakers' in device_name and 'wasapi' in str(device).lower():
                        score += 25
                    elif 'what u hear' in device_name:
                        score += 35
                    elif 'stereo mix' in device_name:
                        score += 20
                    
                    if score >= 40:  # Found good loopback
                        break
            
            # Bonus for high sample rate support
            try:
                default_device = sd.default.device[0]
                if default_device is not None:
                    device_info = devices[default_device]
                    if device_info.get('default_samplerate', 0) >= 48000:
                        score += 10
            except:
                pass
                
            return min(score, 100)
            
        except Exception as e:
            self.log(f"⚠️ SoundDevice test failed: {e}")
            return 0

    def _test_soundcard_backend(self):
        """Test SoundCard backend and return score"""
        if not SOUNDCARD_AVAILABLE:
            return 0
        
        try:
            score = 0
            
            # Check for loopback microphones
            try:
                microphones = sc.all_microphones(include_loopback=True)
                for mic in microphones:
                    mic_name = mic.name.lower()
                    if 'loopback' in mic_name:
                        score += 35
                        self.log(f"✅ SoundCard loopback found: {mic.name}")
                    elif 'speakers' in mic_name:
                        score += 25
                    elif 'what u hear' in mic_name:
                        score += 30
                    elif 'stereo mix' in mic_name:
                        score += 20
                    elif 'vb-audio' in mic_name or 'cable' in mic_name:
                        score += 30
                    
                    if score >= 35:  # Found good device
                        break
            except:
                pass
                
            return min(score, 100)
            
        except Exception as e:
            self.log(f"⚠️ SoundCard test failed: {e}")
            return 0

    def switch_to_next_backend(self):
        """Switch to the next available backend"""
        if not self.current_backend:
            self.detect_best_backend()
            return

        available_backends = []
        if PYAUDIOWPATCH_AVAILABLE:
            available_backends.append("pyaudiowpatch")
        if SOUNDDEVICE_AVAILABLE:
            available_backends.append("sounddevice")
        if SOUNDCARD_AVAILABLE:
            available_backends.append("soundcard")

        if not available_backends:
            self.log("❌ No backends available to switch to")
            return

        try:
            current_index = available_backends.index(self.current_backend)
            next_index = (current_index + 1) % len(available_backends)
            self.current_backend = available_backends[next_index]
        except ValueError:
            self.current_backend = available_backends[0]

        self.backend_switches += 1
        self.log(f"🔄 Switched to backend: {self.current_backend} (switch #{self.backend_switches})")
        self.backend_label.setText(f"Active Backend: {self.current_backend.upper()} (Switched)")
        self.backend_label.setStyleSheet("color: purple; background: transparent;")

    def _test_thread(self):
        """Thread for testing all audio backends"""
        try:
            if self.current_backend == "pyaudiowpatch":
                self._test_pyaudiowpatch_audio()
            elif self.current_backend == "sounddevice":
                self._test_sounddevice_audio()
            elif self.current_backend == "soundcard":
                self._test_soundcard_audio()
            else:
                self._test_all_backends()
        except Exception as e:
            self.log(f"❌ Audio test error: {e}")

    def _test_pyaudiowpatch_audio(self):
        """Test PyAudioWPatch audio capture"""
        if not PYAUDIOWPATCH_AVAILABLE:
            return

        try:
            p = pyaudio.PyAudio()

            # Try to get default loopback device
            device_index = None
            device_name = "Unknown"
            try:
                default_loopback = p.get_default_wasapi_loopback()
                if default_loopback:
                    device_index = default_loopback['index']
                    device_name = default_loopback['name']
            except:
                pass

            if device_index is None:
                # Try loopback generator
                try:
                    for loopback_info in p.get_loopback_device_info_generator():
                        device_index = loopback_info['index']
                        device_name = loopback_info['name']
                        break
                except:
                    pass

            if device_index is None:
                self.log("❌ PyAudioWPatch: No loopback device found")
                p.terminate()
                return

            self.log(f"🎵 Testing PyAudioWPatch: {device_name}")

            # Test audio capture
            stream = p.open(
                format=pyaudio.paInt16,
                channels=2,
                rate=self.samplerate,
                input=True,
                input_device_index=device_index,
                frames_per_buffer=self.capture_blocksize
            )

            self._run_audio_test(stream, "PyAudioWPatch", lambda: stream.read(self.capture_blocksize))

            stream.stop_stream()
            stream.close()
            p.terminate()

        except Exception as e:
            self.log(f"❌ PyAudioWPatch test failed: {e}")

    def _test_sounddevice_audio(self):
        """Test SoundDevice audio capture"""
        if not SOUNDDEVICE_AVAILABLE:
            return

        def test_callback(indata, frames, time, status):
            try:
                # Convert stereo to mono if needed
                if indata.ndim == 2 and indata.shape[1] >= 2:
                    mono = (indata[:, 0] + indata[:, 1]) / 2.0
                else:
                    mono = indata.flatten()

                # Calculate RMS level
                rms = np.sqrt(np.mean(mono ** 2))
                has_real_audio = rms > self.silence_threshold

                # Update UI
                self.after(0, lambda: self.update_content_indicator(has_real_audio, rms))

            except Exception as e:
                self.log_rate_limited("sounddevice_callback_error", f"⚠️ SoundDevice callback error: {e}", 5.0)

        try:
            # Find best device
            device_index = self._find_sounddevice_loopback()
            if device_index is None:
                self.log("❌ SoundDevice: No suitable device found")
                return

            devices = sd.query_devices()
            device_name = devices[device_index]['name']
            self.log(f"🎵 Testing SoundDevice: {device_name}")

            with sd.InputStream(callback=test_callback,
                              device=device_index,
                              channels=2,
                              samplerate=self.samplerate,
                              blocksize=self.capture_blocksize,
                              dtype=np.float32):
                time.sleep(10)  # Test for 10 seconds

            self.log("✅ SoundDevice test completed")

        except Exception as e:
            self.log(f"❌ SoundDevice test failed: {e}")

    def _test_soundcard_audio(self):
        """Test SoundCard audio capture"""
        if not SOUNDCARD_AVAILABLE:
            return

        try:
            # Find a suitable microphone (loopback preferred)
            microphones = sc.all_microphones(include_loopback=True)
            if not microphones:
                self.log("❌ SoundCard: No microphones found")
                return

            # Prefer loopback devices
            selected_mic = None
            for mic in microphones:
                if 'loopback' in mic.name.lower() or 'speakers' in mic.name.lower():
                    selected_mic = mic
                    break

            if not selected_mic:
                selected_mic = microphones[0]  # Use first available

            self.log(f"🎵 Testing SoundCard: {selected_mic.name}")

            # Test capture
            with selected_mic.recorder(samplerate=self.samplerate, channels=2) as recorder:
                for _ in range(50):  # Test for ~5 seconds
                    data = recorder.record(numframes=self.capture_blocksize)
                    
                    # Convert to mono and calculate RMS
                    if data.ndim == 2:
                        mono = np.mean(data, axis=1)
                    else:
                        mono = data
                    
                    rms = np.sqrt(np.mean(mono ** 2))
                    has_real_audio = rms > self.silence_threshold
                    
                    # Update UI
                    self.after(0, lambda: self.update_content_indicator(has_real_audio, rms))
                    
                    time.sleep(0.1)

            self.log("✅ SoundCard test completed")

        except Exception as e:
            self.log(f"❌ SoundCard test failed: {e}")

    def _find_sounddevice_loopback(self):
        """Find a suitable loopback device for SoundDevice"""
        try:
            devices = sd.query_devices()
            for i, device in enumerate(devices):
                if device['max_input_channels'] > 0:
                    device_name = device['name'].lower()
                    if any(keyword in device_name for keyword in ['loopback', 'what u hear', 'stereo mix']):
                        return i
            return None
        except:
            return None

    def _run_audio_test(self, stream, backend_name, read_func):
        """Run audio test with the given stream and read function"""
        self.log(f"🎵 Running {backend_name} audio test for 10 seconds...")
        start_time = time.time()
        
        while time.time() - start_time < 10:
            try:
                data = read_func()
                # Process audio data similar to streaming
                if isinstance(data, bytes):
                    audio_data = np.frombuffer(data, dtype=np.int16)
                else:
                    audio_data = data
                
                # Convert to mono if stereo
                if audio_data.ndim == 2:
                    audio_data = np.mean(audio_data, axis=1)
                elif len(audio_data) > self.capture_blocksize:
                    # Assume interleaved stereo
                    audio_data = audio_data[::2]  # Take every other sample
                
                # Calculate RMS level
                rms = np.sqrt(np.mean(audio_data.astype(float) ** 2)) / 32768.0
                has_real_audio = rms > self.silence_threshold
                
                # Update content indicator
                self.after(0, lambda: self.update_content_indicator(has_real_audio, rms))
                
                time.sleep(0.05)
                
            except Exception as e:
                self.log(f"⚠️ {backend_name} test error: {e}")
                break

    def start_server(self, auto_start=False):
        """Start the WiFi audio server"""
        if self.server_running:
            return

        try:
            # Improve Windows timer resolution for accurate 10 ms pacing
            import platform, ctypes
            if platform.system() == "Windows":
                try:
                    ctypes.windll.winmm.timeBeginPeriod(1)
                except Exception:
                    pass
            # 🎯 CHANGED TO UDP (No TCP optimizations needed!)
            self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            
            # 🎯 Windows UDP Bug Fix (WinError 10054) - Prevents UI Freeze!
            if platform.system() == "Windows":
                try:
                    # SIO_UDP_CONNRESET = -1744830452
                    self.server_socket.ioctl(socket.SIO_UDP_CONNRESET, False)
                except Exception:
                    pass
            
            self.server_socket.bind(('0.0.0.0', self.audio_port))
            self.server_socket.settimeout(1.0)
            
            # 🎯 Extreme optimization for 2.4GHz Wi-Fi congestion:
            # Set DSCP Network Control / Voice priority (0xC0/0xE0) to bypass router TCP queues!
            try:
                self.server_socket.setsockopt(socket.IPPROTO_IP, socket.IP_TOS, 0xE0)
            except Exception:
                pass
            # UDP does NOT use .listen() !
            
            # 🎯 KILLED PRE-FILL QUEUE: Prevents 20ms artificial server delay
            
            # Start discovery server
            self._start_discovery_server()
            
            # === TELEMETRY UDP SOCKET ===
            # Bind here, but do not start its worker until server_running is True.
            # Previously the worker was launched first, saw server_running=False
            # in its loop condition, and exited silently before receiving packets.
            telemetry_sock = None
            try:
                telemetry_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

                # On Windows this dedicated UDP port should belong to exactly one
                # server process; don't silently share it with an old instance.
                if platform.system() == "Windows" and hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                    telemetry_sock.setsockopt(
                        socket.SOL_SOCKET,
                        socket.SO_EXCLUSIVEADDRUSE,
                        1
                    )

                telemetry_sock.bind(("0.0.0.0", self.telemetry_port))
                telemetry_sock.settimeout(1.0)
                self.telemetry_socket = telemetry_sock
                self.log(
                    f"📡 Telemetry UDP socket bound on 0.0.0.0:{self.telemetry_port}; "
                    "receiver worker will start after server enters RUNNING state"
                )
            except Exception as e:
                if telemetry_sock is not None:
                    try:
                        telemetry_sock.close()
                    except Exception:
                        pass
                self.telemetry_socket = None
                self.log(f"⚠️ Telemetry startup failed on UDP {self.telemetry_port}: {e}")

            self.server_running = True
            self.pc_state_stop_event.clear()
            self.start_btn.setEnabled(False)
            self.stop_btn.setEnabled(True)

            # Crucial ordering fix: the receive loop must be started only after
            # the loop guard (self.server_running) is True.
            if self.telemetry_socket is not None:
                try:
                    self.telemetry_thread = threading.Thread(
                        target=self._telemetry_handler,
                        daemon=True,
                        name="WiFiAudioTelemetry"
                    )
                    self.telemetry_thread.start()
                    self.log(f"📡 Telemetry receiver active on UDP {self.telemetry_port}")
                except Exception as e:
                    self.log(f"⚠️ Could not start telemetry receiver thread: {e}")
                    try:
                        self.telemetry_socket.close()
                    except Exception:
                        pass
                    self.telemetry_socket = None
                    self.telemetry_thread = None

            if auto_start:
                self.log("🚀 Multi-Backend WiFi Audio Server auto-started!")
            else:
                self.log("🚀 Multi-Backend WiFi Audio Server started manually!")
            
            self.log(f"🎵 Audio server listening on {self.local_ip}:{self.audio_port}")
            self.log(f"🔍 Discovery server running on ports {self.discovery_port}, {self.legacy_discovery_port}")
            
            # 🎯 Start connection acceptor thread (Correct Architecture!)
            self.accept_thread = threading.Thread(target=self._client_handler, daemon=True)
            self.accept_thread.start()

            # 🎯 Start continuous audio sender thread (survives client reconnects)
            self.sender_thread = threading.Thread(
                target=self._sender_thread,
                daemon=True,
                name="WiFiAudioSender"
            )
            self.sender_thread.start()

            self.client_watchdog_thread = threading.Thread(
             target=self._client_watchdog,
             daemon=True,
             name="WiFiAudioClientWatchdog"
            )
            self.client_watchdog_thread.start()

            self.pc_state_thread = threading.Thread(
                target=self._pc_state_worker,
                daemon=True,
                name="WindowsPCStateWorker"
            )
            self.pc_state_thread.start()

            if PYCaw_AVAILABLE:
                self.log("🖥️ Windows mute-state provider: pycaw AVAILABLE")
            else:
                self.log(f"⚠️ Windows mute-state provider UNAVAILABLE: {PYCaw_IMPORT_ERROR or 'unknown import error'}")
                self.log("   Install with: py -m pip install pycaw")

            if WIN_MEDIA_AVAILABLE:
                self.log("🖥️ Windows media-state provider: WinRT AVAILABLE")
            else:
                self.log(f"⚠️ Windows media-state provider UNAVAILABLE: {WIN_MEDIA_IMPORT_ERROR or 'unknown import error'}")
                self.log("   Install with: py -m pip install --upgrade winrt-runtime winrt-Windows.Foundation winrt-Windows.Media.Control")
            self.log(f"🐍 Python runtime: {sys.executable} | {sys.version.split()[0]}")

            # Start audio capture based on current backend
            self._start_audio_capture()

        except Exception as e:
            self.log(f"❌ Failed to start server: {e}")
            self.server_running = False
            self.start_btn.setEnabled(True)
            self.stop_btn.setEnabled(False)

    def _send_windows_virtual_key(self, virtual_key: int) -> bool:
        try:
            user32 = ctypes.windll.user32
            key_up = 0x0002
            user32.keybd_event(virtual_key, 0, 0, 0)
            user32.keybd_event(virtual_key, 0, key_up, 0)
            return True
        except Exception as e:
            self.log(f"⚠️ Windows PC-control failed: {e}")
            return False

    def _is_active_client_ip(self, addr) -> bool:
        active = getattr(self, "client_address", None)
        return bool(active and addr and active[0] == addr[0])

    def _read_windows_mute_state(self):
        """Return the real Windows default-render-endpoint mute state."""
        if not PYCaw_AVAILABLE:
            return None

        ole32 = None
        com_initialized = False
        try:
            ole32 = ctypes.windll.ole32
            try:
                ole32.CoInitialize(None)
                com_initialized = True
            except Exception:
                pass

            speakers = AudioUtilities.GetSpeakers()
            endpoint = getattr(speakers, "EndpointVolume", None)
            if endpoint is None:
                return None

            muted = bool(endpoint.GetMute())
            return muted
        except Exception as e:
            return None
        finally:
            if ole32 is not None and com_initialized:
                try:
                    ole32.CoUninitialize()
                except Exception:
                    pass

    async def _read_windows_media_state_async(self):
        """Read Windows SMTC playback state using the installed WinRT binding."""
        if not WIN_MEDIA_AVAILABLE or GlobalSystemMediaTransportControlsSessionManager is None:
            return "UNKNOWN"
        manager = await GlobalSystemMediaTransportControlsSessionManager.request_async()
        if manager is None:
            return "STOPPED"
        session = manager.get_current_session()
        if session is None:
            return "STOPPED"
        info = session.get_playback_info()
        if info is None:
            return "UNKNOWN"
        status = getattr(info, "playback_status", None)
        name = getattr(status, "name", None)
        text = str(name or status or "").upper()
        for state in ("PLAYING", "PAUSED", "STOPPED"):
            if state in text:
                return state
        return "UNKNOWN"

    def _read_windows_media_state(self):
        """Synchronous wrapper used only by the background PC-state worker."""
        if not WIN_MEDIA_AVAILABLE:
            return None

        ole32 = None
        com_initialized = False
        try:
            ole32 = ctypes.windll.ole32
            try:
                ole32.CoInitialize(None)
                com_initialized = True
            except Exception:
                pass

            return asyncio.run(self._read_windows_media_state_async())
        except Exception:
            return "UNKNOWN"
        finally:
            if ole32 is not None and com_initialized:
                try:
                    ole32.CoUninitialize()
                except Exception:
                    pass

    def _refresh_pc_state_cache(self):
        """Poll real Windows state without touching audio capture/streaming."""
        media = self._read_windows_media_state()
        muted = self._read_windows_mute_state()

        with self.pc_state_lock:
            if media in ("PLAYING", "PAUSED", "STOPPED"):
                self.pc_media_state = media
                self.pc_media_source = "WINDOWS_MEDIA_SESSION"
            elif not WIN_MEDIA_AVAILABLE:
                self.pc_media_state = "UNKNOWN"
                self.pc_media_source = "WINRT_PROVIDER_UNAVAILABLE"
            else:
                self.pc_media_state = "UNKNOWN"
                self.pc_media_source = "WINDOWS_MEDIA_SESSION_ERROR"

            if muted is not None:
                self.pc_muted = bool(muted)
                self.pc_mute_source = "PYCAW"
            elif not PYCaw_AVAILABLE:
                self.pc_muted = None
                self.pc_mute_source = "PYCAW_PROVIDER_UNAVAILABLE"

    def _pc_state_payload(self):
        with self.pc_state_lock:
            payload = {
                "type": "PC_STATE",
                "media": self.pc_media_state,
                "muted": self.pc_muted,
                "media_source": self.pc_media_source,
                "mute_source": self.pc_mute_source,
            }
        return "PC_STATE:" + json.dumps(payload, separators=(",", ":"))

    def _send_pc_state(self, addr) -> None:
        if not self._is_active_client_ip(addr):
            return

        try:
            payload = self._pc_state_payload().encode("utf-8")
            if self.server_socket:
                self.server_socket.sendto(payload, addr)
        except Exception as e:
            if self.server_running:
                self.log(f"⚠️ PC-state response failed: {e}")

    def _pc_state_worker(self):
        """Continuously refresh real PC state while an Android client is active."""
        provider_logged = False
        while self.server_running and not self.pc_state_stop_event.is_set():
            try:
                if getattr(self, "client_address", None):
                    self._refresh_pc_state_cache()

                    if not provider_logged:
                        with self.pc_state_lock:
                            media_source = self.pc_media_source
                            mute_source = self.pc_mute_source
                        media_error = getattr(self, "_win_media_last_error", "")
                        self.log(
                            f"🖥️ PC state providers: media={media_source}, mute={mute_source}" +
                            (f" | media_error={media_error}" if media_error else "")
                        )
                        provider_logged = True

                self.pc_state_stop_event.wait(1.0)
            except Exception as e:
                if self.server_running:
                    self.log(f"⚠️ PC-state worker error: {e}")
                self.pc_state_stop_event.wait(1.0)

    def _handle_android_pc_control(self, command: str, addr) -> None:
        if not self._is_active_client_ip(addr):
            self.log(f"🛡️ Ignored PC-control command from non-active client {addr[0]}")
            return
        if command == "PC_MEDIA_PLAY_PAUSE":
            if self._send_windows_virtual_key(0xB3):
                self.log(f"⏯️ PC media Play/Pause accepted from {addr[0]}")
                # Refresh immediately so the notification can read the real
                # post-toggle state without waiting for the next worker tick.
                self._refresh_pc_state_cache()
                self._send_pc_state(addr)
            return
        if command == "PC_AUDIO_MUTE":
            if self._send_windows_virtual_key(0xAD):
                self.log(f"🔇 PC master-mute accepted from {addr[0]}")
                self._refresh_pc_state_cache()
                self._send_pc_state(addr)
            return
        self.log(f"⚠️ Unknown Android PC-control command: {command}")

    def _client_handler(self):
        """
        Handle incoming UDP client control/handshake packets.

        Important:
        - DISCONNECT is handled BEFORE CLIENT_INFO.
        - UDP does not create a connected socket per client.
        - self.server_socket remains the shared UDP socket.
        - self.client_address identifies the active Android client.
        """

        while self.server_running:
            try:
                data, addr = self.server_socket.recvfrom(1024)
    
                if not data:
                    continue
    
                text = data.decode("utf-8", errors="ignore").strip()
    
                if not text:
                    continue

                if text.startswith("CLIENT_HEARTBEAT"):
                    if getattr(self, "client_address", None) == addr or (getattr(self, "client_address", None) and self.client_address[0] == addr[0]):
                        self.client_last_seen = time.monotonic()
                    continue

                if text == "PC_STATE_REQUEST":
                    if self._is_active_client_ip(addr):
                        self._send_pc_state(addr)
                    else:
                        self.log(f"🛡️ Ignored PC-state request from non-active client {addr[0]}")
                    continue

                if text in ("PC_MEDIA_PLAY_PAUSE", "PC_AUDIO_MUTE"):
                    self._handle_android_pc_control(text, addr)
                    continue
    
                # ============================================================
                # 1. GRACEFUL CLIENT DISCONNECT
                # ============================================================
                # This MUST be checked before CLIENT_INFO.
                #
                # nativeSendDisconnect() sends:
                #     DISCONNECT\r\n
                #
                # It does NOT necessarily contain CLIENT_INFO.
                # ============================================================
                if text.startswith("DISCONNECT"):
                    if getattr(self, "client_address", None) == addr:
    
                        client_label = self.client_info or "Unknown client"
    
                        self.log(
                            f"🛑 Client Disconnected Gracefully: "
                            f"{client_label} @ {addr[0]}:{addr[1]}"
                        )
    
                        # Stop sender session.
                        self.client_address = None
                        self.client_socket = None
                        self.client_info = None
                        self.client_connected_time = None
                        self.after(0, self._sync_qr_visibility)
                        self._streaming_thread_spawned = False
    
                        try:
                            self.force_disconnect_btn.setEnabled(False)
                        except Exception:
                            pass
    
                        self.update_client_display()
    
                        self.log("🔓 Server ready for new connections")
    
                    continue
    
                # ============================================================
                # 2. IGNORE OTHER NON-HANDSHAKE UDP PACKETS
                # ============================================================
                if "CLIENT_INFO:" not in text:
                    continue
    
                # ============================================================
                # 3. PARSE CLIENT_INFO
                # ============================================================
                try:
                    parts = text.split(":")
    
                    if len(parts) >= 4:
                        manufacturer = parts[1].strip()
                        model = parts[2].strip()
                        device = parts[3].strip()
    
                        self.client_info = (
                            f"{manufacturer} {model} ({device}) @ {addr[0]}"
                        )
                    else:
                        self.client_info = f"Client @ {addr[0]}"
    
                except Exception:
                    self.client_info = f"Android Client @ {addr[0]}"
    
                # ============================================================
                # 4. LOCK THIS UDP CLIENT AS THE ACTIVE STREAM TARGET
                # ============================================================
                self.log(
                    f"🤝 UDP Target Locked: "
                    f"{self.client_info}"
                )
    
                self.client_socket = self.server_socket
                # Preserve the original pre-telemetry handshake default. The Android
                # client sends its selected profile immediately after SERVER_ACK and
                # before native audio reception starts, so Golden/High/Medium are
                # activated from the first audio frame without guessing the mode here.
                self._apply_client_streaming_profile("unknown", "adaptive_v2", client_ip=addr[0])
                self.client_address = addr
                self.client_connected_time = datetime.now()


                self.client_last_seen = time.monotonic()
                # Active connection started: hide the automatic QR.
                self.after(0, self._sync_qr_visibility)

                try:
                    ack = (
                        f"SERVER_ACK:{self.server_name}:"
                        f"{getattr(self, 'current_backend', None) or 'Unknown'}"
                    ).encode("utf-8")
                
                    self.server_socket.sendto(ack, addr)
                
                    self.log(
                        f"🛜 SERVER ACK sent to {addr[0]}:{addr[1]} "
                        f"({self.server_name})"
                    )
                except Exception as e:
                    self.log(f"⚠️ Failed to send SERVER ACK: {e}")
    
                # ============================================================
                # 5. ENABLE SERVER FORCE-DISCONNECT BUTTON
                # ============================================================
                try:
                    self.force_disconnect_btn.setEnabled(True)
                except Exception:
                    pass
    
                # ============================================================
                # 6. ENSURE AUDIO SENDER IS ACTIVE & DRAIN STALE AUDIO
                # ============================================================
                # Flush stale audio to ensure zero-lag playback upon connection/reconnection
                while not self.audio_buffer_queue.empty():
                    try:
                        self.audio_buffer_queue.get_nowait()
                    except queue.Empty:
                        break

                if not hasattr(self, 'sender_thread') or not self.sender_thread or not self.sender_thread.is_alive():
                    self.sender_thread = threading.Thread(
                        target=self._sender_thread,
                        daemon=True,
                        name="WiFiAudioSender"
                    )
                    self.sender_thread.start()

                self.log(
                    f"🚀 AUDIO STREAM ACTIVE -> "
                    f"{addr[0]}:{addr[1]}"
                )

                self.update_client_display()
    
            except socket.timeout:
                # Normal UDP receive timeout.
                # It only allows this loop to check server_running.
                continue
    
            except ConnectionResetError:
                # Windows can report UDP connection-reset events.
                # Treat this as an abrupt client disappearance.
                if getattr(self, "client_address", None):
    
                    old_addr = self.client_address
                    old_client = self.client_info or "Unknown client"
    
                    self.log(
                        f"🛑 Connection Reset: "
                        f"{old_client} @ "
                        f"{old_addr[0]}:{old_addr[1]}"
                    )
    
                    self.client_address = None
                    self.client_socket = None
                    self.client_info = None
                    self.client_connected_time = None
                    self.after(0, self._sync_qr_visibility)
                    self.client_last_seen = 0.0
                    self._streaming_thread_spawned = False
    
                    try:
                        self.force_disconnect_btn.setEnabled(False)
                    except Exception:
                        pass
    
                    try:
                        self.update_client_display()
                    except Exception:
                        pass
    
                    self.log("🔓 Server ready for new connections")
    
                time.sleep(0.01)
                continue
    
            except Exception as e:
    
                if not self.server_running:
                    break
    
                err_text = str(e)
    
                # Expected Windows UDP shutdown/reset conditions.
                if (
                    "10038" in err_text
                    or "10053" in err_text
                    or "10054" in err_text
                ):
                    time.sleep(0.1)
                    continue
    
                self.log(f"⚠️ UDP client handler error: {e}")
                time.sleep(0.1)

    def _client_watchdog(self):
        """Detect clients that disappear without sending DISCONNECT."""
        while self.server_running:
            try:
                client_addr = getattr(self, "client_address", None)
    
                if client_addr and self.client_last_seen > 0:
                    age = time.monotonic() - self.client_last_seen
    
                    if age > self.client_watchdog_timeout:
                        old_client = self.client_info or "Unknown client"
    
                        self.log(
                            f"🛜 Client heartbeat timeout: "
                            f"{old_client} @ {client_addr[0]}:{client_addr[1]} "
                            f"({age:.1f}s)"
                        )
    
                        self.client_address = None
                        self.client_socket = None
                        self.client_info = None
                        self.client_connected_time = None
                        self.after(0, self._sync_qr_visibility)
                        self.client_last_seen = 0.0
                        self._streaming_thread_spawned = False
    
                        try:
                            self.force_disconnect_btn.setEnabled(False)
                        except Exception:
                            pass
    
                        try:
                            self.update_client_display()
                        except Exception:
                            pass
    
                        self.log("🔓 Server ready for new connections")
    
                time.sleep(1.0)
    
            except Exception as e:
                if self.server_running:
                    self.log_rate_limited("client_watchdog_error", f"⚠️ Client watchdog error: {e}", 5.0)
                time.sleep(1.0)

    def _apply_client_streaming_profile(self, buffer_mode, streaming_profile, client_ip=""):
        """Apply the receiver-announced sender profile without changing the PCM packet format."""
        mode_key = str(buffer_mode or "").strip().lower()
        profile_key = str(streaming_profile or "").strip().lower()
        is_golden = (mode_key == "ultra_low" or profile_key == "golden_ultra_low_v1")
        is_high_continuity = (mode_key == "high" or profile_key == "high_continuity_v1") and not is_golden
        is_medium_balanced = (
            (mode_key == "medium" or profile_key == "medium_balanced_v1")
            and not is_golden and not is_high_continuity
        )
        is_low_lipsync = (
            (mode_key == "low" or profile_key == "low_lipsync_v1")
            and not is_golden and not is_high_continuity and not is_medium_balanced
        )
        selected_profile = (
            "golden_ultra_low_v1" if is_golden else
            "high_continuity_v1" if is_high_continuity else
            "medium_balanced_v1" if is_medium_balanced else
            "low_lipsync_v1" if is_low_lipsync else "adaptive_v2"
        )
        selected_mode = mode_key or ("ultra_low" if is_golden else "unknown")
        target_queue_size = (
            4 if is_golden else
            8192 if is_high_continuity else
            32 if is_medium_balanced else
            16 if is_low_lipsync else 12
        )

        with self._client_streaming_profile_lock:
            if (
                self.client_streaming_profile == selected_profile
                and self.client_buffer_mode == selected_mode
                and self.buffer_queue_size == target_queue_size
            ):
                return

            old_profile = self.client_streaming_profile
            self.client_streaming_profile = selected_profile
            self.client_buffer_mode = selected_mode
            self.buffer_queue_size = target_queue_size
            self._medium_backlog_since = 0.0
            self._low_backlog_since = 0.0

            # Keep queue size and sender trimming profile-specific. Low is a short
            # 160ms queue; Medium is a 320ms burst cushion; High preserves audio.
            dropped = 0
            audio_queue = self.audio_buffer_queue
            with audio_queue.mutex:
                audio_queue.maxsize = target_queue_size
                while len(audio_queue.queue) > target_queue_size:
                    audio_queue.queue.popleft()
                    dropped += 1
                if dropped:
                    audio_queue.unfinished_tasks = max(0, audio_queue.unfinished_tasks - dropped)
                    if audio_queue.unfinished_tasks == 0:
                        audio_queue.all_tasks_done.notify_all()
                audio_queue.not_full.notify_all()
                audio_queue.not_empty.notify_all()

            self.log(
                f"🎚️ Client streaming profile: {old_profile} -> {selected_profile} | "
                f"BufferMode={selected_mode} | capture queue={target_queue_size} frames "
                f"(~{target_queue_size * 10}ms) | stale frames trimmed={dropped}"
                + (" | High continuity: no stale-frame trim; no synthetic silence on capture gaps" if is_high_continuity else "")
                + (" | Medium balanced: 32-frame burst cushion; sustained backlog trim only" if is_medium_balanced else "")
                + (" | Low lip-sync: 16-frame queue; 250ms sustained trim guard" if is_low_lipsync else "")
                + (f" | client={client_ip}" if client_ip else "")
            )

    def _telemetry_handler(self):
        """Receive Android telemetry on UDP 50009 and log every valid report.

        The worker is started only after server_running becomes True.
        Malformed/unknown packets and idle diagnostics are rate-limited.
        """
        telemetry_sock = self.telemetry_socket
        if telemetry_sock is None:
            self.log("⚠️ Telemetry receiver did not start: UDP socket is missing")
            return

        started_at = time.monotonic()
        last_valid_at = 0.0
        last_diag_at = 0.0
        last_bad_json_log_at = 0.0
        last_unexpected_type_log_at = 0.0
        datagrams_received = 0
        valid_telemetry_received = 0

        self.log(
            f"🧵 Telemetry receiver thread running; listening on UDP "
            f"0.0.0.0:{self.telemetry_port}"
        )

        def as_int(obj, key, default=0):
            try:
                return int(float(obj.get(key, default)))
            except (TypeError, ValueError, OverflowError):
                return default

        def as_float(obj, key, default=0.0):
            try:
                return float(obj.get(key, default))
            except (TypeError, ValueError, OverflowError):
                return default

        while self.server_running and self.telemetry_socket is telemetry_sock:
            try:
                data, addr = telemetry_sock.recvfrom(8192)
                now = time.monotonic()
                datagrams_received += 1

                try:
                    decoded = data.decode("utf-8", errors="replace").strip()
                    obj = json.loads(decoded)
                except (UnicodeDecodeError, json.JSONDecodeError, TypeError) as e:
                    if now - last_bad_json_log_at >= 15.0:
                        self.log(
                            f"⚠️ UDP {self.telemetry_port} received non-JSON "
                            f"data from {addr[0]}:{addr[1]} ({len(data)} bytes): {e}"
                        )
                        last_bad_json_log_at = now
                    continue

                if not isinstance(obj, dict):
                    if now - last_bad_json_log_at >= 15.0:
                        self.log(
                            f"⚠️ UDP {self.telemetry_port} received JSON that is "
                            f"not an object from {addr[0]}:{addr[1]}"
                        )
                        last_bad_json_log_at = now
                    continue

                packet_type = str(obj.get("type", "")).strip().upper()
                if packet_type != "TELEMETRY":
                    if now - last_unexpected_type_log_at >= 15.0:
                        self.log(
                            f"ℹ️ UDP {self.telemetry_port} received a datagram "
                            f"from {addr[0]}:{addr[1]} with type="
                            f"{packet_type or '(missing)'}; expected TELEMETRY"
                        )
                        last_unexpected_type_log_at = now
                    continue

                q = as_int(obj, "quality", 0)
                rtt = as_float(obj, "rtt", 0.0)
                jit = as_float(obj, "jitter", 0.0)
                conn = str(obj.get("conn", obj.get("connection_type", "UNKNOWN")))
                rssi = as_int(obj, "rssi", -99)
                packets_received = as_int(obj, "packets_received", 0)
                packets_dropped = as_int(obj, "packets_dropped", 0)
                buffer_underruns = as_int(obj, "buffer_underruns", 0)
                audio_track_underruns = as_int(obj, "audio_track_underruns", -1)
                buffer_level = as_int(obj, "buffer_level", 0)
                buffer_ms = as_int(obj, "buffer_ms", 0)
                mode = str(obj.get("mode", "UNKNOWN"))
                buffer_mode = str(obj.get("buffer_mode", mode))
                streaming_profile = str(obj.get("streaming_profile", "")).strip().lower()
                sync_score = as_int(obj, "sync_score", 0)
                low_lipsync_skipped_blocks = as_int(obj, "low_lipsync_skipped_blocks", 0)

                # A phone sends telemetry from a different UDP source port than
                # the audio target, so correlate by active client IP, not port.
                active_client = getattr(self, "client_address", None)
                if active_client and addr[0] == active_client[0]:
                    self._apply_client_streaming_profile(
                        buffer_mode,
                        streaming_profile,
                        client_ip=addr[0]
                    )

                self.last_client_telemetry_conn = conn
                valid_telemetry_received += 1
                last_valid_at = now

                try:
                    client_network_type = self.classify_client_network_type(
                        addr[0],
                        conn
                    )
                except Exception:
                    client_network_type = str(conn or "UNKNOWN")

                client_ip = addr[0]
                state_key = f"{conn}|{buffer_mode}|{streaming_profile}|{client_network_type}"
                last_log_at = self._telemetry_log_times.get(client_ip, 0.0)
                previous_state = self._telemetry_log_states.get(client_ip)
                if state_key != previous_state or now - last_log_at >= 5.0:
                    self._telemetry_log_states[client_ip] = state_key
                    self._telemetry_log_times[client_ip] = now
                    self.log(
                        f"📡 Telemetry from {addr[0]} → "
                        f"Q={q}, RTT={rtt:.0f}ms, Jitter={jit:.1f}ms, RSSI={rssi} | "
                        f"{client_network_type} | Conn={conn} | "
                        f"BufferMode={buffer_mode} | Mode={mode} | "
                        f"StreamingProfile={streaming_profile or 'adaptive_v2'} | "
                        f"RX={packets_received} DROP={packets_dropped} "
                        f"RING_TIMEOUT={buffer_underruns} AT_UNDERRUN={audio_track_underruns} "
                        f"BUF={buffer_level} BUF_MS={buffer_ms} SYNC={sync_score}% "
                        f"LOW_SKIP={low_lipsync_skipped_blocks} "
                        f"SERVER_LOW_QDROP={self.low_capture_queue_drops} "
                        f"LOW_SILENCE={self.low_silence_injections}"
                    )

            except socket.timeout:
                now = time.monotonic()
                # First diagnostic after 15s; thereafter at most every 30s.
                should_report = (
                    (last_diag_at == 0.0 and now - started_at >= 15.0)
                    or (last_diag_at > 0.0 and now - last_diag_at >= 30.0)
                )
                if should_report:
                    if datagrams_received == 0:
                        self.log(
                            f"⚠️ No UDP datagrams received on telemetry port "
                            f"{self.telemetry_port} after {now - started_at:.0f}s. "
                            "Check Android telemetry send logs and allow inbound "
                            f"UDP {self.telemetry_port} in Windows Defender Firewall."
                        )
                        last_diag_at = now
                    elif valid_telemetry_received == 0:
                        self.log(
                            f"⚠️ Telemetry port {self.telemetry_port} received "
                            f"{datagrams_received} UDP datagram(s), but none had "
                            "JSON type=TELEMETRY. Check the Android payload/schema."
                        )
                        last_diag_at = now
                    elif now - last_valid_at >= 45.0:
                        self.log(
                            f"⚠️ Telemetry became idle: no valid report for "
                            f"{now - last_valid_at:.0f}s; valid reports received="
                            f"{valid_telemetry_received}"
                        )
                        last_diag_at = now
                continue

            except OSError as e:
                # A socket-close during Stop is expected.
                if not self.server_running or self.telemetry_socket is not telemetry_sock:
                    break
                now = time.monotonic()
                if now - last_diag_at >= 5.0:
                    self.log(f"⚠️ Telemetry UDP receiver error: {e}")
                    last_diag_at = now
                time.sleep(0.1)

            except Exception as e:
                now = time.monotonic()
                if now - last_diag_at >= 5.0:
                    self.log(
                        f"⚠️ Telemetry handler error on UDP {self.telemetry_port}: "
                        f"{type(e).__name__}: {e}"
                    )
                    last_diag_at = now
                time.sleep(0.1)

        self.log(
            f"🧵 Telemetry receiver stopped; UDP datagrams={datagrams_received}, "
            f"valid telemetry={valid_telemetry_received}"
        )

    def _start_discovery_server(self):
        """Start UDP discovery servers"""
        # Primary discovery server
        self.discovery_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.discovery_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.discovery_socket.bind(('0.0.0.0', self.discovery_port))
        
        # Legacy discovery server
        self.discovery_socket_legacy = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.discovery_socket_legacy.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.discovery_socket_legacy.bind(('0.0.0.0', self.legacy_discovery_port))
        
        self.discovery_running = True
        
        # Start discovery threads
        threading.Thread(target=self._discovery_handler, args=(self.discovery_socket,), daemon=True).start()
        threading.Thread(target=self._discovery_handler, args=(self.discovery_socket_legacy,), daemon=True).start()

    def _discovery_handler(self, sock):
        """Handle discovery requests"""
        while self.discovery_running:
            try:
                data, addr = sock.recvfrom(1024)
                message = data.decode('utf-8')
                
                if "PING_TEST" in message:
                    try:
                        sock.sendto(b"PONG", addr)
                        self.ping_response_count += 1
                        if self.ping_response_count % 10 == 1:
                            self.log(f"📶 Ping response sent to {addr[0]}")
                    except Exception as e:
                        self.log(f"⚠️ Ping response failed: {e}")
                    continue
                
                if "DISCOVER_WIFI_AUDIO_REQUEST" in message:
                    # Return the NIC IP that can reach this peer
                    try:
                        tmp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                        tmp.connect((addr[0], addr[1]))
                        local_ip_for_peer = tmp.getsockname()[0]
                        tmp.close()
                    except Exception:
                        local_ip_for_peer = self.local_ip.split(" / ")[0]
                        
                    backend_name = (
                        getattr(self, "current_backend", None)
                        or "Unknown"
                    )
                    
                    server_net_type, server_adapter, server_ssid = \
                        self.get_server_network_info(local_ip_for_peer)
                    
                    # Keep the existing colon-delimited discovery protocol intact,
                    # and put additional metadata after a pipe separator.
                    metadata = (
                        f"NET={server_net_type}|"
                        f"ADAPTER={server_adapter}|"
                        f"SSID={server_ssid}|"
                        f"SERVER_IP={local_ip_for_peer}"
                    )
                    
                    response = (
                        f"WIFI_AUDIO_SERVER:"
                        f"{local_ip_for_peer}:"
                        f"{self.audio_port}:"
                        f"{self.server_name}:"
                        f"{backend_name}|"
                        f"{metadata}"
                    )
                    
                    sock.sendto(
                        response.encode("utf-8"),
                        addr
                    )
                    
                    self.log(
                        f"📡 Discovery response sent to {addr[0]} "
                        f"from ({self.server_name}) with {backend_name} | "
                        f"{server_net_type} | Adapter={server_adapter} | "
                        f"SSID={server_ssid}"
                    )
                    
            except Exception as e:
                if self.discovery_running:
                    self.log(f"⚠️ Discovery handler error: {e}")
                break

    def _pace_until(self, deadline: float) -> None:
        while True:
            remaining = deadline - time.perf_counter()
            if remaining <= 0:
                return
            if remaining > 0.003:
                time.sleep(min(remaining - 0.001, 0.005))
            else:
                time.sleep(0)

    def _sender_thread(self):
        """Send PCM16 mono frames paced directly by audio capture queue to client."""
        frame_samples = self.stream_blocksize  # 480 samples = 10ms @ 48kHz
        bytes_per_sample = 2  # 16-bit mono
        frame_bytes = frame_samples * bytes_per_sample  # 960 bytes for 480 mono samples
        frames_sent = 0

        self.log(f"🚀 SENDER THREAD STARTED:")
        self.log(f"   Frame Size: {frame_samples} samples (10.0ms)")
        self.log(f"   Sample Rate: {self.samplerate} Hz | Format: MONO 16-bit")
        self.log(f"   Packet Format: 4B sequence header + {frame_bytes}B PCM = {4 + frame_bytes}B total")

        last_target = None
        # 🎯 LEAKY BUCKET TIMER: strict 10ms pacing decouple from WASAPI capture bursts
        next_frame_time = time.perf_counter()
        frame_duration = frame_samples / self.samplerate

        while self.server_running:
            target_addr = getattr(self, "client_address", None)
            if target_addr is None:
                # No client connected; drain queue to keep it real-time fresh
                try:
                    self.audio_buffer_queue.get(timeout=0.03)
                except queue.Empty:
                    pass
                next_frame_time = time.perf_counter()
                continue

            if target_addr != last_target:
                self.log(f"🚀 Audio sender locked to target -> {target_addr[0]}:{target_addr[1]}")
                last_target = target_addr
                # Reset the clock when a new client connects
                next_frame_time = time.perf_counter()

            try:
                active_profile = getattr(self, "client_streaming_profile", "adaptive_v2")
                if active_profile == "golden_ultra_low_v1":
                    # Golden pipeline: real WASAPI frames are sent on the capture
                    # queue's cadence (no independent 10ms sender timer). If the
                    # capture source stalls, only the silence branch is software-paced.
                    try:
                        data = self.audio_buffer_queue.get(timeout=0.1)
                        mono_data = self._convert_to_mono_samples(data, frame_samples)
                        next_frame_time = time.perf_counter() + frame_duration
                    except queue.Empty:
                        mono_data = np.zeros(frame_samples, dtype=np.int16)
                        self.silence_injections += 1

                        now = time.perf_counter()
                        sleep_time = next_frame_time - now
                        if sleep_time > 0:
                            if sleep_time > 0.002:
                                time.sleep(sleep_time - 0.002)
                            while time.perf_counter() < next_frame_time:
                                pass
                        elif sleep_time < -0.05:
                            next_frame_time = time.perf_counter()
                        next_frame_time += frame_duration
                elif active_profile == "medium_balanced_v1":
                    # Medium remains hardware-independent 10ms paced, but uses a
                    # 32-frame capture cushion. Only trim when the queue is almost
                    # full and remains that way for 500ms; retain ~160ms so bursts
                    # are absorbed without allowing indefinite sync drift.
                    now = time.perf_counter()
                    if next_frame_time > now:
                        self._pace_until(next_frame_time)
                    send_now = time.perf_counter()
                    if send_now > next_frame_time:
                        next_frame_time = send_now
                    try:
                        data = self.audio_buffer_queue.get(timeout=max(frame_duration * 1.5, 0.02))
                        backlog = self.audio_buffer_queue.qsize()
                        if backlog >= 28:
                            if not self._medium_backlog_since:
                                self._medium_backlog_since = time.monotonic()
                            elif time.monotonic() - self._medium_backlog_since >= 0.50:
                                dropped = 0
                                target_depth = 16
                                while self.audio_buffer_queue.qsize() > target_depth:
                                    try:
                                        self.audio_buffer_queue.get_nowait()
                                        dropped += 1
                                    except queue.Empty:
                                        break
                                self._medium_backlog_since = 0.0
                                if dropped:
                                    self.medium_capture_queue_drops += dropped
                                    self.log_rate_limited(
                                        "medium_sustained_backlog_trim",
                                        f"⚖️ Medium sustained backlog: trimmed {dropped} stale frame(s); retained {target_depth} (~160ms)",
                                        10.0,
                                    )
                        else:
                            self._medium_backlog_since = 0.0
                        mono_data = self._convert_to_mono_samples(data, frame_samples)
                    except queue.Empty:
                        mono_data = np.zeros(frame_samples, dtype=np.int16)
                        self.silence_injections += 1
                    next_frame_time = max(
                        next_frame_time + frame_duration,
                        send_now + frame_duration
                    )
                elif active_profile == "low_lipsync_v1":
                    # Low follows the WASAPI capture queue cadence for REAL PCM.
                    # The capture callback already produces 10ms frames; a second
                    # independent 10ms timer can lag behind it under Python/Windows
                    # scheduling and repeatedly fill/trim the 16-frame queue.
                    # Drain real frames as they arrive. Only synthetic silence during
                    # a genuine capture gap is paced by next_frame_time.
                    try:
                        # A 12.5ms timeout was too close to the 10ms capture
                        # cadence on Windows and could turn small callback delays
                        # into artificial silence packets. Allow up to 25ms only
                        # for Low; real frames still pass immediately on arrival.
                        data = self.audio_buffer_queue.get(timeout=max(frame_duration * 2.5, 0.025))
                        backlog = self.audio_buffer_queue.qsize()
                        if backlog >= 12:
                            if not self._low_backlog_since:
                                self._low_backlog_since = time.monotonic()
                            elif time.monotonic() - self._low_backlog_since >= 0.25:
                                dropped = 0
                                target_depth = 8
                                while self.audio_buffer_queue.qsize() > target_depth:
                                    try:
                                        self.audio_buffer_queue.get_nowait()
                                        dropped += 1
                                    except queue.Empty:
                                        break
                                self._low_backlog_since = 0.0
                                if dropped:
                                    self.low_capture_queue_drops += dropped
                                    self.log_rate_limited(
                                        "low_sustained_backlog_trim",
                                        f"🎯 Low lip-sync backlog: trimmed {dropped} stale frame(s); retained {target_depth} (~80ms)",
                                        5.0,
                                    )
                        else:
                            self._low_backlog_since = 0.0
                        mono_data = self._convert_to_mono_samples(data, frame_samples)
                        # Real frames are capture-clock paced. Reset the silence
                        # deadline after each captured frame; do not add another timer.
                        next_frame_time = time.perf_counter() + frame_duration
                    except queue.Empty:
                        self._low_backlog_since = 0.0
                        mono_data = np.zeros(frame_samples, dtype=np.int16)
                        self.silence_injections += 1
                        self.low_silence_injections += 1
                        now = time.perf_counter()
                        sleep_time = next_frame_time - now
                        if sleep_time > 0:
                            self._pace_until(next_frame_time)
                        elif sleep_time < -0.05:
                            next_frame_time = now
                        next_frame_time += frame_duration
                else:
                    # Existing adaptive_v2 sender remains for Custom/legacy modes;
                    # its original timing and anti-burst trimming are left unchanged.
                    now = time.perf_counter()
                    if next_frame_time > now:
                        self._pace_until(next_frame_time)

                    send_now = time.perf_counter()
                    if send_now > next_frame_time:
                        next_frame_time = send_now

                    try:
                        data = self.audio_buffer_queue.get(timeout=max(frame_duration * 1.5, 0.02))
                        backlog = self.audio_buffer_queue.qsize()
                        high_continuity = (
                            getattr(self, "client_buffer_mode", "medium") == "high"
                            or getattr(self, "client_streaming_profile", "") == "high_continuity_v1"
                        )
                        if backlog >= 8 and not high_continuity:
                            dropped = 0
                            target_depth = 4
                            while self.audio_buffer_queue.qsize() > target_depth:
                                try:
                                    self.audio_buffer_queue.get_nowait()
                                    dropped += 1
                                except queue.Empty:
                                    break
                            if dropped:
                                with self._capture_backlog_log_lock:
                                    log_now = time.monotonic()
                                    should_log_backlog = (
                                        log_now - self._last_capture_backlog_log_at >=
                                        self._capture_backlog_log_interval_s
                                    )
                                    if should_log_backlog:
                                        self._last_capture_backlog_log_at = log_now
                                if should_log_backlog:
                                    self.log(
                                        f"⏩ Capture queue sustained backlog; dropped {dropped} stale frame(s), "
                                        f"retained {target_depth} (repeated warning limited to every "
                                        f"{int(self._capture_backlog_log_interval_s)}s)"
                                    )
                        mono_data = self._convert_to_mono_samples(data, frame_samples)
                    except queue.Empty:
                        high_continuity = (
                            getattr(self, "client_buffer_mode", "medium") == "high"
                            or getattr(self, "client_streaming_profile", "") == "high_continuity_v1"
                        )
                        if high_continuity:
                            # Preserve the audio timeline rather than replacing a short
                            # capture stall with a synthetic silence packet. Playback may
                            # drift, which is the explicit High-mode tradeoff.
                            next_frame_time = time.perf_counter()
                            continue
                        mono_data = np.zeros(frame_samples, dtype=np.int16)
                        self.silence_injections += 1

                    next_frame_time = max(
                        next_frame_time + frame_duration,
                        send_now + frame_duration
                    )

                # Verify mono_data length
                if len(mono_data) != frame_samples:
                    if len(mono_data) < frame_samples:
                        padding = np.zeros(frame_samples - len(mono_data), dtype=np.int16)
                        mono_data = np.concatenate([mono_data, padding])
                    else:
                        mono_data = mono_data[:frame_samples]

                payload = mono_data.astype(np.int16).tobytes()

                # Ensure exact payload length (960 bytes)
                if len(payload) != frame_bytes:
                    if len(payload) < frame_bytes:
                        payload += b'\x00' * (frame_bytes - len(payload))
                    else:
                        payload = payload[:frame_bytes]

                # 4-byte big-endian sequence header + 960B PCM payload
                sequence_bytes = struct.pack('!I', self.packets_sent % 4294967296)
                packet = sequence_bytes + payload

                curr_target = getattr(self, "client_address", None)
                if curr_target and self.server_socket:
                    self.server_socket.sendto(packet, curr_target)
                    self.packets_sent += 1
                    self.bytes_sent += len(packet)
                    frames_sent += 1

                    if frames_sent % 5000 == 0:
                        self.log(f"📤 Sent {frames_sent} packets ({frames_sent * 10 / 1000:.1f}s streamed)")

            except Exception as e:
                if self.server_running:
                    error_msg = str(e)
                    if "10051" not in error_msg and "10054" not in error_msg:
                        self.log_rate_limited("udp_sender_error", f"⚠️ UDP Sender error: {e}", 5.0)
                time.sleep(0.01)

        self.log("🛑 Audio sender thread terminated.")

    def get_windows_hotspot_info(self):
        """Detect Windows Mobile Hotspot by IP presence only.

        No SSID/netsh/PowerShell/Wi-Fi Direct inspection is performed.
        Windows Mobile Hotspot / ICS normally owns 192.168.137.1.
        """
        try:
            interfaces = psutil.net_if_addrs()
            stats = psutil.net_if_stats()
            for iface_name, addrs in interfaces.items():
                ipv4s = [a.address for a in addrs if a.family == socket.AF_INET]
                if "192.168.137.1" not in ipv4s:
                    continue
                iface_stats = stats.get(iface_name)
                if iface_stats is not None and not iface_stats.isup:
                    continue
                self.windows_hotspot_state = True
                self.windows_hotspot_adapter = iface_name
                self.windows_hotspot_ip = "192.168.137.1"
                return True, iface_name, "192.168.137.1"
            self.windows_hotspot_state = False
            self.windows_hotspot_adapter = "N/A"
            self.windows_hotspot_ip = "N/A"
        except Exception as e:
            self.log(f"⚠️ Windows Hotspot IP scan failed: {e}")
        return False, "N/A", "N/A"

    def get_windows_wifi_direct_info(self):
        """
        Detect Windows Wi-Fi Direct virtual adapters for diagnostics.

        Windows 10 often exposes the adapter as ``Local Area Connection* N``
        even though its real interface description is ``Microsoft Wi-Fi Direct
        Virtual Adapter``.  We therefore use psutil first and a short
        Get-NetAdapter query as a Windows-specific fallback.

        This is diagnostic/control-plane information only.  The Miracast
        ``Projecting to this PC`` feature is not treated as a normal UDP LAN
        endpoint for the audio protocol.
        """
        found = []
        seen = set()

        try:
            interfaces = psutil.net_if_addrs()
            stats = psutil.net_if_stats()

            # Fast path: some Windows builds expose "Wi-Fi Direct" directly
            # in the interface name.
            for iface_name, addrs in interfaces.items():
                low = iface_name.lower()
                if "wi-fi direct" not in low and "wifi direct" not in low:
                    continue

                iface_stats = stats.get(iface_name)
                if iface_stats is not None and not iface_stats.isup:
                    continue

                ipv4s = [
                    a.address
                    for a in addrs
                    if a.family == socket.AF_INET
                    and not a.address.startswith("127.")
                    and not a.address.startswith("169.254.")
                ]
                found.append((iface_name, ipv4s))
                seen.add(iface_name.lower())

            # Windows-specific fallback: the user-facing name is commonly
            # "Local Area Connection* N", while the interface description
            # contains "Wi-Fi Direct Virtual Adapter".
            if platform.system() == "Windows":
                try:
                    command = (
                        "powershell -NoProfile -ExecutionPolicy Bypass "
                        "-Command \"$ErrorActionPreference='SilentlyContinue'; "
                        "$a=Get-NetAdapter -IncludeHidden | "
                        "Select-Object Name,InterfaceDescription,Status; "
                        "$a | ConvertTo-Json -Compress\""
                    )
                    proc = subprocess.run(
                        command,
                        shell=True,
                        capture_output=True,
                        text=True,
                        timeout=2.5
                    )
                    if proc.returncode == 0 and proc.stdout.strip():
                        raw = json.loads(proc.stdout.strip())
                        adapters = raw if isinstance(raw, list) else [raw]
                        for item in adapters:
                            name = str(item.get("Name") or "").strip()
                            desc = str(item.get("InterfaceDescription") or "").lower()
                            status = str(item.get("Status") or "").lower()
                            if "wi-fi direct" not in desc and "wifi direct" not in desc:
                                continue
                            if status and status not in ("up", "connected"):
                                continue

                            key = name.lower()
                            if key in seen:
                                continue

                            ipv4s = []
                            for addr in interfaces.get(name, []):
                                if (
                                    addr.family == socket.AF_INET
                                    and not addr.address.startswith("127.")
                                    and not addr.address.startswith("169.254.")
                                ):
                                    ipv4s.append(addr.address)

                            found.append((name or "Wi-Fi Direct Virtual Adapter", ipv4s))
                            seen.add(key)
                except Exception as e:
                    self.log(f"ℹ️ Wi-Fi Direct PowerShell query unavailable: {e}")

            self.windows_wifi_direct_adapters = found
            self.windows_wifi_direct_state = bool(found)
            return bool(found), found
        except Exception as e:
            self.windows_wifi_direct_adapters = []
            self.windows_wifi_direct_state = False
            self.log(f"⚠️ Wi-Fi Direct adapter scan failed: {e}")
            return False, []

    def _convert_to_mono_samples(self, data, samples):
        """Convert captured audio array to exact length mono int16 array."""
        if data is None:
            return np.zeros(samples, dtype=np.int16)

        if data.ndim == 2:
            if data.shape[1] >= 2:
                mono = (data[:, 0].astype(np.float32) + data[:, 1].astype(np.float32)) * 0.5
                mono = np.clip(mono, -32768.0, 32767.0).astype(np.int16)
            else:
                mono = data[:, 0]
        elif data.ndim == 1:
            mono = data
        else:
            return np.zeros(samples, dtype=np.int16)

        flat = mono.flatten()
        if len(flat) >= samples:
            return flat[:samples]
        else:
            padding = np.zeros(samples - len(flat), dtype=np.int16)
            return np.concatenate([flat, padding])

    def _start_audio_capture(self):
        """Start audio capture using the selected backend"""
        if not self.current_backend:
            self.detect_best_backend()
        
        if not self.current_backend:
            self.log("❌ No backend available for audio capture")
            return
        
        self.log(f"🎵 Starting audio capture with {self.current_backend}")
        
        if self.current_backend == "pyaudiowpatch":
            threading.Thread(target=self._capture_pyaudiowpatch, daemon=True).start()
        elif self.current_backend == "sounddevice":
            threading.Thread(target=self._capture_sounddevice, daemon=True).start()
        elif self.current_backend == "soundcard":
            threading.Thread(target=self._capture_soundcard, daemon=True).start()

    def _capture_pyaudiowpatch(self):
        """FIXED: PyAudioWPatch capture with proper system audio detection
        BULLETPROOF: PyAudioWPatch with Auto-Recovery for Windows Audio Sleep"""
        
        # 🎯 AUTO-RECOVERY LOOP: If Windows kills the mic, the server just restarts it automatically!
        while self.server_running:
            try:
                # Capture Jitter Tracking
                current_time = time.perf_counter()
                if hasattr(self, 'last_capture_time'):
                    jitter = (current_time - self.last_capture_time) * 1000
                    if jitter > 15: # If capture takes > 15ms (for 10ms chunks), warn!
                            self.log_rate_limited("capture_interval_lag", f"🚨 PC LAG DETECTED: Capture interval {jitter:.2f}ms (repeat diagnostics limited to 5s)", 5.0)
                self.last_capture_time = current_time
                self.pyaudio_instance = pyaudio.PyAudio()
    
                # 🔍 CRITICAL: Find actual WASAPI loopback device
                device_index = None
                device_name = "Unknown"
                
                # Method 1: Try default WASAPI loopback
                try:
                    default_loopback = self.pyaudio_instance.get_default_wasapi_loopback()
                    if default_loopback:
                        device_index = default_loopback['index']
                        device_name = default_loopback['name']
                        self.log(f"✅ Found default WASAPI loopback: {device_name}")
                except Exception as e:
                    self.log(f"⚠️ Default loopback detection failed: {e}")
                
                # Method 2: Try loopback generator if default failed
                if device_index is None:
                    try:
                        for loopback_info in self.pyaudio_instance.get_loopback_device_info_generator():
                            device_index = loopback_info['index']
                            device_name = loopback_info['name']
                            self.log(f"✅ Found loopback device: {device_name}")
                            break
                    except Exception as e:
                        self.log(f"⚠️ Loopback generator failed: {e}")
                
                # If no device, wait and retry cleanly
                if device_index is None:
                    self.log("⚠️ No WASAPI loopback device found. Retrying in 2s (Ensure audio device is active)...")
                    if self.pyaudio_instance:
                        self.pyaudio_instance.terminate()
                    time.sleep(2)
                    continue
                
                # 🎯 CRITICAL FIX: Get actual native hardware rate to prevent Errno -9997
                device_info = self.pyaudio_instance.get_device_info_by_index(device_index)
                device_rate = int(device_info["defaultSampleRate"])
                device_channels = int(device_info["maxInputChannels"]) if device_info["maxInputChannels"] > 0 else 2
                
                # 🎯 OPTIMIZED: Low-latency configuration
                capture_block = int(device_rate * 0.01)  # 10ms at 48kHz for ultra-low latency
                
                self.log(f"🎯 WASAPI CAPTURE SETUP:")
                self.log(f"   Device: {device_name}")
                self.log(f"   Format: 48000Hz STEREO -> MONO conversion")
                self.log(f"   Ultra-Low Latency Block: {capture_block} samples (~10ms)")
        
                def audio_callback(in_data, frame_count, time_info, status):
                    try:
                        if status:
                            self.log_rate_limited("pyaudio_callback_status", f"⚠️ Audio callback status: {status} (repeat diagnostics limited to 5s)", 5.0)
                            
                        # Convert bytes to numpy array (STEREO from WASAPI)
                        # Proper STEREO to MONO conversion
                        raw_audio_data = np.frombuffer(in_data, dtype=np.int16)
                        
                        
                        # 1. Process at Native Hardware Rate (STEREO -> MONO)
                        expected_elements = capture_block * device_channels
                        if raw_audio_data.size >= expected_elements and device_channels >= 2:
                            stereo_data = raw_audio_data[:expected_elements].reshape(-1, device_channels)
                            # Extract just 2 channels if it's > 2 (e.g., 5.1), then process
                            stereo_data_2ch = stereo_data[:, :2] 
                            processed_native_rate = process_mono_int16(stereo_data_2ch)
                        elif raw_audio_data.size >= capture_block:
                             # Fallback if mono
                            processed_native_rate = raw_audio_data[:capture_block]
                        else:
                            processed_native_rate = np.zeros(capture_block, dtype=np.int16)
                            
                        
                        # 2. 🎯 REAL-TIME NUMPY RESAMPLING (e.g. 48000Hz -> 44100Hz)
                        if device_rate != self.samplerate:
                            old_indices = np.arange(len(processed_native_rate))
                            new_indices = np.linspace(0, len(processed_native_rate) - 1, self.capture_blocksize)
                            mono_data = np.interp(new_indices, old_indices, processed_native_rate).astype(np.int16)
                        else:
                            mono_data = processed_native_rate
                        
                        # 3. FIXED: Ensure exact blocksize frames for UDP Target
                        if len(mono_data) != self.capture_blocksize:
                            if len(mono_data) > self.capture_blocksize:
                                mono_data = mono_data[:self.capture_blocksize]
                            else:
                                padding = np.zeros(self.capture_blocksize - len(mono_data), dtype=np.int16)
                                mono_data = np.concatenate([mono_data, padding])
                
                        
                        # 🔍 CRITICAL: Check if we're getting real audio
                        rms = np.sqrt(np.mean(mono_data.astype(float) ** 2) / 32768.0)
                        
                        if rms > 0.001:  # Real audio detected
                            self.real_audio_frames += 1
                            if self.real_audio_frames % 2000 == 1:  # One content-level summary about every 20s
                                self.log(f"🎵 REAL AUDIO: Frame {self.real_audio_frames}, RMS: {rms:.4f}")    
                            
                        
                        # Add to queue with overflow protection
                        try:
                            self.audio_buffer_queue.put_nowait(mono_data)
                            self.total_audio_processed += len(mono_data)
                        except queue.Full:
                            active_mode = getattr(self, "client_buffer_mode", "medium")
                            if active_mode == "high":
                                # High retains every queued frame; never evict older PCM.
                                self.capture_queue_overruns += 1
                                self.log_rate_limited(
                                    "high_capture_queue_full",
                                    f"⚠️ High capture queue capacity reached ({self.capture_queue_overruns} events); preserving older queued audio",
                                    10.0
                                )
                            elif active_mode == "medium":
                                # Medium keeps the newest live edge only when the full
                                # 320ms capture cushion is exhausted; don't mislabel this
                                # producer-queue overflow as a playback underrun.
                                try:
                                    self.audio_buffer_queue.get_nowait()
                                    self.audio_buffer_queue.put_nowait(mono_data)
                                    self.medium_capture_queue_drops += 1
                                    self.log_rate_limited(
                                        "medium_capture_queue_full",
                                        f"⚖️ Medium capture queue full; replaced oldest queued frame (events={self.medium_capture_queue_drops})",
                                        10.0,
                                    )
                                except queue.Empty:
                                    pass
                            elif active_mode == "low":
                                try:
                                    self.audio_buffer_queue.get_nowait()
                                    self.audio_buffer_queue.put_nowait(mono_data)
                                    self.low_capture_queue_drops += 1
                                    self.log_rate_limited(
                                        "low_capture_queue_full",
                                        f"🎯 Low capture queue full; replaced oldest 10ms frame (events={self.low_capture_queue_drops})",
                                        5.0,
                                    )
                                except queue.Empty:
                                    pass
                            else:
                                # Low/Custom retain their existing adaptive queue policy.
                                try:
                                    self.audio_buffer_queue.get_nowait()
                                    self.audio_buffer_queue.put_nowait(mono_data)
                                    self.buffer_underruns += 1
                                except queue.Empty:
                                    pass
                            
                    except Exception as e:
                        self.log_rate_limited("pyaudio_callback_error", f"⚠️ Audio callback error: {e}", 5.0)
                        
                    return (None, pyaudio.paContinue)
            
                # 🎯 CRITICAL: Open stream with native hardware settings
                self.audio_stream = self.pyaudio_instance.open(
                    format=pyaudio.paInt16,
                    channels=device_channels,  # Capture native channels from WASAPI
                    rate=device_rate,
                    input=True,
                    input_device_index=device_index,
                    frames_per_buffer=capture_block,  # Ultra-low latency based on native rate
                    stream_callback=audio_callback
                )
                
                # Start stream and log configuration
                self.audio_stream.start_stream()
                self.log(f"✅ PyAudioWPatch LOW-LATENCY capture started")
                self.log(f"📤 SENDING: {self.samplerate}Hz MONO 16-bit, {self.stream_blocksize} samples/frame")
                
                
                # Keep thread alive cleanly. If Windows kills the stream, this loop exits.  
                while self.audio_stream.is_active() and self.server_running:
                    time.sleep(0.5)  
                            
                # Clean up the dead stream before attempting to loop and restart  
                self.audio_stream.stop_stream()  
                self.audio_stream.close() 
                self.pyaudio_instance.terminate()
                    
            except Exception as e:
                # 🎯 AUTO-RECOVERY
                self.log(f"⚠️ PyAudioWPatch capture loop error ({e}). Auto-recovering in 1s...")
                try:
                    if hasattr(self, 'audio_stream') and self.audio_stream:
                        self.audio_stream.stop_stream()
                        self.audio_stream.close()
                    if hasattr(self, 'pyaudio_instance') and self.pyaudio_instance:
                        self.pyaudio_instance.terminate()
                except Exception:
                    pass
                time.sleep(1)  # Wait 1 second before restarting the loop

    def _capture_sounddevice(self):
        """Capture audio using SoundDevice"""
        if not SOUNDDEVICE_AVAILABLE:
            return
        
        def audio_callback(indata, frames, time, status):
            try:
                # Convert stereo to mono if needed
                if indata.ndim == 2 and indata.shape[1] >= 2:
                    mono = (indata[:, 0] + indata[:, 1]) / 2.0
                else:
                    mono = indata.flatten()
                
                # Convert to int16
                mono_int16 = (mono * 32767).astype(np.int16)
                
                # Resize to stream block size
                if len(mono_int16) < self.stream_blocksize:
                    mono_int16 = np.resize(mono_int16, self.stream_blocksize)
                elif len(mono_int16) > self.stream_blocksize:
                    mono_int16 = mono_int16[:self.stream_blocksize]
                
                # Add to buffer queue
                try:
                    self.audio_buffer_queue.put_nowait(mono_int16)
                    self.real_audio_frames += 1
                except queue.Full:
                    active_mode = getattr(self, "client_buffer_mode", "medium")
                    if active_mode == "high":
                        self.capture_queue_overruns += 1
                        self.log_rate_limited("high_capture_queue_full_sd", f"⚠️ High capture queue capacity reached ({self.capture_queue_overruns} events); preserving queued audio", 10.0)
                    else:
                        try:
                            self.audio_buffer_queue.get_nowait()
                            self.audio_buffer_queue.put_nowait(mono_int16)
                            if active_mode == "medium":
                                self.medium_capture_queue_drops += 1
                                self.log_rate_limited("medium_capture_queue_full_sd", f"⚖️ Medium capture queue full; replaced oldest frame (events={self.medium_capture_queue_drops})", 10.0)
                            elif active_mode == "low":
                                self.low_capture_queue_drops += 1
                                self.log_rate_limited("low_capture_queue_full_sd", f"🎯 Low capture queue full; replaced oldest frame (events={self.low_capture_queue_drops})", 5.0)
                            else:
                                self.buffer_underruns += 1
                        except queue.Empty:
                            pass
                #This keeps the newest audio rather than silently rejecting the newest frame.    
            except Exception as e:
                self.log_rate_limited("sounddevice_callback_error", f"⚠️ SoundDevice callback error: {e}", 5.0)
        
        try:
            device_index = self._find_sounddevice_loopback()
            if device_index is None:
                self.log("❌ SoundDevice: No suitable device found for capture")
                return
            
            self.log("✅ SoundDevice audio capture started")
            
            with sd.InputStream(callback=audio_callback,
                              device=device_index,
                              channels=2,
                              samplerate=self.samplerate,
                              blocksize=self.capture_blocksize,
                              dtype=np.float32):
                while self.server_running:
                    time.sleep(0.1)
                    
        except Exception as e:
            self.log(f"❌ SoundDevice capture failed: {e}")

    def _capture_soundcard(self):
        """Capture audio using SoundCard"""
        if not SOUNDCARD_AVAILABLE:
            return
        
        try:
            # Find a suitable microphone
            microphones = sc.all_microphones(include_loopback=True)
            if not microphones:
                self.log("❌ SoundCard: No microphones found for capture")
                return
            
            # Prefer loopback devices
            selected_mic = None
            for mic in microphones:
                if 'loopback' in mic.name.lower() or 'speakers' in mic.name.lower():
                    selected_mic = mic
                    break
            
            if not selected_mic:
                selected_mic = microphones[0]
            
            self.log(f"✅ SoundCard audio capture started: {selected_mic.name}")
            
            with selected_mic.recorder(samplerate=self.samplerate, channels=2) as recorder:
                while self.server_running:
                    try:
                        data = recorder.record(numframes=self.capture_blocksize)
                        
                        # Convert to mono
                        if data.ndim == 2:
                            mono = np.mean(data, axis=1)
                        else:
                            mono = data
                        
                        # Convert to int16
                        mono_int16 = (mono * 32767).astype(np.int16)
                        
                        # Resize to stream block size
                        if len(mono_int16) < self.stream_blocksize:
                            mono_int16 = np.resize(mono_int16, self.stream_blocksize)
                        elif len(mono_int16) > self.stream_blocksize:
                            mono_int16 = mono_int16[:self.stream_blocksize]
                        
                        # Add to buffer queue
                        try:
                            self.audio_buffer_queue.put(mono_int16, block=False)
                            self.real_audio_frames += 1
                        except queue.Full:
                            active_mode = getattr(self, "client_buffer_mode", "medium")
                            if active_mode == "high":
                                self.capture_queue_overruns += 1
                                self.log_rate_limited(
                                    "high_capture_queue_full_soundcard",
                                    f"⚠️ High SoundCard capture queue full ({self.capture_queue_overruns} events); preserving queued audio",
                                    10.0,
                                )
                            elif active_mode == "medium":
                                try:
                                    self.audio_buffer_queue.get_nowait()
                                    self.audio_buffer_queue.put_nowait(mono_int16)
                                    self.medium_capture_queue_drops += 1
                                    self.log_rate_limited(
                                        "medium_capture_queue_full_soundcard",
                                        f"⚖️ Medium SoundCard capture queue full; replaced oldest frame (events={self.medium_capture_queue_drops})",
                                        10.0,
                                    )
                                except queue.Empty:
                                    pass
                            elif active_mode == "low":
                                try:
                                    self.audio_buffer_queue.get_nowait()
                                    self.audio_buffer_queue.put_nowait(mono_int16)
                                    self.low_capture_queue_drops += 1
                                    self.log_rate_limited(
                                        "low_capture_queue_full_soundcard",
                                        f"🎯 Low SoundCard capture queue full; replaced oldest frame (events={self.low_capture_queue_drops})",
                                        5.0,
                                    )
                                except queue.Empty:
                                    pass
                            else:
                                self.buffer_underruns += 1
                            
                    except Exception as e:
                        self.log_rate_limited("soundcard_capture_error", f"⚠️ SoundCard capture error: {e}", 5.0)
                        break
                        
        except Exception as e:
            self.log(f"❌ SoundCard capture failed: {e}")

    def stop_server(self):
        """Stop the WiFi audio server cleanly and release all sockets."""
        if not self.server_running:
            # Still release partially-created sockets from a failed start.
            for attr in (
                "client_socket",
                "server_socket",
                "telemetry_socket",
                "discovery_socket",
                "discovery_socket_legacy",
            ):
                sock = getattr(self, attr, None)
                if sock is not None:
                    try:
                        sock.close()
                    except Exception:
                        pass
                    setattr(self, attr, None)
            return
        
        self.server_running = False
        self.discovery_running = False
        self.pc_state_stop_event.set()
        self.pc_state_thread = None
        
        # Close sockets
        try:
            # Restore default timer resolution
            import platform, ctypes
            if platform.system() == "Windows":
                ctypes.windll.winmm.timeEndPeriod(1)
    
            if self.client_socket and self.client_socket is not self.server_socket:
                self.client_socket.close()
            self.client_socket = None
                
            if self.server_socket:
                self.server_socket.close()
                self.server_socket = None
                
            if self.telemetry_socket:             # <-- NEW
                self.telemetry_socket.close()     # <-- NEW
                self.telemetry_socket = None
                
            if self.discovery_socket:
                self.discovery_socket.close()
                self.discovery_socket = None
                
            if self.discovery_socket_legacy:
                self.discovery_socket_legacy.close()
                self.discovery_socket_legacy = None
        except:
            pass
        
        # Clear audio buffer
        try:
            while not self.audio_buffer_queue.empty():
                self.audio_buffer_queue.get_nowait()
        except:
            pass
        
        # Update UI
        self.start_btn.setEnabled(True)
        self.stop_btn.setEnabled(False)
        self.force_disconnect_btn.setEnabled(False)
        
        self.log("🛑 Multi-Backend WiFi Audio Server stopped")

    def force_disconnect_client(self):
        """Force disconnect the current client"""
        if getattr(self, "client_address", None):
            client_label = self.client_info or "Unknown client"
            client_addr = getattr(self, 'client_address', None)
            
            try:
                if client_addr:
                    self.server_socket.sendto(b"SERVER_FORCE_DISCONNECT", client_addr)
            except Exception as e:
                self.log(f"⚠️ Failed to notify client about force disconnect: {e}")
            
            if client_addr:
                self.log(
                    f"⚡You ForceDisconnected client {client_label}  "
                    f"@ {client_addr[0]}:{client_addr[1]} from this server")
            else:
                self.log(f"⚡ ForceDisconnected client {client_label} from this server"
                )            
            
            # IMPORTANT: do NOT close server_socket here.
            # client_socket is an alias used for compatibility.
            
            self.client_socket = None
            self.client_address = None
            self.client_info = None
            self.client_connected_time = None
            self.client_last_seen = 0.0
            self._streaming_thread_spawned = False
            
            self.update_client_display()
            self.force_disconnect_btn.setEnabled(False)
            self._sync_qr_visibility()
            self.log("🔓 Server ready for new connections")
        else:
            self.log("⚠️ No client connected to disconnect")

def run_app():
    app = QApplication.instance() or QApplication(sys.argv)
    app.setStyleSheet(STYLE)
    server = WifiAudioServer()

     # Always launch the Qt6 server window maximized.  Do this after the
    # QMainWindow has been constructed so Windows cannot ignore the state
    # while the UI is still being created.  The second queued call also
    # protects against Windows restoring a normal geometry during startup.
    server.setWindowState(server.windowState() | Qt.WindowMaximized)
    server.show()
    QTimer.singleShot(0, server.showMaximized)
    QTimer.singleShot(150, server.showMaximized)
    
    server.run()
    app.exec()

if __name__ == "__main__":
    run_app()
