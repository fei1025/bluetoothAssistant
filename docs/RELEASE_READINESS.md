# Release readiness

## 当前证据

| 检查项 | 结果 |
|---|---|
| `testDebugUnitTest` | 通过 |
| `lintDebug` | 通过 |
| `assembleDebug` | 通过 |
| `bundleRelease` | 通过，生成 `app/build/outputs/bundle/release/app-release.aab` |
| `connectedDebugAndroidTest` | 通过，API 36 模拟器运行 2 个 instrumentation 测试 |
| 版本来源 | 已统一由 `app/build.gradle` 管理（versionCode 24 / versionName 2.4），Manifest 不再保留旧版本号 |
| 日志导出权限 | 已改用系统文件选择器，旧版 Android 不再需要 `WRITE_EXTERNAL_STORAGE` |
| AAB 签名 | 未通过：当前仓库没有配置发布 keystore，`jarsigner -verify` 报告 `jar 未签名` |
| 真机蓝牙验收 | 未完成：当前环境 `adb devices -l` 没有连接设备 |

## 发布前必须补齐

1. 提供项目实际使用的发布 keystore、alias 和密码，或在 Android Studio/CI 的安全环境中配置 signing config。
2. 使用签名后的 AAB 执行 `jarsigner -verify` 或 Play Console 上传前校验。
3. 按 [蓝牙连接回归清单](BLUETOOTH_CONNECTION_REGRESSION_CHECKLIST.md) 完成两台 Android 设备或 Android + SPP 模块验收。

不在仓库中保存 keystore、密码、私钥或密码库内容。
