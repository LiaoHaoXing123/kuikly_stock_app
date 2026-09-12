// 导航骨架：路由常量、模块间跳转、底部 Tab 栏、页面标题栏、刷新按钮。
//
// 把这几件放在一起，是因为它们共享同一套「页面骨架」样式（白底、1dp 分割线、
// 44dp 最小热区），分开写很容易各自漂移。

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

/** 全部页面路由。新增页面先在这里登记，避免路由字符串散落成魔法值。 */
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

private data class AppNavItem(val route: String, val icon: String, val label: String)

private val APP_NAV_ITEMS = listOf(
    AppNavItem(AppRoutes.HOME, "⌂", "首页"),
    AppNavItem(AppRoutes.MARKET, "⌁", "行情"),
    AppNavItem(AppRoutes.CHAT, "AI", "研究"),
    AppNavItem(AppRoutes.WATCHLIST, "☆", "自选"),
    AppNavItem(AppRoutes.PROFILE, "●", "我的"),
)

/** 底部 Tab 的五个页面。它们是**同级**模块，不是彼此的下一层，转场要按同级规则走。 */
private val MODULE_ROUTES = APP_NAV_ITEMS.map { it.route }.toSet()

/** `openPage` 里用来告诉宿主「这次导航属于哪一类」的键。 */
internal const val NAV_TRANSITION_KEY = "transition"

/**
 * 同级目的地之间的转场标记：淡入淡出。
 *
 * 依据：Material 的转场选型看的是「两个目的地之间的关系」，同级目的地（bottom
 * navigation 的各个 tab）用 fade through；表示层级下钻才用带方向的横切。
 * 详见 [openModule]。
 */
internal const val NAV_TRANSITION_FADE = "fade"

/**
 * 切到某个模块（底部 Tab 的页面）。
 *
 * 为什么不能直接 `openPage`：转场动画由宿主决定，Android 是 Activity 之间的系统默认转场、
 * iOS 是 `UINavigationController` 的 push，两者都是**横切**。横切表达的是「进入下一层」，
 * 用在详情页上是对的；但底部 Tab 之间是同级切换，横切会让模块看起来像被压进了导航栈
 * （返回时还朝反方向再滑一次），这是「切换不平滑」的来源。
 *
 * 所以这里给同级目的地打上 [NAV_TRANSITION_FADE] 标记，由各平台宿主换成淡入淡出：
 *   - Android：`NavTransition` 换成「新页面淡入」（Material fade through 的入场段）；
 *   - iOS：`KRRouterHandler` 换成 cross-dissolve（打开和关闭都是）；
 *   - 鸿蒙：`pages/Index.pageTransition` 按标记走 opacity，因为 `router.pushUrl` 没有动画入参。
 *
 * 详情页等层级下钻**不打标**。宿主按 iOS push/pop 做方向性横切：
 * 进入时新页从右侧滑入（旧页视差左移），返回时当前页从右侧滑出。
 * 不再依赖各系统默认 Activity / pageTransition 观感（Android 默认常是淡入+缩放，方向不明）。
 */
internal fun Pager.openModule(route: String, params: JSONObject = JSONObject()) {
    val data = if (route in MODULE_ROUTES) params.put(NAV_TRANSITION_KEY, NAV_TRANSITION_FADE) else params
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(route, data)
}

/** 底部 Tab 栏。五个 Tab 是同级路由，切换走淡入淡出（见 [openModule]）。 */
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
                Text {
                    attr {
                        text(item.icon)
                        fontSize(if (item.icon == "AI") 12f else 20f)
                        fontWeightBold()
                        color(if (selected) AppColor.PRIMARY else AppColor.TEXT_SUB)
                    }
                }
                Text {
                    attr {
                        text(item.label)
                        fontSize(AppFont.CAPTION)
                        marginTop(2f)
                        color(if (selected) AppColor.PRIMARY else AppColor.TEXT_SUB)
                        if (selected) fontWeightBold()
                    }
                }
            }
        }
    }
}

/**
 * 二级页面的标题栏：返回箭头 + 标题/副标题 +（可选）右侧动作。
 *
 * @param trailingAction 右侧动作；传 null 就只显示返回。给了它就会渲染一个 [refreshButton]。
 */
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
            height(AppSize.TITLE_BAR + ctx.pagerData.statusBarHeight)
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            backgroundColor(AppColor.SURFACE)
        }
        View {
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

/**
 * 文本刷新按钮。自带 44dp 热区与「刷新中」态。
 *
 * @param foreground 常态文字色；压在深色底上时传 [AppColor.ON_DARK]。
 */
internal fun ViewContainer<*, *>.refreshButton(
    refreshing: () -> Boolean,
    label: () -> String = { "刷新" },
    foreground: Long = AppColor.PRIMARY,
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
                color(if (refreshing()) AppColor.TEXT_SUB else foreground)
            }
        }
    }
}
