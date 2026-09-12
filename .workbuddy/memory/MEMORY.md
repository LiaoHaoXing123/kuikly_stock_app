# kuikly_stock_app — 项目长期记忆

Kuikly(KMP) 股票 App（origin `LiaoHaoXing123/kuikly_stock_app`），模块 `shared`/`androidApp`/`iosApp`/`h5App`/`miniApp`。

## 构建与验证
- iOS 目标在 Windows 上**静默 SKIPPED**（`kotlin.native.ignoreDisabledTargets=true`），不是成功。
- 改完 commonMain 先跑 `:shared:compileKotlinJs`（几秒），暴露 JVM 专属 API 泄漏。
- 本机可验 iOS Kotlin 编译：`./gradlew :shared:compileKotlinIosArm64 :shared:compileKotlinIosX64 :shared:compileKotlinIosSimulatorArm64 -Pkotlin.native.enableKlibsCrossCompilation=true`（只覆盖 Kotlin，链接/Xcode 靠 CI `macos-15`）。
- `:shared:jsTest` 本机不可用（`kotlinNodeJsSetup` 仓库冲突）→ commonTest 走 `:shared:testDebugUnitTest`。
- 时间统一 `data/Time.kt` 的 `nowMillis()`，禁止 commonMain 用 `System.currentTimeMillis()`。
- Windows Git Bash：`find`/`sort`/`grep` 用绝对路径；`/tmp` 不存在，用 `$LOCALAPPDATA/Temp`。

## UI 分层（可复用 UI 只放 `ui/`，别塞 `pages/`）
- `ui/theme/AppTheme.kt` token 唯一来源：`AppColor`(语义色) · `AppRadius` · `AppSpace` · `AppFont` · `AppSize` · `AppMotion`。
- `ui/component/`：Motion · Overlay · Interaction(PressState/pressed\*/skeletonBlock) · Refresh · Nav · Feedback · Drawer(sideDrawer) · QuoteItem · ControlButton · EntryCard · Segmented · Dialog。
- `util/TextUtil.kt` 纯函数。`base/` 只留 BasePager/BridgeModule/Haptics/IPagerIdKtx/Utils。
- **颜色纪律**：`pages/`、`ui/` 不许硬编码色，走 `AppColor.*`（`data/` 除外）。深色底白字用 `ON_DARK`，白底用 `SURFACE`。
- **`animate()` 硬约束**：按 `(observable, viewRef)` 注册后不可撤销，一个 observable 只挂一条曲线；进场/退场必须拆两个 observable。`Translate` 的 offsetX/offsetY 序列化丢失，位移只能用 `percentageX`。
- 下拉刷新：`pullToRefresh` 放 Scroller 第一子视图 + 必须给 `height()`；早退分支也要 `endRefresh()`。

## 版本与依赖
Kotlin `2.1.21`；AGP 实际 **7.4.2**（`libs.versions.toml` 的 `agp=9.3.1` 未生效）。钉 `com.android.tools:r8:8.3.37`（否则 `mergeExtDexDebug` 报 `r8.kotlin.H`），**不要升 R8 9.x**。需 **JDK 17**。

## 代码约定
- **Pager 页面必须继承 `base/BasePager`**（注册 `BridgeModule`）。取 Module 用 `Utils.currentBridgeModuleOrNull()`，**绝不用 `currentBridgeModule()`**（经 `throwRuntimeError` 延迟抛，runCatching 拦不住）。
- Canvas 手绘不能用 `attr.animate(Animation,value)`，视口动画用协程逐帧改 observable（`ChartAnimation.kt`，delay 收 Int 毫秒）。
- iOS `StockDb` 委托 `JsonBackedStockDb`（读 `commonMain/assets/*.json`）；密钥存 Keychain。

## Kuikly 框架能力（先查源码再断言缺失）
源码：`~/.gradle/caches/modules-2/files-2.1/com.tencent.kuikly-open/core/<ver>/<hash>/core-<ver>-sources.jar`。
- 刷新 `views/RefreshView.kt`；触底 `FooterRefreshView.kt`；动画 `base/Animation.kt`；转场 `TransitionView.kt`。
- **`vbind`（派生列表唯一正解）**：`vbind({ 指纹 }){…}`，指纹变整块重建。`vfor` 只吃 `ObservableList<T>`，派生列表用 `vbind`+`forEach`。
- `observableList`：读列表本身被追踪，`size`/`get` 不被追踪。
- 触觉：扩宿主 `HRBridgeModule`（不新建 Module），common `base/Haptics.kt` 的 `hapticTick`。
- 内置 Module：BackPress/Calendar/Codec/Font/Image/MemoryCache/Network/Notify/Performance/Reflection/Router/SharedPreferences/Snapshot/TurboDisplay/Vsync。

## 内置 JSON 资产契约
Android 读 SQLite `stock.db`；iOS/JS 读 `commonMain/assets/*.json`，逐字段一致。生成 `data-pipeline/export_common_assets.py`，校验 `test_export_common_assets.py`。**`sector_list.json` 内嵌 `stock_board` 直连映射，不要改回按行业名 JOIN**。

## StockDetailPage 已拆分
4327 行 → 主类 876 行 + 7 模块（Kline/Ai/Minute/Info/OrderBook/Views/Models）。

## 真机验证
`adb.exe` 在 `~/AppData/Local/Android/Sdk/platform-tools/`（裸 adb 找不到）。`emulator.exe -avd Pixel_6_35` → `emulator-5556`，1080x2400 / density 420（截图即竖屏）。MuMu `127.0.0.1:16384` 截图横屏 1920x1080。定位控件用 `uiautomator dump`（Kuikly 文字读不到，`accessibility("…")` 过的以 `content-desc` 出来并带 bounds，取中心点点击）。Canvas 手绘截图无控件，靠亮度直方图/像素比对。

## 协作
AI 本地改代码 + 跑构建验证，`git push` 由用户执行（沙箱访问不到 GitHub）。
