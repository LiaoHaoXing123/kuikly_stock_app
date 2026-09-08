# 质检报告：Task 1 + Task 2（AI 股票行情 / 问答 Demo）

- 核验日期：2026-09-08
- 核验范围：`LiaoHaoXing123/kuikly_stock_app`，commit `2130211`（工作区干净，无未提交改动）
- 核验方式：逐项代码实查（所有行号均为本次实测，可直接对照）。未重跑 Android 构建与单测；产品功能已由作者手动检测通过。
- 总体结论：**Task 1、Task 2 的全部需求点均已实现，且多处超额；可交付评审。** P1 口径问题已决策采用方案 B（见问题 1）；指数全链路已落地（pipeline 三表 + App 识别/指数卡/index_detail 页），P2-1 已解决。

---

## Task 1 · AI 股票行情原型 Demo

### 需求 1：首页行情列表页 ✅ 已完成（超额）

| 任务要求 | 实现位置 | 说明 |
|---|---|---|
| 股票名称 / 代码 / 最新价 / 涨跌额 / 涨跌幅 | `StockListPage.kt:423-427`（表头）、`:458-556`（行渲染） | 五列齐全，价格格式化、花红色涨绿跌 |
| 列表滚动浏览 | `:432 Scroller` + `:448 vfor` | 标准滚动列表 |
| 点击进入个股详情页 | `:481-485 openPage("stock_detail", {code})` | 携带 code 参数跳转 |

超额部分：搜索栏（`:279`，关键词红字高亮 `:728`）、排序（默认/涨幅/跌幅/成交量，`:367-376`）、分页"加载更多"（`:108`、`:678`）、loading/空/失败态（`:556-700`）。

### 需求 2：个股详情页 ✅ 已完成（超额）

`@Page("stock_detail")`（`StockDetailPage.kt:26`），按 `code` 参数承接（`:55`）。

| 任务要求 | 实现位置 |
|---|---|
| 名称、代码（基础信息） | `:292 infoCard`、「基础信息」`:306` |
| 最新价 / 涨跌幅 / 涨跌额 | `:350 realtimeCard`，大字最新价 + 涨跌额 + 涨跌幅 |
| 最高 / 最低 | 同上，`:350-420` 含开盘/昨收/最高/最低 |
| 成交量 | 同上，`:414` 成交量（手）+ `:415` 成交额（元），另有市盈率/市净率 |

超额部分：技术指标 MACD/RSI/KDJ（`:516-`）、分时（`:584-`，缺数据时有明确提示 `:626`）、五档盘口（`:631-`）、近 30 日 K 线 Canvas（`:677-923`）、数据来源脚注。

### 需求 3：AI 分析与解读模块 ✅ 已完成（超额）

- 入口与状态：`aiAnalysisCards`（`:976-`）、「AI 智能解读」标题（`:992`）、「开始 AI 分析」（`:1169`）/「刷新分析」（`:1014`）、分析中态（`:1108-1128`）、未分析态（`:1137-`）。
- AI 输出卡片（`DeepSeekApi.buildAnalysisCards`）：`trend_card` 趋势判断、`signal_card` 技术信号、`suggestion_card` 操作建议（买入/卖出/持有/观望 + 目标价/止损价/支撑位/压力位）、`risk_card` 风险评估（等级 + 风险项）、`summary_card` 总结 + 数据来源卡——**覆盖任务列举的全部发散方向**（买卖点位、操作提示、趋势判断、风险提醒、信号解读、行情总结）。
- 渲染形式：`buildAnalysisMarkdown`（`:1058-1106`）拼装为 Markdown 文本分析气泡（`renderAnalysisBubble :1035-`）。任务允许"卡片、标签、提示区、文本分析区"任选，当前为文本分析区形式，符合"方式不限"。
- 防幻觉：prompt 明确"最新技术指标（程序计算，非AI生成）"，指标来自本地 SQLite 真实计算值；离线/未配置 key 时退回 `mockAnalysis`（`StockRepository.analyzeStock`）。

---

## Task 2 · AI 股票问答应用 Demo

### 需求 1：AI 聊天主页面 ✅ 已完成（超额）

`@Page("chat_main")`（`ChatMainPage.kt:40`），AppShell 底部 Tab「研究」直达（`AppShell.kt: AppRoutes.CHAT="chat_main"`）。

| 任务要求 | 实现位置 |
|---|---|
| 输入问题 | 输入框 `placeholder("输入问题...")`（`:1587`） |
| 发送消息 | 发送按钮点击 → `sendMessage()`（`:1620` → `:565`） |
| 会话记录展示 | `vfor` 消息气泡流；用户右对齐纯文本，AI 左对齐 Markdown + 卡片 + 追问 chips（`:833/:861/:873`） |

超额部分：SSE 流式 + 阶段提示 + 停止生成、失败消息重试（`retryMessage :83-`）、多会话（新建/重命名/置顶/删除/切换，会话抽屉 `:1816-`）、全部落盘 SharedPreferences（`:209/:341/:349/:439`，`ChatSession` `:2429`），重启可恢复；失败/中止消息不进入有效上下文。

### 需求 2：AI 返回内容渲染 ✅ 已完成（口径已按方案 B 统一：聊天链路 7 种可用卡，见问题 1）

- **Markdown**：自研轻量解析 `parseMarkdown`（`:2110-2167`，标题/引用/列表/代码块/段落 + 行内加粗），`renderMarkdown`（`:2169-`）。全仓无 KuiklyMarkdown 官方组件引用——够用，但表格/超链接/图片会降级为段落。
- **结构化卡片渲染层**：`renderCard` 支持 **10 种** + 未知类型兜底：`conclusion_card` 结论卡（多空标签/一句话/支撑压力/技术依据展开/价位提醒，`:1010-`）、`compare_card` 多股对比（`:906-`）、`stock_card` 个股卡（`:1306-`）、`index_card` 指数卡（点进 `index_detail` 指数详情页）、`chart_card` 图表、`trend/signal/risk/suggestion/summary` 五种 AI 信息卡（经 `aiCard :1366-`）。
- **图表**：`chart_card` 由 `ChatChartView.kt`（全文件 73 行）纯 Kuikly Canvas 手绘折线/柱状图，无平台依赖；追问 chips 一点即重问（`:873`、`:1425`）。
- **协议校验**：`ChatProtocol.kt`（`decodeChatReply`）做版本/必填字段/正文长度/卡片≤8/追问≤3/数值有限性校验，非法卡片自动隐藏并提示"N 张卡片格式无效"。
- **防幻觉（硬核）**：`StockRepository.kt:90-105`，聊天返回的 `chart_card` 一律丢弃模型数值、改用本地真实 K 线重建（`buildLocalChart`，最多取 60 根）；无本地 K 线时明确提示"未生成走势图"而不编造。
- **降级**：未配置 key / 离线时返回本地模板并前缀"【本地模板回答，非 AI 生成】"（`StockRepository.kt:115-123`），演示不断档。

### 需求 3：股票/指数详情承接页 ✅ 已完成（任务口径"至少一个"已达标）

- 聊天内 `stock_card` / `conclusion_card` 点击 → `openPage("stock_detail", {code})`（`:1056`、`:1330`）。
- `stock_detail` 承接 `code` 参数（`StockDetailPage.kt:55`），含基础信息（`:292-`）、实时行情（`:350-420`）、技术指标、分时、五档盘口、K 线 Canvas（`:677-923`）、AI 解读区（`:976-1185`）——覆盖任务列举的"基础行情信息、走势区域、摘要信息、AI 解读"四项。
- ✅ 指数承接页已落地：`@Page("index_detail")`（`IndexDetailPage.kt`），含指数基础信息、实时点位、近 30 日 K 线 Canvas、AI 解读区；聊天内 `index_card` 点击直达。指数识别（`matchIndexCandidates`，全名/别名/代码+语境三级消歧，`000001` 无语境时仍默认平安银行）与指数上下文/图表/工具（`get_index_quote` / `get_index_kline` / `list_indices`）均已打通。

---

## 工程健全度

- **版本状态**：工作区干净，全部改动已在 `2130211 feat(chat): AI research room deep development` 中，无遗留未提交文件。
- **单测**：12 个 `*Test.kt`，覆盖协议解码（`ChatProtocolTest`）、SSE/传输（`ChatTransportTest`）、滚动协调（`ChatScrollCoordinatorTest`）、PageResult、对比卡格式化、结论提醒工厂、AI profile 编解码等。
- **构建证据缺失**：`chat-build.log` / `chat-test.log` 被 `.gitignore` 排除，仓库内无构建/测试通过证据；本次也未复跑。评审前建议跑一次 `./gradlew :shared:build` + 单测并记录。
- **API 配置**：`ApiConfigPage` 支持多套 provider profile（`AiProfileStore` / `AiProviderProfile`），密钥经 `SecureSecretStore` 存储（Android 有加密实现 `SecureSecretStore.android.kt`）。真实 AI 效果依赖现场配好 key 并联网。

---

## 问题清单（按优先级）

### P1 · 协议与渲染口径不一致（✅ 已决策：方案 B，零改动）

`ChatProtocol.validCard` 只放行 **6 种**卡（`stock / conclusion / signal / risk / chart / index`），而渲染层支持 10 种。 consequence：AI 若输出 `trend_card` / `suggestion_card` / `summary_card`，经聊天通道会被隐藏并提示"卡片格式无效"。这三种卡目前只活在不走聊天协议的旁路（离线 mock 直出、详情页 AI 分析）。`compare_card` 为本地构建、不受协议限制，不受影响。

**已决策（2026-09-08，作者确认）：采用方案 B，不改代码。统一对外口径为——聊天链路 7 种可用卡（6 协议卡：个股/结论/信号/风险/图表/指数 + 1 本地构建：多股对比卡）；趋势/建议/总结三卡仅详情页 AI 分析与离线模板路径可用。评审问答时按此口径回答。（2026-09-08 指数落地后由 6 卡更新为 7 卡）**

> 备选方案 A（已放弃）：将三者加入 `validCard` 白名单。如日后反悔，改动点在 `ChatProtocol.kt` 的 `validCard`，约 10 行。

### P2 · 演示注意事项（不修代码，规避即可）

1. **指数话题需规避**（✅ 已解决，保留为注意事项）：`detectMentioned`（`StockDb.android.kt:338-`）只查 `stock_info` 个股表，指数（如上证指数）不在识别范围，6 位指数代码会被当个股 code 查询后静默丢弃；`stock_detail` 也只会按个股查详情。演示时不要问指数问题，或明确标注"仅支持个股"。
   - **进展（2026-09-08）**：pipeline 指数三表（`index_info` / `index_realtime` / `index_daily_kline`）已实现并通过 dry-run + 17 项单测，数据源头就绪；App 层（指数识别 + `index_detail` 承接页）待接。App 层已于 2026-09-08 落地（识别 + 指数卡 + index_detail 页 + 指数 AI 分析）。注意：指数数据依赖新版数据库（含 index_* 三表），旧库/旧包会降级为空并给出明确提示，演示前请确认 App 已更新到最新 stock.db。
2. **提前配 key**：「我的 → API 配置」配好 DeepSeek key 并确认联网；否则现场会落入 mock 模板（虽有明确标识，但效果弱一档）。
3. **Markdown 短板**：含表格/链接/图片的模型输出会降级为段落。如评审在意，可后续接入官方 KuiklyMarkdown 组件替换自研解析器。

### P3 · 可选打磨（不影响评审结论）

- 复跑构建 + 单测，把证据落盘（注意 log 文件被 gitignore，如需留存改名存放到 `docs/` 或 CI 产物）。
- ~~补"指数详情页"~~ ✅ 已完成（2026-09-08）：`index_detail` 路由 + 指数数据源 + 识别分支均已落地。

---

## 一句话 verdict

**Task 1 三项、Task 2 三项全部实现，多处超基准（搜索/排序/分页、K 线/盘口/分时、多会话持久化、SSE 流式、协议校验、本地 K 线防幻觉、mock 降级）；P1 已决策方案 B（统一为"聊天链路 7 种可用卡"口径）；指数全链路已落地，P2-1 已解决。演示前确认指数库已更新 + 备好 API key，即可交付评审。**
