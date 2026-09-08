package com.kuikly.stock.pages

import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.attr.AccessibilityRole
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

internal object AppRoutes {
    const val HOME = "home_dashboard"
    const val MARKET = "stock_list"
    const val CHAT = "chat_main"
    const val WATCHLIST = "watchlist"
    const val PROFILE = "profile"
    const val RISK = "risk_center"
    const val API_CONFIG = "api_config"
}

private data class AppNavItem(val route: String, val icon: String, val label: String)

private val APP_NAV_ITEMS = listOf(
    AppNavItem(AppRoutes.HOME, "⌂", "首页"),
    AppNavItem(AppRoutes.MARKET, "⌁", "行情"),
    AppNavItem(AppRoutes.CHAT, "AI", "研究"),
    AppNavItem(AppRoutes.WATCHLIST, "☆", "自选"),
    AppNavItem(AppRoutes.PROFILE, "●", "我的"),
)

internal fun ViewContainer<*, *>.appBottomNav(ctx: Pager, activeRoute: String) {
    View {
        attr {
            height(64f)
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            backgroundColor(Color.WHITE)
            borderTop(Border(1f, BorderStyle.SOLID, Color(0xFFE7EBF2)))
        }
        APP_NAV_ITEMS.forEach { item ->
            val selected = item.route == activeRoute
            View {
                attr {
                    flex(1f)
                    height(56f)
                    alignItems(FlexAlign.CENTER)
                    justifyContent(com.tencent.kuikly.core.layout.FlexJustifyContent.CENTER)
                    accessibility("${item.label}导航${if (selected) "，当前页面" else ""}")
                    accessibilityRole(AccessibilityRole.BUTTON)
                    accessibilityInfo(clickable = !selected, longClickable = false)
                }
                event {
                    click {
                        if (!selected) {
                            ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                                .openPage(item.route, JSONObject())
                        }
                    }
                }
                Text {
                    attr {
                        text(item.icon)
                        fontSize(if (item.icon == "AI") 12f else 20f)
                        fontWeightBold()
                        color(if (selected) 0xFF0E67D1 else 0xFF7F8998)
                    }
                }
                Text {
                    attr {
                        text(item.label)
                        fontSize(11f)
                        marginTop(2f)
                        color(if (selected) 0xFF0E67D1 else 0xFF7F8998)
                        if (selected) fontWeightBold()
                    }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.pageTitleBar(
    ctx: Pager,
    title: String,
    subtitle: String,
    refreshing: () -> Boolean = { false },
    trailingAction: (() -> Unit)? = null,
) {
    View {
        attr {
            padding(top = ctx.pagerData.statusBarHeight, left = 6f, right = 10f)
            height(58f + ctx.pagerData.statusBarHeight)
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            backgroundColor(Color.WHITE)
        }
        View {
            attr { size(44f, 44f); allCenter(); accessibility("返回"); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(true, false) }
            event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
            Text { attr { text("‹"); fontSize(32f); color(0xFF243A55) } }
        }
        View {
            attr { flex(1f) }
            Text { attr { text(title); fontSize(18f); fontWeightBold(); color(0xFF12263F) } }
            Text { attr { text(subtitle); fontSize(10f); color(0xFF8792A1); marginTop(1f) } }
        }
        if (trailingAction != null) {
            refreshButton(refreshing, { "刷新$title" }, action = trailingAction)
        }
    }
}


internal fun ViewContainer<*, *>.refreshButton(refreshing: () -> Boolean, label: () -> String = { "刷新" }, foreground: Long = 0xFF0E67D1, action: () -> Unit) {
    View {
        attr { minWidth(64f); height(44f); allCenter(); accessibility(if (refreshing()) "正在刷新" else label()); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(!refreshing(), false) }
        event { click { if (!refreshing()) action() } }
        Text { attr { text(if (refreshing()) "刷新中…" else "刷新"); fontSize(12f); fontWeightBold(); color(if (refreshing()) 0xFF8792A1 else foreground) } }
    }
}

internal fun ViewContainer<*, *>.statusFeedback(message: () -> String, isError: () -> Boolean = { false }) {
    vif({ message().isNotEmpty() }) {
        View {
            attr { padding(12f); margin(8f); borderRadius(10f); backgroundColor(if (isError()) 0xFFFFF0F0 else 0xFFE8F2FF) }
            Text { attr { text(message()); fontSize(12f); lineHeight(18f); color(if (isError()) 0xFFC34C4C else 0xFF165D9E) } }
        }
    }
}

internal fun ViewContainer<*, *>.selectionChip(label: String, selected: () -> Boolean, action: () -> Unit) {
    View {
        attr {
            marginRight(6f); marginBottom(6f); minHeight(44f); allCenter()
            padding(left = 10f, top = 4f, right = 10f, bottom = 4f)
            backgroundColor(if (selected()) 0xFF1976D2 else 0xFFE3F2FD); borderRadius(13f)
            accessibility("$label${if (selected()) "，已选择" else ""}")
            accessibilityRole(AccessibilityRole.CHECKBOX); accessibilityInfo(true, false)
        }
        event { click { action() } }
        Text { attr { text(label); fontSize(12f); fontWeightBold(); color(if (selected()) 0xFFFFFFFF else 0xFF1976D2) } }
    }
}
