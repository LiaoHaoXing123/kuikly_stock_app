# Changelog

## [v0.0.02-dev3] - 2026-08-25（多会话 + 抽屉侧栏 + 快捷提问）

> 依据用户要求重构首页交互，已在 vivo 真机（V2505A）实测通过。

### 1. 顶栏精简
- 左上角：☰ 抽屉按钮（展开/收起历史对话侧栏）
- 中间：当前会话标题
- 右上角：【大盘行情】入口（原左上角移入）
- 移除顶栏的【新建对话】【开发者】按钮与模式徽标（功能迁入抽屉/底部）

### 2. 底部输入区
- 输入框上方第一行：左侧【＋ 新建对话】按钮（固定）+ 右侧【快捷提问】chip
- 快捷提问动态取**数据库第一条股票**（`请帮我分析XXX`，StockDb 第一条），换库自动更新
- 点击快捷提问 chip 直接填入并发送

### 3. 多会话（【新建对话】语义重构）
- 点击【新建对话】→ **新增一个独立会话页面**（不清空、不覆盖旧会话）
- 左侧抽屉展示全部历史会话（标题 + 消息数，当前高亮），点击即切回
- 会话持久化到 SharedPreferences（多会话 JSON：sessions + activeId），**退出 App/清后台也保留，除非卸载**
- 顶栏标题随会话切换更新

### 4. 抽屉侧栏（历史对话）
- 会话列表 + 底部【数据来源（开发者选项）】：离线/在线模式切换、检测 AI 服务连接、收起
- 【开发者/离线模式】模块已从顶栏移入抽屉

### 涉及文件
- shared/src/commonMain/kotlin/com/kuikly/stock/pages/ChatMainPage.kt

# Changelog

## [v0.0.02-dev2] - 2026-08-25（UI/交互优化：K线图 + AI气泡 + 会话持久化）

> 依据用户反馈完成界面与交互优化，已在 vivo 真机（V2505A）实测通过。

### 1. K 线图表：占位 → 真实 30 日蜡烛图

- **渲染真实 K 线**：用 Kuikly Canvas 绘制 30 根日 K 蜡烛（红涨绿跌，蜡烛实体=开收盘，影线=最高最低，含网格、最高/最低价刻度、首尾交易日标注），替换原 `[K线图表区域]` 调试占位文本
- **文案业务化**：加载中显示 `K线数据加载中...`；无数据/失败显示 `K线数据获取失败，请重试`（不再永久停留在占位）

### 2. AI 智能解读：聊天气泡 + loading 防重复

- **微信式聊天气泡**：AI 分析结果改为浅蓝气泡（`#F1F5FF`，左对齐、显式 width 自适应、圆角），内部用 Markdown 排版（复用聊天页 renderMarkdown），解决大段黄色无效底色/文字顶左问题
- **loading 防重复**：`triggerAIAnalysis` 加 `isAnalyzing` 防抖；分析中显示 `AI 正在分析中...` 加载卡片，开始/刷新按钮隐藏（杜绝重复提交）
- **修复响应式快照 bug**：`aiAnalysisCards` 原用局部 `val analysis = ctx.aiAnalysis` 作 velseif 条件（构建期快照），分析完成后永远停留在"尚未进行 AI 分析"（velse 永不执行）——改为直接读 `ctx.aiAnalysis`（observable 响应式）

### 3. 会话持久化 + 【新建对话】

- **对话不再丢失**：消息列表序列化到 SharedPreferences（`chat_history_v1`），`viewDidLoad` 恢复；离开页面（进列表/详情）再返回，历史对话完整保留
- **新增【新建对话】按钮**（顶栏）：一键清空当前会话 + 清除持久化记录，并给出提示条

### 4. 布局细节

- K 线卡片/AI 解读区/未分析卡片/消息列表内边距压缩（减少留白）

### 涉及文件

- shared/src/commonMain/kotlin/com/kuikly/stock/pages/StockDetailPage.kt
- shared/src/commonMain/kotlin/com/kuikly/stock/pages/ChatMainPage.kt

# Changelog

## [v0.0.02-dev] - 2026-08-25（SQLite 进 APK 全链路验证版）

> 目标：**App 装好即用，不再需要数据线连接手机和电脑**。数据全部本地 SQLite（assets/stock.db），AI 直连 DeepSeek。本版在 Android 模拟器（Pixel_6, Android 16）上完成全链路实测。

### 本次修复的关键问题（均在模拟器实测发现并验证）

**1. stock.db 打进 APK 后 App 读不到 → 数据层全空（根因 + 修复）**

- 症状：initStockDb 的 assets.openFd("stock.db") 抛异常（被 catch 吞掉），files/stock.db 从不生成，SQLite 数据层不可用
- 根因：AGP 默认把 .db 等非图片资产 deflate 压缩进 APK，AssetManager.openFd() 对压缩资产抛 FileNotFoundException（"probably compressed"）
- 修复：shared/build.gradle.kts 增加 androidResources { noCompress += "db" }，stock.db 以未压缩方式存储；ensureDb() 对 openFd 失败做容错（缓存缺失才拷贝，避免每次启动重拷）
- 验证：APK 内 assets/stock.db 由 deflate 变 stored；设备 files/stock.db 与资产 SHA256 一致（5095424 字节）

**2. 断网时 AI 请求挂死 1-2 分钟（DNS 阻塞不受超时控制）**

- 症状：无网络时 DeepSeek 调用卡在"正在分析/思考"，withTimeout(95s) 也救不回（阻塞在不可取消的 DNS lookup）
- 修复：androidMain/ApiClient.kt 自定义 Dns 把解析丢到独立线程并加 5 秒超时，超时抛 UnknownHostException 快速失败；配合 connectTimeout(10s)，断网场景约 5-15 秒回退本地模板
- 验证：飞行模式下发送消息 → 5 秒内失败 → 本地模板卡片正常渲染

**3. SQLite 连接泄漏（logcat 告警）**

- 症状：每次查询 openDatabase() 后不 close，logcat 报 "A SQLiteConnection object ... was leaked!"
- 修复：openDb() 改为缓存单例只读连接（App 生命周期内复用，进程结束由系统回收）
- 验证：修复后连续 list/detail/AI 查询不再出现泄漏告警

**4. DeepSeek key 占位 → 已填入真实 key**

- DeepSeekConfig.API_KEY 从占位符替换为 backend/.env 中的真实 key（个人 Demo，key 随 APK 分发会暴露，正式分发需自建中转）；deepseek-chat 模型实测可返回内容

### 模拟器全链路实测记录（2026-08-25）

- 通过：SQL → SQLite（convert_sql_to_sqlite.py --deploy 重跑成功：stock_info 5212 / realtime 5212 / kline 4382 / indicator 4381 / minute 21670 / orderbook 11，PRAGMA integrity_check = ok）
- 通过：列表 listStocks -> total=5212 items=5000（SQLite 路径，非 JSON 回退）
- 通过：详情 stockDetail 000001 -> info=true realtime=true kline=30 ind=2026-08-25
- 通过：通路一 AI 分析（在线）App 直连 DeepSeek，4.3 秒返回，6 张 AI 卡片渲染
- 通过：通路二 AI 问答（在线）analyze 600519 → DeepSeek 4 秒返回 Markdown，气泡渲染
- 通过：离线/无网络飞行模式下快速失败回退本地模板卡片
- 通过：全程无崩溃、无 FATAL

### 涉及文件

- shared/build.gradle.kts（noCompress "db"）
- shared/src/androidMain/kotlin/com/kuikly/stock/network/ApiClient.kt（DNS 5s 超时）
- shared/src/androidMain/kotlin/com/kuikly/stock/data/StockDb.kt（单例连接 + 日志标记）
- shared/src/commonMain/kotlin/com/kuikly/stock/network/DeepSeekApi.kt（真实 key + 链路日志）
- shared/src/commonMain/kotlin/com/kuikly/stock/data/StockRepository.kt（分析链路日志）

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
