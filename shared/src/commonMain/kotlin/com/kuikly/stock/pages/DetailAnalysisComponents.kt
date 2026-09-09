package com.kuikly.stock.pages

import com.kuikly.stock.data.AnalysisHistoryStore
import com.kuikly.stock.data.AnalysisSnapshot
import com.kuikly.stock.data.exportTimestampString
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.views.*

internal class DetailAnalysisState(val kind: String) {
    private val store = AnalysisHistoryStore()
    var analysis by observable<AIAnalysisData?>(null)
    var records by observable<List<AnalysisSnapshot>>(emptyList())
    var selectedId by observable("")
    var expanded by observable(false)
    var notice by observable("")

    fun restore(code: String) {
        records = store.list(kind, code)
        select(records.firstOrNull())
    }

    fun accept(result: AIAnalysisData) {
        analysis = result
        if (store.save(kind, result)) {
            records = store.list(kind, result.code)
            selectedId = records.firstOrNull()?.id.orEmpty()
            notice = "分析已保存到本机"
        } else {
            selectedId = ""
            notice = "分析已生成，但本机保存失败；请稍后重试"
        }
    }

    fun select(record: AnalysisSnapshot?) {
        selectedId = record?.id.orEmpty()
        analysis = record?.result
    }

    fun delete(record: AnalysisSnapshot) {
        if (!store.delete(record.id)) { notice = "删除失败，请重试"; return }
        records = store.list(kind, record.result.code)
        if (selectedId == record.id) select(records.firstOrNull())
        notice = "已删除这条分析记录"
    }
}

internal fun ViewContainer<*, *>.detailAction(label: String, action: () -> Unit) {
    View {
        attr { padding(8f, 10f, 8f, 10f); marginTop(4f); marginRight(6f); borderRadius(8f); backgroundColor(0xFFE8F2FF) }
        event { click { action() } }
        Text { attr { text(label); fontSize(12f); color(0xFF1976D2) } }
    }
}

internal fun ViewContainer<*, *>.analysisHistoryPanel(state: DetailAnalysisState, onChange: () -> Unit) {
    View {
        attr { padding(12f); margin(6f, 12f, 6f, 12f); backgroundColor(0xFFFFFFFF); borderRadius(10f) }
        Text { attr { text("分析记录 · ${state.records.size} 条"); fontSize(14f); fontWeightBold(); color(0xFF26384A) } }
        Text {
            attr {
                val a = state.analysis
                text(if (a == null) "分析后自动保存，返回详情可继续查看" else
                    "${a.source}\n生成：${exportTimestampString(a.generatedAt)} · 行情：${a.dataDate.ifBlank { "未提供" }}\n历史分析基于当时快照，请核对最新行情")
                fontSize(11f); lineHeight(17f); color(0xFF627083); marginTop(6f)
            }
        }
        Text { attr { text(state.notice); fontSize(11f); color(0xFFA56100); marginTop(4f) } }
        detailAction("展开 / 收起历史") { state.expanded = !state.expanded }
        vif({ state.expanded }) {
            Scroller {
                attr { height(200f); marginTop(6f); flexDirectionColumn() }
                vfor({ ObservableList(state.records.toMutableList()) }) { record ->
                    View {
                        attr { padding(8f); marginBottom(5f); backgroundColor(if (state.selectedId == record.id) 0xFFE8F2FF else 0xFFF5F7FA); borderRadius(8f) }
                        Text { attr { text("${exportTimestampString(record.result.generatedAt)} · ${record.result.source}"); fontSize(11f); color(0xFF26384A) } }
                        View {
                            attr { flexDirectionRow() }
                            detailAction("查看") { state.select(record); state.notice = "正在查看历史分析"; onChange() }
                            detailAction("删除") { state.delete(record); onChange() }
                        }
                    }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.chartEvidencePanel(
    bars: () -> List<KLineDataItem>, selected: () -> Int, analysis: () -> AIAnalysisData?,
    focus: (Int) -> Unit, ask: () -> Unit,
) {
    View {
        attr { marginTop(8f); padding(10f); backgroundColor(0xFFF1F7FF); borderRadius(10f) }
        Text { attr { text("行情依据 · 与K线联动"); fontSize(13f); fontWeightBold(); color(0xFF26384A) } }
        Text { attr { text(candleEvidence(bars(), selected())); fontSize(11f); lineHeight(17f); color(0xFF43576C); marginTop(6f) } }
        Text {
            attr {
                val date = bars().getOrNull(selected())?.tradeDate
                val reasons = aiDatedEvidence(analysis()).filter { normalizedTradeDate(it.date) == date?.let(::normalizedTradeDate) }
                text(if (reasons.isEmpty()) "AI 尚未给出该日的明确依据，可携带选中行情追问" else
                    "AI 对该日的解释：" + reasons.joinToString("；") { it.reason })
                fontSize(11f); lineHeight(17f); color(0xFF665094); marginTop(6f)
            }
        }
        detailAction("携带选中行情问 AI") { ask() }
        Text { attr { text("以下为行情计算的候选信号，点击定位；并非模型已验证的结论"); fontSize(10f); color(0xFF627083); marginTop(8f) } }
        vfor({ ObservableList(chartEvidence(bars()).toMutableList()) }) { evidence ->
            detailAction("${bars().getOrNull(evidence.index)?.tradeDate.orEmpty()} · ${evidence.title}") { focus(evidence.index) }
        }
    }
}

internal data class DatedAnalysisEvidence(val date: String, val reason: String)

internal fun aiDatedEvidence(analysis: AIAnalysisData?): List<DatedAnalysisEvidence> = analysis?.cards.orEmpty()
    .filter { it["type"] == "evidence_card" }.flatMap { it["items"] as? List<*> ?: emptyList<Any?>() }
    .mapNotNull { raw ->
        val item = raw as? Map<*, *> ?: return@mapNotNull null
        val date = item["date"] as? String ?: return@mapNotNull null
        val reason = item["reason"] as? String ?: return@mapNotNull null
        DatedAnalysisEvidence(date, reason)
    }

internal fun ViewContainer<*, *>.aiEvidencePanel(analysis: () -> AIAnalysisData?, focusDate: (String) -> Unit) {
    View {
        attr { padding(10f); marginTop(8f); backgroundColor(0xFFF1F7FF); borderRadius(10f) }
        Text { attr { text("AI 引用的行情依据"); fontSize(13f); fontWeightBold(); color(0xFF26384A) } }
        vif({ aiDatedEvidence(analysis()).isEmpty() }) {
            Text { attr { text("本次分析未提供可定位日期。可在图表选择行情后追问。"); fontSize(11f); color(0xFF627083); marginTop(6f) } }
        }
        vfor({ ObservableList(aiDatedEvidence(analysis()).toMutableList()) }) { evidence ->
            Text { attr { text(evidence.reason); fontSize(12f); lineHeight(18f); color(0xFF43576C); marginTop(8f) } }
            detailAction("定位 ${evidence.date} 的K线与成交量") { focusDate(evidence.date) }
        }
    }
}
