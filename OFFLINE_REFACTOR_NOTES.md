# 方案B：股票行情 App 离线化改造记录

> 改造时间：2026-08-19
> 改造目标：将依赖局域网后端服务的股票行情 App，改造为**完全离线可用的独立软件包**（内嵌 JSON 数据，无需同一 WiFi、无需电脑运行后端）

---

## 一、改造背景与目标

原 App 架构依赖局域网后端（`FastAPI`，`http://127.0.0.1:8000`）提供股票列表、个股详情、K线、AI 分析等数据，存在以下问题：

- 必须手机与电脑在同一 WiFi 才能使用
- 每次使用需先启动后端服务
- AI 分析强依赖远程 DeepSeek 接口

**方案B（离线独立包）** 的核心思路：

1. 将股票数据（股票列表 + K线）导出为 JSON 文件，打包进 App 的 `assets` 目录
2. 新增 `LocalDataService` 读取本地数据，替代网络 API 调用
3. AI 分析功能改为基于本地数据的**模拟分析**（不依赖后端 DeepSeek）
4. 改造后 App 无需后端、无需同一网络，安装即可独立使用

---

## 二、改造文件清单（共 10 个文件）

### 1. 数据文件（新建，打包进 APK）

| 文件路径 | 大小 | 说明 |
|---------|------|------|
| `shared/src/commonMain/assets/stock_list.json` | 94 KB | 388 只真实 A 股（代码/名称/行业/板块/上市日期）+ 模拟行情字段（`_mock_price`、`_mock_change_percent`） |
| `shared/src/commonMain/assets/stock_kline.json` | 328 KB | 前 50 只股票的近 30 日 K线（OHLCV）|

> 数据由 `generate_data.py`（项目根目录）生成，可重复运行刷新数据。

### 2. 核心离线数据服务（新建 / 跨平台）

| 平台 | 文件路径 | 说明 |
|------|---------|------|
| commonMain | `shared/src/commonMain/kotlin/com/kuikly/stock/data/LocalDataService.kt` | 离线服务核心：`expect` 读取 + 数据解析 + 缓存 + Mock 实时行情/K线 |
| androidMain | `shared/src/androidMain/kotlin/com/kuikly/stock/data/LocalDataService.kt` | `actual`：`Context.assets` 读取 |
| iosMain | `shared/src/iosMain/kotlin/com/kuikly/stock/data/LocalDataService.kt` | `actual`：`NSBundle.mainBundle` 读取 |
| jsMain | `shared/src/jsMain/kotlin/com/kuikly/stock/data/LocalDataService.kt` | `actual`：`XMLHttpRequest` 读取 |

### 3. 平台初始化入口（修改）

| 文件 | 修改内容 |
|------|---------|
| `androidApp/src/main/java/com/kuikly/stock/KRApplication.kt` | 在 `onCreate()` 中新增 `initLocalDataService(this)`，注入 `Context` 供 Assets 读取 |

### 4. 页面改造（修改）

| 文件 | 改造内容 |
|------|---------|
| `shared/src/commonMain/kotlin/com/kuikly/stock/pages/StockListPage.kt` | 列表/搜索/分页从 `ApiService.getStockList()` 改为 `LocalDataService.loadStockList()` / `searchStocks()`；去除网络/WiFi 错误提示 |
| `shared/src/commonMain/kotlin/com/kuikly/stock/pages/StockDetailPage.kt` | 详情加载从 `ApiService.getStockDetail()` 改为 `LocalDataService.loadStockDetail(code)` 一行调用；`triggerAIAnalysis()` 改为基于本地数据的 Mock 分析 |

---

## 三、核心架构设计

### 1. expect / actual 跨平台文件读取

`commonMain` 声明读取接口（顶层函数）：

```kotlin
// commonMain
internal expect fun loadAssetText(path: String): String?
```

各平台 `actual` 实现（都必须是**顶层函数**，与 expect 对应）：

```kotlin
// androidMain
actual fun loadAssetText(path: String): String? {
    val ctx = appContext ?: return null
    return try {
        ctx.assets.open(path).bufferedReader().use { it.readText() }
    } catch (e: Exception) { null }
}
```

### 2. Android Context 注入

Android 端读取 `assets` 需要 `Context`，通过 `KRApplication.onCreate()` 注入：

```kotlin
class KRApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initLocalDataService(this)  // 注入 Context
    }
}
```

### 3. 单例缓存策略

`LocalDataService` 首次加载后缓存在内存（`cachedStockList` / `cachedKlines`），避免重复解析 JSON。

### 4. Mock 实时行情 / AI 分析

- **实时行情**：详情页根据本地模拟价格动态生成 开盘/最高/最低/成交量/PE/PB 等字段
- **AI 分析**：`triggerAIAnalysis()` 模拟 800ms 延迟后，根据涨跌幅/K线连续收阳收阴数，生成 5 张卡片（趋势研判 / 技术信号 / 风险提示 / 操作建议 / 总结），并标注"数据来源：本地离线数据（模拟分析）"

---

## 四、遇到的问题与解决方案（★ 重点）

> 以下为编译期逐个暴露并修复的错误，按修复批次整理。

### 问题 1：`actual` 报错 "has no corresponding expected declaration"

**报错信息**：
```
e: androidMain/.../LocalDataService.kt:17:29
'actual fun LocalDataService.loadAssetText(path: String): String?'
has no corresponding expected declaration
```

**根因**：`expect` 声明写在 `object LocalDataService` **内部**（成员函数），而三个平台的 `actual` 却用 `LocalDataService.loadAssetText` 限定符（即写在 object **外部**）。

Kotlin 规则：`expect`/`actual` 必须**同为成员函数或同为顶层函数**，不能交叉。

**解决方案**：把 `expect` 改为**顶层函数**（移出 object），三平台 `actual` 去掉 `LocalDataService.` 限定符。

```kotlin
// ❌ 修改前（expect 在 object 内，actual 在 object 外且用限定符）
object LocalDataService {
    internal expect fun loadAssetText(path: String): String?  // 在 object 内部
}
// androidMain: actual fun LocalDataService.loadAssetText(...)  // 限定符形式 → 不匹配

// ✅ 修改后（expect 为顶层函数）
internal expect fun loadAssetText(path: String): String?  // 在 object 外部

// 三平台：actual fun loadAssetText(path: String): String?  // 去掉 LocalDataService. 限定符
```

---

### 问题 2：`Unresolved reference 'double'`（4 处）

**报错信息**：
```
e: commonMain/.../LocalDataService.kt:138:54 Unresolved reference 'double'.
e: commonMain/.../LocalDataService.kt:139:56 Unresolved reference 'double'.
e: commonMain/.../LocalDataService.kt:140:54 Unresolved reference 'double'.
e: commonMain/.../LocalDataService.kt:141:52 Unresolved reference 'double'.
```

**根因**：代码使用了 `jsonPrimitive.double`，但 `JsonPrimitive.double` 是 `kotlinx.serialization.json` 包中的**扩展属性**，必须显式 `import` 才能使用。原文件只 import 了 `doubleOrNull` 和 `int`，漏掉了 `double`。

**解决方案**：添加 import。

```kotlin
import kotlinx.serialization.json.double   // ← 新增
import kotlinx.serialization.json.doubleOrNull
```

> 经验：kotlinx.serialization 的每个 `JsonPrimitive.xxx` 访问器（`.double` / `.int` / `.boolean` / `.content` 等）都是独立扩展属性，用到哪个就必须 import 哪个。

---

### 问题 3：`cachedKlines` 类型不匹配（2 处）

**报错信息**：
```
e: ...LocalDataService.kt:127:36
Return type mismatch: expected 'Map<String, List<KLineRaw>>',
actual 'Map<String, List<KLineDataItem>>'.

e: ...LocalDataService.kt:147:24
Assignment type mismatch: actual type is 'MutableMap<String, List<KLineRaw>>',
but 'Map<String, List<KLineDataItem>>?' was expected.
```

**根因**：缓存字段 `cachedKlines` 声明为 `Map<String, List<KLineDataItem>>?`（页面展示类型），但 `loadAllKlines()` 实际解析并存入的是 `KLineRaw`（原始数据类型）。类型设计前后不一致。

数据流本应是：
```
stock_kline.json
  → loadAllKlines() 解析为 KLineRaw（缓存）
  → loadStockDetail() 中映射为 KLineDataItem（页面展示）
```

**解决方案**：将 `cachedKlines` 的缓存类型改为与解析结果一致的 `Map<String, List<KLineRaw>>?`。

```kotlin
// ❌ 修改前
private var cachedKlines: Map<String, List<KLineDataItem>>? = null

// ✅ 修改后
private var cachedKlines: Map<String, List<KLineRaw>>? = null
```

---

### 问题 4：`Unresolved reference 'roundToInt'`

**报错信息**：
```
e: ...LocalDataService.kt:153:49 Unresolved reference 'roundToInt'.
```

**根因**：`roundToInt` 是 Kotlin 标准库中的**扩展函数**（`fun Double.roundToInt(): Int`），不是顶层函数。写成 `kotlin.math.roundToInt(v * 100.0)` 这种"静态调用"形式会找不到符号。

**解决方案**：改用顶层函数 `kotlin.math.round`（返回 `Double`），保持返回值类型一致。

```kotlin
// ❌ 修改前
private fun round2(v: Double) = kotlin.math.roundToInt(v * 100.0) / 100.0

// ✅ 修改后
private fun round2(v: Double) = kotlin.math.round(v * 100.0) / 100.0
```

> 经验：标准库中 `round`（顶层函数）、`roundToInt` / `roundToLong`（扩展函数）易混淆。跨平台 commonMain 里优先使用顶层函数或显式 import 扩展函数后用接收者调用：`(v * 100.0).roundToInt()`。

---

### 附带修复：KMP commonMain 平台特定 API 隐患（★ 重要）

以下写法在 `commonMain`（跨平台公共代码）中**不可用**，会导致编译失败或在非 JVM 平台失败，已全部修正：

| 修改前（JVM 特有） | 修改后（跨平台） | 说明 |
|-------------------|-----------------|------|
| `java.util.Random().nextDouble()` | `kotlin.random.Random.nextDouble()` | `java.util.*` 不能在 commonMain 使用 |
| `Math.round(v * 100.0)` | `kotlin.math.round(v * 100.0)` | `java.lang.Math` 不能在 commonMain 使用 |
| `$name($stockName)` | `$name($stockCode)` | StockDetailPage 显示 bug：括号里重复显示名称，应显示股票代码 |

---

## 五、修复后验证状态

| 检查项 | 状态 |
|--------|------|
| expect/actual 声明一致性（4 个文件） | ✅ 已修复 |
| kotlinx.serialization `.double` import | ✅ 已修复 |
| `cachedKlines` 缓存类型一致性 | ✅ 已修复 |
| `roundToInt` / `Math` / `java.util.Random` 跨平台兼容 | ✅ 已修复 |
| 显示 bug（$stockName 重复） | ✅ 已修复 |
| **Android Studio Rebuild 编译通过** | ⏳ 待用户验证 |
| **真机安装 + 离线功能验证** | ⏳ 待用户验证 |

---

## 六、下一步操作

### 1. 编译验证

在 Android Studio 中：
```
Build → Rebuild Project  （或 Ctrl+F9）
```

### 2. 真机离线验证（无需后端、无需同一 WiFi）

安装新 APK 后确认：
- [ ] 股票列表正常显示 388 只股票及模拟行情
- [ ] 搜索 / 分页正常
- [ ] 点击个股进入详情页，展示基础信息 / 模拟实时行情 / K线
- [ ] 点击"AI分析"生成离线模拟分析报告

### 3. 可选优化

- `ChatMainPage.kt`（AI 聊天页）仍保留 `ApiService.chat()` 网络调用，离线模式下会失败；可考虑改为 Mock 或隐藏入口。
- `generate_data.py` 可移至 `scripts/` 目录作为数据刷新工具保留。

---

## 七、一句话总结

方案B 通过 **expect/actual 跨平台 Assets 读取 + LocalDataService 本地数据替代网络 API + Mock AI 分析**（共 10 个文件改造），彻底去除对局域网后端的依赖；编译期暴露的 4 类错误（expect/actual 位置不一致、序列化 `.double` 缺失 import、缓存类型不匹配、`roundToInt` 误用）及 KMP 平台特定 API 隐患均已修复，待用户 Rebuild 验证与真机测试。
---

# 方案C：离线模式接入真实 AI（DeepSeek，基于数据文件夹作答）

> 改造时间：2026-08-18
> 背景：方案B 的离线 AI 问答/分析是本地模板（只有 3 类固定回答），用户要求"离线模式联通真实 AI，且 AI 直接基于已保存的数据文件夹作答"。

## 一、新架构

手机 App（Kuikly）
  - 离线模式：股票列表/详情 ← assets 内置 JSON（不变）
  - AI 问答/个股分析 ← POST 局域网后端 /api/v1/ai/chat、/api/v1/ai/analyze/{code}
      （后端不可达时自动回退内置模板，App 不中断）

电脑 FastAPI 后端（无需 MySQL 也能跑 AI）
  - 数据源：config.AI_DATA_MODE=local（默认）
      读取 backend/data/（= assets 数据文件夹的拷贝：388 只股票 + 50 只x30 日K线）
      AI 上下文 = 数据文件夹里的真实行情 + K线 + 市场概览
  - LLM：DeepSeek（OpenAI 兼容，base_url/model 可在 .env 配置）
      httpx trust_env=True，支持 HTTP_PROXY/HTTPS_PROXY 代理

## 二、关键改动

| 文件 | 改动 |
|------|------|
| backend/data/stock_list.json、stock_kline.json | 新增：数据文件夹（从 assets 拷贝） |
| backend/services/local_data_source.py | 新增：读数据文件夹（无需 MySQL），含股票识别（代码+名称）、市场概览、确定性模拟行情 |
| backend/services/ai_service.py | 重构：数据源按 AI_DATA_MODE 路由；AI 问答/分析基于数据文件夹真实数据组装 Prompt 后调用 DeepSeek；宽容解析卡片字段（change_percent/chart_type） |
| backend/prompts/chat.py | 重写：要求 Markdown 排版（## 标题、**加粗**、- 列表、空行）、只引用给定数据、snake_case 卡片字段 |
| backend/prompts/stock_analysis.py | 修复 kline 循环变量 bug（kk→k） |
| backend/config.py | 新增 AI_DATA_MODE / LOCAL_DATA_DIR |
| backend/routers/ai_chat.py | 新增 GET /api/v1/ai/status（排查用） |
| shared/.../data/Models.kt | ChatReply/AIAnalysisResponse 的 cards 改 List<Map<String, JsonElement>>（支持嵌套数组） |
| shared/.../network/ApiService.kt | JsonElement→Any? 转换器；VO 类型更新 |
| shared/.../data/StockRepository.kt | chat/analyzeStock 一律先调后端真实 AI，失败回退模板并提示 |
| shared/.../data/LocalDataService.kt | 新增 mockAnalysis()（回退用）；mockChat 保留为回退 |
| shared/.../pages/ChatMainPage.kt | 排版升级：Markdown 渲染（标题/加粗/列表/引用/代码块）、"AI 正在思考…"气泡、卡片字段兼容、开发者面板文案 |
| shared/.../pages/StockDetailPage.kt | AI 分析改走后端（回退模板） |

## 三、验证

- 后端：AST 语法检查通过；本地数据源单测通过（388 只/50 K线/股票识别/确定性行情）；
  TestClient 冒烟：/health、/ai/status、/stocks、/ai/chat（真实 DeepSeek 返回 Markdown + 股票卡片 + 追问）。
- App：Kotlin 改动待 Android Studio Rebuild 验证。

## 四、使用前提（重要）

- 手机与电脑同一网络；电脑端启动后端：python main.py（backend 目录）。
- 电脑需能访问 DeepSeek（api.deepseek.com）；如走代理，在 backend/.env 放开 HTTP_PROXY/HTTPS_PROXY。
- 若 DeepSeek 报模型不存在：DeepSeek 官方模型名为 deepseek-chat / deepseek-reasoner，改 .env 的 DEEPSEEK_MODEL。
- 后端不可达/无网时，App 自动回退内置模板回答（聊天会显示提示行）。
