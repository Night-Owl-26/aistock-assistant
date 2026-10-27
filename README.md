# AIStock Assistant

**Your inventory. Just talk.**

An Android-first, offline-first inventory app for small businesses. Say or type what happened —
"Received 50 boxes of A4 paper from Kumar Traders at 250 rupees each" — the app shows what it understood,
asks you to confirm, and only then updates stock, bills and balances in its local database.

Package `com.aistock.assistant` · Kotlin · Jetpack Compose · Material 3 · Room · Hilt · min SDK 26.

## Getting the APK

Every push to `main` is built by GitHub Actions (`.github/workflows/build.yml`):

1. `./gradlew assembleDebug` builds the APK.
2. `./gradlew testDebugUnitTest` runs the unit tests.
3. When both pass, the APK is published under **Releases → "AIStock Assistant - latest build"** as `AIStockAssistant.apk`.

To build on your own computer, first fetch the offline "Hey Stocky" model (about 40 MB, kept out of git),
then open the folder in Android Studio, or run:

```
bash tools/fetch_wake_model.sh   # puts the model in app/src/main/assets/model-wake
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
```

Builds are signed with the debug key in `keystore/debug.keystore`, so a newer build installs over an older one
without losing data. Use your own private key before publishing to an app store.

## What V1 does

Business setup · dashboard · products and categories · stock in / out / adjustment with full history ·
low-stock detection and alerts · customers and suppliers with outstanding balances · sales · purchases ·
expenses · payments · seven reports with date filters · search · Excel/CSV import with column matching and preview ·
Excel export · local backup and restore · English and Tamil · light and dark mode · optional PIN lock.

Not in V1 on purpose: GST filing, full accounting, payroll, multi-branch, cloud sync, online payments, CRM.

## The assistant

```
microphone → SpeechRecognizer → text ┐
                       typed text ───┴→ AIProvider → AICommand → validation → clarification → confirmation
                                                                                → business logic → Room → reply
```

* `ai/AIProvider.kt` — the interface. The app does not depend on any particular AI vendor.
* `ai/LocalFallbackAIProvider.kt` — on-device rules for the V1 commands in English and mixed Tamil-English. Works offline.
* `ai/CloudAIProvider.kt` — optional. Talks to any "chat completions" compatible HTTPS endpoint.
* `ai/AssistantEngine.kt` — conversation state: asks for missing details, shows a confirmation, executes, offers undo.
* `ai/AssistantBackend.kt` — the only door to the data. The AI never sees SQL and cannot bypass business rules.

### Using a cloud AI model

No credentials are built into the app. In the app go to **More → Settings → AI Provider**, choose
"Cloud AI when online", and enter the service address (must be `https://`), the model name and your API key.
The key is encrypted with the Android Keystore and stored on the phone only. Whenever the phone is offline,
the service fails, or its reply is not valid, the on-device rules take over automatically.

Until that is filled in: **Cloud AI credentials not configured; local fallback is active.**

## Data safety rules built into the code

* Money is stored as whole paise and quantities as thousandths, in integers. No floating point.
* Stock never changes without an `InventoryTransaction` recording before, after and why (`InventoryRepository.move`).
* A sale or purchase is one database transaction: bill, lines, stock movements and balances succeed or roll back together.
* Sales, purchases, expenses and payments are never deleted; cancelling adds reversing stock movements and marks the record.
* Products, customers and suppliers with history are hidden (`active = false`) instead of deleted.
* Negative stock is refused unless switched on in Settings.
* Every assistant write is recorded in `audit_logs`.
* Restore validates the entire backup file first and replaces data inside one transaction; a bad file changes nothing.
* The database uses real Room migrations (`AppDatabase.MIGRATIONS`); destructive fallback is not enabled.

## Version 3: one app for many kinds of business

**Business platform** (`app/.../business/`). A versioned, validated `BusinessConfig` says which modules a business uses
(inventory, sales, purchases, expenses, customers, suppliers, orders, production, service tickets, appointments,
projects, tasks, follow-ups, staff), its own words for things, its process steps and extra fields. Ten templates
(`Templates.kt`): retail, garment manufacturing, wholesale, grocery, restaurant, service/repair, salon, construction,
agency, custom. The bottom bar, the More menu, the forms and Stocky's tools all follow the configuration. Nothing in a
configuration can run code or SQL; the server validates the same JSON shape (`server/src/businessConfig.js`).

**Work items, follow-ups, staff, reminders, expiry, briefing.** One `work_items` table (with a `work_events` history)
serves production orders, job cards, appointments, projects, orders and tasks. Follow-ups with reminders
(AlarmManager, restored after reboot), a contact log, staff, batch expiry dates, and `Insights` — sales compared with the
previous period, estimated margin (labelled as an estimate), fast/slow sellers, low stock with reorder amounts, prices at
or below cost, expense jumps, missing entries, overdue work and follow-ups. Calls and messages are only handed to the
dialler / WhatsApp / SMS; nothing is sent or dialled by the app.

**Server** (`server/`, Node 22 + PostgreSQL + Caddy, Docker Compose). Accounts, businesses and roles, the Gemini key,
plan limits and usage, real-time voice relay, backups, audit log. See `server/README.md` for deployment on Oracle Cloud.

**Real-time voice** (`app/.../voice/live/`). The Gemini Live API through the owner's server: 16 kHz microphone audio
streams up, Stocky's 24 kHz voice streams back and plays as it arrives; Google's voice detection lets the owner interrupt.
Echo cancelling and noise suppression from the phone are switched on. Tool calls run in the app with the same rules as
typed chat; a spoken "yes" only counts after Stocky has finished asking. Without a server the earlier speech-to-text →
Gemini text → text-to-speech path (or the on-device rules) is used.

**Database.** Room version 4; `MIGRATION_2_3` and `MIGRATION_3_4` only add tables, so earlier data is kept (tested from a real version-1 file, and 3 → 4 in `SmartWorkbookTest`).

## Version 3.1: smart Excel columns, sizes, your own Excel file kept up to date, Google Cloud voice

**AI column matching** (`excel/HeaderMapper.kt`). When a workbook is uploaded, the column titles and up to three example
values per column (phone numbers and emails masked) are sent to Gemini, which says what each sheet holds and which column is
the name, size, stock, price and so on — whatever language or short form the titles use ("Itm", "Sz", "Bal Qty", "Rt/pc").
The answer is checked: unknown fields and titles that are not in the sheet are dropped, no column is used twice, price and
quantity columns must hold numbers. Anything left out falls back to the header rules. Every choice is shown with who made
it (AI, rules, last time, you) and can be changed before importing. The confirmed mapping is saved and reused for the same
layout without asking the AI again.

**Sizes.** A Size column makes each size its own product ("Shirt" + "XL" → "Shirt XL") with its own stock and price.
Stocky's sale and stock tools take a `size` ("XL", "32", "medium" → "M", "double XL" → "XXL"), and "XL shirt" finds "Shirt XL".

**Your Excel file kept up to date** (`excel/ExcelSync.kt`, `XlsxPatcher.kt`, `ExcelSyncPlan.kt`). After an import the owner
can turn this on for the file they picked. A few seconds after any stock change (a sale, stock in, a correction), the file is
read again, rows are found by product name (and size), and only the stock (and price) cells that differ are rewritten
inside the .xlsx; formulas, formatting, other sheets and all other parts are copied unchanged, formula cells are never
overwritten, and Excel recalculates on opening. Optional: new products added as rows, and an "AIStock Sales" sheet with every
bill and the total revenue. A copy of the file is kept inside the app before every write (last 5), the written file is read
back and the original put back if it does not check out. More > Import / Export > Linked Excel files shows the last result,
updates now, pauses or stops.

**Google Cloud Speech-to-Text and Text-to-Speech** (`voice/cloud/`, `server/src/speech.js`). Optional, in Settings when signed
in. The phone records one request (it stops by itself after a pause), the server sends it to Google Cloud Speech-to-Text
(Tamil, with Indian English as the second language; product and customer names as hints), Stocky handles the text with
Gemini, and the reply is read by Google Cloud Text-to-Speech (a Tamil voice for Tamil text, Indian English otherwise). The
Google key stays on the server (`GOOGLE_CLOUD_API_KEY`); speech time counts against the plan's voice minutes. If the speech
service fails, the phone's own voice reads the reply.

**Sarvam AI speech-to-text** (`server/src/speech.js`). With `SARVAM_API_KEY` set on the server, Sarvam AI (`saaras:v4`,
`codemix` mode: Tamil words in Tamil script, English words in English) turns the owner's speech into text instead of Google,
with up to 50 product and customer names as key terms. The app does not change: it sends the recording to the server either way.

## Project layout

```
app/src/main/java/com/aistock/assistant/
  ai/          command model, on-device parser, cloud provider, conversation engine
  backup/      backup and restore
  data/        repositories and business rules, settings
  database/    Room entities, DAOs, database
  di/          Hilt module
  excel/       .xlsx / .csv reader and writer, import rules, reports, export
  features/    home, inventory, transactions, parties, reports, ai, data, settings, onboarding
  ui/          theme, shared components, navigation
  utils/       money, quantities, units, dates, name matching
  voice/       speech recognition and text-to-speech
app/src/test/  unit tests (JVM + Robolectric with a real in-memory Room database)
tools/strings.py   single source for the English and Tamil text; generates both strings.xml files
tools/smoke.py     emulator smoke test used by the device-test job
```

## Tests

`./gradlew testDebugUnitTest` covers product creation, stock in/out/adjustment, negative-stock protection,
sales, purchases, expenses, payments, outstanding balances, low-stock detection, inventory transaction creation,
command parsing and validation, date, money and unit parsing, Excel import/export, backup and restore,
and the full spoken end-to-end sequence against a real database (`EndToEndTest`).

Adding `[device]` to a commit message also runs `tools/smoke.py` on an emulator in CI and saves screenshots
to the `ci-device` branch.
