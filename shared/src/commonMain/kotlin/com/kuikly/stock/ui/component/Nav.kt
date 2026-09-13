package com.kuikly.stock.ui.component

import com.kuikly.stock.ui.theme.AppColor
import com.kuikly.stock.ui.theme.AppFont
import com.kuikly.stock.ui.theme.AppSize
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.AccessibilityRole
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent
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
    const val GUIDE = "user_guide"
    const val CALENDAR = "holding_calendar"
}

private data class AppNavItem(val route: String, val label: String)

private val APP_NAV_ITEMS = listOf(
    AppNavItem(AppRoutes.HOME, "首页"),
    AppNavItem(AppRoutes.MARKET, "行情"),
    AppNavItem(AppRoutes.CHAT, "研究"),
    AppNavItem(AppRoutes.WATCHLIST, "自选仓"),
    AppNavItem(AppRoutes.PROFILE, "我的"),
)

private val MODULE_ROUTES = APP_NAV_ITEMS.map { it.route }.toSet()

internal const val NAV_TRANSITION_KEY = "transition"

internal const val NAV_TRANSITION_FADE = "fade"

internal fun Pager.openModule(route: String, params: JSONObject = JSONObject()) {
    val data = if (route in MODULE_ROUTES) params.put(NAV_TRANSITION_KEY, NAV_TRANSITION_FADE) else params
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(route, data)
}

internal fun ViewContainer<*, *>.appBottomNav(ctx: Pager, activeRoute: String) {
    View {
        attr {
            height(AppSize.NAV_BAR)
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            backgroundColor(AppColor.SURFACE)
            borderTop(Border(1f, BorderStyle.SOLID, Color(AppColor.DIVIDER)))
        }
        APP_NAV_ITEMS.forEach { item ->
            val selected = item.route == activeRoute
            View {
                attr {
                    flex(1f)
                    height(56f)
                    alignItems(FlexAlign.CENTER)
                    justifyContent(FlexJustifyContent.CENTER)
                    accessibility("${item.label}导航${if (selected) "，当前页面" else ""}")
                    accessibilityRole(AccessibilityRole.BUTTON)
                    accessibilityInfo(clickable = !selected, longClickable = false)
                }
                event {
                    click {
                        if (!selected) {
                            ctx.openModule(item.route)
                        }
                    }
                }
                navIcon(item.route, selected)
                Text {
                    attr {
                        text(item.label)
                        fontSize(AppFont.CAPTION)
                        marginTop(4f)
                        color(if (selected) AppColor.PRIMARY else AppColor.TEXT_SUB)
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
    showBack: Boolean = true,
    trailingAction: (() -> Unit)? = null,
) {
    View {
        attr {
            padding(top = ctx.pagerData.statusBarHeight, left = if (showBack) 6f else 18f, right = 10f)
            height(AppSize.TITLE_BAR + ctx.pagerData.statusBarHeight)
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            backgroundColor(AppColor.SURFACE)
        }
        if (showBack) View {
            attr {
                size(AppSize.TOUCH_MIN, AppSize.TOUCH_MIN)
                allCenter()
                accessibility("返回")
                accessibilityRole(AccessibilityRole.BUTTON)
                accessibilityInfo(true, false)
            }
            event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
            Text { attr { text("‹"); fontSize(32f); color(AppColor.TEXT_STRONG) } }
        }
        View {
            attr { flex(1f) }
            Text { attr { text(title); fontSize(AppFont.HEAD); fontWeightBold(); color(AppColor.TITLE) } }
            Text { attr { text(subtitle); fontSize(10f); color(AppColor.TEXT_SUB); marginTop(1f) } }
        }
        if (trailingAction != null) {
            refreshButton(refreshing, { "刷新$title" }, action = trailingAction)
        }
    }
}

internal fun ViewContainer<*, *>.refreshButton(
    refreshing: () -> Boolean,
    label: () -> String = { "刷新" },
    foreground: Long? = null,
    action: () -> Unit,
) {
    View {
        attr {
            minWidth(64f)
            height(AppSize.TOUCH_MIN)
            allCenter()
            accessibility(if (refreshing()) "正在刷新" else label())
            accessibilityRole(AccessibilityRole.BUTTON)
            accessibilityInfo(!refreshing(), false)
        }
        event { click { if (!refreshing()) action() } }
        Text {
            attr {
                text(if (refreshing()) "刷新中…" else "刷新")
                fontSize(AppFont.NOTE)
                fontWeightBold()
                color(if (refreshing()) AppColor.TEXT_SUB else foreground ?: AppColor.PRIMARY)
            }
        }
    }
}
