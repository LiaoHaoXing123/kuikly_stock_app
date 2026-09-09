# iOS 端支持（与 Android 双端可运行）

日期：2026-09-08。目标：iOS 编译通过并可正常运行；顺带补齐 JS 端缺失的 actual。

## 改动一览

- `String.format` 是 JVM 专属 API（KT-25506 至今未解决），commonMain 内 37 处调用会让
  iOS / JS 编译失败。新增 `data/AlertEngine.kt` 纯 Kotlin 实现 `fmtFixed/fmt0/fmt1/fmt2/
  fmt3/fmtSigned2/fmtSignedPct`，并替换全部 7 个文件的调用点。
- 新增 `data/JsonBackedStockDb.kt`（common）：无 SQLite 平台的行情库实现，基于随包内置的
  `stock_list.json`（5212 条 A 股快照）与 `stock_kline.json`，语义与 Android 的 SQLite
  实现对齐（排序键 / 分页 / 涨跌统计口径 / mention 上限 3）。
- 新增 `StockDb.ios.kt` / `StockDb.js.kt`：13 个 actual 全部委托给 `JsonBackedStockDb`。
- 新增 `DataUpdater.ios.kt` / `DataUpdater.js.kt`：`refreshNow()` 恒为 false（无库可导入）。
- iOS 持久化从 stub 换成真实现：`appPrefsGet/Set` → NSUserDefaults，
  `SecureSecretStore` → 系统 Keychain（kSecClassGenericPassword，无需 entitlement），
  `copyTextToClipboard` → UIPasteboard。
- `ContentView.swift` 启动页从开发调试用的 `"router"` 改为 `"home_dashboard"`（与 Android 一致）。
- 空态兜底：分时卡片增加 `isEmpty` 隐藏（iOS/JS 无分钟级数据）；状态文案「本地 SQLite」
  改为平台中立的「本地行情库」；使用指南的数据章节标注 iOS 快照随 App 版本更新。

## iOS 上可用的能力

首页快照、行情列表 / 搜索 / 排序、自选、提醒、股票详情（含日 K 线图）、聊天（含在线
AI + 工具调用，走 JSON 快照数据）、API 配置（Key 存 Keychain）、导出复制、使用指南。

## iOS 上明确降级的能力

- 指数详情 / 指数列表：包内无指数快照，显示「暂无数据」。
- 分时图、盘口：无分钟级数据，卡片自动隐藏。
- 数据更新：刷新恒显示「数据已是最新」，快照随 App 版本更新。
- 分享面板 / 存下载文件夹：暂不支持，导出走剪贴板兜底。

## 构建说明（需在 macOS 上执行）

```bash
cd iosApp
pod install
open *.xcworkspace   # 用 Xcode 打开并构建
```

沙盒内无 Xcode，本次只做了静态校验（expect/actual 全覆盖、括号配平、无 JVM 专属
API 残留）；首次在 mac 上构建如遇到 Keychain / 签名问题，检查 Bundle ID 与
`KEYCHAIN_SERVICE` 即可（未使用 access group，无需额外 entitlement）。
