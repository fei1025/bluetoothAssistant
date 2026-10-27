# 插件打包、审核与官方目录

要求 Python 3.10+、JDK 17。工具只使用标准库和仓库内 `PluginCrypto.java`，无需 pip。

## 作者工作流

以下为 PowerShell 示例。私钥必须放在源码目录和 Git 仓库之外：

```powershell
python -B scripts/plugin_tool.py keygen C:/secure/author.pk8
python -B scripts/plugin_tool.py pack samples/plugins/classic --author-key C:/secure/author.pk8 --output C:/temp/classic-author.btplugin
```

工具同时生成 `.pk8.pub` 公钥侧文件，不覆盖已有文件。源码的整数 version 每次修改必须递增；发布后的包保持原字节，不重新压缩或重新签名覆盖同版本。Debug 安装作者包用于调试。正式安装之前需要独立审核人审核源码、资源、声明能力及设备行为，再执行：

```powershell
python -B scripts/plugin_tool.py review C:/temp/classic-author.btplugin --review-key C:/secure/review.pk8 --output C:/temp/classic-reviewed.btplugin
python -B scripts/plugin_tool.py verify C:/temp/classic-reviewed.btplugin --review-keys app/src/main/assets/plugins/review-keys.json
```

审核签名不是自动审核服务。人工检查：离线资源完整、无隐藏远程通信/原生载荷、能力与页面说明一致、设备操作有明确用户触发、协议数据处理和错误提示正确。将审核通过的第三方包放入 `plugins/approved/`，ID 不得与内置示例重复。作者姓名和 ID 不代表官方身份，只有审核根签名决定正式版是否接受。

## 当前密钥与备份

本次生成的专用作者与审核私钥保存在本机仓库外：

`C:/Users/ZZF/.codex/secrets/bluetoothAssistant-plugins/`

- `official-examples-v1.pk8`：两个内置通用示例的作者密钥。
- `review-v1.pk8`：插件和官方目录的审核密钥。
- 相应 `.pub`：公开的 DER Base64 公钥。

请将两份私钥和公钥侧文件离线加密备份。它们与 APK / Play 上传密钥不同，未配置到远程服务。公开审核根在 App 的 `assets/plugins/review-keys.json`，公开作者根在 `plugins/official-author.pub`。更换审核根需要 App 更新；先在新版本信任新公钥，再逐步切换签名。作者密钥丢失不能直接以新密钥覆盖相同 ID。

## GitHub Pages / Releases

默认目录为 `https://fei1025.github.io/bluetoothAssistant/plugins/catalog.json`。本次只提供工具和工作流，尚未发布此目录。目录 envelope 为 `{payload,key,signature}`，payload 是 Base64 编码的原始 JSON 字节，签名验证这些字节；payload 含递增 revision、plugins、revoked。

每个插件条目包含 ID、名称、作者、version、HTTPS URL、整个 ZIP 的 sha256、原始 plugin.json 的 contentSha256。两种哈希分别用于下载完整性与撤下身份。同步拒绝旧 revision 或同 revision 不同内容。已知撤下记录在本机累积，后续目录不能解除，离线不会恢复问题版本。需要恢复时发布修复后的新版本。离线/同步失败无法获知服务器新撤下记录，安装运行检查使用已同步集合。

本地预览（输出目录必须不存在）：

```powershell
python -B scripts/build_plugin_catalog.py --tag plugins-v1 --review-key C:/secure/review.pk8 --output C:/temp/plugins-preview
```

输出 `release/*.btplugin`、`site/plugins/catalog.json`。生成器先验签全部包，并校验目录私钥、公钥、App 信任根匹配。`plugins/catalogue.source.json` 维护 revision 和撤下的 **contentSha256**；撤下时递增 revision，并保留之前全部撤下记录。可以删除对应目录条目，但仅删除条目不会禁止已安装包运行。

发布前设置仓库 Pages 来源为 GitHub Actions，并添加受保护的 GitHub Actions secret `PLUGIN_REVIEW_KEY_PKCS8`（审核私钥二进制的 Base64）。不要在日志、Issue 或仓库文件中保存私钥、Base64 私钥或 APK 签名密钥。工作流在 runner 临时目录以 0600 写入密钥并在结束清理。

手动运行 **Publish reviewed plugins**，传入新的 `plugins-v1` 等 tag。工作流先测试和验证，再新建不可覆盖的 Release，最后部署 Pages。需正常配置 GitHub Pages environment 权限。若 Release 成功但 Pages 失败，可重跑 Pages job；不要重跑 prepare 覆盖 Release。若确需新一轮完整发布，使用新 tag、递增目录 revision，复用相同已发布包的原字节。

用户点击同步后才获取目录和撤下记录，更新必须点击确认，运行中的包不能更新。无账号、上传后台或自动审核；HTTPS 地址与本地导入仍执行正式版全部校验。

## 发布核对

执行 `python -B -m unittest discover -s scripts/tests -p "test_*.py"`、JVM、API 23/31/36 仪器测试和优化 Release smoke；硬件及 Play 审核步骤见 [PLUGIN_RELEASE_CHECKLIST.md](PLUGIN_RELEASE_CHECKLIST.md)。历史 2.8 的真机或 Console 记录不证明本次平台候选通过。
