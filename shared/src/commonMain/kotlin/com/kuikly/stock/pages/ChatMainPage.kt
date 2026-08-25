package com.kuikly.stock.pages

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.layout.FlexWrap
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.kuikly.stock.data.DataSourceManager
import com.kuikly.stock.data.StockRepository
import com.kuikly.stock.network.ApiEndpoints
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.coroutines.launch

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

    // 状态：消息列表（使用 ObservableList + vfor 实现响应式列表刷新）
    internal var messages: ObservableList<ChatMessageItem> by observableList()

    // 状态：输入框文本
    internal var inputText by observable("")

    // 状态：开发者面板显隐
    internal var showDevPanel by observable(false)

    // 状态：当前数据源模式（供顶栏徽标与面板单选使用）
    internal var devModeOnline by observable(DataSourceManager.isOnline)

    // 状态：AI 是否正在思考（显示"正在思考…"气泡）
    internal var isThinking by observable(false)

    // 状态：AI 服务连接检测结果（独立弹窗显示）
    // 用 ObservableList<String> + vfor 渲染：普通 observable 更新后弹窗内 Text 不会自动刷新
    // （构建期快照），vfor 绑定集合才会随内容变化实时重建，与聊天消息列表同机制。
    internal var aiStatusLines: ObservableList<String> by observableList()

    // 状态：连接检测结果弹窗显隐
    internal var showStatusDialog by observable(false)

    // 状态：AI 服务错误提示（独立悬浮条，5 秒后自动消失）
    internal var aiErrorNotice by observable("")

    // 状态：模式切换反馈（开发者面板内显示，3 秒后自动消失）
    internal var modeSwitchNotice by observable("")

    // 输入框引用，用于主动清空/聚焦
    lateinit var inputRef: ViewRef<InputView>

    companion object {
        /** 会话记录持久化 key（SharedPreferences） */
        private const val CHAT_HISTORY_KEY = "chat_history_v1"
    }

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
                // 消息列表或空态提示（根据是否有消息自动切换，必须用 Kuikly 的 vif/velse）
                vif({ ctx.messages.isEmpty() }) {
                    welcomeHint()
                }
                velse {
                    messageList(ctx)
                }
                // 底部输入区域
                inputArea(ctx)

                // 开发者选项面板（覆盖层，最后渲染在最上层）
                vif({ ctx.showDevPanel }) {
                    devPanel(ctx)
                }

                // AI 服务连接检测结果弹窗（独立弹窗）
                vif({ ctx.showStatusDialog }) {
                    statusDialog(ctx)
                }

                // AI 服务错误提示条（独立悬浮，不挤聊天区，点击或 5 秒后消失）
                vif({ ctx.aiErrorNotice.isNotEmpty() }) {
                    aiErrorToast(ctx)
                }
            }
        }
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        // 读取持久化的数据源模式（默认离线）
        val saved = acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
            .getItem(DataSourceManager.PREFS_KEY)
        if (saved == "ONLINE") {
            DataSourceManager.setMode(DataSourceManager.Mode.ONLINE)
        }
        devModeOnline = DataSourceManager.isOnline
        // 恢复历史会话（离开页面再回来对话不丢失）
        restoreMessages()
    }

    /**
     * 新建对话：清空当前消息列表 + 清空持久化记录
     */
    internal fun newChat() {
        messages.clear()
        clearPersistedHistory()
        inputText = ""
        inputRef.view?.setText("")
        aiErrorNotice = "已新建对话，开始新的提问吧"
    }

    /** 把当前会话序列化到 SharedPreferences（每次消息变更后调用） */
    private fun persistMessages() {
        val arr = JSONArray()
        for (m in messages) {
            val obj = JSONObject()
            obj.put("role", m.role)
            obj.put("content", m.content)
            obj.put("isUser", m.isUser)
            m.cards?.let { cards ->
                val ca = JSONArray()
                for (c in cards) {
                    val co = JSONObject()
                    for ((k, v) in c) co.put(k, v)
                    ca.put(co)
                }
                obj.put("cards", ca)
            }
            m.suggestions?.let { sugs ->
                val sa = JSONArray()
                for (s in sugs) sa.put(s)
                obj.put("suggestions", sa)
            }
            arr.put(obj)
        }
        try {
            acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
                .setItem(CHAT_HISTORY_KEY, arr.toString())
        } catch (e: Throwable) {
            // 持久化失败不影响当前会话
        }
    }

    /** 从 SharedPreferences 恢复历史会话 */
    private fun restoreMessages() {
        val saved = try {
            acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
                .getItem(CHAT_HISTORY_KEY)
        } catch (e: Throwable) {
            null
        }
        if (saved.isNullOrEmpty()) return
        try {
            val arr = JSONArray(saved)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val role = obj.optString("role", "assistant")
                val content = obj.optString("content", "")
                val isUser = obj.optBoolean("isUser", false)
                val cards = obj.optJSONArray("cards")?.let { ca ->
                    buildList {
                        for (j in 0 until ca.length()) {
                            val co = ca.optJSONObject(j) ?: continue
                            val map = mutableMapOf<String, Any?>()
                            for (k in co.keySet()) map[k] = co.opt(k)
                            add(map)
                        }
                    }
                }
                val suggestions = obj.optJSONArray("suggestions")?.let { sa ->
                    buildList {
                        for (j in 0 until sa.length()) {
                            sa.optString(j)?.let { add(it) }
                        }
                    }
                }
                if (content.isNotEmpty()) {
                    messages.add(ChatMessageItem(role, content, isUser, cards, suggestions))
                }
            }
        } catch (e: Throwable) {
            // 历史数据损坏则清空重来
            messages.clear()
            clearPersistedHistory()
        }
    }

    /** 清空持久化的会话记录 */
    private fun clearPersistedHistory() {
        try {
            acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
                .setItem(CHAT_HISTORY_KEY, "")
        } catch (e: Throwable) {
        }
    }

    /**
     * 切换数据源模式（开发者选项）
     *
     * 注意：开发者面板是 vif 覆盖层，在覆盖层内直接修改 observable 可能不触发
     * 覆盖层外部（顶栏徽标）的刷新。因此把 devModeOnline 更新和持久化放到
     * lifecycleScope.launch 里，在下一个主线程事件循环执行，确保触发全局刷新。
     */
    internal fun selectMode(online: Boolean) {
        // 数据源模式立即更新（StockRepository 会读取它，无需等 UI 刷新）
        DataSourceManager.setMode(
            if (online) DataSourceManager.Mode.ONLINE else DataSourceManager.Mode.OFFLINE
        )
        // 面板内部反馈立即更新（触发覆盖层内部刷新，用户能看到绿色提示）
        modeSwitchNotice = if (online)
            "✅ 已切换到在线模式||请确保手机与电脑同一 WiFi，然后点「检测 AI 服务连接」"
        else
            "✅ 已切换到离线模式||将使用内置模拟数据，不调用真实 AI"
        // 顶栏徽标状态 + 持久化放到下一个事件循环，确保触发全局 UI 刷新
        lifecycleScope.launch {
            devModeOnline = online
            acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
                .setItem(DataSourceManager.PREFS_KEY, if (online) "ONLINE" else "OFFLINE")
        }
    }

    /**
     * 发送消息
     * 按数据源模式走 StockRepository（离线 Mock 或在线后端）
     */
    internal fun sendMessage() {
        val text = inputText.trim()
        if (text.isEmpty()) return

        // 添加用户消息到列表（ObservableList.add 会自动触发 vfor 刷新）
        messages.add(ChatMessageItem(role = "user", content = text, isUser = true))
        persistMessages()

        // 清空输入框（状态 + 原生控件）
        inputText = ""
        inputRef.view?.setText("")

        // 调用 AI 问答（真实 AI 优先，后端不可达自动回退模板）
        isThinking = true
        lifecycleScope.launch {
            try {
                // Ktor 的 suspend 函数本身非阻塞。Kuikly 的 lifecycleScope 协程（EmptyCoroutineContext）
                // 默认在 Kuikly 渲染线程执行；但网络调用挂起后恢复发生在 OkHttp 回调线程，
                // 因此更新 observable 前必须用 Kuikly 的 delay(0)（内部 setTimeout 走 Bridge 原生层）
                // 切回渲染线程，否则响应式状态不刷新/抛 "Assertion!"。
                val reply = StockRepository.chat(text)
                delay(0)
                messages.add(
                    ChatMessageItem(
                        role = "assistant",
                        content = reply.text,
                        isUser = false,
                        cards = reply.cards,
                        suggestions = reply.suggestions
                    )
                )
                persistMessages()
                val notice = reply.errorNotice
                if (notice != null && notice.isNotEmpty()) {
                    aiErrorNotice = notice
                    delay(5000)
                    if (aiErrorNotice == notice) aiErrorNotice = ""
                }
            } catch (e: Throwable) {
                // 任何未捕获的异常（包括协程取消）都要让用户看到，不能静默吞掉。
                // catch 块可能在 OkHttp 回调线程执行，先 delay(0) 切回渲染线程再更新 observable
                delay(0)
                messages.add(
                    ChatMessageItem(
                        role = "assistant",
                        content = "⚠️ AI 助手暂时无法回答：" + (e.message ?: "未知错误") +
                            "\n\n排查：①手机与电脑是否同一 WiFi ②后端是否启动 ③防火墙是否放行 8000",
                        isUser = false
                    )
                )
                persistMessages()
            } finally {
                isThinking = false
            }
        }
    }

    /**
     * 检测 AI 服务连接（开发者面板按钮触发）
     * 结果展示在后端可达性 / LLM 配置 / 数据源信息
     */
    internal fun runAiStatusCheck() {
        // 关闭开发者面板，打开独立结果弹窗，避免内容拥挤
        showDevPanel = false
        aiStatusLines.clear()
        aiStatusLines.add("正在检测…（最多 30 秒）")
        showStatusDialog = true
        lifecycleScope.launch {
            try {
                // Ktor suspend 本身非阻塞；网络恢复在 OkHttp 线程，用 delay(0) 切回渲染线程再更新
                val result = StockRepository.checkAiService()
                delay(0)
                aiStatusLines.clear()
                result.split("\n").forEach { aiStatusLines.add(it) }
            } catch (e: Throwable) {
                // 兜底：任何异常都要显示，不能让弹窗永远停在"正在检测"
                delay(0)  // catch 块可能在 OkHttp 线程执行，先切回渲染线程再更新
                aiStatusLines.clear()
                ("❌ 检测失败\n原因：${e.message ?: "未知错误"}\n排查：①后端是否启动 ②手机与电脑是否同一 WiFi ③防火墙 8000 端口")
                    .split("\n").forEach { aiStatusLines.add(it) }
            }
        }
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
                    text("大盘行情")
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

        // 右侧：当前模式徽标 + 开发者选项入口
        View {
            attr {
                padding(left = 6f, top = 2f, right = 6f, bottom = 2f)
                backgroundColor(if (ctx.devModeOnline) 0xFFE8F5E9 else 0xFFEEEEEE)
                borderRadius(8f)
            }
            Text {
                attr {
                    text(if (ctx.devModeOnline) "在线" else "离线")
                    fontSize(11f)
                    color(if (ctx.devModeOnline) 0xFF43A047 else 0xFF999999)
                }
            }
        }
        // 新建对话：清空当前会话（历史已持久化，随时可回来）
        View {
            attr { padding(left = 10f, top = 12f, right = 6f, bottom = 12f) }
            event { click { ctx.newChat() } }
            Text {
                attr {
                    text("新建对话")
                    fontSize(13f)
                    color(0xFF1976D2)
                    fontWeightBold()
                }
            }
        }
        View {
            attr { padding(left = 12f, top = 12f, right = 16f, bottom = 12f) }
            event { click { ctx.showDevPanel = true } }
            Text {
                attr {
                    text("开发者")
                    fontSize(14f)
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
        attr {
            flex(1f)
            flexDirectionColumn()
            scrollEnable(true)
            padding(left = 10f, top = 6f, right = 10f, bottom = 6f)
        }
        // 使用 vfor 让消息列表响应式增删
        vfor({ ctx.messages }) { message ->
            chatBubble(ctx, message)
        }

        // AI 思考中气泡
        vif({ ctx.isThinking }) {
            thinkingBubble()
        }
    }
}

/**
 * AI 服务错误提示条（顶部悬浮，点击或 5 秒后自动消失）
 * 用于在 AI 不可用时给出简短原因，不占用聊天区空间。
 */
internal fun ViewContainer<*, *>.aiErrorToast(ctx: ChatMainPage) {
    View {
        attr {
            absolutePosition(top = 70f, left = 0f, right = 0f)
            alignItems(FlexAlign.CENTER)
        }
        event { click { ctx.aiErrorNotice = "" } }
        View {
            attr {
                maxWidth(ctx.pagerData.pageViewWidth - 60f)
                backgroundColor(0xE6D32F2F)
                borderRadius(10f)
                padding(left = 14f, top = 8f, right = 14f, bottom = 8f)
            }
            Text {
                attr {
                    text(ctx.aiErrorNotice)
                    fontSize(12f)
                    color(0xFFFFFFFF)
                    textAlignCenter()
                }
            }
        }
    }
}

/**
 * AI 思考中气泡（等待真实 AI 返回时显示）
 */
internal fun ViewContainer<*, *>.thinkingBubble() {
    View {
        attr {
            flexDirectionRow()
            marginTop(8f)
            justifyContent(FlexJustifyContent.FLEX_START)
        }
        View {
            attr {
                backgroundColor(0xFFFFFFFF)
                borderRadius(12f)
                padding(left = 12f, top = 10f, right = 12f, bottom = 10f)
            }
            Text {
                attr {
                    text("AI 正在思考…")
                    fontSize(14f)
                    color(0xFF888888)
                }
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
            // 微信式气泡自适应（关键）：Kuikly 的 Text 无宽度约束时按 100000 测量（EXACTLY），
            // maxWidth 只是上限不参与 flex 计算 → 短文本也会拉满。因此必须显式 width：
            // - 内容短 → width(估算内容宽)，气泡收窄成"内容宽"；
            // - 内容长（或带卡片/按钮）→ width(最大宽)，文字正常换行。
            val maxBubbleW = ctx.pagerData.pageViewWidth - 48f
            val hasExtras = !message.cards.isNullOrEmpty() || !message.suggestions.isNullOrEmpty()
            val estW = estimateTextWidth(message.content)
            val bubbleW = if (hasExtras || estW > maxBubbleW * 0.9f) maxBubbleW else estW + 28f
            attr {
                // 显式纵向布局：内部 markdown 块/卡片/快捷按钮依次竖排
                flexDirectionColumn()
                width(bubbleW)
                backgroundColor(if (message.isUser) 0xFFE3F2FD else 0xFFFFFFFF)
                borderRadius(12f)
                padding(left = 12f, top = 10f, right = 12f, bottom = 10f)
            }

            // 消息文本：用户消息普通展示；AI 消息按 Markdown 排版渲染（标题/加粗/列表/引用）
            if (message.isUser) {
                Text {
                    attr {
                        text(message.content)
                        fontSize(15f)
                        color(0xFF333333)
                        // 不设 lineHeight：单 Text 多行 + lineHeight 在部分设备花屏/重叠（已踩坑）
                    }
                }
            } else {
                renderMarkdown(message.content)
            }

            with(bubble) {
                message.cards?.forEach { card -> renderCard(ctx, card) }
                message.suggestions?.forEach { suggestion -> suggestionChip(ctx, suggestion) }
            }
        }
    }
}

/**
 * 估算文本渲染宽度（像素），用于微信式气泡宽度决策。
 * 全角字符（中文等）≈ fontSize，半角字符（英文/数字）≈ 0.55 * fontSize。
 */
private fun estimateTextWidth(text: String, fontSize: Float = 15f): Float {
    var w = 0f
    for (ch in text) {
        w += if (ch.code > 0xFF) fontSize else fontSize * 0.55f
    }
    return w
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
        "trend_card" -> aiCard(card, "趋势判断")
        "signal_card" -> aiCard(card, "技术信号")
        "risk_card" -> aiCard(card, "风险评估")
        "suggestion_card" -> aiCard(card, "操作建议")
        "summary_card" -> aiCard(card, "AI 总结")
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
    // 兼容后端 snake_case 与本地 mock camelCase
    val changePercent = (card["change_percent"] as? String)
        ?: (card["changePercent"] as? String) ?: "-"

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

        // 卡片内容：按行渲染 + 支持 **加粗**；不用 lineHeight（单 Text 多行 + lineHeight
        // 在 vivo 等设备会花屏/乱码/文字重叠，已踩坑，改为逐行独立 Text 靠 margin 控制行距）
        View {
            attr {
                flexDirectionColumn()
                marginTop(4f)
            }
            content.split("\n").forEach { line ->
                if (line.isNotBlank()) {
                    View {
                        attr { marginTop(2f) }
                        renderInlineBold(line, fontSize = 13f, color = 0xFF555555)
                    }
                }
            }
        }
    }
}

/**
 * 图表卡片（简化版，实际项目中可集成图表库）
 */
internal fun ViewContainer<*, *>.chartCard(card: Map<String, Any?>) {
    val title = card["title"] as? String ?: "图表"
    val chartType = (card["chart_type"] as? String)
        ?: (card["chartType"] as? String) ?: "line"
    val data = card["data"] as? List<*> ?: emptyList<Any?>()

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
                text(title + "（" + chartType + " · " + data.size + " 条数据）")
                fontSize(13f)
                fontWeightBold()
                color(0xFF2E7D32)
            }
        }

        // 图表占位符（实际项目应使用图表库渲染；按行渲染避免多行花屏）
        View {
            attr {
                flexDirectionColumn()
                marginTop(4f)
            }
            "[图表数据加载中...]\n实际项目中应集成 MPAndroidChart 或其他图表库".split("\n").forEach { line ->
                Text {
                    attr {
                        text(line)
                        fontSize(12f)
                        color(0xFF666666)
                        marginTop(2f)
                    }
                }
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
                text(suggestion)
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
 *
 * 注意：Android 模拟器上单个 Text 带 \n 与 lineHeight 容易出现文字重叠，
 * 因此拆成多个独立 Text，用 margin 控制间距。
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
                text("AI 智能助手")
                fontSize(18f)
                fontWeightBold()
                color(0xFF333333)
            }
        }

        Text {
            attr {
                text("我可以帮你：")
                fontSize(14f)
                color(0xFF666666)
                marginTop(16f)
            }
        }

        welcomeFeature("• 查询股票行情和分析")
        welcomeFeature("• 解读市场动态")
        welcomeFeature("• 对比个股表现")
        welcomeFeature("• 风险评估和建议")

        Text {
            attr {
                text("请在下方输入你的问题...")
                fontSize(14f)
                color(0xFF999999)
                marginTop(24f)
            }
        }
    }
}

/**
 * 欢迎页功能条目
 */
internal fun ViewContainer<*, *>.welcomeFeature(text: String) {
    Text {
        attr {
            text(text)
            fontSize(14f)
            color(0xFF666666)
            marginTop(6f)
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
                    // 明确可编辑，避免某些 Android 渲染层把输入框设成只读
                    editable(true)
                    // 取消横屏全屏输入，提升模拟器/小屏体验
                    imeNoFullscreen(true)
                    // 键盘右下角显示「发送」
                    returnKeyTypeSend()
                }
                event {
                    textDidChange {
                        ctx.inputText = it.text
                    }
                    inputReturn {
                        ctx.sendMessage()
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
 * 开发者选项面板（覆盖层）
 */
internal fun ViewContainer<*, *>.devPanel(ctx: ChatMainPage) {
    View {
        attr {
            absolutePositionAllZero()
            backgroundColor(0x99000000)
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
        }
        event { click { ctx.showDevPanel = false } }

        // 面板卡片
        View {
            attr {
                width(ctx.pagerData.pageViewWidth - 64f)
                flexDirectionColumn()
                backgroundColor(0xFFFFFFFF)
                borderRadius(12f)
                padding(left = 20f, top = 20f, right = 20f, bottom = 20f)
            }

            Text {
                attr {
                    text("开发者选项")
                    fontSize(17f)
                    fontWeightBold()
                    color(0xFF333333)
                }
            }

            Text {
                attr {
                    text("选择数据来源（默认离线，免去同网依赖）")
                    fontSize(12f)
                    color(0xFF999999)
                    marginTop(4f)
                }
            }

            devModeOption(ctx, "离线模式", "内置数据 + 局域网真实 AI（需电脑后端在线）", online = false)
            devModeOption(ctx, "在线模式", "后端 MySQL 数据 + 真实 AI（需同一网络）", online = true)

            // 模式切换反馈（点击后显示，明确告知用户切换成功；用 || 分隔多行，逐个 Text 渲染避免 Android 重叠）
            vif({ ctx.modeSwitchNotice.isNotEmpty() }) {
                View {
                    attr {
                        marginTop(8f)
                        padding(left = 10f, top = 8f, right = 10f, bottom = 8f)
                        backgroundColor(0xFFE8F5E9)
                        borderRadius(8f)
                        flexDirectionColumn()
                    }
                    ctx.modeSwitchNotice.split("||").forEach { line ->
                        if (line.isNotBlank()) {
                            Text {
                                attr {
                                    text(line.trim())
                                    fontSize(12f)
                                    color(0xFF2E7D32)
                                    marginTop(2f)
                                }
                            }
                        }
                    }
                }
            }

            vif({ ctx.devModeOnline }) {
                Text {
                    attr {
                        text("后端地址：${ApiEndpoints.BASE_URL}")
                        fontSize(12f)
                        color(0xFF999999)
                        marginTop(8f)
                    }
                }
            }

            // AI 服务连接检测
            View {
                attr {
                    marginTop(12f)
                    height(40f)
                    backgroundColor(0xFFE3F2FD)
                    borderRadius(20f)
                    alignItems(FlexAlign.CENTER)
                    justifyContent(FlexJustifyContent.CENTER)
                }
                event { click { ctx.runAiStatusCheck() } }
                Text {
                    attr {
                        text("检测 AI 服务连接")
                        fontSize(14f)
                        color(0xFF1976D2)
                        fontWeightBold()
                    }
                }
            }

            // 完成按钮
            View {
                attr {
                    marginTop(16f)
                    height(40f)
                    backgroundColor(0xFF1976D2)
                    borderRadius(20f)
                    alignItems(FlexAlign.CENTER)
                    justifyContent(FlexJustifyContent.CENTER)
                }
                event { click { ctx.showDevPanel = false } }
                Text {
                    attr {
                        text("完成")
                        fontSize(14f)
                        color(0xFFFFFFFF)
                        fontWeightBold()
                    }
                }
            }
        }
    }
}

/**
 * AI 服务连接检测结果弹窗（独立弹窗，点击遮罩或关闭按钮消失）
 */
internal fun ViewContainer<*, *>.statusDialog(ctx: ChatMainPage) {
    View {
        attr {
            absolutePositionAllZero()
            backgroundColor(0x99000000)
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
        }
        event { click { ctx.showStatusDialog = false } }

        // 弹窗卡片
        View {
            attr {
                width(ctx.pagerData.pageViewWidth - 64f)
                flexDirectionColumn()
                backgroundColor(0xFFFFFFFF)
                borderRadius(12f)
                padding(left = 20f, top = 20f, right = 20f, bottom = 20f)
            }

            Text {
                attr {
                    text("AI 服务连接检测")
                    fontSize(17f)
                    fontWeightBold()
                    color(0xFF333333)
                }
            }

            // 结果按行渲染：vfor 绑定 ObservableList，检测完成后实时刷新弹窗内容
            // （单 Text 多行 + lineHeight 在部分设备会花屏/重叠，按行渲染规避）
            vfor({ ctx.aiStatusLines }) { line ->
                if (line.isNotBlank()) {
                    Text {
                        attr {
                            text(line)
                            fontSize(13f)
                            color(0xFF444444)
                            marginTop(4f)
                        }
                    }
                }
            }

            // 关闭按钮
            View {
                attr {
                    marginTop(16f)
                    height(40f)
                    backgroundColor(0xFF1976D2)
                    borderRadius(20f)
                    alignItems(FlexAlign.CENTER)
                    justifyContent(FlexJustifyContent.CENTER)
                }
                event { click { ctx.showStatusDialog = false } }
                Text {
                    attr {
                        text("关闭")
                        fontSize(14f)
                        color(0xFFFFFFFF)
                        fontWeightBold()
                    }
                }
            }
        }
    }
}

/**
 * 开发者面板中的单选行
 */
internal fun ViewContainer<*, *>.devModeOption(
    ctx: ChatMainPage,
    label: String,
    desc: String,
    online: Boolean
) {
    val selected = ctx.devModeOnline == online
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            marginTop(12f)
            padding(left = 12f, top = 10f, right = 12f, bottom = 10f)
            backgroundColor(if (selected) 0xFFE3F2FD else 0xFFF5F5F5)
            borderRadius(8f)
        }
        event { click { ctx.selectMode(online) } }

        View {
            attr {
                flex(1f)
                flexDirectionColumn()
            }
            Text {
                attr {
                    text(label)
                    fontSize(14f)
                    fontWeightBold()
                    color(0xFF333333)
                }
            }
            Text {
                attr {
                    text(desc)
                    fontSize(11f)
                    color(0xFF999999)
                    marginTop(2f)
                }
            }
        }

        Text {
            attr {
                text(if (selected) "已选" else "")
                fontSize(12f)
                color(0xFF1976D2)
                marginLeft(8f)
            }
        }
    }
}


// ==================== Markdown 排版渲染（AI 回答专用） ====================

/** 解析后的 Markdown 块 */
internal data class MdBlock(
    val kind: String,      // heading / para / list / quote / code
    val text: String,
    val level: Int = 0
)

/** 简单的 Markdown 解析：标题(#)、列表(-、*、•)、引用(>)、代码块(三个反引号)、段落 */
internal fun parseMarkdown(raw: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = raw.replace("\r\n", "\n").split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trim()
        if (line.isEmpty()) {
            i++
            continue
        }

        // 标题 # ~ ######
        val h = Regex("^(#{1,6})\\s+(.*)$").find(line)
        if (h != null) {
            blocks.add(MdBlock("heading", h.groupValues[2], h.groupValues[1].length))
            i++
            continue
        }

        // 引用 >
        if (line.startsWith(">")) {
            blocks.add(MdBlock("quote", line.removePrefix(">").trim()))
            i++
            continue
        }

        // 列表 - * • · （连续列表合并为一个块；兼容 AI 输出的中文圆点 ·）
        val listItem = Regex("^[-*•·]\\s*(.*)$").find(line)
        if (listItem != null) {
            val items = mutableListOf<String>()
            while (i < lines.size) {
                val l = lines[i].trim()
                val m = Regex("^[-*•·]\\s*(.*)$").find(l)
                if (m != null) {
                    items.add(m.groupValues[1])
                    i++
                } else {
                    break
                }
            }
            blocks.add(MdBlock("list", items.joinToString("\n")))
            continue
        }

        // 代码块
        if (line.startsWith("```")) {
            val buf = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                buf.add(lines[i])
                i++
            }
            i++ // 跳过结束符
            blocks.add(MdBlock("code", buf.joinToString("\n")))
            continue
        }

        blocks.add(MdBlock("para", line))
        i++
    }
    return blocks
}

/** 按块渲染 Markdown（AI 回复的排版核心） */
internal fun ViewContainer<*, *>.renderMarkdown(text: String) {
    View {
        attr { flexDirectionColumn() }
        parseMarkdown(text).forEach { block ->
            when (block.kind) {
                "heading" -> markdownHeading(block.text, block.level)
                "list" -> markdownList(block.text)
                "quote" -> markdownQuote(block.text)
                "code" -> markdownCode(block.text)
                else -> markdownParagraph(block.text)
            }
        }
    }
}

/** 标题 */
internal fun ViewContainer<*, *>.markdownHeading(text: String, level: Int) {
    Text {
        attr {
            // 标题整体已加粗，去掉行内 ** 星号标记，避免原样显示
            text(text.replace("**", ""))
            fontSize(if (level <= 2) 16f else 14f)
            fontWeightBold()
            color(0xFF222222)
            marginTop(12f)
            marginBottom(3f)
        }
    }
}

/** 列表（每项支持 **加粗** 行内标记） */
internal fun ViewContainer<*, *>.markdownList(text: String) {
    View {
        attr {
            flexDirectionColumn()
            marginTop(4f)
        }
        text.split("\n").forEach { item ->
            View {
                attr { marginTop(2f) }
                renderInlineBold("•  " + item, fontSize = 14f, color = 0xFF444444)
            }
        }
    }
}

/** 引用（支持 **加粗** 行内标记） */
internal fun ViewContainer<*, *>.markdownQuote(text: String) {
    View {
        attr { marginTop(6f) }
        renderInlineBold("›  " + text, fontSize = 12f, color = 0xFF888888)
    }
}

/** 代码块（按行渲染，避免单 Text 多行 + lineHeight 花屏） */
internal fun ViewContainer<*, *>.markdownCode(text: String) {
    View {
        attr {
            flexDirectionColumn()
            marginTop(6f)
        }
        text.split("\n").forEach { line ->
            Text {
                attr {
                    text(line)
                    fontSize(12f)
                    color(0xFF6A1B9A)
                    marginTop(2f)
                }
            }
        }
    }
}

/**
 * 渲染一行文本，支持 **加粗** 行内标记。
 * 含加粗时用 RichText + Span（原生富文本，自动换行且局部加粗），
 * 避免 Row+flexWrap 按片段换行导致的"词语被拆断/文字截断"问题。
 * 发起者（用户）与回答者（AI）共用此函数，但由调用方传入各自独立的
 * fontSize / color 样式，保证两类消息的排版各自统一。
 */
internal fun ViewContainer<*, *>.renderInlineBold(
    text: String,
    fontSize: Float,
    color: Long
) {
    if (text.contains("**")) {
        RichText {
            attr {
                fontSize(fontSize)
                color(color)
            }
            val parts = text.split("**")
            parts.forEachIndexed { index, part ->
                if (part.isNotEmpty()) {
                    Span {
                        text(part)
                        if (index % 2 == 1) fontWeightBold()
                    }
                }
            }
        }
    } else {
        Text {
            attr {
                text(text)
                fontSize(fontSize)
                color(color)
            }
        }
    }
}

/** 段落（支持 **加粗** 行内样式）
 * 按行渲染：单个 Text 多行 + lineHeight 在 Android 真机/模拟器容易文字重叠/花屏（已踩坑），
 * 每个逻辑行拆成独立 Text，靠 margin 控制行距，避免乱码/重叠。
 */
internal fun ViewContainer<*, *>.markdownParagraph(text: String) {
    View {
        attr {
            flexDirectionColumn()
            marginTop(8f)
        }
        text.split("\n").forEach { line ->
            if (line.isBlank()) return@forEach
            View {
                attr { marginTop(2f) }
                renderInlineBold(line, fontSize = 15f, color = 0xFF333333)
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
