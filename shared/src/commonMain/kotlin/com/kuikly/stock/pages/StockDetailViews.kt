// 个股详情页 —— 页面级状态视图。
// 自 StockDetailPage.kt 拆出：AI 分析中/未分析、确认弹窗、Toast、加载与错误态。

package com.kuikly.stock.pages

import com.kuikly.stock.base.AI_DOT_STEP_MS
import com.kuikly.stock.base.BasePager
import com.kuikly.stock.base.Overlay
import com.kuikly.stock.base.overlayEnterExit
import com.kuikly.stock.base.aiDotWaveDots
import com.kuikly.stock.base.SKELETON_BG_ON_DARK
import com.kuikly.stock.base.SKELETON_BG_STRONG
import com.kuikly.stock.base.pressFeedback
import com.kuikly.stock.base.pressedScale
import com.kuikly.stock.base.skeletonBlock

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.attr.AccessibilityRole
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.velse
import com.tencent.kuikly.core.directives.velseif
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.layout.FlexWrap
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.TextAlign
import com.kuikly.stock.data.StockRepository
import com.kuikly.stock.data.WatchStore
import com.kuikly.stock.data.nowMillis
import com.kuikly.stock.data.AIVerdict
import com.kuikly.stock.ai.protocol.VerdictSynthesizer
import com.kuikly.stock.network.DeepSeekApi
import com.kuikly.stock.data.ConclusionAlertFactory
import com.kuikly.stock.data.PriceAlertRule
import com.tencent.kuikly.core.coroutines.delay
import com.tencent.kuikly.core.coroutines.launch
import com.tencent.kuiklybase.KuiklyMarkdown
import com.kuikly.stock.data.fmt0
import com.kuikly.stock.data.fmt2
import com.kuikly.stock.data.fmt3
import com.kuikly.stock.data.fmtSigned2
import com.kuikly.stock.data.fmtSignedPct
import kotlin.math.abs

internal fun ViewContainer<*, *>.analyzingView(ctx: StockDetailPage) {
    View {
        attr {
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            padding(top = 20f, left = 16f, bottom = 20f, right = 16f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(12f)
        }

        Text {
            attr {
                text("AI 正在分析中...")
                fontSize(14f)
                color(0xFF666666)
            }
        }

        Text {
            attr {
                text("正在结合K线、指标与盘口数据生成结构化研判")
                fontSize(12f)
                color(0xFF999999)
                marginTop(6f)
                textAlignCenter()
            }
        }

        View { attr { marginTop(12f) } }
        aiDotWaveDots(ctx.aiDotWave)
    }
}

internal fun ViewContainer<*, *>.notAnalyzedView(ctx: StockDetailPage) {
    View {
        attr {
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            padding(top = 20f, left = 16f, bottom = 20f, right = 16f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(12f)
        }

        Text {
            attr {
                text("尚未进行 AI 分析")
                fontSize(14f)
                color(0xFF666666)
            }
        }

        Text {
            attr {
                text("AI 将结合K线走势、技术指标与盘口数据，生成带关键价位的结构化研判，并与K线联动")
                fontSize(11f)
                color(0xFF999999)
                marginTop(6f)
                textAlignCenter()
                lineHeight(16f)
            }
        }

        View {
            attr {
                marginTop(14f)
                padding(top = 10f, left = 28f, bottom = 10f, right = 28f)
                backgroundColor(0xFF1976D2)
                borderRadius(20f)
                pressedScale(ctx.press, AI_START_TAG, normal = 1f, pressed = 0.97f)
                accessibility("开始 AI 分析")
                accessibilityRole(AccessibilityRole.BUTTON)
                accessibilityInfo(true, false)
            }
            event {
                pressFeedback(ctx.press, AI_START_TAG)
                click {
                    ctx.press.releaseAll()
                    ctx.triggerAIAnalysis()
                }
            }
            Text {
                attr {
                    text("开始 AI 分析")
                    fontSize(14f)
                    fontWeightBold()
                    color(0xFFFFFFFF)
                }
            }
        }
    }
}

private const val AI_START_TAG = "detail_ai_start"

/**
 * 详情页确认弹窗。
 *
 * 入场：卡片按 [OVERLAY_ENTER_ANIMATION] 从 0.94 倍 / 全透明推到原位。
 * 驱动值是 [Overlay]：显隐和驱动值绑在同一个对象上，调用点写不出「只改一半」的代码。
 */
internal fun ViewContainer<*, *>.detailAlertConfirmDialog(ctx: StockDetailPage) {
    View {
        attr { absolutePositionAllZero(); backgroundColor(0x88000000); allCenter() }
        View {
            attr {
                overlayEnterExit(ctx.alertOverlay)
            }
            Text { attr { text("确认创建价格提醒"); fontSize(18f); fontWeightBold(); color(0xFF172A43) } }
            Text { attr { text("${ctx.pendingAlertName} · ${ctx.pendingAlertCode}"); fontSize(13f); color(0xFF697789); marginTop(9f) } }
            View { attr { padding(14f); marginTop(12f); borderRadius(12f); backgroundColor(0xFFF4F7FB) }
                Text { attr { text(if (ctx.pendingAlertType == 1) "价格跌至或低于" else "价格涨至或高于"); fontSize(11f); color(0xFF7A8797) } }
                Text { attr { text("¥ ${fmt2(ctx.pendingAlertValue)}"); fontSize(23f); fontWeightBold(); color(0xFF173C64); marginTop(4f) } }
            }
            Text { attr { text("提醒在行情数据刷新时检查，可能存在延迟。"); fontSize(11f); color(0xFF8A94A1); marginTop(10f) } }
            View { attr { flexDirectionRow(); marginTop(16f) }
                View { attr { flex(1f); height(44f); allCenter(); borderRadius(12f); backgroundColor(0xFFF0F2F5); pressedScale(ctx.press, ALERT_CANCEL_TAG) }; event { pressFeedback(ctx.press, ALERT_CANCEL_TAG); click { ctx.press.releaseAll(); ctx.dismissAlertConfirm() } }; Text { attr { text("取消"); fontSize(13f); color(0xFF697586) } } }
                View { attr { width(10f) } }
                View { attr { flex(1f); height(44f); allCenter(); borderRadius(12f); backgroundColor(0xFF0E67D1); pressedScale(ctx.press, ALERT_OK_TAG, normal = 1f, pressed = 0.97f); accessibility("确认创建价格提醒"); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(true, false) }; event { pressFeedback(ctx.press, ALERT_OK_TAG); click { ctx.press.releaseAll(); ctx.confirmAlert() } }; Text { attr { text("确认创建"); fontSize(13f); fontWeightBold(); color(Color.WHITE) } } }
            }
        }
    }
}

private const val ALERT_CANCEL_TAG = "detail_alert_cancel"
private const val ALERT_OK_TAG = "detail_alert_ok"

internal fun ViewContainer<*, *>.detailAiToast(ctx: StockDetailPage) {
    View {
        attr {
            absolutePosition(top = 70f, left = 0f, right = 0f)
            alignItems(FlexAlign.CENTER)
        }
        event { click { ctx.aiErrorNotice = "" } }
        View {
            attr {
                maxWidth(ctx.pagerData.pageViewWidth - 60f)
                backgroundColor(0xE60E67D1)
                borderRadius(10f)
                padding(left = 14f, top = 8f, right = 14f, bottom = 8f)
            }
            Text {
                attr {
                    text(ctx.aiErrorNotice)
                    fontSize(12f)
                    color(0xFFFFFFFF)
                    textAlignCenter()
                }
            }
        }
    }
}

/**
 * 详情页首屏骨架。
 *
 * 关键不是好看，是**先把版式占住**：顶栏、实时行情卡、图表区的位置和尺寸
 * 与真实内容严格对齐，数据到达时只是「填充」，页面不跳、眼睛不用重新找焦点。
 * 原来的实现是一行居中的「加载中...」，信息量为零，且让整页在数据前后
 * 呈现两个完全不同的版式。
 *
 * 个股详情与指数详情版式同族（顶栏 + 行情卡 + 图表区），所以两边共用一份。
 */
internal fun ViewContainer<*, *>.stockDetailLoadingView(ctx: BasePager) {
    val sweep = ctx.skeletonPulse
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
            backgroundColor(0xFFF5F5F5)
        }

        // 1) 顶栏（与 detailNavigationBar 同高、同底色）
        View {
            attr {
                flexDirectionRow()
                alignItems(FlexAlign.CENTER)
                backgroundColor(0xFF1976D2)
                paddingTop(ctx.pagerData.statusBarHeight)
                height(48f + ctx.pagerData.statusBarHeight)
            }
            View {
                attr { marginLeft(16f) }
                skeletonBlock(height = 16f, w = 52f, color = SKELETON_BG_ON_DARK, sweep = sweep)
            }
            View {
                attr { marginLeft(12f) }
                skeletonBlock(height = 16f, w = 84f, color = SKELETON_BG_ON_DARK, sweep = sweep)
            }
        }

        // 2) 实时行情卡（与 realtimeCard 同外边距、同内边距、同圆角）
        View {
            attr {
                flexDirectionColumn()
                margin(4f, 12f, 4f, 12f)
                padding(top = 12f, left = 16f, bottom = 12f, right = 16f)
                backgroundColor(0xFFFFFFFF)
                borderRadius(10f)
            }

            skeletonBlock(height = 15f, w = 72f, sweep = sweep) // 「实时行情」标题

            View {
                attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginTop(14f) }
                skeletonBlock(height = 26f, w = 96f, color = SKELETON_BG_STRONG, sweep = sweep) // 最新价
                View { attr { width(16f) } }
                skeletonBlock(height = 15f, w = 56f, sweep = sweep) // 涨跌额
                View { attr { width(16f) } }
                skeletonBlock(height = 15f, w = 56f, sweep = sweep) // 涨跌幅
            }

            // 开/昨收/最高/最低 + 量/额/市盈率/市净率，共 8 格，列宽与 quoteItem 一致
            View {
                attr { flexDirectionRow(); flexWrap(FlexWrap.WRAP); marginTop(12f) }
                repeat(8) {
                    View {
                        attr {
                            width((ctx.pagerData.pageViewWidth - 57f) / 4f)
                            flexDirectionColumn()
                            paddingRight(4f)
                            marginTop(8f)
                        }
                        skeletonBlock(height = 11f, w = 30f, sweep = sweep)
                        View { attr { height(6f) } }
                        skeletonBlock(height = 13f, w = 48f, sweep = sweep)
                    }
                }
            }
        }

        // 3) 图表区
        View {
            attr {
                flexDirectionColumn()
                margin(4f, 12f, 4f, 12f)
                padding(top = 12f, left = 16f, bottom = 12f, right = 16f)
                backgroundColor(0xFFFFFFFF)
                borderRadius(10f)
            }
            skeletonBlock(height = 15f, w = 64f, sweep = sweep)
            View { attr { height(12f) } }
            skeletonBlock(height = 200f, radius = 8f, sweep = sweep)
        }
    }
}

/**
 * 详情页错误态。
 *
 * 原来的做法是把 `loadErrorMessage`（可能是 "HTTP 404: ..." 甚至异常 message）原样摊在屏幕上——
 * 那是给开发者看的，用户读完既不知道发生了什么，也不知道该不该重试。
 * 现在分两层：一行给人看的结论 + 一行原始信息（可自查，也可截图反馈）。
 */
internal fun ViewContainer<*, *>.errorView(ctx: StockDetailPage) {
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            justifyContent(FlexJustifyContent.CENTER)
            padding(left = 32f, right = 32f)
        }

        Text {
            attr {
                text("暂时取不到这只股票的行情")
                fontSize(16f)
                fontWeightBold()
                color(0xFF333333)
                textAlignCenter()
            }
        }

        Text {
            attr {
                text("可能是网络不稳定或数据源暂未覆盖 ${ctx.stockCode}，稍后重试通常就能恢复。")
                fontSize(13f)
                color(0xFF888888)
                marginTop(8f)
                textAlignCenter()
                lineHeight(19f)
            }
        }

        if (ctx.loadErrorMessage.isNotEmpty()) {
            Text {
                attr {
                    text(ctx.loadErrorMessage)
                    fontSize(11f)
                    color(0xFFAAAAAA)
                    marginTop(10f)
                    textAlignCenter()
                    lines(3)
                }
            }
        }

        View {
            attr {
                marginTop(18f)
                padding(top = 11f, left = 28f, bottom = 11f, right = 28f)
                backgroundColor(0xFF1976D2)
                borderRadius(22f)
                pressedScale(ctx.press, DETAIL_RETRY_TAG, normal = 1f, pressed = 0.97f)
                accessibility("重试加载行情")
                accessibilityRole(AccessibilityRole.BUTTON)
                accessibilityInfo(true, false)
            }
            event {
                pressFeedback(ctx.press, DETAIL_RETRY_TAG)
                click {
                    ctx.press.releaseAll()
                    ctx.loadStockDetail()
                }
            }
            Text {
                attr {
                    text("重试")
                    fontSize(14f)
                    fontWeightBold()
                    color(0xFFFFFFFF)
                }
            }
        }
    }
}

private const val DETAIL_RETRY_TAG = "detail_retry"
