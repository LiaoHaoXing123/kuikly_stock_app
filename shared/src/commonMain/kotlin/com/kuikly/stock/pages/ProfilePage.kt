package com.kuikly.stock.pages

import com.kuikly.stock.base.BasePager
import com.kuikly.stock.ai.config.AiRuntimeConfig
import com.kuikly.stock.data.DataSourceManager
import com.kuikly.stock.data.DataUpdater
import com.kuikly.stock.data.STOCK_DATA_BASE
import com.kuikly.stock.data.StockDb
import com.kuikly.stock.data.StockRepository
import com.kuikly.stock.home.HomeDashboardService
import com.kuikly.stock.ui.component.AppRoutes
import com.kuikly.stock.ui.component.Overlay
import com.kuikly.stock.ui.component.PressState
import com.kuikly.stock.ui.component.appBottomNav
import com.kuikly.stock.ui.component.openModule
import com.kuikly.stock.ui.component.overlayEnterExit
import com.kuikly.stock.ui.component.pageTitleBar
import com.kuikly.stock.ui.component.pressFeedback
import com.kuikly.stock.ui.component.pressedBg
import com.kuikly.stock.ui.component.segmentedControl
import com.kuikly.stock.ui.component.statusFeedback
import com.kuikly.stock.ui.theme.AppColor
import com.kuikly.stock.ui.theme.AppFont
import com.kuikly.stock.ui.theme.AppRadius
import com.kuikly.stock.ui.theme.AppSize
import com.kuikly.stock.ui.theme.AppSpace
import com.kuikly.stock.ui.theme.ThemeManager
import com.kuikly.stock.ui.theme.ThemeMode
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.AccessibilityRole
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

@Page(AppRoutes.PROFILE)
class ProfilePage : BasePager() {
    internal var aiStatus by observable("未配置")
    internal var dataStatus by observable("检查中")
    internal var refreshing by observable(false)
    internal var refreshIsError by observable(false)
    internal var refreshMessage by observable("")

    internal var devModeOnline by observable(DataSourceManager.isOnline)
    internal var modeSwitchNotice by observable("")

    internal var themeIsDark by observable(ThemeManager.isDark)
    internal val statusOverlay = Overlay(this)
    internal val aiStatusLines: ObservableList<String> by observableList()

    internal val press = PressState(this)

    override fun didInit() {
        super.didInit()
        restoreDataSourceMode()
        reloadStatus()
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        reloadStatus()
    }

    private fun restoreDataSourceMode() {
        val saved = runCatching {
            acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
                .getItem(DataSourceManager.PREFS_KEY)
        }.getOrNull()
        when (saved) {
            "ONLINE" -> DataSourceManager.setMode(DataSourceManager.Mode.ONLINE)
            "OFFLINE" -> DataSourceManager.setMode(DataSourceManager.Mode.OFFLINE)
        }
        devModeOnline = DataSourceManager.isOnline
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
            try {
                val updated = pageResult { DataUpdater.refreshNow() }
                HomeDashboardService.invalidate()
                reloadStatus()
                refreshIsError = false
                refreshMessage = if (updated) "行情数据已更新" else "数据已是最新"
            } catch (e: Throwable) {
                refreshIsError = true
                refreshMessage = "更新失败，请稍后重试"
            } finally {
                refreshing = false
            }
        }
    }

    internal fun selectMode(online: Boolean) {
        DataSourceManager.setMode(
            if (online) DataSourceManager.Mode.ONLINE else DataSourceManager.Mode.OFFLINE
        )
        val notice = if (online) "已切换到在线模式" else "已切换到离线模式"
        modeSwitchNotice = notice
        lifecycleScope.launch {
            devModeOnline = online
            runCatching {
                acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
                    .setItem(DataSourceManager.PREFS_KEY, if (online) "ONLINE" else "OFFLINE")
            }
        }
        lifecycleScope.launch {
            delay(3000)
            if (modeSwitchNotice == notice) modeSwitchNotice = ""
        }
    }

    internal fun selectTheme(dark: Boolean) {
        setTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        themeIsDark = ThemeManager.isDark
    }

    internal fun runAiStatusCheck() {
        aiStatusLines.clear()
        aiStatusLines.add("正在检测…（最多 30 秒）")
        statusOverlay.show()
        lifecycleScope.launch {
            try {
                val result = StockRepository.checkAiService()
                delay(0)
                aiStatusLines.clear()
                result.split("\n").forEach { aiStatusLines.add(it) }
            } catch (e: Throwable) {
                delay(0)
                aiStatusLines.clear()
                ("检测失败\n原因：${e.message ?: "未知错误"}\n排查：①是否联网 ②本地 SQLite 库是否就绪 ③数据源是否已配置")
                    .split("\n").forEach { aiStatusLines.add(it) }
            }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr { flex(1f); flexDirectionColumn(); backgroundColor(AppColor.BG) }
                pageTitleBar(ctx, "我的", "配置、数据与隐私", { ctx.refreshing }, showBack = false) { ctx.refreshData() }
                Scroller {
                    attr { flex(1f); flexDirectionColumn(); scrollEnable(true) }
                    View {
                    attr { padding(AppSpace.PAGE) }
                    profileHero(ctx)
                    profileSection("服务")
                    profileRow("盈亏日历", { "持仓每日快照" }, true) { ctx.openModule(AppRoutes.CALENDAR) }
                    profileRow("API 配置", { ctx.aiStatus }, true) { ctx.openApi() }
                    profileRow("行情数据", { ctx.dataStatus }, false) { }
                    profileRow("立即更新数据", { if (ctx.refreshing) "更新中" else "手动刷新" }, true) { ctx.refreshData() }
                    statusFeedback({ ctx.refreshMessage }, { ctx.refreshIsError })

                    profileSection("数据来源（开发者选项）")
                    devModeRow(ctx, "离线模式", "内置数据 + 本地模板回答（不联网）", online = false)
                    devModeRow(ctx, "在线模式", "使用当前 API 配置（需联网）", online = true)
                    devModeNotice({ ctx.modeSwitchNotice })
                    profileActionRow("检测 AI 服务连接") { ctx.runAiStatusCheck() }

                    profileSection("外观")
                    themeRow(ctx)

                    profileSection("安全与说明")
                    profileInfo("API Key 仅在本机使用 Android Keystore 加密保存，不写入聊天历史、源码或日志。")
                    profileInfo("行情来源：$STOCK_DATA_BASE\n应用不会在首页自动调用付费 AI。")
                    Text {
                        attr {
                            text("Kuikly Stock · 开发版")
                            fontSize(AppFont.CAPTION)
                            color(AppColor.TEXT_MUTED)
                            margin(top = AppSpace.LOOSE, bottom = 10f)
                            textAlignCenter()
                        }
                    }
                }
                }
                appBottomNav(ctx, AppRoutes.PROFILE)

                vif({ ctx.statusOverlay.isVisible }) { statusDialog(ctx) }
            }
        }
    }
}

private fun ViewContainer<*, *>.profileHero(ctx: ProfilePage) {
    View {
        attr {
            padding(18f)
            borderRadius(AppRadius.XL)
            backgroundColor(AppColor.INK_PANEL)
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
        }
        View {
            attr { size(46f, 46f); borderRadius(23f); allCenter(); backgroundColor(AppColor.INK_PANEL_ALT) }
            Text { attr { text("研"); fontSize(19f); fontWeightBold(); color(Color.WHITE) } }
        }
        View {
            attr { flex(1f); marginLeft(13f) }
            Text { attr { text("你的本地研究助手"); fontSize(AppFont.TITLE); fontWeightBold(); color(Color.WHITE) } }
            Text {
                attr {
                    text(ctx.aiStatus)
                    fontSize(AppFont.CAPTION)
                    color(AppColor.ON_DARK_SUB)
                    marginTop(4f)
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.profileSection(title: String) {
    Text {
        attr {
            text(title)
            fontSize(AppFont.BODY)
            fontWeightBold()
            color(AppColor.TEXT_SUB)
            margin(top = AppSpace.LOOSE, left = 4f, bottom = AppSpace.TIGHT)
        }
    }
}

private fun ViewContainer<*, *>.profileRow(title: String, value: () -> String, clickable: Boolean, action: () -> Unit) {
    View {
        attr {
            minHeight(AppSize.ROW_MIN)
            padding(left = 15f, right = 15f)
            marginBottom(1f)
            backgroundColor(AppColor.SURFACE)
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            accessibility("$title，${value()}")
            if (clickable) {
                accessibilityRole(AccessibilityRole.BUTTON)
                accessibilityInfo(true, false)
            }
        }
        if (clickable) event { click { action() } }
        Text { attr { text(title); fontSize(AppFont.LABEL); color(AppColor.TEXT); flex(1f) } }
        Text { attr { text(value()); fontSize(AppFont.CAPTION); color(AppColor.TEXT_SUB); marginLeft(10f) } }
        if (clickable) Text { attr { text("  ›"); fontSize(20f); color(AppColor.TEXT_MUTED) } }
    }
}

private fun ViewContainer<*, *>.profileActionRow(label: String, action: () -> Unit) {
    View {
        attr {
            height(AppSize.ROW_MIN)
            backgroundColor(AppColor.SURFACE)
            borderRadius(AppRadius.MD)
            marginTop(AppSpace.TIGHT)
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
            accessibility(label)
            accessibilityRole(AccessibilityRole.BUTTON)
            accessibilityInfo(true, false)
        }
        event { click { action() } }
        Text { attr { text(label); fontSize(AppFont.BODY); fontWeightBold(); color(AppColor.PRIMARY) } }
    }
}

private fun ViewContainer<*, *>.themeRow(ctx: ProfilePage) {
    View {
        attr {
            minHeight(AppSize.ROW_MIN)
            padding(left = 15f, top = 10f, right = 15f, bottom = 10f)
            backgroundColor(AppColor.SURFACE)
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            accessibility("主题，${if (ctx.themeIsDark) "深色" else "浅色"}")
        }
        View {
            attr { flex(1f); flexDirectionColumn() }
            Text { attr { text("主题"); fontSize(AppFont.LABEL); fontWeightBold(); color(AppColor.TEXT) } }
            Text {
                attr {
                    text("深色在暗光下更省眼")
                    fontSize(AppFont.CAPTION)
                    color(AppColor.TEXT_SUB)
                    marginTop(2f)
                }
            }
        }
        segmentedControl(
            options = THEME_OPTIONS,
            selectedIndex = { if (ctx.themeIsDark) 1 else 0 },
            itemWidth = THEME_OPTION_W,
            onSelect = { ctx.selectTheme(it == 1) },
            height = 30f,
            trackRadius = 16f,
            thumbRadius = 13f,
            thumbColor = { AppColor.SURFACE },
            selectedTextColor = { AppColor.PRIMARY_SOFT },
            fontSize = AppFont.NOTE,
            accessibilityLabel = { label, selected -> if (selected) "主题：$label，已选择" else "主题：$label" },
        )
    }
}

private val THEME_OPTIONS = listOf("浅色", "深色")

private const val THEME_OPTION_W = 44f

private fun ViewContainer<*, *>.devModeRow(ctx: ProfilePage, label: String, desc: String, online: Boolean) {
    val selected = ctx.devModeOnline == online
    val tag = "devmode:$online"
    View {
        attr {
            minHeight(AppSize.ROW_MIN)
            padding(left = 15f, top = 10f, right = 15f, bottom = 10f)
            marginBottom(1f)
            pressedBg(ctx.press, tag, normal = AppColor.SURFACE)
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            accessibility("$label，${if (selected) "已选择" else "未选择"}")
            accessibilityRole(AccessibilityRole.CHECKBOX)
            accessibilityInfo(true, false)
        }
        event {
            pressFeedback(ctx.press, tag)
            click {
                ctx.press.releaseAll()
                if (!selected) ctx.selectMode(online)
            }
        }
        View {
            attr { flex(1f); flexDirectionColumn() }
            Text { attr { text(label); fontSize(AppFont.LABEL); fontWeightBold(); color(AppColor.TEXT) } }
            Text { attr { text(desc); fontSize(AppFont.CAPTION); color(AppColor.TEXT_SUB); marginTop(2f) } }
        }
        Text {
            attr {
                text(if (selected) "已选" else "选择")
                fontSize(AppFont.NOTE)
                fontWeightBold()
                color(if (selected) AppColor.PRIMARY else AppColor.TEXT_MUTED)
                marginLeft(AppSpace.TIGHT)
            }
        }
    }
}

private fun ViewContainer<*, *>.devModeNotice(message: () -> String) {
    vif({ message().isNotEmpty() }) {
        View {
            attr {
                marginTop(6f)
                padding(AppSpace.TIGHT)
                backgroundColor(AppColor.SUCCESS_BG)
                borderRadius(AppRadius.SM)
            }
            Text { attr { text(message()); fontSize(AppFont.CAPTION); color(AppColor.SUCCESS) } }
        }
    }
}

private fun ViewContainer<*, *>.profileInfo(text: String) {
    View {
        attr {
            padding(15f)
            marginBottom(AppSpace.TIGHT)
            borderRadius(AppRadius.MD)
            backgroundColor(AppColor.SURFACE)
        }
        Text {
            attr {
                text(text)
                fontSize(AppFont.NOTE)
                lineHeight(19f)
                color(AppColor.TEXT_SUB)
            }
        }
    }
}

private fun ViewContainer<*, *>.statusDialog(ctx: ProfilePage) {
    View {
        attr {
            absolutePositionAllZero()
            backgroundColor(0x99000000)
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
        }
        event { click { ctx.statusOverlay.hide() } }

        View {
            attr {
                overlayEnterExit(ctx.statusOverlay)
                width(ctx.pagerData.pageViewWidth - 64f)
                flexDirectionColumn()
                backgroundColor(AppColor.SURFACE)
                borderRadius(AppRadius.MD)
                padding(AppSpace.LOOSE)
            }
            Text {
                attr {
                    text("AI 服务连接检测")
                    fontSize(AppFont.TITLE)
                    fontWeightBold()
                    color(AppColor.TEXT_STRONG)
                }
            }
            vfor({ ctx.aiStatusLines }) { line ->
                if (line.isNotBlank()) {
                    Text {
                        attr {
                            text(line)
                            fontSize(AppFont.BODY)
                            color(AppColor.TEXT)
                            marginTop(4f)
                        }
                    }
                }
            }
            View {
                attr {
                    marginTop(AppSpace.CARD)
                    height(40f)
                    backgroundColor(AppColor.PRIMARY_SOFT)
                    borderRadius(20f)
                    alignItems(FlexAlign.CENTER)
                    justifyContent(FlexJustifyContent.CENTER)
                }
                event { click { ctx.statusOverlay.hide() } }
                Text {
                    attr {
                        text("关闭")
                        fontSize(AppFont.LABEL)
                        color(AppColor.ON_DARK)
                        fontWeightBold()
                    }
                }
            }
        }
    }
}
