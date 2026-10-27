# Release readiness — 2.8

## 2026-10-13 优化变更的验收范围

本轮软件修复和本地验证已完成：126 项 JVM、70 项脚本、API 23 的 58 项 Debug 测试（另 1 项平台条件跳过）、API 36 的 59 项 Debug 测试、临时签名压缩 Release 的 2 项数据库/实际调试页面重建测试通过；Debug/Release/AAB 构建及 Lint 通过，Lint 为 0 错误。详细范围和产物身份见 [优化计划](OPTIMIZATION_PLAN.md) 及 `OPTIMIZATION_VERIFICATION_2026-10-13.json`。本轮全部 Android 构建使用离线审计配置，未构建新的生产密钥签名候选。下面 2026-09 的真机、签名和 Play Console 记录保留为历史证据，不能推断新代码或当前 Console 已通过。

新的验收项包括：网络恢复/进程结束后的购买确认及退款状态、IMMEDIATE 更新返回前台继续、云备份与设备转移仅保留语言和服务开关、R8 后 LitePal 原字段/schema 与旧库升级、API 23/31/36 生命周期、长时间 RFCOMM/大流量 UI 和性能 trace。没有可用 Play/真机环境的项必须继续标注待验收。本轮没有外部上传、送审、真实购买或正式发布。

备份明确排除所有数据库和按 MAC 的配置；宏与历史共用 DB，由用户主动导出/导入恢复。Billing 私有 token/缓存及 WorkManager 数据库同样排除。增加的 WorkManager 只处理短确认任务，合并 Manifest 必须保持无 `FOREGROUND_SERVICE` 权限和 SystemForegroundService。

记录日期：2026-09-10。候选已改为仅前台蓝牙通信；最新签名构建、97 项 JVM 测试和 Lint 已完成。本次 Mi 11 真机回归已结束：双向 RFCOMM、键盘配置覆盖保留、前台退出断连、解锁后自动重连与收发恢复、服务关闭后 Home/冷启动保持、中英文切换均通过。2026-09-10 已暂停旧内部测试 27，FGS 问题消失；新的 28 内部 draft 已通过 API commit 保存。该次省略 review 参数曾意外将原三项更改送审，现已通过 Console 撤回，当前三项均尚未提交送审、无审核中栏目。正式 24 未改动；内部测试网页已确认 2.8 (28) 为草稿，轨道仍暂停、无效。此前模拟器回归保留原有范围，支付、真实 API 23、签名 Play 安装及性能全面验证仍未完成。自动上传进度见[操作记录](PLAY_CONSOLE_RELEASE_STATUS.md)，本页中的“通过”不代表正式发布。

## 发布候选

| 项目 | 当前值 |
|---|---|
| applicationId | `com.zzf.bluetoothsmp` |
| versionCode / versionName | **28 / 2.8**，由 `app/build.gradle` 管理 |
| Console 版本检查 | 旧内部测试 **27 / 2.7 已暂停**，当时 UI 显示无效、测试人员无法获取；正式 **24 未改动**。旧 28 edit 已过期；新 edit `14548134092201909362` 已 **committed**，保存 **internal / draft / 28**，网页已确认草稿存在、轨道暂停，未执行恢复轨道 |
| compileSdk / targetSdk | **36 / 36** |
| minSdk | **23**（Android 6.0） |
| Android Gradle Plugin / Gradle | **8.13.2 / 8.13** |
| Google Play Billing | **8.3.0** |
| 语言交付 | AAB 不拆分语言；中英文资源随应用一起安装，切换语言不依赖额外下载 |

应用退到后台或锁屏时停止发现、监听和所有连接，并暂停自动重连。回到前台或解锁后按持久化服务开关恢复监听，按原自动重连设置恢复客户端重连。应用内聊天页面跳转与配置重建保留当前通信；不会在后台显示连接通知。已移除 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE` 和 `POST_NOTIFICATIONS` 权限及服务声明。

中英文商店发布说明分别见 [中文](RELEASE_NOTES_2.8_zh-CN.txt) 和 [English](RELEASE_NOTES_2.8_en-US.txt)。

## 已确认的验证结果

签名流水线执行 `testDebugUnitTest lintRelease writeReleaseUploadMetadata`，日志为 `release-artifacts/20260909T151735Z-d181c3d7/build.log`，**BUILD SUCCESSFUL in 1m28s**；97 项 JVM 测试零失败，Release Lint 0 errors / 159 warnings，逐文件验签通过。此前 `.scratch/real-device-2026-09-09/final-candidate-build.log` 为真机候选的构建记录。`remediation-final-v28-foreground-validation.log` 中的 16 项 instrumentation 仅代表当时 API 36.1 模拟器候选，没有在最新源码或 Mi 11 真机上重跑。

| 检查项 | 最近确认结果 |
|---|---|
| JVM 单元测试 | **97 项通过，0 failures / 0 errors / 0 skipped**；新增 13 项键盘资源 ID 映射、重复记录选择、默认回退和幂等迁移测试 |
| Android Lint | **0 errors / 159 warnings**；仍有警告，不表述为零告警 |
| `connectedDebugAndroidTest` | **此前 16 项通过**，当时的 API 36.1 模拟器候选；本次未在新候选或真机重跑。覆盖后台清理、重连意图保留、真实 Main→Chat→Main、Service 配置重建、旧接收回调和旧中央消息跨代次拒绝 |
| 服务开关修复 | 持久化用户启停意图；冷启动及回到前台均遵守设置；修改 UUID 不会重新启用已关闭的监听。Mi 11 上关闭后 Home、冷启动及语言重建仍为 OFF，最终恢复 ON 并确认重新监听 |
| 发送取消修复 | 取消当前连续发送批次的积压任务，保留其他发送者；取消消息不再进入待处理历史/日志/计数 |
| 断开清理修复 | 先关闭 socket，再清理缓冲流，覆盖阻塞写持有缓冲流锁的回归路径 |
| 国际化修复 | 首次启动采用系统语言；禁用语言拆分；模拟器英文→中文（zh-CN）→英文（en-US）通过。Mi 11 中文→英文→中文通过，服务开关在语言重建中保持 OFF，最终恢复 zh-CN |
| 日志文件权限 | 使用系统文件选择器，无需广泛存储权限 |
| 仅前台通信 | 应用级 Activity 计数、锁屏暂停与前台 generation 校验；旧 connect/accept、宏、待发消息和接收失败回调均校验前台代次；此前自动化回归通过；Mi 11 真实 RFCOMM 两种连接方向已验证，Home 和屏幕关闭时对端观察到断连；解锁返回后未点击连接即自动重连，恢复后 `RECONNECT123` 及 ACK 双向收发通过 |
| 键盘升级修复 | 固定 slot 1–12，兼容已核实 23 与 27/原 28 资源 ID；真实原 28 配置覆盖到修复版后名称和 KD28/KU28 保留，DB `buttonid=1`、integrity `ok`；23 为静态资源表和纯 Java 验证，24 未取得 APK |
| Mi 11 真机回归 | API 34，同签名从 debug 23→28 覆盖；先备份原 APK/数据库/prefs，没有卸载或清数据。手机作服务端发送 `test123`、Home 断连；作客户端发送 `KD28`/`KU28`/`LOCK123` 并获 ACK，解锁返回自动重连后 `RECONNECT123` 及 ACK 通过；真实设备标题显示 `ZZF-01`，详见[实测记录](REAL_DEVICE_TEST_2026-09-09.md) |
| 模拟器人工 UI 回归 | 全新安装只请求附近设备；未请求通知授权、未启动蓝牙前台服务；HOME 与熄屏时监听关闭，返回应用/唤醒后恢复；服务关闭后 HOME/冷启动/横竖屏仍关闭；最终 crash buffer 为空 |
| 真机稳定性观察 | 最终日志未见 FATAL/ANR；本次两个进程退出均为测试操作的 FORCE STOP。仍有 MIUI 日志警告及慢帧记录（566ms doFrame、启动附近 754ms 渲染），性能未全面验证；普通 GMS/数据传输后台服务存在，观察到 `startForegroundCount=0`，不声称没有任何 Service |

人工回归证据保存在本地审计目录的 `foreground-*.json/txt`：启用监听时 HOME 于 10:38:07 关闭 socket 12，返回于 10:38:22 建立监听 socket 13；熄屏至 Asleep 于 10:38:40 关闭 socket 13，唤醒回应用于 10:38:56 建立监听 socket 14；关闭开关于 10:39:13 关闭 socket 14，后续 HOME/冷启动/旋转均没有新监听。这些是模拟器监听生命周期证据，不是对外蓝牙传输验证。

已验证签名产物：`release-artifacts/20260909T151735Z-d181c3d7/bluetoothsmp-28.aab`，5,527,974 bytes，798 项 AAB payload 的签名均匹配登记的上传证书。SHA-256：

```text
55E51C71A102E3826279F4BDF9082BC6434218DA9B9214064B06FDB4D042278D
```

此前 unsigned 审计产物仍位于 `app/build/outputs/bundle/release/app-release.aab`，5,480,621 bytes，不能上传；其历史 SHA-256 为：

```text
7DD44A9201FA1B1988C0C945D724A49FA7A917526F27EE6C7BE2CBEACA0702B2
```

本次真机安装的 debug APK 为 `app/build/outputs/apk/debug/app-debug.apk`，13,581,017 bytes，SHA-256：

```text
1A7D5E60C1474E597DB97B73B3E59E11B6FEE89762FA318D01DC57C78213A607
```

## 上传签名配置

`app/build.gradle` 从构建进程环境读取以下四个变量，不读取仓库中的密码文件：

| 环境变量 | 内容 |
|---|---|
| `BLUETOOTH_UPLOAD_KEYSTORE` | 上传密钥库的文件路径，建议使用仓库外的绝对路径 |
| `BLUETOOTH_UPLOAD_STORE_PASSWORD` | 密钥库密码 |
| `BLUETOOTH_UPLOAD_KEY_ALIAS` | 密钥库中实际使用的上传密钥 alias |
| `BLUETOOTH_UPLOAD_KEY_PASSWORD` | 对应私钥的密码；不要假定与密钥库密码相同 |

- 四项均未设置或均为空白：允许生成 **unsigned 审计构建**，不可把它当作可上传的签名 AAB。
- 四项均非空：启用 release 的 `upload` signingConfig。原始密码值不做裁剪。
- 只有部分非空：配置阶段失败，只列出缺失变量名，不输出任何配置值。补齐四项，或全部清除后执行 unsigned 审计构建。

2026-09-09 已用用户提供的密码验证仓库外 `upload.jks`（PKCS12、alias `upload`），完成实际私钥签名及全部 AAB payload 验签；上传证书与 Play Console 一致。密码通过隐藏输入保存到当前 Windows 用户的仓库外 DPAPI 加密配置，未写入仓库或日志。后续直接运行 `./release.cmd`，用法见[发布脚本说明](RELEASE_AUTOMATION.md)。

PowerShell 可以通过交互输入为当前会话设置环境，避免把密码写进命令历史：

```powershell
$env:BLUETOOTH_UPLOAD_KEYSTORE = Read-Host 'Upload keystore path'
$env:BLUETOOTH_UPLOAD_KEY_ALIAS = Read-Host 'Upload key alias'
$storeSecret = Read-Host 'Keystore password' -AsSecureString
$env:BLUETOOTH_UPLOAD_STORE_PASSWORD = [System.Net.NetworkCredential]::new('', $storeSecret).Password
$storeSecret.Dispose()
$keySecret = Read-Host 'Private-key password' -AsSecureString
$env:BLUETOOTH_UPLOAD_KEY_PASSWORD = [System.Net.NetworkCredential]::new('', $keySecret).Password
$keySecret.Dispose()

.\gradlew.bat --no-daemon bundleRelease
jarsigner -verify -verbose -certs app\build\outputs\bundle\release\app-release.aab
```

发布脚本会逐文件验签并核对固定上传证书，本页已记录上述签名产物通过。不要将密码作为 `-storepass`、`-keypass` 命令行参数或输出到日志。CI 应通过受保护的 secret 配置注入同名环境变量。

构建完成后，清理当前 PowerShell 会话中的四个环境变量：

```powershell
foreach ($variable in @(
    'BLUETOOTH_UPLOAD_KEYSTORE',
    'BLUETOOTH_UPLOAD_STORE_PASSWORD',
    'BLUETOOTH_UPLOAD_KEY_ALIAS',
    'BLUETOOTH_UPLOAD_KEY_PASSWORD'
)) {
    Remove-Item -LiteralPath "Env:$variable" -ErrorAction SilentlyContinue
}
```

`.gitignore` 已排除常见密钥库、私钥、签名密码配置文件和 `.scratch/`。不要在其他受跟踪文件中保存凭据；忽略规则不能撤回已经提交的秘密。

## 发布前待办

| 状态 | 待办及验收标准 |
|---|---|
| **通过（签名）** | 本机加密签名配置已保存；实际签名构建成功，798 项 AAB payload 的签名与 Console 上传证书一致，大小及 SHA-256 见上方 |
| **通过（发布构建）** | 独立构建目录中的 97 项 JVM 测试、Release Lint 和签名 bundleRelease 通过，候选为 28 / 2.8；自动上传状态见[操作记录](PLAY_CONSOLE_RELEASE_STATUS.md) |
| **通过（模拟器）** | 完成中英文双向切换、Service 页面横竖屏、服务开关、HOME/返回、force-stop 冷启动与熄屏/唤醒回归。模拟器没有安全 PIN 锁屏，不能把熄屏结果等同于所有安全锁屏/厂商行为已验证 |
| **通过（本次真机范围）** | Mi 11（API 34）与 Windows 对端已完成双向真实 RFCOMM、收发/ACK、Home 及屏幕关闭断连、解锁返回自动重连与恢复后双向收发、键盘覆盖保留、服务关闭后 Home/冷启动保持和中英文切换。真实 API 23、签名 Play 安装、首次配对/取消、其他 Android/厂商、安全锁屏组合和长时间压力仍未充分覆盖；慢帧仍需性能专项验证，见[实测记录](REAL_DEVICE_TEST_2026-09-09.md)及[回归清单](BLUETOOTH_CONNECTION_REGRESSION_CHECKLIST.md) |
| **FGS 提示已消失 / 新 edit 校验通过** | 2026-09-10“查看 app bundle/APK”弹窗仅列内部测试 27（2.7），没有正式 24；此前将 24 也归因为本次警告来源的推断已更正。暂停内部测试后发布概览 FGS 问题消失，正式 24 未改动；不要直接恢复旧 27，否则警告可能重现。2.8 合并 Manifest 无前台服务/通知权限，新 edit 已 validate 并 commit 成功 |
| **已保存 / 已撤回审核 / 待送审** | 已导入并保存[数据安全 CSV](release/2.8/data-safety.csv)，按实际 Firebase 配置申报六类数据。该项曾随 API commit 意外送审，现已撤回到尚未提交送审，未审核生效，不能把已保存草稿视作公开标签已更新 |
| **隐私已发布 / 广告 ID 已核实** | 双语隐私政策已发布并验证 HTTP 200；GA 资源广告个性化已关闭为 0/307，Google 产品和服务共享保留。Console 广告 ID 声明为“使用：是”，用途仅勾选“分析”，与当前 SDK/Manifest 一致，无需修改。Consent 信号停用不等于 denied 或用户同意，详见[操作记录](PLAY_CONSOLE_RELEASE_STATUS.md) |
| **API 内部草稿已保存 / 网页已确认** | 新回执 `release-artifacts/receipts/20260910T001900Z-fgs-paused.json` 为 committed，保存 internal draft 28，仍为上述 5,527,974 bytes、SHA-256 `55E51C71A102E3826279F4BDF9082BC6434218DA9B9214064B06FDB4D042278D` 的签名包。首次 commit 因 `changesNotSentForReview` 不适用返回 400，受控 resume 省略该参数后成功，也实际触发原三项一起送审，现已撤回；ERROR_IF_IN_REVIEW 未阻止此副作用。旧 403 失败回执及 GET 400 `This edit has expired, please create a new Edit.` 证据保留；未发布正式版或执行恢复轨道 |
| **意外送审已撤回 / 当前未在审核** | API commit 后原三项进入审核；已在 Console 点击“移除更改”，确认全部退回。当前仅 Data safety 1 项和开放测试国家/地区 2 项处于“尚未提交送审”，页面显示“更改现在可以送审”，无审核中栏目、无 FGS 警告；正式最后发布日期仍为 2026-07-25。新内部草稿及轨道暂停状态已获网页确认 |
| **阻塞（真机 Billing）** | Mi 11 已安装 Google Play/GMS，但均为 `enabled=2` 停用状态，Billing 返回 code 3 unavailable；尚未完成真实购买、取消、恢复或退款。需在可用 Play 环境及内部测试轨道验收，见 [Billing 文档](SUPPORTER_BADGE_BILLING.md) |

数据披露核对参考：[Firebase 数据披露指南](https://firebase.google.com/docs/android/play-data-disclosure)、[Analytics 数据披露指南](https://support.google.com/analytics/answer/11582702)。这些链接用于核对实际配置，不替代 Console 中的真实申报结果。

广告个性化默认信号已在 Manifest 中设为 `false`（`firebase_analytics_default_allow_ad_personalization_signals`）；Analytics/Crashlytics 统计与现有 AD_ID 配置保留。线上 Analytics 资源设置和 Console 申报以最终操作记录为准，不能把这项默认值表述为关闭全部数据采集。
