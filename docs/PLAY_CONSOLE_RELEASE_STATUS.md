# Play Console 发布操作记录 — 2.8

记录日期：2026-09-10。应用：`com.zzf.bluetoothsmp`，候选版本 **28 / 2.8**。本记录依据本次授权 Console 操作、公开网站检查及本机构建结果。**版本 28 已通过 API commit 保存为内部测试 draft；该次省略 review 参数也意外将原三项更改送审，随后已在 Console 撤回。当前三项均回到“尚未提交送审”，没有审核中栏目或 FGS 警告。**旧内部测试 27 已暂停，未执行恢复；已在内部测试网页确认 2.8 (28) 为草稿，轨道仍为“已暂停、无效”。正式版本 24 未作修改，正式最后发布日期仍为 2026-07-25，数据安全答案尚未审核生效。

## 当前状态

| 项目 | 已完成的操作 | 尚未完成 |
|---|---|---|
| 隐私政策 | 独立仓库 `privacy-policie` 的 `main` 已推送提交 `a5f56f9`；公开页 HTTP 200，已核对新日期、双语内容、2.8 版本界限和保留期 | 若后续代码或云端设置改变，先同步政策 |
| Data safety | 最终 CSV 已导入并保存；2026-09-10 曾被 API commit 一并送审，发现后已通过 Console 撤回 | 当前回到尚未提交送审，不代表公开数据安全标签已更新 |
| 广告 ID 声明 | 已核实“使用广告 ID = 是”，用途仅勾选“分析”，与当前 SDK/Manifest 一致；Save/Discard 均禁用，无需修改 | 当前无此项待办；后续用途或 SDK 配置改变时重新核对 |
| 发布概览 | API commit 意外触发原三项送审后，已点击“移除更改”并确认全部退回。当前只有 **1 项 Data safety + 2 项开放测试国家/地区**，均在“尚未提交送审的更改”；按钮可用，文案为“更改现在可以送审” | 当前无审核中栏目、无 FGS 警告。后续送审前仍须逐项核对三项实际内容和范围 |
| 正式版本草稿 | 既有 `release8` 已填写名称 **28 (2.8) 前台通信与稳定性修复** 和中英文发布说明，并确认 Save as draft | **没有上传 AAB**；草稿名称不代表版本 28 的包已被 Play 接受 |
| 当前轨道状态 | 内部测试已执行“暂停轨道”→“暂停测试”，当时 UI 显示已暂停、无效，测试人员无法从该轨道获取 **27 / 2.7**；正式 **24** 未改动。新 API commit 保存的是 **28 / 2.8 内部 draft**，没有执行恢复轨道 | 网页已确认新草稿存在且轨道仍暂停；不能直接恢复旧 27，否则声明警告可能再次出现 |
| 前台服务声明 | 2.8 源码和合并 Manifest 已移除前台服务及通知权限；2026-09-10“查看 app bundle/APK”弹窗仅列出内部测试 **27 / 2.7**，没有正式 24；暂停该轨道后问题消失，新 28 edit 的 validate 已通过 | 撤回此前将正式 24 也视为本次 FGS 警告来源的推断；新包网页状态已核对为草稿，轨道仍暂停 |
| 上传签名 | 已验证密码及实际私钥签名，798 项 AAB payload 验签匹配 Console 上传证书；密码保存在仓库外的 Windows DPAPI 加密配置 | 签名已通过，上传结果另行记录 |
| 最新构建 | 独立发布目录构建成功 1m28s；97 项 JVM 测试零失败，Lint 0 errors / 159 warnings，签名 AAB 已生成 | 此前 16 项 instrumentation 是旧候选 API 36.1 模拟器结果，未在最新候选或真机重跑 |
| 自动上传授权 | 专用服务账号已创建；Play Console 显示有效，仅本应用查看和测试轨道发布权限；真实 Google API 请求成功 | 无需扩大到生产、管理员或财务权限 |
| 真实 API 上传 | 新 edit `14548134092201909362` 已 validate 并 commit 成功，回执 `20260910T001900Z-fgs-paused.json` 为 **committed**，保存 **internal / draft / 28**；使用昨晚同一签名包及 SHA-256 | 省略参数实际触发原三项送审，现已撤回；API draft 不保证其他更改不被送审。网页已确认新内部草稿与暂停状态，旧 403 失败回执保留 |
| Mi 11 真机 | 本次回归已结束。同签名 debug 23→28 覆盖，先备份，无卸载/清数据；真实双向 RFCOMM、键盘保留、Home/屏幕关闭断连、解锁重连及收发恢复、服务关闭后 Home/冷启动保持、中英文切换通过 | Google Play/GMS 停用导致 Billing code 3，支付未验收；真实 API 23、签名 Play 安装及性能全面验证未完成，详见[实测记录](REAL_DEVICE_TEST_2026-09-09.md) |
| 根项目 Git | 本次源码和文档变更保留在工作区 | **尚未 commit**；独立隐私仓库的推送不等于根业务仓库已提交 |

公开隐私政策：[蓝牙调试助手隐私政策](https://fei1025.github.io/privacy-policie/bluetto/)。页面最后更新日期为 **2026-09-09**，保留公开联系邮箱 `zhangfeidamo@gmail.com`。仅前台蓝牙通信、不再使用前台服务或请求通知的说明明确适用于 **2.8 及后续版本**；2.8 之前的版本可能仍使用前台服务和通知维持后台通信。

中英文版本说明分别保存在[中文说明](RELEASE_NOTES_2.8_zh-CN.txt)和[英文说明](RELEASE_NOTES_2.8_en-US.txt)。

## 数据安全答案与配置依据

最终导入文件保存在 [release/2.8/data-safety.csv](release/2.8/data-safety.csv)。它包含完整的 **782 行、5 列** Console schema，仅包含题目、选项、答案和标签，**没有密码或用户明细记录**。与导入前备份相比只修改 **49 个 Response value**：42 个改为 TRUE，7 个改为 FALSE；题目 ID、选项 ID、中文标签和行顺序保持原样。

| 数据类别 | 收集 / 共享 | 用户选择 / 临时处理 | 收集用途 | 共享用途 |
|---|---|---|---|---|
| 近似位置 | 是 / 是 | 必选 / 非临时 | Analytics | Analytics |
| 设备 ID 或其他 ID | 是 / 是 | 必选 / 非临时 | Analytics、App functionality | Analytics |
| 应用互动 | 是 / 是 | 必选 / 非临时 | Analytics | Analytics |
| 购买历史 | 是 / 是 | 必选 / 非临时 | Analytics、App functionality | Analytics |
| 崩溃日志 | 是 / 是 | 必选 / 非临时 | Analytics | Analytics |
| 诊断信息 | 是 / 是 | 必选 / 非临时 | Analytics | Analytics |

共享判断来自 Analytics 的实际数据路径及已开启的 Google 产品和服务共享。**Crash logs 的共享仅因有限崩溃发生事件 `app_exception` 进入 Analytics；不表示完整 Crashlytics 异常栈也供 Google 独立产品用途使用。**Crashlytics 专有报告仍按崩溃诊断服务处理。

全局答案为：收集或共享数据 **是**；收集数据通过 TLS 加密传输 **是**；不提供开发者账号创建或外部账号登录；当前没有可可靠处理云端遥测删除的用户机制，因此未申报支持数据删除，也未声称所有数据会在 90 天内自动删除。可选支持者购买不等于其拥有状态统计可以由用户关闭。未添加广告营销、个性定制或账号管理用途。

CSV SHA-256：

```text
65F725BB86462B860225BF9AD35966BD593E724B2ACDF9082A0BA95A25E3BB20
```

导入前原始 CSV、逐项差异、SDK 与代码映射备份保存在本机忽略目录：

```text
.scratch/play_policy_insights_34f19f20-a26c-4f40-851a-56aa390b8af5/remediation/
  data_safety_before.csv
  data_safety_corrected.csv
  data_safety_csv_changes.json
  data_safety_csv_changes.md
  data_safety_mapping.md
```

该目录是本地审计备份，不是持续交付文件的唯一保存位置。后续修改答案时，应先从 Console 重新导出备份并比对实际 schema，勿直接覆盖新出现的题目或他人已保存的答案。

## 已核实的 Analytics / Firebase 配置

| 设置 | 2026-09-09 实际状态 |
|---|---|
| Analytics 账号 | **damo (263833475)** |
| Analytics 资源 | **bluetoothsmp-dc9e4 (396914184)** |
| 资源广告个性化 | 已从 307/307 允许改为 **0/307 允许**，Apply 后复读确认 |
| 2.8 Manifest | `firebase_analytics_default_allow_ad_personalization_signals=false` |
| Google 产品和服务共享 | **保留开启**；controller-controller 条款于 2023 年接受 |
| 汇总模型/业务洞察、技术支持、业务建议 | 均开启 |
| Google Ads / BigQuery 关联 | 均未关联 |
| Google Play 关联 | 已关联 1 个应用 |
| Google Signals | 未启用；实际 UI 显示启用入口，不按日历假定控件已迁移 |
| 用户提供数据 | 尚未设置启用 |
| 精细位置和设备数据 | 开启；城市级网络位置仍归近似位置，不等于 GPS 精确坐标 |
| 事件级数据保留 | **2 个月** |
| 用户级数据保留 | **14 个月** |
| 有新用户活动时重置 | **开启**；不承诺安装后 14 个月所有记录必然删除 |
| Consent UI | 显示状态良好、未检测到问题；行为和广告 consent signals 均显示已停用 |
| 应用 consent 调用 | 未调用 `setConsent`；**信号停用不等于 denied，也不证明用户已经同意** |

广告个性化关闭不会停用 Analytics、Crashlytics、AD_ID 或 Google 产品和服务共享。标准汇总报告不受上述事件/用户保留设置控制。Crashlytics 的公开保留说明为崩溃栈及相关标识保留 90 天后开始从在线和备份系统清除，不是第 90 天所有副本立即消失。

2026-09-09 已核实 Console 现有广告 ID 声明：**使用广告 ID = 是，用途仅勾选“分析”**。应用功能、广告或营销、开发者通讯、欺诈防范/安全/法规遵从、个性定制和账号管理均未勾选。Save/Discard 均禁用，表明没有待保存修改；现有声明与当前 SDK 和 Manifest 一致，无需改动。资源广告个性化关闭不等于 AD_ID 权限或采集已关闭。

## 审计包、上传证书与签名验证

自动化、模拟器与代码验证结果以 [RELEASE_READINESS.md](RELEASE_READINESS.md) 为准。此前审计文件仍为 `app/build/outputs/bundle/release/app-release.aab`，**5,480,621 bytes，unsigned**，不能直接上传：

```text
SHA-256 7DD44A9201FA1B1988C0C945D724A49FA7A917526F27EE6C7BE2CBEACA0702B2
```

最新 debug APK：`app/build/outputs/apk/debug/app-debug.apk`，**13,581,017 bytes**，SHA-256：

```text
1A7D5E60C1474E597DB97B73B3E59E11B6FEE89762FA318D01DC57C78213A607
```

该 APK 已用于 Mi 11 覆盖测试，不能替代 Google Play 所需的上传签名 AAB。键盘兼容已通过真实原 28 配置覆盖验证；本地旧 27 AAB 的按钮 ID 与原 28 相同，已静态确认映射覆盖。23 的资源表和映射已验证，但该手机原 23 数据库为空，不能称为真实旧用户键盘数据迁移样本；24 尚未取得 APK。

本次真机测试结束时保留最新 debug 安装、电脑设备测试历史和 `UpgradeKey` 示例按钮；没有清除用户数据。界面已恢复 `zh-CN`，蓝牙服务开关恢复 ON（`bluetooth_serveruser_enabled=true`），22:03:54 本地时间日志确认 `accept()` 恢复监听；用户开启的充电常亮值 15 保留，Google Play/GMS 停用状态未修改。最终日志未见 FATAL/ANR，本次两个进程退出为测试操作的 FORCE STOP；存在普通后台服务及 MIUI/慢帧告警，不能视为全面性能验收。

用户随后提供了签名密码。已通过隐藏输入验证 `upload.jks`（**PKCS12、alias = upload**），保存到仓库外的 Windows DPAPI 配置，并完成实际签名构建。没有把密钥、密码或 Google 私钥凭据写入仓库。

2026-09-09 已在 Play Console 应用签名页核对，上传证书的 SHA-1 和 SHA-256 均与本地 `upload.pem` 一致：

```text
SHA-1   56:49:F0:79:90:06:B5:6C:E4:76:0A:A6:E3:9D:6E:EC:C7:AA:8D:FC
SHA-256 6F:87:F2:BA:3D:11:4F:9E:32:B6:F2:58:72:07:FC:7F:2A:3E:FD:78:F1:EB:2F:6B:20:62:36:3B:5B:62:88:23
```

**实际签名验证已通过。**独立发布脚本构建的 `release-artifacts/20260909T151735Z-d181c3d7/bluetoothsmp-28.aab` 为 28 / 2.8、5,527,974 bytes，798 项 payload 均经 JAR 验签并匹配上述上传证书。SHA-256：

```text
55E51C71A102E3826279F4BDF9082BC6434218DA9B9214064B06FDB4D042278D
```

构建日志位于同目录 `build.log`，验签日志为 `verify.log`。发布流程和外部凭据配置见[一键发布脚本说明](RELEASE_AUTOMATION.md)。这份 AAB 随后已被 Google 接收到旧 edit；签名和收包成功均不代表已保存 Console 草稿或正式发布。

## 自动上传实测与当前进度

2026-09-09 本地时间 23:19，实际执行 `./release.cmd`，完成签名构建并调用 Google Play Android Developer API。专用服务账号为 `bluetoothsmp-play-uploader@bluetoothsmp-dc9e4.iam.gserviceaccount.com`，凭据位于仓库外，Windows 文件权限已限制为当前用户、SYSTEM、本机管理员。没有授予 Cloud 项目角色或 Play 正式发布/管理员/财务权限。

本次回执：`release-artifacts/receipts/20260909T151735Z-d181c3d7.json`，edit ID `03655763206922559505`。Google 已返回版本 **28** 和与本地一致的 SHA-256；内部草稿写入成功，原 internal 27 保留，其他轨道内容复核未变。随后 `edits.validate` 返回 **HTTP 403 PERMISSION_DENIED**；单独重复无副作用校验确认原因为：

> You must let us know whether your app uses any Foreground Service permissions.

当时只读 `inspect` 确认 `editAccessible=true`、`bundleInEditMatches=true`、`draftInEdit=true`。该次尝试没有调用 commit，也没有重新上传同一 AAB。本次不属于服务账号权限不足，不能通过扩大账号权限解决。

2026-09-10 对旧 edit `03655763206922559505` 的只读 GET 返回 **HTTP 400**，精确错误消息为：

> This edit has expired, please create a new Edit.

这条响应确认旧 edit 已过期；它不是已保存的 Console 草稿，也不能据此证明版本号 28 可以复用。原失败回执 `release-artifacts/receipts/20260909T151735Z-d181c3d7.json`、签名 AAB、哈希和状态索引均保留。后续是否可使用 28 以 Google 实际响应为准。[官方 edit 生命周期](https://developers.google.com/android-publisher/edits)

### 2026-09-10：核实警告来源并暂停旧内部测试

前台服务声明页的“查看 app bundle/APK”弹窗只有 **内部测试 27（2.7）** 一行，**没有正式版本 24**。此前依据 27 和 24 当时均有效，将 24 也视为 FGS 警告来源，属于未充分核实的历史推断，现予更正；本次界面证据指向内部测试 27，不据此推断正式 24 的 Manifest 内容。

随后在内部测试中选择“暂停轨道”并确认“暂停测试”。Console 显示该轨道**已暂停、无效**，测试人员无法从该轨道获取版本 27；**正式版本 24 未作修改**。返回发布概览后，FGS 问题已消失，“提交 3 项更改”按钮恢复，UI 提示常规检查最多 14 分钟。此时尚未执行下述新 API commit，三项更改仍未送审；没有手动点击送审按钮。

不要直接恢复旧版本 27 的内部测试轨道，否则原前台服务声明警告可能再次出现。后续应以无 FGS 的新候选为准，再核对轨道状态和声明提示。[前台服务声明要求](https://support.google.com/googleplay/android-developer/answer/13392821)

### 2026-09-10：版本 28 API 内部草稿已提交保存

新回执为 `release-artifacts/receipts/20260910T001900Z-fgs-paused.json`，edit ID 为 `14548134092201909362`，最终 **status = committed**。本次沿用昨晚已经验签的 `release-artifacts/20260909T151735Z-d181c3d7/bluetoothsmp-28.aab`，版本 **28 / 2.8**、**5,527,974 bytes**，SHA-256 仍为 `55E51C71A102E3826279F4BDF9082BC6434218DA9B9214064B06FDB4D042278D`。

新 edit 的 validate 已通过。首次 commit 返回 HTTP 400，Google 明确要求 `changesNotSentForReview must not be set`；随后通过受控 `resume` 复核同一 edit，并显式省略该参数，commit 成功。内部版本保持 **draft**，`changesInReviewBehavior=ERROR_IF_IN_REVIEW` 仍保留；没有发布 production，也没有执行恢复内部测试轨道。上传脚本当前 **42 项离线测试通过**。

API commit 后的网页核对发现，原三项更改（Data safety 和两项开放测试国家/地区）已进入**正在审核**。虽未手动点击送审，省略 `changesNotSentForReview` 的 commit 仍实际触发了它们一起送审；内部版本为 draft、保留 `ERROR_IF_IN_REVIEW` 并不能阻止这个副作用。

随即在发布概览点击**“移除更改”**。确认弹窗明确说明全部更改会退回“尚未送审”，确认后重新核对：当前仅有上述 **3 项“尚未提交送审的更改”**，按钮可用，页面显示“更改现在可以送审”；**没有正在审核栏目，也没有 FGS 警告**。本次意外送审已撤回，当前不在审核，正式最后发布日期仍为 **2026-07-25**。

新版本 28 的 API committed 回执保留；内部测试网页已确认“草稿版本：2.8 (28)”和“此轨道已暂停 / 无效”，与 API 回执一致。此次未发布正式版，也不能把 API 草稿保存称为向测试人员推出。后续脚本继续默认携带 `changesNotSentForReview=true`，Google 拒绝时停止并人工处理；**不得自动省略参数**，手动省略前必须核对整个发布概览并明确接受其他待审更改可能一并送审。

## 下一次继续发布

1. 先重新读取 Console 当前状态，确认未送审变更、有效轨道、版本号和上传证书。原有开放测试国家/地区变更应单独核对，不能因本次修正数据安全就视为已确认其发布范围。
2. 本机签名配置、签名构建和版本 28 API 草稿保存已完成，先核对上述 committed 回执及网页，不再次上传 28。后续新版本使用 [release.cmd](../release.cmd)，换机器时重新配置本机加密密码；不得把密码写入仓库、命令参数或日志。
3. 保留旧 403 失败回执、过期响应、新 committed 回执和同一签名文件。原三项意外送审已撤回，后续补记网页新内部草稿及暂停状态的核对结果；结合已完成的[真机实测](REAL_DEVICE_TEST_2026-09-09.md)，补齐可用 Play 环境下的 Billing、签名 Play 安装、真实 API 23 等未测范围，并跟进慢帧性能。
4. 内部测试 27 已暂停，FGS 问题已从发布概览消失；不要直接恢复旧 27。新候选上传后再次核对实际轨道与声明提示，不填写不实的 `connectedDevice` 使用声明，也不因本次问题消失就推断正式 24 的权限内容。
5. 核对 Console 预览、发布范围和预发布报告后再送审；每次操作追加日期、动作及结果。只有 Console 明确确认审核通过并发布后，才记录为已审核或已上线。
