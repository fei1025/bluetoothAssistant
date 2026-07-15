# Bluetooth Assistant

Bluetooth Assistant is an open-source Android app for scanning, connecting, and testing Bluetooth SPP-style device communication. It is aimed at Android, embedded, IoT, and maker workflows where a lightweight phone-side Bluetooth debugging tool is useful.

The app is written in Java and currently targets Android SDK 35.

Google Play listing: [蓝牙调试助手-SPP(串口)通信](https://play.google.com/store/apps/details?id=com.zzf.bluetoothsmp)

## Features

- Scan nearby Bluetooth devices and show connection information.
- Connect to Bluetooth devices from an Android phone.
- Start and stop a phone-side Bluetooth service manually.
- Configure the service UUID and client UUID used for SPP-style communication.
- Send and receive messages in chat mode.
- Use keyboard mode to bind reusable commands to buttons.
- Store message and system configuration data locally with LitePal.
- Support Chinese and English app language resources.

## Typical Use Cases

- Debug Bluetooth modules, microcontrollers, and embedded devices.
- Send repeatable control commands from Android to Bluetooth equipment.
- Test Bluetooth service UUID changes without writing a custom app each time.
- Keep a lightweight history of Bluetooth messages during hardware development.

## Distribution

The app is available on Google Play as `com.zzf.bluetoothsmp`. The public Play listing shows 100+ downloads, a Tools category listing, a 3+ content rating, and an update date of January 16, 2026.

## Requirements

- Android Studio Ladybug or newer is recommended.
- JDK 17 or the JDK bundled with Android Studio.
- Android device with Bluetooth support.
- Android 5.0+ for installation. Android 12+ devices require the runtime Bluetooth permissions declared by the app.

## Build

Clone the repository and build with Gradle:

```powershell
git clone https://github.com/fei1025/bluetoothAssistant.git
cd bluetoothAssistant
.\gradlew.bat assembleDebug
```

On macOS or Linux:

```bash
git clone https://github.com/fei1025/bluetoothAssistant.git
cd bluetoothAssistant
./gradlew assembleDebug
```

The debug APK is generated under:

```text
app/build/outputs/apk/debug/
```

## Permissions And Privacy

Bluetooth Assistant requests Bluetooth and location-related permissions because Android requires them for Bluetooth discovery and connection workflows on different Android versions. The project also includes Firebase dependencies for analytics/crash reporting and uses network permission for update or reporting integrations.

Review `app/src/main/AndroidManifest.xml` before producing a release build for your own distribution channel.

## Project Status

This is a personal open-source utility project. Current maintenance priorities are:

- Improve README and release documentation.
- Add clearer permission and privacy notes.
- Modernize Android permission handling across Android versions.
- Add automated checks for Gradle builds.
- Reduce legacy code and improve Bluetooth connection reliability.

## Contributing

Bug reports, compatibility reports, documentation improvements, and pull requests are welcome. Please include:

- Android version and device model.
- Bluetooth device/module type.
- Reproduction steps.
- Relevant logs or screenshots when possible.

See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

## Security

Bluetooth apps handle device identifiers, nearby-device discovery, and user-granted permissions. Please report security issues privately when possible. See [SECURITY.md](SECURITY.md).

## License

This project is released under the MIT License. See [LICENSE](LICENSE).
