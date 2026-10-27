# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

Android Bluetooth SPP assistant app (classic Bluetooth RFCOMM) with three main navigation tabs and a chat Debug tab:
- **Home**: device discovery + connect
- **Service**: local/client SPP UUID configuration + server start/stop
- **History**: conversation/device history from local DB
- **Debug** (inside the chat ViewPager): realtime traffic monitor, hex/ascii tools, stress send, log export

## Common Commands

Run from repository root (`bluetoothAssistant/`).

```bash
# Build
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew clean

# Lint
./gradlew lint
./gradlew lintDebug

# Unit tests
./gradlew test
./gradlew testDebugUnitTest

# Offline release audit (no production Firebase mapping upload/collection)
./gradlew -PofflineAudit testDebugUnitTest lintRelease bundleRelease
python -B -m unittest discover -s scripts/tests -p "test_*.py"

# Run one unit test class or method
./gradlew testDebugUnitTest --tests "com.zzf.bluetoothsmp.ExampleUnitTest"
./gradlew testDebugUnitTest --tests "com.zzf.bluetoothsmp.ExampleUnitTest.testMethod"

# Instrumentation tests (device/emulator required)
./gradlew connectedDebugAndroidTest

# Run one instrumentation test
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.zzf.bluetoothsmp.ExampleInstrumentedTest
```

## Git Conventions

- Git commit messages must be written in Chinese.

## Tech Stack / Build Facts

- Android Gradle Plugin: **8.13.2**
- compileSdk / targetSdk: **36**
- minSdk: **23**
- Language: **Java** (ViewBinding enabled)
- Storage: **LitePal** (`app/src/main/assets/litepal.xml`)
- Firebase Analytics + Crashlytics enabled in app module

## High-Level Architecture

### 1) Connection model (client + server in same app)

- **Server side**: `BluetoothService` starts an RFCOMM server socket with `listenUsingInsecureRfcommWithServiceRecord(...)` and accepts incoming sockets.
- **Client side**: `BluetoothObject` initiates outbound RFCOMM connections to selected remote devices.
- Both paths create a `BluetoothServiceConnect` session object that owns input/output streams for one remote device.

Core files:
- `app/src/main/java/com/zzf/bluetoothsmp/BluetoothService.java`
- `app/src/main/java/com/zzf/bluetoothsmp/BluetoothObject.java`
- `app/src/main/java/com/zzf/bluetoothsmp/BluetoothServiceConnect.java`

### 2) Global runtime state and message bus

`StaticObject` holds shared process-wide state:
- `bluetoothSocketMap`: active sessions keyed by remote MAC
- `mTaskQueue`: bounded `BluetoothMessageQueue` implementing `BlockingQueue<Msg>`; FIFO data has a separate reserved, prioritized control lane. Producers use `submitMessage` for nonblocking sends and `submitReceivedMessage` for receive backpressure; admission/generation checks prevent stale or excessive work
- `bluetoothEvent`: app-wide event dispatcher

Message flow:
1. Producers (chat UI, receive thread, disconnect handlers) enqueue `Msg` into `StaticObject.mTaskQueue`
2. `StaticObject` owns the process-wide background consumer and monitor lifetime
3. Dispatch to `BluetoothEvent` (`SEND`, `RECEIVE`, `NOT_CONNECT`, `All_MSG`)
4. Subscribers (session writers, DB monitor, UI fragments) react via `EventDispatcher`

Core files:
- `app/src/main/java/com/zzf/bluetoothsmp/StaticObject.java`
- `app/src/main/java/com/zzf/bluetoothsmp/MainActivity.java`
- `app/src/main/java/com/zzf/bluetoothsmp/event/EventDispatcher.java`
- `app/src/main/java/com/zzf/bluetoothsmp/event/BluetoothEvent.java`

### 3) Socket session behavior

`BluetoothServiceConnect` does two key jobs:
- Registers SEND listener: admits work to the session's bounded `BluetoothSendQueue`; its writer owns stream writes and reports final send status
- Runs receive loop: reads bytes through `StreamFrameDecoder`, emits RECEIVE frames to the central queue

CRLF is the default text framing, not a universal wire convention. Device protocol settings also support delimiter, fixed-length and timeout framing. Text encoding is selected per device; binary/hex payloads must not acquire implicit text terminators. Send admission is distinct from write success.

### 4) UI/navigation structure

`MainActivity` hosts Navigation Component + bottom nav (`mobile_navigation.xml`, `bottom_nav_menu.xml`) and coordinates permission/bootstrap flow.

Fragments:
- `HomeFragment`: discovery list + initiate client connection
- `ServiceFragment`: configure service/client UUID and restart local server
- `DashboardFragment`: history list from LitePal models
- `DebugFragment`: connection stats, hex tools, send-once/continuous send, diagnostics, export logs

Chat screen:
- `Liantian_new` activity with ViewPager tabs: `ChatModeFragment` and `KeyboardFragment`

### 5) Persistence model

LitePal entities in active use:
- `SystemInfoMapper`: persisted service/client SPP UUID overrides
- `MessageMapper`: per-message history records
- Raw message bytes use the supported String `payloadBase64` column (schema v6); LitePal 3.2.3 does not persist `byte[]` model fields. Decode through model/repository APIs; preserve legacy TEXT fallback and stable message IDs
- `BluetoothDrive`: per-device conversation metadata / last message
- `KeyboardEntity`: keyboard mode data
- `CommandMacroEntity`: reusable command macros (explicit export/import)
- `BluetoothDeviceProfileEntity`: per-device preferences

`MonitorMessage` subscribes to Bluetooth events and persists message + device summary data.

Core files:
- `app/src/main/java/com/zzf/bluetoothsmp/utils/MonitorMessage.java`
- `app/src/main/java/com/zzf/bluetoothsmp/entity/*.java`
- `app/src/main/assets/litepal.xml`

## Operational Notes

- BLE central/GATT debugging lives in `ble/BleActivity` + retained `BleClient`, entered from the Home toolbar; it does not use RFCOMM sessions or SPP chat/history. See `docs/BLE_SUPPORT.md` for supported features, semantics and hardware acceptance checks. Keep GATT procedures serialized, validate characteristic instances, and close stale/timed-out sessions. `BleRuntime.suspendAll()` participates in global foreground teardown; do not add background BLE work.
- BLE callbacks cross the `BleGattConnection` boundary; keep immediate `BluetoothStatusCodes` separate from asynchronous ATT status codes. Invalidate each procedure at completion, queue continuation chunks behind already-arrived MTU updates, and preserve local delivery until remote CCCD disable succeeds. Run `ble.BleClientInstrumentedTest` for deterministic connection/write/subscription/teardown regressions; these tests use a transport fake, not a real peripheral.

- Default SPP UUID constant: `00001101-0000-1000-8000-00805F9B34FB`
- Effective UUID can be overridden from DB (`SystemInfoMapper`) separately for client and server paths
- Runtime bootstrap (permissions, adapter init, discovery, service startup) is centralized in `MainActivity`
- `MyApplication` initializes LitePal, language handling and the process foreground gate
- Bluetooth work is foreground-only: background/lockscreen suspends discovery, server, sessions and reconnects; internal Activity handoff/configuration rebuild preserves foreground intent
- Billing acknowledgement alone uses short persistent WorkManager jobs; never start a Bluetooth foreground worker or reintroduce foreground-service permissions
- Backups whitelist `i18n.xml` and `bluetooth_server.xml`; LitePal DBs, MAC-bearing preferences, Firebase state and purchase cache/tokens are excluded. Restore macros by explicit export/import
- `-PofflineAudit` disables Firebase collection and mapping upload; CI uses synthetic Firebase configuration and disposable test signing only
- `-PskipMappingUpload` only disables Crashlytics mapping upload and leaves Release collection behavior intact; `Build-And-Upload.ps1 -BuildOnly` passes it automatically
- Preserve existing LitePal model/field names during R8/schema changes. `core:3.2.3` has no consumer rules; run the optimized persistence smoke before weakening reflection keeps
- Local build evidence captures Git revision, dirty-source fingerprint, tool/library identities and checks. Historical real-device/Play acceptance records do not prove a new candidate passed
- Debug previews run in a count/byte-bounded background pipeline (512-byte HEX/ASCII, 4096-character text); display batches every 100 ms and can pause. Full raw bytes remain in the bounded export snapshot. Send rows follow `SEND_STATUS`/messageId/sessionId; only terminal `SENT` increments transport success counters
- Debug SAF transfer state is a `SavedStateHandle` ViewModel containing cache paths, URIs and a captured target MAC only. Serialize/write/import in IO workers, publish export readiness after closing the snapshot, and import macros transactionally with a 256 KiB transfer cap
- Current queue defaults: 512 data messages / 4 MiB estimated memory plus 64 control events / 256 KiB; each session admits at most 128 sends / 2 MiB raw pending bytes, including canceled writes that have not returned. Queue-full is an admission error and must not close a healthy socket
- Framing delimiter matching is incremental and preserves empty frames. Oversized delimiter frames drop through the next delimiter; oversized timeout bursts drop through idle. RAW preserves read bytes and text decoding carries UTF-8/GBK fragments across reads
- Plugin platform lives in `plugin/`; `PluginRuntime` retains one local WebView and foreground-only SPP/BLE ownership. SPP plugin sessions are RAW and bypass chat/history/reconnect preferences; do not route plugin data onto the global history bus. BLE native UI and plugins share `BleController` (the `BleClient` facade preserves UI/test APIs).
- Release `PLUGIN_DEVELOPER_MODE` must remain false. Review public roots are assets; private signing material must stay outside the repository. Revocations and installed identities use signed-manifest SHA-256, independently of the ZIP download hash. Never replace a published version's content or author identity.
- Plugin install/runtime/SDK and publishing docs are `docs/PLUGIN_SDK.md`, `docs/PLUGIN_PUBLISHING.md`, `docs/PLUGIN_RELEASE_CHECKLIST.md`; generic examples are `samples/plugins/`. Do not add runtime networking, background work or manufacturer-specific behavior to the generic platform.
