# Mi 11 真机测试记录 — 2026-09-09

应用：`com.zzf.bluetoothsmp`，当前候选 **28 / 2.8**。设备为 **Mi 11、Android API 34**，对端为 Windows 电脑 **ZZF-01** 的经典蓝牙 RFCOMM。**本次真机回归已结束，记录范围内的功能通过**，包括解锁后自动重连与双向收发恢复、服务关闭后 Home/冷启动保持和中英文切换。支付、真实 API 23、签名 Play 安装及全面性能验收仍未完成。此记录不代表签名 AAB 已上传、送审或发布。

后续测试时，用户已开启并核实保持亮屏设置（`stay_on_while_plugged_in=15`），USB 安全输入可用；Google Play/GMS 的停用状态未修改。这些是测试环境设置，不是应用功能改动。

## 安装与原始数据保护

已从手机拉取原 debug **23 / 2.3、target 35** APK，并在覆盖前备份数据库及 SharedPreferences；采用同签名覆盖安装，**没有卸载应用或清除其数据**。原始私有数据 tar 为 **252,416 bytes**，已完成备份校验。

覆盖前数据库 `message` 的 `user_version=2`；`messagemapper`、`bluetoothdrive`、`keyboardentity`、`systeminfomapper` 均为 0 行。因此本次能验证备份和覆盖流程，但不能把该手机当作已有真实历史消息/键盘配置的 23 版迁移样本。

先覆盖原 28 候选进行通信与构造配置，再覆盖包含键盘兼容修复和真实设备名称修复的最新 28 候选。没有在用户应用上运行会自动安装/卸载目标包的 instrumentation 流程。

## 当前构建与适用范围

`final-candidate-build.log` 记录执行 `testDebugUnitTest assembleDebug lintRelease bundleRelease`，**BUILD SUCCESSFUL in 52s**。

| 检查 | 结果 |
|---|---|
| JVM 测试 | **97 tests，0 failures、0 errors、0 skipped**；含 13 项键盘映射/选择回归 |
| Android Lint | **0 errors / 159 warnings** |
| 最新 debug APK | 13,581,017 bytes，已用于本次最终覆盖测试 |
| 最新 AAB | 5,480,621 bytes，**unsigned，不可直接上传 Play** |
| Instrumentation | 此前 **16 项**通过属于当时的 **API 36.1 模拟器候选**；未随本次最新源码重跑，也没有在 Mi 11 上执行 |

`app/build/outputs/apk/debug/app-debug.apk`：

```text
SHA-256 1A7D5E60C1474E597DB97B73B3E59E11B6FEE89762FA318D01DC57C78213A607
```

`app/build/outputs/bundle/release/app-release.aab`：

```text
SHA-256 7DD44A9201FA1B1988C0C945D724A49FA7A917526F27EE6C7BE2CBEACA0702B2
```

## 真实蓝牙收发与生命周期

以下均使用真实无线 RFCOMM，不是模拟 socket。测试帧使用 CRLF 结尾，表中时间为原始日志的 **UTC**。

| 场景 | 观察结果 | 状态 |
|---|---|---|
| 手机服务端、电脑客户端 | `rf-client-01.jsonl` 于 13:35:58 连接并发送 `PC_TO_PHONE`；手机界面显示接收内容 | 通过（原 28 候选） |
| 手机服务端向电脑发送 | 13:36:59 收到手机发送的 `test123\r\n`（9 bytes），电脑返回对应 ACK | 通过（原 28 候选） |
| 手机回 Home | 13:37:27 电脑记录 `remote_closed` | 通过（原 28 候选） |
| 手机客户端、电脑服务端 | `rf-server-01.jsonl` 与最终 `rf-server-final.jsonl` 均记录手机主动连接及电脑发送帧 | 两种连接方向均已建立 |
| 最终候选键盘按下/抬起 | 13:49:36 电脑依次收到 `KD28\r\n`、`KU28\r\n`，各 6 bytes，并分别返回 ACK | 通过（修复后最终候选） |
| 最终候选普通发送 | 13:50:02 电脑收到 `LOCK123\r\n`（9 bytes）并返回 ACK | 通过 |
| 屏幕关闭 | 13:50:45 电脑记录 `remote_closed`，此前累计接收 21 bytes | 通过 |
| 解锁并返回应用后自动重连 | ADB 确认 Awake、锁屏 `showing=false`；未点击连接，`rf-server-final.jsonl` 于 13:54:38 建立 session 3，`resume-after-lock-current.json` 显示接收 `PC_TO_PHONE` | **通过** |
| 恢复后的双向收发 | 旧电脑测试端按期限结束后，应用在前台自动重试连接新电脑服务端；`rf-reconnect-final.jsonl` 的 session 2 于 13:58:25 收到 `RECONNECT123\r\n`（14 bytes），返回 `PC_ACK:12:3dee1edc11215754`；`reconnect-ack-ui.json` 同时显示手机发送内容和回复 | **通过** |
| 服务关闭后 Home 返回 | `service-after-home-screen.json` 的服务开关 `enable_iconnect` 仍为 OFF | **通过** |
| 服务关闭后冷启动 | 测试执行 FORCE STOP 后重新启动，`service-after-cold-start-service.json` 的服务开关仍为 OFF | **通过** |
| 中文→英文→中文与重建 | `language-en-confirmed.json` 和 `language-en-service.json` 显示 English 界面；`language-zh-restored.json` 和 `language-zh-service-off.json` 确认中文恢复。服务开关在两次语言重建后均保持 OFF | **通过** |
| 恢复服务开启 | `service-restored-on.json` 的服务开关为 ON；`final-restored-logcat.txt` 于本地时间 22:03:54（UTC 14:03:54）记录 `accept()` 恢复监听 | **通过** |
| 设备标题 | 删除把真实设备名覆盖成 `demo` 的赋值后，真机界面显示 **ZZF-01** | 通过 |

`rf-server-final.jsonl` 在 13:55:46 记录 `fixture_limit_reached` 并移除 SDP，是电脑测试端达到预设 600 秒期限后正常结束，不能归因为应用断连缺陷。恢复结论同时依据解锁状态、未点击连接的操作记录、手机 UI 和恢复后的真实收发，不仅依据单个 `connected` 事件。

这些结果验证当前 Mi 11 与该 Windows 对端上的连接、收发和已记录的生命周期动作；不覆盖所有厂商、首次配对/拒绝、系统版本、安全锁屏组合或长时间压力场景。

## 键盘配置覆盖验证

1. 在修复前的原 28 版界面，为第一个按钮保存测试配置：名称 **UpgradeKey**、按下 **KD28**、抬起 **KU28**。该版按钮资源 ID 为 `0x7f090082`。
2. 同签名覆盖安装修复后的 28 版，不清数据。界面仍显示 **UpgradeKey**。
3. 实际操作该键，电脑收到 **KD28** 和 **KU28**，并返回对应 ACK。
4. `post-fix-keyboard.json` 确认保存结果为 `buttonid=1`，名称和两个动作不变；数据库版本 **4**、完整性检查 **ok**。

修复复用 `buttonId` 保存固定 slot **1–12**，不新增数据库字段；兼容已核实旧资源 ID，只转换选中的记录，保留未知 ID 和落选重复行。设备自己的配置优先，默认配置仅在缺失 slot 时回退；编辑默认回退会建立当前设备的独立记录。

| 旧版本 | 证据 | 验证范围 |
|---|---|---|
| 23 / 2.3 | 手机实际旧 APK：12 个 ID 均已解析；LitePal 版本 2，四个旧模型保留 | 映射有纯 Java 测试；手机原数据库为空，未验证真实既有用户配置 |
| 27 / 2.7 | 本地旧 `app/release/app-release.aab` 解析确认版本 27；12 个 ID 与原 28 完全相同，LitePal 版本 4 | 现有别名映射静态覆盖；没有安装该 27 包做真机升级 |
| 原 28 / 2.8 | UI 创建 UpgradeKey 后覆盖修复版，DB 与真实收发共同验证 | **本次真实配置保留样本** |
| 24 | 尚未取得实际 APK/AAB | **未验证，不推测其按钮 ID** |

## 稳定性观察与未测范围

`final-restored-logcat.txt` 未见 FATAL 或 ANR。`app-exit-info.txt` 中本次日期的两个退出均为测试执行的 **USER REQUESTED / FORCE STOP**，没有本次 crash/ANR 退出记录；另有 2026-03-30 的历史 LOW_MEMORY 退出，不归入本次结果。

日志仍有 MIUI 警告和慢帧：`final-screen-off-logcat.txt` 记录一次 **566ms doFrame**，以及启动附近 **754ms OpenGLRenderer 渲染**。未进行完整性能分析、长时间压力或功耗测试，不能据此宣称完全不卡顿。运行状态中可见 GMS/数据传输使用的普通后台服务，观察到 `startForegroundCount=0`；这与已移除蓝牙前台服务一致，不表示应用没有任何 Service。

本次未覆盖真实 **Android API 23**、通过 Google Play 安装的签名 release、完整购买/取消/恢复/退款，以及所有厂商、首次配对/拒绝和安全锁屏组合。版本号 **23 / 2.3** 的旧 APK 不等于 API 23 系统验证；本次手机始终为 API 34。

## Billing 与发布边界

Mi 11 上已安装 Google Play 和 Google Play services，但检查时均处于 **`enabled=2` 停用状态**。Billing 返回 **code 3 / unavailable**。本次没有完成真实购买、取消、恢复或退款，不能将支付不可用归为购买流程已验收。

2.8 的前台服务/通知权限移除没有变化；旧内部测试 27 和正式 24 尚未被新包替换，Console 前台服务声明仍受旧有效包阻塞。上传证书已核对匹配，但密码、密钥库完整性及实际签名未验证，当前 AAB 仍 unsigned。Data safety 和正式版本说明仍是已保存、未送审状态，详见 [Play Console 记录](PLAY_CONSOLE_RELEASE_STATUS.md)。

## 原始证据与后续补充

本地忽略目录 `.scratch/real-device-2026-09-09/` 保存原 APK、数据备份、构建日志、JSONL 和 UI/数据库 JSON。已核对的主要文件：

- `installed-v23.apk`、`backup-metadata.json`、`pre-upgrade-db-summary.json`
- `final-candidate-build.log`
- `rf-client-01.jsonl`、`rf-server-01.jsonl`、`rf-server-final.jsonl`、`rf-reconnect-final.jsonl`
- `key-saved.json`、`keyboard-after-upgrade.json`、`post-fix-keyboard.json`
- `home-connect-final.json`、`lock-final-typed.json`、`lock-before-screen-off.json`
- `resume-after-lock-current.json`、`reconnect-ack-ui.json`
- `service-after-home-screen.json`、`service-after-cold-start-service.json`
- `language-en-confirmed.json`、`language-en-service.json`、`language-zh-restored.json`、`language-zh-service-off.json`
- `service-restored-on.json`、`final-restored-logcat.txt`、`final-screen-off-logcat.txt`、`app-exit-info.txt`
- `final-service-screen.png`

本次结束时，手机保持最新 debug 28 安装，语言已恢复 **zh-CN**，服务开关恢复开启（`bluetooth_serveruser_enabled=true`），自动重连开启，并保留用户开启的充电常亮值 **15**。最终截图目视确认中文服务界面、两个开关和正常底部导航。电脑设备测试历史和 **UpgradeKey** 示例按钮留在应用中；没有清除用户数据，Google Play/GMS 停用状态未修改。所有有时限的 Windows RFCOMM 测试端已退出（exit 0），SDP 清理均成功。

后续测试应补齐上述未测范围，新增日期和对应证据，不覆盖本次原始结果。原始日志应结合用户动作与 UI 证据归因；仅出现 `connected` 不能单独证明某个生命周期场景通过。
