package com.kuikly.stock.pages

import com.kuikly.stock.ai.config.AiRuntimeConfig
import com.kuikly.stock.data.DataUpdater
import com.kuikly.stock.data.STOCK_DATA_BASE
import com.kuikly.stock.data.StockDb
import com.kuikly.stock.home.HomeDashboardService
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

@Page(AppRoutes.PROFILE)
class ProfilePage : Pager() {
    internal var aiStatus by observable("未配置")
    internal var dataStatus by observable("检查中")
    internal var refreshing by observable(false)
    internal var refreshMessage by observable("")

    override fun didInit() {
        super.didInit()
        reloadStatus()
    }

    internal fun reloadStatus() {
        aiStatus = runCatching {
            val p = AiRuntimeConfig.activeProfile()
            if (AiRuntimeConfig.isConfigured()) "${p.name} · ${p.model}" else "${p.name} · 待填写密钥"
        }.getOrDefault("未配置")
        dataStatus = if (runCatching { StockDb.isAvailable() }.getOrDefault(false)) "本地数据库可用" else "本地数据不可用"
    }

    internal fun openApi() {
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(AppRoutes.API_CONFIG, JSONObject())
    }

    internal fun refreshData() {
        if (refreshing) return
        refreshing = true
        refreshMessage = "正在更新本地行情…"
        lifecycleScope.launch {
            val ok = runCatching { DataUpdater.refreshNow() }.getOrDefault(false)
            refreshMessage = if (ok) "行情数据已更新" else "更新失败，请稍后重试"
            refreshing = false
            HomeDashboardService.invalidate()
            reloadStatus()
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr { flex(1f); flexDirectionColumn(); backgroundColor(0xFFF4F7FB) }
                pageTitleBar(ctx, "我的", "配置、数据与隐私") { ctx.reloadStatus() }
                Scroller {
                    attr { flex(1f); flexDirectionColumn(); scrollEnable(true); padding(16f) }
                    profileHero(ctx)
                    profileSection("服务")
                    profileRow("API 配置", ctx.aiStatus, true) { ctx.openApi() }
                    profileRow("行情数据", ctx.dataStatus, false) { }
                    profileRow("立即更新数据", if (ctx.refreshing) "更新中" else "手动刷新", true) { ctx.refreshData() }
                    if (ctx.refreshMessage.isNotEmpty()) {
                        Text { attr { text(ctx.refreshMessage); fontSize(12f); color(0xFF0E67D1); margin(top = 8f, left = 4f) } }
                    }
                    profileSection("安全与说明")
                    profileInfo("API Key 仅在本机使用 Android Keystore 加密保存，不写入聊天历史、源码或日志。")
                    profileInfo("行情来源：$STOCK_DATA_BASE\n应用不会在首页自动调用付费 AI。")
                    Text { attr { text("Kuikly Stock · 开发版"); fontSize(11f); color(0xFF9AA3AF); margin(top = 20f, bottom = 10f); textAlignCenter() } }
                }
                appBottomNav(ctx, AppRoutes.PROFILE)
            }
        }
    }
}

private fun ViewContainer<*, *>.profileHero(ctx: ProfilePage) {
    View {
        attr { padding(18f); borderRadius(18f); backgroundColor(0xFF0B2B50); flexDirectionRow(); alignItems(FlexAlign.CENTER) }
        View { attr { size(46f, 46f); borderRadius(23f); allCenter(); backgroundColor(0xFF174D7C) }; Text { attr { text("研"); fontSize(19f); fontWeightBold(); color(Color.WHITE) } } }
        View { attr { flex(1f); marginLeft(13f) }
            Text { attr { text("你的本地研究助手"); fontSize(17f); fontWeightBold(); color(Color.WHITE) } }
            Text { attr { text(ctx.aiStatus); fontSize(11f); color(0xFFA9CBEA); marginTop(4f) } }
        }
    }
}

private fun ViewContainer<*, *>.profileSection(title: String) { Text { attr { text(title); fontSize(13f); fontWeightBold(); color(0xFF617086); margin(top = 20f, left = 4f, bottom = 8f) } } }

private fun ViewContainer<*, *>.profileRow(title: String, value: String, clickable: Boolean, action: () -> Unit) {
    View {
        attr { minHeight(56f); padding(left = 15f, right = 15f); marginBottom(1f); backgroundColor(Color.WHITE); flexDirectionRow(); alignItems(FlexAlign.CENTER) }
        if (clickable) event { click { action() } }
        Text { attr { text(title); fontSize(14f); color(0xFF1D3048); flex(1f) } }
        Text { attr { text(value); fontSize(11f); color(0xFF7E8998); marginLeft(10f) } }
        if (clickable) Text { attr { text("  ›"); fontSize(20f); color(0xFF9EA7B2) } }
    }
}

private fun ViewContainer<*, *>.profileInfo(text: String) {
    View { attr { padding(15f); marginBottom(8f); borderRadius(13f); backgroundColor(Color.WHITE) }; Text { attr { text(text); fontSize(12f); lineHeight(19f); color(0xFF677587) } } }
}
