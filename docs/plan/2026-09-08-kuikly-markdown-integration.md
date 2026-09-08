# KuiklyMarkdown 组件接入（替换自研 Markdown 渲染器）

- 日期：2026-09-08
- 状态：已实现，需本地 `./gradlew` 首编验证（沙箱无 Android SDK，未编译）
- 分支：`arena/01a08076-kuikly-stock-app`（本次提交）；如需独立分支：`git checkout -b feature/kuikly-markdown <本提交>`

## 1. 为什么换

自研渲染器（`parseMarkdown` + `renderMarkdown`，约 130 行）只支持标题/引用/无序列表/代码块/段落 + 行内加粗；
AI 提示词要求 Markdown 作答，但输出中的**表格、有序列表、行内代码、链接、删除线/斜体**会被降级为
原样文本（如 `| 股票 | 代码 |` 直接显示竖线）。流式阶段更是纯文本。

`Kuikly-contrib/KuiklyMarkdown`（MIT，源码内含 JetBrains intellij-markdown / Highlights，
均为 Apache-2.0，见其仓库 THIRD_PARTY_LICENSES）提供完整 CommonMark + GFM：表格、有序/任务列表、
行内代码、链接、图片、分割线、19 种语言代码高亮，以及 AI 场景的块级增量流式渲染。

卡片协议（conclusion/stock/index/chart/compare 等）保持不变：**长文本走组件，结构化数据走卡片**。

## 2. 依赖与版本

| 依赖 | 版本 | 说明 |
|---|---|---|
| `com.tencent.kuiklybase:KuiklyMarkdown` | `1.0.6-2.1.21` | Kotlin 2.1.21 变体，与本项目 Kotlin 版本一致 |
| `kotlinx-coroutines-core`（commonMain 新增） | `1.10.1` | 组件 README 要求宿主提供；与组件自身构建版本一致 |
| `kotlinx-coroutines-android` | `1.6.4` → `1.10.1` | 与 core 对齐（shared/androidMain + androidApp） |
| `kotlinx-serialization-json` | `1.7.3` → `1.8.0` | 与组件自身构建版本一致；本项目无 `@Serializable`，仅基础 Json API，低风险 |
| `com.tencent.kuikly-open:core` | `2.7.0-2.1.21` 不变 | **不升级**（升级需同步原生 render，风险大，见 §3） |

仓库：`settings.gradle.kts` 的 `dependencyResolutionManagement` 新增
`https://mirrors.tencent.com/nexus/repository/maven-tencent/`（注意是 `nexus/` 路径，
与旧 `repository/maven-tencent/` 不同；旧源保留）。

关键事实（已逐条核实源码）：组件 `build.gradle.kts` 中 core / coroutines / serialization
**全部是 `compileOnly`**，因此：

- Gradle **不会**把本项目 core 2.7.0 顶到 2.24.0，无版本冲突；
- 但组件 klib 引用的 core 符号在**本项目编译/链接时**必须存在 → 做了 §3 的兼容性核查。

## 3. core 2.7.0 兼容性核查（逐符号对照 [KuiklyUI@2.7.0](https://github.com/Tencent-TDS/KuiklyUI/tree/2.7.0) 源码）

| 组件用到的 core API | 2.7.0 | 结论 |
|---|---|---|
| `View/Text/RichText/Span/Image` DSL、`DivView`、`addChild` | ✅ 存在 | 静态 + 流式主路径安全 |
| `TextSpan.click(ClickParams)`、`RichTextEvent` | ✅ 存在 | 链接点击链路符号存在 |
| `fontStyleItalic()`、`textDecorationLineThrough()`、`fontWeightBold()`、`lines()`、4 参 `padding()` | ✅ 存在 | 斜体/删除线/行内代码等常用样式安全 |
| `ClickParams` / `LongPressParams` 类型 | ✅ 存在 | 本项目编译期解析 `MarkdownConfig` 构造签名无碍 |
| `ImageSpan` / `ImageAttr` **单参 `src(url)`** | ❌ 缺失 | 2.7.0 只有 `src(url, isDotNineImage=false)` 等价形式，组件的单参调用在 2.7.0 无法解析 → **渲染前用正则剥离 `![alt](url)`（保留 alt），见 `sanitizeMarkdownForRender`** |
| `TextSpan.longPress {}`、`eventProxy`（`convertFrame`/`onFireEvent`） | ❌ 缺失/未验证 | 仅当配置 `onLinkClick/onLinkLongPress` 才执行 → **保持默认 null，Android 永不执行**；链接渲染为蓝色文本但无点击行为 |

稳定措施：

1. `sanitizeMarkdownForRender` 剥离图片语法（AI 股票问答本就不会输出图片，实际无感）。
2. 不使用 `diffUpdate`（2.7.0 无此 API，不确定）→ `syncStreamBlocks` 手写增量 diff：
   追加 / 末块替换走增量分支，其余全量刷新。
3. `onLinkClick` 保持 null（后续如需"点击复制链接"，需先验证 Span 事件在 2.7.0 render 端表现）。

## 4. 改动清单

- `shared/src/commonMain/kotlin/com/kuikly/stock/pages/ChatMarkdown.kt`（新）：`chatMarkdownConfig`
  （配色/字号沿用原气泡风格：正文 15sp `#333`，行高 23）+ `sanitizeMarkdownForRender`。
- `ChatMainPage.kt`：
  - 删除自研 `MdBlock` / `parseMarkdown` / `renderMarkdown` / `markdownHeading|List|Quote|Code|Paragraph`
    （134 行；`renderInlineBold` 被结论卡片复用，保留）；
  - AI 气泡：`KuiklyMarkdown(content = sanitizeMarkdownForRender(message.content), config = chatMarkdownConfig)`；
  - 流式气泡：`streamState: MarkdownStreamingState` + `streamBlocks` + `vfor` +
    `KuiklyStreamingMarkdown`，`onText` 回调内 `syncStreamBlocks(text)`；
    请求开始 / `finally` 中 `reset()` + `clear()`（停止生成、重试、切会话路径均覆盖）。
- `StockDetailPage.kt` / `IndexDetailPage.kt`：AI 分析气泡同方案替换（之前复用已删除的 `renderMarkdown`）。
- `ChatMarkdownTest.kt`（新）：清洗器 3 个单测。
- gradle：见 §2。

## 5. 已知限制

- **iOS 构建待验证**：Kotlin/Native 链接是保守可达性分析，图片/链接事件分支即使运行时不可达，
  也可能因符号缺失导致 iOS 链接失败。本次以 Android 为主目标；iOS 首编若报错，把错误贴回来，
  备选方案：`iosMain` 降级渲染（纯 `Text`）或升级 core。
- 链接暂无点击行为（见 §3）；表格超宽时按列均分（组件默认行为），列数过多时字小，需人工看一眼效果。
- `streamBlocks` 与 `streamingText` 双轨：前者渲染，后者供"停止生成"拼半截消息；属有意为之。

## 6. 本地验证步骤

```bash
./gradlew :shared:assembleDebug :androidApp:assembleDebug
```

1. 编译通过（重点看 `:shared:compileKotlinAndroid` 有无 `unresolved reference: kuiklybase` 类错误，
   若有，先检查 §2 仓库行是否生效）。
2. 聊天页发一条会触发长回答 + 表格的问题（如"对比茅台五粮液"），观察：
   流式阶段即带格式（加粗/列表逐步出现）、完成后表格正常、无闪退。
3. 发一条含代码块的问题，确认高亮配色在浅色气泡可读。
4. 个股详情页 / 指数详情页打开 AI 分析，确认渲染正常。
5. 回归：停止生成、重试、切会话、导出 Markdown（导出器读原始文本，不受渲染层影响）。
