package com.kuikly.stock.pages

import com.tencent.kuiklybase.config.FontStyle
import com.tencent.kuiklybase.config.FontWeight
import com.tencent.kuiklybase.config.MarkdownColors
import com.tencent.kuiklybase.config.MarkdownConfig
import com.tencent.kuiklybase.config.MarkdownDimens
import com.tencent.kuiklybase.config.MarkdownPadding
import com.tencent.kuiklybase.config.MarkdownTypography
import com.tencent.kuiklybase.config.TextStyleConfig
import com.kuikly.stock.ui.theme.AppColor

/**
 * 聊天消息 Markdown 渲染配置（KuiklyMarkdown 组件）。
 * 字号/颜色沿用此前自研渲染器的气泡风格：正文 15sp 深灰，标题加粗逐级减小。
 *
 * 【为什么是函数而不是 val】
 * `MarkdownConfig` 里的颜色是**创建那一刻**从 [AppColor] 取出的 Long 值；若做成顶层
 * `val`，配置会在进程首次访问时冻结一套色板。App 启动时恢复的是上次主题，之后用户在
 * 「我的」里切换浅/深，其它视图会经 themeGeneration 重跑换色，唯独这个静态配置不会
 * 更新 —— 表现为「浅色模式下 AI 问答仍是白字」（冻结的是启动时的深色字色）。
 * 改成每次渲染时重建，调用点所在 attr 块已依赖 AppColor（气泡背景等），主题切换时
 * 会自动重跑并取到当前色板。
 *
 * 兼容性说明（core 2.7.0 + KuiklyMarkdown 1.0.6）：
 * - 图片语法必须先经 [sanitizeMarkdownForRender] 剥离：组件图片路径用到的单参
 *   `src(url)` 是 core 2.24 新增的 API，2.7.0 上遇到 `![alt](url)` 会崩溃。
 * - 链接保持默认无点击行为：onLinkClick/onLinkLongPress 置空后，组件内依赖新
 *   core API 的 Span 事件代理分支不会执行，链接仅渲染为蓝色文本。
 */
internal fun chatMarkdownConfig(): MarkdownConfig = MarkdownConfig(
    colors = MarkdownColors(
        text = AppColor.TEXT_INK,
        codeBackground = AppColor.SURFACE_SOFT,
        inlineCodeBackground = AppColor.SURFACE_SOFT,
        dividerColor = AppColor.DIVIDER,
        tableBackground = AppColor.SURFACE_ALT,
        blockQuoteBar = AppColor.ACCENT,
        blockQuoteBackground = AppColor.SURFACE_ALT,
        linkColor = AppColor.PRIMARY,
        codeText = AppColor.TEXT_INK,
    ),
    typography = MarkdownTypography(
        text = TextStyleConfig(fontSize = 15f, lineHeight = 23f),
        code = TextStyleConfig(fontSize = 13f, lineHeight = 20f),
        inlineCode = TextStyleConfig(fontSize = 13f),
        h1 = TextStyleConfig(fontSize = 17f, fontWeight = FontWeight.Bold),
        h2 = TextStyleConfig(fontSize = 16f, fontWeight = FontWeight.Bold),
        h3 = TextStyleConfig(fontSize = 15f, fontWeight = FontWeight.Bold),
        h4 = TextStyleConfig(fontSize = 15f, fontWeight = FontWeight.Bold),
        h5 = TextStyleConfig(fontSize = 14f, fontWeight = FontWeight.Bold),
        h6 = TextStyleConfig(fontSize = 14f, fontWeight = FontWeight.Bold),
        quote = TextStyleConfig(fontSize = 14f, fontStyle = FontStyle.Italic, color = AppColor.TEXT_SUB_DEEP),
        paragraph = TextStyleConfig(fontSize = 15f, lineHeight = 23f),
        ordered = TextStyleConfig(fontSize = 15f, lineHeight = 23f),
        bullet = TextStyleConfig(fontSize = 15f, lineHeight = 23f),
        list = TextStyleConfig(fontSize = 15f, lineHeight = 23f),
        table = TextStyleConfig(fontSize = 13f, lineHeight = 20f),
        textLink = TextStyleConfig(fontSize = 15f),
    ),
    dimens = MarkdownDimens(
        tableCellPadding = 10f,
        tableCornerSize = 8f,
    ),
    padding = MarkdownPadding(
        block = 6f,
    ),
)

/**
 * 渲染前清洗：剥离整段图片语法（含 alt 文本）。
 * AI 股票问答几乎不会输出图片；此举只为彻底消除 2.7.0 缺失单参 src() 的崩溃向量。
 */
internal fun sanitizeMarkdownForRender(raw: String): String {
    if (!raw.contains("![")) return raw
    return raw.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "")
}
