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
import com.kuikly.stock.data.LocalDataService
import com.kuikly.stock.data.StockDb
import com.kuikly.stock.data.StockRepository
import com.kuikly.stock.data.DataUpdater
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

    // 状态：消息列表（当前会话，使用 ObservableList + vfor 实现响应式列表刷新）
    internal var messages: ObservableList<ChatMessageItem> by observableList()

    // 状态：输入框文本
    internal var inputText by observable("")

    // 会话列表（多会话，抽屉侧栏展示）
    internal var sessions: ObservableList<ChatSession> by observableList()

    // 当前会话 id
    internal var activeSessionId by observable("")

    // 顶栏标题（当前会话标题，随切换更新）
    internal var activeTitle by observable("AI 智能助手")

    // 抽屉（历史对话侧栏）显隐
    internal var showDrawer by observable(false)

    // 快捷提问文案（动态取数据库第一条股票：请帮我分析XXX）
    internal var quickQuestion by observable("")

    // 状态：当前数据源模式（抽屉内模式切换使用）
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

    // 状态：手动刷新数据（抽屉按钮）是否进行中
    internal var isRefreshing by observable(false)

    // 状态：手动刷新提示（3 秒后自动消失）
    internal var refreshNotice by observable("")

    // 会话操作（删除/置顶/重命名）：目标会话 id 与操作菜单显隐（ActionSheet）
    internal var sessionOpsTargetId by observable("")
    internal var showSessionOps by observable(false)

    // 重命名对话框状态
    internal var showRenameDialog by observable(false)
    internal var renameTargetId by observable("")
    internal var renameInputText by observable("")
    internal var renameInputRef: com.tencent.kuikly.core.views.InputView? = null

    // 状态：软键盘高度（dp，keyboardHeightChange 换算；键盘弹出时页面底部留白，
    // 保证输入框一定在键盘上方可见——Kuikly 页面不依赖 adjustResize）
    internal var keyboardHeight by observable(0f)

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
                    // 键盘弹出时底部留白（keyboardHeight 由 Input.keyboardHeightChange 更新，
                    // attr 读 observable 响应式生效），输入框因此保持在键盘上方
                    paddingBottom(ctx.keyboardHeight)
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

                // 历史对话抽屉（左侧滑出，含会话列表 + 模式切换/开发者）
                vif({ ctx.showDrawer }) {
                    drawer(ctx)
                }

                // AI 服务连接检测结果弹窗（独立弹窗）
                vif({ ctx.showStatusDialog }) {
                    statusDialog(ctx)
                }

                // AI 服务错误提示条（独立悬浮，不挤聊天区，点击或 5 秒后消失）
                vif({ ctx.aiErrorNotice.isNotEmpty() }) {
                    aiErrorToast(ctx)
                }

                // 会话操作菜单（置顶/重命名/删除，ActionSheet）
                ActionSheet {
                    attr {
                        showActionSheet(ctx.showSessionOps)
                        descriptionOfActions("会话操作")
                        actionButtons("取消", "置顶", "重命名", "删除")
                    }
                    event {
                        clickActionButton { index ->
                            ctx.showSessionOps = false
                            when (index) {
                                1 -> ctx.togglePinSession()
                                2 -> ctx.openRenameDialog()
                                3 -> ctx.deleteSession()
                            }
                        }
                    }
                }

                // 重命名会话对话框（Modal 内 Input + 确定/取消）
                vif({ ctx.showRenameDialog }) {
                    renameDialog(ctx)
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
        // 恢复历史会话（多会话：退出 App/清后台也会保留，除非卸载）
        restoreSessions()
        // 加载快捷提问（数据库第一条股票，换库自动更新）
        loadQuickQuestion()
    }

    /**
     * 新建对话：新增一个独立会话页面（不清空、不覆盖已有会话）
     * 旧会话保留在左侧历史对话抽屉中，可随时切回。
     */
    internal fun newChat() {
        val id = createSessionId()
        sessions.add(ChatSession(id = id, title = "新对话", messages = emptyList(), updatedAt = System.currentTimeMillis()))
        switchToSession(id, persist = false)
        aiErrorNotice = "已新建对话，可点击左上角☰查看历史对话"
    }

    /**
     * 切换会话：保存当前会话消息 → 加载目标会话消息
     */
    internal fun switchToSession(id: String, persist: Boolean = true) {
        val target = sessions.firstOrNull { it.id == id } ?: return
        saveActiveMessages()
        activeSessionId = id
        activeTitle = if (target.title.isBlank()) "AI 智能助手" else target.title
        messages.clear()
        target.messages.forEach { messages.add(it) }
        inputText = ""
        inputRef.view?.setText("")
        showDrawer = false
        if (persist) persistAllSessions()
    }

    /** 把当前会话消息写回 sessions（标题取首条用户消息） */
    private fun saveActiveMessages() {
        val idx = sessions.indexOfFirst { it.id == activeSessionId }
        if (idx < 0) return
        val s = sessions[idx]
        val msgs = messages.toList()
        val title = if (s.title.isBlank() || s.title == "新对话") {
            msgs.firstOrNull { it.isUser }?.content?.take(14) ?: "新对话"
        } else s.title
        sessions[idx] = s.copy(messages = msgs, title = title, updatedAt = System.currentTimeMillis())
        activeTitle = if (title.isBlank()) "AI 智能助手" else title
    }

    /** 消息变更后调用：更新当前会话并持久化全部会话 */
    private fun onMessagesChanged() {
        saveActiveMessages()
        persistAllSessions()
    }

    /** 生成会话 id */
    private fun createSessionId(): String = "s" + System.currentTimeMillis()

    /**
     * 快捷提问：把"请帮我分析XXX"填入输入框并直接发送
     */
    internal fun sendQuickQuestion() {
        if (quickQuestion.isEmpty()) return
        inputText = quickQuestion
        inputRef.view?.setText(quickQuestion)
        sendMessage()
    }

    /** 加载快捷提问：动态取数据库第一条股票（换库自动更新） */
    private fun loadQuickQuestion() {
        lifecycleScope.launch {
            var name: String? = null
            try {
                name = StockDb.listStocks(null, null, null, 1, 1).items.firstOrNull()?.name
            } catch (e: Throwable) {
                try { name = LocalDataService.loadStockList().firstOrNull()?.name } catch (e2: Throwable) {}
            }
            delay(0)
            quickQuestion = if (name.isNullOrBlank()) "请帮我分析一只股票" else "请帮我分析$name"
        }
    }

    /** 手动刷新数据：从 Render 拉最新 stock.db 并替换本地库；成功后重载快捷提问（证明 UI 数据也更新） */
    internal fun manualRefresh() {
        if (isRefreshing) return
        isRefreshing = true
        refreshNotice = ""
        lifecycleScope.launch {
            try {
                val updated = DataUpdater.refreshNow()
                delay(0)
                isRefreshing = false
                // 换库后重载 UI 相关数据源（快捷提问取库第一条股票）
                loadQuickQuestion()
                refreshNotice = if (updated) "✅ 数据已刷新到最新" else "已是最近数据"
            } catch (e: Throwable) {
                delay(0)
                isRefreshing = false
                refreshNotice = "刷新失败，请重试"
            }
            lifecycleScope.launch {
                delay(3000)
                refreshNotice = ""
            }
        }
    }

    /** 序列化全部会话到 SharedPreferences（多会话持久化，重启/清后台保留） */
    private fun persistAllSessions() {
        val root = JSONObject()
        val arr = JSONArray()
        for (s in sessions) {
            val so = JSONObject()
            so.put("id", s.id)
            so.put("title", s.title)
            so.put("updatedAt", s.updatedAt)
            so.put("pinned", s.pinned)
            val ma = JSONArray()
            for (m in s.messages) {
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
                    for (sg in sugs) sa.put(sg)
                    obj.put("suggestions", sa)
                }
                ma.put(obj)
            }
            so.put("messages", ma)
            arr.put(so)
        }
        root.put("sessions", arr)
        root.put("activeId", activeSessionId)
        try {
            acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
                .setItem(CHAT_HISTORY_KEY, root.toString())
        } catch (e: Throwable) {
        }
    }

    /** 恢复全部会话（含当前激活会话） */
    private fun restoreSessions() {
        val saved = try {
            acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
                .getItem(CHAT_HISTORY_KEY)
        } catch (e: Throwable) {
            null
        }
        if (saved.isNullOrEmpty()) {
            createDefaultSession()
            return
        }
        try {
            val root = JSONObject(saved)
            val arr = root.optJSONArray("sessions") ?: JSONArray()
            sessions.clear()
            for (i in 0 until arr.length()) {
                val so = arr.optJSONObject(i) ?: continue
                val id = so.optString("id", "")
                val title = so.optString("title", "新对话")
                val updatedAt = so.optLong("updatedAt", 0L)
                val pinned = so.optBoolean("pinned", false)
                val ma = so.optJSONArray("messages") ?: JSONArray()
                val msgs = buildList {
                    for (j in 0 until ma.length()) {
                        val obj = ma.optJSONObject(j) ?: continue
                        val role = obj.optString("role", "assistant")
                        val content = obj.optString("content", "")
                        val isUser = obj.optBoolean("isUser", false)
                        val cards = obj.optJSONArray("cards")?.let { ca ->
                            buildList {
                                for (k in 0 until ca.length()) {
                                    val co = ca.optJSONObject(k) ?: continue
                                    val map = mutableMapOf<String, Any?>()
                                    for (kk in co.keySet()) map[kk] = co.opt(kk)
                                    add(map)
                                }
                            }
                        }
                        val suggestions = obj.optJSONArray("suggestions")?.let { sa ->
                            buildList {
                                for (k in 0 until sa.length()) {
                                    sa.optString(k)?.let { add(it) }
                                }
                            }
                        }
                        if (content.isNotEmpty()) {
                            add(ChatMessageItem(role, content, isUser, cards, suggestions))
                        }
                    }
                }
                if (id.isNotEmpty()) {
                    sessions.add(ChatSession(id, title, msgs, updatedAt, pinned))
                }
            }
            if (sessions.isEmpty()) {
                createDefaultSession()
            } else {
                val activeId = root.optString("activeId", "")
                val target = sessions.firstOrNull { it.id == activeId } ?: sessions.first()
                activeSessionId = target.id
                activeTitle = if (target.title.isBlank()) "AI 智能助手" else target.title
                target.messages.forEach { messages.add(it) }
            }
        } catch (e: Throwable) {
            sessions.clear()
            createDefaultSession()
        }
    }

    /** 无历史时创建默认会话 */
    private fun createDefaultSession() {
        val id = createSessionId()
        sessions.add(ChatSession(id = id, title = "新对话", messages = emptyList(), updatedAt = System.currentTimeMillis()))
        activeSessionId = id
        activeTitle = "AI 智能助手"
        persistAllSessions()
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
        // 面板内部反馈立即更新：只保留一句话，3 秒后自动消失
        val notice = if (online) "✅ 已切换到在线模式" else "✅ 已切换到离线模式"
        modeSwitchNotice = notice
        lifecycleScope.launch {
            delay(3000)
            if (modeSwitchNotice == notice) modeSwitchNotice = ""
        }
        // 顶栏徽标状态 + 持久化放到下一个事件循环，确保触发全局 UI 刷新
        lifecycleScope.launch {
            devModeOnline = online
            acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
                .setItem(DataSourceManager.PREFS_KEY, if (online) "ONLINE" else "OFFLINE")
        }
    }

    // ==================== 会话操作（删除 / 置顶 / 重命名） ====================

    /** 打开会话操作菜单（ActionSheet） */
    internal fun openSessionOps(sessionId: String) {
        sessionOpsTargetId = sessionId
        showSessionOps = true
    }

    /** 置顶/取消置顶目标会话，置顶会话排在最前 */
    internal fun togglePinSession() {
        val idx = sessions.indexOfFirst { it.id == sessionOpsTargetId }
        if (idx < 0) return
        val s = sessions[idx]
        sessions[idx] = s.copy(pinned = !s.pinned)
        reorderSessions()
        persistAllSessions()
        aiErrorNotice = if (s.pinned) "已取消置顶" else "已置顶「${s.title}」"
    }

    /** 删除目标会话；若删除的是当前会话则切换到剩余会话（无会话时新建默认会话） */
    internal fun deleteSession() {
        val idx = sessions.indexOfFirst { it.id == sessionOpsTargetId }
        if (idx < 0) return
        val removed = sessions[idx]
        sessions.removeAt(idx)
        if (activeSessionId == sessionOpsTargetId) {
            if (sessions.isEmpty()) {
                createDefaultSession()
            } else {
                switchToSession(sessions.first().id)
            }
        } else {
            persistAllSessions()
        }
        aiErrorNotice = "已删除会话「${removed.title}」"
    }

    /** 打开重命名对话框（预填当前标题） */
    internal fun openRenameDialog() {
        val target = sessions.firstOrNull { it.id == sessionOpsTargetId } ?: return
        renameTargetId = sessionOpsTargetId
        renameInputText = target.title
        showRenameDialog = true
    }

    /** 保存重命名结果 */
    internal fun renameSession() {
        val title = renameInputText.trim()
        val idx = sessions.indexOfFirst { it.id == renameTargetId }
        if (idx < 0 || title.isEmpty()) {
            showRenameDialog = false
            return
        }
        val s = sessions[idx]
        sessions[idx] = s.copy(title = title)
        if (activeSessionId == renameTargetId) activeTitle = title
        showRenameDialog = false
        persistAllSessions()
        aiErrorNotice = "已重命名为「$title」"
    }

    /** 置顶会话排最前（稳定排序，其余保持原相对顺序） */
    private fun reorderSessions() {
        val sorted = sessions.sortedWith(compareByDescending<ChatSession> { it.pinned })
        if (sorted.map { it.id } != sessions.map { it.id }) {
            sessions.clear()
            sorted.forEach { sessions.add(it) }
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
        onMessagesChanged()

        // 清空输入框（状态 + 原生控件）
        inputText = ""
        inputRef.view?.setText("")
        // 发送后自动收起键盘（保证下次输入时输入框一定在键盘上方可见）
        inputRef.view?.blur()

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
                onMessagesChanged()
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
                onMessagesChanged()
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
        // 关闭抽屉，打开独立结果弹窗，避免内容拥挤
        showDrawer = false
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
 * 顶部导航栏：左侧抽屉按钮 ☰，中间当前会话标题，右上角【大盘行情】
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

        // 左侧：抽屉按钮（展开/收起历史对话侧栏）
        View {
            attr { padding(left = 16f, top = 12f, right = 12f, bottom = 12f) }
            event {
                click { ctx.showDrawer = !ctx.showDrawer }
            }
            Text {
                attr {
                    text("☰")
                    fontSize(22f)
                    color(0xFF333333)
                }
            }
        }

        // 中间：当前会话标题
        View { attr { flex(1f) } }
        Text {
            attr {
                text(ctx.activeTitle)
                fontSize(17f)
                fontWeightBold()
                color(0xFF333333)
            }
        }
        View { attr { flex(1f) } }

        // 右上角：大盘行情入口
        View {
            attr { padding(left = 12f, top = 12f, right = 16f, bottom = 12f) }
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
 * 底部输入区域：
 * 第 1 行：左侧【＋新建对话】（固定）+ 右侧【快捷提问】chip（动态取数据库第一条股票）
 * 第 2 行：输入框 + 发送按钮
 */
internal fun ViewContainer<*, *>.inputArea(ctx: ChatMainPage) {
    View {
        attr {
            flexDirectionColumn()
            backgroundColor(0xFFFFFFFF)
        }

        // 快捷操作行：新建对话 + 快捷提问
        View {
            attr {
                flexDirectionRow()
                alignItems(FlexAlign.CENTER)
                padding(left = 12f, top = 6f, right = 12f, bottom = 2f)
            }

            // 左侧固定：新建对话
            View {
                attr {
                    padding(left = 10f, top = 5f, right = 10f, bottom = 5f)
                    backgroundColor(0xFFE3F2FD)
                    borderRadius(14f)
                }
                event {
                    click { ctx.newChat() }
                }
                Text {
                    attr {
                        text("＋ 新建对话")
                        fontSize(12f)
                        color(0xFF1976D2)
                        fontWeightBold()
                    }
                }
            }

            // 右侧：快捷提问 chip（动态股票名）
            View {
                attr {
                    flex(1f)
                    marginLeft(8f)
                    padding(left = 10f, top = 5f, right = 10f, bottom = 5f)
                    backgroundColor(0xFFF5F5F5)
                    borderRadius(14f)
                }
                event {
                    click { ctx.sendQuickQuestion() }
                }
                Text {
                    attr {
                        text(ctx.quickQuestion)
                        fontSize(12f)
                        color(0xFF666666)
                    }
                }
            }
        }

        // 输入行：输入框 + 发送
        View {
            attr {
                flexDirectionRow()
                alignItems(FlexAlign.CENTER)
                padding(left = 12f, top = 4f, right = 12f, bottom = 8f)
            }

            // 输入框容器（圆角背景）
            View {
                attr {
                    flex(1f)
                    height(64f)
                    backgroundColor(0xFFF5F5F5)
                    borderRadius(20f)
                    flexDirectionRow()
                    alignItems(FlexAlign.CENTER)
                }
                // 真正的可编辑输入框（多行：回车换行，发送走右侧按钮）
                Input {
                    ref {
                        ctx.inputRef = it
                    }
                    attr {
                        flex(1f)
                        height(56f)
                        fontSize(14f)
                        color(Color(0xFF333333))
                        placeholder("输入问题...")
                        placeholderColor(Color(0xFF999999))
                        marginLeft(16f)
                        marginRight(16f)
                        // 多行输入：回车=换行（不再触发发送）
                        lines(3)
                        // 明确可编辑，避免某些 Android 渲染层把输入框设成只读
                        editable(true)
                        // 取消横屏全屏输入，提升模拟器/小屏体验
                        imeNoFullscreen(true)
                    }
                    event {
                        textDidChange {
                            ctx.inputText = it.text
                        }
                        // 键盘高度变化：height 已是 dp（原生层换算），直接作为页面底部 padding 顶起输入框
                        keyboardHeightChange { params ->
                            println("[KB] height=" + params.height + " duration=" + params.duration)
                            ctx.keyboardHeight = params.height
                        }
                        // 多行模式下回车为换行，不在此发送
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
}

/**
 * 历史对话抽屉（左侧滑出侧栏）：
 * - 会话列表：点击切回对应对话（多会话，退出 App 也保留）
 * - 底部：模式切换（在线/离线）+ 检测 AI 服务 + 收起
 * 【开发者/离线模式】模块已从顶栏移入本抽屉
 */
internal fun ViewContainer<*, *>.drawer(ctx: ChatMainPage) {
    View {
        attr {
            absolutePositionAllZero()
            backgroundColor(0x88000000)
        }
        // 遮罩点击收起
        event { click { ctx.showDrawer = false } }

        // 左侧面板
        View {
            attr {
                absolutePosition(left = 0f, top = 0f, bottom = 0f)
                width(ctx.pagerData.pageViewWidth * 0.78f)
                backgroundColor(0xFFFFFFFF)
                flexDirectionColumn()
            }

            // 顶部标题栏
            View {
                attr {
                    flexDirectionRow()
                    alignItems(FlexAlign.CENTER)
                    paddingTop(ctx.pagerData.statusBarHeight)
                    height(56f + ctx.pagerData.statusBarHeight)
                    backgroundColor(0xFF1976D2)
                }
                Text {
                    attr {
                        text("历史对话")
                        fontSize(17f)
                        fontWeightBold()
                        color(0xFFFFFFFF)
                        marginLeft(16f)
                    }
                }
            }

            // 会话列表
            Scroller {
                attr {
                    flex(1f)
                    flexDirectionColumn()
                    scrollEnable(true)
                }
                vfor({ ctx.sessions }) { s ->
                    drawerSessionItem(ctx, s)
                }
                // 空态提示
                vif({ ctx.sessions.isEmpty() }) {
                    View {
                        attr {
                            padding(top = 40f, left = 16f, right = 16f)
                            alignItems(FlexAlign.CENTER)
                        }
                        Text {
                            attr {
                                text("暂无历史对话\n点击下方「＋ 新建对话」开始提问")
                                fontSize(13f)
                                color(0xFF999999)
                                textAlignCenter()
                            }
                        }
                    }
                }
            }

            // 底部：模式切换 + 开发者 + 收起
            View {
                attr {
                    flexDirectionColumn()
                    padding(left = 12f, top = 10f, right = 12f, bottom = 12f)
                    backgroundColor(0xFFF7F8FA)
                }

                Text {
                    attr {
                        text("数据来源（开发者选项）")
                        fontSize(12f)
                        fontWeightBold()
                        color(0xFF333333)
                    }
                }

                devModeOption(ctx, "离线模式", "内置数据 + 本地模板回答（不联网）", online = false)
                devModeOption(ctx, "在线模式", "App 直连 DeepSeek（需联网，真实 AI）", online = true)

                // 模式切换反馈（单行绿字提示，3 秒自动消失；vif 条件直接读 observable）
                vif({ ctx.modeSwitchNotice.isNotEmpty() }) {
                    View {
                        attr {
                            marginTop(6f)
                            padding(left = 10f, top = 6f, right = 10f, bottom = 6f)
                            backgroundColor(0xFFE8F5E9)
                            borderRadius(8f)
                        }
                        Text {
                            attr {
                                text(ctx.modeSwitchNotice)
                                fontSize(11f)
                                color(0xFF2E7D32)
                            }
                        }
                    }
                }

                // 检测 AI 服务连接
                View {
                    attr {
                        marginTop(8f)
                        height(38f)
                        backgroundColor(0xFFE3F2FD)
                        borderRadius(19f)
                        alignItems(FlexAlign.CENTER)
                        justifyContent(FlexJustifyContent.CENTER)
                    }
                    event { click { ctx.runAiStatusCheck() } }
                    Text {
                        attr {
                            text("检测 AI 服务连接")
                            fontSize(13f)
                            color(0xFF1976D2)
                            fontWeightBold()
                        }
                    }
                }

                // 手动刷新数据（从 Render 拉最新 stock.db 并替换，打开大盘行情/详情即加载最新数据重绘）
                View {
                    attr {
                        marginTop(8f)
                        height(38f)
                        backgroundColor(0xFFE8F5E9)
                        borderRadius(19f)
                        alignItems(FlexAlign.CENTER)
                        justifyContent(FlexJustifyContent.CENTER)
                    }
                    event { click { ctx.manualRefresh() } }
                    Text {
                        attr {
                            text("手动刷新数据")
                            fontSize(13f)
                            color(0xFF2E7D32)
                            fontWeightBold()
                        }
                    }
                }

                // 刷新提示（vif 非空才渲染，一次性设置后出现，3 秒自动清除）
                vif({ ctx.refreshNotice.isNotEmpty() }) {
                    View {
                        attr {
                            marginTop(6f)
                            padding(left = 10f, top = 6f, right = 10f, bottom = 6f)
                            backgroundColor(0xFFE8F5E9)
                            borderRadius(8f)
                        }
                        Text {
                            attr {
                                text(ctx.refreshNotice)
                                fontSize(11f)
                                color(0xFF2E7D32)
                            }
                        }
                    }
                }

                // 收起按钮
                View {
                    attr {
                        marginTop(6f)
                        height(38f)
                        backgroundColor(0xFF1976D2)
                        borderRadius(19f)
                        alignItems(FlexAlign.CENTER)
                        justifyContent(FlexJustifyContent.CENTER)
                    }
                    event { click { ctx.showDrawer = false } }
                    Text {
                        attr {
                            text("收起")
                            fontSize(13f)
                            color(0xFFFFFFFF)
                            fontWeightBold()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 抽屉里的单个会话条目（标题 + 消息数 + 置顶标记 + 操作按钮，当前会话高亮）
 */
internal fun ViewContainer<*, *>.drawerSessionItem(ctx: ChatMainPage, s: ChatSession) {
    val active = ctx.activeSessionId == s.id
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            padding(left = 16f, top = 12f, right = 8f, bottom = 12f)
            backgroundColor(if (active) 0xFFE3F2FD else 0xFFFFFFFF)
            marginTop(1f)
        }
        event {
            click { ctx.switchToSession(s.id) }
        }

        // 左侧：标题 + 消息数
        View {
            attr {
                flex(1f)
                flexDirectionColumn()
            }
            Text {
                attr {
                    text(if (s.pinned) "📌 " + s.title else s.title)
                    fontSize(14f)
                    fontWeightBold()
                    color(0xFF333333)
                }
            }
            Text {
                attr {
                    text(if (active) "当前会话 · 共 " + s.messages.size + " 条消息" else "共 " + s.messages.size + " 条消息")
                    fontSize(11f)
                    color(0xFF999999)
                    marginTop(2f)
                }
            }
        }

        // 右侧：操作按钮（⋮）—— 置顶 / 重命名 / 删除
        View {
            attr {
                padding(left = 10f, top = 8f, right = 10f, bottom = 8f)
            }
            event {
                click { ctx.openSessionOps(s.id) }
            }
            Text {
                attr {
                    text("⋮")
                    fontSize(20f)
                    color(0xFF999999)
                }
            }
        }
    }
}

/**
 * 重命名会话对话框（Modal 弹层：Input + 确定/取消）
 */
internal fun ViewContainer<*, *>.renameDialog(ctx: ChatMainPage) {
    View {
        attr {
            absolutePositionAllZero()
            backgroundColor(0x88000000)
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
        }
        // 遮罩点击关闭
        event { click { ctx.showRenameDialog = false } }

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
                    text("重命名会话")
                    fontSize(17f)
                    fontWeightBold()
                    color(0xFF333333)
                }
            }

            // 输入框（叶子组件不支持 padding，用 margin 留白；autofocus 立即弹键盘）
            Input {
                ref {
                    ctx.renameInputRef = it.view
                }
                attr {
                    height(40f)
                    margin(top = 12f)
                    fontSize(14f)
                    color(Color(0xFF333333))
                    editable(true)
                    autofocus(true)
                    text(ctx.renameInputText)
                    backgroundColor(0xFFF5F5F5)
                    borderRadius(8f)
                }
                event {
                    textDidChange(isSyncEdit = true) { params ->
                        ctx.renameInputText = params.text
                    }
                    inputReturn { params ->
                        ctx.renameInputText = params.text
                        ctx.renameSession()
                    }
                }
            }

            View {
                attr {
                    flexDirectionRow()
                    marginTop(16f)
                }

                // 取消
                View {
                    attr {
                        flex(1f)
                        height(40f)
                        backgroundColor(0xFFF5F5F5)
                        borderRadius(20f)
                        alignItems(FlexAlign.CENTER)
                        justifyContent(FlexJustifyContent.CENTER)
                    }
                    event { click { ctx.showRenameDialog = false } }
                    Text {
                        attr {
                            text("取消")
                            fontSize(14f)
                            color(0xFF666666)
                        }
                    }
                }

                // 确定
                View {
                    attr {
                        flex(1f)
                        height(40f)
                        backgroundColor(0xFF1976D2)
                        borderRadius(20f)
                        alignItems(FlexAlign.CENTER)
                        justifyContent(FlexJustifyContent.CENTER)
                        marginLeft(12f)
                    }
                    event { click { ctx.renameSession() } }
                    Text {
                        attr {
                            text("确定")
                            fontSize(14f)
                            fontWeightBold()
                            color(0xFFFFFFFF)
                        }
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
 * 注意：选中态必须用双 vif（条件直接读 observable）渲染——单视图内 Text 的 text() 是
 * 构建期快照，点击 selectMode 后 devModeOnline 变化不会刷新"已选"文字与高亮背景，
 * 用户必须收起再打开抽屉才能确认；双 vif 在条件变化时重建视图，点击后立即反馈。
 */
internal fun ViewContainer<*, *>.devModeOption(
    ctx: ChatMainPage,
    label: String,
    desc: String,
    online: Boolean
) {
    vif({ ctx.devModeOnline == online }) {
        devModeOptionView(ctx, label, desc, online, selected = true)
    }
    vif({ ctx.devModeOnline != online }) {
        devModeOptionView(ctx, label, desc, online, selected = false)
    }
}

internal fun ViewContainer<*, *>.devModeOptionView(
    ctx: ChatMainPage,
    label: String,
    desc: String,
    online: Boolean,
    selected: Boolean
) {
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

/**
 * 会话（多会话历史）：
 * - id：唯一标识（时间戳生成）
 * - title：会话标题（取首条用户消息）
 * - messages：该会话的消息列表
 * - updatedAt：最后更新时间
 * - pinned：是否置顶（置顶会话排在历史列表最前）
 */
data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessageItem>,
    val updatedAt: Long,
    val pinned: Boolean = false
)
