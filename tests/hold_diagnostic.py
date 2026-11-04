"""仅用于已授权的 15 秒实机诊断；不用于正式宏执行。"""
import json
import os
import queue
import socket
import subprocess
import threading
import time
import winsound

ADB = os.path.join(os.environ['TEMP'], 'turbo-adb-tools', 'platform-tools', 'adb.exe')


def state():
    with socket.create_connection(('127.0.0.1', 18764), timeout=0.3) as s:
        s.settimeout(0.3)
        with s.makefile('rb') as stream:
            return json.loads(stream.readline(8192))


baseline = state()
print('BASELINE', json.dumps(baseline), flush=True)
if baseline['held']:
    raise SystemExit('请先松开按住区，再重新测试。')
p = subprocess.Popen([ADB, 'shell', '-T', 'timeout', '-k', '1s', '17s',
                      '/data/local/tmp/touchmux-diag-742b03f', '--diagnostic-seconds', '15'],
                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                     text=True, bufsize=1)
messages = queue.Queue()

def collect():
    for line in p.stdout:
        messages.put(line.strip())

threading.Thread(target=collect, daemon=True).start()

def send(command):
    p.stdin.write(command + '\n')
    p.stdin.flush()

start = time.monotonic()
merged = False
last_ping = last_tap = 0.0
last_uptime = None
fresh_at = start
sent = 0
last_hold = None
try:
    send('ENGAGE 1220 2712')
    while p.poll() is None and time.monotonic() - start < 16:
        now = time.monotonic()
        while not messages.empty():
            line = messages.get_nowait()
            print('MUX', line, flush=True)
            if line.startswith('ERR'):
                raise RuntimeError(line)
            if line.startswith('OK MERGING '):
                merged = True
                winsound.Beep(1000, 120)
                winsound.Beep(1400, 120)
        if now - last_ping > 0.4:
            send('PING')
            last_ping = now
        if merged:
            current = state()
            if current['uptimeMs'] != last_uptime:
                last_uptime = current['uptimeMs']
                fresh_at = now
            if now - fresh_at > 0.3:
                raise RuntimeError('状态未刷新，停止诊断')
            if current['held'] != last_hold:
                print('HOLD', json.dumps(current), flush=True)
                last_hold = current['held']
            # 不预排队：每次点击前重新读取按住状态；每次触点最多保持 20ms。
            if current['held'] and now - last_tap >= 0.5:
                send(f"TAP {int(current['targetX'])} {int(current['targetY'])} 20")
                sent += 1
                last_tap = now
        threading.Event().wait(0.025)
finally:
    if p.poll() is None:
        try:
            send('QUIT')
            p.stdin.close()
        except (BrokenPipeError, OSError):
            pass
        try:
            p.wait(timeout=5)
        except subprocess.TimeoutExpired:
            p.kill()
            p.wait()
    print('SENT', sent, 'EXIT', p.returncode, flush=True)
    while not messages.empty():
        print('MUX', messages.get_nowait(), flush=True)
    print('FINAL', json.dumps(state()), flush=True)
    winsound.Beep(600, 300)
