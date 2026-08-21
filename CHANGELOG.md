# Changelog

## [v0.0.01] - 2026-08-21（内测版 / Alpha）

首个可完整走通「手机 App ↔ 局域网 FastAPI ↔ DeepSeek 真实 AI」全链路的版本，主要用于内测验证，可能存在未发现的缺陷。

### 三层核心根因修复（此前 App 无法正常使用 AI）

**1. kotlinx.serialization 编译插件缺失**

- 症状：App 请求到达后端并返回 200（DeepSeek 真生成 5~15s），但界面却显示本地模板；日志/界面报 `Serializer for class 'ChatResponse' is not found`
- 根因：`shared` 模块从未配置 `kotlin("plugin.serialization")` 编译插件，`@Serializable` 注解不生成任何序列化代码，运行时 `serializer<T>()` 必然失败，异常被 `catch` 吞掉回退模板
- 修复：根 `build.gradle.kts` 与 `shared/build.gradle.kts` 应用 serialization 插件（2.1.21），`commonMain` 显式声明 `kotlinx-serialization-json:1.7.3`；验证 `ChatResponse$$serializer.class` 等生成

**2. kotlinx 协程与 Kuikly 自研协程库混用**

- 症状：AI 回复延迟到「下一次发送」才显示、界面抛 `Assertion!`、连接检测弹窗卡在"正在检测"
- 根因：Kuikly 的 `lifecycleScope.launch` 是自研协程（`EmptyCoroutineContext`，默认在渲染线程执行），Ktor 网络 suspend 挂起后**恢复在 OkHttp 回调线程**；在 Kuikly 协程里套 kotlinx 的 `withContext(Dispatchers.Main)` 会使 observable 在错误上下文修改，响应式刷新失效
- 修复：移除全部 `withContext(Dispatchers.Main)`；网络调用后、更新 observable 前统一用 Kuikly `delay(0)`（内部 `setTimeout` 走 Bridge 原生层）切回渲染线程——成功与 catch/finally 分支都要加；`kotlinx.coroutines.delay` 一律换成 Kuikly `delay`

**3. Kuikly Text 渲染花屏/乱码（vivo 真机）**

- 症状：单个 Text 多行 + `lineHeight` 文字重叠/花屏/乱码（如黄卡 `葳莢曩齏…`、`㵟作建议…`）
- 修复：markdown 段落/列表/引用/卡片/代码块全部改为**按行渲染**、去除 `lineHeight`；行内 `**加粗**` 改用 `RichText + Span` 原生富文本渲染（自动换行且词语不被拆断）

### 功能与体验优化

- **微信式聊天气泡自适应**：短句气泡按估算内容宽度收窄（不再撑满整行），长文本或带卡片/快捷按钮时固定最大宽度换行
- **Markdown 渲染完善**：标题去除星号标记；列表/引用支持行内加粗；列表兼容 `·`（中文圆点）
- **AI 服务连接检测**：失败自动 `ApiClient.recreate()` 清连接池 + 自动重试一次（根治 keep-alive 死连接导致的 `unexpected end of stream`）；弹窗结果改为 `ObservableList + vfor` 实时刷新
- **离线模式**：切换离线后不再调用后端；回答开头醒目标识【当前处于离线模式，行情数据不可用】；**不再输出虚假价格/涨跌幅/买卖点**（个股仅基本面参考，大盘仅本地样本统计，卡片价格显示 `-`）；快捷按钮改为基本面/风险类
- **网络可靠性**：Android OkHttp 强制 IPv4 + `NO_PROXY` + socket 级超时（connect 10s / read 30s）；后端清理为单实例（此前 8000 端口双监听导致请求随机分发）

### 涉及文件

```
build.gradle.kts
shared/build.gradle.kts
shared/src/commonMain/kotlin/com/kuikly/stock/network/ApiClient.kt
shared/src/commonMain/kotlin/com/kuikly/stock/network/ApiEndpoints.kt
shared/src/commonMain/kotlin/com/kuikly/stock/network/ApiService.kt
shared/src/commonMain/kotlin/com/kuikly/stock/data/Models.kt
shared/src/commonMain/kotlin/com/kuikly/stock/data/StockRepository.kt
shared/src/commonMain/kotlin/com/kuikly/stock/data/LocalDataService.kt
shared/src/commonMain/kotlin/com/kuikly/stock/data/DataSourceManager.kt
shared/src/commonMain/kotlin/com/kuikly/stock/pages/ChatMainPage.kt
shared/src/commonMain/kotlin/com/kuikly/stock/pages/StockListPage.kt
shared/src/commonMain/kotlin/com/kuikly/stock/pages/StockDetailPage.kt
shared/src/androidMain/kotlin/com/kuikly/stock/network/ApiClient.kt
```

### 已知待办

- 后端 Prompt 意图识别 + 标准 Markdown 输出约束优化（减少 AI 输出的中文圆点/非标准列表）
- 气泡宽度的微信式 72% 上限方案在真机的最终视觉效果确认
