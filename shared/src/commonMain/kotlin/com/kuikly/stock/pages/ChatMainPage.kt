// AI 聊天主页：会话管理、消息列表、卡片渲染与手动刷新数据都在这里。

package com.kuikly.stock.pages

import com.kuikly.stock.base.BasePager
import com.kuikly.stock.ui.component.topToast
import com.kuikly.stock.ui.component.Overlay
import com.kuikly.stock.ui.component.overlayEnterExit
import com.kuikly.stock.data.StockColors

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.attr.AccessibilityRole
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.vbind
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
import com.tencent.kuiklybase.KuiklyMarkdown
import com.tencent.kuiklybase.KuiklyStreamingMarkdown
import com.tencent.kuiklybase.streaming.MarkdownBlock
import com.tencent.kuiklybase.streaming.MarkdownStreamingState
import com.kuikly.stock.util.splitBreaks
import com.kuikly.stock.data.copyTextToClipboard
import com.kuikly.stock.data.exportTimestampString
import com.kuikly.stock.data.saveTextToDownloads
import com.kuikly.stock.data.shareText
import com.kuikly.stock.data.DataSourceManager
import com.kuikly.stock.data.LocalDataService
import com.kuikly.stock.data.StockDb
import com.kuikly.stock.data.StockRepository
import com.kuikly.stock.data.DataUpdater
import com.kuikly.stock.data.ConclusionAlertFactory
import com.kuikly.stock.data.WatchStore
import com.kuikly.stock.ai.chat.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import com.kuikly.stock.ai.config.AiRuntimeConfig
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.coroutines.launch
import com.kuikly.stock.data.fmt2
import com.kuikly.stock.data.nowMillis
import com.kuikly.stock.ui.component.AppRoutes
import com.kuikly.stock.ui.component.DrawerSide
import com.kuikly.stock.ui.component.appBottomNav
import com.kuikly.stock.ui.component.drawerState
import com.kuikly.stock.ui.component.sideDrawer
import com.kuikly.stock.ui.component.entryCard
import com.kuikly.stock.ui.component.PressState
import com.kuikly.stock.ui.component.pressFeedback
import com.kuikly.stock.ui.component.pressedBg
import com.kuikly.stock.ui.component.pressedScale
import com.kuikly.stock.util.relativeTimeLabel
import com.kuikly.stock.ui.theme.AppColor
import com.kuikly.stock.ui.theme.AppFont
import com.kuikly.stock.ui.theme.AppRadius
import com.kuikly.stock.ui.theme.AppSize

@Page("chat_main")
class ChatMainPage : BasePager() {

    internal var messages: ObservableList<ChatMessageItem> by observableList()

    internal var inputText by observable("")

    internal var sessions: ObservableList<ChatSession> by observableList()

    internal var activeSessionId by observable("")

    internal var activeTitle by observable("AI 智能助手")

    /**
     * 会话抽屉。用 [drawerState] 建而不是 `Overlay(this)`：
     * 抽屉是滑动退场，比弹窗淡出慢，卸载时机要跟着变（见 Drawer.kt）。
     */
    internal val drawerOverlay = drawerState(this)

    internal var quickQuestion by observable("")

    /** 按压态容器。抽屉里的按钮、空状态推荐问题都要它（见 Interaction.kt）。 */
    internal val press = PressState(this)

    /**
     * 空状态推荐问题。
     *
     * 从本地行情取一只股票生成第一条，其余是通用问题。
     * 比只列「我能做什么」更容易上手——用户不用先想清楚怎么问。
     */
    internal val recommendedQuestions: ObservableList<String> by observableList()

    internal var isThinking by observable(false)
    internal var streamingText by observable("")
    internal var streamBlocks: ObservableList<MarkdownBlock> by observableList()
    internal val streamState = MarkdownStreamingState()
    internal var requestStage by observable("")
    private var requestVersion = 0
    private var requestJob: Job? = null
    private var pendingQuestion = ""

    override fun pageWillDestroy() {
        requestVersion++
        requestJob?.cancel()
        super.pageWillDestroy()
    }

    internal fun stopResponse() {
        if (!isThinking) return
        requestVersion++
        requestJob?.cancel()
        requestJob = null
        messages.add(ChatMessageItem("assistant", streamingText.ifBlank { "已停止生成" } + "\n\n（回答未完成）", false, failed = true, retryQuestion = pendingQuestion))
        streamingText = ""
        isThinking = false
        onMessagesChanged()
    }

    internal fun retryMessage(message: ChatMessageItem) {
        if (isThinking || message.retryQuestion.isBlank()) return
        if (messages.lastOrNull() == message) {
            messages.removeAt(messages.lastIndex)
            if (messages.lastOrNull()?.let { it.isUser && it.content == message.retryQuestion } == true) messages.removeAt(messages.lastIndex)
        }
        quoteText = ""
        inputText = message.retryQuestion
        sendMessage()
    }


    internal var aiErrorNotice by observable("")

    internal var sessionOpsTargetId by observable("")
    internal var showSessionOps by observable(false)

    internal val renameOverlay = Overlay(this)
    internal var renameTargetId by observable("")
    internal var renameInputText by observable("")
    internal var renameInputRef: com.tencent.kuikly.core.views.InputView? = null

    internal val exportOverlay = Overlay(this)
    internal var exportTargetId by observable("")

    internal var keyboardHeight by observable(0f)

    internal var msgScrollerRef: ViewRef<ScrollerView<*, *>>? = null

    private val chatScrollCoordinator = ChatScrollCoordinator()

    internal var showMsgActions by observable(false)
    internal var msgActionIndex by observable(-1)
    internal var msgActionContent by observable("")
    internal var quoteText by observable("")

    internal var activeProviderLabel by observable("API 未配置")
    internal var expandedEvidenceKey by observable("")
    internal val alertOverlay = Overlay(this)
    internal var pendingAlertCode by observable("")
    internal var pendingAlertName by observable("")
    internal var pendingAlertType by observable(0)
    internal var pendingAlertValue by observable(0.0)

    lateinit var inputRef: ViewRef<InputView>

    companion object {
        private const val CHAT_HISTORY_KEY = "chat_history_v1"
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    flexDirectionColumn()
                    backgroundColor(AppColor.SURFACE_SOFT)
                    paddingBottom(ctx.keyboardHeight)
                }
                topBar(ctx)
                vif({ ctx.messages.isEmpty() }) {
                    welcomeHint(ctx)
                }
                velse {
                    messageList(ctx)
                }
                inputArea(ctx)
                vif({ ctx.keyboardHeight <= 0f }) {
                    appBottomNav(ctx, AppRoutes.CHAT)
                }

                vif({ ctx.drawerOverlay.isVisible }) {
                    drawer(ctx)
                }

                vif({ ctx.aiErrorNotice.isNotEmpty() }) {
                    aiErrorToast(ctx)
                }

                ActionSheet {
                    attr {
                        showActionSheet(ctx.showSessionOps)
                        descriptionOfActions("会话操作")
                        actionButtons("取消", "置顶", "重命名", "导出", "删除")
                    }
                    event {
                        clickActionButton { index ->
                            ctx.showSessionOps = false
                            when (index) {
                                1 -> ctx.togglePinSession()
                                2 -> ctx.openRenameDialog()
                                3 -> ctx.openExportDialog()
                                4 -> ctx.deleteSession()
                            }
                        }
                    }
                }

                vif({ ctx.renameOverlay.isVisible }) {
                    renameDialog(ctx)
                }

                vif({ ctx.showMsgActions }) {
                    msgActionSheet(ctx)
                }

                vif({ ctx.alertOverlay.isVisible }) {
                    alertConfirmDialog(ctx)
                }

                vif({ ctx.exportOverlay.isVisible }) {
                    exportDialog(ctx)
                }
            }
        }
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        val saved = acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
            .getItem(DataSourceManager.PREFS_KEY)
        if (saved == "ONLINE") {
            DataSourceManager.setMode(DataSourceManager.Mode.ONLINE)
        }
        activeProviderLabel = runCatching {
            val p = AiRuntimeConfig.activeProfile()
            if (AiRuntimeConfig.isConfigured()) "${p.name} · ${p.model}" else "${p.name} · 待配置"
        }.getOrDefault("API 未配置")
        restoreSessions()
        val detailQuestion = pagerData.params.optString("detail_question", "")
        if (detailQuestion.isNotBlank()) {
            newChat()
            inputText = detailQuestion
            aiErrorNotice = "已带入选中行情，可编辑后发送"
        }
        loadQuickQuestion()
    }

    internal fun newChat() {
        stopResponse()
        val id = createSessionId()
        sessions.add(ChatSession(id = id, title = "新对话", messages = emptyList(), updatedAt = nowMillis()))
        switchToSession(id, persist = false)
        aiErrorNotice = "已新建对话，可点击右上角查看历史对话"
    }

    internal fun switchToSession(id: String, persist: Boolean = true) {
        stopResponse()
        val target = sessions.firstOrNull { it.id == id } ?: return
        saveActiveMessages()
        activeSessionId = id
        activeTitle = if (target.title.isBlank()) "AI 智能助手" else target.title
        messages.clear()
        target.messages.forEach { messages.add(it) }
        inputText = ""
        inputRef.view?.setText("")
        drawerOverlay.hide()
        if (persist) persistAllSessions()
    }

    private fun saveActiveMessages() {
        val idx = sessions.indexOfFirst { it.id == activeSessionId }
        if (idx < 0) return
        val s = sessions[idx]
        val msgs = messages.toList()
        val title = if (s.title.isBlank() || s.title == "新对话") {
            msgs.firstOrNull { it.isUser }?.content?.take(14) ?: "新对话"
        } else s.title
        sessions[idx] = s.copy(messages = msgs, title = title, updatedAt = nowMillis())
        activeTitle = if (title.isBlank()) "AI 智能助手" else title
    }

    private fun onMessagesChanged() {
        saveActiveMessages()
        persistAllSessions()
    }

    private fun createSessionId(): String = "s" + nowMillis()

    internal fun sendQuickQuestion() {
        if (quickQuestion.isEmpty()) return
        inputText = quickQuestion
        inputRef.view?.setText(quickQuestion)
        sendMessage()
    }

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
            // 推荐问题跟着行情一起刷新：第一条锚定用户当前能看到的一只股票
            val qs = mutableListOf<String>()
            if (!name.isNullOrBlank()) qs.add("请帮我分析$name 近期走势")
            qs.add("今天大盘整体怎么样")
            qs.add("对比一下我自选里的股票表现")
            qs.add("帮我看看当前持仓有什么风险")
            recommendedQuestions.clear()
            recommendedQuestions.addAll(qs)
        }
    }

    /** 点推荐问题时只填入输入框、不直接发送：用户多半想先改两个字再问。 */
    internal fun fillQuestion(question: String) {
        inputText = question
        inputRef.view?.setText(question)
    }

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
                obj.put("failed", m.failed)
                obj.put("retryQuestion", m.retryQuestion)
                m.cards?.let { cards ->
                    val ca = JSONArray(nativeToJson(cards).toString())
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
                                    @Suppress("UNCHECKED_CAST")
                                    val map = Json.parseToJsonElement(co.toString()).toNativeValue() as? Map<String, Any?>
                                    if (map != null) add(map)
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
                            add(ChatMessageItem(role, content, isUser, cards, suggestions, obj.optBoolean("failed", false), obj.optString("retryQuestion", "")))
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

    private fun createDefaultSession() {
        messages.clear()
        inputText = ""
        quoteText = ""
        val id = createSessionId()
        sessions.add(ChatSession(id = id, title = "新对话", messages = emptyList(), updatedAt = nowMillis()))
        activeSessionId = id
        activeTitle = "AI 智能助手"
        persistAllSessions()
    }

    internal fun openSessionOps(sessionId: String) {
        sessionOpsTargetId = sessionId
        showSessionOps = true
    }

    internal fun togglePinSession() {
        val idx = sessions.indexOfFirst { it.id == sessionOpsTargetId }
        if (idx < 0) return
        val s = sessions[idx]
        sessions[idx] = s.copy(pinned = !s.pinned)
        reorderSessions()
        persistAllSessions()
        aiErrorNotice = if (s.pinned) "已取消置顶" else "已置顶「${s.title}」"
    }

    internal fun deleteSession() {
        if (activeSessionId == sessionOpsTargetId) stopResponse()
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

    internal fun openRenameDialog() {
        val target = sessions.firstOrNull { it.id == sessionOpsTargetId } ?: return
        renameTargetId = sessionOpsTargetId
        renameInputText = target.title
        renameOverlay.show()
    }

    internal fun openExportDialog() {
        if (sessions.none { it.id == sessionOpsTargetId }) return
        exportTargetId = sessionOpsTargetId
        exportOverlay.show()
    }

    private fun exportTarget(): ChatSession? {
        val id = if (exportTargetId.isNotEmpty()) exportTargetId else sessionOpsTargetId
        val s = sessions.firstOrNull { it.id == id } ?: return null
        // 导出当前会话时带上内存中最新的消息（含正在进行的回复）。
        return if (id == activeSessionId) s.copy(messages = messages.toList()) else s
    }

    /**
     * 执行导出：0 分享 Markdown，1 保存 .md 到下载文件夹，2 复制 Markdown 全文，3 分享 JSON（备份）。
     */
    internal fun performExport(kind: Int) {
        val target = exportTarget()
        exportOverlay.hide()
        if (target == null || target.messages.isEmpty()) {
            toastExport("该会话暂无消息，无需导出")
            return
        }
        val exportedAt = exportTimestampString(nowMillis())
        val title = target.title.ifBlank { "AI 问答" }
        when (kind) {
            3 -> {
                val json = exportSessionJson(target, exportedAt)
                if (!shareText(title, json)) {
                    copyTextToClipboard(json)
                    toastExport("系统分享不可用，已复制 JSON 全文")
                }
            }
            1 -> {
                val md = exportSessionMarkdown(target, exportedAt)
                val saved = saveTextToDownloads(exportFileBase(target.title) + ".md", md, "text/markdown")
                toastExport(if (saved != null) "已保存到下载文件夹：$saved" else "保存失败，请改用分享或复制")
            }
            2 -> {
                copyTextToClipboard(exportSessionMarkdown(target, exportedAt))
                toastExport("已复制全文（Markdown）")
            }
            else -> {
                val md = exportSessionMarkdown(target, exportedAt)
                if (!shareText(title, md)) {
                    copyTextToClipboard(md)
                    toastExport("系统分享不可用，已复制全文")
                }
            }
        }
    }

    private fun toastExport(msg: String) {
        aiErrorNotice = msg
        lifecycleScope.launch {
            delay(2500)
            if (aiErrorNotice == msg) aiErrorNotice = ""
        }
    }

    internal fun renameSession() {
        val title = renameInputText.trim()
        val idx = sessions.indexOfFirst { it.id == renameTargetId }
        if (idx < 0 || title.isEmpty()) {
            renameOverlay.hide()
            return
        }
        val s = sessions[idx]
        sessions[idx] = s.copy(title = title)
        if (activeSessionId == renameTargetId) activeTitle = title
        renameOverlay.hide()
        persistAllSessions()
        aiErrorNotice = "已重命名为「$title」"
    }

    private fun reorderSessions() {
        val sorted = sessions.sortedWith(compareByDescending<ChatSession> { it.pinned })
        if (sorted.map { it.id } != sessions.map { it.id }) {
            sessions.clear()
            sorted.forEach { sessions.add(it) }
        }
    }

    /**
     * 空状态「最近对话」的数据源：置顶优先，其次按更新时间；还没问过话的空会话不算历史。
     */
    internal fun recentSessions(limit: Int = RECENT_PREVIEW_MAX): List<ChatSession> =
        sessions.asSequence()
            .filter { it.messages.isNotEmpty() }
            .sortedWith(compareByDescending<ChatSession> { it.pinned }.thenByDescending { it.updatedAt })
            .take(limit)
            .toList()

    /** 上面那份列表的内容指纹，给 `vbind` 判断要不要重建（只比 id 会漏掉重命名）。 */
    internal fun recentSessionsKey(): String =
        recentSessions().joinToString("|") { it.id + "#" + it.title + "#" + it.updatedAt }

    internal fun openMsgActions(index: Int, content: String) {
        msgActionIndex = index
        msgActionContent = content
        showMsgActions = true
    }

    internal fun copyMsg() {
        val content = msgActionContent
        showMsgActions = false
        if (content.isNotEmpty()) {
            copyTextToClipboard(content)
            aiErrorNotice = "已复制到剪贴板"
            lifecycleScope.launch {
                delay(2000)
                if (aiErrorNotice == "已复制到剪贴板") aiErrorNotice = ""
            }
        }
    }

    internal fun deleteMsg() {
        val idx = msgActionIndex
        showMsgActions = false
        if (idx in 0 until messages.size) {
            messages.removeAt(idx)
            onMessagesChanged()
        }
    }

    internal fun quoteMsg() {
        val content = msgActionContent
        showMsgActions = false
        if (content.isNotEmpty()) {
            quoteText = content
        }
    }

    internal fun clearQuote() {
        quoteText = ""
    }

    internal fun requestScrollToLatestOnce() {
        chatScrollCoordinator.requestScrollAfterSend()
    }

    internal fun onMessageContentSizeChanged(contentHeight: Float) {
        val scroller = msgScrollerRef?.view ?: return
        val bottomOffset = chatScrollCoordinator.consumeBottomOffset(
            contentHeight = contentHeight,
            viewportHeight = scroller.frame.height
        ) ?: return

        scroller.setContentOffset(
            offsetX = 0f,
            offsetY = bottomOffset,
            animated = false
        )
    }

    internal fun sendMessage() {
        if (isThinking) return
        val typed = inputText.trim()
        if (typed.isEmpty()) return
        if (typed.length + quoteText.length > 6000) {
            aiErrorNotice = "问题过长，请缩短到 6000 字以内"
            return
        }
        val finalText = if (quoteText.isNotBlank()) quoteText.split("\n").joinToString("\n") { "> $it" } + "\n\n" + typed else typed
        val history = boundedHistory(messages.filter { !it.failed }.map { message ->
            val references = message.cards.orEmpty().mapNotNull { card ->
                (card["code"] as? String)?.let { code -> "${card["name"] ?: "股票"}($code)" }
            }.distinct().joinToString(" ")
            message.role to (message.content + if (references.isBlank()) "" else "\n涉及股票：$references")
        })
        requestScrollToLatestOnce()
        messages.add(ChatMessageItem("user", finalText, true))
        onMessagesChanged()
        quoteText = ""
        inputText = ""
        inputRef.view?.setText("")
        inputRef.view?.blur()
        pendingQuestion = finalText
        isThinking = true
        streamingText = ""
        streamState.reset()
        streamBlocks.clear()
        aiErrorNotice = ""
        requestStage = "正在准备会话上下文…"
        val version = ++requestVersion
        val session = activeSessionId
        val job = Job()
        requestJob = job
        lifecycleScope.launch(context = job) {
            try {
                val reply = pageResult {
                    StockRepository.chat(finalText, history,
                        onText = { text ->
                            pageResult { Unit }
                            if (version == requestVersion && session == activeSessionId) {
                                streamingText = text
                                syncStreamBlocks(text)
                            }
                        },
                        onStage = { stage ->
                            pageResult { Unit }
                            if (version == requestVersion && session == activeSessionId) requestStage = stage
                        },
                    )
                }
                if (version != requestVersion || session != activeSessionId) return@launch
                messages.add(ChatMessageItem("assistant", reply.text, false, reply.cards, reply.suggestions,
                    failed = reply.failed, retryQuestion = if (reply.errorNotice != null) finalText else ""))
                aiErrorNotice = reply.errorNotice.orEmpty()
                onMessagesChanged()
            } catch (e: Throwable) {
                pageResult { Unit }
                if (version != requestVersion || session != activeSessionId) return@launch
                val reason = if (e is CancellationException) "生成已停止" else e.message ?: "请求失败，请重试"
                messages.add(ChatMessageItem("assistant", reason, false, failed = true, retryQuestion = finalText))
                onMessagesChanged()
            } finally {
                if (version == requestVersion) {
                    streamingText = ""
                    streamBlocks.clear()
                    requestStage = ""
                    isThinking = false
                    requestJob = null
                    job.complete()
                }
            }
        }
    }

    /**
     * 流式 Markdown 块增量同步（手动 diff：core 2.7.0 没有 diffUpdate）。
     * 常见情况只有追加新块或末块变化，走增量分支避免全量重建。
     */
    internal fun syncStreamBlocks(text: String) {
        val newBlocks = streamState.update(text) ?: return
        if (newBlocks.isEmpty()) {
            if (streamBlocks.isNotEmpty()) streamBlocks.clear()
            return
        }
        val current = streamBlocks.toList()
        if (current.size == newBlocks.size && current.indices.all { current[it].id == newBlocks[it].id }) return
        if (newBlocks.size >= current.size && current.indices.all { current[it].id == newBlocks[it].id }) {
            newBlocks.drop(current.size).forEach { streamBlocks.add(it) }
            return
        }
        if (newBlocks.size == current.size && newBlocks.size > 1 &&
            (0 until newBlocks.size - 1).all { newBlocks[it].id == current[it].id }
        ) {
            streamBlocks[streamBlocks.size - 1] = newBlocks.last()
            return
        }
        streamBlocks.clear()
        newBlocks.forEach { streamBlocks.add(it) }
    }

    internal fun toggleEvidence(key: String) {
        expandedEvidenceKey = if (expandedEvidenceKey == key) "" else key
        // vfor 列表项不追踪页面级 observable：原位替换消息项，触发列表重渲染
        val idx = messages.indexOfFirst { m ->
            m.cards?.any { c ->
                c["type"] == "conclusion_card" &&
                    ((c["code"] as? String ?: "") + "_" + (c["one_liner"] as? String ?: "")).take(80) == key
            } == true
        }
        if (idx >= 0) messages[idx] = messages[idx].copy()
    }

    internal fun prepareAlert(code: String, name: String, type: Int, value: Double) {
        if (code.isBlank() || !value.isFinite() || value <= 0.0) {
            aiErrorNotice = "当前结论没有可用的精确价位"
            return
        }
        pendingAlertCode = code
        pendingAlertName = name
        pendingAlertType = type
        pendingAlertValue = value
        alertOverlay.show()
    }

    internal fun confirmAlert() {
        val rule = if (pendingAlertType == 1) {
            ConclusionAlertFactory.support(pendingAlertCode, pendingAlertName, pendingAlertValue)
        } else {
            ConclusionAlertFactory.resistance(pendingAlertCode, pendingAlertName, pendingAlertValue)
        }
        if (!WatchStore.upsertAlert(rule)) {
            aiErrorNotice = "提醒保存失败，请重试"
            return
        }
        alertOverlay.hide()
        aiErrorNotice = "提醒已创建 · ${pendingAlertName} ${if (pendingAlertType == 1) "跌至" else "涨至"} ${fmtCardNumber(pendingAlertValue)}"
    }
}

internal fun ViewContainer<*, *>.topBar(ctx: ChatMainPage) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            height(60f + ctx.pagerData.statusBarHeight)
            backgroundColor(AppColor.SURFACE)
            paddingTop(ctx.pagerData.statusBarHeight)
        }

        View {
            attr { size(44f, 44f); allCenter(); marginLeft(4f); accessibility("返回"); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(true, false) }
            event {
                click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() }
            }
            Text {
                attr {
                    text("‹")
                    fontSize(32f)
                    color(AppColor.TEXT_DEEP)
                }
            }
        }

        View {
            attr { flex(1f); marginLeft(2f) }
            Text {
                attr {
                    text("AI 研究室")
                    fontSize(18f)
                    fontWeightBold()
                    color(AppColor.TITLE)
                }
            }
            Text {
                attr {
                    text(ctx.activeProviderLabel)
                    fontSize(10f)
                    color(AppColor.PRIMARY)
                    marginTop(1f)
                }
            }
        }

        View {
            attr {
                minWidth(52f)
                height(44f)
                allCenter()
                marginRight(6f)
                accessibility("打开历史对话")
                accessibilityRole(AccessibilityRole.BUTTON)
                accessibilityInfo(true, false)
            }
            event { click { ctx.drawerOverlay.toggle() } }
            Text {
                attr {
                    text("历史")
                    fontSize(12f)
                    color(AppColor.PRIMARY)
                    fontWeightBold()
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.messageList(ctx: ChatMainPage) {
    Scroller {
        ref { ctx.msgScrollerRef = it }
        attr {
            flex(1f)
            flexDirectionColumn()
            scrollEnable(true)
            padding(left = 10f, top = 6f, right = 10f, bottom = 6f)
        }
        event {
            contentSizeChanged { _, contentHeight ->
                ctx.onMessageContentSizeChanged(contentHeight)
            }
        }
        vfor({ ctx.messages }) { message ->
            chatBubble(ctx, message)
        }

        vif({ ctx.isThinking }) {
            thinkingBubble(ctx)
        }
    }
}

internal fun ViewContainer<*, *>.aiErrorToast(ctx: ChatMainPage) {
    topToast(
        ctx = ctx,
        text = { ctx.aiErrorNotice },
        onDismiss = { ctx.aiErrorNotice = "" },
        tint = AppColor.DANGER,
    )
}

internal fun ViewContainer<*, *>.thinkingBubble(ctx: ChatMainPage) {
    View {
        attr { flexDirectionColumn(); marginTop(8f); padding(12f); borderRadius(12f); backgroundColor(AppColor.SURFACE) }
        Text { attr { text(ctx.requestStage); fontSize(12f); color(AppColor.TEXT_SUB_DEEP) } }
        vfor({ ctx.streamBlocks }) { block ->
            KuiklyStreamingMarkdown(state = ctx.streamState, block = block, config = chatMarkdownConfig)
        }
        View {
            attr { height(44f); allCenter(); accessibility("停止生成"); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(true, false) }
            event { click { ctx.stopResponse() } }
            Text { attr { text("停止生成"); fontSize(12f); color(AppColor.PRIMARY) } }
        }
    }
}

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

        View {
            val bubble = this
            val maxBubbleW = ctx.pagerData.pageViewWidth - 48f
            val hasExtras = !message.cards.isNullOrEmpty() || !message.suggestions.isNullOrEmpty()
            val estW = estimateTextWidth(message.content)
            val bubbleW = if (hasExtras || estW > maxBubbleW * 0.9f) maxBubbleW else estW + 28f
            attr {
                flexDirectionColumn()
                width(bubbleW)
                backgroundColor(if (message.isUser) AppColor.PRIMARY_BG else AppColor.SURFACE)
                borderRadius(12f)
                padding(left = 12f, top = 10f, right = 12f, bottom = 10f)
            }
            event {
                longPress {
                    val idx = ctx.messages.indexOf(message)
                    if (idx >= 0) {
                        ctx.openMsgActions(idx, message.content)
                    }
                }
            }

            if (message.isUser) {
                Text {
                    attr {
                        text(message.content)
                        fontSize(15f)
                        color(AppColor.TEXT_INK)
                    }
                }
            } else {
                KuiklyMarkdown(content = sanitizeMarkdownForRender(message.content), config = chatMarkdownConfig)
            }

            with(bubble) {
                if (message.retryQuestion.isNotBlank()) {
                    View {
                        attr { height(44f); allCenter(); accessibility("重试回答"); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(true, false) }
                        event { click { ctx.retryMessage(message) } }
                        Text { attr { text("重试回答"); fontSize(13f); color(AppColor.PRIMARY) } }
                    }
                }
                message.cards?.forEach { card -> renderCard(ctx, card) }
                message.suggestions?.forEach { suggestion -> suggestionChip(ctx, suggestion) }
            }
        }
    }
}

private fun estimateTextWidth(text: String, fontSize: Float = 15f): Float {
    var w = 0f
    for (ch in text) {
        w += if (ch.code > 0xFF) fontSize else fontSize * 0.55f
    }
    return w
}

internal fun ViewContainer<*, *>.renderCard(
    ctx: ChatMainPage,
    card: Map<String, Any?>
) {
    val type = card["type"] as? String ?: "unknown"
    when (type) {
        "conclusion_card" -> conclusionCard(ctx, card)
        "compare_card" -> compareCard(ctx, card)
        "stock_card" -> stockCard(ctx, card)
        "index_card" -> indexCard(ctx, card)
        "chart_card" -> chartCard(card)
        "trend_card" -> aiCard(card, "趋势判断")
        "signal_card" -> aiCard(card, "技术信号")
        "risk_card" -> aiCard(card, "风险评估")
        "suggestion_card" -> aiCard(card, "操作建议")
        "summary_card" -> aiCard(card, "AI 总结")
        else -> unknownCard(card)
    }
}

internal fun ViewContainer<*, *>.compareCard(
    ctx: ChatMainPage,
    card: Map<String, Any?>
) {
    val title = (card["title"] as? String) ?: "多股对比"
    val headers = ((card["headers"] as? String) ?: "").split("|").map { it.trim() }.filter { it.isNotEmpty() }
    val rawRows = when (val r = card["rows"]) {
        is String -> splitBreaks(r)
        is List<*> -> r.map { it?.toString() ?: "" }
        else -> emptyList()
    }.map { it.split("|").map { c -> c.trim() } }
    val colCount = listOf(headers.size, rawRows.maxOfOrNull { it.size } ?: 0).max()
    if (colCount <= 0) return

    View {
        attr {
            flexDirectionColumn()
            marginTop(8f)
            backgroundColor(AppColor.SURFACE)
            borderRadius(12f)
            padding(left = 12f, top = 12f, right = 12f, bottom = 10f)
        }
        Text {
            attr {
                text(title)
                fontSize(14f)
                fontWeightBold()
                color(AppColor.TEXT_STRONG)
            }
        }
        rawRows.forEach { cells -> compareStockBlock(headers, cells) }
        Text {
            attr {
                text("本地实时数据 · 仅供参考")
                fontSize(10f)
                color(AppColor.TEXT_MUTED)
                marginTop(6f)
            }
        }
    }
}

internal fun ViewContainer<*, *>.compareStockBlock(headers: List<String>, cells: List<String>) {
    val name = cells.getOrNull(0).orEmpty()
    val change = cells.getOrNull(2).orEmpty()
    View {
        attr { marginTop(9f); padding(11f); borderRadius(10f); backgroundColor(AppColor.SURFACE_TINT) }
        View { attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
            Text { attr { text(name); fontSize(14f); fontWeightBold(); color(AppColor.TEXT_DEEP); flex(1f) } }
            Text { attr { text(change); fontSize(14f); fontWeightBold(); color(if (change.startsWith("+")) AppColor.UP_ALT else if (change.startsWith("-")) AppColor.DOWN_ALT else AppColor.TEXT_SUB_DEEP) } }
        }
        View { attr { flexDirectionRow(); marginTop(9f) }
            compareMetric(headers.getOrNull(1) ?: "现价", cells.getOrNull(1) ?: "-")
            View { attr { width(7f) } }
            compareMetric(headers.getOrNull(3) ?: "MA5", cells.getOrNull(3) ?: "-")
        }
        View { attr { flexDirectionRow(); marginTop(7f) }
            compareMetric(headers.getOrNull(4) ?: "RSI6", cells.getOrNull(4) ?: "-")
            View { attr { width(7f) } }
            compareMetric("关键位", cells.getOrNull(5) ?: "-")
        }
    }
}

internal fun ViewContainer<*, *>.compareMetric(label: String, value: String) {
    View {
        attr { flex(1f); padding(8f); borderRadius(8f); backgroundColor(AppColor.SURFACE) }
        Text { attr { text(label); fontSize(10f); color(AppColor.TEXT_SUB) } }
        Text { attr { text(value); fontSize(14f); fontWeightBold(); color(AppColor.TEXT_STRONG); marginTop(3f) } }
    }
}

internal fun ViewContainer<*, *>.compareTableLine(cells: List<String>, colCount: Int, isHeader: Boolean) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            padding(left = 6f, top = 6f, right = 6f, bottom = 6f)
        }
        for (ci in 0 until colCount) {
            val cell = if (ci < cells.size) cells[ci] else ""
            val weight = if (ci == 0) 1.5f else 1f
            val tint = when {
                isHeader -> AppColor.PRIMARY_SOFT
                cell.startsWith("+") -> StockColors.UP
                cell.startsWith("-") -> StockColors.DOWN
                else -> AppColor.TEXT_INK
            }
            View {
                attr { flex(weight) }
                Text {
                    attr {
                        text(cell)
                        fontSize(if (isHeader) 11f else 13f)
                        fontWeightBold()
                        color(tint)
                        if (ci > 0) textAlignRight()
                    }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.conclusionCard(
    ctx: ChatMainPage,
    card: Map<String, Any?>
) {
    val name = card["name"] as? String ?: ""
    val code = card["code"] as? String ?: ""
    val changePercent = (card["change_percent"] as? String)
        ?: (card["changePercent"] as? String) ?: ""
    val bias = card["bias"] as? String ?: ""
    val biasNote = card["bias_note"] as? String ?: ""
    val oneLiner = card["one_liner"] as? String ?: ""
    val resistance = card["resistance"] as? String ?: ""
    val support = card["support"] as? String ?: ""
    val resistanceValue = cardNumber(card["resistance_value"])
    val supportValue = cardNumber(card["support_value"])
    val dataDate = card["data_date"]?.toString().orEmpty()
    val indicatorDate = card["indicator_date"]?.toString().orEmpty()
    val signals = when (val s = card["signals"]) {
        is String -> splitBreaks(s)
        is List<*> -> s.map { it?.toString() ?: "" }.flatMap { splitBreaks(it) }
        else -> emptyList()
    }
    val action = card["action"] as? String ?: ""
    val footnote = card["footnote"] as? String ?: "仅供参考，不构成投资建议"
    val evidenceKey = (code + "_" + oneLiner).take(80)
    val evidenceExpanded = ctx.expandedEvidenceKey == evidenceKey

    val isUp = changePercent.contains("+")
    val upColor = AppColor.UP_ALT
    val downColor = AppColor.DOWN_ALT

    View {
        attr {
            flexDirectionColumn()
            marginTop(8f)
            backgroundColor(AppColor.SURFACE)
            borderRadius(14f)
            border(Border(1f, BorderStyle.SOLID, Color(AppColor.SURFACE_SOFT)))
            padding(left = 14f, top = 13f, right = 14f, bottom = 13f)
        }
        event {
            click {
                if (code.isNotEmpty()) {
                    val params = JSONObject()
                    params.put("code", code)
                    ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                        .openPage("stock_detail", params)
                }
            }
        }

        Text {
            attr {
                text(if (code.isNotEmpty()) "$name · $code" else name)
                fontSize(16f)
                fontWeightBold()
                color(AppColor.TEXT_STRONG)
            }
        }

        View {
            attr {
                flexDirectionRow()
                flexWrapWrap()
                marginTop(8f)
            }
            if (bias.isNotEmpty() || changePercent.isNotEmpty()) {
                conclusionTag(
                    text = (bias + " " + changePercent).trim(),
                    textColor = if (isUp) upColor else downColor,
                    bgColor = if (isUp) AppColor.DANGER_BG else AppColor.SUCCESS_BG
                )
            }
            if (biasNote.isNotEmpty()) {
                conclusionTag(text = biasNote, textColor = AppColor.WARNING_TEXT, bgColor = AppColor.WARNING_BG)
            }
        }

        if (oneLiner.isNotEmpty()) {
            View {
                attr { marginTop(10f) }
                renderInlineBold("**一句话：**$oneLiner", fontSize = 14f, color = AppColor.TEXT_DEEP)
            }
        }

        if (resistance.isNotEmpty() || support.isNotEmpty()) {
            conclusionDivider()
            Text {
                attr {
                    text("关键价位")
                    fontSize(14f)
                    fontWeightBold()
                    color(AppColor.TEXT_STRONG)
                    marginTop(2f)
                }
            }
            View {
                attr {
                    flexDirectionRow()
                    marginTop(7f)
                }
                metricBox("压力位", resistance.ifEmpty { "-" })
                View { attr { width(7f) } }
                metricBox("MA5 支撑", support.ifEmpty { "-" })
            }
            if (resistanceValue != null || supportValue != null) {
                View { attr { flexDirectionRow(); marginTop(8f) } }
                if (resistanceValue != null) {
                    conclusionAction("设压力位提醒") { ctx.prepareAlert(code, name, 0, resistanceValue) }
                }
                if (resistanceValue != null && supportValue != null) View { attr { width(7f) } }
                if (supportValue != null) {
                    conclusionAction("设支撑位提醒") { ctx.prepareAlert(code, name, 1, supportValue) }
                }
            }
        }

        if (signals.isNotEmpty()) {
            View {
                attr { minHeight(44f); flexDirectionRow(); alignItems(FlexAlign.CENTER); marginTop(8f); borderRadius(9f); backgroundColor(AppColor.BG); accessibility(if (evidenceExpanded) "收起技术依据" else "展开技术依据"); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(true, false) }
                event { click { ctx.toggleEvidence(evidenceKey) } }
                Text { attr { text(if (evidenceExpanded) "收起技术依据" else "展开技术依据（${signals.size}）"); fontSize(12f); fontWeightBold(); color(AppColor.PRIMARY); flex(1f) } }
                Text { attr { text(if (evidenceExpanded) "⌃" else "⌄"); fontSize(17f); color(AppColor.PRIMARY) } }
            }
            if (evidenceExpanded) signals.forEach { sig -> signalBullet(sig) }
        }

        if (action.isNotEmpty()) {
            View {
                attr {
                    flexDirectionColumn()
                    marginTop(11f)
                    backgroundColor(AppColor.PRIMARY_BG_LIGHT)
                    borderRadius(8f)
                    padding(left = 11f, top = 9f, right = 11f, bottom = 9f)
                }
                Text {
                    attr {
                        text("观察动作")
                        fontSize(13f)
                        fontWeightBold()
                        color(AppColor.TEXT_STRONG)
                    }
                }
                Text {
                    attr {
                        text(action)
                        fontSize(13f)
                        color(AppColor.TEXT_SUB_DEEP)
                        marginTop(3f)
                        lineHeight(19f)
                    }
                }
            }
        }

        Text {
            attr {
                text(buildString {
                    append(footnote)
                    if (dataDate.isNotBlank()) append(" · 行情 ").append(dataDate)
                    if (indicatorDate.isNotBlank() && indicatorDate != dataDate) append(" · 指标 ").append(indicatorDate)
                })
                fontSize(10f)
                color(AppColor.TEXT_MUTED)
                marginTop(11f)
            }
        }
    }
}

private fun cardNumber(value: Any?): Double? = when (value) {
    is Number -> value.toDouble().takeIf { it.isFinite() && it > 0.0 }
    else -> value?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
}

private fun fmtCardNumber(value: Double): String = fmt2(value)

internal fun ViewContainer<*, *>.conclusionAction(label: String, action: () -> Unit) {
    View {
        attr { flex(1f); minHeight(44f); allCenter(); borderRadius(9f); backgroundColor(AppColor.INFO_BG); accessibility(label); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(true, false) }
        event { click { action() } }
        Text { attr { text(label); fontSize(11f); fontWeightBold(); color(AppColor.PRIMARY) } }
    }
}

internal fun ViewContainer<*, *>.alertConfirmDialog(ctx: ChatMainPage) {
    View {
        attr { absolutePositionAllZero(); backgroundColor(AppColor.SCRIM); allCenter() }
        View {
            attr {
                overlayEnterExit(ctx.alertOverlay)
                width(ctx.pagerData.pageViewWidth - 46f)
                padding(18f)
                borderRadius(18f)
                backgroundColor(AppColor.SURFACE)
            }
            Text { attr { text("确认创建价格提醒"); fontSize(18f); fontWeightBold(); color(AppColor.TEXT_STRONG) } }
            Text { attr { text("${ctx.pendingAlertName} · ${ctx.pendingAlertCode}"); fontSize(13f); color(AppColor.TEXT_SUB_DEEP); marginTop(9f) } }
            View { attr { padding(14f); marginTop(12f); borderRadius(12f); backgroundColor(AppColor.BG) }
                Text { attr { text(if (ctx.pendingAlertType == 1) "价格跌至或低于" else "价格涨至或高于"); fontSize(11f); color(AppColor.TEXT_SUB_DEEP) } }
                Text { attr { text("¥ ${fmtCardNumber(ctx.pendingAlertValue)}"); fontSize(23f); fontWeightBold(); color(AppColor.TEXT_STRONG); marginTop(4f) } }
            }
            Text { attr { text("提醒在行情数据刷新时检查，可能存在延迟。"); fontSize(11f); color(AppColor.TEXT_SUB); marginTop(10f) } }
            View { attr { flexDirectionRow(); marginTop(16f) }
                View { attr { flex(1f); height(44f); allCenter(); borderRadius(12f); backgroundColor(AppColor.BG_SOFT); accessibility("取消创建提醒"); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(true, false) }; event { click { ctx.alertOverlay.hide() } }; Text { attr { text("取消"); fontSize(13f); color(AppColor.TEXT_SUB_DEEP) } } }
                View { attr { width(10f) } }
                View { attr { flex(1f); height(44f); allCenter(); borderRadius(12f); backgroundColor(AppColor.PRIMARY); accessibility("确认创建价格提醒"); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(true, false) }; event { click { ctx.confirmAlert() } }; Text { attr { text("确认创建"); fontSize(13f); fontWeightBold(); color(Color.WHITE) } } }
            }
        }
    }
}

internal fun ViewContainer<*, *>.conclusionTag(text: String, textColor: Long, bgColor: Long) {
    View {
        attr {
            marginRight(6f)
            marginBottom(4f)
            backgroundColor(bgColor)
            borderRadius(20f)
            padding(left = 8f, top = 3f, right = 8f, bottom = 3f)
        }
        Text {
            attr {
                text(text)
                fontSize(11f)
                fontWeightBold()
                color(textColor)
            }
        }
    }
}

internal fun ViewContainer<*, *>.metricBox(label: String, value: String) {
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
            backgroundColor(AppColor.SURFACE_TINT)
            borderRadius(9f)
            padding(left = 9f, top = 7f, right = 9f, bottom = 7f)
        }
        Text {
            attr {
                text(label)
                fontSize(10f)
                color(AppColor.TEXT_SUB_DEEP)
            }
        }
        Text {
            attr {
                text(value)
                fontSize(14f)
                fontWeightBold()
                color(AppColor.TEXT_STRONG)
                marginTop(3f)
            }
        }
    }
}

internal fun ViewContainer<*, *>.signalBullet(text: String) {
    View {
        attr {
            flexDirectionRow()
            marginTop(6f)
        }
        Text {
            attr {
                text("●")
                fontSize(9f)
                color(AppColor.PRIMARY_SOFT)
                marginRight(6f)
                marginTop(3f)
            }
        }
        View {
            attr {
                flex(1f)
                flexDirectionColumn()
            }
            splitBreaks(text).forEach { line ->
                View {
                    attr { marginTop(1f) }
                    renderInlineBold(line, fontSize = 13f, color = AppColor.TEXT_DEEP)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.conclusionDivider() {
    View {
        attr {
            height(1f)
            backgroundColor(AppColor.SURFACE_SOFT)
            marginTop(11f)
            marginBottom(9f)
        }
    }
}

internal fun ViewContainer<*, *>.stockCard(
    ctx: ChatMainPage,
    card: Map<String, Any?>
) {
    val code = card["code"] as? String ?: ""
    val name = card["name"] as? String ?: ""
    val price = (card["price"] as? Number)?.let { fmtCardNumber(it.toDouble()) } ?: card["price"] as? String ?: "-"
    val changePercent = (card["change_percent"] as? String)
        ?: (card["changePercent"] as? String) ?: "-"

    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            marginTop(8f)
            padding(left = 12f, top = 10f, right = 12f, bottom = 10f)
            backgroundColor(AppColor.PRIMARY_BG_LIGHT)
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

        View {
            attr { flex(1f) }
            Text {
                attr {
                    text("$name ($code)")
                    fontSize(14f)
                    fontWeightBold()
                    color(AppColor.PRIMARY_SOFT)
                }
            }
        }

        Text {
            attr {
                text(price)
                fontSize(14f)
                fontWeightBold()
                color(if (changePercent.contains("+")) StockColors.UP else StockColors.DOWN)
                marginLeft(8f)
            }
        }
        Text {
            attr {
                text(changePercent)
                fontSize(12f)
                color(if (changePercent.contains("+")) StockColors.UP else StockColors.DOWN)
                marginLeft(4f)
            }
        }
    }
}

internal fun ViewContainer<*, *>.indexCard(
    ctx: ChatMainPage,
    card: Map<String, Any?>
) {
    val code = card["code"] as? String ?: ""
    val name = card["name"] as? String ?: ""
    val price = (card["price"] as? Number)?.let { fmtCardNumber(it.toDouble()) } ?: card["price"] as? String ?: "-"
    val changePercent = (card["change_percent"] as? String)
        ?: (card["changePercent"] as? String) ?: "-"

    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            marginTop(8f)
            padding(left = 12f, top = 10f, right = 12f, bottom = 10f)
            backgroundColor(AppColor.SUCCESS_BG)
            borderRadius(8f)
        }
        event {
            click {
                val params = JSONObject()
                params.put("code", code)
                ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                    .openPage("index_detail", params)
            }
        }

        View {
            attr {
                padding(left = 6f, top = 2f, right = 6f, bottom = 2f)
                backgroundColor(AppColor.SUCCESS)
                borderRadius(4f)
                marginRight(8f)
            }
            Text {
                attr {
                    text("指数")
                    fontSize(11f)
                    fontWeightBold()
                    color(AppColor.ON_DARK)
                }
            }
        }

        View {
            attr { flex(1f) }
            Text {
                attr {
                    text("$name ($code)")
                    fontSize(14f)
                    fontWeightBold()
                    color(AppColor.SUCCESS)
                }
            }
        }

        Text {
            attr {
                text(price)
                fontSize(14f)
                fontWeightBold()
                color(if (changePercent.contains("+")) StockColors.UP else StockColors.DOWN)
                marginLeft(8f)
            }
        }
        Text {
            attr {
                text(changePercent)
                fontSize(12f)
                color(if (changePercent.contains("+")) StockColors.UP else StockColors.DOWN)
                marginLeft(4f)
            }
        }
    }
}

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
            backgroundColor(AppColor.WARNING_BG)
            borderRadius(8f)
        }

        Text {
            attr {
                text(title)
                fontSize(13f)
                fontWeightBold()
                color(AppColor.TEXT_INK)
            }
        }

        View {
            attr {
                flexDirectionColumn()
                marginTop(4f)
            }
            content.split("\n").forEach { line ->
                if (line.isNotBlank()) {
                    View {
                        attr { marginTop(2f) }
                        renderInlineBold(line, fontSize = 13f, color = AppColor.TEXT_GRAY)
                    }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.suggestionChip(
    ctx: ChatMainPage,
    suggestion: String
) {
    View {
        attr {
            alignSelf(FlexAlign.FLEX_START)
            marginTop(6f)
            padding(left = 10f, top = 6f, right = 10f, bottom = 6f)
            backgroundColor(AppColor.PRIMARY_BG)
            borderRadius(16f)
        }
        event {
            click {
                ctx.inputText = suggestion
                ctx.inputRef.view?.setText(suggestion)
                ctx.sendMessage()
            }
        }
        Text {
            attr {
                text(suggestion)
                fontSize(12f)
                color(AppColor.PRIMARY_SOFT)
            }
        }
    }
}

internal fun ViewContainer<*, *>.unknownCard(card: Map<String, Any?>) {
    Text {
        attr {
            text("[未知卡片类型: ${card["type"]}]\n")
            fontSize(12f)
            color(AppColor.TEXT_HINT)
            marginTop(4f)
        }
    }
}

/**
 * 空状态：标题 + 推荐问题。
 *
 * 原先只有「AI 智能助手」+ 4 条能力清单，等于让用户自己把能力翻译成一个能问出口的问题。
 * 现在直接把可点的问题摆出来，点一下填进输入框。
 */
internal fun ViewContainer<*, *>.welcomeHint(ctx: ChatMainPage) {
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
            padding(left = 24f, top = 16f, right = 24f, bottom = 16f)
        }

        Text {
            attr {
                text("AI 研究室")
                fontSize(20f)
                fontWeightBold()
                color(AppColor.TEXT_STRONG)
            }
        }

        Text {
            attr {
                text("带本地行情上下文提问，回答会引用你自选和行情库里的真实数据")
                fontSize(AppFont.NOTE)
                color(AppColor.TEXT_SUB)
                marginTop(6f)
                textAlignCenter()
            }
        }

        View {
            attr {
                flexDirectionColumn()
                marginTop(20f)
                width(ctx.pagerData.pageViewWidth - 48f)
            }
            welcomeSectionLabel("可以这样问")
            vfor({ ctx.recommendedQuestions }) { q ->
                welcomeSuggestion(ctx, q)
            }
        }

        // 最近对话：没有历史整块不出现。派生列表用 vbind 按内容指纹重建——
        // vfor 只吃 ObservableList，而这里是「排序 + 截断」的结果，没有对应的 observable。
        vbind({ ctx.recentSessionsKey() }) {
            val recent = ctx.recentSessions()
            if (recent.isNotEmpty()) {
                View {
                    attr {
                        flexDirectionColumn()
                        marginTop(18f)
                        width(ctx.pagerData.pageViewWidth - 48f)
                    }
                    welcomeSectionLabel("最近对话")
                    recent.forEach { welcomeRecentItem(ctx, it) }
                }
            }
        }
    }
}

/** 空状态里的分组小标题（「可以这样问」「最近对话」）。 */
private fun ViewContainer<*, *>.welcomeSectionLabel(title: String) {
    Text {
        attr {
            text(title)
            fontSize(AppFont.NOTE)
            fontWeightBold()
            color(AppColor.TEXT_SUB)
            marginBottom(2f)
        }
    }
}

/** 空状态里的一条推荐问题。点了只填输入框，见 [ChatMainPage.fillQuestion]。 */
internal fun ViewContainer<*, *>.welcomeSuggestion(ctx: ChatMainPage, question: String) {
    entryCard(
        press = ctx.press,
        tag = "wq:$question",
        title = question,
        accessibilityLabel = "推荐问题：$question",
        onClick = { ctx.fillQuestion(question) },
    )
}

/** 空状态「最近对话」里的一条：标题 + 「多久之前 · 几条消息」，点了直接进那条会话。 */
internal fun ViewContainer<*, *>.welcomeRecentItem(ctx: ChatMainPage, s: ChatSession) {
    val title = s.title.ifBlank { "新对话" }
    val meta = relativeTimeLabel(s.updatedAt, nowMillis()) + " · " + s.messages.size + " 条消息"
    entryCard(
        press = ctx.press,
        tag = "recent:" + s.id,
        title = title,
        meta = meta,
        titleColor = AppColor.TEXT_INK,
        accessibilityLabel = "最近对话：$title，$meta",
        onClick = { ctx.switchToSession(s.id) },
    )
}


internal fun ViewContainer<*, *>.inputArea(ctx: ChatMainPage) {
    View {
        attr {
            flexDirectionColumn()
            backgroundColor(AppColor.SURFACE)
        }

        vif({ ctx.quoteText.isNotEmpty() }) {
            quoteBar(ctx)
        }

        // 快捷提问只在空状态下出现：有对话之后用户已经在自己的上下文里，
        // 再顶一条示例问题既占地方又容易误触。「新建对话」已收进抽屉。
        vif({ ctx.messages.isEmpty() && ctx.quickQuestion.isNotEmpty() }) {
            View {
                attr { padding(left = 12f, top = 6f, right = 12f, bottom = 2f) }
                View {
                    attr {
                        padding(left = 11f, top = 6f, right = 11f, bottom = 6f)
                        backgroundColor(AppColor.PRIMARY_BG)
                        borderRadius(14f)
                    }
                    event { click { ctx.sendQuickQuestion() } }
                    Text {
                        attr {
                            text(ctx.quickQuestion)
                            fontSize(AppFont.NOTE)
                            color(AppColor.PRIMARY_SOFT)
                        }
                    }
                }
            }
        }


        View {
            attr {
                flexDirectionRow()
                alignItems(FlexAlign.CENTER)
                padding(left = 12f, top = 4f, right = 12f, bottom = 8f)
            }

            View {
                attr {
                    flex(1f)
                    height(50f)
                    backgroundColor(AppColor.SURFACE_SOFT)
                    borderRadius(20f)
                    flexDirectionRow()
                    alignItems(FlexAlign.CENTER)
                }
                Input {
                    ref {
                        ctx.inputRef = it
                    }
                    attr {
                        flex(1f)
                        height(46f)
                        fontSize(14f)
                        color(Color(AppColor.TEXT_INK))
                        placeholder("输入问题...")
                        placeholderColor(Color(AppColor.TEXT_HINT))
                        marginLeft(16f)
                        marginRight(16f)
                        lines(2)
                        editable(true)
                        imeNoFullscreen(true)
                    }
                    event {
                        textDidChange {
                            ctx.inputText = it.text
                        }
                        keyboardHeightChange { params ->
                            ctx.keyboardHeight = params.height
                        }
                    }
                }
            }

            View {
                attr {
                    width(64f)
                    height(44f)
                    backgroundColor(AppColor.PRIMARY)
                    borderRadius(22f)
                    alignItems(FlexAlign.CENTER)
                    justifyContent(FlexJustifyContent.CENTER)
                    marginLeft(8f)
                    accessibility("发送问题")
                    accessibilityRole(AccessibilityRole.BUTTON)
                    accessibilityInfo(true, false)
                }
                event {
                    click { ctx.sendMessage() }
                }
                Text {
                    attr {
                        text("发送")
                        fontSize(14f)
                        color(AppColor.ON_DARK)
                        fontWeightBold()
                    }
                }
            }
        }
    }
}

/**
 * 会话抽屉：从左侧滑入的历史对话列表。
 *
 * 用 [sideDrawer] 而不是手写 Overlay：抽屉是「从屏幕外滑进来的一块面板」，
 * 用缩放表达会让人以为它是浮在当前页上方的弹窗，和「点右上角拉开历史」对不上。
 *
 * 这里是**单纯的历史入口**——数据源 / 在线模式 / 连接检测已迁到「我的」页。
 * 那些是低频设置，用户想找时会去设置页；挤在抽屉里会把会话列表压到屏幕下半截。
 */
internal fun ViewContainer<*, *>.drawer(ctx: ChatMainPage) {
    sideDrawer(
        drawer = ctx.drawerOverlay,
        pageWidth = ctx.pagerData.pageViewWidth,
        side = DrawerSide.LEFT,
        onScrimTap = { ctx.drawerOverlay.hide() },
    ) {
        View {
            attr {
                flexDirectionRow()
                alignItems(FlexAlign.CENTER)
                paddingTop(ctx.pagerData.statusBarHeight)
                height(56f + ctx.pagerData.statusBarHeight)
                backgroundColor(AppColor.PRIMARY_SOFT)
            }
            Text {
                attr {
                    text("历史对话")
                    fontSize(AppFont.TITLE)
                    fontWeightBold()
                    color(AppColor.ON_DARK)
                    marginLeft(16f)
                }
            }
        }

        // 「新建对话」从输入栏搬到这里：它属于「会话管理」，和历史列表是一类动作
        View {
            attr { padding(left = 12f, top = 10f, right = 12f, bottom = 2f) }
            View {
                attr {
                    height(AppSize.TOUCH_MIN)
                    backgroundColor(AppColor.PRIMARY)
                    borderRadius(AppSize.TOUCH_MIN / 2f)
                    alignItems(FlexAlign.CENTER)
                    justifyContent(FlexJustifyContent.CENTER)
                    pressedScale(ctx.press, "drawer_new", normal = 1f, pressed = 0.97f)
                    accessibility("新建对话")
                    accessibilityRole(AccessibilityRole.BUTTON)
                    accessibilityInfo(true, false)
                }
                event {
                    pressFeedback(ctx.press, "drawer_new")
                    click {
                        ctx.press.releaseAll()
                        ctx.newChat()
                    }
                }
                Text {
                    attr {
                        text("＋ 新建对话")
                        fontSize(AppFont.LABEL)
                        color(AppColor.ON_DARK)
                        fontWeightBold()
                    }
                }
            }
        }

        Scroller {
            attr {
                flex(1f)
                flexDirectionColumn()
                scrollEnable(true)
            }
            vfor({ ctx.sessions }) { s ->
                drawerSessionItem(ctx, s)
            }
            vif({ ctx.sessions.isEmpty() }) {
                View {
                    attr {
                        padding(top = 40f, left = 16f, right = 16f)
                        alignItems(FlexAlign.CENTER)
                    }
                    Text {
                        attr {
                            text("暂无历史对话\n点上方「＋ 新建对话」开始提问")
                            fontSize(AppFont.BODY)
                            color(AppColor.TEXT_MUTED)
                            textAlignCenter()
                        }
                    }
                }
            }
        }
    }
}


internal fun ViewContainer<*, *>.drawerSessionItem(ctx: ChatMainPage, s: ChatSession) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            padding(left = 16f, top = 12f, right = 8f, bottom = 12f)
            backgroundColor(if (ctx.activeSessionId == s.id) AppColor.PRIMARY_BG else AppColor.SURFACE)
            marginTop(1f)
        }
        event {
            click { ctx.switchToSession(s.id) }
        }

        View {
            attr {
                flex(1f)
                flexDirectionColumn()
            }
            Text {
                attr {
                    text(if (s.pinned) "置顶 · " + s.title else s.title)
                    fontSize(14f)
                    fontWeightBold()
                    color(AppColor.TEXT_INK)
                }
            }
            Text {
                attr {
                    text(if (ctx.activeSessionId == s.id) "当前会话 · 共 " + s.messages.size + " 条消息" else "共 " + s.messages.size + " 条消息")
                    fontSize(11f)
                    color(AppColor.TEXT_HINT)
                    marginTop(2f)
                }
            }
        }

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
                    color(AppColor.TEXT_HINT)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.renameDialog(ctx: ChatMainPage) {
    View {
        attr {
            absolutePositionAllZero()
            backgroundColor(AppColor.SCRIM)
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
        }
        event { click { ctx.renameOverlay.hide() } }

        View {
            attr {
                overlayEnterExit(ctx.renameOverlay)
                width(ctx.pagerData.pageViewWidth - 64f)
                flexDirectionColumn()
                backgroundColor(AppColor.SURFACE)
                borderRadius(12f)
                padding(left = 20f, top = 20f, right = 20f, bottom = 20f)
            }

            Text {
                attr {
                    text("重命名会话")
                    fontSize(17f)
                    fontWeightBold()
                    color(AppColor.TEXT_INK)
                }
            }

            Input {
                ref {
                    ctx.renameInputRef = it.view
                }
                attr {
                    height(40f)
                    margin(top = 12f)
                    fontSize(14f)
                    color(Color(AppColor.TEXT_INK))
                    editable(true)
                    autofocus(true)
                    text(ctx.renameInputText)
                    backgroundColor(AppColor.SURFACE_SOFT)
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

                View {
                    attr {
                        flex(1f)
                        height(40f)
                        backgroundColor(AppColor.SURFACE_SOFT)
                        borderRadius(20f)
                        alignItems(FlexAlign.CENTER)
                        justifyContent(FlexJustifyContent.CENTER)
                    }
                    event { click { ctx.renameOverlay.hide() } }
                    Text {
                        attr {
                            text("取消")
                            fontSize(14f)
                            color(AppColor.TEXT_GRAY)
                        }
                    }
                }

                View {
                    attr {
                        flex(1f)
                        height(40f)
                        backgroundColor(AppColor.PRIMARY_SOFT)
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
                            color(AppColor.ON_DARK)
                        }
                    }
                }
            }
        }
    }
}
internal fun ViewContainer<*, *>.exportDialog(ctx: ChatMainPage) {
    val target = ctx.sessions.firstOrNull { it.id == ctx.exportTargetId }
    val title = target?.title?.ifBlank { "当前会话" } ?: "当前会话"
    val count = target?.messages?.size ?: 0
    View {
        attr {
            absolutePositionAllZero()
            backgroundColor(AppColor.SCRIM)
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
        }
        event { click { ctx.exportOverlay.hide() } }

        View {
            attr {
                overlayEnterExit(ctx.exportOverlay)
                width(ctx.pagerData.pageViewWidth - 64f)
                flexDirectionColumn()
                backgroundColor(AppColor.SURFACE)
                borderRadius(12f)
                padding(left = 20f, top = 20f, right = 20f, bottom = 20f)
            }

            Text {
                attr {
                    text("导出会话")
                    fontSize(17f)
                    fontWeightBold()
                    color(AppColor.TEXT_INK)
                }
            }
            Text {
                attr {
                    text("「" + title + "」· " + count + " 条消息")
                    fontSize(13f)
                    color(AppColor.TEXT_HINT)
                    marginTop(6f)
                }
            }

            exportOption(ctx, "分享 Markdown", "调起系统分享面板，可发送到微信、邮件等", 0)
            exportOption(ctx, "保存 .md 到下载文件夹", "生成 Markdown 文件，方便归档与二次编辑", 1)
            exportOption(ctx, "复制全文", "复制 Markdown 到剪贴板", 2)
            exportOption(ctx, "分享 JSON（备份）", "完整数据，可用于备份与恢复", 3)

            View {
                attr {
                    flexDirectionRow()
                    marginTop(16f)
                }
                View {
                    attr {
                        flex(1f)
                        height(40f)
                        backgroundColor(AppColor.SURFACE_SOFT)
                        borderRadius(20f)
                        alignItems(FlexAlign.CENTER)
                        justifyContent(FlexJustifyContent.CENTER)
                    }
                    event { click { ctx.exportOverlay.hide() } }
                    Text {
                        attr {
                            text("取消")
                            fontSize(15f)
                            color(AppColor.TEXT_GRAY)
                        }
                    }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.exportOption(ctx: ChatMainPage, label: String, desc: String, kind: Int) {
    View {
        attr {
            marginTop(10f)
            backgroundColor(AppColor.SURFACE_SOFT)
            borderRadius(10f)
            padding(left = 14f, top = 10f, right = 14f, bottom = 10f)
        }
        event { click { ctx.performExport(kind) } }
        Text {
            attr {
                text(label)
                fontSize(15f)
                fontWeightBold()
                color(AppColor.TEXT_INK)
            }
        }
        Text {
            attr {
                text(desc)
                fontSize(12f)
                color(AppColor.TEXT_HINT)
                marginTop(2f)
            }
        }
    }
}

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

internal fun ViewContainer<*, *>.quoteBar(ctx: ChatMainPage) {
    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            margin(top = 6f, left = 12f, right = 12f, bottom = 2f)
            padding(left = 10f, top = 6f, right = 6f, bottom = 6f)
            backgroundColor(AppColor.PRIMARY_BG)
            borderRadius(8f)
        }
        View {
            attr {
                flex(1f)
                flexDirectionColumn()
            }
            Text {
                attr {
                    text("引用消息")
                    fontSize(10f)
                    fontWeightBold()
                    color(AppColor.PRIMARY_SOFT)
                }
            }
            Text {
                attr {
                    text(if (ctx.quoteText.length > 120) ctx.quoteText.take(120) + "…" else ctx.quoteText)
                    fontSize(13f)
                    color(AppColor.TEXT_GRAY)
                    marginTop(2f)
                    lineHeight(18f)
                }
            }
        }
        View {
            attr {
                padding(left = 10f, top = 6f, right = 10f, bottom = 6f)
            }
            event { click { ctx.clearQuote() } }
            Text {
                attr {
                    text("✕")
                    fontSize(14f)
                    color(AppColor.TEXT_GRAY)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.msgActionSheet(ctx: ChatMainPage) {
    View {
        attr {
            absolutePositionAllZero()
            backgroundColor(0x99000000)
            justifyContent(FlexJustifyContent.FLEX_END)
        }
        event { click { ctx.showMsgActions = false } }

        View {
            attr {
                absolutePosition(left = 0f, right = 0f, bottom = 0f)
                flexDirectionColumn()
                backgroundColor(AppColor.SURFACE)
                padding(bottom = 16f)
            }
            View {
                attr {
                    height(24f)
                    alignItems(FlexAlign.CENTER)
                    justifyContent(FlexJustifyContent.CENTER)
                }
                Text {
                    attr {
                        text("消息操作")
                        fontSize(12f)
                        color(AppColor.TEXT_HINT)
                        textAlignCenter()
                    }
                }
            }
            View {
                attr {
                    height(1f)
                    backgroundColor(AppColor.SURFACE_SOFT)
                }
            }
            msgActionItem(ctx, "复制", onClick = { ctx.copyMsg() })
            msgActionItem(ctx, "删除", onClick = { ctx.deleteMsg() })
            msgActionItem(ctx, "引用", onClick = { ctx.quoteMsg() })
            View {
                attr {
                    height(1f)
                    backgroundColor(AppColor.SURFACE_SOFT)
                    margin(top = 4f)
                }
            }
            View {
                attr {
                    height(46f)
                    alignItems(FlexAlign.CENTER)
                    justifyContent(FlexJustifyContent.CENTER)
                }
                event { click { ctx.showMsgActions = false } }
                Text {
                    attr {
                        text("取消")
                        fontSize(16f)
                        color(AppColor.TEXT_INK)
                    }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.msgActionItem(ctx: ChatMainPage, label: String, onClick: () -> Unit) {
    View {
        attr {
            height(48f)
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
        }
        event { click { onClick() } }
        Text {
            attr {
                text(label)
                fontSize(16f)
                color(if (label == "删除") StockColors.UP else AppColor.PRIMARY_SOFT)
            }
        }
    }
}

data class ChatMessageItem(
    val role: String,
    val content: String,
    val isUser: Boolean,
    val cards: List<Map<String, Any?>>? = null,
    val suggestions: List<String>? = null,
    val failed: Boolean = false,
    val retryQuestion: String = "",
)

data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessageItem>,
    val updatedAt: Long,
    val pinned: Boolean = false
)

/** 空状态「最近对话」最多展示几条。 */
private const val RECENT_PREVIEW_MAX = 3
