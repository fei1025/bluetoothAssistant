# Contributing

Thanks for helping improve Bluetooth Assistant.

## Good First Contributions

- Improve setup and build documentation.
- Report Android version compatibility issues.
- Add screenshots or usage examples.
- Fix permission handling on specific Android versions.
- Improve Bluetooth connection, reconnection, and error messages.

## Reporting Bugs

Please include:

- App version or commit SHA.
- Android version and device model.
- Bluetooth module or target device information.
- Steps to reproduce.
- Expected result and actual result.
- Logs, screenshots, or screen recordings when available.

## Pull Requests

Before opening a pull request:

1. Keep the change focused.
2. Explain the problem and the fix.
3. Build the project locally when possible.
4. Note any Android version or device-specific testing you performed.

Recommended local check:

```powershell
.\gradlew.bat assembleDebug
```

## Code Style

- Follow the existing Java and Android project style.
- Keep UI text in Android string resources.
- Avoid adding new runtime permissions unless the feature requires them.
- Document Bluetooth behavior changes in the pull request.
