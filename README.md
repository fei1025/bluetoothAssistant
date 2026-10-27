# Bluetooth Assistant

Bluetooth Assistant is an open-source Android app for scanning, connecting, and testing Bluetooth SPP-style device communication. It is aimed at Android, embedded, IoT, and maker workflows where a lightweight phone-side Bluetooth debugging tool is useful.

The app is written in Java, uses ViewBinding, and compiles/targets Android SDK 36 (minimum API 23).

Google Play listing: [蓝牙调试助手-SPP(串口)通信](https://play.google.com/store/apps/details?id=com.zzf.bluetoothsmp)

## BLE Low Energy

The Home toolbar now includes a BLE debugger for scanning/filtering, advertising details, GATT services/characteristics/descriptors, read/write, Notify/Indicate, UART setup, MTU splitting, repeated sends and log export. See [BLE_SUPPORT.md](docs/BLE_SUPPORT.md) for usage, feature boundaries and device acceptance checks.

## Features

The main navigation contains Home, Service and History. A conversation contains Chat, Keyboard and Debug tabs.

- Scan nearby Bluetooth devices and show connection information.
- Connect to Bluetooth devices from an Android phone.
- Start and stop a phone-side Bluetooth service manually.
- Configure the service UUID and client UUID used for SPP-style communication.
- Send and receive messages in chat mode.
- Use keyboard mode to bind reusable commands to buttons.
- Store message and system configuration data locally with LitePal.
- Support Chinese and English app language resources.
- Monitor traffic in Debug, send hex/binary data, run command macros, and export logs with the system file picker.
- Configure stream framing (CRLF, custom delimiter, fixed length or timeout) and text encoding per device.
- Resume eligible client connections when returning to the foreground.

Bluetooth communication is active while the app is visible and the screen is unlocked. Leaving the app or locking the screen stops discovery, listening and connections; returning restores the saved server switch and enabled reconnect intentions. Moving between screens inside the app preserves communication. The app does not provide background Bluetooth transfers.

## Typical Use Cases

- Debug Bluetooth modules, microcontrollers, and embedded devices.
- Send repeatable control commands from Android to Bluetooth equipment.
- Test Bluetooth service UUID changes without writing a custom app each time.
- Keep a lightweight history of Bluetooth messages during hardware development.

## Distribution

The app is available on Google Play as `com.zzf.bluetoothsmp`. The linked listing is the source for the currently published version; local release candidates and internal drafts are documented separately.

## Requirements

- Android Studio Ladybug or newer is recommended.
- JDK 17 or the JDK bundled with Android Studio.
- Android device with Bluetooth support.
- Android 6.0+ for installation. Android 12+ devices require the runtime Bluetooth permissions declared by the app.

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

## Billing Development

The Google Play one-time supporter badge implementation is documented in
[docs/SUPPORTER_BADGE_BILLING.md](docs/SUPPORTER_BADGE_BILLING.md). Read it before
changing the product ID, purchase lifecycle, entitlement rules, or Play Console
configuration.

## Permissions And Privacy

Bluetooth Assistant requests Bluetooth and location-related permissions because Android requires them for Bluetooth discovery and connection workflows on different Android versions. The project also includes Firebase dependencies for analytics/crash reporting and uses network permission for update or reporting integrations.

Review `app/src/main/AndroidManifest.xml` before producing a release build for your own distribution channel.

System cloud backup and device transfer whitelist the language preference and server enabled switch. Chat history, device identifiers, connection profiles, purchase cache/tokens and all databases are excluded. Macros share the history database, so use explicit macro export/import to transfer them. Debug and `-PofflineAudit` builds disable Firebase collection and production mapping upload.

## Checks And Release Evidence

Every push runs **Build test APK**. Open [GitHub Actions](https://github.com/fei1025/bluetoothAssistant/actions/workflows/build-apk.yml), select the latest successful run, and download `test-apk-<commit>` from **Artifacts**. The ZIP contains the installable Debug APK, SHA-256 checksum and commit/version information, retained for 14 days. You can also use **Run workflow** to build a selected branch. This quick APK job is separate from the full emulator and optimized Release checks.

These are test builds with plugin developer mode enabled and Firebase collection disabled. To keep a stable test signature across runs, optionally configure repository Actions secret `CI_DEBUG_KEYSTORE_BASE64` with a dedicated Android debug keystore (alias `androiddebugkey`, store/key password `android`). Never use the production upload key for this secret. Without it, runner-generated debug signatures may differ; do not uninstall an existing app to work around a signature mismatch. Production upgrades must retain the original app signing identity.

```powershell
.\gradlew.bat -PofflineAudit testDebugUnitTest lintRelease assembleDebug bundleRelease
python -B -m unittest discover -s scripts/tests -p "test_*.py"
```

The GitHub workflow builds and checks JVM code, lint, uploader safeguards and disposable-key signature regression. Its emulator matrix covers API 23/31/36; separate optimized Release tests verify LitePal schema, reflective persistence, and the chat Debug page after Activity recreation. These checks do not replace real RFCOMM peripheral, Play purchase/refund, minimum-system and long-duration performance acceptance.

The completed 2026-10-13 repair plan, local API 23/36 results and artifact identities are recorded in [the optimization plan](docs/OPTIMIZATION_PLAN.md) and [verification evidence](docs/OPTIMIZATION_VERIFICATION_2026-10-13.json).

The local signed release pipeline stores `build-info.json` with the Git revision, dirty-source fingerprint, tool/library identities, test/lint summary and artifact hash, and refuses source changes during the build. Signature/script success requires explicit completed-command evidence; standalone evidence capture defaults to unknown. Device CI stores the tested APK hash, Git SHA, API and actual test outcome with its reports. `BuildOnly` disables mapping uploads through `-PskipMappingUpload` while preserving normal Release collection behavior. See [release automation](docs/RELEASE_AUTOMATION.md) and [release readiness](docs/RELEASE_READINESS.md). Publishing still requires separate authorization and review of the complete Play publication overview.

## Bluetooth plugins

Open **Home → toolbar → Plugin center** to import a `.btplugin`, download from HTTPS, or install the bundled generic SPP / BLE examples. Plugins draw local HTML/CSS/JavaScript pages and use a permission-controlled SDK. Release accepts author-signed and review-signed content only; Debug additionally accepts author-signed development packages. Runtime pages have no network access and Bluetooth stops in the background. Existing button layouts and history are preserved.

See the [SDK and package format](docs/PLUGIN_SDK.md), [TypeScript declarations](plugins/sdk.d.ts), [signing and catalogue publishing](docs/PLUGIN_PUBLISHING.md), and [release/hardware checklist](docs/PLUGIN_RELEASE_CHECKLIST.md). The static GitHub catalogue workflow is prepared; deployment and hardware/Play acceptance are separate release tasks.

Current local build and regression evidence: [plugin verification (2026-10-26)](docs/PLUGIN_VERIFICATION_2026-10-26.md).

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
