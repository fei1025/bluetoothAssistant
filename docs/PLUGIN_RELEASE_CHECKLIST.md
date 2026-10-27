# 插件平台发布验收与隐私变更

以下包含待发布的政策文案和待执行的验收，不能作为已经通过 Play 审核或真机验证的证明。

## 隐私政策增补草稿

中文：

> 蓝牙插件在设备本地运行。您授权后，插件可扫描附近蓝牙设备，读取设备名称、地址、广播、服务及特征信息，并与您确认的设备交换数据。插件不能访问应用聊天历史、其他插件配置或任意文件，也不能直接联网。插件配置保存在应用私有存储中；更新保留配置，卸载插件默认保留配置，您可在插件详情中单独清除。退出或应用进入后台时停止插件蓝牙通信。安装、更新和同步目录由应用通过 HTTPS 请求您指定的服务器或官方 GitHub 服务，这些服务可能按照其隐私政策处理连接 IP 地址和常规请求信息。已同步的撤下记录会阻止问题版本继续运行。

English:

> Bluetooth plugins run locally on your device. With your permission, a plugin can scan nearby Bluetooth devices, read their names, addresses, advertisements, services and characteristics, and exchange data with a device you confirm. Plugins cannot access chat history, another plugin's configuration, arbitrary files, or the network directly. Configuration is stored in app-private storage and retained on updates and, by default, after plugin removal. You can clear it separately in plugin details. Plugin Bluetooth communication stops when you exit or the app enters the background. Installation, updates and catalogue synchronization make HTTPS requests to your selected server or the official GitHub service. Those services may process connection IP addresses and ordinary request information under their own privacy policies. Synchronized revocations prevent affected versions from running.

发布前将适用文案合并到实际中英文隐私政策并核实公开 URL；此仓库内草稿不会更新线上政策。

## Data safety 核对记录

| 数据/路径 | 当前平台实现 | 发布前核对 |
|---|---|---|
| 蓝牙地址、广播、设备收发 | 用户授权后本机传递，不写入旧聊天历史；插件运行环境无法联网 | 真机抓包核实；禁止在 Analytics/Crashlytics 自定义字段中记录这些值 |
| 插件配置、目标授权 | 本机私有存储，不参与系统备份；卸载授权清除，配置默认保留 | 验证详情清除与系统备份排除 |
| 目录/插件下载 | 用户触发原生 HTTPS，GitHub 或用户指定主机可见连接 IP、路径及常规请求头 | 核对政策中的第三方服务披露和最新 Play 表单解释 |
| 原有 Firebase Analytics/Crashlytics、Billing | 沿用现有配置，不能因插件离线而声称 App 完全不采集 | 对照本次 Release merged manifest、依赖及现有 Data safety 声明逐项确认 |

插件的声明能力不等于 Android 新增权限。本次不添加蓝牙前台服务、后台位置、任意文件或安装 APK 权限。正式版只运行审核签名内容，但这不保证 Play 审核自动通过。提交时按实际二进制、政策及审核要求回答，不直接复用历史 2.8 已保存的表格。

## 审核演示步骤

1. 从主页工具栏进入插件中心，查看正式版安装说明，安装内置通用 SPP 示例；离线也能完成。
2. 打开示例，展示页面及原生停止/断开/撤销授权栏。扫描触发原生能力授权及 Android 权限，选择设备后显示原生设备确认。
3. 用测试 SPP 设备发送明确的 HEX 数据并接收，退出页面确认断开；展示原聊天历史没有插件数据。
4. 安装 BLE 示例，扫描、连接测试外设、展示重复 UUID 对应不同特征实例，按人工选择读写及 Notify/Indicate。
5. 展示用户确认更新、配置保留、卸载默认保留配置及单独清除。正式版导入未审核包失败，旧版本仍可运行。
6. 展示网页联网失败、后台关闭连接、撤销授权后重新授权、同步撤下后不能运行。记录实际候选 APK SHA-256、版本、设备与系统 WebView 版本。

## 必须完成的硬件验收

- SPP 真机：首次配对/取消、指定 UUID、二进制 `00 FF 0D 0A`、分段接收、无隐式换行、发送失败/结果未知、满队列不关闭健康会话、原页面设备占用。
- BLE 真机：重复服务/特征 UUID、MTU 协商与分块、读写/无响应写、Notify/Indicate、CCCD 失败、超时及迟到回调、通知洪峰关闭并报告数据丢失。
- 生命周期：旋转保留、后台/熄屏/安全锁屏关闭、回前台不自动重发或重连、撤销系统权限、渲染进程退出、强杀进程手动恢复。
- 升级：保留已有按钮位置/内容、聊天及设备偏好；插件新增能力重新授权，更新失败仍使用原版本，卸载/重装保持作者连续性及配置。
- 发布：API 23/31/36、过旧 WebView 提示、现代 WebView 离线运行、Debug 和 R8 Release、严格验签、脚本/JVM/Lint、官方目录同步/安装/更新/撤下全流程。

模拟器与 transport fake 覆盖核心时序和隔离，不能代替上述无线硬件行为。完成后保存本次候选证据；公开目录部署、隐私政策发布、Console 声明及 Play 送审应分别记录实际完成状态。
