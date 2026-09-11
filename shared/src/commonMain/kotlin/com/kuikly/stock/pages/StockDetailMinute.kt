// 个股详情页 —— 分时图。
// 自 StockDetailPage.kt 拆出：分时画布、均线、分时摘要与加载/空态。

package com.kuikly.stock.pages

import com.kuikly.stock.data.StockColors
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

internal fun ViewContainer<*, *>.minuteCard(ctx: StockDetailPage) {
    vfor({ ObservableList(mutableListOf(Triple(ctx.minuteData, ctx.minuteLoading, ctx.minuteError))) }) { (data, loading, error) ->
    minuteCardContent(ctx, data, loading, error)
    }
}

internal fun ViewContainer<*, *>.minuteCardContent(ctx: StockDetailPage, data: List<MinutePoint>?, loading: Boolean, error: String) {

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
                    text(if (data == null) "分时" else "分时（${data.size}分钟）")
                    fontSize(15f); fontWeightBold(); color(0xFF333333); flex(1f)
                }
            }
            Text {
                attr {
                    text(ctx.minuteInfoText.ifEmpty { "点击图表查看" })
                    fontSize(10f); color(0xFF999999); flex(1f); textAlignRight()
                }
            }
        }

        detailAction("重试分时") { ctx.loadMinuteQuote() }
        vif({ loading }) {
            // 骨架高度跟真实画布一致（开成交量时 260f，否则 200f），数据到达只填充不跳版
            minuteLoadingSkeleton(ctx)
        }
        velseif({ error.isNotEmpty() || data.isNullOrEmpty() }) {
            View {
                attr { padding(16f); backgroundColor(0xFFF5F5F5); borderRadius(8f); alignItems(FlexAlign.CENTER) }
                Text { attr { text(if (error.isNotEmpty()) "分时加载失败：$error" else "该股暂无分时数据"); fontSize(13f); color(0xFF666666); fontWeightBold() } }
                Text {
                    attr {
                        text("数据源暂未提供分时，可重试或查看日K行情")
                        fontSize(11f); color(0xFF999999); marginTop(6f); textAlignCenter(); lineHeight(16f)
                    }
                }
                View {
                    attr { marginTop(10f); padding(6f, 12f, 6f, 12f); backgroundColor(0xFFE3F2FD); borderRadius(10f) }
                    event { click { ctx.scrollToChart() } }
                    Text { attr { text("查看K线联动"); fontSize(11f); color(0xFF1976D2) } }
                }
            }
        }
        velse {
            // 控制条
            View {
                attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginBottom(6f) }
                View {
                    attr {
                        padding(4f, 10f, 4f, 10f)
                        backgroundColor(if (ctx.minuteShowAvg) 0xFFE3F2FD else 0xFFF5F5F5)
                        borderRadius(10f)
                        marginRight(6f)
                    }
                    event { click { ctx.toggleMinuteAvg() } }
                    Text {
                        attr {
                            text(if (ctx.minuteShowAvg) "均线开" else "均线关")
                            fontSize(11f)
                            color(if (ctx.minuteShowAvg) 0xFF1976D2 else 0xFF999999)
                            fontWeightBold()
                        }
                    }
                }
                View {
                    attr {
                        padding(4f, 10f, 4f, 10f)
                        backgroundColor(if (ctx.minuteShowVolume) 0xFFF5F5F5 else 0xFFE8F5E9)
                        borderRadius(10f)
                        marginRight(6f)
                    }
                    event { click { ctx.toggleMinuteVolume() } }
                    Text {
                        attr {
                            text(if (ctx.minuteShowVolume) "成交量" else "无量")
                            fontSize(11f)
                            color(0xFF666666)
                        }
                    }
                }
                View { attr { flex(1f) } }
                vif({ ctx.selectedMinuteIndex >= 0 }) {
                    View {
                        attr { padding(4f, 8f, 4f, 8f); backgroundColor(0xFFF0F2F5); borderRadius(8f) }
                        event { click { ctx.clearMinuteSelection() } }
                        Text {
                            attr {
                                // 区分「跟手查看」与「已锁定」，让分时的手势语义与 K 线一致
                                text(if (ctx.minuteLocked) "已锁定 · 清除" else "跟手查看中")
                                fontSize(10f)
                                color(0xFF666666)
                            }
                        }
                    }
                }
            }

            View {
                minuteChartCanvas(ctx, data!!)
                chartTouchLayer(ctx, minute = true)
            }
            minuteSummary(ctx, data!!)

            // AI价位在分时上的图例
            vfor({ ObservableList(listOfNotNull(ctx.aiAnalysis).toMutableList()) }) { analysis ->
                View {
                    attr { flexDirectionRow(); flexWrapWrap(); marginTop(8f) }
                    val levels = parseAIPriceLevels(analysis)
                    levels.take(3).forEach { lvl ->
                        View {
                            attr {
                                flexDirectionRow()
                                alignItems(FlexAlign.CENTER)
                                marginRight(6f)
                                marginBottom(4f)
                                padding(2f, 6f, 2f, 6f)
                                backgroundColor(0xFFF7F9FC)
                                borderRadius(8f)
                            }
                            event { click { ctx.highlightAIPrice(lvl.price, lvl.label) } }
                            View {
                                attr {
                                    width(6f)
                                    height(6f)
                                    borderRadius(3f)
                                    backgroundColor(lvl.color)
                                    marginRight(4f)
                                }
                            }
                            Text { attr { text("${lvl.label} ${fmt2(lvl.price)}"); fontSize(9f); color(lvl.color) } }
                        }
                    }
                }
            }

            Text {
                attr {
                    text("横拖查看分时价位 · 竖拖滚动页面 · 虚线为 AI 关键价位")
                    fontSize(10f)
                    color(0xFFBBBBBB)
                    marginTop(6f)
                }
            }
        }
    }
}

/**
 * 分时加载骨架：控制条 + 画布 + 摘要两行。
 *
 * 画布高度与 [minuteChartCanvas] 严格一致，这样数据到达时卡片不会长高/变矮。
 */
internal fun ViewContainer<*, *>.minuteLoadingSkeleton(ctx: StockDetailPage) {
    val sweep = ctx.skeletonPulse
    View {
        attr { flexDirectionColumn() }

        View {
            attr { flexDirectionRow(); alignItems(FlexAlign.CENTER); marginBottom(6f) }
            skeletonBlock(height = 22f, w = 54f, radius = 11f, sweep = sweep)
            View { attr { width(6f) } }
            skeletonBlock(height = 22f, w = 46f, radius = 11f, sweep = sweep)
            View { attr { flex(1f) } }
        }

        skeletonBlock(height = if (ctx.minuteShowVolume) 260f else 200f, radius = 8f, sweep = sweep)

        View {
            attr { flexDirectionRow(); marginTop(10f) }
            skeletonBlock(height = 12f, w = 70f, sweep = sweep)
            View { attr { width(12f) } }
            skeletonBlock(height = 12f, w = 70f, sweep = sweep)
            View { attr { flex(1f) } }
            skeletonBlock(height = 12f, w = 54f, sweep = sweep)
        }
    }
}

internal fun ViewContainer<*, *>.minuteChartCanvas(ctx: StockDetailPage, data: List<MinutePoint>) {
    Canvas({
        attr {
            height(if (ctx.minuteShowVolume) 260f else 200f)
            marginTop(4f)
            backgroundColor(0xFFFFFFFF)
        }
    }) { context, width, height ->
        val aiLevels = parseAIPriceLevels(ctx.aiAnalysis)
        val n = data.size
        if (n < 2 || width <= 0f || height <= 0f) return@Canvas
        if (ctx.minuteCanvasWidth != width) ctx.minuteCanvasWidth = width

        val tooltipH = 28f
        val padT = 24f
        val volTop = if (ctx.minuteShowVolume) height - 54f else height - 18f
        val volH = if (ctx.minuteShowVolume) 36f else 0f
        val dateY = height - 8f

        val prices = data.map { it.price }
        val avgs = data.mapNotNull { it.avgPrice }
        val preClose = ctx.stockDetail?.realtime?.preClose ?: data.first().price
        var minP = (prices + avgs + aiLevels.map { it.price } + listOfNotNull(ctx.highlightedPrice.takeIf { it > 0 })).minOrNull() ?: 0.0
        var maxP = (prices + avgs + aiLevels.map { it.price } + listOfNotNull(ctx.highlightedPrice.takeIf { it > 0 })).maxOrNull() ?: 1.0
        if (maxP <= minP) maxP = minP + 1.0
        // 以昨收为中心对称，涨跌视觉对称（分时图惯例）；仍包含所有价位点
        if (preClose > 0.0) {
            val half = maxOf(maxP - preClose, preClose - minP).coerceAtLeast(1e-6)
            minP = preClose - half
            maxP = preClose + half
        }
        val pad = (maxP - minP) * 0.12
        minP -= pad
        maxP += pad

        val chartH = volTop - padT - 4f
        fun py(p: Double): Float = padT + chartH * ((maxP - p) / (maxP - minP)).toFloat()
        fun px(i: Int): Float = if (n <= 1) 0f else (width * i / (n - 1).toFloat())

        // 网格
        context.strokeStyle(Color(0xFFF0F2F5))
        context.lineWidth(1f)
        for (i in 0..3) {
            val gy = padT + chartH * i / 3f
            context.beginPath()
            context.moveTo(0f, gy)
            context.lineTo(width, gy)
            context.stroke()
        }
        // 垂直分割（上午/下午）
        if (n > 120) {
            val midX = width * 0.5f
            context.strokeStyle(Color(0xFFEEEEEE))
            context.beginPath()
            context.moveTo(midX, padT)
            context.lineTo(midX, volTop)
            context.stroke()
        }

        // 昨收基准 + 涨跌区域填充（伪渐变：逐段低透明度四边形，段中价≥昨收红 / 否则绿）
        val baseY = py(preClose)
        if (preClose > 0.0) {
            for (i in 0 until n - 1) {
                val x0 = px(i)
                val x1 = px(i + 1)
                val y0 = py(data[i].price)
                val y1 = py(data[i + 1].price)
                val mid = (data[i].price + data[i + 1].price) / 2.0
                context.fillStyle(Color(if (mid >= preClose) StockColors.up(0x18) else StockColors.down(0x18)))
                context.beginPath()
                context.moveTo(x0, y0)
                context.lineTo(x1, y1)
                context.lineTo(x1, baseY)
                context.lineTo(x0, baseY)
                context.closePath()
                context.fill()
            }
            // 昨收虚线（灰）+ 右侧标签
            context.strokeStyle(Color(0xFF999999))
            context.lineWidth(1f)
            var bx = 0f
            while (bx < width) {
                context.beginPath()
                context.moveTo(bx, baseY)
                context.lineTo((bx + 5f).coerceAtMost(width), baseY)
                context.stroke()
                bx += 9f
            }
            context.fillStyle(Color(0xFF999999))
            context.font(8f)
            context.textAlign(TextAlign.RIGHT)
            context.fillText("昨收 ${fmt2(preClose)}", width - 2f, baseY - 2f)
        }

        // AI价位虚线
        aiLevels.forEach { lvl ->
            if (lvl.price in minP..maxP) {
                val y = py(lvl.price)
                context.strokeStyle(Color(lvl.color))
                context.lineWidth(0.8f)
                var x = 0f
                while (x < width) {
                    context.beginPath()
                    context.moveTo(x, y)
                    context.lineTo((x + 5f).coerceAtMost(width), y)
                    context.stroke()
                    x += 9f
                }
                context.fillStyle(Color(lvl.color))
                context.font(8f)
                context.textAlign(TextAlign.RIGHT)
                context.fillText("${lvl.label}", width - 2f, y - 2f)
            }
        }

        // 高亮价位
        if (ctx.highlightedPrice > 0 && ctx.highlightedPrice in minP..maxP) {
            val y = py(ctx.highlightedPrice)
            context.strokeStyle(Color(0xFFFF9800))
            context.lineWidth(1.5f)
            context.beginPath()
            context.moveTo(0f, y)
            context.lineTo(width, y)
            context.stroke()
        }

        // 分时价格线
        context.strokeStyle(Color(0xFF1976D2))
        context.lineWidth(1.5f)
        context.beginPath()
        data.forEachIndexed { i, p ->
            val x = px(i)
            val y = py(p.price)
            if (i == 0) context.moveTo(x, y) else context.lineTo(x, y)
        }
        context.stroke()

        // 均线
        if (ctx.minuteShowAvg && avgs.isNotEmpty()) {
            context.strokeStyle(Color(0xFFFF9800))
            context.lineWidth(1f)
            context.beginPath()
            var started = false
            data.forEachIndexed { i, p ->
                val avg = p.avgPrice ?: return@forEachIndexed
                val x = px(i)
                val y = py(avg)
                if (!started) {
                    context.moveTo(x, y)
                    started = true
                } else {
                    context.lineTo(x, y)
                }
            }
            context.stroke()
        }

        // 成交量
        if (ctx.minuteShowVolume && volH > 0) {
            val vols = data.map { it.volume ?: 0.0 }
            val maxVol = vols.maxOrNull()?.toFloat()?.coerceAtLeast(1f) ?: 1f
            data.forEachIndexed { i, p ->
                val x = px(i)
                val vol = (p.volume ?: 0.0).toFloat()
                if (vol <= 0f) return@forEachIndexed
                val vh = (volH * (vol / maxVol)).coerceAtLeast(1f)
                val up = p.price >= preClose
                context.fillStyle(Color(if (up) StockColors.UP else StockColors.DOWN))
                context.beginPath()
                context.moveTo(x - 1f, volTop + volH)
                context.lineTo(x + 1f, volTop + volH)
                context.lineTo(x + 1f, volTop + volH - vh)
                context.lineTo(x - 1f, volTop + volH - vh)
                context.closePath()
                context.fill()
            }
        }

        // 价格标签
        context.fillStyle(Color(0xFF999999))
        context.font(9f)
        context.textAlign(TextAlign.LEFT)
        context.fillText(fmt2(maxP), 2f, padT + 8f)
        context.fillText(fmt2(minP), 2f, volTop - 2f)
        context.textAlign(TextAlign.RIGHT)
        context.fillText(fmt2(data.last().price), width - 2f, padT + 8f)

        // 时间标签
        context.font(9f)
        context.fillStyle(Color(0xFF999999))
        context.textAlign(TextAlign.LEFT)
        context.fillText(data.first().time, 2f, dateY)
        context.textAlign(TextAlign.CENTER)
        if (n > 60) context.fillText(data[n / 2].time, width * 0.5f, dateY)
        context.textAlign(TextAlign.RIGHT)
        context.fillText(data.last().time, width - 2f, dateY)

        // 选中十字
        val sel = ctx.selectedMinuteIndex
        if (sel >= 0 && sel < n) {
            val p = data[sel]
            val cx = px(sel)
            val cy = py(p.price)
            context.strokeStyle(Color(0xFF333333))
            context.lineWidth(0.8f)
            var vy = padT
            while (vy < volTop) {
                context.beginPath()
                context.moveTo(cx, vy)
                context.lineTo(cx, (vy + 3f).coerceAtMost(volTop))
                context.stroke()
                vy += 7f
            }
            var hx = 0f
            while (hx < width) {
                context.beginPath()
                context.moveTo(hx, cy)
                context.lineTo((hx + 3f).coerceAtMost(width), cy)
                context.stroke()
                hx += 7f
            }
            // 点
            context.fillStyle(Color(0xFF1976D2))
            context.beginPath()
            context.moveTo(cx - 3f, cy)
            context.lineTo(cx + 3f, cy)
            context.lineTo(cx, cy - 3f)
            context.closePath()
            context.fill()

            // tooltip
            context.fillStyle(Color(0xE622263F))
            context.beginPath()
            context.moveTo(0f, 0f)
            context.lineTo(width, 0f)
            context.lineTo(width, tooltipH)
            context.lineTo(0f, tooltipH)
            context.closePath()
            context.fill()
            context.fillStyle(Color(0xFFFFFFFF))
            context.font(9f)
            context.textAlign(TextAlign.LEFT)
            context.fillText(
                "${p.time} 价${fmt2(p.price)}${p.avgPrice?.let { " 均${fmt2(it)}" } ?: ""} ${p.volume?.let { "量${it.toInt()}" } ?: ""}",
                4f, 10f
            )
            val pct = if (preClose != 0.0) (p.price - preClose) / preClose * 100.0 else 0.0
            context.fillStyle(Color(if (pct >= 0) 0xFFFF8A80 else 0xFFA5D6A7))
            context.fillText("涨跌 ${fmtSignedPct(pct)}", 4f, 20f)
        }
    }
}

internal fun ViewContainer<*, *>.minuteSummary(ctx: StockDetailPage, data: List<MinutePoint>) {
    if (data.isEmpty()) return
    val latest = data.last()
    val first = data.first()
    val prices = data.map { it.price }
    val hi = prices.maxOrNull() ?: latest.price
    val lo = prices.minOrNull() ?: latest.price
    val preClose = ctx.stockDetail?.realtime?.preClose ?: first.price
    val change = latest.price - preClose
    val pct = if (preClose != 0.0) change / preClose * 100.0 else 0.0

    View {
        attr { flexDirectionColumn(); marginTop(8f) }
        View { attr { height(1f); backgroundColor(0xFFEEEEEE); marginBottom(6f) } }
        View {
            attr { flexDirectionRow(); flexWrapWrap() }
            Text { attr { text("今开 ${fmt2(first.price)}"); fontSize(11f); color(0xFF666666) } }
            Text { attr { text("最新 ${fmt2(latest.price)}"); fontSize(11f); fontWeightBold(); color(0xFF333333); marginLeft(10f) } }
            Text {
                attr {
                    text("${fmtSigned2(change)} ${fmtSignedPct(pct)}")
                    fontSize(11f)
                    color(if (pct >= 0) StockColors.UP else StockColors.DOWN)
                    marginLeft(8f)
                }
            }
        }
        View {
            attr { flexDirectionRow(); marginTop(4f) }
            Text { attr { text("最高 ${fmt2(hi)}  最低 ${fmt2(lo)}"); fontSize(11f); color(0xFF666666) } }
            Text { attr { text("振幅 ${fmt2((hi - lo) / preClose * 100.0)}%"); fontSize(11f); color(0xFF999999); marginLeft(10f) } }
        }
    }
}
