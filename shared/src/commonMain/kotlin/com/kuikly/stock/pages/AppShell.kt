package com.kuikly.stock.pages

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
            View {
                attr { minWidth(44f); height(44f); allCenter(); accessibility("刷新$title"); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(true, false) }
                event { click { trailingAction() } }
                Text { attr { text("刷新"); fontSize(12f); fontWeightBold(); color(0xFF0E67D1) } }
            }
        }
    }
}
