// 调用工具层：定义 AI 可调用的行情工具（OpenAI 兼容 function-calling schema）， 并把工具调用映射到本地 SQLite (StockDb)。这样提示词不必预注入全部数据， 模型按需取数，且只引用工具真实返回的

package com.kuikly.stock.ai.tool

import com.kuikly.stock.data.StockDb
import com.kuikly.stock.pages.RealtimeQuoteData
import kotlinx.serialization.json.*
import kotlinx.serialization.json.buildJsonObject

object StockTools {

    val definitions: JsonArray = buildJsonArray {
        add(defineTool(
            name = "get_stock_quote",
            description = "获取单只股票的实时行情快照（最新价、涨跌幅、成交量额、PE/PB）。必须提供股票代码。",
            required = listOf("code"),
            properties = buildJsonObject { put("code", stringProp("6位股票代码，如 000001")) }
        ))
        add(defineTool(
            name = "get_stock_kline",
            description = "获取单只股票最近 N 个交易日日K线（前复权）。",
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
            name = "get_market_overview",
            description = "获取全市场涨跌家数概览与涨跌幅榜（top 3）。",
            required = emptyList(),
            properties = buildJsonObject { }
        ))
    }

    suspend fun execute(name: String, args: JsonObject): JsonObject = try {
        when (name) {
            "get_stock_quote" -> quote(args)
            "get_stock_kline" -> kline(args)
            "get_stock_indicator" -> indicator(args)
            "get_market_overview" -> marketOverview()
            else -> buildJsonObject { put("error", "未知工具: $name") }
        }
    } catch (e: Throwable) {
        buildJsonObject { put("error", (e.message ?: e.toString()).take(120)) }
    }

    private fun quote(args: JsonObject): JsonObject {
        val code = args["code"]?.jsonPrimitive?.content ?: ""
        val d = StockDb.stockDetail(code)
        val r: RealtimeQuoteData? = d?.realtime
        return buildJsonObject {
            put("code", code)
            put("name", d?.info?.name ?: "")
            put("price", r?.price ?: 0)
            put("change", r?.change ?: 0)
            put("change_percent", r?.changePercent ?: 0)
            put("open", r?.openPrice ?: 0)
            put("high", r?.high ?: 0)
            put("low", r?.low ?: 0)
            put("volume", r?.volume ?: 0)
            put("amount", r?.amount ?: 0)
            put("pe_ttm", r?.peTtm ?: 0)
            put("pb", r?.pb ?: 0)
        }
    }

    private fun kline(args: JsonObject): JsonObject {
        val code = args["code"]?.jsonPrimitive?.content ?: ""
        val days = args["days"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10
        val kline = StockDb.stockDetail(code)?.kline.orEmpty().takeLast(days)
        return buildJsonObject {
            put("code", code)
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

    private fun indicator(args: JsonObject): JsonObject {
        val code = args["code"]?.jsonPrimitive?.content ?: ""
        val i = StockDb.stockDetail(code)?.indicator
        return buildJsonObject {
            put("code", code)
            put("trade_date", i?.tradeDate ?: "")
            put("ma5", i?.ma5 ?: 0)
            put("ma10", i?.ma10 ?: 0)
            put("ma20", i?.ma20 ?: 0)
            put("dif", i?.dif ?: 0)
            put("dea", i?.dea ?: 0)
            put("macd", i?.macd ?: 0)
            put("rsi6", i?.rsi6 ?: 0)
            put("kdj_k", i?.kdjK ?: 0)
            put("kdj_d", i?.kdjD ?: 0)
            put("kdj_j", i?.kdjJ ?: 0)
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
