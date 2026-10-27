# 蓝牙插件 SDK v1

入口：主页工具栏 → 插件中心 → 安装内置 SPP / BLE 示例 → 运行。示例源码位于 `samples/plugins/classic`、`samples/plugins/ble`，类型声明位于 `plugins/sdk.d.ts`。示例只提供通用调试能力，不包含厂商协议。

## 包格式与身份

`.btplugin` 是 ZIP，必须包含 `plugin.json`、`signature.json` 和完整清单中的本地资源。源码清单示例：

```json
{
  "id": "example.controller",
  "name": "Controller",
  "author": "Example",
  "version": 1,
  "sdk": 1,
  "entry": "index.html",
  "capabilities": ["classic.scan", "classic.connect", "classic.io"]
}
```

打包工具填写 `authorKey`（Base64 X.509 DER P-256 公钥）和 `files`（相对路径 → 小写 SHA-256）。两个签名都采用 `SHA256withECDSA`，签署 **plugin.json 的原始 UTF-8 字节**，签名为 Base64 DER。清单自身、`signature.json` 不加入 `files`；其余每个文件必须列入。`signature.json` 含 `author`、`reviewKey`、`review`。修改清单空白也需要重新签名。

插件 ID 为小写点号或连字符分隔的名称，最多 100 字符；版本为递增正整数，SDK 固定 1。显示名称和作者最多 100 字符。身份由 ID 和作者公钥指纹共同组成。卸载后仍保留身份连续性，禁止换作者、降级或以同版本覆盖不同内容。

包上限 10 MiB，展开总计 32 MiB、512 个文件（包含两个元文件）。路径最多 240 字符，只支持 ASCII 字母、数字、点、下划线、连字符、正斜杠；禁止绝对路径、空段、`.`、`..`、大小写冲突、重复、目录项、链接、加密 ZIP、ZIP64、未列资源和原生代码。支持 html/js/css/json/svg/png/jpg/jpeg/webp/gif/ico/woff/woff2/ttf/txt。打包工具自动生成合规 ZIP，不要自行添加目录项。

内容标识为 `SHA256(plugin.json 原始字节)`，用于版本目录及撤下记录；下载校验另使用整个 ZIP 的 SHA-256。重新压缩或更换签名字节不能解除撤下。

Release 必须同时通过作者签名和内置审核根验证。Debug 允许未审核但作者签名有效的包；此开关是构建常量，正式版没有运行时开关。

## 页面与授权

HTML、JS、CSS、字体、图片必须全部打入包内。宿主在本地来源注入 `window.btAssistant`，无需加载 SDK 脚本。禁止网络、远程脚本、外链导航、iframe、Worker、WebSocket、WebRTC、文件、摄像头、麦克风、网页蓝牙等旁路；只允许宿主原生通信。Canvas、SVG、普通 DOM 页面可自由绘制。旧系统 WebView 不支持安全消息与文档起始注入时，显示更新提示，拒绝运行。

首次使用未授权能力会弹出原生授权框；新增能力需再次授权。连接目标必须由当前插件的扫描或已配对列表返回，首次连接该地址还需原生确认。系统蓝牙权限仍由 Android 管理。运行页原生栏提供断开、停止、撤销授权，插件不能覆盖。

| 能力 | 方法 |
|---|---|
| classic.scan | paired / scan / stopScan |
| classic.connect | connect / disconnect |
| classic.io | write 与 data 接收 |
| ble.scan | scan / stopScan |
| ble.connect | connect / disconnect |
| ble.gatt | services / read / write / subscribe / requestMtu / readRssi 与 data 接收 |

## 调用与事件

```javascript
const bt = window.btAssistant;
await bt.ready();
const off = bt.on('data', event => {
  console.log(event.transport, event.sessionId, event.bytes); // Uint8Array
});
const devices = await bt.classic.paired();
// 由页面让用户选择 devices 中的目标，再调用 connect。
const session = await bt.classic.connect({address: devices[0].address,
  uuid: '00001101-0000-1000-8000-00805F9B34FB'});
await bt.classic.write({sessionId: session.sessionId,
  bytes: new Uint8Array([0x41, 0x0d, 0x0a])});
off(); // 取消事件订阅
```

所有通信方法返回 Promise。单次写入为 1..65536 字节的 `Uint8Array`，桥接内部 Base64。SPP 是 RAW 字节流，没有隐式换行或消息边界，单次接收不等于完整设备报文；插件自行分帧。发送完成代表本地输出流完成，不代表外设执行成功。

BLE `connect` 等待服务发现完成；`services({sessionId})` 返回服务和特征实例。操作使用当前会话返回的 `handle`，不能用 UUID 代替，重连后句柄失效。`write` 默认有响应，超出 MTU-3 时必须显式 `split:true`；分块可能部分成功。`withoutResponse:true` 完成只返回 `SENT_UNCONFIRMED`，不承诺远端收到。`subscribe` 的 mode：0 关闭、1 Notify、2 Indicate，等待 CCCD 回调再完成。MTU 范围 23..517，实际值由系统决定。

扫描最长 15 秒，结果通过 `device` 事件送达；BLE 再次扫描受核心 6 秒节流限制，可选 `namePrefix`。广播中的 advertisement/manufacturerData/serviceData 是 Base64 字符串，服务 UUID 为字符串数组。普通 `data.bytes` 和读结果 `bytes` 已转换为 Uint8Array。

`host.getInfo()` / `host.getState()` 返回 SDK、语言、插件 ID、运行 ID、声明能力及两种连接快照。`state` 为连接变化，`data` 为数据，`operationComplete` 为蓝牙操作结束，`error` 为蓝牙操作错误（同一错误也会 reject Promise），`dataLoss` 表示连接因丢失数据关闭。接收处理器应快速返回；不要自动重发未确认的写入。

错误是带 `code`、`message`、`requestId` 的 Error。常见 code：`CAPABILITY_NOT_DECLARED`、`PERMISSION_DENIED`、`PERMISSION_REQUIRED`、`AUTHORIZATION_BUSY`、`FOREGROUND_REQUIRED`、`DEVICE_NOT_SELECTED`、`DEVICE_BUSY`、`BLUETOOTH_DISABLED`、`CONNECT_FAILED`、`CONNECT_TIMEOUT`、`SESSION_EXPIRED`、`SESSION_ENDED`、`STALE_CHARACTERISTIC`、`QUEUE_FULL`、`OPERATION_FAILED`、`OUTCOME_UNKNOWN`、`DATA_LOSS`、`VERSION_REVOKED`、`STORAGE_QUOTA`。`OUTCOME_UNKNOWN` 表示不能判断外设是否收到，不能安全自动重试。

## 状态、配置和生命周期

一次只运行一个插件、最多一个 SPP 或 BLE 连接；已有原生页面的连接及连接尝试返回 DEVICE_BUSY，不会接管。插件 SPP 不打开聊天页，不读写历史，不修改设备编码、分帧和重连配置。BLE 与原页面使用同一 `BleController` 和串行 GATT 队列。

`storage.get(key)` / `set(key,jsonValue)` / `remove(key)` 为独立 JSON 配置，键最多 200 字符，总计最多 1 MiB；缺失返回 null。更新保留配置，卸载只删除包和授权；详情页可单独清除配置。配置、地址授权、插件包均不进入系统备份。页面不使用 localStorage、IndexedDB 或任意文件。

旋转保留原生运行实例及当前 WebView，重建后查看 `host.getState()`。手动重载不重放旧请求。退出、停止、后台、熄屏、撤销授权均关闭通信；回前台也不自动重连。进程死亡后重新运行并手动连接。

桥接最多 64 个在途请求；单次请求 JSON 最多 128 KiB，较大配置应分成多个键保存；数据队列最多 256 条 / 1 MiB，等待 JS 确认的批次仍计入配额。溢出关闭通信并送 dataLoss；页面不确认批次超过 10 秒或渲染进程失效会停止插件。宿主请求截止 60 秒，SDK 截止 65 秒；旧请求不重放。SPP 复用现有 128 次发送 / 2 MiB 待发送队列。没有后台、服务端、BLE 外设、账号系统或代码编辑器。
