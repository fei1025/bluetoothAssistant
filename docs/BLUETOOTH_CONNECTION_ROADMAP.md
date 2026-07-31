# 蓝牙连接完善计划

## 目标

把当前经典蓝牙 SPP 功能从“能够连接和收发”提升为可诊断、可恢复、可测试的稳定连接系统，同时保持现有主页、服务、历史、调试和聊天功能可用。

## 基线与原则

- 继续使用经典蓝牙 RFCOMM/SPP，不在本计划中混入 BLE。
- 客户端连接与服务端接入共用同一种会话生命周期。
- 同一 MAC 地址只保留一个有效会话；连接中禁止重复发起。
- 连接层不直接控制页面，统一发布状态，页面只负责展示和用户操作。
- 每个阶段必须通过单元测试、`assembleDebug`，涉及设备行为时再通过真机测试。

## 阶段 1：连接生命周期和资源安全（代码完成，真机待验收）

### 工作项

- [x] 建立统一连接状态：空闲、配对中、连接中、已连接、重连中、断开中、已断开、失败。
- [x] 增加按 MAC 地址管理的连接注册表，原子阻止重复连接。
- [x] 客户端支持取消正在进行的连接，并保证超时后 Socket 被关闭；首页连接按钮会在连接中/配对中切换为取消操作。
- [x] 取消请求在连接线程、超时线程和主线程之间幂等传播；会话已建立但尚未打开页面时也会关闭会话并阻止页面跳转。
- [x] 客户端连接前停止设备扫描。
- [x] 服务端和客户端会话统一登记、替换和关闭规则。
- [x] 服务端停止/重启使用代际 accept 线程和局部 Socket，旧线程不会误清理新服务的接入资源。
- [x] 服务端接入会话登记后若元数据读取或前台入口交接失败，会关闭该会话，避免残留 Socket 和幽灵连接。
- [x] 服务端 accept 线程异常停止后，Activity 回到前台会检查监听器健康状态并复用进程级服务恢复监听。
- [x] 应用销毁时关闭全部活动会话并清空运行时状态。
- [x] 配置变更重建 Activity 时只重启页面所属服务和事件线程，保留活动客户端会话、状态注册表和自动重连配置；真正退出应用仍执行全量清理。
- [x] 进行中的客户端连接尝试独立于 Home View 保存，配置变更后仍可查询、取消并更新 UI 回调，不持有旧 Activity Context。
- [x] 统一断开通知，确保一次断开只产生一次业务事件。
- [x] 用户取消连接记为 DISCONNECTED 而非 FAILED；Activity/蓝牙生命周期清理不改变设备自动重连偏好。
- [x] 蓝牙关闭或前台通知主动断开时，关闭活动会话后向聊天页和 Debug 页发布一次断开事件；应用销毁仍保持静默清理。
- [x] 新旧聊天页主动退出时由会话自身完成移除和状态收尾，首页不会残留 CONNECTED 状态。
- [x] 历史聊天空列表首次打开不再触发负索引更新；旧版与新版聊天页销毁时均移除事件和延迟消息。
- [x] 修复广播中无条件读取 RSSI、空设备对象和 switch 贯穿风险。

### 验收标准

- 连续快速点击同一设备只产生一次连接尝试。
- 15 秒超时或用户取消后，不保留连接线程或 Socket。
- 相同设备不会同时存在两个活动会话。
- 应用退出后服务端 Socket、客户端 Socket、接收线程全部关闭。
- 连接状态转换有单元测试覆盖。

## 阶段 2：配对、权限和扫描（代码完成，真机待验收）

- [x] 未配对设备先发起 `createBond()`，配对成功后自动继续连接。
- [x] 展示配对中、配对拒绝、配对失败，并允许重新配对。
- [x] Android 6–11 检查定位权限，Android 12+ 检查扫描/连接/广播权限；拒绝后支持重新申请和跳转设置。
- [x] 监听蓝牙开关变化；关闭时终止任务，重新开启后恢复服务。
- [x] 任意 Activity 回到前台发现蓝牙运行时权限被撤销时，统一停止监听和连接，并通知当前会话安全退出。
- [x] 监听扫描开始和结束，增加 15 秒扫描超时、空结果提示；适配器不可用或关闭时给出失败反馈。
- [x] 设备发现按规范化 MAC 地址去重，重复广播更新已有条目。
- [x] 已配对设备优先展示，按 RSSI/名称排序。
- [x] 已配对设备与附近设备增加分组标题。

### 验收标准

- Android 8、11、12、14/15 权限路径均有明确结果。
- 未配对设备可以在一次用户流程内完成配对并连接。
- 蓝牙关闭或权限撤销不会导致崩溃。

## 阶段 3：自动重连和后台连接（代码完成，真机待验收）

- [x] 保存每台设备的自动重连开关和最近成功参数。
- [x] 异常断开采用 1、2、5、10、30 秒退避重试，设置最大次数。
- [x] 用户主动断开时不触发自动重连。
- [x] 网络式抖动场景下只保留一个重连任务。
- [x] 为每次重连尝试分配代号，重复失败和迟到回调不会重复推进退避任务。
- [x] 重连成功也校验尝试代号，迟到的旧成功不会取消新任务或覆盖有效会话。
- [x] 使用前台服务承载后台连接，并提供连接状态通知和断开入口。
- [x] 系统杀进程后的恢复策略明确且可配置；蓝牙初始化成功后恢复已启用设备的重连任务。
- [x] 前台连接服务在进程重启且 Activity 尚未恢复时重新创建 SPP 监听器，并恢复已启用设备的重连任务；Activity 前台后复用同一监听实例。
- [x] 进程级消息分发器和历史监控器可由前台服务冷启动，后台恢复连接时仍保存收发历史和设备摘要；Activity 重建不重复注册。

### 验收标准

- 远端设备短暂掉电后恢复，可在配置范围内自动连回。
- 主动断开、退出应用或关闭自动重连时不会偷偷重连。

## 阶段 4：协议和收发可靠性（代码完成，真机待验收）

- [x] 每个连接使用独立串行发送队列，避免并发写流。
- [x] 增加发送状态：排队、发送中、成功、失败、取消，并在诊断报告中显示计数。
- [x] 支持原始流、CRLF、LF、CR、固定长度、自定义结束符和超时分包，并可按设备保存配置。
- [x] 帧缓存设置上限，超限时丢弃或切帧并记录诊断事件。
- [x] 支持 UTF-8、GBK、ASCII、HEX 展示与发送；按设备保存编码，HEX 输入优先保留原始字节。
- [x] 聊天页、历史聊天页和宏文本发送统一使用所选设备的编码与组帧配置；RAW/超时不追加分隔符，定长模式严格校验字节数。
- [x] 大数据发送自动按 1024 字节分片，支持片间隔，发送队列报告进度并支持取消/失败终止。
- [x] Debug 页面展示分片进度并提供停止按钮；停止只取消分片任务，不取消普通发送。
- [x] 分片发送在取消竞态下停止后续分段，已发送的当前底层写入不会扩展成新的分段。
- [x] 会话初始化发现同一 MAC 已有活动会话时拒绝重复 Socket；客户端不会重复打开聊天页或消耗重连尝试，正在关闭的旧会话占位会先安全清理。
- [x] 明确空帧、半包、粘包、尾部残帧的处理规则，并覆盖多模式单元测试。
- [x] Debug 页支持 SUM8、XOR、CRC-8、CRC-16/MODBUS、CRC-16/CCITT，并可选择低字节在前追加到 HEX 帧。
- [x] Debug 页支持独立本地快捷命令/宏：TEXT、HEX、DELAY、重复执行、保存、选择、删除；不写入现有消息历史或键盘配置。
- [x] 宏发送使用独立的历史持久化策略：仍进入 Debug 收发日志和实际发送队列，但不会写入聊天消息历史或设备摘要。
- [x] Debug View 重建后重新创建宏执行器，已关闭的线程池不会阻塞下一次宏执行。
- [x] Debug 连续发送在线程启动时快照设备、载荷和分片参数，View 销毁或切换设备不会访问旧 binding 或串发到新设备。
- [x] Debug 的收发、断开和分片回调统一经过二次 View 生命周期检查，迟到 UI 任务不会访问已销毁的 Activity/Binding。

### 验收标准

- 10 MB 无结束符输入不会无限占用内存。
- 多线程同时发送时，字节不会交叉。
- 分包、粘包、中文编码、二进制零字节均有自动化测试。

## 阶段 5：设备和会话体验（代码完成，真机待验收）

- [x] 已连接设备、连接中设备、历史设备统一展示：首页加载保存过的设备资料，并与扫描/连接状态合并展示。
- [x] 支持设备别名、收藏、最近连接、单设备 UUID/协议配置；别名/收藏/最近连接写入独立设备资料表，UUID/协议沿用按设备配置。
- [x] 点击已连接设备直接进入会话，不重复连接。
- [x] 多设备接入时按设备发送通知；后台/自动重连不再直接拉起聊天页面，用户点击通知后再进入对应会话。
- [x] Debug 页面展示连接时长、收发字节、接收帧数、实时速率和连接错误数。

## 阶段 6：诊断、日志和测试（代码完成，真机待验收）

- [x] 错误分类：权限、适配器关闭、配对失败、UUID 无效、超时、远端拒绝、读写失败。
- [x] 结构化连接日志包含时间、MAC、状态转换、线程、错误码和摘要。
- [x] 导出文本/HEX 日志时包含方向、时间、MAC 地址和设备信息。
- [x] 日志导出统一使用系统文件选择器，旧版 Android 不再依赖存储权限或直接写公共目录。
- [x] 为状态机注册表、分帧器、退避策略、地址规范化和发送队列增加单元测试。
- [ ] 使用两台 Android 设备或 Android + SPP 模块验证客户端/服务端、多连接、掉线恢复。
- [x] 建立回归清单：见 `docs/BLUETOOTH_CONNECTION_REGRESSION_CHECKLIST.md`；真实设备结果仍需按清单填写。

| 2026-07-31 | 阶段 2/6（按需权限与隐私诊断） | `testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest` | 通过；扫描、客户端连接、服务端监听分别申请所需权限；诊断报告默认脱敏 MAC，不上传原始地址、UUID 或收发内容 |
| 2026-07-31 | 阶段 6（Firebase/Crashlytics 生命周期埋点） | `testDebugUnitTest lintDebug assembleDebug connectedDebugAndroidTest` | 通过；新增连接尝试/成功/失败、重连、服务端、断开、会话汇总、日志导出和诊断复制事件，通信数据仅按计数汇总 |

## 执行顺序

严格按阶段 1 → 2 → 3 → 4 → 5 → 6 推进。每完成一个阶段：

1. 更新本文件复选框和验证记录。
2. 运行 `./gradlew testDebugUnitTest`。
3. 运行 `./gradlew assembleDebug`。
4. 涉及系统蓝牙行为时补充真机验证结果。

## 验证记录

| 日期 | 阶段 | 命令/设备 | 结果 |
|---|---|---|---|
| 2026-06-30 | 计划建立 | 代码审查 | 已识别连接生命周期、配对、重连、协议和广播处理缺口 |
| 2026-06-30 | 阶段 1（首批） | `testDebugUnitTest assembleDebug` | 本机 SDK 目录在当前执行环境中不可访问；Gradle 长时间无输出后终止，待环境恢复后重跑 |
| 2026-07-31 | 阶段 1（连接资源修复） | `testDebugUnitTest assembleDebug lintDebug` | 通过；客户端失败/取消关闭 Socket，服务端监听可幂等启停，会话发送串行化，旧会话不会覆盖新连接状态 |
| 2026-07-31 | 阶段 2/3（配对、错误分类、重连基础） | `testDebugUnitTest lintDebug assembleDebug` | 通过；未配对设备自动配对，蓝牙开关关闭时清理连接并恢复服务，异常断开按 1/2/5/10/30 秒单任务退避重连 |
| 2026-07-31 | 阶段 3（前台服务与配置） | `testDebugUnitTest lintDebug assembleDebug` | 通过；增加 connectedDevice 前台服务、后台通知和 Service 页面自动重连总开关，配置保存到本地 |
| 2026-07-31 | 阶段 6（连接诊断） | `testDebugUnitTest lintDebug assembleDebug` | 通过；Debug 页面可生成并复制设备、UUID、连接状态、错误码、重连任务和时长的本地诊断报告 |
| 2026-07-31 | 阶段 4（CRC 协议工具） | `testDebugUnitTest lintDebug assembleDebug` | 通过；新增五种常用校验算法、大小端输出和 Debug HEX 帧追加校验入口 |
| 2026-07-31 | 阶段 4（分帧内存保护） | `testDebugUnitTest lintDebug assembleDebug` | 通过；CRLF 分帧默认限制 64 KB，超限帧丢弃至下一个 CRLF 并记录 FRAME_TOO_LARGE |
| 2026-07-31 | 阶段 2（扫描生命周期） | `testDebugUnitTest lintDebug assembleDebug` | 通过；首页监听扫描开始/结束，15 秒超时收尾，空结果提示，回到首页可重扫；规范化 MAC 地址工具有单元测试并用于去重 |
| 2026-07-31 | 阶段 4（独立发送队列） | `testDebugUnitTest lintDebug assembleDebug` | 通过；每个连接使用单线程发送队列，支持排队/发送中/成功/失败/取消，写失败会记录错误并进入断开重连路径 |
| 2026-07-31 | 阶段 2（分版本权限） | `testDebugUnitTest lintDebug assembleDebug` | 通过；Android 6–11 检查定位权限，Android 12+ 检查 BLUETOOTH_SCAN/CONNECT/ADVERTISE，拒绝后可重试或打开应用设置 |
| 2026-07-31 | 阶段 6（结构化连接日志） | `testDebugUnitTest --tests BluetoothConnectionRegistryTest lintDebug assembleDebug` | 通过；记录时间、MAC、状态转换、线程、错误码和摘要，Debug 诊断报告展示最近 8 条记录 |
| 2026-07-31 | 阶段 3（进程重启恢复） | `testDebugUnitTest lintDebug assembleDebug` | 通过构建验证；蓝牙初始化成功后从本地配置恢复已启用设备，每个 MAC 只调度一个重连任务 |
| 2026-07-31 | 阶段 4（多模式分帧） | `testDebugUnitTest lintDebug assembleDebug` | 通过；Debug 页可按设备保存 RAW/CRLF/LF/CR/固定长度/自定义结束符/超时模式，接收线程使用有界分帧器和超时监控 |
| 2026-07-31 | 阶段 4（文本编码） | `testDebugUnitTest lintDebug assembleDebug` | 通过；按设备保存 UTF-8/GBK/ASCII/HEX，接收按配置解码，文本发送按配置编码，Debug HEX 发送保留原始字节 |
| 2026-07-31 | 阶段 4（大数据分片） | `testDebugUnitTest lintDebug assembleDebug` | 通过；超过 1024 字节的 Debug HEX 单次发送自动分片，支持片间隔、进度回调、取消和失败终止 |
| 2026-07-31 | 阶段 4（分片进度 UI） | `testDebugUnitTest lintDebug assembleDebug` | 通过；Debug 页面显示已发送字节进度，停止按钮只取消分片任务，普通聊天发送保持队列不变 |
| 2026-07-31 | 阶段 4（快捷命令/宏） | `testDebugUnitTest lintDebug assembleDebug` | 通过；新增独立 LitePal 宏表和脚本解析/执行器，Debug 页支持保存、选择、删除、重复执行和延时步骤 |
| 2026-07-31 | 阶段 1/2（首页连接操作） | `testDebugUnitTest lintDebug assembleDebug` | 通过；首页按已配对/附近设备分组，连接中或配对中的设备可直接取消，按钮状态随连接注册表刷新 |
| 2026-07-31 | 阶段 5（设备资料与会话入口） | `testDebugUnitTest lintDebug assembleDebug` | 通过；独立设备资料表保存别名/收藏/最近连接，首页合并历史设备并支持编辑、收藏和已连接设备直接进入现有会话 |
| 2026-07-31 | 阶段 5（会话统计） | `testDebugUnitTest lintDebug assembleDebug` | 通过；连接会话统计真实写入/读取字节、接收帧数、连接错误数，Debug 页面每秒刷新实时速率 |
| 2026-07-31 | 阶段 5（后台多设备通知） | `testDebugUnitTest lintDebug assembleDebug` | 通过；后台客户端/服务端连接按 MAC 发送独立通知，通知点击后由 MainActivity 打开对应会话，后台连接不强制拉起页面 |
| 2026-07-31 | 阶段 6（回归清单） | 文档审查 | 通过；新增客户端、协议、生命周期、后台、多设备、统计、日志和退出验收项，真机结果保持独立记录 |
| 2026-07-31 | 阶段 5（通知冷启动与 Manifest） | `testDebugUnitTest lintDebug assembleDebug` | 通过；通知点击在 Activity 冷启动和复用两条路径均路由到对应会话，移除旧 Manifest package 警告 |
| 2026-07-31 | 阶段 3/6（ACL 断开重连竞态） | `testDebugUnitTest --tests BluetoothConnectionRegistryTest compileDebugAndroidTestJavaWithJavac lintDebug assembleDebug` | 通过；系统 ACL 断开广播会进入统一异常断开通知，迟到的广播状态更新不会覆盖已排队的 RECONNECTING，新增状态竞态单测 |
| 2026-07-31 | 阶段 3（生命周期恢复配置） | `testDebugUnitTest compileDebugAndroidTestJavaWithJavac lintDebug assembleDebug` | 通过；蓝牙关闭、Activity 销毁和进程清理只关闭传输资源，不再误关用户的设备自动重连配置，重新初始化时仍可恢复 |
| 2026-07-31 | 阶段 4（宏导入导出） | `testDebugUnitTest --tests MacroTransferCodecTest testDebugUnitTest compileDebugAndroidTestJavaWithJavac lintDebug assembleDebug` | 通过；新增独立的 `SPP_MACRO_EXPORT_V1` 文本格式，通过系统文件选择器导入/导出宏；导入目标仍是当前设备资料，不写入消息历史和键盘配置 |
| 2026-07-31 | 阶段 5（Debug 多设备隔离） | `testDebugUnitTest compileDebugAndroidTestJavaWithJavac lintDebug assembleDebug` | 通过；Debug 页增加活动连接选择器；统计、日志、宏、分帧配置和发送目标跟随所选 MAC，非当前设备断开不会清空当前会话 |
| 2026-07-31 | 阶段 3/5（单设备自动重连开关） | `testDebugUnitTest compileDebugAndroidTestJavaWithJavac lintDebug assembleDebug` | 通过；首页设备长按编辑资料时可独立开启/关闭该 MAC 的自动重连；关闭全局开关不抹掉单设备偏好，终止重连任务时状态回到 DISCONNECTED |
| 2026-07-31 | 阶段 3（全局重连恢复） | `testDebugUnitTest compileDebugAndroidTestJavaWithJavac lintDebug assembleDebug` | 通过；全局自动重连重新开启时恢复已启用的 per-MAC 任务，地址统一规范化，权限/蓝牙关闭/达到最大次数等终止错误进入 FAILED |
| 2026-07-31 | 阶段 3（重连重复失败竞态） | `testDebugUnitTest --tests BluetoothReconnectAttemptGateTest` | 通过；超时与 Socket 异常同时到达时只接受当前尝试的首次失败，旧的延迟回调和重复失败不会产生并发重连 |
| 2026-07-31 | 阶段 1/3（取消连接竞态） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug` | 通过；取消会撤销对应重连尝试、关闭已建立会话并阻止迟到的聊天页跳转，连接线程的迟到异常不会再次触发重连 |
| 2026-07-31 | 阶段 1（连接尝试跨 View 生命周期） | `testDebugUnitTest --tests BluetoothConnectionAttemptRegistryTest compileDebugAndroidTestJavaWithJavac` | 通过；进行中的连接尝试由进程级注册表保存，旋转后首页可继续取消，Handler 使用弱引用且连接上下文降为 Application Context |
| 2026-07-31 | 阶段 1（服务端停止/重启竞态） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 通过；服务端 accept 线程使用代际标识和局部 Socket，停止等待旧线程收尾，旧线程不会关闭新一代服务资源 |
| 2026-07-31 | 阶段 4/5（Debug View 重建） | `testDebugUnitTest --tests MacroExecutorTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 通过；Debug 页面销毁 View 时释放宏执行器，重新创建 View 时使用新的执行器，旋转或返回后宏仍可执行 |
| 2026-07-31 | 阶段 3（重连迟到成功竞态） | `testDebugUnitTest --tests BluetoothReconnectAttemptGateTest --tests BluetoothConnectionRegistryTest lintDebug compileDebugAndroidTestJavaWithJavac` | 通过；重连成功必须匹配当前尝试代号，旧连接线程迟到成功时不会取消新退避任务或关闭新会话 |
| 2026-07-31 | 阶段 4/5（连续发送 View 生命周期） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 通过；连续发送线程不再读取后台 View 的 binding/设备选择，停止或重建页面时不会访问空 View 或串设备 |
| 2026-07-31 | 阶段 6（重连诊断日志） | `testDebugUnitTest --tests BluetoothConnectionRegistryTest compileDebugAndroidTestJavaWithJavac lintDebug assembleDebug` | 通过；手动连接和重连 claim 统一记录状态转换，并清除上一轮错误码，诊断日志能覆盖 CONNECTING 入口 |
| 2026-07-31 | 发布配置清理 | `bundleRelease` | 通过；移除 Manifest 中与 Gradle 不一致的旧 versionCode/versionName，Release AAB 仍正常生成 |
| 2026-07-31 | 阶段 6（导出与退出生命周期） | `testDebugUnitTest lintDebug assembleDebug` | 通过；日志导出统一走系统文件选择器，移除旧版存储权限依赖，异常退出改为正常结束 Activity 以触发生命周期清理 |
| 2026-07-31 | 阶段 4（聊天文本组帧） | `testDebugUnitTest --tests BluetoothTextFrameEncoderTest testDebugUnitTest` | 通过；聊天、历史聊天和宏发送复用按设备编码/分帧配置，覆盖 RAW、CRLF/LF/CR、自定义结束符、超时、定长和 HEX 文本 |
| 2026-07-31 | 阶段 1/3（Activity 配置变更） | `testDebugUnitTest lintDebug assembleDebug` | 通过；旋转或配置变更时停止旧页面服务但不关闭活动客户端会话，真正退出和蓝牙关闭仍执行完整连接清理 |
| 2026-07-31 | 阶段 3/5（连接成功 UI 路由） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug` | 通过；客户端和服务端连接线程统一切到主线程打开会话，后台或启动失败回退到对应设备通知，通知 ID 使用 MAC 尾部避免多设备覆盖 |
| 2026-07-31 | 阶段 1/3（连接地址一致性） | `testDebugUnitTest --tests BluetoothConnectionRegistryTest lintDebug assembleDebug` | 通过；注册表所有状态、错误和日志入口统一规范化 MAC，大小写和首尾空格不再产生重复连接状态 |
| 2026-07-31 | 阶段 4（分片取消竞态） | `testDebugUnitTest --tests BluetoothSendQueueTest` | 通过；分片队列在活动写入返回后重新检查取消状态，取消不会继续写后续分段，普通队列语义保持不变 |
| 2026-07-31 | 阶段 1/3/5（取消、历史聊天与进程恢复） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 通过；用户取消进入 DISCONNECTED，生命周期清理保留自动重连偏好，历史空列表不再负索引，前台服务可恢复共享 SPP 监听、消息分发和历史监控 |
| 2026-07-31 | 阶段 4/5（Debug 生命周期与宏历史隔离） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 通过；迟到 Debug UI 回调不会访问旧 View，宏仍进入发送和 Debug 日志但不写聊天历史/设备摘要 |
| 2026-07-31 | 阶段 1/3（配置变更保留共享监听） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 代码验证；旋转或配置变更不再停止进程级 SPP 监听，真正退出仍执行完整清理 |
| 2026-07-31 | 阶段 1/4（聊天页迟到事件与后台调度） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 通过；聊天页销毁后迟到事件不再访问旧 View，超时分帧改用固定延迟调度，宏命令解析不受系统区域影响 |
| 2026-07-31 | 阶段 1/2/3（权限异常后的服务恢复） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 通过；服务端 accept 异常会释放运行标志并允许重启，会话初始化读取远端信息失败时安全关闭 Socket |
| 2026-07-31 | 阶段 1/3（超时与迟到异常去重） | `testDebugUnitTest --tests BluetoothConnectionFailureGateTest` | 通过；同一连接尝试只发布一次失败结果，迟到 Socket 异常仍会清理资源但不会重复 Toast 或推进重连 |
| 2026-07-31 | 阶段 3/6（服务端与事件诊断日志） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 通过；服务端端口/accept 异常和事件监听器异常统一进入 Android Log，服务端错误仍通过 UI 消息通道反馈 |
| 2026-07-31 | 阶段 4（HEX 编码输入边界） | `testDebugUnitTest --tests BluetoothEncodingUtilsTest --tests BluetoothTextFrameEncoderTest` | 通过；HEX 编码遇到非法输入时明确拒绝，不再静默退回文本编码，合法 HEX 和组帧行为保持不变 |
| 2026-07-31 | 阶段 4（10 MB 无分隔符分帧边界） | `testDebugUnitTest --tests StreamFrameDecoderTest` | 通过；10 MB 无结束符输入不会让分帧缓冲区超过 64 KB 上限，进入有界丢弃态 |
| 2026-07-31 | 阶段 3（关闭重连取消在途尝试） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 代码验证；关闭全局或单设备自动重连时，同时取消待调度任务和已进入 Socket.connect 的重连尝试，普通手动连接不受影响 |
| 2026-07-31 | 阶段 3（重连延迟任务清理） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 代码验证；关闭全局或单设备自动重连会移除 Handler 中尚未执行的延迟 Runnable，并继续取消在途 Socket 尝试 |
| 2026-07-31 | 阶段 6（API 36 模拟器启动验证） | `connectedDebugAndroidTest` | 通过；2 个 instrumentation 测试通过，覆盖应用包名和 MainActivity 启动；模拟器结果不替代真实蓝牙双设备验收 |
| 2026-07-31 | 阶段 3/5（后台通知断开入口） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 代码验证；通知可断开当前 Socket 和重连任务，保留设备自动重连配置；两台设备真机操作仍待验收 |
| 2026-07-31 | 阶段 1/5（主动关闭断开通知） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 代码验证；蓝牙关闭和通知栏主动断开会通知当前聊天/Debug 页面，应用销毁保持静默清理 |
| 2026-07-31 | 阶段 5（聊天页主动退出状态收尾） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 代码验证；旧版与新版聊天页不再先移除 Socket，主动退出后连接注册表正确进入 DISCONNECTED |
| 2026-07-31 | 阶段 1（服务端接入异常收尾） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 代码验证；接入会话完成登记后若元数据或页面交接异常，accept 线程会关闭该会话，不留下活动 Socket |
| 2026-07-31 | 阶段 1/3（服务端监听健康恢复） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 代码验证；accept 线程异常停止后，Activity 回到前台会发现并重启共享 SPP 监听，不要求进程重启 |
| 2026-07-31 | 阶段 2/3（权限撤销运行时收尾） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 代码验证；集中检查蓝牙权限，任意前台 Activity 发现权限撤销时关闭 Socket、监听服务和前台服务，避免后台继续读写 |
| 2026-07-31 | 阶段 3（前台服务启动异常保护） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 代码验证；系统拒绝启动前台连接服务时记录错误，不让 `ForegroundServiceStartNotAllowedException` 直接导致 Activity 崩溃 |
| 2026-07-31 | 阶段 2/3（权限恢复链路） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 代码验证；权限重新授予后同时恢复 SPP 监听、消息监控、前台服务和已保存的自动重连任务 |
| 2026-07-31 | 阶段 1/3（连接交接异常偏好保留） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 通过；连接已建立但后续交接异常时使用生命周期关闭路径，不再误清除设备自动重连偏好 |
| 2026-07-31 | 阶段 1（初始化失败反馈） | `connectedDebugAndroidTest` | 通过；API 36.1 模拟器 2 个 instrumentation 测试通过，SPP 初始化异常会记录 Log、保持未初始化状态并向用户提示端口错误 |
| 2026-07-31 | 阶段 1/3（入站连接与重连竞态） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 通过；服务端接入同一 MAC 时取消在途自动重连，不取消手动连接，并在取消后恢复当前会话为 CONNECTED；双设备并发仍需真机确认 |
| 2026-07-31 | 阶段 1（服务 UUID 配置失败反馈） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 通过；无效或不可用的自定义服务 UUID 不再静默返回，调用方可统一关闭开关、提示错误并重试 |
| 2026-07-31 | 阶段 1/3（Activity 与进程级监听收尾） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 通过；Activity 字段为空时退出或蓝牙关闭仍会通过共享所有者停止监听，避免前台服务恢复后的服务残留 |
| 2026-07-31 | 阶段 3（前台服务提升失败保护） | `testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac assembleDebug bundleRelease` | 通过；系统拒绝 `startForeground` 时记录错误并安全停止前台服务，不让异常直接导致宿主进程崩溃 |
| 2026-07-31 | 发布边界 | `bundleRelease` + `jarsigner -verify` | AAB 构建通过并生成产物；当前未配置发布 keystore，签名仍需安全环境中的人工/CI 步骤，详见 `docs/RELEASE_READINESS.md` |
