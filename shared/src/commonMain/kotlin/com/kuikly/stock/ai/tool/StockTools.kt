// 调用工具层：定义 AI 可调用的行情工具（OpenAI 兼容 function-calling schema）， 并把工具调用映射到统一行情仓库 (MarketRepository)。这样提示词不必预注入全部数据， 模型按需取数，且只引用工具真实返回的

package com.kuikly.stock.ai.tool

import com.kuikly.stock.data.MarketRepository
import com.kuikly.stock.data.StockDb
import com.kuikly.stock.pages.RealtimeQuoteData
import kotlinx.serialization.json.*
import kotlinx.serialization.json.buildJsonObject

/** 缺失字段的统一表述：禁止用 0 谎报数值。 */
private const val NA = "未提供"

object StockTools {

    val definitions: JsonArray = buildJsonArray {
        add(defineTool(
            name = "get_stock_quote",
            description = "获取单只股票的实时行情快照（最新价、涨跌幅、成交量额、PE/PB）。必须提供股票代码。（仅个股；指数用 get_index_quote）",
            required = listOf("code"),
            properties = buildJsonObject { put("code", stringProp("6位股票代码，如 000001")) }
        ))
        add(defineTool(
            name = "get_stock_kline",
            description = "获取单只股票最近 N 个交易日日K线（前复权）。（仅个股；指数用 get_index_kline）",
            required = listOf("code"),
            properties = buildJsonObject {
            put("code", stringProp("6位股票代码，如 000001"))
            put("days", intProp("返回多少个交易日，默认 10"))
            }
        ))
        add(defineTool(
            name = "get_stock_indicator",
            description = "获取单只股票最新技术指标（均线 MA5/10/20、MACD、RSI、KDJ）。",
            required = listOf("code"),
            properties = buildJsonObject { put("code", stringProp("6位股票代码，如 000001")) }
        ))
        add(defineTool(
            name = "get_index_quote",
            description = "获取单个指数的实时行情快照（最新点位、涨跌幅、成交量额）。必须提供指数代码，如 000001（上证指数）。",
            required = listOf("code"),
            properties = buildJsonObject { put("code", stringProp("6位指数代码，如 000001")) }
        ))
        add(defineTool(
            name = "get_index_kline",
            description = "获取单个指数最近 N 个交易日日K线。",
            required = listOf("code"),
            properties = buildJsonObject {
            put("code", stringProp("6位指数代码，如 000001"))
            put("days", intProp("返回多少个交易日，默认 10"))
            }
        ))
        add(defineTool(
            name = "list_indices",
            description = "列出本地指数库中的指数（代码与名称），可按关键字过滤。想知道有哪些指数可查时先调此工具。",
            required = emptyList(),
            properties = buildJsonObject { put("keyword", stringProp("可选过滤关键字，如 上证/300")) }
        ))
        add(defineTool(
            name = "get_market_overview",
            description = "获取全市场涨跌家数概览与涨跌幅榜（top 3）。",
            required = emptyList(),
            properties = buildJsonObject { }
        ))
        add(defineTool(
            name = "get_stock_orderbook",
            description = "获取单只股票的五档盘口（买一到买五、卖一到卖五的价与量）和委比，含数据时间。",
            required = listOf("code"),
            properties = buildJsonObject { put("code", stringProp("6位股票代码，如 000001")) }
        ))
        add(defineTool(
            name = "get_stock_fund_flow",
            description = "获取单只股票最近 N 个交易日的主力资金流向（主力/超大单/大单净额与净占比）。",
            required = listOf("code"),
            properties = buildJsonObject {
                put("code", stringProp("6位股票代码，如 000001"))
                put("days", intProp("返回多少个交易日，默认 5"))
            }
        ))
        add(defineTool(
            name = "get_stock_minute",
            description = "获取单只股票当日分时走势（每分钟价格/均价/成交量）。默认返回最近 10 根并附当日摘要。",
            required = listOf("code"),
            properties = buildJsonObject {
                put("code", stringProp("6位股票代码，如 000001"))
                put("count", intProp("返回最近多少根分钟线，默认 10，最多 30"))
            }
        ))
        add(defineTool(
            name = "get_stock_events",
            description = "获取单只股票的公司事件（分红除权、财报披露），按日期区分未发生与已发生。想知道某股近期有什么大事时调用。",
            required = listOf("code"),
            properties = buildJsonObject { put("code", stringProp("6位股票代码，如 000001")) }
        ))
        add(defineTool(
            name = "get_stock_industry_peers",
            description = "获取单只股票的行业对照：同行业样本的等权平均涨跌幅、该股相对表现、行业内涨跌幅前列个股。",
            required = listOf("code"),
            properties = buildJsonObject { put("code", stringProp("6位股票代码，如 000001")) }
        ))
    }

    suspend fun execute(name: String, args: JsonObject): JsonObject = try {
        when (name) {
            "get_stock_quote" -> quote(args)
            "get_stock_kline" -> kline(args)
            "get_stock_indicator" -> indicator(args)
            "get_index_quote" -> indexQuote(args)
            "get_index_kline" -> indexKline(args)
            "list_indices" -> listIndices(args)
            "get_market_overview" -> marketOverview()
            "get_stock_orderbook" -> orderbook(args)
            "get_stock_fund_flow" -> fundFlow(args)
            "get_stock_minute" -> minute(args)
            "get_stock_events" -> events(args)
            "get_stock_industry_peers" -> industryPeers(args)
            else -> buildJsonObject { put("error", "未知工具: $name") }
        }
    } catch (e: Throwable) {
        buildJsonObject { put("error", (e.message ?: e.toString()).take(120)) }
    }

    private suspend fun quote(args: JsonObject): JsonObject {
        val code = args["code"]?.jsonPrimitive?.content ?: ""
        val snap = MarketRepository.stockQuote(code)
        val r = snap.quote
        fun num(v: Double?): JsonPrimitive = if (v == null) JsonPrimitive(NA) else JsonPrimitive(v)
        return buildJsonObject {
            put("code", code)
            put("name", JsonPrimitive(snap.name ?: NA))
            put("data_source", snap.source)
            if (snap.asOf.isNotEmpty()) put("as_of", snap.asOf)
            if (r == null) {
                put("note", NA)
                return@buildJsonObject
            }
            put("price", num(r.price))
            put("change", num(r.change))
            put("change_percent", num(r.changePercent))
            put("open", num(r.openPrice))
            put("high", num(r.high))
            put("low", num(r.low))
            put("volume", num(r.volume))
            put("amount", num(r.amount))
            put("pe_ttm", num(r.peTtm))
            put("pb", num(r.pb))
        }
    }

    private fun kline(args: JsonObject): JsonObject {
        val code = args["code"]?.jsonPrimitive?.content ?: ""
        val days = (args["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10).coerceIn(1, 120)
        val kline = MarketRepository.klineTail(code, days)
        return buildJsonObject {
            put("code", code)
            put("days", kline.size)
            if (kline.isEmpty()) put("note", NA)
            put("items", buildJsonArray { kline.forEach { k ->
                add(buildJsonObject {
                    put("trade_date", k.tradeDate)
                    put("open", k.open)
                    put("close", k.close)
                    put("high", k.high)
                    put("low", k.low)
                    put("volume", k.volume)
                })
            } })
        }
    }

    private fun indicator(args: JsonObject): JsonObject {
        val code = args["code"]?.jsonPrimitive?.content ?: ""
        val i = MarketRepository.indicator(code)
        return buildJsonObject {
            put("code", code)
            if (i == null) {
                put("note", "本地库未提供该股技术指标")
                return@buildJsonObject
            }
            put("trade_date", i.tradeDate)
            put("ma5", i.ma5)
            put("ma10", i.ma10)
            put("ma20", i.ma20)
            put("dif", i.dif)
            put("dea", i.dea)
            put("macd", i.macd)
            put("rsi6", i.rsi6)
            put("kdj_k", i.kdjK)
            put("kdj_d", i.kdjD)
            put("kdj_j", i.kdjJ)
        }
    }

    private fun indexQuote(args: JsonObject): JsonObject {
        val code = args["code"]?.jsonPrimitive?.content ?: ""
        val d = StockDb.indexDetail(code)
        val r: RealtimeQuoteData? = d?.realtime
        return buildJsonObject {
            put("code", code)
            put("name", d?.info?.name ?: "")
            put("is_index", true)
            put("price", r?.price ?: 0)
            put("change", r?.change ?: 0)
            put("change_percent", r?.changePercent ?: 0)
            put("open", r?.openPrice ?: 0)
            put("high", r?.high ?: 0)
            put("low", r?.low ?: 0)
            put("volume", r?.volume ?: 0)
            put("amount", r?.amount ?: 0)
        }
    }

    private fun indexKline(args: JsonObject): JsonObject {
        val code = args["code"]?.jsonPrimitive?.content ?: ""
        val days = (args["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10).coerceIn(1, 120)
        val kline = StockDb.indexDetail(code)?.kline.orEmpty().takeLast(days)
        return buildJsonObject {
            put("code", code)
            put("is_index", true)
            put("days", days)
            put("items", buildJsonArray { kline.forEach { k ->
                add(buildJsonObject {
                    put("trade_date", k.tradeDate)
                    put("open", k.open)
                    put("close", k.close)
                    put("high", k.high)
                    put("low", k.low)
                    put("volume", k.volume)
                })
            } })
        }
    }

    private fun listIndices(args: JsonObject): JsonObject {
        val keyword = args["keyword"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val items = StockDb.listIndices(keyword).take(50)
        return buildJsonObject {
            put("count", items.size)
            put("items", buildJsonArray { items.forEach { i ->
                add(buildJsonObject {
                    put("code", i.code)
                    put("name", i.name ?: "")
                    put("change_percent", i.changePercent ?: 0)
                })
            } })
        }
    }

    private fun marketOverview(): JsonObject {
        val ov = StockDb.marketOverview()
        return buildJsonObject {
            put("total", ov.total)
            put("up", ov.up)
            put("down", ov.down)
            put("flat", ov.flat)
            put("top_gainers", buildJsonArray { ov.topGainers.forEach { g ->
                add(buildJsonObject { put("code", g.code); put("name", g.name ?: ""); put("change_percent", g.changePercent ?: 0) })
            } })
            put("top_losers", buildJsonArray { ov.topLosers.forEach { g ->
                add(buildJsonObject { put("code", g.code); put("name", g.name ?: ""); put("change_percent", g.changePercent ?: 0) })
            } })
        }
    }

    private fun orderbook(args: JsonObject): JsonObject {
        val code = args["code"]?.jsonPrimitive?.content ?: ""
        val ob = MarketRepository.orderBook(code)
        return buildJsonObject {
            put("code", code)
            if (ob == null) {
                put("note", NA)
                return@buildJsonObject
            }
            put("update_time", JsonPrimitive(ob.updateTime ?: NA))
            put("commission_ratio", ob.commissionRatio?.let { JsonPrimitive(it) } ?: JsonPrimitive(NA))
            put("bids", buildJsonArray { ob.bids.forEach { lvl ->
                add(buildJsonObject { put("price", lvl.first?.let { JsonPrimitive(it) } ?: JsonPrimitive(NA)); put("volume", lvl.second?.let { JsonPrimitive(it) } ?: JsonPrimitive(NA)) })
            } })
            put("asks", buildJsonArray { ob.asks.forEach { lvl ->
                add(buildJsonObject { put("price", lvl.first?.let { JsonPrimitive(it) } ?: JsonPrimitive(NA)); put("volume", lvl.second?.let { JsonPrimitive(it) } ?: JsonPrimitive(NA)) })
            } })
        }
    }

    private fun fundFlow(args: JsonObject): JsonObject {
        val code = args["code"]?.jsonPrimitive?.content ?: ""
        val days = (args["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5).coerceIn(1, 20)
        val flow = MarketRepository.fundFlow(code, days)
        return buildJsonObject {
            put("code", code)
            put("days", flow.size)
            put("source", flow.firstOrNull()?.source ?: "本地库未覆盖")
            put("items", buildJsonArray { flow.forEach { f ->
                add(buildJsonObject {
                    put("trade_date", f.tradeDate)
                    put("main_net", f.mainNet)
                    put("main_ratio", f.mainRatio)
                    f.superNet?.let { put("super_net", it) }
                    f.bigNet?.let { put("big_net", it) }
                })
            } })
            if (flow.isNotEmpty()) put("main_net_sum", flow.sumOf { it.mainNet })
        }
    }

    private fun minute(args: JsonObject): JsonObject {
        val code = args["code"]?.jsonPrimitive?.content ?: ""
        val count = (args["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10).coerceIn(1, 30)
        val all = MarketRepository.minute(code)
        val tail = all.takeLast(count)
        val prices = all.mapNotNull { it.price.takeIf { p -> p.isFinite() && p > 0 } }
        return buildJsonObject {
            put("code", code)
            if (all.isEmpty()) {
                put("note", NA)
                return@buildJsonObject
            }
            put("total_points", all.size)
            put("returned", tail.size)
            put("items", buildJsonArray { tail.forEach { m ->
                add(buildJsonObject {
                    put("time", m.time)
                    put("price", m.price)
                    m.avgPrice?.let { put("avg_price", it) }
                    m.volume?.let { put("volume", it) }
                })
            } })
            if (prices.isNotEmpty()) {
                put("day_open", prices.first())
                put("day_high", prices.max())
                put("day_low", prices.min())
                put("day_last", prices.last())
            }
        }
    }

    private fun events(args: JsonObject): JsonObject {
        val code = args["code"]?.jsonPrimitive?.content ?: ""
        val snap = MarketRepository.events(code)
        fun kindLabel(kind: String) = if (kind == "dividend") "分红除权" else "财报"
        return buildJsonObject {
            put("code", code)
            put("as_of", snap.asOf)
            put("covered", snap.covered)
            put("upcoming", buildJsonArray { snap.upcoming.forEach { e ->
                add(buildJsonObject { put("kind", kindLabel(e.kind)); put("date", e.date); put("label", e.label) })
            } })
            put("recent", buildJsonArray { snap.recent.forEach { e ->
                add(buildJsonObject { put("kind", kindLabel(e.kind)); put("date", e.date); put("label", e.label) })
            } })
            if (!snap.covered) put("note", "本地事件库未覆盖该股，无法区分“没有事件”与“未采集”，请勿断言无事件")
        }
    }

    private fun industryPeers(args: JsonObject): JsonObject {
        val code = args["code"]?.jsonPrimitive?.content ?: ""
        val snap = MarketRepository.industryPeers(code)
        val current = snap?.current.orEmpty()
        val sorted = current.sortedByDescending { it.quote.changePercent ?: 0.0 }
        return buildJsonObject {
            put("code", code)
            if (snap == null) {
                put("note", NA)
                return@buildJsonObject
            }
            put("industry", snap.name)
            put("snapshot_date", snap.date)
            put("sample_size", current.size)
            snap.average?.let { put("average_change_percent", it) }
            snap.relative(code)?.let { put("relative_change_percent", it) }
            put("top_gainers", buildJsonArray { sorted.take(3).forEach { m ->
                add(buildJsonObject {
                    put("code", m.quote.code); put("name", m.quote.name ?: ""); put("change_percent", m.quote.changePercent ?: 0)
                })
            } })
            put("top_losers", buildJsonArray { sorted.takeLast(3).reversed().forEach { m ->
                add(buildJsonObject {
                    put("code", m.quote.code); put("name", m.quote.name ?: ""); put("change_percent", m.quote.changePercent ?: 0)
                })
            } })
        }
    }

    private fun defineTool(name: String, description: String, required: List<String>, properties: JsonObject): JsonObject =
        buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", properties)
                put("required", buildJsonArray { required.forEach { add(it) } })
                })
            })
        }

    private fun stringProp(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun intProp(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }
}
