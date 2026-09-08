# 修改方案：指数全链路落地（pipeline + App）

- 日期：2026-09-08
- 分支：`arena/01a08076-kuikly-stock-app`（已推送远端）
- 提交：`1b3284f`（pipeline）→ `5dc4210`（App）→ 本文档
- 背景：质检发现 App 只认识个股、不认识指数（`P2-1`）。Task 2 只要求"至少一个"承接页，个股页已达标；本方案把指数从"演示规避项"变成"完整能力"，问指数→指数卡→指数详情→AI 解读全闭环。

---

## 一、核心设计决策

### 1. 代码重叠是最大约束：000001 既是上证指数又是平安银行

所有设计都围绕这一点：

| 位置 | 决策 |
|---|---|
| 数据库 | 指数独立三表，用**表名做命名空间**，禁止与 `stock_*` 混查 |
| 识别 | 三级消歧（见 §3），无语境时默认个股，保证个股行为零回归 |
| 协议卡片 | 新增 `index_card`（与 `stock_card` 同形校验），跳转目标不同 |
| 详情页 | 新建 `index_detail`，只读 `index_*` 表；不设自选按钮（`WatchStore` 以 code 为键会串） |

### 2. 旧库必须不崩

内置 `stock.db` 更新前没有指数表。所有指数查询（pipeline 抓取、Android 读库）全部 try/catch 降级为空 + 明确提示语，不抛异常、不编数据。

### 3. 指数识别三级消歧（`matchIndexCandidates`，纯函数可单测）

1. **全名/别名** → 直接算指数：上证指数、大盘、创业板、沪指、深证…
2. **去后缀简称** → 仅有语境时算数："上证"可命中（"上证"本身是语境词），"银行"不命中"银行指数"（防误伤）
3. **6 位代码** → 必须有语境词（指数/大盘/点位/沪深/中证…）才算指数；单独"000001"仍是平安银行

---

## 二、pipeline 层改动（`1b3284f`）

文件：`data-pipeline/build_stock_db.py`、`README.md`、`test_quality_gate.py`、新增 `test_index_pipeline.py`

- **新表**：`index_info`（代码/简称/市场）、`index_realtime`（实时快照，列与个股表同形）、`index_daily_kline`（日K）。6 表 → 9 表，`version.json` / `data_source` / 剪枝自动覆盖。
- **抓取**：实时 `stock_zh_index_spot_em`（一次请求同时产出 info + realtime）；K 线主源 `index_zh_a_hist`（纯 6 位代码），挂了切腾讯 `stock_zh_index_daily_tx` 兜底并按日期截断；默认 10 只宽基（上证/深证/创业板/科创50/北证50/沪深300/中证500/中证1000/上证50/中小板指），`INDEX_KLINE_CODES` 可覆盖。
- **顺手修的 bug**：push2 主机改写只覆盖 3 个固定域名，AKShare 实际命中的 `48/80.push2` 等数字镜像会漏网（指数接口正走这条通道）→ 改成正则全覆盖，个股行为不变。
- **软失败**：指数任一步骤失败只告警，不中断个股构建、不拦截发布；质量门指数项为软告警。
- **验证**：`--dry-run` 全绿（9 表 + 指数剪枝 0 残留）；质量门 10/10；指数 pipeline 离线测试 7/7（桩数据跑通抓取→转换→写入→剪枝→兜底切换）。

---

## 三、App 层改动（`5dc4210`）

### 数据层

| 文件 | 改动 |
|---|---|
| `pages/StockListPage.kt` | `StockListItem` 加 `isIndex = false`（默认参数，现有调用零影响） |
| `data/StockDb.kt`（expect） | 新增 `indexDetail / detectMentionedIndices / listIndices` |
| `data/IndexMatcher.kt`（新） | §1 之 §3 的纯函数实现 + 别名表 + 语境词表 |
| `data/StockDb.kt`（android actual） | 三函数实现：查三表组装 `StockDetailData`（指标置空）、候选匹配、指数列表；缺表 catch 返回空 |

### 协议与渲染

| 文件 | 改动 |
|---|---|
| `ai/chat/ChatProtocol.kt` | prompt 加第 5 类卡 `index_card` + "指数用指数卡、结论卡仅个股"；`validCard` 与 `stock_card` 同规则放行 |
| `pages/ChatMainPage.kt` | 分发加 `index_card` → 新 `indexCard` 渲染器（绿底 + 指数徽标，点击进 `index_detail`） |

### 聊天链路

| 文件 | 改动 |
|---|---|
| `ai/chat/ChatContext.kt` | 追问词加 指数/大盘/点位 |
| `ai/prompt/PromptContext.kt` | 加 `indexLines`（默认空，现有调用/测试不受影响），渲染"相关指数数据："段 |
| `network/DeepSeekApi.kt` | 上下文按 `isIndex` 分流（指数行无 PE/PB/指标 + 防编造声明）；新增 `analyzeIndex`（指数 prompt，复用卡片构建器） |
| `ai/tool/StockTools.kt` | 新增 `get_index_quote / get_index_kline / list_indices`；个股工具描述追加分流提示 |
| `data/StockRepository.kt` | 指数识别与个股合并（`take(4)`）；图表按来源解析（指数优先查指数表，单位覆写为 点/股）；新增 `loadIndexDetail / analyzeIndex` |
| `data/LocalDataService.kt` | 离线 `mockChat` 指数优先分支（`indexQa`：本地快照 + 指数卡）；`mockIndexAnalysis`（点位 wording 模板） |

### 新页面：`@Page("index_detail")`（`pages/IndexDetailPage.kt`，新）

- 导航（绿底 + AI 分析入口，无自选按钮）→ 基础信息（代码/名称/市场）→ 实时点位（点位/涨跌点/涨跌幅/四价/量额，量额格式化为 亿股/亿元）→ 近 30 日 K 线 Canvas（长按 tooltip，复刻个股交互）→ AI 解读（复用 `buildAnalysisMarkdown`，`StockDetailPage.kt` 仅改 1 词 `private→internal`）→ 数据来源脚注。
- 空/错态齐全：旧库缺表时明确提示"更新后重试" + 重试按钮。

### 测试（新增 11 项，全部离线可跑）

- `IndexMatcherTest` 8 项：全名/别名/简称±语境/代码±语境/空安全/截断 3 条。
- `ChatProtocolTest` +2：合法指数卡通过、字符串价格拒绝。
- `PromptContextTest` +1：指数段渲染。

---

## 四、拿到代码后做什么（按顺序）

1. **拉分支**：`git fetch origin && git checkout arena/01a08076-kuikly-stock-app`
2. **编译+单测**（沙箱无 JDK，这步必须在你本机做）：
   `./gradlew :shared:assembleDebug` → `./gradlew :shared:testDebugUnitTest`
3. **更新内置指数库**（否则指数功能显示"暂无数据"）：
   `cd data-pipeline && python build_stock_db.py && cp stock.db ../shared/src/commonMain/assets/stock.db`
4. **实测闭环**：聊天问"上证指数今天怎么样"→ 点指数卡 → `index_detail` → "开始 AI 分析"；再问"000001怎么样"确认仍是平安银行（消歧回归）。

## 五、有意不做的三处（如需可二期）

1. 多股对比（`compare_card`）仍只支持个股，指数对比静默不出卡（不崩）。
2. `conclusion_card` 只用于个股（prompt 已约束）；若模型偶发对指数输出结论卡，点击会进个股页——已知小概率口径问题。
3. 指数无技术指标表（pipeline 未计算），指数 AI 分析明确告知模型"不要编造指标"；如需可加 `index_indicator` 表 + 本地计算。

## 六、评审口径（一句话）

> 聊天链路 7 种可用卡（6 协议卡：个股/结论/信号/风险/图表/**指数** + 1 本地多股对比卡）；指数详情页为第二承接页；指数数据来自 AKShare 东财指数接口，与个股同库不同表。
