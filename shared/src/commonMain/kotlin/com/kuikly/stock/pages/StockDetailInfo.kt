package com.kuikly.stock.pages

import com.kuikly.stock.data.StockColors
import com.kuikly.stock.ui.component.PRESS_BG_DARK
import com.kuikly.stock.ui.component.PRESS_BG_NONE
import com.kuikly.stock.ui.component.pressFeedback
import com.kuikly.stock.ui.component.pressedBg
import com.kuikly.stock.ui.component.pressedScale

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
import com.kuikly.stock.ui.component.quoteItem

internal fun ViewContainer<*, *>.detailNavigationBar(ctx: StockDetailPage) {
    val name = ctx.stockDetail?.info?.name ?: "未知"
    val code = ctx.stockDetail?.info?.code ?: ctx.stockCode

    View {
        attr {
            flexDirectionRow()
            alignItems(FlexAlign.CENTER)
            backgroundColor(AppColor.PRIMARY_SOFT)
            paddingTop(ctx.pagerData.statusBarHeight)
            height(48f + ctx.pagerData.statusBarHeight)
        }

        View {
            attr {
                padding(12f, 16f, 12f, 16f)
                borderRadius(8f)
                pressedBg(ctx.press, NAV_BACK_TAG, normal = PRESS_BG_NONE, pressed = PRESS_BG_DARK)
            }
            event {
                pressFeedback(ctx.press, NAV_BACK_TAG)
                click {
                    ctx.press.releaseAll()
                    ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                }
            }
            Text {
                attr {
                    text("< 返回")
                    fontSize(16f)
                    color(AppColor.ON_DARK)
                }
            }
        }

        Text {
            attr {
                text(name)
                fontSize(16f)
                fontWeightBold()
                color(AppColor.ON_DARK)
                marginLeft(8f)
            }
        }

        Text {
            attr {
                text("($code)")
                fontSize(12f)
                color(AppColor.ON_DARK_ACCENT_STRONG)
                marginLeft(4f)
            }
        }

        View { attr { flex(1f) } }

        View {
            attr {
                padding(8f, 8f, 8f, 8f)
                borderRadius(8f)
                pressedBg(ctx.press, NAV_REFRESH_TAG, normal = PRESS_BG_NONE, pressed = PRESS_BG_DARK)
                pressedScale(ctx.press, NAV_REFRESH_TAG, pressed = 0.94f)
                accessibility(if (ctx.refreshing) "正在刷新行情" else "刷新行情")
                accessibilityRole(AccessibilityRole.BUTTON)
                accessibilityInfo(!ctx.refreshing, false)
            }
            event {
                pressFeedback(ctx.press, NAV_REFRESH_TAG)
                click {
                    ctx.press.releaseAll()
                    if (!ctx.refreshing) ctx.loadStockDetail()
                }
            }
            Text {
                attr {
                    text(if (ctx.refreshing) "…" else "↻")
                    fontSize(17f)
                    fontWeightBold()
                    color(if (ctx.refreshing) AppColor.ON_DARK_ACCENT_STRONG else AppColor.ON_DARK)
                }
            }
        }

        View {
            attr {
                padding(10f, 10f, 6f, 10f)
                borderRadius(8f)
                pressedBg(ctx.press, NAV_WATCH_TAG, normal = PRESS_BG_NONE, pressed = PRESS_BG_DARK)
            }
            event {
                pressFeedback(ctx.press, NAV_WATCH_TAG)
                click {
                    ctx.press.releaseAll()
                    ctx.toggleWatch()
                }
            }
            Text {
                attr {
                    text(if (ctx.watched) "★" else "☆")
                    fontSize(22f)
                    color(AppColor.ON_DARK)
                }
            }
        }

        View {
            attr {
                padding(10f, 12f, 10f, 12f)
                borderRadius(8f)
                pressedBg(
                    ctx.press,
                    NAV_AI_TAG,
                    normal = if (ctx.isAnalyzing) 0xFF4A90D9L else PRESS_BG_NONE,
                    pressed = PRESS_BG_DARK,
                )
            }
            event {
                pressFeedback(ctx.press, NAV_AI_TAG)
                click {
                    ctx.press.releaseAll()
                    ctx.triggerAIAnalysis()
                }
            }
            Text {
                attr {
                    text(if (ctx.isAnalyzing) "分析中…" else "AI分析")
                    fontSize(13f)
                    color(AppColor.ON_DARK)
                }
            }
        }
    }
}

private const val NAV_BACK_TAG = "detail_nav_back"
private const val NAV_REFRESH_TAG = "detail_nav_refresh"
private const val NAV_WATCH_TAG = "detail_nav_watch"
private const val NAV_AI_TAG = "detail_nav_ai"

internal fun ViewContainer<*, *>.infoCard(ctx: StockDetailPage) {
    val info = ctx.stockDetail?.info ?: return

    View {
        attr {
            flexDirectionColumn()
            margin(8f, 12f, 4f, 12f)
            padding(top = 12f, left = 16f, bottom = 12f, right = 16f)
            backgroundColor(AppColor.SURFACE)
            borderRadius(10f)
        }

        Text {
            attr {
                text("基础信息")
                fontSize(15f)
                fontWeightBold()
                color(AppColor.TEXT_INK)
                marginBottom(8f)
            }
        }

        infoItem("股票代码", info.code)
        infoItem("股票名称", info.name ?: "-")
        infoItem("所属行业", info.industry ?: "-")
        infoItem("市场板块", info.plate ?: "-")
        infoItem("上市日期", info.listDate ?: "-")
    }
}

internal fun ViewContainer<*, *>.infoItem(label: String, value: String) {
    View {
        attr {
            flexDirectionRow()
            marginTop(6f)
        }

        Text {
            attr {
                text(label)
                fontSize(13f)
                color(AppColor.TEXT_GRAY)
                width(80f)
            }
        }

        Text {
            attr {
                text(value)
                fontSize(13f)
                fontWeightBold()
                color(AppColor.TEXT_INK)
                flex(1f)
            }
        }
    }
}

internal fun ViewContainer<*, *>.realtimeCard(ctx: StockDetailPage) {
    val realtime = ctx.stockDetail?.realtime ?: return
    val pct = realtime.changePercent
    val priceColor = when {
        pct == null || pct == 0.0 -> AppColor.TEXT_HINT
        pct > 0 -> StockColors.UP
        else -> StockColors.DOWN
    }

    View {
        attr {
            flexDirectionColumn()
            margin(4f, 12f, 4f, 12f)
            padding(top = 12f, left = 16f, bottom = 12f, right = 16f)
            backgroundColor(AppColor.SURFACE)
            borderRadius(10f)
        }

        View {
            attr { flexDirectionRow(); alignItemsCenter(); marginBottom(12f) }
            Text {
                attr {
                    text("行情快照")
                    fontSize(15f)
                    fontWeightBold()
                    color(AppColor.TEXT_INK)
                    flex(1f)
                }
            }
            aiBiasChip(ctx)
        }

        Text {
            attr {

                text(
                    if (ctx.liveStatusText.isNotEmpty()) {
                        (if (ctx.livePaused) "⚠ " else "") + ctx.liveStatusText
                    } else {
                        "本地数据 · 截至 ${realtime.updateTime?.takeIf { it.isNotBlank() } ?: "时间未提供"}"
                    }
                )
                fontSize(10f); color(if (ctx.livePaused) AppColor.WARNING_TEXT else AppColor.TEXT_HINT); marginBottom(8f)
            }
        }

        View {
            attr {
                flexDirectionRow()
                marginBottom(8f)
            }

            val hasPrice = realtime.price != null
            quoteColumn(ctx, "最新价",
                { if (hasPrice) fmt2(ctx.quoteRoll.value(0)) else "-" },
                26f, priceColor)
            quoteColumn(ctx, "涨跌额",
                { if (hasPrice) fmtSigned2(ctx.quoteRoll.value(1)) else "-" },
                15f, priceColor)
            quoteColumn(ctx, "涨跌幅",
                { if (hasPrice) fmtSignedPct(ctx.quoteRoll.value(2)) else "-" },
                15f, priceColor)
        }

        View {
            attr {
                flexDirectionRow()
                flexWrap(FlexWrap.WRAP)
            }

            stockQuoteItem(ctx, "开盘", realtime.openPrice, "")
            stockQuoteItem(ctx, "昨收", realtime.preClose, "")
            stockQuoteItem(ctx, "最高", realtime.high, "")
            stockQuoteItem(ctx, "最低", realtime.low, "")
        }

        View {
            attr {
                flexDirectionRow()
                marginTop(8f)
                flexWrap(FlexWrap.WRAP)
            }

            stockQuoteItem(ctx, "成交量", realtime.volume, "手")
            stockQuoteItem(ctx, "成交额", realtime.amount, "元")
            stockQuoteItem(ctx, "市盈率", realtime.peTtm, "")
            stockQuoteItem(ctx, "市净率", realtime.pb, "")
        }

        vif({ realtime.turnoverRate != null || realtime.volumeRatio != null ||
            realtime.totalMarketCap != null || realtime.circulateMarketCap != null }) {
            View {
                attr {
                    flexDirectionRow()
                    marginTop(8f)
                    flexWrap(FlexWrap.WRAP)
                }

                stockQuoteItem(ctx, "换手率", realtime.turnoverRate, "%")
                stockQuoteItem(ctx, "量比", realtime.volumeRatio, "")
                stockQuoteItem(ctx, "总市值", realtime.totalMarketCap, "元")
                stockQuoteItem(ctx, "流通市值", realtime.circulateMarketCap, "元")
            }
        }
    }
}

internal fun ViewContainer<*, *>.stockQuoteItem(
    ctx: StockDetailPage,
    label: String,
    value: Double?,
    suffix: String = ""
) {
    quoteItem(
        label = label,
        valueText = value?.let {
            if (suffix.isNotEmpty()) {
                when {
                    abs(it) >= 100000000 -> "${fmt2(it / 100000000)}亿"
                    abs(it) >= 10000 -> "${fmt2(it / 10000)}万"
                    else -> fmt2(it)
                }
            } else fmt2(it)
        } ?: "—",
        width = (ctx.pagerData.pageViewWidth - 57f) / 4f,
    )
}

internal fun ViewContainer<*, *>.quoteColumn(
    ctx: StockDetailPage,
    label: String,
    value: () -> String,
    valueSize: Float,
    color: Long
) {
    View {
        attr {
            flex(1f)
            flexDirectionColumn()
        }

        Text {
            attr {
                text(label)
                fontSize(11f)
                color(AppColor.TEXT_HINT)
            }
        }

        Text {
            attr {
                text(value())
                fontSize(valueSize)
                fontWeightBold()
                color(color)
                marginTop(2f)
            }
        }
    }
}

internal fun ViewContainer<*, *>.dataSourceFooter(ctx: StockDetailPage) {
    View {
        attr {
            flexDirectionColumn()
            margin(4f, 12f, 4f, 12f)
            padding(top = 10f, left = 16f, bottom = 10f, right = 16f)
            backgroundColor(AppColor.SURFACE_ALT)
            borderRadius(8f)
        }

        Text {
            attr {
                text("数据来源")
                fontSize(11f)
                color(AppColor.TEXT_HINT)
                marginBottom(4f)
            }
        }

        Text {
            attr {
                text(ctx.dataSourceText)
                fontSize(11f)
                color(AppColor.TEXT_HINT_SOFT)
            }
        }
    }
}

internal fun ViewContainer<*, *>.indicatorCard(ctx: StockDetailPage) {
    val ind = ctx.stockDetail?.indicator ?: return

    View {
        attr {
            flexDirectionColumn()
            margin(4f, 12f, 4f, 12f)
            padding(top = 12f, left = 16f, bottom = 12f, right = 16f)
            backgroundColor(AppColor.SURFACE)
            borderRadius(10f)
        }

        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER) }
            Text {
                attr {
                    text("技术指标（${ind.tradeDate}）")
                    fontSize(15f)
                    fontWeightBold()
                    color(AppColor.TEXT_INK)
                    flex(1f)
                }
            }
            View {
                attr {
                    padding(4f, 8f, 4f, 8f)
                    backgroundColor(if (ctx.klineShowMA) AppColor.PRIMARY_BG else AppColor.SURFACE_SOFT)
                    borderRadius(12f)
                }
                event { click { ctx.toggleMA() } }
                Text {
                    attr {
                        text(if (ctx.klineShowMA) "MA 开" else "MA 关")
                        fontSize(11f)
                        color(if (ctx.klineShowMA) AppColor.PRIMARY_SOFT else AppColor.TEXT_HINT)
                    }
                }
            }
        }

        View { attr { height(8f) } }

        indicatorItem("MA5", ind.ma5)
        indicatorItem("MA10", ind.ma10)
        indicatorItem("MA20", ind.ma20)

        View {
            attr { height(1f); backgroundColor(AppColor.DIVIDER_SOFT); margin(8f, 0f, 8f, 0f) }
        }

        View {
            attr { flexDirectionRow(); marginTop(6f) }
            Text { attr { text("MACD"); fontSize(12f); color(AppColor.TEXT_GRAY); width(60f) } }
            Text {
                attr {
                    text("DIF ${fmtInd(ind.dif)}  DEA ${fmtInd(ind.dea)}  柱 ${fmtInd(ind.macd)}")
                    fontSize(12f); lineHeight(19f); color(AppColor.TEXT_INK); flex(1f)
                }
            }
        }
        View {
            attr { flexDirectionRow(); marginTop(6f) }
            Text { attr { text("RSI6"); fontSize(12f); color(AppColor.TEXT_GRAY); width(60f) } }
            Text { attr { text(fmtInd(ind.rsi6)); fontSize(12f); color(AppColor.TEXT_INK) } }
        }
        View {
            attr { flexDirectionRow(); marginTop(6f) }
            Text { attr { text("KDJ"); fontSize(12f); color(AppColor.TEXT_GRAY); width(60f) } }
            Text {
                attr {
                    text("K ${fmtInd(ind.kdjK)}  D ${fmtInd(ind.kdjD)}  J ${fmtInd(ind.kdjJ)}")
                    fontSize(12f); lineHeight(19f); color(AppColor.TEXT_INK); flex(1f)
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.indicatorItem(label: String, value: Double?) {
    View {
        attr { flexDirectionRow(); marginTop(4f) }
        Text { attr { text(label); fontSize(12f); color(AppColor.TEXT_GRAY); width(60f) } }
        Text { attr { text(fmtInd(value)); fontSize(12f); color(AppColor.TEXT_INK) } }
    }
}

private fun fmtInd(v: Double?): String = if (v == null) "-" else fmt3(v)

internal fun fmtMoney(v: Double): String {
    val abs = kotlin.math.abs(v)
    return when {
        abs >= 1e8 -> "${fmt2(v / 1e8)}亿"
        abs >= 1e4 -> "${fmt0(v / 1e4)}万"
        else -> fmt0(v)
    }
}
