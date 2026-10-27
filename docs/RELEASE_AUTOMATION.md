# 一键签名编译与 Google Play 上传

在项目根目录运行：

```powershell
.\release.cmd
```

脚本执行单元测试、Release Lint、签名 AAB 构建、逐文件签名验证，然后尝试保存 **Google Play 内部测试草稿**。它不写入正式发布轨道。保存失败时保留回执并停止；上传成功后仍需在 Play Console 检查草稿、其他待审更改和测试轨道状态。

2026-10-13 代码优化：编译前自动运行 uploader、构建证据和一次性测试密钥的验签回归；签名产物同时保存 `build-info.json`。记录 Git SHA、脏工作区完整源文件指纹、Gradle/AGP/JDK/SDK 和实际 runtime 依赖的坐标/哈希、JVM/Lint 结果、产物哈希。编译期间源码变化会停止后续上传；不会要求丢弃用户的未提交工作。该本地流水线没有执行真机或 Play Billing 验收，证据会明确记录这点。

**2026-09-10 最新状态：版本 28 已通过 API commit 保存为内部 draft；省略 review 参数曾意外送审原三项更改，现已撤回。**当前发布概览三项均为“尚未提交送审”，无审核中栏目或 FGS 警告；正式 24 未改动，最后发布日期仍为 2026-07-25。内部测试网页已确认 2.8 (28) 为草稿，轨道仍已暂停、无效。此前 FGS 弹窗仅列内部测试 27（2.7），没有正式 24，暂停旧内部测试后警告消失；将正式 24 也视为本次来源的历史推断已更正。不要再次上传 28，也不要直接恢复旧 27，否则警告可能再次出现。详见[操作记录](PLAY_CONSOLE_RELEASE_STATUS.md#自动上传实测与当前进度)。

**默认保留 `changesNotSentForReview=true`，Google 拒绝时停止，不能自动省略。**本应用实测表明，手动省略参数保存内部 draft 会把发布概览中其他待审更改一起送审；`ERROR_IF_IN_REVIEW` 只能防止打断已有审核，不能保证没有新的送审。任何手动省略前，必须核对整个发布概览，并明确接受这些更改可能一并进入审核。

## 本机配置

签名密钥默认位于仓库旁的 `Dandroid-keys\bluetto\upload.jks`，alias 为 `upload`。首次配置运行：

```powershell
.\scripts\Set-ReleaseSigning.ps1
```

密码通过隐藏输入读取，保存在 `%LOCALAPPDATA%\BluetoothAssistant\signing.clixml`，由 Windows DPAPI 绑定当前 Windows 用户和本机。不要提交此文件；换电脑或 Windows 用户时重新配置即可。密钥密码与库密码不同的情况使用 `-SeparateKeyPassword`。脚本检查证书是否与当前已登记的 Google Play 上传证书一致。

自动上传使用另一个文件：仓库旁的 `Dandroid-keys\bluetto\play-service-account.json`。它是 Google Cloud 服务账号的私钥凭据，**不是** `upload.jks`，也不是项目的 `app/google-services.json`。不要把其内容粘贴到聊天或提交到 Git。也可以指定外部路径：

```powershell
.\release.cmd -ServiceAccountPath 'E:\private\play-service-account.json'
```

本机上述两个配置均已就绪，通常无需重新执行配置步骤。专用账号为 `bluetoothsmp-play-uploader@bluetoothsmp-dc9e4.iam.gserviceaccount.com`，仅授权 `com.zzf.bluetoothsmp` 的信息查看和测试轨道发布；Google API 已通过真实只读请求验证。JSON 文件仅允许当前 Windows 用户、SYSTEM 和本机管理员访问。服务账号未授予 Google Cloud 项目角色。

这台电脑需要 JDK 17、Python 3.10+ 和现有 Android SDK。上传依赖安装在忽略目录 `.release-tools\venv` 中。脚本使用独立的 `.release-tools\android-build` 构建目录，避免 Android Studio 占用常规构建目录的文件。

## 常用命令

仅生成签名包，不上传：

```powershell
.\release.cmd -BuildOnly
```

`-BuildOnly` 自动传入独立的 `-PskipMappingUpload`，构建期间不上传 Crashlytics mapping。它保留正常 Release 产品的采集配置；`-PofflineAudit` 则另外关闭产品内 Firebase 采集，仅用于审核/CI。直接调用 Gradle 只想禁止 mapping 上传时也可使用 `-PskipMappingUpload`。

指定下一次版本号（不修改仓库中的默认版本）：

```powershell
.\release.cmd -VersionCode 29 -VersionName 2.8
```

已使用的 `versionCode` 不能重复上传。脚本会检查 Google Play 当前可见版本；遇到重复、已有内部测试草稿或审核冲突会停止，不自动覆盖草稿或递增版本号。不存在于当前 API 可见版本中的历史已占用编号仍可能被 Google 拒绝，以服务器返回结果为准。

默认说明读取 `docs/RELEASE_NOTES_<VersionName>_zh-CN.txt` 和对应的 `en-US` 文件；更换版本名称时同步准备说明，或用 `-ChineseNotesPath` / `-EnglishNotesPath` 指定。

每次运行生成独立的 `release-artifacts/<运行编号>/` 目录：

- `bluetoothsmp-<versionCode>.aab`：本次已验证的签名包。
- `build.log`、`verify.log`：构建与签名验证结果。
- `upload-plan.json`：实际包名、版本、AAB SHA-256 和双语说明，不含密码。
- `build-info.json`、`source-before.json`：产物对应的源码、工具、依赖和检查身份；不读取签名/服务账号或 google-services.json 内容。
- `script-tests.log`：离线脚本与实际 JAR 验签的测试回归结果。

`build-info.json` schema 2 中，签名和脚本检查由调用方在对应命令成功退出后，显式传入 `--signature-status verified --signature-evidence verify.log` 和 `--offline-scripts-status passed --offline-scripts-evidence script-tests.log`；记录日志文件名、字节数和 SHA-256。单独调用证据工具默认两项均为 `unknown`，不会把未验签产物写成已验证，声明成功但缺失或空日志会失败。验签回归缺少 `java`、`keytool` 或 `jarsigner` 会直接失败，不能通过跳过整组测试获得成功结果。

上传回执统一保存在 `release-artifacts/receipts/<运行编号>.json`，这里还有跨运行的状态索引。只有回执确认 `committed` 才能说明内部草稿已提交保存。固定目录用于阻止更换运行编号后重复提交未确认的上传；不要拆散或删除这些状态文件。

如果上传或提交时网络中断，保留整个目录，先只读检查回执；不要删除回执后反复上传同一版本：

```powershell
.\.release-tools\venv\Scripts\python.exe scripts\play_upload.py inspect `
  --receipt 'release-artifacts\receipts\某次运行.json' `
  --service-account-json 'E:\code\androidDev\Dandroid-keys\bluetto\play-service-account.json'
```

## Google Play 一次性授权

专用服务账号仅需本应用的“查看应用信息”和“发布应用到测试轨道”权限；上传内部测试不需要正式发布、财务、订单或管理员权限。在 Google Cloud 项目启用 Google Play Android Developer API，创建服务账号及 JSON 密钥，然后在 Play Console 的用户和权限中为该服务账号添加上述应用级权限。[Google 官方授权步骤](https://developers.google.com/android-publisher/getting_started)

脚本默认创建内部草稿，保留现有已完成版本，复核其他轨道未变，提交时遇到正在审核的更改会报错，不取消已有审核。不要在上传运行期间同时编辑 Play Console 版本；审核与当前有效旧包的政策提示仍须在 Console 核对。[官方草稿工作流](https://developers.google.com/android-publisher/tracks#draft_releases)、[官方提交参数](https://developers.google.com/android-publisher/api-ref/rest/v3/edits/commit)

## 实测状态

2026-09-09：本机签名密码及上传证书已验证，Windows 加密签名配置已保存。实际签名构建、97 项 JVM 测试和 Release Lint（0 errors / 159 warnings）已通过，798 项 AAB payload 验签通过。验签器的 unsigned、追加未签名项、修改已签名内容、错误证书四个负向案例均被拒绝；上传脚本 39 项离线测试通过，PowerShell 5.1 的命令失败传播、日志脱敏、哈希及路径检查已验证。该次真实 API 已接收 AAB，但 Google 的前台服务声明校验返回 403，未调用 commit，未保存 Console 草稿。这不是签名或上传账号权限不足。

2026-09-10：只读 GET 旧 edit `03655763206922559505` 返回 HTTP 400，精确消息为 `This edit has expired, please create a new Edit.`。原失败回执 `release-artifacts/receipts/20260909T151735Z-d181c3d7.json` 继续保留。暂停旧内部测试后 UI 显示该轨道已暂停、无效，测试人员无法获取 27；发布概览 FGS 问题消失，“提交 3 项更改”按钮恢复，常规检查提示最多 14 分钟，正式 24 未改动。

随后沿用签名文件 `release-artifacts/20260909T151735Z-d181c3d7/bluetoothsmp-28.aab`（5,527,974 bytes，SHA-256 `55E51C71A102E3826279F4BDF9082BC6434218DA9B9214064B06FDB4D042278D`）完成新 API 上传。新回执 `release-artifacts/receipts/20260910T001900Z-fgs-paused.json` 为 **committed**，edit `14548134092201909362` 保存 **internal / draft / 28**。validate 通过后，首次 commit 因 Google 要求 `changesNotSentForReview must not be set` 返回 400；受控 resume 显式省略该参数后成功，仍保留 `ERROR_IF_IN_REVIEW`。

网页核对随即发现原 Data safety 和两项开放测试国家/地区更改已一起进入审核；没有手动点击送审，不等于没有实际送审。已在 Console 点击“移除更改”，确认全部退回后复核：仅上述三项处于“尚未提交送审”，文案“更改现在可以送审”，无审核中栏目、无 FGS 警告。正式 24 未改动，最后发布日期仍为 2026-07-25；未执行恢复内部测试轨道。新内部草稿及轨道暂停状态已获网页确认，具体进度见 [Play Console 操作记录](PLAY_CONSOLE_RELEASE_STATUS.md)。上传脚本当前 **42 项离线测试通过**。

如果回执记录的是提交阶段明确 HTTP 4xx 拒绝，可使用 `resume --receipt ... --service-account-json ...` 复核同一个 edit 后重新提交一次，不重传 AAB。`--omit-changes-not-sent-for-review` 是可能触发应用其他待审更改一并送审的人工选项：除了确认 Google 因该参数不适用而拒绝，还须先核对发布概览并明确接受这个范围；不得作为固定 400 的自动重试方式。网络结果不明确时不允许 resume。`reconcile` 仅通过 GET 确认旧 edit 已失效后解除本地阻塞，不能证明原版本号可重新使用。

### 人工省略 review 参数的证据门禁

省略参数现在必须同时传入 `--review-evidence <仓库外或本地审计 JSON>`，否则在读取凭据/访问 API 前失败。人工完整检查 Play 发布概览后填写以下结构（版本和时间必须使用本次真实值，时间须带时区且距执行不超过 30 分钟）：

```json
{
  "packageName": "com.zzf.bluetoothsmp",
  "versionCode": 29,
  "checkedAt": "2026-10-13T15:00:00+08:00",
  "publicationOverviewChecked": true,
  "pendingChanges": ["从整个发布概览逐项抄录的待审更改，空数组仅用于确实没有更改"],
  "acceptsReviewSubmission": true
}
```

这些字段是人工对整个应用的范围核对，不能由 internal/production 的轨道 GET 自动代替；包括数据安全、商店资料、国家/地区等更改。不要直接复制示例假装已经检查。CLI 会解释所有更改可能一起送审，并把证据保存到回执的 `reviewOverviewEvidence`。配置中直接使用 `changesNotSentForReview:null` 同样不能绕过此门禁。这里只约束后续人工操作，本轮优化没有执行任何上传、发布或 Play 购买。

### CI 和离线审核构建

`.github/workflows/android-checks.yml` 自动执行 JVM/Lint/脚本/验签回归，API 23/31/36 模拟器 instrumentation，以及 API 36 的实际 R8 Release ORM smoke。CI 使用合成 Firebase 配置、一次性测试签名，传入 `-PofflineAudit` 禁止生产 collection 和 mapping 上传；不调用 publisher。`-PreleaseSmokeTest` 仅切换 instrumentation 源和被测试 build type，不为整个应用增加宽泛 keep。工作流配置已添加；未运行的 CI/真机检查不得写成通过。

每个设备 job 上传实际被测应用 APK、JUnit XML 和 `device-test-evidence.json`，关联 Git SHA、APK SHA-256、variant、API、该测试步骤的实际 outcome 和失败/跳过计数。缺 APK、报告、零测试、含跳过、运行失败或未知状态不会标成全通过。Release smoke 的范围是当前 ORM schema、反射持久化和原始 `00 FF` 字节 roundtrip；它不证明压缩后的历史版本升级、整个 UI 或真实 Play Billing 已验收。Debug 的升级/UI 结果另行记录。
