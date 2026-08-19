package com.kuikly.stock.pages

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable

/**
 * AI 聊天主页面（默认首页）
 *
 * 功能：
 * 1. 左上角【大盘行情】按钮 → 跳转行情列表页
 * 2. 聊天消息列表区域（支持 Markdown + 结构化卡片渲染）
 * 3. 输入框和发送按钮
 * 4. 会话记录功能
 */
@Page("chat_main")
class ChatMainPage : Pager() {

    // 状态：消息列表（使用 observable 触发 UI 刷新）
    internal var messages by observable(mutableListOf<ChatMessageItem>())

    // 状态：输入框文本
    internal var inputText by observable("")

    // 输入框引用，用于主动清空/聚焦
    lateinit var inputRef: ViewRef<InputView>

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    flexDirectionColumn()
                    backgroundColor(0xFFF5F5F5)
                }
                // 顶部导航栏
                topBar(ctx)
                // 消息列表或空态提示（根据是否有消息自动切换）
                if (ctx.messages.isEmpty()) {
                    welcomeHint()
                } else {
                    messageList(ctx)
                }
                // 底部输入区域
                inputArea(ctx)
            }
        }
    }

    /**
     * 发送消息
     */
    internal fun sendMessage() {
        val text = inputText.trim()
        if (text.isEmpty()) return

        // 添加用户消息到列表
        messages.add(ChatMessageItem(role = "user", content = text, isUser = true))

        // 清空输入框（状态 + 原生控件）
        inputText = ""
        inputRef.view?.setText("")

        // TODO: 调用后端 API 发送消息
        // 1. POST /api/v1/ai/chat
        // 2. 解析返回的 reply.text 和 reply.cards
        // 3. 添加 AI 回复到 messages 列表
        // 4. 刷新 UI

        // 模拟 AI 回复（开发阶段）
        simulateAIResponse(text)
    }

    /**
     * 模拟 AI 回复（开发测试用）
     * 实际项目中应替换为真实的 API 调用
     */
    internal fun simulateAIResponse(userMessage: String) {
        val mockReply = ChatMessageItem(
            role = "assistant",
            content = "收到您的问题：\"$userMessage\"\n\n" +
                "这是一个模拟回复。在实际应用中，这里会显示 DeepSeek AI 的分析结果。" +
                "支持 Markdown 格式文本和结构化卡片展示。",
            isUser = false,
            cards = listOf(
                mapOf(
                    "type" to "stock_card",
                    "code" to "000001",
                    "name" to "平安银行",
                    "price" to "11.05",
                    "changePercent" to "+1.20%"
                ),
                mapOf(
                    "type" to "trend_card",
                    "title" to "趋势判断",
                    "content" to "短期震荡上行，中期看涨",
                    "color" to "#FF6B6B"
                )
            ),
            suggestions = listOf(
                "查看技术指标？",
                "对比同行业？"
            )
        )

        messages.add(mockReply)
    }
}

// ==================== 顶部扩展函数（top-level，避免成员扩展函数在 body 内无法调用的问题）====================

/**
 * 顶部导航栏
 */
internal fun ViewContainer<*, *>.topBar(ctx: ChatMainPage) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            height(56f)
            backgroundColor(0xFFFFFFFF)
            paddingTop(ctx.pagerData.statusBarHeight)
        }

        // 左侧：【大盘行情】按钮
        View {
            attr { padding(left = 16f, top = 12f, right = 16f, bottom = 12f) }
            event {
                click {
                    ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                        .openPage("stock_list", JSONObject())
                }
            }
            Text {
                attr {
                    text("📊 大盘行情")
                    fontSize(14f)
                    color(0xFF1976D2)
                    fontWeightBold()
                }
            }
        }

        // 中间：标题
        View { attr { flex(1f) } }
        Text {
            attr {
                text("AI 智能助手")
                fontSize(18f)
                fontWeightBold()
                color(0xFF333333)
            }
        }
        View { attr { flex(1f) } }

        // 右侧：（可选）历史记录按钮
        View {
            attr { padding(left = 16f, top = 12f, right = 16f, bottom = 12f) }
            Text {
                attr {
                    text("📝")
                    fontSize(16f)
                    color(0xFF666666)
                }
            }
        }
    }
}

/**
 * 消息列表区域
 */
internal fun ViewContainer<*, *>.messageList(ctx: ChatMainPage) {
    Scroller {
        val scroller = this
        attr {
            flex(1f)
            flexDirectionColumn()
            scrollEnable(true)
            padding(left = 12f, top = 8f, right = 12f, bottom = 8f)
        }
        with(scroller) {
            ctx.messages.forEach { message ->
                chatBubble(ctx, message)
            }
        }
    }
}

/**
 * 单条聊天消息气泡
 */
internal fun ViewContainer<*, *>.chatBubble(
    ctx: ChatMainPage,
    message: ChatMessageItem
) {
    View {
        attr {
            flexDirectionRow()
            marginTop(8f)
            justifyContent(if (message.isUser) FlexJustifyContent.FLEX_END else FlexJustifyContent.FLEX_START)
        }

        // 消息内容容器
        View {
            val bubble = this
            attr {
                maxWidth(ctx.pagerData.pageViewWidth - 100f)
                backgroundColor(if (message.isUser) 0xFFE3F2FD else 0xFFFFFFFF)
                borderRadius(12f)
                padding(left = 12f, top = 10f, right = 12f, bottom = 10f)
            }

            // 消息文本（支持 Markdown 的简化版本）
            Text {
                attr {
                    text(message.content)
                    fontSize(15f)
                    color(0xFF333333)
                    lineHeight(1.6f)
                }
            }

            with(bubble) {
                message.cards?.forEach { card -> renderCard(ctx, card) }
                message.suggestions?.forEach { suggestion -> suggestionChip(ctx, suggestion) }
            }
        }
    }
}

/**
 * 渲染 AI 返回的结构化卡片
 */
internal fun ViewContainer<*, *>.renderCard(
    ctx: ChatMainPage,
    card: Map<String, Any?>
) {
    val type = card["type"] as? String ?: "unknown"
    when (type) {
        "stock_card" -> stockCard(ctx, card)
        "chart_card" -> chartCard(card)
        "trend_card" -> aiCard(card, "📈 趋势判断")
        "signal_card" -> aiCard(card, "📡 技术信号")
        "risk_card" -> aiCard(card, "⚠️ 风险评估")
        "suggestion_card" -> aiCard(card, "💡 操作建议")
        "summary_card" -> aiCard(card, "📋 AI 总结")
        else -> unknownCard(card)
    }
}

/**
 * 股票信息卡片（可点击跳转）
 */
internal fun ViewContainer<*, *>.stockCard(
    ctx: ChatMainPage,
    card: Map<String, Any?>
) {
    val code = card["code"] as? String ?: ""
    val name = card["name"] as? String ?: ""
    val price = card["price"] as? String ?: "-"
    val changePercent = card["changePercent"] as? String ?: "-"

    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            marginTop(8f)
            padding(left = 12f, top = 10f, right = 12f, bottom = 10f)
            backgroundColor(0xFFF0F7FF)
            borderRadius(8f)
        }
        event {
            click {
                val params = JSONObject()
                params.put("code", code)
                ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                    .openPage("stock_detail", params)
            }
        }

        // 股票名称和代码
        View {
            attr { flex(1f) }
            Text {
                attr {
                    text("$name ($code)")
                    fontSize(14f)
                    fontWeightBold()
                    color(0xFF1976D2)
                }
            }
        }

        // 价格和涨跌幅
        Text {
            attr {
                text(price)
                fontSize(14f)
                fontWeightBold()
                color(if (changePercent.contains("+")) 0xFFE53935 else 0xFF43A047)
                marginLeft(8f)
            }
        }
        Text {
            attr {
                text(changePercent)
                fontSize(12f)
                color(if (changePercent.contains("+")) 0xFFE53935 else 0xFF43A047)
                marginLeft(4f)
            }
        }
    }
}

/**
 * AI 分析卡片（通用）
 */
internal fun ViewContainer<*, *>.aiCard(
    card: Map<String, Any?>,
    title: String
) {
    val content = card["content"] as? String ?: ""
    @Suppress("UNUSED_VARIABLE")
    val color = card["color"] as? String ?: "#FF6B6B"

    View {
        attr {
            flexDirectionColumn()
            marginTop(8f)
            padding(left = 12f, top = 10f, right = 12f, bottom = 10f)
            backgroundColor(0xFFFFF9C4)
            borderRadius(8f)
        }

        // 卡片标题
        Text {
            attr {
                text(title)
                fontSize(13f)
                fontWeightBold()
                color(0xFF333333)
            }
        }

        // 卡片内容
        Text {
            attr {
                text(content)
                fontSize(13f)
                color(0xFF555555)
                marginTop(4f)
                lineHeight(1.5f)
            }
        }
    }
}

/**
 * 图表卡片（简化版，实际项目中可集成图表库）
 */
internal fun ViewContainer<*, *>.chartCard(card: Map<String, Any?>) {
    val title = card["title"] as? String ?: "图表"
    val chartType = card["chartType"] as? String ?: "line"

    View {
        attr {
            flexDirectionColumn()
            marginTop(8f)
            padding(left = 12f, top = 10f, right = 12f, bottom = 10f)
            backgroundColor(0xFFE8F5E9)
            borderRadius(8f)
        }

        Text {
            attr {
                text("📊 $title ($chartType)")
                fontSize(13f)
                fontWeightBold()
                color(0xFF2E7D32)
            }
        }

        // 图表占位符（实际项目应使用图表库渲染）
        Text {
            attr {
                text("[图表数据加载中...]\n实际项目中应集成 MPAndroidChart 或其他图表库")
                fontSize(12f)
                color(0xFF666666)
                marginTop(4f)
                lineHeight(1.5f)
            }
        }
    }
}

/**
 * 推荐问题标签
 */
internal fun ViewContainer<*, *>.suggestionChip(
    ctx: ChatMainPage,
    suggestion: String
) {
    View {
        attr {
            alignSelf(FlexAlign.FLEX_START)
            marginTop(6f)
            padding(left = 10f, top = 6f, right = 10f, bottom = 6f)
            backgroundColor(0xFFE3F2FD)
            borderRadius(16f)
        }
        event {
            click {
                // 点击推荐问题，自动发送
                ctx.inputText = suggestion
                ctx.inputRef.view?.setText(suggestion)
                ctx.sendMessage()
            }
        }
        Text {
            attr {
                text("💬 $suggestion")
                fontSize(12f)
                color(0xFF1976D2)
            }
        }
    }
}

/**
 * 未知类型卡片
 */
internal fun ViewContainer<*, *>.unknownCard(card: Map<String, Any?>) {
    Text {
        attr {
            text("[未知卡片类型: ${card["type"]}]\n")
            fontSize(12f)
            color(0xFF999999)
            marginTop(4f)
        }
    }
}

/**
 * 欢迎提示（无消息时显示）
 */
internal fun ViewContainer<*, *>.welcomeHint() {
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
            padding(left = 32f, top = 16f, right = 32f, bottom = 16f)
        }

        Text {
            attr {
                text(
                    "🤖 AI 智能助手\n\n我可以帮你：" +
                        "\n• 📈 查询股票行情和分析" +
                        "\n• 💡 解读市场动态" +
                        "\n• 🔍 对比个股表现" +
                        "\n• ⚠️ 风险评估和建议" +
                        "\n\n请在下方输入你的问题..."
                )
                fontSize(14f)
                color(0xFF666666)
                textAlignCenter()
                lineHeight(1.8f)
            }
        }
    }
}

/**
 * 底部输入区域
 */
internal fun ViewContainer<*, *>.inputArea(ctx: ChatMainPage) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            padding(left = 12f, top = 8f, right = 12f, bottom = 8f)
            backgroundColor(0xFFFFFFFF)
        }

        // 输入框容器（圆角背景）
        View {
            attr {
                flex(1f)
                height(40f)
                backgroundColor(0xFFF5F5F5)
                borderRadius(20f)
                flexDirectionRow()
                alignItems(FlexAlign.CENTER)
            }
            // 真正的可编辑输入框
            Input {
                ref {
                    ctx.inputRef = it
                }
                attr {
                    flex(1f)
                    height(36f)
                    fontSize(14f)
                    color(Color(0xFF333333))
                    placeholder("输入问题...")
                    placeholderColor(Color(0xFF999999))
                    marginLeft(16f)
                    marginRight(16f)
                }
                event {
                    textDidChange {
                        ctx.inputText = it.text
                    }
                }
            }
        }

        // 发送按钮
        View {
            attr {
                width(60f)
                height(36f)
                backgroundColor(0xFF1976D2)
                borderRadius(18f)
                alignItems(FlexAlign.CENTER)
                justifyContent(FlexJustifyContent.CENTER)
                marginLeft(8f)
            }
            event {
                click { ctx.sendMessage() }
            }
            Text {
                attr {
                    text("发送")
                    fontSize(14f)
                    color(0xFFFFFFFF)
                    fontWeightBold()
                }
            }
        }
    }
}

/**
 * 聊天消息项数据类
 */
data class ChatMessageItem(
    val role: String,
    val content: String,
    val isUser: Boolean,
    val cards: List<Map<String, Any?>>? = null,
    val suggestions: List<String>? = null
)
