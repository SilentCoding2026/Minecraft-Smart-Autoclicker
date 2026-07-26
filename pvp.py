import threading
import time
import random
import math
from http.server import BaseHTTPRequestHandler, HTTPServer
from pynput.mouse import Button, Controller as MouseController, Listener as MouseListener
from pynput.keyboard import Key, KeyCode, Controller as KeyboardController, Listener as KeyboardListener

# ===================== CONFIGURATION =====================
HOST = "127.0.0.1"
PORT = 4321

# Main clicker settings
TARGET_CPS = 22.0            # Sustained clicks per second
BURST_CLICKS = 4             # Clicks per mini‑burst
BURST_CYCLE_MS = 6.0         # Time between burst clicks (ms)
HOLD_MIN_MS = 1.0            # Min click hold time (ms)
HOLD_MAX_MS = 3.0            # Max click hold time (ms)

# Block‑hit settings (auto right‑click)
BLOCK_HOLD_MS = 20.0         # How long right button is held (ms)
BLOCK_INTERVAL_MS = 100.0    # Minimum gap between blocks (ms)

# W‑tap settings (sprint reset)
WTAP_RELEASE_MS = 40.0       # How long 'w' is released (ms)
WTAP_COOLDOWN_MS = 150.0     # Minimum gap between W‑taps (ms)

# Manual drag burst (when you left‑click)
DRAG_CLICKS_MIN = 12
DRAG_CLICKS_MAX = 20
DRAG_GAP_MIN = 0.005
DRAG_GAP_MAX = 0.015

# Enable tick‑sync (aligns clicks to 20Hz server ticks for better hitreg)
USE_TICK_SYNC = True

# ===================== GLOBAL STATE =====================
mouse = MouseController()
keyboard = KeyboardController()

target_locked = False
enabled = True
blockhit_enabled = False
wtap_enabled = False

# Thread‑safe flags & events
generating_event = False
generating_lock = threading.Lock()
manual_drag_active = False
drag_lock = threading.Lock()
stop_drag = False
shutdown_event = threading.Event()

# ===================== PRECISE SLEEP =====================
def precise_sleep_until(deadline):
    """Hybrid sleep that keeps CPU usage low while maintaining accuracy."""
    while not shutdown_event.is_set():
        remaining = deadline - time.perf_counter()
        if remaining <= 0:
            break
        if remaining > 0.002:
            time.sleep(max(0, remaining - 0.001))
        elif remaining > 0.0003:
            time.sleep(0)          # Yield GIL
        else:
            # Micro‑spin for the last few microseconds
            while time.perf_counter() < deadline and not shutdown_event.is_set():
                pass
            break

# ===================== COMBAT ACTIONS =====================
def do_left_click():
    global generating_event
    hold = random.uniform(HOLD_MIN_MS, HOLD_MAX_MS) / 1000.0
    with generating_lock:
        generating_event = True
    try:
        mouse.press(Button.left)
        precise_sleep_until(time.perf_counter() + hold)
        mouse.release(Button.left)
    finally:
        with generating_lock:
            generating_event = False

def do_block_hit():
    """Quick right‑click (called from its own thread)."""
    mouse.press(Button.right)
    precise_sleep_until(time.perf_counter() + BLOCK_HOLD_MS / 1000.0)
    mouse.release(Button.right)

def do_wtap():
    """Briefly release 'w' to reset sprint."""
    keyboard.release('w')
    precise_sleep_until(time.perf_counter() + WTAP_RELEASE_MS / 1000.0)
    keyboard.press('w')

# ===================== MANUAL DRAG BURST =====================
def execute_single_drag():
    global stop_drag, manual_drag_active
    if not drag_lock.acquire(blocking=False):
        return          # Another drag already running
    try:
        manual_drag_active = True
        stop_drag = False
        num_clicks = random.randint(DRAG_CLICKS_MIN, DRAG_CLICKS_MAX)
        for _ in range(num_clicks):
            if stop_drag or shutdown_event.is_set():
                break
            do_left_click()
            gap = random.uniform(DRAG_GAP_MIN, DRAG_GAP_MAX)
            precise_sleep_until(time.perf_counter() + gap)
    finally:
        manual_drag_active = False
        drag_lock.release()

# ===================== BACKGROUND LOOPS =====================
def blockhit_loop():
    while not shutdown_event.is_set():
        if (blockhit_enabled and target_locked and enabled
                and not manual_drag_active):
            do_block_hit()
            precise_sleep_until(time.perf_counter() + BLOCK_INTERVAL_MS / 1000.0)
        else:
            shutdown_event.wait(0.05)

def wtap_loop():
    while not shutdown_event.is_set():
        if (wtap_enabled and target_locked and enabled
                and not manual_drag_active):
            do_wtap()
            precise_sleep_until(time.perf_counter() + WTAP_COOLDOWN_MS / 1000.0)
        else:
            shutdown_event.wait(0.05)

def autoclicker_loop():
    burst_remaining = 0
    last_state = "idle"
    while not shutdown_event.is_set():
        # Pause if a manual drag is happening
        if manual_drag_active:
            shutdown_event.wait(0.005)
            continue

        active = enabled and target_locked

        # State transition
        if active and last_state != "clicking":
            burst_remaining = BURST_CLICKS
            last_state = "clicking"
        elif not active and last_state != "idle":
            last_state = "idle"
            burst_remaining = 0

        if not active:
            shutdown_event.wait(0.02)
            continue

        # Determine inter‑click interval
        if burst_remaining > 0:
            cycle_s = BURST_CYCLE_MS / 1000.0
            burst_remaining -= 1
        else:
            cycle_s = 1.0 / TARGET_CPS

        do_left_click()

        # Align to server ticks if enabled (improves hit registration)
        now = time.perf_counter()
        raw_deadline = now + cycle_s
        if USE_TICK_SYNC:
            next_tick = math.ceil(now * 20) / 20.0 + 0.001
            deadline = max(raw_deadline, next_tick)
        else:
            deadline = raw_deadline

        precise_sleep_until(deadline)

# ===================== HOTKEYS (FIXED BACKTICK) =====================
def on_key_press(key):
    global enabled, blockhit_enabled, wtap_enabled

    # Robust backtick detection: try char first, then virtual key code (US layout)
    toggle_main = False
    if hasattr(key, 'char') and key.char == '`':
        toggle_main = True
    elif hasattr(key, 'vk') and key.vk == 192:   # 192 = OEM_3 (backtick/tilde)
        toggle_main = True
    elif key == Key.f1:                          # Fallback toggle with F1
        toggle_main = True

    if toggle_main:
        enabled = not enabled
        print(f"[HOTKEY] Autoclicker {'ENABLED' if enabled else 'DISABLED'}")

    try:
        if hasattr(key, 'char') and key.char == '1':
            blockhit_enabled = not blockhit_enabled
            print(f"[HOTKEY] Block‑hit {'ON' if blockhit_enabled else 'OFF'}")
        elif hasattr(key, 'char') and key.char == '2':
            wtap_enabled = not wtap_enabled
            print(f"[HOTKEY] W‑tap {'ON' if wtap_enabled else 'OFF'}")
    except AttributeError:
        pass

# ===================== MOUSE LISTENER =====================
def on_mouse_click(x, y, button, pressed):
    if button == Button.left and pressed:
        if not enabled:                      # اگر اتوکلیکر خاموش است، درگ هم انجام نشود
            return
        with generating_lock:
            if generating_event:
                return
        threading.Thread(target=execute_single_drag, daemon=True).start()
# ===================== HTTP SERVER =====================
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        global target_locked
        if self.path == "/target_locked":
            target_locked = True
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"locked")
        elif self.path == "/target_unlocked":
            target_locked = False
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"unlocked")
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        return   # Suppress console spam

# ===================== STARTUP =====================
if __name__ == "__main__":
    print("========================================")
    print("  PvP Autoclicker – Optimized")
    print("========================================")
    print("  `  (or F1)   : Toggle main autoclicker")
    print("  1            : Toggle auto block‑hit")
    print("  2            : Toggle W‑tap sprint reset")
    print("  Left Mouse   : Manual drag burst")
    print(f"  HTTP Server  : http://{HOST}:{PORT}")
    print("    /target_locked   -> Enable combat")
    print("    /target_unlocked -> Disable combat")
    print("========================================")

    # Start background threads
    threading.Thread(target=autoclicker_loop, daemon=True).start()
    threading.Thread(target=blockhit_loop, daemon=True).start()
    threading.Thread(target=wtap_loop, daemon=True).start()

    # Start input listeners
    KeyboardListener(on_press=on_key_press, daemon=True).start()
    MouseListener(on_click=on_mouse_click, daemon=True).start()

    # Start HTTP server (blocking)
    server = HTTPServer((HOST, PORT), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[EXIT] Shutting down...")
        shutdown_event.set()
        server.shutdown()