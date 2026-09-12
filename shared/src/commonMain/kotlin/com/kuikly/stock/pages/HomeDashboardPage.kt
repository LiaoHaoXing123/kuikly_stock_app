package com.kuikly.stock.pages

import com.kuikly.stock.base.BasePager
import com.kuikly.stock.ui.component.NumberRoll

import com.kuikly.stock.data.DataUpdater
import com.kuikly.stock.data.HoldingCalendar
import com.kuikly.stock.data.WatchStore
import com.kuikly.stock.home.DashboardFocusItem
import com.kuikly.stock.home.HomeDashboardService
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.attr.AccessibilityRole
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.layout.FlexWrap
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.RefreshView
import com.tencent.kuikly.core.views.RefreshViewState
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.kuikly.stock.ui.component.AppRoutes
import com.kuikly.stock.ui.component.REFRESH_SPIN_STEP_MS
import com.kuikly.stock.ui.component.appBottomNav
import com.kuikly.stock.ui.component.openModule
import com.kuikly.stock.ui.component.pullRefreshLabel
import com.kuikly.stock.ui.component.pullToRefresh
import com.kuikly.stock.ui.component.refreshButton
import com.kuikly.stock.ui.component.statusFeedback
import com.kuikly.stock.ui.component.sectionHeader
import com.kuikly.stock.ui.theme.AppColor

@Page(AppRoutes.HOME)
class HomeDashboardPage : BasePager() {
    internal var headline by observable("正在整理今日市场…")
    internal var summary by observable("本页只读取本地行情，不会自动调用付费 AI。")
    internal var marketLabel by observable("数据准备中")
    /**
     * 盘面广度（上涨/下跌/平盘家数）。用 [NumberRoll] 而不是普通 observable：
     * 刷新时这三个数字原地变化，滚一下才看得出「数据换了一批」。
     * 详情页的最新价做不到这件事——见 NumberRoll 的注释。
     */
    internal val breadthRoll = NumberRoll(
        this,
        initialText = "上涨 --  ·  下跌 --  ·  平盘 --",
    ) { v -> "上涨 ${v[0].toInt()}  ·  下跌 ${v[1].toInt()}  ·  平盘 ${v[2].toInt()}" }

    /** 自选仓的「N 个信号 · M 个提醒」，同样滚一下。 */
    internal val watchRoll = NumberRoll(
        this,
        initialText = "0 个信号 · 0 个提醒",
    ) { v -> "${v[0].toInt()} 个信号 · ${v[1].toInt()} 个提醒" }
    internal var dataDate by observable("待更新")
    internal var watchSignals by observable(0)
    internal var alertCount by observable(0)
    internal var focusItems: ObservableList<DashboardFocusItem> by observableList()
    internal var refreshing by observable(false)
    internal var refreshMessage by observable("")
    internal var refreshIsError by observable(false)

    internal var pullState by observable(RefreshViewState.IDLE)

    internal var pullRefreshRef: ViewRef<RefreshView>? = null

    override fun didInit() {
        super.didInit()
        reload(force = false)
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        reload(force = false)
    }

    internal fun reload(force: Boolean) {
        if (refreshing) {
            // 已有请求在跑：立刻收掉刷新头，否则它会一直转
            pullRefreshRef?.view?.endRefresh()
            return
        }
        refreshing = true
        // 刷新头箭头开始转（结束由 refreshing 变 false 自然停）
        refreshSpin.loop(REFRESH_SPIN_STEP_MS) { refreshing }
        refreshMessage = ""
        lifecycleScope.launch {
            try {
                applySnapshot()
                pageResult { HoldingCalendar.syncQuietly() }
                if (force) {
                    val updated = pageResult { DataUpdater.refreshNow() }
                    applySnapshot()
                    refreshMessage = if (updated) "行情数据已更新" else "数据已是最新"
                } else refreshMessage = "本地摘要已刷新"
                refreshIsError = false
            } catch (e: Throwable) {
                refreshIsError = true
                refreshMessage = "刷新失败，请重试"
            } finally {
                refreshing = false
                pullRefreshRef?.view?.endRefresh()
            }
            autoDismiss(refreshMessage)
        }
    }

    /** 下拉刷新入口：下拉即视为用户主动要最新数据，走 force 路径。 */
    internal fun reloadByPull() {
        reload(force = true)
    }

    /** 提示浮窗悬浮 5 秒后自动消失；期间若有新消息则以新消息为准。 */
    private fun autoDismiss(message: String) {
        if (message.isEmpty()) return
        lifecycleScope.launch {
            delay(5000)
            if (refreshMessage == message) refreshMessage = ""
        }
    }

    private fun applySnapshot() {
        HomeDashboardService.invalidate()
        val snapshot = HomeDashboardService.snapshot(force = true)
        headline = snapshot.brief.headline
        summary = snapshot.brief.summary
        marketLabel = snapshot.brief.marketLabel
        breadthRoll.rollTo(
            snapshot.brief.up.toDouble(),
            snapshot.brief.down.toDouble(),
            snapshot.brief.flat.toDouble(),
        )
        dataDate = snapshot.brief.dataDate
        watchSignals = runCatching { WatchStore.list().size }.getOrDefault(snapshot.brief.watchSignals)
        alertCount = runCatching { WatchStore.alerts().count { it.enabled } }.getOrDefault(snapshot.brief.alertCount)
        watchRoll.rollTo(watchSignals.toDouble(), alertCount.toDouble())
        focusItems.clear()
        focusItems.addAll(snapshot.focusItems)
    }

    internal fun open(route: String) {
        // 首页入口卡里既有底部 Tab 模块也有非 Tab 页面，由 openModule 按目的地挑转场
        openModule(route)
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    flexDirectionColumn()
                    backgroundColor(AppColor.BG)
                }
                homeTopBar(ctx)
                guideEntryCard(ctx)
                statusFeedback({ ctx.refreshMessage }, { ctx.refreshIsError })
                Scroller {
                    attr {
                        flex(1f)
                        flexDirectionColumn()
                        scrollEnable(true)
                        padding(left = 16f, right = 16f, bottom = 20f)
                    }
                    pullToRefresh(
                        bind = { ctx.pullRefreshRef = it },
                        label = { pullRefreshLabel(ctx.pullState, ctx.refreshing) },
                        onStateChange = { ctx.pullState = it },
                        onRefresh = { ctx.reloadByPull() },
                        spin = ctx.refreshSpin,
                        spinning = { ctx.refreshing },
                    )
                    marketBriefCard(ctx)
                    sectionTitle("研究工作台", "把重要动作拆开，减少首页拥挤")
                    researchGrid(ctx)
                    calendarEntryCard(ctx)
                    sectionTitle("今日关注", "提醒优先，其次是自选仓信号", onClick = { ctx.openModule(AppRoutes.WATCHLIST) })
                    vfor({ ctx.focusItems }) { item ->
                        focusRow(item)
                    }
                    Text {
                        attr {
                            text("行情与指标仅供研究参考，不构成投资建议")
                            fontSize(11f)
                            color(AppColor.TEXT_MUTED)
                            margin(top = 18f, bottom = 8f)
                            textAlignCenter()
                        }
                    }
                }
                appBottomNav(ctx, AppRoutes.HOME)
            }
        }
    }
}

private fun ViewContainer<*, *>.homeTopBar(ctx: HomeDashboardPage) {
    View {
        attr {
            padding(top = ctx.pagerData.statusBarHeight + 12f, left = 18f, right = 14f, bottom = 13f)
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            backgroundColor(AppColor.SURFACE)
        }
        View {
            attr { flex(1f) }
            Text {
                attr {
                    text("研究台")
                    fontSize(23f)
                    fontWeightBold()
                    color(AppColor.TITLE)
                }
            }
            Text {
                attr {
                    text("A股本地数据 · ${ctx.dataDate}")
                    fontSize(11f)
                    color(AppColor.TEXT_SUB)
                    marginTop(2f)
                }
            }
        }
        refreshButton({ ctx.refreshing }) { ctx.reload(force = true) }
    }
}

private fun ViewContainer<*, *>.marketBriefCard(ctx: HomeDashboardPage) {
    View {
        attr {
            marginTop(14f)
            padding(18f)
            borderRadius(18f)
            backgroundColor(AppColor.INK_PANEL)
        }
        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
            Text {
                attr {
                    text("今日盘面")
                    fontSize(12f)
                    color(AppColor.ON_DARK_ACCENT)
                    letterSpacing(1f)
                }
            }
            View { attr { flex(1f) } }
            View {
                attr { padding(5f, 9f, 5f, 9f); borderRadius(12f); backgroundColor(AppColor.INK_PANEL_ALT) }
                Text { attr { text(ctx.marketLabel); fontSize(11f); color(AppColor.ON_DARK_ACCENT_STRONG); fontWeightBold() } }
            }
        }
        Text {
            attr {
                text(ctx.headline)
                fontSize(24f)
                lineHeight(31f)
                fontWeightBold()
                color(Color.WHITE)
                marginTop(14f)
            }
        }
        Text {
            attr {
                text(ctx.summary)
                fontSize(13f)
                lineHeight(20f)
                color(AppColor.ON_DARK_SUB)
                marginTop(8f)
            }
        }
        Text {
            attr {
                text(ctx.breadthRoll.display)
                fontSize(12f)
                color(AppColor.ON_DARK_SUB)
                marginTop(14f)
            }
        }
    }
}

/**
 * 首页区块标题。样式来自共享的 [sectionHeader]——「18f 加粗标题 + 11f 灰色说明」
 * 这套层级在首页、风险中心是同一件事，各写一份迟早会漂。
 * [onClick] 非空时区块标题变为入口（如「今日关注」→ 自选仓）。
 */
private fun ViewContainer<*, *>.sectionTitle(
    title: String,
    note: String,
    onClick: (() -> Unit)? = null,
) {
    sectionHeader(title, note, onClick = onClick)
}

private fun ViewContainer<*, *>.researchGrid(ctx: HomeDashboardPage) {
    View {
        attr {
            flexDirectionColumn()
            accessibility("研究工作台，四个入口")
        }
        // 第一行：AI研究室 + 组合风险
        View {
            attr { flexDirectionRow(); marginBottom(12f) }
            researchModule(ctx, "AI 研究室", { "带本地行情上下文提问" }, "AI", AppColor.PRIMARY_BG_LIGHT, AppColor.PRIMARY, AppRoutes.CHAT)
            View { attr { width(12f) } }
            researchModule(ctx, "组合风险", { "仓位、行业与回撤" }, "盾", AppColor.WARNING_BG, AppColor.WARNING_TEXT, AppRoutes.RISK)
        }
        // 第二行：全市场 + 自选仓
        View {
            attr { flexDirectionRow(); marginBottom(12f) }
            researchModule(ctx, "全市场", { "搜索与涨跌幅排序" }, "势", AppColor.SUCCESS_BG, AppColor.SUCCESS, AppRoutes.MARKET)
            View { attr { width(12f) } }
            researchModule(ctx, "自选仓", { ctx.watchRoll.display }, "盯", AppColor.VIOLET_BG, AppColor.VIOLET, AppRoutes.WATCHLIST)
        }
    }
}

private fun ViewContainer<*, *>.researchModule(
    ctx: HomeDashboardPage,
    title: String,
    subtitle: () -> String,
    mark: String,
    tint: Long,
    accent: Long,
    route: String,
) {
    View {
        attr {
            flex(1f)
            minHeight(132f)
            padding(14f)
            borderRadius(16f)
            backgroundColor(AppColor.SURFACE)
            border(Border(1f, BorderStyle.SOLID, Color(AppColor.DIVIDER)))
            accessibility("打开$title，${subtitle()}")
            accessibilityRole(AccessibilityRole.BUTTON)
            accessibilityInfo(true, false)
        }
        event { click { ctx.open(route) } }
        View {
            attr { size(34f, 34f); borderRadius(10f); allCenter(); backgroundColor(tint) }
            Text { attr { text(mark); fontSize(if (mark == "AI") 12f else 15f); fontWeightBold(); color(accent) } }
        }
        Text { attr { text(title); fontSize(15f); fontWeightBold(); color(AppColor.TEXT_STRONG); marginTop(12f) } }
        Text { attr { text(subtitle()); fontSize(11f); lineHeight(16f); color(AppColor.TEXT_SUB_DEEP); marginTop(4f) } }
    }
}

private fun ViewContainer<*, *>.calendarEntryCard(ctx: HomeDashboardPage) {
    View {
        attr {
            marginTop(4f)
            padding(14f)
            borderRadius(16f)
            backgroundColor(AppColor.SURFACE)
            border(Border(1f, BorderStyle.SOLID, Color(AppColor.DIVIDER)))
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            accessibility("打开盈亏日历，查看每日持仓盈亏")
            accessibilityRole(AccessibilityRole.BUTTON)
            accessibilityInfo(true, false)
        }
        event { click { ctx.open(AppRoutes.CALENDAR) } }
        View {
            attr { size(34f, 34f); borderRadius(10f); allCenter(); backgroundColor(AppColor.SUCCESS_BG) }
            Text { attr { text("历"); fontSize(15f); fontWeightBold(); color(AppColor.SUCCESS) } }
        }
        View {
            attr { flex(1f); marginLeft(12f) }
            Text { attr { text("盈亏日历"); fontSize(15f); fontWeightBold(); color(AppColor.TEXT_STRONG) } }
            Text { attr { text("行情更新后自动记下当天持仓，对照沪深300"); fontSize(11f); color(AppColor.TEXT_SUB_DEEP); marginTop(3f) } }
        }
        Text { attr { text("›"); fontSize(26f); color(AppColor.TEXT_MUTED) } }
    }
}

private fun ViewContainer<*, *>.guideEntryCard(ctx: HomeDashboardPage) {
    View {
        attr {
            margin(top = 10f, left = 16f, right = 16f)
            padding(14f)
            borderRadius(16f)
            backgroundColor(AppColor.INK_PANEL)
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            accessibility("打开使用指南，功能介绍、提问示例与常见问题")
            accessibilityRole(AccessibilityRole.BUTTON)
            accessibilityInfo(true, false)
        }
        event { click { ctx.open(AppRoutes.GUIDE) } }
        View {
            attr { size(34f, 34f); borderRadius(10f); allCenter(); backgroundColor(AppColor.INK_PANEL_ALT) }
            Text { attr { text("?"); fontSize(17f); fontWeightBold(); color(AppColor.ON_DARK_ACCENT_STRONG) } }
        }
        View {
            attr { flex(1f); marginLeft(12f) }
            Text { attr { text("使用指南"); fontSize(15f); fontWeightBold(); color(Color.WHITE) } }
            Text { attr { text("功能介绍 · 提问示例 · 常见问题"); fontSize(11f); color(AppColor.ON_DARK_ACCENT); marginTop(3f) } }
        }
        Text { attr { text("›"); fontSize(26f); color(AppColor.ON_DARK_ACCENT) } }
    }
}

private fun ViewContainer<*, *>.focusRow(item: DashboardFocusItem) {
    View {
        attr {
            marginBottom(9f)
            padding(14f)
            borderRadius(14f)
            backgroundColor(AppColor.SURFACE)
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
        }
        View {
            attr {
                width(4f)
                height(42f)
                borderRadius(2f)
                backgroundColor(if (item.tag == "提醒触发") AppColor.DANGER else AppColor.PRIMARY_SOFT)
                marginRight(12f)
            }
        }
        View {
            attr { flex(1f) }
            Text { attr { text(item.title); fontSize(14f); fontWeightBold(); color(AppColor.TEXT_STRONG); lineHeight(20f) } }
            Text { attr { text(item.subtitle); fontSize(11f); color(AppColor.TEXT_SUB); marginTop(3f) } }
        }
        Text { attr { text(item.tag); fontSize(10f); color(AppColor.TEXT_SUB_DEEP); marginLeft(8f) } }
    }
}
