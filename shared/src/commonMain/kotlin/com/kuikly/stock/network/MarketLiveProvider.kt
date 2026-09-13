// 实时数据源：东财 push2 快照（报价/涨跌/换手量比）+ 腾讯 fqkline 历史日K回补。
// 腾讯 fqkline 与本地库 stock_daily_kline 同源同口径（前复权、量单位手→股），
// 回补后的图表与既有数据无缝衔接。push2 为公开无鉴权接口；任何失败都返回 null/空列表，
// 由调用方回落本地库，绝不把本地数据伪装成实时数据（UI 会标注数据时间与来源）。

package com.kuikly.stock.network

import com.kuikly.stock.data.LiveProvider
import com.kuikly.stock.data.nowMillis
import com.kuikly.stock.pages.KLineDataItem
import com.kuikly.stock.pages.RealtimeQuoteData
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object MarketLiveProvider : LiveProvider {

    /** 轮询用的轻量客户端：8 秒超时，避免占用 AI 的长超时通道。 */
    private val client: HttpClient by lazy {
        createHttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 8_000
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 东财 secid：沪市 6 开头前缀 1，深市/创业板/北交所前缀 0。 */
    private fun secid(code: String): String? {
        if (code.length != 6 || code.any { !it.isDigit() }) return null
        return when (code[0]) {
            '6' -> "1.$code"
            '0', '3', '4', '8' -> "0.$code"
            else -> null
        }
    }

    /** 腾讯行情符号：sh600519 / sz000001。 */
    private fun txSymbol(code: String): String? {
        if (code.length != 6 || code.any { !it.isDigit() }) return null
        return when (code[0]) {
            '6' -> "sh$code"
            '0', '3' -> "sz$code"
            '4', '8' -> "bj$code"
            else -> null
        }
    }

    private suspend fun getText(url: String): String? =
        try {
            client.get(url) {
                header("Referer", "https://gu.qq.com/")
                header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36")
            }.bodyAsText()
        } catch (e: Throwable) {
            println("[Live] GET fail: ${e.message ?: e} · ${url.take(70)}")
            null
        }

    private fun JsonObject?.num(key: String): Double? = when (val v = this?.get(key)) {
        null -> null
        is kotlinx.serialization.json.JsonPrimitive ->
            v.content.toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    override suspend fun realtimeQuote(code: String): RealtimeQuoteData? {
        val sid = secid(code) ?: return null
        // push2 系主机在部分网络下 TLS 被重置，管道经验：push2delay 全局可达，故双主机回退
        val fields = "f43,f44,f45,f46,f47,f48,f50,f51,f52,f60,f163,f167,f116,f117,f168,f169,f170"
        val data = listOf("push2delay", "push2").firstNotNullOfOrNull { host ->
            getText("https://$host.eastmoney.com/api/qt/stock/get" +
                "?ut=fa5fd1943c7b386f172d6893dbfba10b&invt=2&fltt=2&secid=$sid&fields=$fields"
            )?.let { body ->
                runCatching { json.parseToJsonElement(body).jsonObject["data"]?.jsonObject }.getOrNull()
            }
        } ?: return null
        val price = data.num("f43") ?: return null   // 停牌等场景 f43 可能为 "-"，视为无实时数据
        return RealtimeQuoteData(
            code = code,
            name = null,                             // 名称以本地库为准
            price = price,
            change = data.num("f169"),
            changePercent = data.num("f170"),
            openPrice = data.num("f46"),
            preClose = data.num("f60"),
            high = data.num("f44"),
            low = data.num("f45"),
            // f47 单位为"手"，×100 与本地库"股"的口径对齐
            volume = (data.num("f47") ?: 0.0) * 100,
            amount = data.num("f48"),
            peTtm = data.num("f163"),
            pb = data.num("f167"),
            turnoverRate = data.num("f168"),
            volumeRatio = data.num("f50"),
            totalMarketCap = data.num("f116"),
            circulateMarketCap = data.num("f117"),
            limitUp = data.num("f51"),
            limitDown = data.num("f52"),
            updateTime = beijingClock(),
        )
    }

    /**
     * 个股历史日K：腾讯 fqkline（前复权，约一年）。volume 从"手"换算成"股"，
     * 与本地库 stock_daily_kline 的单位约定保持一致。
     */
    suspend fun dailyKline(code: String, limit: Int = 250): List<KLineDataItem> {
        val symbol = txSymbol(code) ?: return emptyList()
        println("[Live] dailyKline v3 $symbol limit=$limit")
        val text = getText(
            "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$symbol,day,,,$limit,qfq"
        ) ?: return emptyList()
        return runCatching {
            val node = try { json.parseToJsonElement(text).jsonObject } catch (e: Throwable) {
                println("[Live] kline 非 JSON 响应: ${text.take(160)}")
                return emptyList()
            }
                .get("data")?.jsonObject?.get(symbol)?.jsonObject ?: run {
                println("[Live] kline 响应无 data/$symbol: ${text.take(200)}"); return emptyList()
            }
            val arr = (node["qfqday"] ?: node["day"])?.jsonArray ?: run {
                println("[Live] kline 响应无 qfqday/day，keys=${node.keys}: ${node.toString().take(200)}"); return emptyList()
            }
            if (arr.isEmpty()) println("[Live] kline qfqday 为空: ${text.take(220)}")
            val parsed = arr.mapNotNull { el ->
                val line = el.toString().filter { it !in """[]""" && it != '"' }
                val p = line.split(",")
                if (p.size < 6) return@mapNotNull null
                KLineDataItem(
                    code = code,
                    tradeDate = p[0],
                    open = p[1].toDoubleOrNull() ?: return@mapNotNull null,
                    close = p[2].toDoubleOrNull() ?: return@mapNotNull null,
                    high = p[3].toDoubleOrNull() ?: return@mapNotNull null,
                    low = p[4].toDoubleOrNull() ?: return@mapNotNull null,
                    // 腾讯返回"手"，本地库为"股"
                    volume = (p[5].toDoubleOrNull() ?: 0.0) * 100,
                    amount = 0.0,
                )
            }
            if (parsed.isEmpty()) println("[Live] kline 解析后为空: 原始首行=${arr.firstOrNull().toString().take(160)}")
            parsed
        }.getOrDefault(emptyList())
    }

    /** UTC+8 无夏令时，直接由 epoch 推导北京时间钟面。 */
    fun beijingClock(): String {
        val total = nowMillis() / 1000 + 8 * 3600
        val secsOfDay = (total % 86_400).toInt()
        val h = secsOfDay / 3600
        val m = (secsOfDay % 3600) / 60
        val s = secsOfDay % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    /** 是否处于 A 股交易时段（9:15–11:30、13:00–15:00，周一~周五）。 */
    fun isTradingTime(nowMillisLong: Long = nowMillis()): Boolean {
        val total = nowMillisLong / 1000 + 8 * 3600
        val days = total / 86_400
        val mondayIndex = (((days % 7) + 7 + 3) % 7).toInt()   // 0=周一
        if (mondayIndex >= 5) return false
        val minutes = (total % 86_400).toInt() / 60
        return (minutes >= 9 * 60 + 15 && minutes <= 11 * 60 + 30) ||
            (minutes >= 13 * 60 && minutes <= 15 * 60)
    }
}

private typealias JsonObject = kotlinx.serialization.json.JsonObject
