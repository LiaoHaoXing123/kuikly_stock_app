# feat/p0-chart-ux 分支进度梳理

> 整理日期：2026-09-11
> 依据：zcode 会话日志（sess_ca640e50 主任务、sess_f8539d14 修复轮）与代码 diff 核对

## 一、原始需求

让股票 App 的交互从「原型感」走向「成熟 App 感」，共两轮：

**第一轮（P0 交互成熟度，主会话）**
1. 空态有行动出口（自选空列表、搜索无结果给引导按钮）
2. 加载态统一为骨架屏 + 扫光（shimmer），替换纯文字「加载中…」
3. 数字变化有过渡（价格/涨跌幅滚动），不硬切
4. Tab/标签切换、弹窗出现、按钮按压有动效反馈
5. 列表增删、页面转场不瞬切
6. 顺带修复：AI 分析卡片渲染出原始数据串、首页数据日期与个股页不一致

**第二轮（修复轮，fix 会话）**
7. 刷新按钮点击后浅蓝底不消失（bug）
8. 底部模块（Tab）切换只有横切过渡，要求找成熟方案并落地更合适的转场

**第三轮（方向性转场，本轮）**
9. 下钻进入从右滑入、返回从右滑出（iOS push/pop 镜像），替换 Android 系统默认的淡入+缩放
10. 鸿蒙 `router.pushUrl` 无动画入参，fade 标记要在页面级 `pageTransition` 落地

## 二、已优化

### P0 交互成熟度（已提交 `2bc1566`）
- 空态行动出口：自选空列表加「去行情添加」入口
- 骨架屏 + shimmer 扫光：自选/K线/分时/盘口 4 处加载态统一
- 数字补间动画（NumberRoll）、弹窗淡入（Overlay）、AI 分析三点波浪动效（aiDotWaveDots）、按钮按压缩放（StepPulse）——均已接入页面
- 框架能力先核实再动手：确认 Kuikly 2.7.0 可动画属性仅 opacity/transform/backgroundColor/frame，避免走不通的路

### 收尾修复（`b713caf`）
- **AI 卡片渲染**：`level_card` 缺失渲染分支，原始 `Map.toString()` 直接摊到界面 → 补齐操作倾向/价位四行渲染（价位可点击标注、可设提醒），兜底只渲染白名单字段
- **数据日期口径统一**：首页只取自选第一只股票日期导致与个股页「断裂」→ 新增 `StockDb.latestTradeDate()`，三端统一读日线表最大值
- **刷新按钮浅蓝底不消失**：按压态 `Attr.animate` 丢帧 → 改硬切
- **模块切换转场**：同级 Tab 打 `transition=fade`（210ms 淡入）；下钻当时仍走系统默认横切

### 方向性转场（本轮）
- **Android**：新增 `NavTransition`。下钻 open = `kr_slide_in_right` + 旧页视差 `kr_slide_out_left`（30%）；close = 镜像从右滑出。同级 fade 的关闭补了 `kr_module_fade_out`。系统返回键走 `Activity.finish`，与 `closePage` 同一套动画。API 34 用 `overrideActivityTransition` 在 `onCreate` 登记（修掉原先登记在源 Activity 上、对「这一次」打开无效的问题）；API 33- 仍紧挨 `startActivity` / `finish` 调 `overridePendingTransition`。启动页 `NavMotion.NONE`，不会从桌面滑进来。
- **iOS**：下钻继续系统 `push/pop animated:YES`（本身就是从右滑入 / 从右滑出 + 视差）。同级模块关闭改为 cross-dissolve，与打开对称，避免「进淡、出切」。
- **鸿蒙**：`router.pushUrl` 确实没有动画入参。在 `pages/Index.pageTransition` 按本页 `pageData.transition` 分支：fade → opacity 210ms；下钻 → `SlideEffect.Right/Left` 300ms 的 push/pop 镜像。读的是本实例参数，出场不会误用栈顶页的标记。

时长/曲线（按位移选，不再一律 200ms）：
- 同级淡入：210ms + decelerate / FastOutSlowIn / iOS cross-dissolve
- 下钻横切：300ms + Material 3 emphasized `cubic-bezier(0.2, 0, 0, 1)`；iOS 用系统导航曲线（约 350ms）

## 三、待优化

1. 转场观感建议真机确认 300ms / 30% 视差是否要微调（本环境无真机）
2. iOS 无本地编译环境，改动未在 Xcode 过一遍
3. 鸿蒙 `pageTransition` 无本地 DevEco 编译
4. 历史遗留：完整分档资金、分钟资金流叠加、自由绘制保存趋势线、龙虎榜、融资融券、券商下单（超出本分支范围）

## 四、产生的影响

- **转场语义**：同级 = 淡入淡出；下钻 = 从右滑入 / 返回从右滑出。Android 不再吃系统默认的淡入+缩放。
- **返回键对齐**：Android 系统返回与页面返回按钮用同一套 CLOSE 动画。
- **鸿蒙 fade 不再是空操作**：标记真正驱动 `pageTransition`。
- **兼容性**：Android SDK 23–34；shared 侧只改了注释，三端 Kotlin/JS 编译不受影响。
- **CI**：按用户要求本轮提交带 `[skip ci]`，不跑 GitHub Actions。
