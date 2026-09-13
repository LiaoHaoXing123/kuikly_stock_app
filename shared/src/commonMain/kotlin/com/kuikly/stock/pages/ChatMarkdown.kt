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

internal fun sanitizeMarkdownForRender(raw: String): String {
    if (!raw.contains("![")) return raw
    return raw.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "")
}
