package com.kuikly.stock.pages

import com.kuikly.stock.home.DashboardFocusItem
import com.kuikly.stock.home.HomeDashboardService
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.layout.FlexWrap
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

@Page(AppRoutes.HOME)
class HomeDashboardPage : Pager() {
    internal var headline by observable("正在整理今日市场…")
    internal var summary by observable("本页只读取本地行情，不会自动调用付费 AI。")
    internal var marketLabel by observable("数据准备中")
    internal var breadth by observable("上涨 --  ·  下跌 --  ·  平盘 --")
    internal var dataDate by observable("待更新")
    internal var watchSignals by observable(0)
    internal var alertCount by observable(0)
    internal var focusItems: ObservableList<DashboardFocusItem> by observableList()

    override fun didInit() {
        super.didInit()
        reload(force = false)
    }

    internal fun reload(force: Boolean) {
        val snapshot = HomeDashboardService.snapshot(force)
        headline = snapshot.brief.headline
        summary = snapshot.brief.summary
        marketLabel = snapshot.brief.marketLabel
        breadth = "上涨 ${snapshot.brief.up}  ·  下跌 ${snapshot.brief.down}  ·  平盘 ${snapshot.brief.flat}"
        dataDate = snapshot.brief.dataDate
        watchSignals = snapshot.brief.watchSignals
        alertCount = snapshot.brief.alertCount
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
                Scroller {
                    attr {
                        flex(1f)
                        flexDirectionColumn()
                        scrollEnable(true)
                        padding(left = 16f, right = 16f, bottom = 20f)
                    }
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
        View {
            attr {
                minWidth(44f)
                height(44f)
                allCenter()
                backgroundColor(0xFFF0F5FC)
                borderRadius(22f)
            }
            event { click { ctx.reload(force = true) } }
            Text { attr { text("刷新"); fontSize(12f); color(0xFF0E67D1); fontWeightBold() } }
        }
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
                text(ctx.breadth)
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
        attr { flexDirectionRow(); flexWrap(FlexWrap.WRAP); justifyContent(FlexJustifyContent.SPACE_BETWEEN) }
        researchModule(ctx, "AI 研究室", "带本地行情上下文提问", "AI", 0xFFE8F2FF, 0xFF0E67D1, AppRoutes.CHAT)
        researchModule(ctx, "组合风险", "仓位、行业与回撤", "盾", 0xFFFFF1E6, 0xFFB85C00, AppRoutes.RISK)
        researchModule(ctx, "全市场", "搜索与涨跌幅排序", "势", 0xFFEAF8F0, 0xFF17834E, AppRoutes.MARKET)
        researchModule(ctx, "自选盯盘", "${ctx.watchSignals} 个信号 · ${ctx.alertCount} 个提醒", "盯", 0xFFF2EDFF, 0xFF6650A4, AppRoutes.WATCHLIST)
    }
}

private fun ViewContainer<*, *>.researchModule(
    ctx: HomeDashboardPage,
    title: String,
    subtitle: String,
    mark: String,
    tint: Long,
    accent: Long,
    route: String,
) {
    View {
        attr {
            width((ctx.pagerData.pageViewWidth - 44f) / 2f)
            minHeight(126f)
            marginBottom(10f)
            padding(14f)
            borderRadius(15f)
            backgroundColor(Color.WHITE)
        }
        event { click { ctx.open(route) } }
        View {
            attr { size(34f, 34f); borderRadius(10f); allCenter(); backgroundColor(tint) }
            Text { attr { text(mark); fontSize(if (mark == "AI") 12f else 15f); fontWeightBold(); color(accent) } }
        }
        Text { attr { text(title); fontSize(15f); fontWeightBold(); color(0xFF172A43); marginTop(12f) } }
        Text { attr { text(subtitle); fontSize(11f); lineHeight(16f); color(0xFF788494); marginTop(4f) } }
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
