import threading
import time
import random
import math
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse
from pynput.mouse import Button, Controller as MouseController, Listener as MouseListener
from pynput.keyboard import Key, KeyCode, Controller as KeyboardController, Listener as KeyboardListener

# ===================== CONFIG FILE =====================
CONFIG_FILE = "smartclicks_config.json"

# ===================== MODE PROFILES =====================
PROFILES = {
    "bedwars": {
        "cps": 14.0,
        "burst_clicks": 3,
        "burst_cycle_ms": 5.0,
        "hold_min_ms": 1.0,
        "hold_max_ms": 2.5,
        "blockhit_enabled": True,
        "wtap_enabled": True,
        "wtap_release_ms": 30.0,
        "wtap_cooldown_ms": 120.0,
        "drag_clicks_min": 10,
        "drag_clicks_max": 16,
        "drag_gap_min": 0.008,
        "drag_gap_max": 0.018,
    },
    "pvp": {
        "cps": 22.0,
        "burst_clicks": 5,
        "burst_cycle_ms": 4.0,
        "hold_min_ms": 1.0,
        "hold_max_ms": 3.0,
        "blockhit_enabled": False,
        "wtap_enabled": True,
        "wtap_release_ms": 40.0,
        "wtap_cooldown_ms": 150.0,
        "drag_clicks_min": 12,
        "drag_clicks_max": 20,
        "drag_gap_min": 0.005,
        "drag_gap_max": 0.015,
    },
    "knockbackffa": {
        "cps": 18.0,
        "burst_clicks": 6,
        "burst_cycle_ms": 3.5,
        "hold_min_ms": 1.0,
        "hold_max_ms": 2.0,
        "blockhit_enabled": False,
        "wtap_enabled": True,
        "wtap_release_ms": 50.0,
        "wtap_cooldown_ms": 100.0,
        "drag_clicks_min": 15,
        "drag_clicks_max": 25,
        "drag_gap_min": 0.003,
        "drag_gap_max": 0.010,
    },
    "bloody": {   # Emulates Bloody "Hyper" + drag
        "cps": 25.0,
        "burst_clicks": 8,
        "burst_cycle_ms": 3.0,
        "hold_min_ms": 0.8,
        "hold_max_ms": 1.8,
        "blockhit_enabled": False,
        "wtap_enabled": False,
        "wtap_release_ms": 35.0,
        "wtap_cooldown_ms": 100.0,
        "drag_clicks_min": 20,
        "drag_clicks_max": 30,
        "drag_gap_min": 0.003,
        "drag_gap_max": 0.008,
    }
}

# ===================== GLOBAL STATE =====================
class State:
    def __init__(self):
        self.mode = "pvp"
        self.enabled = True
        self.target_locked = False
        self.blockhit_enabled = None   # will be set from profile
        self.wtap_enabled = None
        self.last_hit_time = 0.0
        self.manual_drag_active = False
        self.generating_event = False
        self.shutdown = False

        # Load config if exists
        self.load_config()

    def load_config(self):
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, 'r') as f:
                    data = json.load(f)
                    self.mode = data.get('mode', 'pvp')
            except:
                pass
        self.apply_mode(self.mode)

    def save_config(self):
        with open(CONFIG_FILE, 'w') as f:
            json.dump({'mode': self.mode}, f)

    def apply_mode(self, mode_name):
        if mode_name not in PROFILES:
            mode_name = "pvp"
        self.mode = mode_name
        profile = PROFILES[mode_name]
        self.cps = profile['cps']
        self.burst_clicks = profile['burst_clicks']
        self.burst_cycle_ms = profile['burst_cycle_ms']
        self.hold_min_ms = profile['hold_min_ms']
        self.hold_max_ms = profile['hold_max_ms']
        self.blockhit_enabled = profile['blockhit_enabled']
        self.wtap_enabled = profile['wtap_enabled']
        self.wtap_release_ms = profile['wtap_release_ms']
        self.wtap_cooldown_ms = profile['wtap_cooldown_ms']
        self.drag_clicks_min = profile['drag_clicks_min']
        self.drag_clicks_max = profile['drag_clicks_max']
        self.drag_gap_min = profile['drag_gap_min']
        self.drag_gap_max = profile['drag_gap_max']
        self.save_config()

state = State()

mouse = MouseController()
keyboard = KeyboardController()

# ===================== UTILITY =====================
def precise_sleep_until(deadline):
    while not state.shutdown:
        remaining = deadline - time.perf_counter()
        if remaining <= 0:
            break
        if remaining > 0.002:
            time.sleep(max(0, remaining - 0.001))
        elif remaining > 0.0003:
            time.sleep(0)
        else:
            while time.perf_counter() < deadline and not state.shutdown:
                pass
            break

# ===================== CLICK ACTIONS =====================
def do_left_click():
    state.generating_event = True
    try:
        hold = random.uniform(state.hold_min_ms, state.hold_max_ms) / 1000.0
        mouse.press(Button.left)
        precise_sleep_until(time.perf_counter() + hold)
        mouse.release(Button.left)
    finally:
        state.generating_event = False

def do_block_hit():
    mouse.press(Button.right)
    precise_sleep_until(time.perf_counter() + state.hold_min_ms / 1000.0)  # use hold time
    mouse.release(Button.right)

def do_wtap():
    keyboard.release('w')
    precise_sleep_until(time.perf_counter() + state.wtap_release_ms / 1000.0)
    keyboard.press('w')

# ===================== MANUAL DRAG =====================
def execute_single_drag():
    if state.manual_drag_active:
        return
    state.manual_drag_active = True
    try:
        num_clicks = random.randint(state.drag_clicks_min, state.drag_clicks_max)
        for _ in range(num_clicks):
            if state.shutdown or not state.enabled:
                break
            do_left_click()
            gap = random.uniform(state.drag_gap_min, state.drag_gap_max)
            precise_sleep_until(time.perf_counter() + gap)
    finally:
        state.manual_drag_active = False

# ===================== BACKGROUND LOOPS =====================
def autoclicker_loop():
    burst_remaining = 0
    while not state.shutdown:
        if state.manual_drag_active or not state.enabled or not state.target_locked:
            time.sleep(0.005)
            continue

        if burst_remaining > 0:
            cycle_s = state.burst_cycle_ms / 1000.0
            burst_remaining -= 1
        else:
            cycle_s = 1.0 / state.cps
            burst_remaining = state.burst_clicks - 1

        do_left_click()

        now = time.perf_counter()
        raw_deadline = now + cycle_s
        # tick‑sync (20Hz)
        next_tick = math.ceil(now * 20) / 20.0 + 0.001
        deadline = max(raw_deadline, next_tick)
        precise_sleep_until(deadline)

def blockhit_loop():
    while not state.shutdown:
        if state.blockhit_enabled and state.enabled and state.target_locked and not state.manual_drag_active:
            do_block_hit()
            time.sleep(0.1)  # block interval
        else:
            time.sleep(0.05)

def wtap_trigger_loop():
    # This loop is just a cooldown manager; actual wtap is triggered by hit event
    while not state.shutdown:
        time.sleep(0.05)

# ===================== HOTKEYS =====================
def on_key_press(key):
    if state.shutdown:
        return
    # Toggle main with backtick
    if hasattr(key, 'char') and key.char == '`':
        state.enabled = not state.enabled
        print(f"[HOTKEY] Autoclicker {'ENABLED' if state.enabled else 'DISABLED'}")
    # Feature toggles via number keys
    if hasattr(key, 'char'):
        if key.char == '1':
            state.blockhit_enabled = not state.blockhit_enabled
            print(f"[HOTKEY] Block‑hit {'ON' if state.blockhit_enabled else 'OFF'}")
        elif key.char == '2':
            state.wtap_enabled = not state.wtap_enabled
            print(f"[HOTKEY] W‑tap {'ON' if state.wtap_enabled else 'OFF'}")

def on_mouse_click(x, y, button, pressed):
    if state.shutdown:
        return
    if button == Button.left and pressed:
        if not state.enabled:
            return
        if state.generating_event:
            return
        threading.Thread(target=execute_single_drag, daemon=True).start()

# ===================== HTTP SERVER =====================
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)

        if path == "/target_locked":
            state.target_locked = True
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"locked")
        elif path == "/target_unlocked":
            state.target_locked = False
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"unlocked")
        elif path == "/hit":
            # Trigger W‑tap if enabled and cooldown passed
            if state.wtap_enabled and state.enabled and state.target_locked:
                now = time.perf_counter()
                if now - state.last_hit_time >= state.wtap_cooldown_ms / 1000.0:
                    state.last_hit_time = now
                    threading.Thread(target=do_wtap, daemon=True).start()
            self.send_response(200)
            self.end_headers()
        elif path == "/set_mode":
            mode = query.get('mode', ['pvp'])[0]
            if mode in PROFILES:
                state.apply_mode(mode)
                print(f"[MODE] Switched to {mode}")
            self.send_response(200)
            self.end_headers()
        elif path == "/toggle":
            feature = query.get('feature', [''])[0]
            if feature == 'enabled':
                state.enabled = not state.enabled
            elif feature == 'blockhit':
                state.blockhit_enabled = not state.blockhit_enabled
            elif feature == 'wtap':
                state.wtap_enabled = not state.wtap_enabled
            self.send_response(200)
            self.end_headers()
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        pass

# ===================== STARTUP =====================
if __name__ == "__main__":
    print("========================================")
    print("  SmartClicks – Enhanced PvP Assistant")
    print("========================================")
    print("  `              : Toggle main")
    print("  1              : Toggle block‑hit")
    print("  2              : Toggle W‑tap")
    print("  Left Mouse     : Drag burst")
    print("  Modes: bedwars, pvp, knockbackffa, bloody")
    print(f"  HTTP: http://127.0.0.1:{4321}")
    print("    /set_mode?mode=xxx")
    print("    /toggle?feature=enabled|blockhit|wtap")
    print("    /hit   (called by mod on attack)")
    print("========================================")

    # Start background threads
    threading.Thread(target=autoclicker_loop, daemon=True).start()
    threading.Thread(target=blockhit_loop, daemon=True).start()
    threading.Thread(target=wtap_trigger_loop, daemon=True).start()

    # Input listeners
    KeyboardListener(on_press=on_key_press, daemon=True).start()
    MouseListener(on_click=on_mouse_click, daemon=True).start()

    # HTTP server
    server = HTTPServer(("127.0.0.1", 4321), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[EXIT] Shutting down...")
        state.shutdown = True
        server.shutdown()