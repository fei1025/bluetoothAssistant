# 支持者徽章与 Google Play Billing

本文档记录 supporter_badge 一次性商品的产品约束、代码结构和维护方式，供后续开发者或 Codex 修改。

## 产品定义

- Google Play 商品 ID：supporter_badge
- 商品类型：一次性商品（INAPP）
- 权益类型：非消耗型永久权益
- 美国基准价：在 Play Console 配置为 USD 1.00
- 用户文案：一次性购买，不是订阅，不会自动续费

价格不保存在代码中。应用始终使用 ProductDetails.OneTimePurchaseOfferDetails#getFormattedPrice() 显示 Google Play 返回的本地化价格。

该商品绝不能调用 consumeAsync()。消费购买会让商品重新变为可购买状态，与“永久徽章”的承诺冲突。购买进入 PURCHASED 后必须调用 acknowledgePurchase()；PENDING 状态不能授予权益。

## Play Console 配置

1. 在包名 com.zzf.bluetoothsmp 下创建一次性商品 supporter_badge。
2. 创建 Buy 购买选项，不要创建 Rent、订阅或多数量购买。
3. 设置 USD 1.00 基准价，检查自动生成的地区价格和中英文商品说明。
4. 激活商品并上传包含 Billing 实现的签名 AAB 到内部测试轨道。
5. 将测试 Google 账号配置为许可测试账号，再从 Google Play 测试轨道安装应用。

商品是否存在、是否激活和实际价格都属于 Play Console 配置，无法仅通过本仓库代码完成。普通侧载 Debug APK 不适合作为完整的真实购买验收方式。

## 代码结构

- app/build.gradle：声明 Google Play Billing Library 版本。
- billing/SupporterBillingManager.java：BillingClient 的唯一封装入口，负责查询商品详情、选择非租赁 Buy offer、发起和恢复购买、确认购买并发布 UI 快照。SUPPORTER_BADGE_PRODUCT_ID 是客户端商品 ID 的唯一来源。
- billing/SupporterEntitlementResolver.java：不依赖 Android 的纯 Java 状态归并器，优先级为 PURCHASED > PENDING > NOT_OWNED。
- MainActivity.java：创建和关闭 manager；showSupporterDialog() 创建弹窗；updateSupporterUi() 控制按钮和 Toolbar 徽章；getSupporterMessage() 映射本地化文案。
- res/menu/home_menu.xml 与 res/drawable/ic_supporter_badge.xml：定义支持入口和金色徽章。
- 三套 strings.xml：默认、简体中文和英文购买文案必须同步修改。

## 状态和数据流

1. MainActivity 启动时创建 manager 并注册 Snapshot listener。
2. manager 连接 Google Play，同时查询商品详情和当前 INAPP 购买。
3. 商品详情提供 offerToken 和本地化价格；没有可用 Buy offer 时购买按钮保持禁用。
4. queryPurchasesAsync() 的成功结果是当前权益的客户端权威来源：
   - 匹配商品且为 PURCHASED：缓存 owned、显示徽章、确认未确认订单。
   - 匹配商品且为 PENDING：不显示徽章，提示等待付款完成。
   - 成功查询但不存在匹配商品：清除缓存权益。
   - 查询失败：保留最近一次已确认的 owned 缓存，避免购买者离线时徽章闪烁消失。
5. onResume() 和“恢复购买”都会重新查询商品与购买记录，因此换设备、重装、延迟付款完成或退款后可以重新同步。

本地缓存位于 SharedPreferences supporter_billing/last_confirmed_owned，只用于离线 UI。不要把它改成购买的唯一凭证。

## 修改指南

- 修改徽章外观：替换 ic_supporter_badge.xml，保持资源名不变。
- 修改弹窗：编辑 MainActivity 中三个 supporter UI 方法，并同步三套字符串资源。
- 升级 Billing Library：先阅读对应迁移说明，再更新 app/build.gradle；重点检查 pending purchases、商品详情查询、offer token 和购买查询 API。
- 修改商品 ID：生产商品 ID 应视为不可变。如果确需新商品，应在 Play Console 创建新 ID，并设计旧购买的兼容或迁移规则，不能只改常量。
- 增加更多付费商品：把 manager 扩展为商品目录和 entitlement 映射，不要复制多个 BillingClient；前台应只有一个活动连接。
- 增加服务端校验：把 purchase token 发往后端并使用 Google Play Developer API 验证和确认；后端接管确认后，应移除客户端确认，避免双重职责。

当前实现是适合低价值装饰权益的纯客户端方案，不能防止修改版客户端伪造 UI 状态。若未来徽章同时解锁高价值功能，应迁移到服务端校验，并处理退款、撤销和实时开发者通知。

## 测试

本地回归：

    .\gradlew.bat testDebugUnitTest assembleDebug

Play 内部轨道至少验证：

- 正常购买后立即出现徽章，订单已确认。
- 取消购买不授予权益。
- Pending 购买完成后重新进入应用能解锁。
- 已购买账号不能重复付款。
- 重启、卸载重装和同账号换设备可以恢复。
- 离线启动保留最近一次已确认徽章。
- 退款或撤销后，下次成功同步移除徽章。
- 中英文页面显示 Play 返回的实际币种和价格。

状态归并单元测试位于 SupporterEntitlementResolverTest.java。新增购买状态分支时，应先扩展这里的测试，再修改 manager。
