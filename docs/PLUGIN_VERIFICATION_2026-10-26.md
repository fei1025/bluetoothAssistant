# 插件平台本地验证记录（2026-10-26）

本记录对应当前有未提交修改的工作区，不代表历史 2.8 已发布候选、真实设备无线验收或 Play 审核通过。未清空或迁移用户按钮、历史数据库及设备偏好。

## 已完成

| 验证 | 结果 |
|---|---|
| JVM | 160 项，0 失败 |
| Python / SDK / 签名脚本 | 81 项，0 失败，含真实 ECDSA、SDK 二进制/Promise/配额、目录撤下及构建指纹回归 |
| API 23 完整仪器回归 | 115 项，0 失败；5 项现代 WebView 用例、1 项 API 33+ 系统语言用例按条件跳过 |
| API 31 完整仪器回归 | 115 项，0 失败；同上 6 项条件跳过 |
| API 36 完整仪器回归 | 115 项，0 失败，无上述条件跳过，包含 15 项插件平台测试和 36 项 BLE 核心/插件传输替身测试 |
| R8 Release smoke / API 36 | 3 项，0 失败：优化后持久化、原页面 ViewModel 重建、插件原生安装确认/严格模式/离线运行/配置/网络隔离 |
| 构建 | Debug APK、AndroidTest APK、优化 Release APK、Release AAB 通过；使用 `-PofflineAudit`，无 Firebase 收集及 mapping 上传 |
| Lint | Debug、Release 均 0 Error/Fatal；仍有 Warning，未声明仓库零警告 |
| 目录工具 | 本地生成并验签含两个示例的静态目录；覆盖仅撤下、无 Release 附件的发布方式 |

APK、命令日志、目录预览和机器可读证据位于 `build/plugin-verification/`；正式交付的 Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。机器记录 `verification.json` 包含 APK 哈希、日志哈希、Git SHA、dirty source fingerprint 和系统 WebView 信息。生产签名密钥未使用；Release smoke 使用本机 Android 默认调试证书签署独立目录中的优化 APK，仅用于测试。常规 Release APK/AAB 是离线未签名审计产物，不能直接提交 Play。

## 环境与回归修复

- API 23 系统 WebView 44.0.2403.119、API 31 系统 WebView 91.0.4472.114 不支持需要的安全 API，显示更新提示并拒绝运行插件；不能升级到兼容 WebView 的系统无法使用插件运行页。安装、校验及配置保留仍有测试覆盖。API 36 使用 WebView 134.0.6998.135 实际运行 SDK。
- API 36.1 镜像最初由旧工具生成 `target=android-0`，出现 SurfaceFlinger / `hasReadColorBufferDma` 系统崩溃。使用校验过官方 SHA-1 的 Command-line Tools 22.0 新建 `target=android-36.1` AVD 后稳定通过。中断记录未计入通过；同类问题参考 [Android 官方问题记录](https://issuetracker.google.com/issues/546200928)。
- Debug 仪器测试更新 Espresso 3.7 / runner 1.7，修复 API 36 小版本不再提供反射 InputManager 接口的问题，见 [官方测试库说明](https://developer.android.com/jetpack/androidx/releases/test)。优化版 smoke 使用原有 Java runner 1.4 和框架 UI API，避免为了测试保留生产 R8 已合法删除的 Kotlin ABI；仅测试 APK 忽略 JUnit 可选的桌面 JVM management 引用，生产 keep 规则未因此放宽。
- 原键盘测试等待异步加载完成并明确选择对话框根窗口，避免点击尚未启用的添加按钮或选中失焦窗口。
- API 31 复现的旧语言设置首次恢复问题已修复：在 AppCompat 绑定基础 Context 前准备已保存语言，原语言/页面回归通过。
- 插件撤下绑定签名清单内容哈希，避免重新压缩绕过。授权撤销与正在保存的授权串行处理，迟到确认不恢复权限。BLE 已提交写入被中断会返回 OUTCOME_UNKNOWN，排队未执行请求返回 SESSION_ENDED。

## 尚未完成的发布工作

1. 真实 SPP 设备、BLE 外设及安全锁屏/高吞吐无线行为验收；传输替身不能替代硬件。
2. 配置受保护的插件审核签名 secret，执行 GitHub Releases / Pages 部署，实际走通公网下载、确认更新和撤下同步。当前仓库只具备发布工具和工作流，未向远程发布。
3. 合并并发布中英文隐私政策增补，按实际 Release 核对 Console Data safety、审核演示及 Play 送审状态。草稿见 [发布核对清单](PLUGIN_RELEASE_CHECKLIST.md)。
4. 离线备份仓库外专用作者/审核私钥；位置和轮换说明见 [发布文档](PLUGIN_PUBLISHING.md)。

本轮未适配卷线器网站插件，未建设账号或上传审核后台，也未发布应用或提交审核。
