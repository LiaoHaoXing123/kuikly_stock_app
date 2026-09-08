package com.kuikly.stock.pages

import com.tencent.kuiklybase.config.FontStyle
import com.tencent.kuiklybase.config.FontWeight
import com.tencent.kuiklybase.config.MarkdownColors
import com.tencent.kuiklybase.config.MarkdownConfig
import com.tencent.kuiklybase.config.MarkdownDimens
import com.tencent.kuiklybase.config.MarkdownPadding
import com.tencent.kuiklybase.config.MarkdownTypography
import com.tencent.kuiklybase.config.TextStyleConfig

/**
 * 聊天消息 Markdown 渲染配置（KuiklyMarkdown 组件）。
 * 字号/颜色沿用此前自研渲染器的气泡风格：正文 15sp 深灰，标题加粗逐级减小。
 *
 * 兼容性说明（core 2.7.0 + KuiklyMarkdown 1.0.6）：
 * - 图片语法必须先经 [sanitizeMarkdownForRender] 剥离：组件图片路径用到的单参
 *   `src(url)` 是 core 2.24 新增的 API，2.7.0 上遇到 `![alt](url)` 会崩溃。
 * - 链接保持默认无点击行为：onLinkClick/onLinkLongPress 置空后，组件内依赖新
 *   core API 的 Span 事件代理分支不会执行，链接仅渲染为蓝色文本。
 */
internal val chatMarkdownConfig = MarkdownConfig(
    colors = MarkdownColors(
        text = 0xFF333333,
        codeBackground = 0xFFF5F5F5,
        inlineCodeBackground = 0xFFF0F0F0,
        dividerColor = 0xFFE0E0E0,
        tableBackground = 0xFFF8F8F8,
        blockQuoteBar = 0xFF7B8CFA,
        blockQuoteBackground = 0xFFF4F5FA,
        linkColor = 0xFF1A73E8,
        codeText = 0xFF333333,
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
        quote = TextStyleConfig(fontSize = 14f, fontStyle = FontStyle.Italic, color = 0xFF65758B),
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
 * 渲染前清洗：剥离图片语法并保留 alt 文本。
 * AI 股票问答几乎不会输出图片；此举只为彻底消除 2.7.0 缺失单参 src() 的崩溃向量。
 */
internal fun sanitizeMarkdownForRender(raw: String): String {
    if (!raw.contains("![")) return raw
    return raw.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
}
