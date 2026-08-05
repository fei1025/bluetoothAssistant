# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

Android Bluetooth SPP assistant app (classic Bluetooth RFCOMM) with four primary tabs:
- **Home**: device discovery + connect
- **Service**: local/client SPP UUID configuration + server start/stop
- **History**: conversation/device history from local DB
- **Debug**: realtime traffic monitor, hex/ascii tools, stress send, log export

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
- compileSdk / targetSdk: **35**
- minSdk: **21**
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
- `mTaskQueue`: `PriorityBlockingQueue<Msg>` used as central message pipeline
- `bluetoothEvent`: app-wide event dispatcher

Message flow:
1. Producers (chat UI, receive thread, disconnect handlers) enqueue `Msg` into `StaticObject.mTaskQueue`
2. `MainActivity.sendEvent` background thread consumes queue
3. Dispatch to `BluetoothEvent` (`SEND`, `RECEIVE`, `NOT_CONNECT`, `All_MSG`)
4. Subscribers (session writers, DB monitor, UI fragments) react via `EventDispatcher`

Core files:
- `app/src/main/java/com/zzf/bluetoothsmp/StaticObject.java`
- `app/src/main/java/com/zzf/bluetoothsmp/MainActivity.java`
- `app/src/main/java/com/zzf/bluetoothsmp/event/EventDispatcher.java`
- `app/src/main/java/com/zzf/bluetoothsmp/event/BluetoothEvent.java`

### 3) Socket session behavior

`BluetoothServiceConnect` does two key jobs:
- Registers SEND listener: writes outbound content to socket output stream
- Runs receive loop: reads bytes, splits by `\r\n`, emits RECEIVE messages to queue

Important: the app’s framing convention is newline-delimited payloads (`\r\n`). Chat sending appends CRLF before enqueueing.

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
- `BluetoothDrive`: per-device conversation metadata / last message
- `KeyboardEntity`: keyboard mode data

`MonitorMessage` subscribes to Bluetooth events and persists message + device summary data.

Core files:
- `app/src/main/java/com/zzf/bluetoothsmp/utils/MonitorMessage.java`
- `app/src/main/java/com/zzf/bluetoothsmp/entity/*.java`
- `app/src/main/assets/litepal.xml`

## Operational Notes

- Default SPP UUID constant: `00001101-0000-1000-8000-00805F9B34FB`
- Effective UUID can be overridden from DB (`SystemInfoMapper`) separately for client and server paths
- Runtime bootstrap (permissions, adapter init, discovery, service startup) is centralized in `MainActivity`
- `MyApplication` initializes LitePal and app language handling on activity lifecycle callbacks
