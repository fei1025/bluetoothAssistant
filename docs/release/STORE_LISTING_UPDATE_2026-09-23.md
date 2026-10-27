# Google Play 中英文商店文案更新记录

操作时间：2026-09-23 08:19（Asia/Shanghai）。

应用：`com.zzf.bluetoothsmp`。

## 最终状态

已通过 Chrome 中的 Google Play Console 更新、保存并提交本次 **9 项商品详情文字更改**。提交后页面显示“正在审核中的更改”，同时显示“正在快速检查常见问题”；提示检查成功后会立即送审。

**尚未确认审核通过或公开上线。** 最后发布日期仍显示 2026-09-10；自管式发布模式仍为关闭，未修改该设置。

[发布概览](https://play.google.com/console/u/0/developers/5645418938262583179/app/4975417359732973923/publishing)

## 内容来源与范围

唯一文案来源：[store-listings.json](store-listings.json)。本次未改写源文件。

源文件 SHA-256：`D735399A18D99641373628893C286F2A0FA58350EE170B3D9687525EB56E8195`。

现有 Console 结构是：默认商品详情仅有 `zh-CN`；美国自定义商品详情有 `zh-CN` 和 `en-US`。本次保留该结构，没有新增默认英文语言或扩大美国自定义详情的受众。

| 商品详情 | 语言 | 更改 |
|---|---|---|
| 默认商品详情 | zh-CN | 应用名称、简短说明、完整说明 |
| 美国自定义商品详情 | en-US | 应用名称、简短说明、完整说明 |
| 美国自定义商品详情 | zh-CN | 同步应用名称；简短说明和完整说明沿用默认详情，因此一并更新 |

中文名称：**SPP蓝牙助手：经典蓝牙串口调试**。

英文名称：**Bluetooth SPP Serial Terminal**。

字段长度复核：中文为 16 / 61 / 880；英文为 29 / 78 / 1985，分别满足页面显示的 30 / 80 / 4000 限制。填入后的表单计数及预览全文已核对。

## 操作与验证

1. 核对目标包名，读取原有默认详情和美国自定义详情。
2. 更新中文默认详情三项文字；先保存草稿，并重新打开确认文案已保留。
3. 更新美国自定义详情的英文三项文字，以及该详情独立保存的中文应用名称。未更改中文简介和全文的继承设置。
4. 用户明确确认现有图标、置顶大图和截图为普通设计及真实截图，无 AI 生成图片；据此选择“不为资源添加标签”。未替换任何图片或视频。
5. 分别完成保存。美国详情重新打开后的详细信息仍为美国受众、100% 展示、无结束日期。
6. 保存默认详情时确认了“美国”详情沿用资源的同步范围；自定义英文资源不受覆盖。
7. 发布概览逐项核对，待审列表只有本次 9 项文字变更，无安装包、Data safety、测试轨道或国家/地区更改。
8. 点击“提交 9 项更改以供审核”并确认。最终列表已移入“正在审核中的更改”，没有宣称检查完成、审核通过或上线。

## 未修改的内容

- 图标、置顶大图、截图、视频。
- 安装包、版本号、正式/测试轨道。
- 国家/地区、展示比例、展示期限。
- 数据安全、隐私政策、账号权限、自管式发布模式。

## 更新前文字备份

### 默认中文

名称：`蓝牙调试助手-SPP(串口)通信`

简短说明：`蓝牙SPP(串口)通信`

完整说明：

```text
可以主动连接SPP通信,也可以当成一个被动的SPP服务器串口通信
先支持树莓派蓝牙和常用的数据蓝牙
```

美国详情的中文名称为上述旧名称，中文简短说明和完整说明沿用默认详情。

### 美国详情英文

名称：`Bluetooth - SPP Communication`

简短说明：`Bluetooth Debugging Assistant - SPP (Serial Port) Communication`

完整说明：

```text
1. Can be actively connected to SPP (classic bluetooth) communication.
2. Can also be used as a passive SPP server serial communication
3. First support Raspberry Pi Bluetooth and commonly used data Bluetooth
4. You can modify the port (UUID) to connect to Bluetooth.
5. Can modify the port of the Bluetooth serial port (UUID))
6. Bluetooth development
```
