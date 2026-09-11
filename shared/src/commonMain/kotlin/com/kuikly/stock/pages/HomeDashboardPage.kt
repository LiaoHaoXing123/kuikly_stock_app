package com.kuikly.stock.pages

import com.kuikly.stock.base.BasePager
import com.kuikly.stock.base.NumberRoll

import com.kuikly.stock.data.DataUpdater
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
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.RefreshView
import com.tencent.kuikly.core.views.RefreshViewState
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

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

    /** 自选盯盘的「N 个信号 · M 个提醒」，同样滚一下。 */
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
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(route, JSONObject())
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    flexDirectionColumn()
                    backgroundColor(0xFFF4F7FB)
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
                    sectionTitle("今日关注", "提醒优先，其次是自选信号")
                    vfor({ ctx.focusItems }) { item ->
                        focusRow(item)
                    }
                    Text {
                        attr {
                            text("行情与指标仅供研究参考，不构成投资建议")
                            fontSize(11f)
                            color(0xFF929CAB)
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
            backgroundColor(Color.WHITE)
        }
        View {
            attr { flex(1f) }
            Text {
                attr {
                    text("研究台")
                    fontSize(23f)
                    fontWeightBold()
                    color(0xFF0B1F3A)
                }
            }
            Text {
                attr {
                    text("A股本地数据 · ${ctx.dataDate}")
                    fontSize(11f)
                    color(0xFF7F8998)
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
            backgroundColor(0xFF0B2B50)
        }
        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
            Text {
                attr {
                    text("今日盘面")
                    fontSize(12f)
                    color(0xFF9EC8F5)
                    letterSpacing(1f)
                }
            }
            View { attr { flex(1f) } }
            View {
                attr { padding(5f, 9f, 5f, 9f); borderRadius(12f); backgroundColor(0xFF154875) }
                Text { attr { text(ctx.marketLabel); fontSize(11f); color(0xFFD7EAFF); fontWeightBold() } }
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
                color(0xFFC7D8EA)
                marginTop(8f)
            }
        }
        Text {
            attr {
                text(ctx.breadthRoll.display)
                fontSize(12f)
                color(0xFF89B9E8)
                marginTop(14f)
            }
        }
    }
}

private fun ViewContainer<*, *>.sectionTitle(title: String, note: String) {
    View {
        attr { margin(top = 22f, bottom = 10f) }
        Text { attr { text(title); fontSize(18f); fontWeightBold(); color(0xFF14263D) } }
        Text { attr { text(note); fontSize(11f); color(0xFF8A94A3); marginTop(2f) } }
    }
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
            researchModule(ctx, "AI 研究室", { "带本地行情上下文提问" }, "AI", 0xFFE8F2FF, 0xFF0E67D1, AppRoutes.CHAT)
            View { attr { width(12f) } }
            researchModule(ctx, "组合风险", { "仓位、行业与回撤" }, "盾", 0xFFFFF1E6, 0xFFB85C00, AppRoutes.RISK)
        }
        // 第二行：全市场 + 自选盯盘
        View {
            attr { flexDirectionRow(); marginBottom(12f) }
            researchModule(ctx, "全市场", { "搜索与涨跌幅排序" }, "势", 0xFFEAF8F0, 0xFF17834E, AppRoutes.MARKET)
            View { attr { width(12f) } }
            researchModule(ctx, "自选盯盘", { ctx.watchRoll.display }, "盯", 0xFFF2EDFF, 0xFF6650A4, AppRoutes.WATCHLIST)
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
            backgroundColor(Color.WHITE)
            border(Border(1f, BorderStyle.SOLID, Color(0xFFE9EEF5)))
            accessibility("打开$title，${subtitle()}")
            accessibilityRole(AccessibilityRole.BUTTON)
            accessibilityInfo(true, false)
        }
        event { click { ctx.open(route) } }
        View {
            attr { size(34f, 34f); borderRadius(10f); allCenter(); backgroundColor(tint) }
            Text { attr { text(mark); fontSize(if (mark == "AI") 12f else 15f); fontWeightBold(); color(accent) } }
        }
        Text { attr { text(title); fontSize(15f); fontWeightBold(); color(0xFF172A43); marginTop(12f) } }
        Text { attr { text(subtitle()); fontSize(11f); lineHeight(16f); color(0xFF788494); marginTop(4f) } }
    }
}

private fun ViewContainer<*, *>.guideEntryCard(ctx: HomeDashboardPage) {
    View {
        attr {
            margin(top = 10f, left = 16f, right = 16f)
            padding(14f)
            borderRadius(16f)
            backgroundColor(0xFF0B2B50)
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            accessibility("打开使用指南，功能介绍、提问示例与常见问题")
            accessibilityRole(AccessibilityRole.BUTTON)
            accessibilityInfo(true, false)
        }
        event { click { ctx.open(AppRoutes.GUIDE) } }
        View {
            attr { size(34f, 34f); borderRadius(10f); allCenter(); backgroundColor(0xFF154875) }
            Text { attr { text("?"); fontSize(17f); fontWeightBold(); color(0xFFD7EAFF) } }
        }
        View {
            attr { flex(1f); marginLeft(12f) }
            Text { attr { text("使用指南"); fontSize(15f); fontWeightBold(); color(Color.WHITE) } }
            Text { attr { text("功能介绍 · 提问示例 · 常见问题"); fontSize(11f); color(0xFF9EC8F5); marginTop(3f) } }
        }
        Text { attr { text("›"); fontSize(26f); color(0xFF9EC8F5) } }
    }
}

private fun ViewContainer<*, *>.focusRow(item: DashboardFocusItem) {
    View {
        attr {
            marginBottom(9f)
            padding(14f)
            borderRadius(14f)
            backgroundColor(Color.WHITE)
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
        }
        View {
            attr {
                width(4f)
                height(42f)
                borderRadius(2f)
                backgroundColor(if (item.tag == "提醒触发") 0xFFE55050 else 0xFF2E7BC4)
                marginRight(12f)
            }
        }
        View {
            attr { flex(1f) }
            Text { attr { text(item.title); fontSize(14f); fontWeightBold(); color(0xFF1A2D45); lineHeight(20f) } }
            Text { attr { text(item.subtitle); fontSize(11f); color(0xFF8993A1); marginTop(3f) } }
        }
        Text { attr { text(item.tag); fontSize(10f); color(0xFF607188); marginLeft(8f) } }
    }
}
