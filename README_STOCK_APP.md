# AI 股票行情原型 Demo - Kuikly 前端项目

## 📋 项目简介

基于 **Kuikly (KMP)** 跨平台框架的 AI 股票行情应用前端，配合 FastAPI 后端服务，实现完整的双通路功能。

### 核心功能

#### 通路一：主动浏览行情
- ✅ AI 聊天主页（默认首页）→ 左上角【大盘行情】按钮
- ✅ 行情列表页（股票名称、代码、最新价、涨跌幅）
- ✅ 个股详情页（基础信息 + 实时行情 + K线 + AI 解读卡片）

#### 通路二：AI 问答查询
- ✅ AI 聊天界面（输入框 + 消息列表）
- ✅ Markdown + 结构化卡片渲染（股票卡片、趋势卡片等）
- ✅ 详情承接页（从聊天结果跳转）
- ✅ 多轮对话支持

---

## 🛠️ 环境要求

### 必需工具

1. **Android Studio** (2024.2.1 或更高版本)
   - 下载地址: https://developer.android.com/studio

2. **JDK 17**
   - Android Studio → Settings → Build Tools → Gradle → Gradle JDK → 选择 JDK 17

3. **Kotlin 和 KMP 插件**
   - Android Studio → Settings → Plugins → Marketplace
   - 搜索 "Kotlin" 并安装
   - 搜索 "Kotlin Multiplatform" 并安装

4. **Kuikly Android Studio 插件** ⭐ 重要
   - 安装方式:
     1. Android Studio → Settings → Plugins → ⚙️ (齿轮图标)
     2. 点击 "Manage Plugin Repositories"
     3. 添加仓库地址（参考 Kuikly 官方文档）
     4. 在 Marketplace 搜索 "Kuikly" 并安装
     5. 重启 IDE

5. **Gradle 7.5.1**（推荐版本）
   - File → Project Structure → Project → Gradle Version → 设置为 7.5.1

### 可选工具（iOS 开发需要）

- **XCode** (仅 Mac)
- **CocoaPods** (仅 Mac)

> **Windows 用户注意**: 本机无法运行 iOS，需要在 `shared/build.gradle.kts` 中注释掉 iOS 相关配置。

---

## 🚀 快速开始

### 第一步：使用 Kuikly 插件创建新项目

1. 打开 Android Studio

2. 选择 **File → New → New Project**

3. 在模板列表中选择 **Kuikly Project Template**

4. 配置项目：
   - **Project name**: `kuikly_stock_app`
   - **Package name**: `com.kuikly.stock`
   - **Save location**: `E:\OpenSourceProject\Project_Practice\work\kuikly_stock_app`

5. 点击 **Finish**，等待项目创建完成

### 第二步：复制代码文件

插件会自动生成基础项目结构。现在将我们准备好的代码复制到对应位置：

```
你的项目目录结构应该是：
E:\OpenSourceProject\Project_Practice\work\kuikly_stock_app\
├── shared/
│   └── src/commonMain/kotlin/com/kuikly/stock/    ← 复制到这里
│       ├── data/
│       │   ├── Models.kt          # 数据模型
│       │   └── AIModels.kt        # AI 相关模型
│       ├── network/
│       │   ├── ApiEndpoints.kt    # API 端点定义
│       │   └── ApiClient.kt       # HTTP 客户端
│       ├── pages/
│       │   ├── ChatMainPage.kt    # AI 聊天主页（⭐ 默认首页）
│       │   ├── StockListPage.kt   # 行情列表页
│       │   └── StockDetailPage.kt # 个股详情页
│       ├── components/            # （可选）可复用组件
│       ├── viewmodels/            # （可选）ViewModel
│       └── main.kt                # 应用入口（自动生成）
├── androidApp/                    # Android 宿主工程（自动生成）
├── iosApp/                        # iOS 宿主工程（自动生成，Windows 可忽略）
├── build.gradle.kts               # 项目构建配置（自动生成）
├── settings.gradle.kts            # 项目设置（自动生成）
└── gradlew / gradlew.bat          # Gradle 包装脚本（自动生成）
```

**操作步骤**：

```bash
# 1. 进入项目目录
cd E:\OpenSourceProject\Project_Practice\work\kuikly_stock_app

# 2. 创建必要的子目录（如果不存在）
mkdir -p shared/src/commonMain/kotlin/com/kuikly/stock/{data,network,pages,components,viewmodels}

# 3. 将代码文件从 work/kuikly_stock_app/shared/... 复制到项目的 shared/src/... 对应位置
# 可以手动复制，或使用以下命令（根据实际情况调整）：
cp -r "E:\OpenSourceProject\Project_Practice\work\kuikly_stock_app\shared\src\commonMain\kotlin\com\kuikly\stock\data\"* "shared/src/commonMain/kotlin/com/kuikly/stock/data/"
cp -r "E:\OpenSourceProject\Project_Practice\work\kuikly_stock_app\shared\src\commonMain\kotlin\com\kuikly\stock\network\"* "shared/src/commonMain/kotlin/com/kuikly/stock/network/"
cp -r "E:\OpenSourceProject\Project_Practice\work\kuikly_stock_app\shared\src\commonMain\kotlin\com\kuikly\stock\pages\"* "shared/src/commonMain/kotlin/com/kuikly/stock/pages/"
```

### 第三步：配置依赖

编辑 `shared/build.gradle.kts`，添加必要的依赖：

```kotlin
// shared/build.gradle.kts

plugins {
    kotlin("multiplatform")
    // 注释掉 iOS 相关配置（Windows 环境）
    // kotlin("native.cocoapods")
    
    // 其他插件...
}

kotlin {
    androidTarget()
    
    // 注释掉 iOS 目标（Windows 环境）
    // iosX64()
    // iosArm64()
    // iosSimulatorArm64()
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                // Kuikly 核心库（由插件自动添加）
                implementation("com.tencent.kuikly:core:最新版本号")
                
                // Ktor Client（网络请求）
                implementation("io.ktor:ktor-client-core:2.3.0")
                implementation("io.ktor:ktor-client-cio:2.3.0")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.0")
                
                // Kotlinx Serialization
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
                
                // Coroutines（异步支持）
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.0")
            }
        }
        
        val androidMain by getting {
            dependencies {
                // Android 特定依赖
            }
        }
    }
}
```

### 第四步：配置 Android App

确保 `androidApp/build.gradle.kts` 正确配置：

```kotlin
// androidApp/build.gradle.kts

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.multiplatform")
}

android {
    namespace = "com.kuikly.stock"
    compileSdk = 34  // 或更高版本
    
    defaultConfig {
        applicationId = "com.kuikly.stock"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

### 第五步：同步 Gradle 并运行

1. **同步项目**：
   - 打开 Android Studio 右侧的 **Gradle** 面板
   - 点击顶部的 🐘 大象图标（Sync Project with Gradle Files）
   - 等待同步完成（首次可能需要下载依赖）

2. **运行 Android 应用**：
   - 连接 Android 手机或启动模拟器
   - 点击顶部工具栏的 ▶️ 运行按钮
   - 选择 `androidApp` 配置
   - 等待编译和安装

3. **查看效果**：
   - 应用启动后应显示 **AI 聊天主页**（ChatMainPage）
   - 左上角有【📊 大盘行情】按钮
   - 点击后跳转到行情列表页
   - 点击某只股票进入详情页

---

## 📱 页面说明

### 1. ChatMainPage（AI 聊天主页）

**路由标识**: `@Page("chat_main")`

**功能**:
- 顶部导航栏：左侧【大盘行情】按钮 + 标题 "AI 智能助手"
- 消息列表区域：支持滚动，渲染用户消息和 AI 回复
- 结构化卡片渲染：
  - 股票卡片（可点击跳转详情）
  - 趋势判断卡片
  - 技术信号卡片
  - 风险评估卡片
  - 操作建议卡片
  - 图表卡片（占位符）
- 推荐问题标签（点击自动发送）
- 底部输入区域：输入框 + 发送按钮

**跳转关系**:
- 点击【大盘行情】→ `RouterModule.openPage("stock_list", params)`
- 点击股票卡片 → `RouterModule.openPage("stock_detail", params)`

---

### 2. StockListPage（行情列表页）

**路由标识**: `@Page("stock_list")`

**功能**:
- 导航栏：返回按钮 + 标题（显示股票数量）+ 刷新按钮
- 搜索栏：按代码或名称搜索
- 排序标签：默认 / 涨幅 / 跌幅 / 成交量
- 股票列表项：
  - 显示：名称、代码、最新价、涨跌幅
  - 颜色：红涨绿跌（中国股市惯例）
  - 点击跳转详情页
- 分页加载：加载更多按钮

**数据来源**:
- `GET /api/v1/stocks?page=1&size=20&keyword=xxx&sort=change_percent&order=desc`

**跳转关系**:
- 返回 → `RouterModule.closePage()`
- 点击列表项 → `RouterModule.openPage("stock_detail", {code: "000001"})`

---

### 3. StockDetailPage（个股详情页）

**路由标识**: `@Page("stock_detail")`

**功能**:
- 导航栏：返回 + 股票名称(代码) + 【🤖 AI 分析】按钮
- 基础信息卡片：
  - 股票代码、名称、行业、板块、上市日期
- 实时行情卡片：
  - 最新价（大字显示）+ 涨跌幅 + 涨跌额
  - 详细数据网格：开盘、昨收、最高、最低、成交量、成交额、市盈率、市净率
- K 线图表区域：
  - 占位符（实际项目应集成图表库）
  - 最近 N 天数据摘要
- AI 解读卡片区域：
  - 触发分析按钮
  - 分析中状态提示
  - 多张分析卡片：
    - 📈 趋势判断
    - 📡 技术信号
    - ⚠️ 风险评估
    - 💡 操作建议（含目标价、止损位）
    - 📋 AI 总结

**数据来源**:
- 个股详情: `GET /api/v1/stocks/{code}/detail`
- AI 分析: `POST /api/v1/ai/analyze/{code}?analysis_type=full`

**接收路由参数**:
```kotlin
val code = pagerData.params?.optString("code", "")
```

---

## 🔗 后端 API 对接

### 基础配置

在 `ApiClient.kt` 中修改 BASE_URL：

```kotlin
object ApiEndpoints {
    // 开发环境
    const val BASE_URL = "http://127.0.0.1:8000"

    // 生产环境（部署后修改）
    // const val BASE_URL = "https://your-api-domain.com"
}
```

### 网络请求示例

```kotlin
// 获取股票列表
suspend fun getStockList(page: Int, size: Int): ApiResponse<PaginatedResponse<StockInfo>> {
    return ApiClient.client.get("${ApiEndpoints.BASE_URL}${ApiEndpoints.Stocks.LIST}") {
        parameter("page", page)
        parameter("size", size)
    }.body()
}

// AI 问答
suspend fun chat(message: String, sessionId: String?): ChatResponse {
    return ApiClient.client.post("${ApiEndpoints.BASE_URL}${ApiEndpoints.AI.CHAT}") {
        setBody(ChatRequest(
            message = message,
            sessionId = sessionId,
            context = mapOf("mentioned_stocks" to listOf("600519"))
        ))
    }.body()
}
```

---

## 🎨 UI 设计规范

### 颜色方案

| 用途 | 颜色值 | 说明 |
|------|--------|------|
| 主色调 | `#1976D2` | 蓝色（导航栏、按钮） |
| 背景色 | `#F5F5F5` | 浅灰色 |
| 卡片背景 | `#FFFFFF` | 白色 |
| 涨（红） | `#E53935` | 中国股市惯例 |
| 跌（绿） | `#43A047` | 中国股市惯例 |
| 文字主色 | `#333333` | 深灰 |
| 文字辅色 | `#666666` / `#999999` | 中灰 / 浅灰 |
| AI 卡片背景 | `#FFF9C4` | 浅黄色 |

### 字体大小

| 用途 | 字号 |
|------|------|
| 页面标题 | 18f |
| 卡片标题 | 15f |
| 正文 | 14-15f |
| 辅助文字 | 12-13f |
| 小字 | 11f |

### 间距规范

- 页面边距: 12f
- 卡片内边距: 12-16f
- 元素间距: 8f
- 行高: 1.5

---

## 🔧 常见问题

### Q1: Gradle 同步失败？

**解决方案**:
1. 检查 JDK 版本是否为 17
2. 检查 Gradle 版本是否为 7.5.1
3. 检查网络连接（可能需要代理）
4. 清理缓存: `File → Invalidate Caches → Invalidate and Restart`

### Q2: 找不到 Kuikly 插件？

**解决方案**:
1. 确认已添加正确的插件仓库地址
2. 在 Marketplace 中搜索 "Kuikly"（不是 "KMP"）
3. 如果搜索不到，检查网络或联系技术支持

### Q3: iOS 编译报错？

**原因**: Windows 无法运行 iOS 开发环境

**解决方案**:
在 `shared/build.gradle.kts` 中注释掉所有 iOS 相关配置：
- `kotlin("native.cocoapods")`
- `iosX64()`, `iosArm64()`, `iosSimulatorArm64()`
- `cocoapods { ... }`
- 所有 `ios*Main`, `ios*Test` 相关配置

### Q4: 如何调试网络请求？

**方案**:
1. 使用 Android Studio 的 **Logcat** 查看日志
2. 在 `ApiClient.kt` 中启用 Logging 插件
3. 使用 Charles / Fiddler 抓包工具
4. 检查后端服务是否正常运行 (`python backend/main.py`)

### Q5: 如何集成真实的图表库？

**推荐方案**:
- **Android**: MPAndroidChart (v3.1.0)
- **KMP 跨平台**: 
  - Kotlin Charts (https://github.com/nacular/doodle)
  - 自定义 Compose Canvas 绘制

**示例代码**（简化版）:
```kotlin
// 在 StockDetailPage.kt 的 klineChartArea() 中替换 chartPlaceholder()
View {
    attr { height(200f) }
    // 使用图表库绘制 K 线
    // KLineChart(klineData = stockDetail!!.kline)
}
```

---

## 🐛 编译问题排查记录（Troubleshooting）

> 以下为本项目在 `E:\OpenSourceProject\Project_Practice\work\kuikly_stock_app` 实际踩坑与修复方案，`git` 版本基线：Kotlin `2.1.21` + Kuikly `2.7.0-2.1.21` + AGP `7.4.2`。

### 1. Kuikly 页面 Kotlin 编译错误（`:shared:compileDebugKotlinAndroid FAILED`）

**现象**：`compileDebugKotlinAndroid` 报大量错误，集中在 `ChatMainPage.kt` / `StockListPage.kt` / `StockDetailPage.kt`，典型报错：
- `Unresolved reference 'observable'`
- `'X' cannot be called in this context with an implicit receiver`
- `Unresolved reference 'END' / 'START'`
- `Too many arguments for 'fun text(text: String)'`
- `Argument type mismatch: actual type is 'FlexAlign', but 'FlexJustifyContent' was expected`
- `Unresolved reference 'not' for operator '!'`、`Unresolved reference 'Tuple'`、`'onAttach' overrides nothing`
- 大量 `Syntax error: Expecting ','` / `Expecting ')'`（多由赋值语法误用级联引发）

**根因**：页面代码最初由模板/自动生成，存在多处 Kuikly DSL 误用。

**修复清单**（均已落到三份页面源码）：
1. `observable` 状态委托需显式 import：`import com.tencent.kuikly.core.reactive.handler.observable`。
2. 子视图组合必须用**顶层扩展函数** `internal fun ViewContainer<*,*>.xxx(ctx: Page)`，不能用类内「成员扩展函数」——`body()` 的 lambda 没有 dispatch receiver，会报 *cannot be called in this context with an implicit receiver*；对应 state 字段需改为 `internal` 以便顶层函数访问。
3. 属性设置必须用**调用语法**而非赋值语法：`text("x")` / `fontSize(12f)` / `color(0xFF...)` / `backgroundColor(...)` / `borderRadius(...)` / `marginTop(...)` 等，**不能**写成 `text = "x"`。
4. `padding` 必须用**命名参数**：`padding(top=, left=, bottom=, right=)`；不存在 `padding(all = …)`；`Tuple(...)` / `Pair(...)` 写法不存在（统一改为命名参数）。
5. 布局枚举修正：`FlexAlign.END/START` → `FLEX_END / FLEX_START`；`justifyContent(FlexAlign.X)` → `FlexJustifyContent.X`；`flexWrap(true)` → `FlexWrap.WRAP`。
6. 生命周期：`Pager` 没有 `onAttach`，初始化请重写 `didInit()`（内部可读 `pagerData.params` 并触发首屏加载）。
7. `borderTop(width, color)` 不存在 → 改用 1px 高度的 `View` 作分割线。
8. 非法 9 位十六进制（如 `0xFFFFFFF9C4`）→ 改为 8 位 `0xFFFFF9C4`。
9. Kotlin 无跨类型除法：`/ 4` → `/ 4f`（Float 不能直接除 Int）。
10. 同包（`package com.kuikly.stock.pages`）内多个页面文件若定义同签名顶层函数（如 `loadingView()`）会冲突 → 按文件唯一命名（`stockListLoadingView` / `stockDetailLoadingView`）。
11. 数据加载方法（如 `refreshData` / `searchStocks`）若写成「成员扩展函数」`internal fun StockListPage.xxx()`，`ctx.xxx()` 在顶层视图里无法解析 → 改为**普通成员函数** `internal fun xxx()`。
12. `Text` 的 attr 在该版本（2.7.0）取不到 `padding`；如需内边距，用外层 `View` 包裹 `Text` 来承载。

### 2. `mergeExtDexDebug` 失败（`com.android.tools.r8.kotlin.H`）

**现象**：Kotlin 编译通过后，`androidApp:mergeExtDexDebug` 报 `com.android.tools.r8.kotlin.H`，对 `kotlin-stdlib-2.1.21.jar` 与 Kuikly `2.1.21` 产物做 dex 时失败。

**根因**：项目实际应用的 AGP 为 **7.4.2**（root `build.gradle.kts` 中 `com.android.application` / `com.android.library` 插件版本），而 Kotlin 插件为 **2.1.21**。AGP 7.4.2 自带的 R8（约 8.1/8.2）**无法解析 Kotlin 2.1 元数据**，属于版本不匹配，并非 Gradle 缓存损坏（不要去清缓存）。

**修复**：在 root `build.gradle.kts` 的 `buildscript.dependencies` 强制注入新版 R8：
```kotlin
buildscript {
    repositories { /* google() / mavenCentral() */ }
    dependencies {
        classpath(BuildPlugin.kuikly)
        classpath("com.android.tools:r8:8.3.37")   // 覆盖 AGP 自带旧 R8，支持 Kotlin 2.1 元数据
    }
}
```
- **8.3.37** 是首个支持 Kotlin 2.1 元数据、且离 AGP 7.4.2 自带 R8 最近的版本，兼容性最佳。
- **Build JDK 必须为 17**（AGP 7.4 要求）；Gradle 8.5 + AGP 7.4.2 组合合法。
- **切勿使用 R8 9.x**（需 AGP 8.4+，会与 7.4.2 冲突）。
- 兜底：若 8.3.37 仍报 `r8.kotlin.H`（说明 Kuikly 产物带了更高版本元数据），升级到 **8.5.35 / 8.9.35**（仍为 8.x 线）；若报 R8/AGP 不兼容，退回 **8.5.35**。

---

## 📝 下一步工作

### 功能完善

- [ ] 替换模拟数据为真实 API 调用
- [ ] 实现 ViewModel 层（状态管理）
- [ ] 集成图表库展示 K 线
- [ ] 添加下拉刷新和上拉加载更多
- [ ] 实现消息持久化（本地数据库）
- [ ] 添加错误重试机制

### 体验优化

- [ ] 添加 Loading 动画
- [ ] 添加空状态插画
- [ ] 优化键盘弹出逻辑
- [ ] 支持深色模式
- [ ] 添加页面切换动画

### 性能优化

- [ ] 列表虚拟化（懒加载）
- [ ] 图片缓存
- [ ] 网络请求去重
- [ ] 内存泄漏检测

---

## 📞 技术支持

- **Kuikly 官方文档**: https://kuikly.tds.qq.com/
- **KMP 官方文档**: https://kotlinlang.org/docs/multiplatform.html
- **FastAPI 文档**: https://fastapi.tiangolo.com/

---

## 📄 许可证

本项目仅供学习和演示使用。
