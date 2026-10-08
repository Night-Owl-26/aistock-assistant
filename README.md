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

To build on your own computer, open the folder in Android Studio, or run:

```
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
