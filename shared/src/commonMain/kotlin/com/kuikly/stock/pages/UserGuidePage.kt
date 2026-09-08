package com.kuikly.stock.pages

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.AccessibilityRole
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 内置使用指南：功能介绍、提问示例、常见问题。内容均为静态文本，
 * 每个章节可折叠；所有功能描述必须与实际实现一致，改功能时同步改这里。
 */
@Page(AppRoutes.GUIDE)
class UserGuidePage : Pager() {
    internal var expanded: ObservableList<String> by observableList()

    override fun didInit() {
        super.didInit()
        expanded.addAll(GUIDE_SECTION_IDS)
    }

    internal fun toggleSection(id: String) {
        val cur = expanded.toList()
        expanded.clear()
        expanded.addAll(if (cur.contains(id)) cur - id else cur + id)
    }

    internal fun open(route: String) {
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(route, JSONObject())
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    flexDirectionColumn()
                    backgroundColor(0xFFF4F7FB)
                }
                pageTitleBar(ctx, "使用指南", "功能 · 提问示例 · 常见问题")
                Scroller {
                    attr {
                        flex(1f)
                        flexDirectionColumn()
                        scrollEnable(true)
                        padding(left = 16f, right = 16f, bottom = 24f)
                    }
                    guideSection(ctx, "home", "一", "认识首页", "盘面、入口与刷新") {
                        guidePara("首页是全应用的入口，只读本地行情，不会消耗 AI 额度：")
                        guideBullet("今日盘面：深色卡片展示市场一句话点评与上涨 / 下跌 / 平盘家数。")
                        guideBullet("研究工作台：四个入口，分别进入 AI 研究室、组合风险、全市场、自选盯盘。")
                        guideBullet("今日关注：价格提醒触发会置顶显示在这里，其次是自选信号。")
                        guideBullet("右上角刷新：把本地行情库更新到最新快照；数据日期显示在标题下方。")
                        guideBullet("底部导航：首页、行情、研究（AI 对话）、自选、我的，共五个 Tab。")
                    }
                    guideSection(ctx, "chat", "二", "AI 研究室：对话怎么问", "核心功能，建议细读") {
                        guidePara("直接用中文提问即可，说股票名字或 6 位代码都可以。AI 会结合本地行情作答，回答由三部分组成：文字分析、可点击的卡片、追问建议。")
                        guideExample(
                            "帮我分析 600519",
                            "返回贵州茅台的文字点评 + 个股卡（现价/涨跌幅，点击进详情页）+ 结论卡（偏强/偏弱/中性、支撑压力位，可展开看技术依据）。"
                        )
                        guideExample(
                            "上证指数今天怎么样",
                            "返回指数点评 + 指数卡。注意指数没有 PE/PB，只有行情点位与市场广度。"
                        )
                        guideExample(
                            "对比一下茅台和五粮液",
                            "返回多标的对比：文字小结 + 对比卡片，关键指标并排展示。"
                        )
                        guideExample(
                            "今天大盘怎么样？涨幅榜有哪些？",
                            "返回市场概览：涨跌家数、领涨个股与热点结构。不指定股票时可以这样问大盘。"
                        )
                        guidePara("对话过程中的常用操作：")
                        guideBullet("点蓝色追问 chip：把它当成下一问直接发送，顺着话题深挖。")
                        guideBullet("长按一条消息：复制、引用或删除；引用后可以针对那段话追问。")
                        guideBullet("停止生成 / 重试回答：回答太长可随时打断；失败或不满意可重试。")
                        guideBullet("右上角抽屉：历史对话列表，可新建、切换、置顶、重命名、删除。")
                        guideBullet("导出会话：在历史对话的更多菜单里导出 Markdown（分享 / 存下载文件夹 / 复制）或 JSON 备份。")
                        guideNote("抽屉里可切换在线 / 离线模式：在线模式联网调用 AI（需先配置 API）；离线模式用本地数据库模板作答，不花钱、没网也能查行情。")
                        guideTry(ctx, "去 AI 研究室提问", AppRoutes.CHAT)
                        guideTry(ctx, "去配置 API", AppRoutes.API_CONFIG)
                    }
                    guideSection(ctx, "market", "三", "行情与详情页", "搜索、K线与 AI 分析") {
                        guidePara("行情 Tab（全市场）支持按名称 / 代码搜索，按涨跌幅排序，点任意股票进入详情页。")
                        guideBullet("个股详情：分时走势、五档盘口、日 K 线、技术指标一页看全。")
                        guideBullet("右上角 AI分析：对当前股票生成一份图文分析（在线模式调用 AI，离线用本地模板）。")
                        guideBullet("右上角星标：一点即可加入自选，再点取消。")
                        guideBullet("指数也有独立详情页：点位、区间与 AI 解读，入口在对话的指数卡或行情页。")
                    }
                    guideSection(ctx, "watch", "四", "自选盯盘", "持仓与价格提醒") {
                        guidePara("在个股详情页点星标加入自选后，可以继续做两件事：")
                        guideStep("1", "设置持仓：在自选行点设置，填股数与成本，自选页会算持仓市值与盈亏，也是风险中心的数据来源。")
                        guideStep("2", "设置提醒：价格涨破 / 跌破某个价位，或涨跌幅达到阈值时提醒。共四种类型，各设各的阈值。")
                        guideNote("提醒在打开首页或风险中心时按最新本地行情判断；触发后会出现在首页今日关注顶部与风险中心已触发列表中。")
                    }
                    guideSection(ctx, "risk", "五", "组合风险", "市值、集中度与回撤") {
                        guidePara("风险中心汇总你的持仓风险，前提是在自选里设置了持仓股数与成本：")
                        guideBullet("组合市值与累计盈亏：按最新行情实时计算。")
                        guideBullet("集中度：最高单股占比、最高行业占比，过高会提示。")
                        guideBullet("回撤规则：命中高回撤等规则时列出，逐条核对。")
                        guideBullet("已触发提醒：与首页今日关注同步的价格提醒。")
                        guideNote("没有持仓数据时这里是空状态，按提示去自选页补录即可；缺行情的持仓会明确标出，不会用 0 伪装市值。")
                    }
                    guideSection(ctx, "api", "六", "API 配置", "在线 AI 的钥匙") {
                        guidePara("在线模式的 AI 问答、AI 分析都要经过这里配置。入口：我的 → API 配置。")
                        guideStep("1", "新建配置：填名称、服务地址（Base URL）、模型 ID（多个用英文逗号分隔）、API Key。")
                        guideStep("2", "测试连通：点测试，成功会显示服务商、模型与耗时。")
                        guideStep("3", "启用：多个配置可随时切换当前生效的一个。")
                        guideNote("API Key 只保存在本机加密存储中，不写入聊天记录、源码或日志。兼容 OpenAI 接口规范的服务都可以使用。")
                        guideTry(ctx, "去配置 API", AppRoutes.API_CONFIG)
                    }
                    guideSection(ctx, "data", "七", "数据更新与离线模式", "本地行情库") {
                        guidePara("应用内置一份本地 A 股行情库，首页、行情页、离线问答都读它：")
                        guideBullet("手动更新：首页右上角刷新，或我的 → 立即更新数据。")
                        guideBullet("自动更新：联网时后台会定期检查并下载最新快照。")
                        guideBullet("离线模式：断网或想省额度时用，行情照查，AI 解读换成低配本地模板。")
                    }
                    guideSection(ctx, "faq", "八", "常见问题", "先看这里") {
                        guidePara("AI 没反应或报错？")
                        guideBullet("确认抽屉里是“在线模式”，且已配置 API 并测试连通成功。")
                        guideBullet("确认手机联网；失败的消息点“重试回答”即可。")
                        guidePara("000001 到底是平安银行还是上证指数？")
                        guideBullet("同一串代码分属两个市场：000001.SZ 是深交所平安银行，000001.SH 是上证指数。提问时带上市场后缀或直接说名字即可区分。")
                        guidePara("数据不是今天的？")
                        guideBullet("点首页右上角刷新；标题下的数据日期会告诉你当前快照是哪天的。")
                        guidePara("导出的文件在哪里？")
                        guideBullet("存在系统下载文件夹，文件名取会话标题；分享与复制不需要找文件。")
                    }
                    Text {
                        attr {
                            text("行情与分析仅供学习研究，不构成投资建议")
                            fontSize(11f)
                            color(0xFF929CAB)
                            margin(top = 18f, bottom = 8f)
                            textAlignCenter()
                        }
                    }
                }
            }
        }
    }
}

private val GUIDE_SECTION_IDS = listOf("home", "chat", "market", "watch", "risk", "api", "data", "faq")

private fun ViewContainer<*, *>.guideSection(
    ctx: UserGuidePage,
    id: String,
    num: String,
    title: String,
    desc: String,
    content: ViewContainer<*, *>.() -> Unit
) {
    View {
        attr {
            marginTop(12f)
            padding(left = 16f, top = 14f, right = 16f, bottom = 16f)
            borderRadius(16f)
            backgroundColor(Color.WHITE)
        }
        View {
            attr {
                flexDirectionRow()
                alignItems(FlexAlign.CENTER)
                accessibility("$title，${if (ctx.expanded.contains(id)) "已展开" else "已收起"}")
                accessibilityRole(AccessibilityRole.BUTTON)
                accessibilityInfo(true, false)
            }
            event { click { ctx.toggleSection(id) } }
            View {
                attr { size(30f, 30f); borderRadius(15f); allCenter(); backgroundColor(0xFFE8F2FF) }
                Text { attr { text(num); fontSize(14f); fontWeightBold(); color(0xFF0E67D1) } }
            }
            View {
                attr { flex(1f); marginLeft(10f) }
                Text { attr { text(title); fontSize(16f); fontWeightBold(); color(0xFF14263D) } }
                Text { attr { text(desc); fontSize(11f); color(0xFF8A94A3); marginTop(2f) } }
            }
            Text {
                attr {
                    text(if (ctx.expanded.contains(id)) "收起" else "展开")
                    fontSize(12f)
                    color(0xFF0E67D1)
                }
            }
        }
        vif({ ctx.expanded.contains(id) }) {
            content()
        }
    }
}

private fun ViewContainer<*, *>.guidePara(text: String) {
    Text {
        attr {
            text(text)
            fontSize(13f)
            lineHeight(20f)
            color(0xFF3A4452)
            marginTop(10f)
        }
    }
}

private fun ViewContainer<*, *>.guideBullet(text: String) {
    View {
        attr { flexDirectionRow(); marginTop(7f) }
        Text { attr { text("•"); fontSize(13f); color(0xFF0E67D1); marginRight(7f) } }
        View {
            attr { flex(1f) }
            Text { attr { text(text); fontSize(13f); lineHeight(20f); color(0xFF3A4452) } }
        }
    }
}

private fun ViewContainer<*, *>.guideStep(num: String, text: String) {
    View {
        attr { flexDirectionRow(); marginTop(8f) }
        View {
            attr { size(20f, 20f); borderRadius(10f); allCenter(); backgroundColor(0xFF0E67D1); marginRight(8f); marginTop(1f) }
            Text { attr { text(num); fontSize(11f); fontWeightBold(); color(Color.WHITE) } }
        }
        View {
            attr { flex(1f) }
            Text { attr { text(text); fontSize(13f); lineHeight(20f); color(0xFF3A4452) } }
        }
    }
}

private fun ViewContainer<*, *>.guideExample(question: String, answer: String) {
    View {
        attr {
            marginTop(10f)
            padding(12f)
            borderRadius(12f)
            backgroundColor(0xFFF4F7FB)
        }
        View {
            attr { flexDirectionRow(); justifyContent(com.tencent.kuikly.core.layout.FlexJustifyContent.FLEX_END) }
            View {
                attr { borderRadius(12f); backgroundColor(0xFFE3F2FD); padding(left = 10f, top = 7f, right = 10f, bottom = 7f) }
                Text { attr { text(question); fontSize(12f); fontWeightBold(); color(0xFF0E67D1) } }
            }
        }
        Text {
            attr {
                text(answer)
                fontSize(12f)
                lineHeight(19f)
                color(0xFF4A5568)
                marginTop(8f)
            }
        }
    }
}

private fun ViewContainer<*, *>.guideNote(text: String) {
    View {
        attr {
            marginTop(10f)
            padding(left = 12f, top = 9f, right = 12f, bottom = 9f)
            borderRadius(10f)
            backgroundColor(0xFFE8F2FF)
        }
        Text { attr { text(text); fontSize(12f); lineHeight(19f); color(0xFF165D9E) } }
    }
}

private fun ViewContainer<*, *>.guideTry(ctx: UserGuidePage, label: String, route: String) {
    View {
        attr {
            marginTop(10f)
            height(44f)
            borderRadius(22f)
            allCenter()
            backgroundColor(0xFF0E67D1)
            accessibility(label)
            accessibilityRole(AccessibilityRole.BUTTON)
            accessibilityInfo(true, false)
        }
        event { click { ctx.open(route) } }
        Text { attr { text(label); fontSize(13f); fontWeightBold(); color(Color.WHITE) } }
    }
}
