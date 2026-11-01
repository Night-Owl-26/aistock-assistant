#!/usr/bin/env python3
"""Release-build check on an emulator: the minified, non-debuggable APK starts, sets up, reaches sign-in,
refuses a plain-http server, and Android itself reports it as not debuggable.
Usage: python3 tools/release_smoke.py path/to/app-release.apk [output-dir]"""
import os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

PKG = "com.aistock.assistant"
APK = sys.argv[1]
OUT = sys.argv[2] if len(sys.argv) > 2 else "device-out/release"
os.makedirs(OUT, exist_ok=True)
log = open(os.path.join(OUT, "release-smoke.log"), "w")
problems = []
n_shot = [0]

def say(*a):
    line = " ".join(str(x) for x in a); print(line, flush=True); log.write(line + "\n"); log.flush()

def adb(*args, timeout=90, binary=False):
    r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    return r.stdout if binary else (r.stdout + r.stderr).decode("utf-8", "replace")

def nodes():
    for _ in range(4):
        adb("shell", "uiautomator", "dump", "/sdcard/u.xml")
        raw = adb("exec-out", "cat", "/sdcard/u.xml", binary=True)
        try: root = ET.fromstring(raw.decode("utf-8", "replace")); break
        except Exception: time.sleep(1)
    else: return []
    out = []
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            out.append({"text": n.get("text", ""), "desc": n.get("content-desc", ""), "cls": n.get("class", ""), "x": (x1 + x2) // 2, "y": (y1 + y2) // 2})
    return out

def find(label, exact=False, last=False):
    hits = [n for n in nodes() if any((v == label) if exact else (label.lower() in v.lower()) for v in (n["text"], n["desc"]))]
    return (hits[-1] if last else hits[0]) if hits else None

def wait_for(label, secs=20, exact=False):
    end = time.time() + secs
    while time.time() < end:
        n = find(label, exact)
        if n: return n
        time.sleep(0.8)
    return None

def tap(label, secs=15, exact=False, last=False):
    end = time.time() + secs
    while time.time() < end:
        n = find(label, exact, last)
        if n:
            adb("shell", "input", "tap", str(n["x"]), str(n["y"])); time.sleep(1.2); return True
        time.sleep(0.8)
    problems.append(f"could not find '{label}'"); say("  !! missing", label); shot("missing"); return False

def expect(label, what, secs=20):
    if not wait_for(label, secs): problems.append(f"{what}: expected '{label}'"); say("  !! expected", label); return False
    return True

def type_text(t):
    for i, w in enumerate(t.split(" ")):
        if w: adb("shell", "input", "text", w)
        if i < len(t.split(" ")) - 1: adb("shell", "input", "keyevent", "62")
        time.sleep(0.3)

def hide_keyboard():
    if "mInputShown=true" in adb("shell", "dumpsys", "input_method"): adb("shell", "input", "keyevent", "4"); time.sleep(0.8)

def field(label, text, exact=True, clear=0):
    n = wait_for(label, 10, exact)
    if not n:
        edits = [x for x in nodes() if "EditText" in x["cls"]]
        problems.append(f"field '{label}' missing"); return
    adb("shell", "input", "tap", str(n["x"]), str(n["y"])); time.sleep(0.8)
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(clear): adb("shell", "input", "keyevent", "KEYCODE_DEL")
    type_text(text); hide_keyboard()

def shot(name):
    n_shot[0] += 1
    open(os.path.join(OUT, f"{n_shot[0]:02d}-{name}.png"), "wb").write(adb("exec-out", "screencap", "-p", binary=True))

say("uninstall test build:", adb("uninstall", PKG).strip())
say("install release:", adb("install", "-r", APK, timeout=240).strip())
adb("logcat", "-c")
# Android itself must refuse to treat the release app as debuggable.
runas = adb("shell", "run-as", PKG, "ls")
say("run-as:", runas.strip()[:120])
if "not debuggable" not in runas: problems.append("release app is debuggable (run-as worked)")
flags = adb("shell", "dumpsys", "package", PKG)
if re.search(r"flags=\[[^\]]*DEBUGGABLE", flags): problems.append("package flags show DEBUGGABLE")

adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
expect("Welcome to Follower", "first launch", 60); shot("welcome")
for _ in range(3): tap("Next", exact=True)
expect("Business Setup", "setup")
field("Business name", "Release Test Shop", exact=False)
adb("shell", "input", "swipe", "500", "1500", "500", "600", "300"); time.sleep(1)
tap("Load sample data"); tap("Get started", exact=True)
expect("Create your Follower account", "sign-in gate", 40); shot("gate")
if find("Change server"): problems.append("server option visible without the long press")
tap("Sign in", exact=True); expect("Forgot password?", "forgot password link"); tap("Forgot password?")
expect("Reset your password", "forgot password screen"); shot("forgot"); tap("Back to sign in")
tap("Create account", exact=True)
# Plain http must be refused by the release build, whatever address is typed.
n = find("Create your Follower account")
if n: adb("shell", "input", "swipe", str(n["x"]), str(n["y"]), str(n["x"]), str(n["y"]), "1500"); time.sleep(1.2)
field("Email", "owner@shop.in")
field("Password (8+ characters)", "shopowner1")
tap("Change server")
field("Server address", "http://10.0.2.2:8080", clear=45)
tap("Create account", exact=True, last=True)
expect("must start with https", "release refuses a plain-http server", 20); shot("http-refused")

time.sleep(2)
logcat = adb("logcat", "-d", "-v", "brief", timeout=120)
open(os.path.join(OUT, "logcat.txt"), "w").write("\n".join(l for l in logcat.splitlines() if PKG in l or "AndroidRuntime" in l or "FATAL" in l)[-200000:])
if any("Process: " + PKG in l for l in logcat.splitlines()): problems.append("crash in logcat")
if not adb("shell", "pidof", PKG).strip(): problems.append("app not running at the end")
say("problems:", problems or "none")
open(os.path.join(OUT, "result.txt"), "w").write("PASS\n" if not problems else "FAIL\n" + "\n".join(problems) + "\n")
