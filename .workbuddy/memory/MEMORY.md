# kuikly_stock_app — 项目长期记忆

Kuikly(KMP) 股票 App，origin: `LiaoHaoXing123/kuikly_stock_app`。模块：`shared` / `androidApp` / `iosApp` / `h5App` / `miniApp`。

## 双端构建约定（重要）

**iOS 目标在 Windows 上默认被静默跳过**（`gradle.properties` 里 `kotlin.native.ignoreDisabledTargets=true`），任务状态是 `SKIPPED` 而非失败——不要把它当成功。

本机可在 Windows 上验证 iOS Kotlin 编译（Kotlin/Native 的 Windows 发行版自带 Apple 平台 klib）：

```bash
./gradlew :shared:compileKotlinIosArm64 :shared:compileKotlinIosX64 :shared:compileKotlinIosSimulatorArm64 \
  -Pkotlin.native.enableKlibsCrossCompilation=true --console=plain 2>&1 | grep -E "^e: |BUILD"
```

局限：只验 Kotlin 编译，**不覆盖** framework 链接 / CocoaPods / Xcode。那部分靠 `.github/workflows/build.yml` 的 `macos-15` job（Android job 在 ubuntu，跑 `:androidApp:assembleDebug` + `:shared:compileKotlinJs`）。

**快速冒烟测试**：非 JVM 目标里，`:shared:compileKotlinJs` 能在几秒内暴露 `commonMain` 里的 JVM 专属 API 泄漏（`System.currentTimeMillis` / `java.*` / `String.format` 等）。改完 commonMain 先跑它。

**跨平台时间**：统一用 `com.kuikly.stock.data.nowMillis()`（`Time.kt`，基于 `kotlin.time.Clock`）。禁止在 commonMain 直接用 `System.currentTimeMillis()`。

## 版本与依赖
- Kotlin `2.1.21`；AGP 实际应用 **7.4.2**（注意 `libs.versions.toml` 里的 `agp = 9.3.1` 并未生效）
- 根 `build.gradle.kts` 的 buildscript 里钉了 `com.android.tools:r8:8.3.37`，否则 `:androidApp:mergeExtDexDebug` 会因 Kotlin 2.1 元数据解析失败报 `com.android.tools.r8.kotlin.H`。**不要升到 R8 9.x**（需 AGP 8.4+）。
- Kuikly 依赖走 `com.tencent.kuikly-open:*`，版本由 `Version.getKuiklyVersion()` 提供（当前 `2.7.0-2.1.21`）；仓库在 `mirrors.tencent.com`。
- 构建需 **JDK 17**。

## 代码约定
- **所有 Pager 页面必须继承 `base/BasePager`**，不要直接 `: Pager()`。`BasePager` 唯一作用是 `createExternalModules()` 注册 `BridgeModule`；不收它的页面拿不到桥，`hapticTick` 等一旦取 Module 就会经 `throwRuntimeError` 延迟崩进程。10 个页面（首页/列表/详情/指数/自选/我的/风控/API 配置/引导/对话）已统一改过。
- `KlineInteractionHost`（`KlineChartInteraction.kt`）：个股页 / 指数页共用十字光标、框选、缩放平移的接口抽象。新增图表交互时两端都要接。
- `chartTouchLayer`（`ChartTouchLayer.kt`）：手势层。`nativeChartGestures` 参数决定走原生 `StockChartGestureView`（**仅 androidApp 有实现**）还是 DSL pan/pinch 兜底。iOS/JS 不要传 `true`。
- `StockColors`：涨跌配色常量，新增涨跌色一律引用它，不要硬编码。
- iOS 端 `StockDb` 委托 `JsonBackedStockDb`（JSON 兜底），无 SQLite；`industryPeers` / `sectorOfStock` / `fundFlow` / `indexDetail` 已改走内置 JSON 资产（见下「内置 JSON 资产导出契约」），不再是 null/empty。
- iOS 密钥存 Keychain（`SecureSecretStore.ios.kt`）。cinterop 文件需 `@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)`；用 `ptr` / `value` 扩展属性记得显式 `import kotlinx.cinterop.ptr` / `.value`。

## Kuikly 框架自带能力（核对过的，别重复造）
框架源码包在 Gradle 缓存里可解压查阅：`~/.gradle/caches/modules-2/files-2.1/com.tencent.kuikly-open/core/<ver>/<hash>/core-<ver>-sources.jar`，内部路径前缀 `commonMain/com/tencent/kuikly/core/`。
判断「某能力缺失」前**必须先查这里**——业务代码搜不到只说明没接线，不代表框架没有。

- **下拉刷新**：`views/RefreshView.kt` → `ScrollerView.Refresh { }`，`refreshEnable`、`refreshStateDidChange`、`pullingPercentageChanged`、`beginRefresh(animated)`；状态 `IDLE/PULLING/REFRESHING`
- **触底自动加载**：`views/FooterRefreshView.kt` → `beginRefresh()` / `endRefresh(endState)`，`preloadDistance`（默认 100f 预加载）
- **动画**：`base/Animation.kt` → `Animation.linear/easeIn/easeOut/easeInOut/spring*`、`delay`、`repeatForever`；施加方式 `base/attr/IStyleAttr.kt` 的 `animation(anim, value)` / `animate(anim, value)`
- **转场**：`views/TransitionView.kt`
- **Hover**：`views/HoverView.kt` → `ScrollerView.Hover { }`
- **触觉反馈**：框架无 API，已在宿主 `HRBridgeModule` 上扩方法自建（**不新建 Module**，否则要在 iOS xcodeproj 里登记文件）。
  common：`base/Haptics.kt`（`hapticTick(HapticStyle.Light/Medium/Heavy)`，runCatching fire-and-forget）；Android：`KRBridgeModule.vibrate()`（`VibrationEffect.createOneShot`，<26 回落老 API）；iOS：`HRBridgeModule.m -vibrate:`（`UIImpactFeedbackGenerator`，主线程）。JS 走空操作。
  三档语义：Medium=落点锁定、Heavy=模式切换、Light=收尾/切档/点击；连续平移不震。
  ⚠️ **取 Module 必须走 `Utils.currentBridgeModuleOrNull()`**（内部 `Pager.getModule`，未注册返回 null）。绝对不要用 `currentBridgeModule()`——它经 `throwRuntimeError`，而后者是 `setTimeout(1){throw}` 的**延迟抛**，`runCatching` 拦不住，1ms 后在定时器线程崩进程（`FATAL EXCEPTION: HRContextQueueHandlerThread`）。
- **按压态 / 骨架屏基元**：`base/Interaction.kt`（纯表现层，跨端，不碰业务）。
  `PressState(scope)` 用一个 observable `key: String` 做"当前按下的 tag"，配合 `Attr.pressedBg(press, tag, normal, pressed)` + `GroupEvent.pressFeedback(press, tag)`（内部接 `touchDown/touchUp/touchCancel`）。原理是 `attr { }` 包了 `ReactiveObserver`，**attr 块里读到的 observable 一变整块自动重跑**并下发 native prop，所以按压态零原生代码。
  点击 handler 里要顺手 `press.releaseAll()`（防 touchUp 丢失后卡住）。颜色常量必须显式标 `: Long`（`0x33FFFFFF` 落在 Int 范围会类型不符）。
  `skeletonBlock(height, w?, radius, color)`：占位灰块，配 `SKELETON_BG` / `SKELETON_BG_STRONG` / `SKELETON_BG_ON_DARK`。
- 内置 Module 清单：BackPress / Calendar / Codec / Font / Image / MemoryCache / Network / Notify / Performance / Reflection / Router / SharedPreferences / Snapshot / TurboDisplay / Vsync

**Windows Git Bash 注意**：裸 `find` / `sort` / `grep` 会撞到 Windows 的同名 exe（报 `FIND: 无效的开关`），用 `/usr/bin/find`、`/usr/bin/sort` 等绝对路径；`/tmp` 不存在，临时目录用 `$LOCALAPPDATA/Temp`。

## 列表刷新组件使用约束（踩过的坑）
- `RefreshView`（`ScrollerView.Refresh { }`）：**必须放 Scroller 的第一个子视图**（内部用 `parent?.parent` 取 Scroller）；**必须给 `height()`**，否则高度为 0 时状态回调直接被 return；它是 `positionAbsolute`，靠 z 序垫在内容下面，下拉才露出。引用用 `ref { }` 拿 `ViewRef<RefreshView>`，完成时 `endRefresh()`；**早退分支也要调 `endRefresh()`**，否则指示器一直转。
- `FooterRefreshView`（`FooterRefresh { }`）：沿父链找 Scroller，位置自由；`preloadDistance(200f)` 控制预加载距离；用 `endRefresh(SUCCESS / NONE_MORE_DATA)` 收尾；重新拉取时 `resetRefreshState(IDLE)` 复位。
- 分时与 K 线的手势语义已统一：tap=锁定/取消，拖动=跟手（`minuteLocked=false`），抬手=保留锁定。`KlineInteractionHost.selectMinuteAtX(x, locked)`。

## 真机验证（模拟器可用）
`adb.exe` 在 `~/AppData/Local/Android/Sdk/platform-tools/`，**裸 `adb` 会 command not found，用绝对路径**。已有模拟器：`127.0.0.1:16384` / `:7555`（Redmi K50）。装 APK → `am start -n com.kuikly.stock/.KuiklyRenderActivity` → `exec-out screencap -p`。
坑：① 截图实际是**横屏 1920x1080**，别信 `wm size` 报的 1080x1920；② `uiautomator dump` **读不到 Kuikly 文字**（自绘），定位控件只能读布局代码算 dp→px（密度 280dpi，×1.75）或扫描点击比对截图哈希；③ 抓手势中间帧要 `swipe ... & sleep; screencap` 并发。
Canvas 手绘内容不能用 `attr.animate(Animation, value)`（那只作用于视图属性）；视口动画用协程逐帧改写 observable，见 `ChartAnimation.kt`。注意 `core.coroutines.delay` 收 **Int 毫秒**。

## 内置 JSON 资产导出契约（iOS/JS 数据来源）
Android 用 SQLite `stock.db`；iOS/JS 无 SQLite，读 `shared/src/commonMain/assets/*.json`。两者必须逐字段一致。

生成脚本：`data-pipeline/export_common_assets.py`（跑在 `run_daily.ps1` 的 `[1b/3]` 步骤），产出
`index_list.json` / `sector_list.json` / `fundflow_list.json`；校验：`test_export_common_assets.py`（CI `build.yml` 的 `data-assets` job）。

**`sector_list.json` 内嵌 `stock_board` 直连映射（code→board_code），不要改回按行业名 JOIN。**
`stock_list.json` 是人工裁剪的旧快照（5212 条 vs DB 5911 条，价格 8-25 vs 9-09），无生成脚本、不可复现，
行业词表已漂移（旧「银行Ⅱ」vs 新「股份制银行Ⅲ」），按行业名 JOIN 会选错板块。**该文件保持不动。**

## StockDetailPage 已拆分（勿再塞回单文件）
原 4327 行 → 主类 876 行 + 7 个同包模块：`StockDetailKline`(K线区) / `StockDetailAi`(AI解析+卡片) /
`StockDetailMinute`(分时) / `StockDetailInfo`(信息+行情+指标) / `StockDetailOrderBook`(五档) /
`StockDetailViews`(状态视图) / `StockDetailModels`(数据类)。
新增详情页 UI 时按主题放进对应模块。各文件保留了同一份 import 列表（未用 import 只是 warning）。
注意：`pricePill` / `fmtInd` / `fmtOpt` 是**各模块的 file-private**，跨模块用不到。

## 协作方式
AI 在本地改代码 + 跑构建验证，`git push` 由用户在本机执行（沙箱访问不到 GitHub）。仓库是私有的。
