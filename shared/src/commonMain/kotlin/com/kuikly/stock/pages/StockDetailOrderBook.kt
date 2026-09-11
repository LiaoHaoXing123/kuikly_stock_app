// 个股详情页 —— 五档盘口。
// 自 StockDetailPage.kt 拆出：五档买卖档位行、占比条、深度画布。
// fmtOpt 为该模块私有，仅本文件使用。

package com.kuikly.stock.pages

import com.kuikly.stock.data.StockColors
import com.kuikly.stock.base.MountPulse
import com.kuikly.stock.base.skeletonBlock

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
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

internal fun ViewContainer<*, *>.orderBookCard(ctx: StockDetailPage) {
    View {
        attr { margin(4f, 12f, 4f, 12f); padding(12f); backgroundColor(0xFFFFFFFF); borderRadius(10f) }
        Text { attr { text("五档盘口"); fontSize(15f); fontWeightBold() } }
        vif({ ctx.orderBookLoading }) { orderBookLoadingSkeleton(ctx) }
        velseif({ ctx.orderBookError.isNotEmpty() || ctx.orderBook == null }) {
            Text { attr { text(ctx.orderBookError.ifEmpty { "暂无盘口数据" }); fontSize(12f) } }
            detailAction("重试盘口") { ctx.loadBookQuote() }
            detailAction("查看K线") { ctx.scrollToChart() }
        }
        velse { vfor({ ObservableList(listOfNotNull(ctx.orderBook).toMutableList()) }) { book -> orderBookCardContent(ctx, book) } }
    }
}

/**
 * 盘口加载骨架：10 档（5 卖 + 5 买）+ 中间现价分隔行。
 *
 * 档位行的高度/内边距与 orderBookRow 对齐，这样从骨架切到真实档位时
 * 卡片不会整体跳动——盘口是十行结构，跳一下非常显眼。
 */
internal fun ViewContainer<*, *>.orderBookLoadingSkeleton(ctx: StockDetailPage) {
    val sweep = ctx.skeletonPulse
    View {
        attr { flexDirectionColumn() }

        repeat(5) { orderBookSkeletonRow(sweep) }

        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); margin(6f, 0f, 6f, 0f) }
            View { attr { height(1f); backgroundColor(0xFFEEEEEE); flex(1f) } }
            View { attr { marginLeft(8f); marginRight(8f) } }
            skeletonBlock(height = 11f, w = 72f, sweep = sweep)
            View { attr { marginLeft(8f); marginRight(8f) } }
            View { attr { height(1f); backgroundColor(0xFFEEEEEE); flex(1f) } }
        }

        repeat(5) { orderBookSkeletonRow(sweep) }

        View {
            attr { flexDirectionRow(); marginTop(10f) }
            skeletonBlock(height = 22f, w = 72f, radius = 8f, sweep = sweep)
            View { attr { width(6f) } }
            skeletonBlock(height = 22f, w = 88f, radius = 8f, sweep = sweep)
            View { attr { flex(1f) } }
        }
    }
}

private fun ViewContainer<*, *>.orderBookSkeletonRow(sweep: MountPulse) {
    View {
        attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginTop(3f); padding(4f, 6f, 4f, 6f) }
        skeletonBlock(height = 12f, w = 30f, sweep = sweep)
        View { attr { width(8f) } }
        skeletonBlock(height = 13f, w = 56f, sweep = sweep)
        View { attr { flex(1f) } }
        skeletonBlock(height = 11f, w = 44f, sweep = sweep)
    }
}

internal fun ViewContainer<*, *>.orderBookCardContent(ctx: StockDetailPage, book: OrderBookData) {
    // 派生量：各档量能比例条 / 委差 / 大单判定
    val levelVols = (book.bids + book.asks).mapNotNull { it.second }.filter { it.isFinite() && it >= 0.0 }
    val maxLevelVol = levelVols.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val meanLevelVol = if (levelVols.isEmpty()) 0.0 else levelVols.average()
    val bidVolTotal = book.bids.sumOf { it.second ?: 0.0 }
    val askVolTotal = book.asks.sumOf { it.second ?: 0.0 }
    val weicha = bidVolTotal - askVolTotal  // 委差(手)：买总量 - 卖总量

    View {
        attr {
            flexDirectionColumn()
            margin(4f, 12f, 4f, 12f)
            padding(top = 12f, left = 12f, bottom = 10f, right = 12f)
            backgroundColor(0xFFFFFFFF)
            borderRadius(10f)
        }

        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginBottom(8f) }
            Text {
                attr {
                    text("五档盘口${book.updateTime?.let { "（$it）" } ?: ""}")
                    fontSize(15f); fontWeightBold(); color(0xFF333333); flex(1f)
                }
            }
            View {
                attr {
                    padding(4f, 10f, 4f, 10f)
                    backgroundColor(if (ctx.orderBookMode == "depth") 0xFFE3F2FD else 0xFFF5F5F5)
                    borderRadius(10f)
                    marginRight(6f)
                }
                event { click { ctx.toggleOrderBookMode() } }
                Text {
                    attr {
                        text(if (ctx.orderBookMode == "depth") "深度图" else "列表")
                        fontSize(11f)
                        color(if (ctx.orderBookMode == "depth") 0xFF1976D2 else 0xFF666666)
                        fontWeightBold()
                    }
                }
            }
            Text {
                attr {
                    text("点击价位联动K线")
                    fontSize(10f)
                    color(0xFF999999)
                }
            }
        }

        vif({ ctx.orderBookMode == "depth" }) {
            orderBookDepthCanvas(ctx, book)
        }

        // 列表
        book.asks.reversed().forEachIndexed { i, (price, vol) ->
            val r = (vol ?: 0.0) / maxLevelVol
            val big = vol != null && meanLevelVol > 0.0 && vol >= meanLevelVol * 1.8
            orderBookRow(ctx, "卖${5 - i}", price, vol, StockColors.DOWN, isAsk = true, ratio = r, isBig = big)
        }
        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); margin(6f, 0f, 6f, 0f) }
            View { attr { height(1f); backgroundColor(0xFFEEEEEE); flex(1f) } }
            Text {
                attr {
                    text("现价 ${ctx.stockDetail?.realtime?.price?.let { fmt2(it) } ?: "-"}")
                    fontSize(11f)
                    color(0xFF1976D2)
                    fontWeightBold()
                    marginLeft(8f)
                    marginRight(8f)
                }
            }
            View { attr { height(1f); backgroundColor(0xFFEEEEEE); flex(1f) } }
        }
        book.bids.forEachIndexed { i, (price, vol) ->
            val r = (vol ?: 0.0) / maxLevelVol
            val big = vol != null && meanLevelVol > 0.0 && vol >= meanLevelVol * 1.8
            orderBookRow(ctx, "买${i + 1}", price, vol, StockColors.UP, isAsk = false, ratio = r, isBig = big)
        }

        View {
            attr { flexDirectionRow(); marginTop(10f); flexWrapWrap() }
            book.commissionRatio?.let { ratio ->
                View {
                    attr {
                        padding(4f, 8f, 4f, 8f)
                        backgroundColor(0xFFF5F5F5)
                        borderRadius(8f)
                        marginRight(6f)
                    }
                    Text { attr { text("委比 ${fmt2(ratio)}%"); fontSize(11f); color(0xFF666666) } }
                }
            }
            // 委差（手）：买总量 - 卖总量，>0 偏多(红)、<0 偏空(绿)
            View {
                attr {
                    padding(4f, 8f, 4f, 8f)
                    backgroundColor(0xFFF5F5F5)
                    borderRadius(8f)
                    marginRight(6f)
                }
                Text {
                    attr {
                        text("委差 ${if (weicha >= 0) "+" else ""}${weicha.toInt()}手")
                        fontSize(11f)
                        color(if (weicha >= 0) StockColors.UP else StockColors.DOWN)
                    }
                }
            }
            vif({ ctx.orderBookHighlightPrice > 0 }) {
                View {
                    attr {
                        flexDirectionRow()
                        alignItems(FlexAlign.CENTER)
                        padding(4f, 8f, 4f, 8f)
                        backgroundColor(0xFFFFF3E8)
                        borderRadius(8f)
                    }
                    Text {
                        attr {
                            text("已标注 ¥${fmt2(ctx.orderBookHighlightPrice)}")
                            fontSize(11f)
                            color(0xFFA56100)
                        }
                    }
                    View {
                        attr { marginLeft(6f); padding(2f, 6f, 2f, 6f); backgroundColor(0xFFFFFFFF); borderRadius(6f) }
                        event { click { ctx.clearHighlight() } }
                        Text { attr { text("✕"); fontSize(10f); color(0xFF999999) } }
                    }
                }
            }
        }

        Text {
            attr {
                text("盘口价位可一键标注到K线，或设提醒，联动分时与日K")
                fontSize(10f)
                color(0xFFBBBBBB)
                marginTop(8f)
            }
        }
    }
}

internal fun ViewContainer<*, *>.orderBookRow(ctx: StockDetailPage, label: String, price: Double?, vol: Double?, color: Long, isAsk: Boolean, ratio: Double, isBig: Boolean) {
    val barFlex = (ratio.coerceIn(0.0, 1.0) * 1000).toInt().coerceIn(0, 1000)
    View {
        attr {
            flexDirectionRow()
            marginTop(3f)
            alignItems(FlexAlign.CENTER)
            padding(4f, 6f, 4f, 6f)
            backgroundColor(if (price != null && ctx.orderBookHighlightPrice == price) 0xFFFFF3E8 else 0xFFFFFFFF)
            borderRadius(6f)
        }
        // 量能比例背景条（绝对铺底、无事件，不拦截点击；靠右填充，买红/卖绿低透明度）
        View {
            attr { absolutePositionAllZero(); flexDirectionRow(); borderRadius(6f) }
            View { attr { flex((1000 - barFlex).toFloat()) } }
            View { attr { flex(barFlex.toFloat()); backgroundColor(if (isAsk) StockColors.down(0x15) else StockColors.up(0x15)) } }
        }
        Text { attr { text(label); fontSize(12f); color(0xFF666666); width(36f) } }
        Text { attr { text(fmtOpt(price)); fontSize(13f); fontWeightBold(); color(color); flex(1f) } }
        Text { attr { text(fmtOpt(vol)); fontSize(11f); color(0xFF666666); width(60f); textAlignRight() } }

        vif({ isBig }) {
            View {
                attr {
                    marginLeft(6f)
                    padding(2f, 5f, 2f, 5f)
                    backgroundColor(if (isAsk) 0xFFEAF7EF else 0xFFFFF0F0)
                    borderRadius(6f)
                }
                Text { attr { text("大单"); fontSize(9f); color(if (isAsk) 0xFF2E7D32 else 0xFFC62828); fontWeightBold() } }
            }
        }
        View {
            attr {
                marginLeft(6f)
                padding(3f, 8f, 3f, 8f)
                backgroundColor(if (price != null && ctx.orderBookHighlightPrice == price) 0xFFFFE0B2 else 0xFFF0F2F5)
                borderRadius(8f)
            }
            event {
                click {
                    if (price != null) ctx.highlightOrderBookPrice(price, label)
                }
            }
            Text { attr { text(if (price != null && ctx.orderBookHighlightPrice == price) "已标" else "标注"); fontSize(10f); color(if (price != null && ctx.orderBookHighlightPrice == price) 0xFFA56100 else 0xFF666666); fontWeightBold() } }
        }
        View {
            attr {
                marginLeft(4f)
                padding(3f, 8f, 3f, 8f)
                backgroundColor(Color(color))
                borderRadius(8f)
            }
            event {
                click {
                    if (price != null) ctx.prepareAlertFromOrderBook(price)
                }
            }
            Text { attr { text("提醒"); fontSize(10f); color(0xFFFFFFFF); fontWeightBold() } }
        }
    }
}

internal fun ViewContainer<*, *>.orderBookDepthCanvas(ctx: StockDetailPage, book: OrderBookData) {
    Canvas({
        attr {
            height(120f)
            marginBottom(8f)
            backgroundColor(0xFFFAFAFA)
            borderRadius(8f)
        }
    }) { context, width, height ->
        if (width <= 0f || height <= 0f) return@Canvas
        val bids = book.bids.filter { (price, _) -> price != null && price.isFinite() && price > 0 }
        val asks = book.asks.filter { (price, _) -> price != null && price.isFinite() && price > 0 }
        if (bids.isEmpty() && asks.isEmpty()) return@Canvas

        val allPrices = (bids.map { it.first!! } + asks.map { it.first!! })
        var minP = allPrices.minOrNull() ?: 0.0
        var maxP = allPrices.maxOrNull() ?: 1.0
        if (maxP <= minP) maxP = minP + 1.0
        val pad = (maxP - minP) * 0.1
        minP -= pad
        maxP += pad

        val allVols = (bids.map { it.second ?: 0.0 } + asks.map { it.second ?: 0.0 })
        val maxVol = allVols.maxOrNull()?.toFloat()?.coerceAtLeast(1f) ?: 1f

        fun py(vol: Double): Float = height - 16f - (vol.toFloat() / maxVol) * (height - 32f)
        fun px(price: Double): Float = ((price - minP) / (maxP - minP)).toFloat() * (width - 16f) + 8f

        // 买盘
        context.fillStyle(Color(StockColors.up(0x33)))
        context.strokeStyle(Color(StockColors.UP))
        context.lineWidth(1f)
        if (bids.isNotEmpty()) {
            val sortedBids = bids.sortedBy { it.first }
            context.beginPath()
            context.moveTo(px(sortedBids.first().first!!), height - 16f)
            sortedBids.forEach { (price, vol) ->
                context.lineTo(px(price!!), py(vol ?: 0.0))
            }
            context.lineTo(px(sortedBids.last().first!!), height - 16f)
            context.closePath()
            context.fill()
            context.beginPath()
            sortedBids.forEachIndexed { i, (price, vol) ->
                val x = px(price!!)
                val y = py(vol ?: 0.0)
                if (i == 0) context.moveTo(x, y) else context.lineTo(x, y)
            }
            context.stroke()
        }

        // 卖盘
        context.fillStyle(Color(StockColors.down(0x33)))
        context.strokeStyle(Color(StockColors.DOWN))
        if (asks.isNotEmpty()) {
            val sortedAsks = asks.sortedBy { it.first }
            context.beginPath()
            context.moveTo(px(sortedAsks.first().first!!), height - 16f)
            sortedAsks.forEach { (price, vol) ->
                context.lineTo(px(price!!), py(vol ?: 0.0))
            }
            context.lineTo(px(sortedAsks.last().first!!), height - 16f)
            context.closePath()
            context.fill()
            context.beginPath()
            sortedAsks.forEachIndexed { i, (price, vol) ->
                val x = px(price!!)
                val y = py(vol ?: 0.0)
                if (i == 0) context.moveTo(x, y) else context.lineTo(x, y)
            }
            context.stroke()
        }

        // 现价线
        ctx.stockDetail?.realtime?.price?.let { curPrice ->
            if (curPrice in minP..maxP) {
                val x = px(curPrice)
                context.strokeStyle(Color(0xFF1976D2))
                context.lineWidth(1f)
                context.beginPath()
                context.moveTo(x, 0f)
                context.lineTo(x, height - 16f)
                context.stroke()
                context.fillStyle(Color(0xFF1976D2))
                context.font(8f)
                context.textAlign(TextAlign.CENTER)
                context.fillText("现价", x, 10f)
            }
        }

        // 标签
        context.fillStyle(Color(0xFF999999))
        context.font(8f)
        context.textAlign(TextAlign.LEFT)
        context.fillText(fmt2(minP), 2f, height - 2f)
        context.textAlign(TextAlign.RIGHT)
        context.fillText(fmt2(maxP), width - 2f, height - 2f)
    }
}

private fun fmtOpt(v: Double?): String = if (v == null) "-" else fmt2(v)
