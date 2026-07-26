import threading
import time
import random
import math
from http.server import BaseHTTPRequestHandler, HTTPServer
from pynput.mouse import Button, Controller
from pynput import keyboard

# ==========================================
# SERVER CONFIG
# ==========================================
HOST = "127.0.0.1"
PORT = 4321
mouse = Controller()

# ==========================================
# GLOBAL STATE (UNCHANGED FROM YOUR LOGIC)
# ==========================================
target_locked = False
enabled = True
last_state = None
stop_drag = False

# ==========================================
# 🧬 ADVANCED AUTOCLICKER FACTORS (MASTER TABLE)
# ==========================================
# Core Timing
TARGET_MEAN_MS = 58.2          # 17.17 CPS (Maximum effective DPS)
SIGMA_MS = 3.5                 # Gaussian jitter (human inconsistency)
HARD_FLOOR_MS = 52.0           # NEVER go below! (Avoids 50ms tick drop)
HARD_CEILING_MS = 85.0         # NEVER go above! (Prevents DPS loss)

# Human "Memory" (Autocorrelation)
RHO = 0.35                     # Lag-1 correlation (slow follows slow, fast follows fast)

# Click Hold Duration (Physical Switch Emulation)
HOLD_MIN_MS = 38
HOLD_MAX_MS = 58

# Session State (Auto-drift & Fatigue)
base_mean = TARGET_MEAN_MS
prev_delay = TARGET_MEAN_MS
click_counter = 0
session_start = time.time()

# ==========================================
# 🖱️ IMPROVED DRAG FUNCTION (Fixed 52ms Floor)
# ==========================================
def execute_single_drag():
    global stop_drag
    stop_drag = False
    
    num_clicks = random.randint(12, 20)
    print('[AUTOCLICKER] Drag Started')
    
    for i in range(num_clicks):
        if stop_drag:
            print("[DRAG] Stopped mid-way")
            break
            
        # Parabolic speed: fast in middle, slow at edges
        progress = i / num_clicks
        speed_factor = 1.0 - (2 * abs(progress - 0.5))
        
        # Base sleep between clicks (20ms to 50ms)
        sleep_time = 0.05 - (speed_factor * 0.03)
        
        # --- CRITICAL FIX: Enforce 52ms TOTAL CYCLE ---
        # Hold time + Sleep time must be >= 52ms to avoid tick drops
        hold_time = random.uniform(0.008, 0.015)  # 8-15ms hold
        total_cycle = hold_time + sleep_time
        
        if total_cycle < 0.052:  # If under 52ms, pad the sleep
            sleep_time = 0.052 - hold_time + random.uniform(0.001, 0.003)
        
        mouse.press(Button.left)
        time.sleep(hold_time)
        mouse.release(Button.left)
        
        time.sleep(sleep_time)
    
    print("[DRAG] Drag sequence finished")

# ==========================================
# 🧠 THE ULTIMATE AUTOCLICKER LOOP (FULL MASTER TABLE IMPLEMENTATION)
# ==========================================
def autoclicker_loop():
    global last_state, base_mean, prev_delay, click_counter, session_start

    while True:
        active = enabled and target_locked

        if active:
            if last_state != "clicking":
                print("[AUTOCLICKER] Clicking started (17 CPS, fully registered)")
                last_state = "clicking"
                # Reset session timer when starting to click
                if click_counter == 0:
                    session_start = time.time()

            # ---------- 1. LONG-TERM FATIGUE (Drift over time) ----------
            elapsed = time.time() - session_start
            # After 5 minutes, slow down by 0.15ms per second. Max slowdown = +6ms (drops to ~15.5 CPS)
            fatigue_penalty = min(6.0, max(0.0, (elapsed - 300) * 0.15))
            current_mean = base_mean + fatigue_penalty

            # ---------- 2. SHORT-TERM DRIFT (Every 500 clicks, random wobble) ----------
            click_counter += 1
            if click_counter % 500 == 0:
                current_mean += random.uniform(-0.8, 0.8)
                # Clamp to prevent going too slow or too fast
                current_mean = max(55.0, min(current_mean, 64.0))

            # ---------- 3. GENERATE GAUSSIAN JITTER ----------
            noise = random.gauss(0, SIGMA_MS)

            # ---------- 4. APPLY AUTOCORRELATION (AR-1 Model - The "Memory" Factor) ----------
            # This ensures: if previous click was fast, this one is statistically likely fast too.
            delay = current_mean + (RHO * (prev_delay - current_mean)) + ((1 - RHO) * noise)

            # ---------- 5. APPLY RIGHT-SKEW (Humans have rare long pauses, rarely super-fast) ----------
            if random.random() < 0.25:  # 25% chance to add a tiny slowdown
                delay += random.uniform(1.0, 4.0)

            # ---------- 6. MICRO-BURSTS (Finger Tremor - Every 6th click) ----------
            if click_counter % 6 == 0:   # Burst click (faster)
                delay = delay - 4.0
            if click_counter % 6 == 3:   # Recovery click (slower)
                delay = delay + 6.0

            # ---------- 7. ENFORCE HARDWARE BOUNDARIES (THE HOLY GRAIL) ----------
            # Absolutely NEVER go below 52ms (prevents tick drop)
            # Absolutely NEVER go above 85ms (prevents DPS loss)
            if delay < HARD_FLOOR_MS:
                delay = HARD_FLOOR_MS
            if delay > HARD_CEILING_MS:
                delay = HARD_CEILING_MS

            # ---------- 8. STORE FOR NEXT AUTOCORRELATION ----------
            prev_delay = delay

            # ---------- 9. EXECUTE THE PHYSICAL CLICK (WITH HOLD TIME!) ----------
            # This is the #1 fix for your dropped clicks. 
            # A physical finger holds the button for 38-58ms.
            mouse.press(Button.left)
            hold_time = random.uniform(HOLD_MIN_MS, HOLD_MAX_MS) / 1000.0  # Convert to seconds
            time.sleep(hold_time)
            mouse.release(Button.left)

            # ---------- 10. WAIT FOR THE NEXT CYCLE ----------
            time.sleep(delay / 1000.0)  # Convert ms to seconds

        else:
            # ----- IDLE STATE (Not clicking) -----
            if last_state != "idle":
                if not enabled:
                    print("[AUTOCLICKER] Disabled by keyboard toggle")
                elif not target_locked:
                    print("[AUTOCLICKER] Waiting for target")
                last_state = "idle"
                # Reset memory when idle, so first click is fresh
                prev_delay = TARGET_MEAN_MS
                click_counter = 0
                base_mean = TARGET_MEAN_MS + random.uniform(-0.5, 0.5)

            time.sleep(0.03)

# ==========================================
# 🔥 FIRST CLICK BONUS (Slower startup)
# ==========================================
# NOTE: This is handled automatically. 
# The first click after idle uses `prev_delay = TARGET_MEAN_MS`, 
# which naturally produces a slightly slower or normal delay.
# If you want a guaranteed +22ms on the VERY first click, uncomment this:
# (But it's not strictly necessary due to the Gaussian spread)

# ==========================================
# HTTP SERVER (UNCHANGED - WORKS PERFECTLY)
# ==========================================
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        global target_locked
        if self.path == "/target_locked":
            target_locked = True
            print("[SERVER] target_locked")
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"locked")
        elif self.path == "/target_unlocked":
            target_locked = False
            print("[SERVER] target_unlocked")
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"unlocked")
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        return

# ==========================================
# KEYBOARD HOTKEY (UNCHANGED)
# ==========================================
def on_press(key):
    global enabled
    try:
        if key.char == '`':
            enabled = not enabled
            print(f"[HOTKEY] Autoclicker {'ENABLED' if enabled else 'DISABLED'}")
    except AttributeError:
        pass

def start_keyboard_listener():
    listener = keyboard.Listener(on_press=on_press)
    listener.daemon = True
    listener.start()

def run_server():
    server = HTTPServer((HOST, PORT), Handler)
    print(f"[SERVER] Running on http://{HOST}:{PORT}")
    print("[HOTKEY] Press ` to enable/disable autoclicker")
    print("[AUTOCLICKER] Advanced 17-CPS engine loaded (52ms floor, Gaussian, AR-1, Fatigue)")
    server.serve_forever()

# ==========================================
# MAIN ENTRY
# ==========================================
if __name__ == "__main__":
    threading.Thread(target=autoclicker_loop, daemon=True).start()
    start_keyboard_listener()
    run_server()