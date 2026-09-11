// 个股详情页的数据模型。
// 自 StockDetailPage.kt 拆出，供详情页及其拆分出的各 UI 模块共用。

package com.kuikly.stock.pages

import com.kuikly.stock.data.StockColors

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

data class StockInfoData(
    val code: String,
    val name: String?,
    val industry: String?,
    val plate: String?,
    val listDate: String?
)

data class RealtimeQuoteData(
    val code: String,
    val name: String?,
    val price: Double?,
    val change: Double?,
    val changePercent: Double?,
    val openPrice: Double?,
    val preClose: Double?,
    val high: Double?,
    val low: Double?,
    val volume: Double?,
    val amount: Double?,
    val peTtm: Double?,
    val pb: Double?
)

data class IndicatorData(
    val tradeDate: String,
    val ma5: Double?, val ma10: Double?, val ma20: Double?,
    val dif: Double?, val dea: Double?, val macd: Double?,
    val rsi6: Double?,
    val kdjK: Double?, val kdjD: Double?, val kdjJ: Double?
)

data class KLineDataItem(
    val code: String,
    val tradeDate: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double,
    val amount: Double?
)

data class MinutePoint(
    val time: String,
    val price: Double,
    val avgPrice: Double?,
    val volume: Double?
)

data class OrderBookData(
    val updateTime: String?,
    val bids: List<Pair<Double?, Double?>>,
    val asks: List<Pair<Double?, Double?>>,
    val commissionRatio: Double?
)

data class FundFlowItem(
    val tradeDate: String,
    val mainNet: Double,     // 主力净流入-净额（元）
    val mainRatio: Double,   // 主力净流入-净占比（%，如 -15.73）
    val superNet: Double?,   // 超大单净额（东财通道才有，新浪主力档为 null）
    val bigNet: Double?,
    val midNet: Double?,
    val smallNet: Double?,
    val source: String = "来源未提供"
)

data class StockDetailData(
    val info: StockInfoData?,
    val realtime: RealtimeQuoteData?,
    val kline: List<KLineDataItem>?,
    val indicator: IndicatorData? = null,
    val fundFlow: List<FundFlowItem>? = null
)

data class AIAnalysisData(
    val code: String,
    val name: String?,
    val analysis: Map<String, Any?>,
    val cards: List<Map<String, Any?>>,
    val source: String = "来源未标注",
    val generatedAt: Long = 0,
    val dataDate: String = "",
    // ---- v2 新增 ----
    val verdict: com.kuikly.stock.data.AIVerdict? = null,
    val protocolVersion: Int = 1,      // 2=严格v2, 1=旧协议降级, 0=离线模板
    val degraded: Boolean = false,
    val validateNote: String = "",
)
