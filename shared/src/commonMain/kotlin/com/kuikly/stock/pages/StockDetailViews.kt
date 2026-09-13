package com.kuikly.stock.pages

import com.kuikly.stock.ui.component.topToast
import com.kuikly.stock.ui.component.AI_DOT_STEP_MS
import com.kuikly.stock.base.BasePager
import com.kuikly.stock.ui.component.Overlay
import com.kuikly.stock.ui.component.overlayEnterExit
import com.kuikly.stock.ui.component.aiDotWaveDots
import com.kuikly.stock.ui.component.SKELETON_BG_ON_DARK
import com.kuikly.stock.ui.component.SKELETON_BG_STRONG
import com.kuikly.stock.ui.component.pressFeedback
import com.kuikly.stock.ui.component.pressedScale
import com.kuikly.stock.ui.component.skeletonBlock

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
import com.kuikly.stock.ui.theme.AppColor

internal fun ViewContainer<*, *>.analyzingView(ctx: StockDetailPage) {
    View {
        attr {
            flexDirectionColumn()
            alignItems(FlexAlign.CENTER)
            padding(top = 20f, left = 16f, bottom = 20f, right = 16f)
            backgroundColor(AppColor.SURFACE)
            borderRadius(12f)
        }

        Text {
            attr {
                text("AI 正在分析中...")
                fontSize(14f)
                color(AppColor.TEXT_GRAY)
            }
        }

        Text {
            attr {
                text("正在结合K线、指标与盘口数据生成结构化研判")
                fontSize(12f)
                color(AppColor.TEXT_HINT)
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
            backgroundColor(AppColor.SURFACE)
            borderRadius(12f)
        }

        Text {
            attr {
                text("尚未进行 AI 分析")
                fontSize(14f)
                color(AppColor.TEXT_GRAY)
            }
        }

        Text {
            attr {
                text("AI 将结合K线走势、技术指标与盘口数据，生成带关键价位的结构化研判，并与K线联动")
                fontSize(11f)
                color(AppColor.TEXT_HINT)
                marginTop(6f)
                textAlignCenter()
                lineHeight(16f)
            }
        }

        View {
            attr {
                marginTop(14f)
                padding(top = 10f, left = 28f, bottom = 10f, right = 28f)
                backgroundColor(AppColor.PRIMARY_SOFT)
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
                    color(AppColor.ON_DARK)
                }
            }
        }
    }
}

private const val AI_START_TAG = "detail_ai_start"

internal fun ViewContainer<*, *>.detailAlertConfirmDialog(ctx: StockDetailPage) {
    View {
        attr { absolutePositionAllZero(); backgroundColor(AppColor.SCRIM); allCenter() }
        View {
            attr {
                overlayEnterExit(ctx.alertOverlay)
            }
            Text { attr { text("确认创建价格提醒"); fontSize(18f); fontWeightBold(); color(AppColor.TEXT_STRONG) } }
            Text { attr { text("${ctx.pendingAlertName} · ${ctx.pendingAlertCode}"); fontSize(13f); color(AppColor.TEXT_SUB_DEEP); marginTop(9f) } }
            View { attr { padding(14f); marginTop(12f); borderRadius(12f); backgroundColor(AppColor.BG) }
                Text { attr { text(if (ctx.pendingAlertType == 1) "价格跌至或低于" else "价格涨至或高于"); fontSize(11f); color(AppColor.TEXT_SUB_DEEP) } }
                Text { attr { text("¥ ${fmt2(ctx.pendingAlertValue)}"); fontSize(23f); fontWeightBold(); color(AppColor.TEXT_STRONG); marginTop(4f) } }
            }
            Text { attr { text("提醒在行情数据刷新时检查，可能存在延迟。"); fontSize(11f); color(AppColor.TEXT_SUB); marginTop(10f) } }
            View { attr { flexDirectionRow(); marginTop(16f) }
                View { attr { flex(1f); height(44f); allCenter(); borderRadius(12f); backgroundColor(AppColor.BG_SOFT); pressedScale(ctx.press, ALERT_CANCEL_TAG) }; event { pressFeedback(ctx.press, ALERT_CANCEL_TAG); click { ctx.press.releaseAll(); ctx.dismissAlertConfirm() } }; Text { attr { text("取消"); fontSize(13f); color(AppColor.TEXT_SUB_DEEP) } } }
                View { attr { width(10f) } }
                View { attr { flex(1f); height(44f); allCenter(); borderRadius(12f); backgroundColor(AppColor.PRIMARY); pressedScale(ctx.press, ALERT_OK_TAG, normal = 1f, pressed = 0.97f); accessibility("确认创建价格提醒"); accessibilityRole(AccessibilityRole.BUTTON); accessibilityInfo(true, false) }; event { pressFeedback(ctx.press, ALERT_OK_TAG); click { ctx.press.releaseAll(); ctx.confirmAlert() } }; Text { attr { text("确认创建"); fontSize(13f); fontWeightBold(); color(Color.WHITE) } } }
            }
        }
    }
}

private const val ALERT_CANCEL_TAG = "detail_alert_cancel"
private const val ALERT_OK_TAG = "detail_alert_ok"

internal fun ViewContainer<*, *>.detailAiToast(ctx: StockDetailPage) {
    topToast(
        ctx = ctx,
        text = { ctx.aiErrorNotice },
        onDismiss = { ctx.aiErrorNotice = "" },
        tint = AppColor.PRIMARY,
    )
}

internal fun ViewContainer<*, *>.stockDetailLoadingView(ctx: BasePager) {
    val sweep = ctx.skeletonPulse
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
            backgroundColor(AppColor.SURFACE_SOFT)
        }

        View {
            attr {
                flexDirectionRow()
                alignItems(FlexAlign.CENTER)
                backgroundColor(AppColor.PRIMARY_SOFT)
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

        View {
            attr {
                flexDirectionColumn()
                margin(4f, 12f, 4f, 12f)
                padding(top = 12f, left = 16f, bottom = 12f, right = 16f)
                backgroundColor(AppColor.SURFACE)
                borderRadius(10f)
            }

            skeletonBlock(height = 15f, w = 72f, sweep = sweep)

            View {
                attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginTop(14f) }
                skeletonBlock(height = 26f, w = 96f, color = SKELETON_BG_STRONG, sweep = sweep)
                View { attr { width(16f) } }
                skeletonBlock(height = 15f, w = 56f, sweep = sweep)
                View { attr { width(16f) } }
                skeletonBlock(height = 15f, w = 56f, sweep = sweep)
            }

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

        View {
            attr {
                flexDirectionColumn()
                margin(4f, 12f, 4f, 12f)
                padding(top = 12f, left = 16f, bottom = 12f, right = 16f)
                backgroundColor(AppColor.SURFACE)
                borderRadius(10f)
            }
            skeletonBlock(height = 15f, w = 64f, sweep = sweep)
            View { attr { height(12f) } }
            skeletonBlock(height = 200f, radius = 8f, sweep = sweep)
        }
    }
}

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
                color(AppColor.TEXT_INK)
                textAlignCenter()
            }
        }

        Text {
            attr {
                text("可能是网络不稳定或数据源暂未覆盖 ${ctx.stockCode}，稍后重试通常就能恢复。")
                fontSize(13f)
                color(AppColor.TEXT_HINT_SOFT)
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
                    color(AppColor.TEXT_MUTED)
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
                backgroundColor(AppColor.PRIMARY_SOFT)
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
                    color(AppColor.ON_DARK)
                }
            }
        }
    }
}

private const val DETAIL_RETRY_TAG = "detail_retry"
