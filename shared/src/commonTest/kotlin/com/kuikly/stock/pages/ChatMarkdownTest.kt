package com.kuikly.stock.pages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ChatMarkdownTest {
    @Test
    fun sanitizeStripsImageSyntaxButKeepsAlt() {
        assertEquals(
            "看这张走势图很清晰",
            sanitizeMarkdownForRender("看这张走势图![走势图](https://x/y.png)很清晰")
        )
    }

    @Test
    fun sanitizeHandlesMultipleImages() {
        assertEquals("ab", sanitizeMarkdownForRender("![a](1)![b](2)"))
    }

    @Test
    fun sanitizeLeavesNormalMarkdownUntouched() {
        val md = "# 标题\n\n**加粗** `代码` [链接](https://x)\n\n- 列表\n\n| a | b |\n|---|---|\n| 1 | 2 |"
        assertEquals(md, sanitizeMarkdownForRender(md))
        assertFalse(sanitizeMarkdownForRender("![x](y)").contains("!["))
    }
}
