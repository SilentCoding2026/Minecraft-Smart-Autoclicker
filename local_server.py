import threading
import time
import random
import math
from http.server import BaseHTTPRequestHandler, HTTPServer
from pynput.mouse import Button, Controller
from pynput import keyboard

HOST = "127.0.0.1"
PORT = 4321

mouse = Controller()

# آیا الان تارگت روی پلیر هست؟
target_locked = False

# آیا از سمت پایتون اتوکلیکر مجاز به کار کردن هست؟
enabled = True

# برای جلوگیری از اسپم لاگ
last_state = None

stop_drag = False

def execute_single_drag():
    global stop_drag
    stop_drag = False # بازنشانی وضعیت در هر بار صدا زدن
    
    # تعداد کلیک‌ها در یک درگ (مثلا 15 تا 25 کلیک برای یک درگ کامل)
    num_clicks = random.randint(15, 25)
    print ('[AUTOCLICKER] Drag Started')
    for i in range(num_clicks):
        if stop_drag:
            print("[DRAG] Stopped mid-way")
            break
            
        # محاسبه نرخ کلیک بر اساس نمودار سهمی (ابتدا و انتها کند، وسط تند)
        # i/num_clicks ضریب پیشرفت است
        progress = i / num_clicks
        # فرمول شتاب: 1 منهای فاصله از مرکز (شکل گوسی/سهمی)
        speed_factor = 1.0 - (2 * abs(progress - 0.5))
        
        # زمان بین کلیک‌ها (هرچه speed_factor بیشتر باشد، زمان خواب کمتر = کلیک سریع‌تر)
        # بازه: 0.02 تا 0.05 ثانیه
        sleep_time = 0.05 - (speed_factor * 0.03)
        
        mouse.press(Button.left)
        time.sleep(random.uniform(0.005, 0.01)) # زمان نگه داشتن دکمه
        mouse.release(Button.left)
        
        time.sleep(sleep_time + random.uniform(-0.005, 0.005))

    print("[DRAG] Drag sequence finished")

def autoclicker_loop():
    global last_state
    ex_hits_for_single_drag = 2
    hits = 0

    while True:
        active = enabled and target_locked

        if active:
            if last_state != "clicking":
                print("[AUTOCLICKER] Clicking started")
                last_state = "clicking"

            mouse.click(Button.left)
            hits = hits + 1
            if( hits == ex_hits_for_single_drag):
                threading.Thread(target=execute_single_drag).start()
                hits = 0
                ex_hits_for_single_drag = random.randint(1, 4)

            # بازه نسبتاً طبیعی
            time.sleep(random.uniform(0.09, 0.145))
        else:
            if last_state != "idle":
                if not enabled:
                    print("[AUTOCLICKER] Disabled by keyboard toggle")
                    hits = 0
                elif not target_locked:
                    print("[AUTOCLICKER] Waiting for target")
                    hits = 0
                last_state = "idle"

            time.sleep(0.03)


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
        # لاگ پیش‌فرض HTTP را خاموش می‌کنیم که ترمینال شلوغ نشود
        return


def on_press(key):
    global enabled

    try:
        # کلید بک‌تیک: `
        if key.char == '`':
            enabled = not enabled
            if enabled:
                print("[HOTKEY] Autoclicker ENABLED")
            else:
                print("[HOTKEY] Autoclicker DISABLED")
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
    server.serve_forever()


if __name__ == "__main__":
    threading.Thread(target=autoclicker_loop, daemon=True).start()
    start_keyboard_listener()
    run_server()
