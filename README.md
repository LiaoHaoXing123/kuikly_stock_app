# Kuikly Stock — 跨平台 A 股行情分析应用

> 一套 Kotlin 代码，多端运行的 A 股（沪深）行情研究工具。基于腾讯 **Kuikly** 跨平台原生渲染框架开发，行情数据全部落在本地，主打「离线可用、本地分析」；K 线 / 分时图由 Kuikly Canvas 自主渲染，并内置可自由配置的 AI 研究助手。

https://github.com/user-attachments/assets/23088982-636d-4207-836d-b153984626b7

> 完整演示约 18 分钟，可直接在上方播放，也可[打开视频](https://github.com/user-attachments/assets/23088982-636d-4207-836d-b153984626b7)。各功能详细介绍见 [`docs/使用指南.md`](docs/使用指南.md)。

---

## 目录

- [1. 项目简介](#1-项目简介)
- [2. 核心特性](#2-核心特性)
- [3. 技术架构](#3-技术架构)
- [4. 支持平台](#4-支持平台)
- [5. 功能模块详解](#5-功能模块详解)
- [6. 数据链路：每日自动更新行情](#6-数据链路每日自动更新行情)
- [7. 项目结构](#7-项目结构)
- [8. 快速开始](#8-快速开始)
- [9. 数据管道（data-pipeline）](#9-数据管道data-pipeline)
- [10. 自动化与质量保障](#10-自动化与质量保障)
- [11. 文档索引](#11-文档索引)
- [12. 常见问题](#12-常见问题)
- [13. 免责声明](#13-免责声明)

---

## 1. 项目简介

本项目是一个使用 **Kotlin Multiplatform（KMP）+ Kuikly** 构建的 A 股行情分析与研究应用，面向「个人投资者 / 技术分析爱好者」设计。

- **数据本地化**：行情数据以 SQLite（Android）或内置 JSON（iOS / Web）形式存储在本机，不依赖在线服务也能完整浏览行情、K 线、指标。
- **自主渲染图表**：K 线、分时图完全基于 Kuikly Canvas 自研渲染，支持点击锁定、长按区间统计、缩放平移、指标副图等完整交互。
- **AI 研究助手**：可在「我的 → API 配置」中接入任意 OpenAI 兼容接口（如 DeepSeek），提问时自动携带当前股票行情 / K 线 / 指标作为上下文；不配置也能使用离线模板分析。
- **每日自动更新**：GitHub Actions 每个交易日收盘后自动抓取数据构建行情库，并通过 GitHub Release 滚动发布，App 后台自动检测更新。

> 本应用为学习 / 研究用途的示例项目，行情数据为收盘快照而非实时逐笔数据，**不构成任何投资建议**。

---

## 2. 核心特性

### 2.1 研究台首页
- 顶部展示行情数据日期，可一键手动刷新。
- 「今日盘面」根据全市场涨跌家数自动生成市场强弱判断。
- 「研究工作台」四张卡片：**AI 研究室 / 组合风险 / 全市场 / 自选盯盘**，覆盖「发现机会 → 深入研究 → 组合管控」完整闭环。
- 「今日关注」自动聚合信号：价格提醒触发优先，其次为自选股技术指标信号（如站上 / 跌破 MA5），最多展示 4 条。

### 2.2 行情列表
- 按股票名称或六位代码搜索（如「平安银行」/「000001」）。
- 支持默认 / 涨幅 / 跌幅 / 成交量多维度排序，分页加载。
- 遵循 A 股配色：红涨、绿跌、灰平。

### 2.3 个股详情页
| 区域 | 内容 |
| --- | --- |
| 实时行情卡 | 最新价、涨跌额/幅、开收高低、量额、估值，标注数据快照时间 |
| 同业比较 | 同行业样本等权均值与个股相对表现（百分点），可展开排行、切换排序 |
| 官方板块 | 东财行业板块快照：板块涨幅、领涨股、涨跌家数、市值、板块内排名 |
| K 线走势 | 日 / 周 / 月 K，MA 均线、成交量，MACD / KDJ / RSI 指标副图，自动支撑压力趋势线 |
| 分时图 | 当日价格曲线、均价、每分钟成交量，与 K 线联动标注 |
| 五档盘口 | 买卖五档价格与数量（快照展示） |
| 技术指标 | MA / MACD / KDJ / RSI 数值 |
| 主力资金 | 最近交易日 / 近 5 日 / 近 10 日净流入，可点击日期定位到日 K |
| 公司事件 | 分红除权、财报披露（数据随每日构建入库） |
| AI 智能解读 | 结论、支撑/压力价位、风险提示、引用的行情依据（可点击定位 K 线） |
| 分析记录 | 本机保存的历史分析，可查看 / 删除 |

### 2.4 K 线交互（自研 Canvas）
- **点击锁定十字光标**：单击某根 K 线，信息栏同步显示当日开高低收与涨跌幅。
- **长按区间统计**：长按设定起点 → 拖动到终点松开，区间高亮并自动计算 K 线数量、区间涨跌幅、振幅、最高/最低价、累计成交量。
- **缩放平移**：悬浮按钮「＋ / － / ◀◀ / ▶▶」或双指手势，以视图中心为锚点缩放。
- **周期与指标**：日 / 周 / 月 K 切换，MA、成交量、趋势线独立开关，副图支持关 / MACD / KDJ / RSI（RSI 为 6/12/24 三线）。
- **AI 联动**：点击 AI 给出的支撑 / 压力 / 目标价位，在 K 线上标注虚线；点击「AI 引用的行情依据」可跳转到对应日期。

### 2.5 AI 研究室
- 配置任意 OpenAI 兼容接口（服务地址 + 模型 ID + API Key），Android 端 API Key 本机加密存储。
- **带本地行情上下文提问**：提问时自动附加当前查看股票的行情、K 线、指标数据，无需手动复制粘贴。
- 多轮对话、流式输出、可停止/重试；长按消息可复制、引用、删除。
- 历史会话管理：新建、切换、置顶、重命名、删除，支持 Markdown 导出 / JSON 备份。
- 离线模式：不配置 API 也能用本地数据与模板进行分析。
- 聊天内容支持 KuiklyMarkdown 组件渲染（含图表卡片、证据卡片等结构化回复）。

### 2.6 自选盯盘
- 星标收藏 / 移除自选股；录入持仓股数、成本与起始日期（本地记录，不连接券商）。
- **价格提醒**：支持四类规则——价格高于等于 / 低于等于某值、涨幅 / 跌幅达到阈值；AI 价位可一键设提醒。
- **盈亏日历**：每个行情快照日自动记录持仓市值与当日盈亏；热力图红绿深浅表示幅度，标记跑赢 / 跑输沪深 300；头部统计日胜率、连盈连亏、最大单日盈亏、当月合计；涨跌停、提醒触发自动打点。

### 2.7 组合风险
- 组合市值、浮动盈亏、个股集中度、行业集中度计算。
- 风险规则命中提示与提醒；支持压力测试（模拟大盘下跌对组合的影响）。

### 2.8 我的 / 设置
- API 配置（多配置管理、连通性测试）。
- 在线 / 离线数据模式切换、检测 AI 服务连接。
- 浅色 / 深色主题切换。
- 手动更新行情数据、查看数据状态。

---

## 3. 技术架构

```
┌─────────────────────────────────────────────────────────┐
│                    宿主层（各端壳工程）                    │
│   androidApp（Android / WorkManager）  iosApp（iOS /     │
│   CocoaPods）  ohosApp（HarmonyOS）                       │
└───────────────┬─────────────────────────────────────────┘
                │ Kuikly 原生渲染引擎（跨端 UI + 事件桥接）
┌───────────────▼─────────────────────────────────────────┐
│          shared（Kotlin Multiplatform 共享层）            │
│  ┌───────────┬────────────┬────────────┬──────────────┐  │
│  │ pages/*   │ data/*     │ ai/*       │ network/*    │  │
│  │ 页面与交互 │ 本地库/仓库 │ AI 协议/    │ Ktor 客户端  │  │
│  │ RouterPage│ StockDb/   │ 提示词/工具 │ DeepSeekApi/ │  │
│  │ 业务页面  │ LocalData  │ ChatStream │ ChatTransport│  │
│  └───────────┴────────────┴────────────┴──────────────┘  │
│  ui/*（组件）  risk/*（组合风险）  home/*（首页聚合）        │
└───────────────┬─────────────────────────────────────────┘
                │ SQLite / 内置 JSON / Ktor HTTP
┌───────────────▼─────────────────────────────────────────┐
│        数据层（data-pipeline + GitHub Actions）          │
│   AKShare 抓取 → SQLite stock.db → Release(data-latest) │
│                    → App 自动下载更新                     │
└─────────────────────────────────────────────────────────┘
```

- **UI 框架**：[Kuikly](https://github.com/Tencent-TDS/KuiklyUI)（腾讯 TDS 的 Kotlin Multiplatform UI 框架，一套代码、六端运行、动态下发）。
- **共享逻辑**：Kotlin Multiplatform + kotlinx-serialization / kotlinx-coroutines。
- **网络层**：Ktor Client（Android 用 OkHttp 引擎、iOS 用 Darwin、JS 用 ktor-client-js）。
- **Markdown 渲染**：[KuiklyMarkdown](https://github.com/Kuikly-contrib/KuiklyMarkdown) 组件（com.tencent.kuiklybase:KuiklyMarkdown）。
- **本地存储**：Android 使用 SQLite（`stock.db`，WorkManager 自动更新）；iOS / JS 使用随包内置 JSON 资产。
- **专业图表**：Android 端「专业图」使用 WebView 加载 KlineCharts 9.8.12 渲染（K 线工具、画线、斐波那契等）。

---

## 4. 支持平台

| 平台 | 工程目录 | 状态 |
| --- | --- | --- |
| Android | `androidApp/` | ✅ 主力验证平台（真机 / 模拟器） |
| iOS | `iosApp/`（CocoaPods + shared framework） | ✅ CI 编译门禁通过 |
| Web (H5) | `shared/src/jsMain/` | 共享 JS 目标；仓库暂不包含 H5 宿主工程 |
| HarmonyOS | `ohosApp/` | 🚧 原生壳工程（ArkTS 桥接） |

> CI（`.github/workflows/build.yml`）对 Android（assembleDebug + compileKotlinJs）与 iOS（Kotlin 三架构编译 + Xcode 模拟器构建）做双端编译门禁。

---

## 5. 功能模块详解

### 5.1 首页（HomeDashboardPage）
首页自上而下：标题与数据日期 → 使用指南入口 → 今日盘面摘要 → 研究工作台（四卡片）→ 今日关注信号。设计目标是「把重要动作拆开，减少首页拥挤」。
- 研究工作台入口：AI 研究室、组合风险、全市场（行情）、自选盯盘。
- 今日关注：提醒优先，其次自选信号；自动计算、自动推送，最多 4 条。

### 5.2 行情（StockListPage）
搜索框 + 排序栏 + 股票列表。搜索支持名称与代码；排序支持默认 / 涨幅 / 跌幅 / 成交量。

### 5.3 个股详情（StockDetailPage 系列）
由多个可滚动区块组成（行情 → 同业 → 官方板块 → K 线 → 分时 → 盘口 → 指标 → 资金 → AI 依据 → 分析记录），每个区块是独立组件（见 `shared/src/commonMain/kotlin/com/kuikly/stock/pages/` 下 `StockDetail*` 系列）。

### 5.4 AI 研究室（ChatMainPage）
- 协议层：`ai/protocol/ChatSchemaV1.kt`、`DetailSchemaV2.kt`、`CardSchema.kt`、`VerdictSynthesizer.kt`。
- 传输层：`network/DeepSeekApi.kt`（OpenAI 兼容流式）、`ChatTransport.kt`、`ChatStream.kt`。
- 上下文注入：`ai/prompt/PromptContext.kt` + `ai/tool/StockTools.kt`（行情 / K 线 / 指标 / 资金 / 事件等工具）。
- 配置存储：`ai/config/AiProfileStore.kt`（Android 端 Key 经 `SecureSecretStore` 加密）。

### 5.5 自选与盈亏日历（WatchlistPage / HoldingCalendarPage）
- 持仓录入（股数 / 成本 / 起始日期）→ `DatedPortfolio` + `HoldingCalendar` 计算逐日市值与盈亏。
- 提醒引擎：`data/AlertEngine.kt` 四类规则，触发后进「今日关注」并打点盈亏日历。

### 5.6 组合风险（RiskCenterPage）
`risk/PortfolioRiskCalculator.kt`：组合市值、盈亏、个股 / 行业集中度、风险规则命中、压力测试。

### 5.7 图表引擎
- `pages/MatureKlineChart.kt` + `KlineChartInteraction.kt`：自研 Canvas K 线（点击锁定、区间统计、缩放平移、指标副图）。
- `pages/ChartTouchLayer.kt`：基于 click / longPress 事件的手势层。
- `pages/IndicatorSeries.kt`：MA / MACD / KDJ / RSI 指标计算与绘制。
- `pages/StockDetailMinute.kt`：分时图（价格 / 均价 / 成交量）。
- Android「专业图」：`StockKlineWebView.kt` + `assets/chart/`（KlineCharts 9.8.12）。

---

## 6. 数据链路：每日自动更新行情

```
GitHub Actions（每交易日 16:30 北京，周一~五）
        │  data-pipeline/build_stock_db.py：AKShare 抓数 → SQLite stock.db
        │  （6 张个股表 + 3 张指数表，30 天自动剪枝，逐表标注数据来源）
        │  + build_events.py：公司事件（分红除权 / 财报披露）
        ▼
GitHub Release（滚动 tag：data-latest）
        │  稳定下载地址：
        │  https://github.com/LiaoHaoXing123/kuikly_stock_app/releases/download/data-latest/stock.db
        ▼
App 内 DataUpdateWorker（WorkManager）
        · 启动兜底：立即比对 version.json，有更新就下载
        · 每日首刷：约 16:30 周期任务（best-effort）
        ▼
StockDb.refreshFromFile()：关闭旧只读连接 → 原子替换 filesDir/stock.db → 重新打开
```

- 非交易日拉取到的 `latest_trade_date` 仍是上一交易日，App 据此判断「无更新」自动跳过（自愈）。
- 除 `stock.db` 外，还会将 `index_list / sector_list / fundflow_list` 等 JSON 资产同步到 `cdn` 分支，供静态站点 / iOS / JS 兜底使用。
- Android 运行时可下载刷新；iOS / JS 的 JSON 资产为编译期内置，时效等于最近一次构建。

---

## 7. 项目结构

```
kuikly_stock_app/
├── androidApp/                # Android 宿主工程（Activity、适配器、WorkManager 更新）
│   └── src/main/assets/
│       ├── chart/             # 专业图 WebView 资产（KlineCharts + index.html）
│       └── common/guide/      # App 内置使用指南配图
├── iosApp/                    # iOS 宿主（Xcode 工程 + CocoaPods + Kuikly 桥接）
├── ohosApp/                   # HarmonyOS 宿主（ArkTS + NAPI 桥接）
├── shared/                    # ★ KMP 共享层：全部业务逻辑与页面
│   ├── build.gradle.kts       # Android / iOS(3 架构) / JS 多目标配置
│   └── src/
│       ├── commonMain/kotlin/com/kuikly/stock/
│       │   ├── RouterPage.kt          # 页面路由总入口
│       │   ├── pages/                 # 各业务页面（首页/行情/详情/AI/自选/我的…）
│       │   ├── data/                  # 本地数据层（StockDb/Repository/日历/提醒…）
│       │   ├── ai/                    # AI 协议、配置、提示词、工具、流式聊天
│       │   ├── network/               # Ktor 客户端、DeepSeek API、实时行情
│       │   ├── risk/  home/  base/    # 组合风险 / 首页聚合 / 基座能力
│       │   └── ui/                    # 通用组件与主题
│       ├── commonMain/assets/         # iOS/JS 内置行情 JSON 资产
│       ├── androidMain/ iosMain/ jsMain/   # 平台实现（存储/网络/剪贴板…）
│       └── commonTest/                # 跨平台单元测试
├── data-pipeline/             # Python 数据管道（AKShare 抓数/建库/导出/测试）
├── docs/                      # 使用指南与功能配图
├── .github/workflows/         # CI/CD（双端编译门禁 + 每日数据构建发布）
├── buildSrc/  gradle/         # 构建配置
└── README.md                  # 项目说明与完整演示视频
```

---

## 8. 快速开始

### 8.1 环境要求
- JDK 17、Android SDK（compileSdk 34 / minSdk 23）、Gradle 8.5（wrapper 自带）
- iOS 构建需 macOS + Xcode + CocoaPods
- 数据管道需 Python 3.12 + `pip install -r data-pipeline/requirements.txt`

### 8.2 构建 Android
```bash
git clone https://github.com/LiaoHaoXing123/kuikly_stock_app.git
cd kuikly_stock_app
./gradlew :androidApp:assembleDebug
```

### 8.3 构建 iOS
```bash
cd iosApp && pod install
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -configuration Debug \
  -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO
```

### 8.4 编译 Web (H5)
```bash
./gradlew :shared:compileKotlinJs
```

### 8.5 本地构建行情库（可选）
```bash
cd data-pipeline
python build_stock_db.py --dry-run   # 不联网自检
python build_stock_db.py             # 全量构建（需联网 + akshare）
```

> 注意：仓库 Maven 依赖优先走腾讯云镜像（见 `settings.gradle.kts`），网络受限环境 CI 会自动回退代理。

---

## 9. 数据管道（data-pipeline）

| 脚本 | 作用 |
| --- | --- |
| `build_stock_db.py` | AKShare 抓取 → SQLite `stock.db`（9 张表，30 天剪枝，逐表标注来源），输出 `version.json` |
| `build_events.py` | 公司事件：分红除权 / 财报披露，写入 `stock_dividend` / `stock_earnings` 表 |
| `build_fund_flow.py` | 主力资金流数据构建 |
| `build_sector.py` | 官方行业板块 + 成分股构建 |
| `export_common_assets.py` | 从 `stock.db` 导出 iOS / JS 内置 JSON（index / sector / fundflow） |
| `run_daily.ps1` | 本地一键跑完整链路（建库 → 事件 → 资金 → 板块 → 导出） |
| `test_quality_gate.py` | 数据质量门：schema / 剪枝 / 关键字段校验 |
| `test_export_common_assets.py` | 一致性单测：JSON 解码结果 == SQLite 查询结果（两端口径钉死） |

数据来源：个股信息 = 交易所 + 东方财富；实时行情 = 东方财富；日 K = 腾讯；分时 = 新浪；盘口 / 指数 = 东方财富；技术指标 = 本地计算。
注意：指数代码与个股代码存在重叠（如 `000001` 既是上证指数又是平安银行），指数独立成表、用表名做命名空间。

---

## 10. 自动化与质量保障

- **双端编译门禁**（`build.yml`）：任何 push / PR 必须通过 Android `assembleDebug` + JS 编译 + iOS Kotlin 三架构编译 + Xcode 模拟器构建。
- **内置资产一致性**：`test_export_common_assets.py` 在每次 push 时执行，保证 Android（SQLite）与 iOS / JS（JSON）两端数据口径一致。
- **每日数据构建**（`data-update.yml`）：每交易日 16:30 自动建库 → 质量门 → 发布 `data-latest` Release → 同步 `cdn` 分支；事件抓取失败不阻断行情发布。
- **测试**：`shared/src/commonTest/` 覆盖 AI 协议编解码、提示词、行情仓库、区间统计、盈亏日历、组合风险等核心逻辑。

---

## 11. 文档索引

| 文档 | 说明 |
| --- | --- |
| [`docs/使用指南.md`](docs/使用指南.md) | 详细使用指南（功能入口、操作步骤、常见问题） |
| [`data-pipeline/README.md`](data-pipeline/README.md) | 数据管道详细说明 |

---

## 12. 常见问题

| 问题 | 处理方式 |
| --- | --- |
| 搜不到股票 | 核对六位代码或完整名称，确认本地库是否包含该标的，刷新后重试 |
| K 线拖不动 | 可能已显示全部历史；已选中时横拖用于逐根查看，可先点「重置」 |
| 图表日期定位失败 | 当前日线可能不含该日，或证据日期与本地数据不一致 |
| 分时为空 | 检查网络并点「重试分时」；空数据不代表零成交 |
| 近 10 日只显示几天 | 查看「实际交易日」数量，可用数据不足所致 |
| AI 分析失败 | 检查网络、当前启用配置、模型 ID 与测试连通结果 |
| AI 结论与现价不一致 | 核对历史分析日期与行情日期，必要时重新分析 |
| 提醒没有即时出现 | 提醒依赖本地数据检查时机，非交易所实时推送 |

反馈问题请提供「入口 → 操作 → 实际结果 → 预期结果」+ 股票代码与时间，无需提供 API Key。

---

## 13. 免责声明

- 本应用所有行情、指标、资金数据均为**收盘快照**（或历史数据），非交易所实时逐笔数据，不同模块数据日期可能不一致，使用前请核对。
- 所有分析结论（含 AI 生成内容）仅供**研究参考**，不构成任何投资建议；据此操作风险自负。
- 应用不连接券商账户，不执行任何交易；持仓与提醒均为本地记录。

---

## 相关项目

- [KuiklyUI](https://github.com/Tencent-TDS/KuiklyUI) — 腾讯 TDS 的 Kotlin Multiplatform UI 框架
- [KuiklyMarkdown](https://github.com/Kuikly-contrib/KuiklyMarkdown) — Kuikly 聊天 Markdown 渲染组件
- [Kuikly-awesome](https://github.com/Kuikly-contrib/Kuikly-awesome) — Kuikly 资源精选列表
