package com.kuikly.stock.pages

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
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

internal data class AiProfileRow(
    val profile: AiProviderProfile,
    val active: Boolean,
    val maskedKey: String,
)

@Page(AppRoutes.API_CONFIG)
class ApiConfigPage : Pager() {
    internal var rows: ObservableList<AiProfileRow> by observableList()
    internal var showEditor by observable(false)
    internal var editingId by observable("")
    internal var editName by observable("")
    internal var editBaseUrl by observable("")
    internal var editModel by observable("")
    internal var editKey by observable("")
    internal var editTools by observable(true)
    internal var editorError by observable("")
    internal var statusMessage by observable("")
    internal var testingId by observable("")

    override fun didInit() {
        super.didInit()
        reload()
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
            .onSuccess { statusMessage = "已切换为当前配置" }
            .onFailure { statusMessage = it.message ?: "切换失败" }
        reload()
    }

    internal fun edit(profile: AiProviderProfile?) {
        val now = System.currentTimeMillis()
        editingId = profile?.id ?: "custom_$now"
        editName = profile?.name ?: "自定义服务"
        editBaseUrl = profile?.baseUrl ?: "https://"
        editModel = profile?.model ?: ""
        editKey = ""
        editTools = profile?.toolsEnabled ?: true
        editorError = ""
        showEditor = true
    }

    internal fun saveEditor() {
        validateBaseUrl(editBaseUrl)?.let { editorError = it; return }
        if (editName.isBlank()) { editorError = "请输入配置名称"; return }
        if (editModel.isBlank()) { editorError = "请输入模型 ID"; return }
        val old = AiProfileStore.profiles().firstOrNull { it.id == editingId }
        val now = System.currentTimeMillis()
        val profile = AiProviderProfile(
            id = editingId,
            name = editName.trim(),
            baseUrl = editBaseUrl.trim().trimEnd('/'),
            model = editModel.trim(),
            toolsEnabled = editTools,
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
        )
        runCatching {
            AiProfileStore.saveProfile(profile)
            if (editKey.isNotBlank()) SecureSecretStore.put(profile.id, editKey.trim())
            if (old == null) AiProfileStore.setActive(profile.id)
        }.onSuccess {
            editKey = ""
            showEditor = false
            statusMessage = "配置已安全保存"
            reload()
        }.onFailure {
            editorError = it.message ?: "保存失败"
        }
    }

    internal fun delete(profile: AiProviderProfile) {
        if (AiProfileStore.deleteProfile(profile.id)) {
            SecureSecretStore.remove(profile.id)
            statusMessage = "已删除 ${profile.name}"
        } else {
            statusMessage = "至少保留一个 API 配置"
        }
        reload()
    }

    internal fun test(profile: AiProviderProfile) {
        if (testingId.isNotEmpty()) return
        val secret = SecureSecretStore.get(profile.id).orEmpty()
        if (secret.isBlank()) {
            statusMessage = "请先编辑并填写 API Key"
            return
        }
        testingId = profile.id
        statusMessage = "正在测试 ${profile.name}…"
        lifecycleScope.launch {
            val result = runCatching { DeepSeekApi.testConnection(profile, secret) }
            statusMessage = result.fold(
                onSuccess = { if (it.success) "连接成功 · ${it.providerName} · ${it.model} · ${it.elapsedMs}ms" else it.message },
                onFailure = { it.message ?: "连接测试失败" },
            )
            testingId = ""
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr { flex(1f); flexDirectionColumn(); backgroundColor(0xFFF4F7FB) }
                pageTitleBar(ctx, "API 配置", "OpenAI Compatible · 密钥本机加密") { ctx.reload() }
                Scroller {
                    attr { flex(1f); flexDirectionColumn(); scrollEnable(true); padding(16f) }
                    apiSecurityNote()
                    vif({ ctx.statusMessage.isNotEmpty() }) {
                        View { attr { padding(12f); margin(top = 10f, bottom = 2f); borderRadius(11f); backgroundColor(0xFFE8F2FF) }; Text { attr { text(ctx.statusMessage); fontSize(12f); lineHeight(18f); color(0xFF165D9E) } } }
                    }
                    vfor({ ctx.rows }) { row -> apiProfileCard(ctx, row) }
                    View {
                        attr { minHeight(48f); allCenter(); margin(top = 5f, bottom = 20f); borderRadius(14f); backgroundColor(0xFF0E67D1); accessibility("添加自定义 API 服务"); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(true, false) }
                        event { click { ctx.edit(null) } }
                        Text { attr { text("＋ 添加自定义服务"); fontSize(14f); fontWeightBold(); color(Color.WHITE) } }
                    }
                }
                vif({ ctx.showEditor }) { apiEditor(ctx) }
            }
        }
    }
}

private fun ViewContainer<*, *>.apiSecurityNote() {
    View {
        attr { padding(14f); borderRadius(14f); backgroundColor(0xFF0B2B50) }
        Text { attr { text("密钥不会进入项目文件"); fontSize(15f); fontWeightBold(); color(Color.WHITE) } }
        Text { attr { text("Base URL 与模型可自由修改；API Key 使用 Android Keystore 加密。编辑时留空表示保留原密钥。"); fontSize(12f); lineHeight(19f); color(0xFFB7D3EE); marginTop(6f) } }
    }
}

private fun ViewContainer<*, *>.apiProfileCard(ctx: ApiConfigPage, row: AiProfileRow) {
    val profile = row.profile
    View {
        attr { padding(15f); marginTop(11f); borderRadius(15f); backgroundColor(Color.WHITE) }
        View { attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
            View { attr { flex(1f) }
                Text { attr { text(profile.name); fontSize(16f); fontWeightBold(); color(0xFF182C45) } }
                Text { attr { text(if (row.active) "当前使用" else "未启用"); fontSize(10f); color(if (row.active) 0xFF0E67D1 else 0xFF8C96A4); marginTop(2f) } }
            }
            Text { attr { text(row.maskedKey); fontSize(11f); color(if (row.maskedKey == "未配置") 0xFFD05B5B else 0xFF32815C) } }
        }
        Text { attr { text(profile.baseUrl); fontSize(11f); color(0xFF738092); marginTop(10f); lineHeight(16f) } }
        Text { attr { text("模型  ${profile.model}  ·  工具调用 ${if (profile.toolsEnabled) "开启" else "关闭"}"); fontSize(11f); color(0xFF738092); marginTop(4f) } }
        View { attr { flexDirectionRow(); marginTop(13f) }
            apiSmallButton(if (row.active) "已启用" else "启用", row.active) { ctx.activate(profile.id) }
            apiSmallButton("编辑", false) { ctx.edit(profile) }
            apiSmallButton(if (ctx.testingId == profile.id) "测试中" else "测试", ctx.testingId.isNotEmpty()) { ctx.test(profile) }
            apiSmallButton("删除", false, danger = true) { ctx.delete(profile) }
        }
    }
}

private fun ViewContainer<*, *>.apiSmallButton(label: String, disabled: Boolean, danger: Boolean = false, action: () -> Unit) {
    View {
        attr { minWidth(54f); height(44f); padding(left = 8f, right = 8f); marginRight(7f); borderRadius(10f); allCenter(); backgroundColor(if (danger) 0xFFFFEEEE else if (disabled) 0xFFF0F2F5 else 0xFFE8F2FF); accessibility(label); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(!disabled, false) }
        event { click { if (!disabled) action() } }
        Text { attr { text(label); fontSize(11f); fontWeightBold(); color(if (danger) 0xFFC34C4C else if (disabled) 0xFF929BA8 else 0xFF0E67D1) } }
    }
}

private fun ViewContainer<*, *>.apiEditor(ctx: ApiConfigPage) {
    View {
        attr { absolutePositionAllZero(); backgroundColor(0x88000000); justifyContent(FlexJustifyContent.FLEX_END) }
        View {
            attr { maxHeight(ctx.pagerData.pageViewHeight * 0.86f); padding(18f); backgroundColor(Color.WHITE); borderRadius(22f) }
            View { attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
                Text { attr { text("编辑 API 配置"); fontSize(19f); fontWeightBold(); color(0xFF142941); flex(1f) } }
                View { attr { size(44f, 44f); allCenter(); accessibility("关闭编辑器"); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(true, false) }; event { click { ctx.editKey = ""; ctx.showEditor = false } }; Text { attr { text("×"); fontSize(25f); color(0xFF758193) } } }
            }
            Scroller {
                attr { flexDirectionColumn(); scrollEnable(true) }
                apiInput("配置名称", ctx.editName, "例如：AgentRouter") { ctx.editName = it }
                apiInput("Base URL", ctx.editBaseUrl, "https://example.com/v1") { ctx.editBaseUrl = it }
                apiInput("模型 ID", ctx.editModel, "例如：deepseek-v4-flash") { ctx.editModel = it }
                Text { attr { text("API Key（留空保留原密钥）"); fontSize(12f); color(0xFF536276); margin(top = 12f, bottom = 5f) } }
                Input {
                    attr { height(44f); borderRadius(10f); backgroundColor(0xFFF3F5F8); fontSize(13f); color(Color(0xFF1B2D44)); placeholder("仅保存在本机加密区"); keyboardTypePassword(); text(ctx.editKey); accessibility("API Key 密码输入框") }
                    event { textDidChange(isSyncEdit = true) { ctx.editKey = it.text } }
                }
                View {
                    attr { minHeight(48f); flexDirectionRow(); alignItems(FlexAlign.CENTER); marginTop(10f); accessibility("工具调用，${if (ctx.editTools) "已开启" else "已关闭"}"); accessibilityRole(AccessibilityRole.CHECKBOX); accessibilityInfo(true, false) }
                    event { click { ctx.editTools = !ctx.editTools } }
                    Text { attr { text("工具调用"); fontSize(13f); color(0xFF263A51); flex(1f) } }
                    Text { attr { text(if (ctx.editTools) "开启" else "关闭"); fontSize(12f); fontWeightBold(); color(if (ctx.editTools) 0xFF0E67D1 else 0xFF7F8998) } }
                }
                vif({ ctx.editorError.isNotEmpty() }) { Text { attr { text(ctx.editorError); fontSize(12f); color(0xFFD34C4C); marginTop(8f) } } }
                View {
                    attr { height(48f); allCenter(); borderRadius(14f); margin(top = 14f, bottom = 12f); backgroundColor(0xFF0E67D1); accessibility("安全保存 API 配置"); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(true, false) }
                    event { click { ctx.saveEditor() } }
                    Text { attr { text("安全保存"); fontSize(14f); fontWeightBold(); color(Color.WHITE) } }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.apiInput(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    Text { attr { text(label); fontSize(12f); color(0xFF536276); margin(top = 12f, bottom = 5f) } }
    Input {
        attr { height(44f); borderRadius(10f); backgroundColor(0xFFF3F5F8); fontSize(13f); color(Color(0xFF1B2D44)); placeholder(placeholder); text(value); accessibility("$label 输入框") }
        event { textDidChange(isSyncEdit = true) { onChange(it.text) } }
    }
}
