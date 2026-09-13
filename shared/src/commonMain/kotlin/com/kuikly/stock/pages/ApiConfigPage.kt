package com.kuikly.stock.pages

import com.kuikly.stock.base.BasePager

import com.kuikly.stock.ai.config.AiProfileStore
import com.kuikly.stock.ai.config.AiProviderProfile
import com.kuikly.stock.ai.config.SecureSecretStore
import com.kuikly.stock.ai.config.maskApiKey
import com.kuikly.stock.ai.config.validateBaseUrl
import com.kuikly.stock.network.DeepSeekApi
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.AccessibilityRole
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.kuikly.stock.data.nowMillis
import com.kuikly.stock.ui.component.AppRoutes
import com.kuikly.stock.ui.component.pageTitleBar
import com.kuikly.stock.ui.component.statusFeedback
import com.kuikly.stock.ui.theme.AppColor

internal data class AiProfileRow(
    val profile: AiProviderProfile,
    val active: Boolean,
    val maskedKey: String,
)

@Page(AppRoutes.API_CONFIG)
class ApiConfigPage : BasePager() {
    internal var refreshing by observable(false)
    internal var rows: ObservableList<AiProfileRow> by observableList()
    internal var showEditor by observable(false)
    internal var editingId by observable("")
    internal var editName by observable("")
    internal var editBaseUrl by observable("")
    internal var editModels by observable("")
    internal var editKey by observable("")
    internal var keyVisible by observable(false)
    internal var editTools by observable(true)
    internal var editorError by observable("")
    internal var statusMessage by observable("")
    internal var statusIsError by observable(false)
    internal var testingId by observable("")
    internal var testedId by observable("")
    internal var testPassed by observable(false)
    internal var confirmDeleteId by observable("")

    override fun didInit() {
        super.didInit()
        reload()
        fillEditorFields(null)
        showEditor = true
    }

    private fun fillEditorFields(profile: AiProviderProfile?) {
        val now = nowMillis()
        editingId = profile?.id ?: "custom_$now"
        editName = profile?.name ?: ""
        editBaseUrl = profile?.baseUrl ?: ""
        editModels = profile?.model ?: ""
        editKey = profile?.let { SecureSecretStore.get(it.id).orEmpty() } ?: ""
        keyVisible = false
        editTools = profile?.toolsEnabled ?: true
        editorError = ""
    }

    internal fun refreshPage() {
        if (refreshing) return
        refreshing = true
        statusMessage = ""
        lifecycleScope.launch {
            try {
                delay(0)
                reload()
                statusIsError = false
                statusMessage = "刷新完成"
            } catch (e: Throwable) {
                statusIsError = true
                statusMessage = "刷新失败，请重试"
            } finally {
                refreshing = false
            }
        }
    }

    internal fun reload() {
        val activeId = runCatching { AiProfileStore.active().id }.getOrDefault("")
        rows.clear()
        rows.addAll(AiProfileStore.profiles().map { profile ->
            AiProfileRow(profile, profile.id == activeId, maskApiKey(SecureSecretStore.get(profile.id).orEmpty()))
        })
    }

    internal fun activate(id: String) {
        runCatching { AiProfileStore.setActive(id) }
            .onSuccess { statusMessage = "已切换为当前配置"; statusIsError = false }
            .onFailure { statusMessage = it.message ?: "切换失败"; statusIsError = true }
        reload()
    }

    internal fun edit(profile: AiProviderProfile?) {

        showEditor = false
        lifecycleScope.launch {
            delay(150)
            fillEditorFields(profile)
            showEditor = true
        }
    }

    internal fun saveEditor() {
        validateBaseUrl(editBaseUrl)?.let { editorError = it; return }
        if (editModels.isBlank()) { editorError = "请输入至少一个模型 ID"; return }
        val old = AiProfileStore.profiles().firstOrNull { it.id == editingId }
        val now = nowMillis()
        val models = editModels.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (models.isEmpty()) { editorError = "请输入至少一个模型 ID"; return }
        val displayName = editName.takeIf { it.isNotBlank() } ?: old?.name ?: providerNameFromUrl(editBaseUrl)
        val profile = AiProviderProfile(
            id = editingId,
            name = displayName,
            baseUrl = editBaseUrl.trim().trimEnd('/'),
            model = models.joinToString(", "),
            toolsEnabled = editTools,
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
        )
        runCatching {
            AiProfileStore.saveProfile(profile)
            if (editKey.isNotBlank()) SecureSecretStore.put(profile.id, editKey.trim())
            if (old == null) AiProfileStore.setActive(profile.id)
        }.onSuccess {

            showEditor = false
            statusMessage = "配置已安全保存"
            statusIsError = false
            reload()
            lifecycleScope.launch {
                delay(150)
                edit(null)
            }
        }.onFailure {
            editorError = it.message ?: "保存失败"
        }
    }

    internal fun delete(profile: AiProviderProfile) {
        confirmDeleteId = profile.id
    }

    internal fun confirmDelete(profile: AiProviderProfile) {
        if (AiProfileStore.deleteProfile(profile.id)) {
            SecureSecretStore.remove(profile.id)
            statusMessage = "已删除 ${profile.name}"
            statusIsError = false
        } else {
            statusMessage = "至少保留一个 API 配置"
            statusIsError = true
        }
        confirmDeleteId = ""
        reload()
    }

    internal fun test(profile: AiProviderProfile) {
        if (testingId.isNotEmpty()) return
        val secret = SecureSecretStore.get(profile.id).orEmpty()
        if (secret.isBlank()) {
            statusMessage = "请先编辑并填写 API Key"
            statusIsError = true
            return
        }
        testingId = profile.id
        testedId = ""
        statusMessage = "正在测试 ${profile.name}…"
        statusIsError = false
        lifecycleScope.launch {
            val result = runCatching { pageResult { DeepSeekApi.testConnection(profile, secret) } }
            result.fold(
                onSuccess = {
                    testPassed = it.success
                    testedId = profile.id
                    if (it.success) {
                        statusMessage = "连接成功 · ${it.providerName} · ${it.model} · ${it.elapsedMs}ms"
                        statusIsError = false
                    } else {
                        statusMessage = friendlyApiError(it.message)
                        statusIsError = true
                    }
                },
                onFailure = {
                    statusMessage = friendlyApiError(it.message ?: "连接测试失败")
                    statusIsError = true
                },
            )
            testingId = ""
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr { flex(1f); flexDirectionColumn(); backgroundColor(AppColor.BG) }
                pageTitleBar(ctx, "API 配置", "OpenAI Compatible · 密钥本机加密", { ctx.refreshing }) { ctx.refreshPage() }
                Scroller {
                    attr { flex(1f); flexDirectionColumn(); scrollEnable(true); padding(16f) }
                    apiSecurityNote()
                    vif({ ctx.showEditor }) { apiEditor(ctx) }
                    statusFeedback({ ctx.statusMessage }, { ctx.statusIsError })
                    vfor({ ctx.rows }) { row -> apiProfileCard(ctx, row) }
                    View { attr { minHeight(24f) } }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.apiSecurityNote() {
    View {
        attr { padding(14f); borderRadius(14f); backgroundColor(AppColor.INK_PANEL) }
        Text { attr { text("密钥不会进入项目文件"); fontSize(15f); fontWeightBold(); color(Color.WHITE) } }
        Text { attr { text("填写 Base URL、模型列表和 API Key；密钥只保存在本机加密区。"); fontSize(12f); lineHeight(19f); color(AppColor.ON_DARK_SUB); marginTop(6f) } }
    }
}

private fun ViewContainer<*, *>.apiProfileCard(ctx: ApiConfigPage, row: AiProfileRow) {
    val profile = row.profile
    View {
        attr { padding(15f); marginTop(11f); borderRadius(15f); backgroundColor(AppColor.SURFACE) }
        View { attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
            View { attr { flex(1f) }
                Text { attr { text(profile.name); fontSize(16f); fontWeightBold(); color(AppColor.TEXT_STRONG) } }
                Text { attr { text(if (row.active) "当前使用" else "未启用"); fontSize(10f); color(if (row.active) AppColor.PRIMARY else AppColor.TEXT_SUB); marginTop(2f) } }
            }
            Text { attr { text(row.maskedKey); fontSize(11f); color(if (row.maskedKey == "未配置") AppColor.DANGER_TEXT else AppColor.SUCCESS) } }
        }
        Text { attr { text(profile.baseUrl); fontSize(11f); color(AppColor.TEXT_SUB_DEEP); marginTop(10f); lineHeight(16f) } }
        Text { attr { text("模型  ${profile.model}  ·  工具调用 ${if (profile.toolsEnabled) "开启" else "关闭"}"); fontSize(11f); color(AppColor.TEXT_SUB_DEEP); marginTop(4f) } }
        View { attr { flexDirectionRow(); marginTop(13f) }
            apiSmallButton(if (row.active) "已启用" else "启用", row.active) { ctx.activate(profile.id) }
            apiSmallButton("编辑", false) { ctx.edit(profile) }
            apiSmallButton(
                label = { if (ctx.testingId == profile.id) "测试中" else if (ctx.testedId == profile.id) {
                    if (ctx.testPassed) "已连通" else "连接失败"
                } else "测试" },
                disabled = { ctx.testingId.isNotEmpty() },
                stateColor = { if (ctx.testedId == profile.id) {
                    if (ctx.testPassed) AppColor.SUCCESS else AppColor.DANGER_TEXT
                } else null },
            ) { ctx.test(profile) }
            apiSmallButton("删除", false, danger = true) { ctx.delete(profile) }
        }
        vif({ ctx.confirmDeleteId == profile.id }) {
            View {
                attr { padding(10f); marginTop(10f); borderRadius(10f); backgroundColor(AppColor.DANGER_BG) }
                Text { attr { text("确认删除 ${profile.name}？"); fontSize(12f); color(AppColor.DANGER_TEXT) } }
                View { attr { flexDirectionRow(); marginTop(8f) }
                    apiSmallButton("取消", false) { ctx.confirmDeleteId = "" }
                    apiSmallButton("确认删除", false, danger = true) { ctx.confirmDelete(profile) }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.apiSmallButton(label: String, disabled: Boolean, danger: Boolean = false, stateColor: Long? = null, action: () -> Unit) {
    apiSmallButton({ label }, { disabled }, danger, { stateColor }, action)
}

private fun ViewContainer<*, *>.apiSmallButton(label: () -> String, disabled: () -> Boolean, danger: Boolean = false, stateColor: () -> Long? = { null }, action: () -> Unit) {
    View {
        attr { minWidth(54f); height(44f); padding(left = 8f, right = 8f); marginRight(7f); borderRadius(10f); allCenter(); backgroundColor(if (danger) AppColor.DANGER_BG else if (disabled()) AppColor.BG_SOFT else AppColor.PRIMARY_BG_LIGHT); accessibility(label()); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(!disabled(), false) }
        event { click { if (!disabled()) action() } }
        Text { attr { text(label()); fontSize(11f); fontWeightBold(); color(stateColor() ?: if (danger) AppColor.DANGER_TEXT else if (disabled()) AppColor.TEXT_SUB else AppColor.PRIMARY) } }
    }
}

private fun ViewContainer<*, *>.apiEditor(ctx: ApiConfigPage) {
    View {
        attr { padding(16f); marginTop(12f); borderRadius(16f); backgroundColor(AppColor.SURFACE) }
        View { attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
            Text { attr { text(if (ctx.editingId.startsWith("custom_")) "添加自定义服务" else "编辑：${ctx.editName}"); fontSize(18f); fontWeightBold(); color(AppColor.TEXT_STRONG); flex(1f) } }
            if (ctx.showEditor) {
                View { attr { minWidth(44f); height(44f); allCenter(); accessibility("清空输入"); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(true, false) }; event { click { ctx.edit(null) } }; Text { attr { text("清空"); fontSize(12f); color(AppColor.TEXT_SUB_DEEP) } } }
            }
        }
        apiInput("名称", ctx.editName, "") { ctx.editName = it }
        apiInput("BASE URL", ctx.editBaseUrl, "") { ctx.editBaseUrl = it }
        apiInput("MODELS", ctx.editModels, "") { ctx.editModels = it }
        Text { attr { text("API KEY"); fontSize(12f); color(AppColor.TEXT_SUB_DEEP); margin(top = 12f, bottom = 5f) } }
        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); height(44f); borderRadius(10f); backgroundColor(AppColor.SURFACE_ALT) }
            vif({ !ctx.keyVisible }) {
                Input {
                    attr { flex(1f); height(44f); fontSize(13f); color(Color(AppColor.TEXT_STRONG)); keyboardTypePassword(); text(ctx.editKey); accessibility("API Key 密码输入框") }
                    event { textDidChange(isSyncEdit = true) { ctx.editKey = it.text } }
                }
            }
            vif({ ctx.keyVisible }) {
                Input {
                    attr { flex(1f); height(44f); fontSize(13f); color(Color(AppColor.TEXT_STRONG)); text(ctx.editKey); accessibility("API Key 明文输入框") }
                    event { textDidChange(isSyncEdit = true) { ctx.editKey = it.text } }
                }
            }
            View { attr { size(44f, 44f); allCenter(); accessibility(if (ctx.keyVisible) "隐藏 API Key" else "显示 API Key"); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(true, false) }; event { click { ctx.keyVisible = !ctx.keyVisible } }; Text { attr { text(if (ctx.keyVisible) "隐" else "眼"); fontSize(12f); color(AppColor.PRIMARY); fontWeightBold() } } }
        }
        View {
            attr { minHeight(44f); flexDirectionRow(); alignItems(FlexAlign.CENTER); accessibility("工具调用，${if (ctx.editTools) "已开启" else "已关闭"}"); accessibilityRole(AccessibilityRole.CHECKBOX); accessibilityInfo(true, false) }
            event { click { ctx.editTools = !ctx.editTools } }
            Text { attr { text("工具调用"); fontSize(13f); color(AppColor.TEXT_STRONG); flex(1f) } }
            Text { attr { text(if (ctx.editTools) "开启" else "关闭"); fontSize(12f); fontWeightBold(); color(if (ctx.editTools) AppColor.PRIMARY else AppColor.TEXT_SUB) } }
        }
        vif({ ctx.editorError.isNotEmpty() }) { Text { attr { text(ctx.editorError); fontSize(12f); color(AppColor.DANGER_TEXT); marginTop(8f) } } }
        View {
            attr { height(48f); allCenter(); borderRadius(14f); margin(top = 14f, bottom = 2f); backgroundColor(AppColor.PRIMARY); accessibility("保存 API 配置"); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(true, false) }
            event { click { ctx.saveEditor() } }
            Text { attr { text(if (ctx.editingId.startsWith("custom_")) "保存配置" else "更新配置"); fontSize(14f); fontWeightBold(); color(Color.WHITE) } }
        }
    }
}

private fun ViewContainer<*, *>.apiInput(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    Text { attr { text(label); fontSize(12f); color(AppColor.TEXT_SUB_DEEP); margin(top = 12f, bottom = 5f) } }
    Input {
        attr { height(44f); borderRadius(10f); backgroundColor(AppColor.SURFACE_ALT); fontSize(13f); color(Color(AppColor.TEXT_STRONG)); placeholder(placeholder); text(value); accessibility("$label 输入框") }
        event { textDidChange(isSyncEdit = true) { onChange(it.text) } }
    }
}

private fun providerNameFromUrl(raw: String): String {
    val host = raw.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
    if (host.isBlank()) return "自定义服务"
    return host.split('.').firstOrNull { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "自定义服务"
}

private fun friendlyApiError(raw: String): String {
    val msg = raw.lowercase()
    return when {
        msg.contains("timeout") -> "连接超时，请检查网络或 Base URL 是否可达"
        msg.contains("unknownhost") || msg.contains("unable to resolve host") -> "无法解析域名，请检查 Base URL 是否正确"
        msg.contains("connection refused") -> "连接被拒绝，请确认服务已启动且端口正确"
        msg.contains("401") || msg.contains("unauthorized") -> "API Key 无效或已过期"
        msg.contains("403") || msg.contains("forbidden") -> "访问被拒绝，请检查 API Key 权限"
        msg.contains("404") || msg.contains("not found") -> "接口地址不存在，请检查 Base URL 和模型名称"
        msg.contains("429") || msg.contains("too many requests") -> "请求过于频繁，请稍后再试"
        msg.contains("500") || msg.contains("server error") -> "服务器内部错误，请稍后再试"
        msg.contains("ssl") || msg.contains("certificate") -> "SSL 证书验证失败，请检查 Base URL"
        else -> "连接失败，请检查网络、Base URL 和 API Key"
    }
}
