# BLE 低功耗支持

入口：首页工具栏的 **BLE 低功耗**（空间不足时位于更多菜单）。经典蓝牙 SPP 继续使用原首页、服务和聊天页面。

## 已实现的中央设备 / GATT 客户端功能

| 功能 | 使用方式 |
| --- | --- |
| BLE 扫描 | 每次最多 15 秒，按 MAC 去重、RSSI 排序，最多保留 256 台设备；扫描启动至少间隔 6 秒 |
| 扫描筛选 | 名称、MAC、广播服务 UUID 的子串，以及最低 RSSI |
| 广播详情 | 长按设备：名称、MAC、RSSI、可连接标志、广播标志、TX Power、服务 UUID、厂商数据、服务数据和原始 HEX |
| 连接和断开 | 点击可连接设备；顶部手动重新连接；连接超时 20 秒，服务发现超时 15 秒 |
| GATT 浏览 | 展开服务查看特征、属性、实例编号和描述符，重复 UUID 的实例分别显示 |
| 读取 | 支持特征和描述符读取，结果显示在收发日志 |
| 写入 | 选择任意支持写入的特征，支持 Write Request 和 Write Without Response；仅显示本机提交成功的无响应写入标为 `SENT_UNCONFIRMED` |
| Notify / Indicate | 独立选择通知或指示、取消订阅；同时配置本地通知与远端 CCCD，CCCD 写入成功后才标记 RX |
| UART 快捷配置 | 识别 Nordic UART Service 与 FFE0/FFE1，验证属性后选择 TX、订阅 RX；其他协议手动选特征 |
| 编码与结尾 | HEX、UTF-8、ASCII、GBK；文本可选无结尾 / CRLF / LF / CR，HEX 永远不添加文本结尾 |
| MTU 和分包 | 请求 23–517，使用实际协商结果；默认不分包，透传可主动启用，每包最多 `min(MTU - 3, 512)` 字节 |
| 单次和循环发送 | 单次载荷最多 64 KiB；循环间隔 100–60000 ms，在上次完整发送回调后计时，不累计无限待发任务 |
| 连接调试 | 读取连接 RSSI、请求均衡 / 高速 / 低功耗优先级、发起系统配对 |
| 日志 | 时间、特征地址、状态、HEX / UTF-8 预览、收发字节计数、暂停显示、清空、TSV 导出 |
| 生命周期 | 旋转保留客户端与输入；离开 BLE 页面、应用退后台、锁屏关闭扫描和 GATT，返回后手动重连 |
| 本地偏好 | 记住每个 MAC 上选择的 TX 服务 / 特征及实例；重新发现服务时验证匹配后恢复，通知需重新订阅 |
| 中英文 | 跟随现有应用语言设置 |

## 操作示例

1. 打开 BLE 页并扫描，点击设备连接。
2. 切到“服务”，展开设备的服务。点击可写特征，选择“设为发送特征”。点击接收特征，选择 Notify 或 Indicate。
3. 到“收发”输入 `01 03 00 00` 并选择 HEX；或选择 UTF-8 / GBK 输入文本。按设备协议选择结尾和写入类型。
4. 对 UART 透传设备，可点击顶部 UART 快捷配置。需要更大吞吐量时请求 MTU，并按协议开启分包。
5. 导出先捕获保留范围内的完整原始字节到缓存，文件关闭后打开系统文件选择器。打开文件选择器会离开前台并断开 BLE，但不会改变已捕获的导出内容。

## 边界和语义

- 本轮实现手机作为中央设备的 BLE 调试能力。手机作为外设的 GATT Server、广播模拟、DFU、BLE Mesh、专有设备协议解析和 BLE 自动重连尚未实现。
- BLE 收发独立于现有 SPP 的 LitePal 聊天历史、宏、键盘模式和 `StaticObject.bluetoothSocketMap`；不要将 BLE 地址交给 SPP 的 RFCOMM 连接器。BLE 日志仅在当前页面会话内有界保留，可显式导出。
- Write Request 的成功表示 GATT 写入响应成功，不代表外设业务动作已完成。Write Without Response 没有远端 ATT 确认，字节统计反映本地栈成功回调，不承诺设备收到。
- BLE 特征值有自己的边界，不能把任意长特征值自动当串口流。默认超出一包即拒绝；仅当设备协议支持透传拼包时开启分包。通知按原始 GATT 值显示，不套用 SPP 的 CRLF 分帧。UTF-8 预览逐值解码，跨通知拆开的多字节文本以导出的原始 HEX 为准。
- GATT 请求最多 128 条、2 MiB 估算内存，包含正在执行的请求。队列满仅拒绝新请求，不关闭健康连接。超时为避免迟到回调误匹配后续操作而关闭会话；超时写入结果可能未知，不能自动重发。
- 循环停止后，不再提交后续轮次；已开始的底层写入无法撤回，其回调返回后不再继续该轮剩余分包。切换发送特征也会停止循环。
- 日志最多 512 条 / 1 MiB（含元数据），通知回调最多积压 256 条；溢出计数可见。屏幕最多显示最近 100 条、每条最多预览 512 原始字节，每 100 ms 合并刷新；导出包含捕获时仍保留的完整字节。暂停只暂停显示，不暂停接收或容量淘汰。
- Android 14 起系统首次 MTU 请求按 517 发起协商，实际结果由对端决定；本客户端避免再次请求导致等待无回调。连接优先级是请求，不保证外设接受。
- Android 6–11 需要定位权限和系统定位开关。Android 12+ 使用现有扫描 / 连接权限；沿用 `neverForLocation`，部分 Beacon 可能被系统过滤。BLE 硬件为可选功能，不排除仅支持经典蓝牙的设备安装。
- 每设备偏好包含 MAC，受现有备份白名单排除；导出缓存也不进入备份。BLE 不新增前台服务、后台扫描或后台连接权限。

## 验证

自动检查命令（`offlineAudit` 禁止 Firebase 采集和 mapping 上传）：

```powershell
.\gradlew.bat -PofflineAudit testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest lintRelease assembleRelease
adb -s <serial> shell am instrument -w -e class com.zzf.bluetoothsmp.ble.BleClientInstrumentedTest,com.zzf.bluetoothsmp.ble.BleInstrumentedTest com.zzf.bluetoothsmp.test/androidx.test.runner.AndroidJUnitRunner
```

新增单元测试覆盖 FIFO 串行、执行中请求占用容量、超载拒绝、断开清理、MTU 23/247/517 分包重组、HEX 不附加结尾、文本编码 / 容量、UUID 转换、日志条数 / 字节上限和原始字节快照。新增设备测试覆盖无 BLE 外设的页面启动、旋转恢复、导出关闭后完整性及 SavedStateHandle 恢复。

2026-10-24 本地验证：136 项单元测试通过（含 10 项新增 BLE 测试）；Debug 构建、R8 优化的离线 Release 构建、Debug / Release lint、测试 APK 编译通过。Android 6 / API 23 模拟器上 `BleInstrumentedTest`、`ForegroundBluetoothInstrumentedTest`、`ForegroundActivityHandoffInstrumentedTest`、`BluetoothSessionDispatchInstrumentedTest` 共 11 项通过；检查了竖屏、横屏与键盘弹出后可滚动到发送控件。Android 16 模拟器启动停留在 offline，未形成该版本的运行通过证据。当前没有连接物理 BLE 外设，下面的硬件验收仍待执行。

### BLE 实现复查与修复（2026-10-24）

| 问题 | 修复行为 |
| --- | --- |
| 取消订阅在远端确认前关闭本地通知，失败时可能丢失仍在途的数据 | 启用时先注册本地、再写 CCCD；禁用时先写远端 CCCD，成功后才关闭本地 |
| CCCD 失败回滚 / 禁用后的本地配置失败被忽略 | 回滚到本次操作开始前的本地状态；无法恢复一致状态则关闭连接并清空订阅和待发请求 |
| 同一特征重复进入主线程队列的回调可能确认下一包 | 每个底层请求单独标记代次和执行状态，回调进入与处理时校验，完成时立即失效 |
| 分包直接在写回调内继续，可能早于已经排队的 MTU 更新 | 后续分包重新排队，先应用已到达的 MTU 更新，再按新 MTU 计算包长；无主动请求时的 MTU 变化也更新会话状态 |
| 非主线程发起的延迟暂停可能关闭后来建立的新连接 | 暂停捕获当时的 GATT 和扫描对象，只清理对应实例 |
| 平台启动错误码统一丢成 -1，部分致命故障仍显示连接可用 | 保留同步启动错误码；蓝牙关闭、GATT 服务不可用或权限撤销时清理；未知启动异常按结果未知关闭，不自动重试 |
| 旧描述符、非法订阅模式或发送参数缺乏完整校验 | 校验当前服务树中的特征 / 描述符实例、读写属性、订阅模式、载荷和循环间隔 |
| 重连后发送特征属性改变，但 UI 沿用旧写入模式 | 按新的特征对象刷新发送模式；日志追加 MAC 与连接会话编号，避免切换设备后混淆历史记录 |

`BleGattConnection` 将平台读写封装为单独边界，`AndroidBleGattConnection` 保留 Android 6–12 的旧读写 API 与 Android 13+ 的带值 API 分支。同步返回的 `BluetoothStatusCodes` 与异步 ATT 回调状态分开处理，不能将 ATT 状态 6 误当作缺少连接权限。

本轮最终验证：136 项单元测试、API 23 模拟器上 30 项 `BleClientInstrumentedTest` 可控 GATT 回调测试、2 项 BLE UI / 导出测试及 9 项原有前台 / 消息分发回归全部通过（共 41 项设备测试）；Debug / R8 Release 构建与两种 lint 通过。可控测试覆盖连接启动 / 发现失败、超时、回调复制及错配、分包完整性与部分失败、MTU 增减、CCCD 顺序及失败回滚、队列条数 / 字节容量、停止或替换循环、服务变化、权限撤销、锁屏等价前台门控、ViewModel 清理和旧连接回调。测试替身验证客户端状态机，不能证明真实射频、厂商协议栈或配对交互已通过；真机验收仍按下表执行。

### 真机验收（不能用模拟器 UI 测试替代）

- Android 6–11 与 Android 12+：拒绝 / 重新授予权限、关闭蓝牙 / 定位、扫描超时、广播更新、不可连接广播。
- Nordic UART 和 FFE0/FFE1 外设各一台：服务发现、读、两种写入、Notify、Indicate、取消订阅与 CCCD 失败。
- MTU 23、247、Android 14+ 的 517 协商：20 / 244 / 512 字节边界及跨包二进制精确重组，无响应写入对端丢包的可观测性。
- 普通非透传特征超一包时不写入；队列满时连接保持，写失败报告已成功分包字节数。
- 循环发送中断开、切换设备、切换特征、停止、锁屏、退后台；旧回调不污染新连接，不自动重发未知结果。
- 多通知特征、重复 UUID 实例、Service Changed 后重新发现、移除 / 重新配对、外设掉电。
- 连续通知下日志内存、丢弃计数、暂停、完整字节导出；文件选择器取消、旋转、进程重建、不可写目标。
- 现有经典蓝牙发现、连接、服务、聊天、Debug 与历史功能回归。

参考：[nRF Connect 功能说明](https://github.com/nordicsemi/Android-nRF-Connect)、[Android BLE 数据传输](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data)、[BluetoothGatt API](https://developer.android.com/reference/android/bluetooth/BluetoothGatt)、[蓝牙权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)。
