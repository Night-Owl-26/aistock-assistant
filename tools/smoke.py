#!/usr/bin/env python3
"""Device smoke test. Drives the installed app on an emulator with adb, saves screenshots, and fails on any crash.
Usage: python3 tools/smoke.py path/to/app-debug.apk [output-dir]"""
import os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

PKG = "com.aistock.assistant"
APK = sys.argv[1]
OUT = sys.argv[2] if len(sys.argv) > 2 else "device-out"
os.makedirs(OUT, exist_ok=True)
log = open(os.path.join(OUT, "smoke.log"), "w")
problems = []
step_no = [0]

def say(*a):
    line = " ".join(str(x) for x in a)
    print(line, flush=True); log.write(line + "\n"); log.flush()

def adb(*args, timeout=60, binary=False):
    r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")

def dump():
    for _ in range(4):
        adb("shell", "uiautomator", "dump", "/sdcard/u.xml")
        raw = adb("exec-out", "cat", "/sdcard/u.xml", binary=True)
        try:
            return ET.fromstring(raw.decode("utf-8", "replace"))
        except Exception:
            time.sleep(1)
    return ET.fromstring("<hierarchy/>")

def nodes():
    out = []
    for n in dump().iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if not m: continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append({"text": n.get("text", ""), "desc": n.get("content-desc", ""), "cls": n.get("class", ""),
                    "x": (x1 + x2) // 2, "y": (y1 + y2) // 2, "w": x2 - x1, "h": y2 - y1, "pkg": n.get("package", "")})
    return out

def find(label, exact=False, last=False):
    hits = []
    all_nodes = nodes()
    # A slow emulator sometimes shows "System UI isn't responding": press Wait and look again.
    if any("isn't responding" in n["text"] for n in all_nodes):
        for n in all_nodes:
            if n["text"] == "Wait":
                adb("shell", "input", "tap", str(n["x"]), str(n["y"])); time.sleep(3)
        all_nodes = nodes()
    for n in all_nodes:
        for v in (n["text"], n["desc"]):
            if (v == label) if exact else (label.lower() in v.lower()):
                hits.append(n); break
    if not hits: return None
    return hits[-1] if last else hits[0]

def wait_for(label, secs=20, exact=False):
    end = time.time() + secs
    while time.time() < end:
        n = find(label, exact)
        if n: return n
        time.sleep(0.7)
    return None

def tap(label, secs=20, exact=False, last=False, required=True):
    end = time.time() + secs
    n = None
    while time.time() < end:
        n = find(label, exact, last)
        if n: break
        time.sleep(0.7)
    if not n and required:
        # The item may be further down a long page: scroll and look again.
        for _ in range(5):
            adb("shell", "input", "swipe", "540", "1500", "540", "800", "400"); time.sleep(1.0)
            n = find(label, exact, last)
            if n: break
        if not n:
            for _ in range(9):
                adb("shell", "input", "swipe", "540", "800", "540", "1500", "400"); time.sleep(1.0)
                n = find(label, exact, last)
                if n: break
    if not n:
        if required:
            problems.append(f"could not find '{label}'"); say("  !! missing:", label); shot("missing-" + re.sub(r"\W+", "_", label)[:30])
            open(os.path.join(OUT, f"{step_no[0]:02d}-dump.xml"), "wb").write(adb("exec-out", "cat", "/sdcard/u.xml", binary=True))
        return False
    adb("shell", "input", "tap", str(n["x"]), str(n["y"]))
    time.sleep(1.0)
    return True

def scroll_to(label, tries=8):
    for _ in range(tries):
        n = find(label)
        # Something found right at the bottom edge would be tapped through the navigation bar: bring it up first.
        if n and n["y"] > 1950:
            adb("shell", "input", "swipe", "540", "1500", "540", "900", "400"); time.sleep(1.2)
            n = find(label)
        if n: return True
        adb("shell", "input", "swipe", "540", "1700", "540", "700", "400"); time.sleep(1.2)
    return False

def tap_field(label, index=0, secs=8):
    """Taps a text field by its label or placeholder; falls back to the n-th edit box on screen."""
    end = time.time() + secs
    while time.time() < end:
        n = find(label)
        if n:
            adb("shell", "input", "tap", str(n["x"]), str(n["y"])); time.sleep(1.0); return True
        time.sleep(0.7)
    edits = [n for n in nodes() if "EditText" in n["cls"]]
    if len(edits) > index:
        adb("shell", "input", "tap", str(edits[index]["x"]), str(edits[index]["y"])); time.sleep(1.0); return True
    problems.append(f"could not find field '{label}'"); say("  !! missing field:", label); shot("missing-field")
    return False

def type_text(text):
    """Types word by word; one long burst of key events can be cut short on a slow emulator."""
    words = text.replace("'", "").split(" ")
    for i, w in enumerate(words):
        if w: adb("shell", "input", "text", w.replace("?", "\\?"))
        if i < len(words) - 1: adb("shell", "input", "keyevent", "62")
        time.sleep(0.35)
    time.sleep(0.8)

def back():
    adb("shell", "input", "keyevent", "4"); time.sleep(0.8)

def hide_keyboard():
    if "mInputShown=true" in adb("shell", "dumpsys", "input_method"):
        back()

def shot(name):
    step_no[0] += 1
    path = os.path.join(OUT, f"{step_no[0]:02d}-{name}.png")
    with open(path, "wb") as f:
        f.write(adb("exec-out", "screencap", "-p", binary=True))
    say("  shot", path)

def alive():
    return bool(adb("shell", "pidof", PKG).strip())

def check(step):
    if not alive():
        problems.append(f"app not running after: {step}")
        say("  !! app is not running after", step)
        adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"); time.sleep(4)

def expect(label, step, secs=15):
    if not wait_for(label, secs) and not scroll_to(label, 4):
        problems.append(f"{step}: expected to see '{label}'"); say("  !! expected", label, "at", step)
        return False
    return True

def make_xlsx(path, rows):
    """A small real .xlsx with one sheet "Stock", written the plain way (inline strings)."""
    import zipfile
    def col(i): return chr(ord("A") + i)
    def cell(r, c, v):
        ref = f"{col(c)}{r}"
        if isinstance(v, (int, float)): return f'<c r="{ref}"><v>{v}</v></c>'
        return f'<c r="{ref}" t="inlineStr"><is><t>{v}</t></is></c>'
    data = "".join(f'<row r="{r}">' + "".join(cell(r, c, v) for c, v in enumerate(row)) + "</row>" for r, row in enumerate(rows, 1))
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>')
        z.writestr("_rels/.rels", '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
        z.writestr("xl/workbook.xml", '<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Stock" sheetId="1" r:id="rId1"/></sheets></workbook>')
        z.writestr("xl/_rels/workbook.xml.rels", '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
        z.writestr("xl/worksheets/sheet1.xml", '<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>' + data + "</sheetData></worksheet>")

def xlsx_cell(raw, ref):
    """Value of one cell of sheet1 in an .xlsx given as bytes, or None."""
    import io, zipfile
    try:
        xml = zipfile.ZipFile(io.BytesIO(raw)).read("xl/worksheets/sheet1.xml").decode("utf-8")
    except Exception:
        return None
    m = re.search(r'<c r="' + ref + r'"[^>]*>(?:<v>([^<]*)</v>|<is><t[^>]*>([^<]*)</t></is>)', xml)
    return (m.group(1) or m.group(2)) if m else None

def step(name):
    say("==", name)

# ---------------------------------------------------------------- run
time.sleep(40)  # let the freshly booted emulator settle
adb("shell", "settings", "put", "global", "hide_error_dialogs", "1")
adb("shell", "settings", "put", "secure", "anr_show_background", "0")
say(adb("install", "-r", APK, timeout=180).strip())
adb("logcat", "-c")
adb("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")

step("onboarding")
expect("Welcome to Follower", "first launch", 40); shot("onboarding-1")
tap("Next", exact=True); expect("simply talking", "onboarding 2"); shot("onboarding-2")
tap("Next", exact=True); expect("Excel", "onboarding 3"); shot("onboarding-3")
tap("Next", exact=True); expect("Business Setup", "onboarding 4")
tap_field("Business name", 0); type_text("Sri Murugan Textiles"); hide_keyboard()
tap_field("Owner name", 1); type_text("Bharath"); hide_keyboard()
adb("shell", "input", "swipe", "500", "1500", "500", "600", "300"); time.sleep(1)
tap("Load sample data"); shot("business-setup")
tap("Get started", exact=True)
expect("Already keep your stock in Excel", "import offer after setup", 30); shot("import-offer"); tap("Later", exact=True)
expect("Today's Sales", "dashboard after onboarding", 30); check("onboarding"); shot("dashboard")

step("inventory")
tap("Inventory", exact=True); expect("Blue Shirt", "products list"); shot("products")
tap("Stock", exact=True); expect("Minimum", "stock tab"); shot("stock")
tap("History", exact=True); expect("Opening stock", "history tab"); shot("history")
tap("Categories", exact=True); expect("Garments", "categories tab")
tap("Products", exact=True); tap("Blue Shirt"); expect("Edit product", "product form"); shot("product-form"); back()
check("inventory")

step("assistant by text")
tap("Chat", exact=True); expect("Tell me what happened", "assistant"); shot("assistant-empty")
tap_field("Type what happened"); type_text("Sold 20 Blue Shirts to Kumar Traders"); tap("Send", exact=True)
hide_keyboard(); expect("Confirm", "sale confirmation", 25); shot("assistant-confirm")
tap("Confirm", exact=True); expect("Sale saved", "sale done"); shot("assistant-done")
tap_field("Type what happened"); type_text("How many Blue Shirts do I have"); tap("Send", exact=True)
expect("30 pieces", "stock answer"); hide_keyboard(); shot("assistant-answer")
tap_field("Type what happened"); type_text("Remove 10 because they were damaged"); tap("Send", exact=True)
expect("Confirm", "damage confirmation"); hide_keyboard(); tap("Confirm", exact=True); expect("You now have 20 pieces", "damage done")
tap_field("Type what happened"); type_text("Show low stock products"); tap("Send", exact=True)
expect("Black Shirt", "low stock answer"); hide_keyboard(); shot("assistant-low-stock")
tap_field("Type what happened"); type_text("hello"); tap("Send", exact=True); time.sleep(3); hide_keyboard(); shot("assistant-greeting")  # the greeting changes with the time of day
tap_field("Type what happened"); type_text("give me the list of shirt products"); tap("Send", exact=True); expect("Black Shirt", "product list by word"); hide_keyboard(); shot("assistant-chat")
tap_field("Type what happened"); type_text("Add 50"); tap("Send", exact=True)
expect("Which product", "clarification"); hide_keyboard(); shot("assistant-clarify")
tap_field("Type what happened"); type_text("cancel"); tap("Send", exact=True); hide_keyboard()
check("assistant text")

step("assistant by voice")
tap("Speak", exact=True)
time.sleep(2); shot("mic-permission")
for label in ("While using the app", "Only this time", "Allow"):
    if tap(label, secs=3, required=False): break
time.sleep(4); shot("mic-after-permission")
if find("Stop listening", exact=True): tap("Stop listening", exact=True)
check("voice")

step("transactions")
tap("Transactions", exact=True); expect("INV-0001", "sales list"); shot("sales")
tap("INV-0001"); expect("Cancel sale", "sale detail"); shot("sale-detail"); tap("Close", exact=True)
tap("Home", exact=True); tap("Sale", exact=True); expect("Add item", "sale form"); tap("Add item"); tap("A4 Paper"); expect("Subtotal", "sale form with item")
shot("sale-form"); tap("Save ·"); expect("Today's Sales", "back on home after sale")
sale = find("Sale", exact=True)
row_y = str(sale["y"]) if sale else "920"
adb("shell", "input", "swipe", "900", row_y, "200", row_y, "300"); time.sleep(1)
tap("Expense", exact=True); expect("Payment method", "expense form")
tap_field("Amount", 0); type_text("2500"); hide_keyboard(); tap("Transport", exact=True); shot("expense-form"); tap("Save", exact=True)
expect("Today's Sales", "back on home after expense")
tap("Transactions", exact=True); expect("INV-0002", "second sale listed"); tap("Expenses", exact=True); expect("Transport", "expense list"); shot("expenses")
tap("New expense", required=False, secs=5)
if find("Payment method"): back()
tap("Payments", exact=True); expect("No payments yet", "payments tab")
tap("Purchases", exact=True); expect("No purchases yet", "purchases tab")
check("transactions")

step("home totals")
tap("Home", exact=True); expect("Today's Sales", "home"); expect("₹9,300", "today sales total on home"); expect("₹2,500", "today expenses on home"); shot("dashboard-with-data")

step("more: parties, reports, export, backup")
tap("More", exact=True); expect("Reports", "more"); shot("more")
tap("Customers"); expect("Kumar Traders", "customers"); shot("customers"); back()
tap("Reports"); expect("Save as Excel", "reports", 20); shot("report-sales"); back()
tap("Backup / Restore"); expect("Create backup", "backup"); tap("Create backup"); expect(".aistock", "backup created"); shot("backup"); back()
tap("Import / Export"); expect("Export to Excel", "import export"); shot("import-export"); back()
check("more")

step("excel workbook: import with sizes, keep the owner's file updated, a sale changes it")
make_xlsx(os.path.join(OUT, "shop.xlsx"), [["Item", "Size", "Stock", "Price"], ["Polo", "XL", 10, 300], ["Polo", "L", 6, 280]])
adb("push", os.path.join(OUT, "shop.xlsx"), "/sdcard/Download/shop.xlsx")
tap("More", exact=True); tap("Import / Export"); tap("Let Stocky read my whole workbook"); tap("Choose file", exact=True); time.sleep(3)
if not wait_for("shop.xlsx", 8, exact=True):
    tap("Show roots", required=False); tap("Downloads", required=False); time.sleep(2)
shot("picker")
# The file picker sometimes uses the first touch to settle its layout: tap the file until the picker closes.
for _ in range(4):
    n = find("shop.xlsx", exact=True)  # the file name itself, not "Preview the file shop.xlsx"
    if not n or n["pkg"] == PKG: break  # back in the app
    # In the grid view a tap on the name area selects the file; a tap on its picture opens it.
    adb("shell", "input", "tap", str(n["x"]), str(max(300, n["y"] - 280))); time.sleep(4)
expect("Import and remember this workbook", "workbook read", 40); shot("workbook-columns")
expect("Polo XL", "size joined to the name in the preview")
tap("Import and remember this workbook"); expect("Import finished", "workbook imported", 30)
if expect("Keep this Excel file updated", "offer to keep the file updated", 10):
    tap("Turn on", exact=True); expect("current stock", "first update of the file", 30); shot("excel-linked")
tap("Ask Stocky", exact=True); time.sleep(2); tap("New conversation", required=False)
tap_field("Type what happened"); type_text("Sold 2 Polo XL"); tap("Send", exact=True)
hide_keyboard(); expect("Confirm", "size sale confirmation", 25); tap("Confirm", exact=True); expect("Sale saved", "size sale done")
value = None
for _ in range(15):
    time.sleep(2)
    value = xlsx_cell(adb("exec-out", "cat", "/sdcard/Download/shop.xlsx", binary=True), "C2")
    if value == "8": break
say("  excel C2 (Polo XL stock) =", value)
if value != "8": problems.append(f"excel write-back: Polo XL stock in the file is {value}, expected 8")
if xlsx_cell(adb("exec-out", "cat", "/sdcard/Download/shop.xlsx", binary=True), "C3") != "6": problems.append("excel write-back changed a row it should not")
tap("More", exact=True); tap("Import / Export"); tap("Linked Excel files"); expect("shop.xlsx", "linked files screen"); expect("rows changed", "update result shown"); shot("excel-links")
back(); back()
check("excel")

step("business setup: garment manufacturing")
tap("More", exact=True); scroll_to("Business setup"); tap("Business setup"); expect("Type of business", "setup screen")
tap("Garments / textile manufacturing"); time.sleep(1); shot("setup-garment"); tap("Save", exact=True, last=True); time.sleep(2)
tap("Production", exact=True); expect("New Production order", "production tab"); shot("production-empty")
tap("New Production order", exact=True); tap_field("Title"); type_text("500 polo shirts"); hide_keyboard()
tap("Save", exact=True, last=True); expect("PO-0001", "production order saved"); shot("production-list")
tap("500 polo shirts"); expect("Move to Stitching", "production detail"); tap("Move to Stitching"); time.sleep(2)
expect("[Stitching]", "moved to stitching"); shot("production-detail"); back()
check("production")

step("follow-up with reminder")
tap("More", exact=True); scroll_to("Follow-ups"); tap("Follow-ups"); expect("Add follow-up", "follow-ups")
tap("Add follow-up", exact=True); tap_field("or type a new name"); type_text("Priya"); hide_keyboard(); tap("Save", exact=True, last=True); time.sleep(2)
expect("Priya", "follow-up saved"); shot("followups"); back()
tap("More", exact=True); scroll_to("Reminders"); tap("Reminders"); expect("Call Priya", "reminder scheduled"); shot("reminders"); back()
check("follow-up")

step("back to a shop")
tap("More", exact=True); scroll_to("Business setup"); tap("Business setup"); tap("Fancy store / retail shop"); tap("Save", exact=True, last=True); time.sleep(2)
expect("Transactions", "shop tabs back"); tap("Home", exact=True)

step("sign in to the test Follower server; Stocky answers through it")
tap("More", exact=True); scroll_to("Follower account"); tap("Follower account"); expect("Server address", "account screen")
tap_field("Server address"); adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
for _ in range(12): adb("shell", "input", "keyevent", "KEYCODE_DEL")
type_text("http://10.0.2.2:8080"); hide_keyboard()
tap("Create account", exact=True)
tap_field("Email"); type_text("owner@shop.in"); hide_keyboard()
tap_field("Password"); type_text("shopowner1"); hide_keyboard()
tap_field("Business name"); type_text("Test Shop"); hide_keyboard()
tap("Create account", exact=True, last=True); expect("Plan", "signed in", 30); shot("account-signed-in")
expect("Free trial", "trial plan")
back(); tap("Settings"); scroll_to("Server voice"); expect("Server voice", "server voice option when signed in"); shot("settings-cloud-voice"); back()
tap("More", exact=True); scroll_to("Follower account"); tap("Follower account"); expect("Plan", "account again")
back(); tap("Chat", exact=True); tap("New conversation", required=False)
tap_field("Type what happened"); type_text("hello Stocky"); tap("Send", exact=True)
expect("Vanakkam from the test server", "AI reply through the server", 40); hide_keyboard(); shot("assistant-through-server")
tap("More", exact=True); scroll_to("Follower account"); tap("Follower account"); scroll_to("Sign out"); tap("Sign out"); time.sleep(2); back()
check("server")

step("settings: dark mode and Tamil")
tap("Settings"); expect("Language", "settings"); shot("settings")
if not scroll_to("AI Provider"): problems.append("settings: AI Provider section missing")
step("AI connection check (network path to Google, no key)")
if scroll_to("My Follower account"):
    tap("My Follower account"); time.sleep(1); tap("My own Gemini key on this phone (testing)"); time.sleep(2)
if scroll_to("On this phone (works offline)"):
    tap("On this phone (works offline)"); time.sleep(1); tap("Gemini AI when online"); time.sleep(2)
scroll_to("Check the AI connection"); tap("Check the AI connection")
time.sleep(25); scroll_to("AI key", 4); shot("ai-connection-check")
if not find("AI key"):
    problems.append("AI connection check did not finish")
if not find("✓ Secure connection to Google"):
    problems.append("AI connection check: could not reach Google from the emulator"); say("  !! no secure connection to Google")
else:
    say("  reached Google AI server")
if scroll_to("My own Gemini key on this phone"):
    tap("My own Gemini key on this phone"); time.sleep(1); tap("My Follower account (recommended)"); time.sleep(1)
for _ in range(10): adb("shell", "input", "swipe", "540", "700", "540", "1700", "200")
time.sleep(1)
step("hands-free listening service")
for perm in ("android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS"):
    adb("shell", "pm", "grant", PKG, perm)
scroll_to("hands-free"); shot("settings-handsfree")
tap("hands-free"); time.sleep(6)
svc = adb("shell", "dumpsys", "activity", "services", PKG)
if "HandsFreeService" not in svc:
    problems.append("hands-free service did not start"); say("  !! hands-free service not running")
else:
    say("  hands-free service running")
adb("shell", "cmd", "statusbar", "expand-notifications"); time.sleep(2); shot("handsfree-notification")
adb("shell", "cmd", "statusbar", "collapse"); time.sleep(1)
scroll_to("hands-free"); tap("hands-free"); time.sleep(3)
svc = adb("shell", "dumpsys", "activity", "services", PKG)
if "HandsFreeService" in svc and "isForeground=true" in svc:
    problems.append("hands-free service did not stop"); say("  !! hands-free still running")
check("hands-free")
scroll_to("Security"); shot("settings-security")
for _ in range(10): adb("shell", "input", "swipe", "540", "700", "540", "1700", "200")
time.sleep(1)
adb("shell", "cmd", "uimode", "night", "yes"); time.sleep(4); check("dark mode"); shot("settings-dark")
back(); tap("Home", exact=True); expect("Today's Sales", "home dark"); shot("dashboard-dark")
tap("Chat", exact=True); time.sleep(1); shot("assistant-dark")
adb("shell", "cmd", "uimode", "night", "no"); time.sleep(6)
tap("More", exact=True); tap("Settings"); expect("Language", "settings again")
tap("Language: English", exact=True); tap("தமிழ்", exact=True); time.sleep(4); check("language switch")
shot("settings-tamil"); back()
tap("முகப்பு", exact=True); expect("இன்றைய விற்பனை", "tamil dashboard"); shot("dashboard-tamil")
tap("ஸ்டாக்", exact=True); time.sleep(1); shot("inventory-tamil")
tap("பேசு", exact=True); time.sleep(1); shot("assistant-tamil")
tap_field("நடந்ததை எழுதுங்கள்")
type_text("blue shirt evlo irukku"); tap("அனுப்பு", exact=True); expect("பீஸ்", "tamil answer"); hide_keyboard(); shot("assistant-tamil-answer")
check("tamil")

step("restart keeps data and skips onboarding")
adb("shell", "am", "force-stop", PKG); time.sleep(1)
adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
expect("இன்றைய விற்பனை", "relaunch", 30); shot("relaunch")

# ---------------------------------------------------------------- verdict
time.sleep(2)
logcat = adb("logcat", "-d", "-v", "brief", timeout=120)
open(os.path.join(OUT, "logcat.txt"), "w").write("\n".join(l for l in logcat.splitlines() if PKG in l or "AndroidRuntime" in l or "FATAL" in l)[-400000:])
crashes = [l for l in logcat.splitlines() if "Process: " + PKG in l]
if crashes: problems.append(f"{len(crashes)} crash(es) in logcat")
say("problems:", problems if problems else "none")
open(os.path.join(OUT, "result.txt"), "w").write("PASS\n" if not problems else "FAIL\n" + "\n".join(problems) + "\n")
sys.exit(0)
