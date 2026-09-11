package com.kuikly.stock.pages

import com.kuikly.stock.base.BasePager

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
 * 章节内容与 docs/使用指南.md 保持一致（15 章节，2026-09-10）。
 */
@Page(AppRoutes.GUIDE)
class UserGuidePage : BasePager() {
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
                pageTitleBar(ctx, "使用指南", "首次使用 · 操作详解 · 常见问题")
                Scroller {
                    attr {
                        flex(1f)
                        flexDirectionColumn()
                        scrollEnable(true)
                        padding(left = 16f, right = 16f, bottom = 24f)
                    }

                    guideSection(ctx, "g01", "一", "第一次使用", "建议按这个顺序走一遍") {
                        guidePara("首次打开应用，按下面顺序完成配置与第一次研究：")
                        guideStep("1", "打开应用，先看首页标题下方的数据日期，确认当前行情快照是哪一天。")
                        guideStep("2", "进入“我的 → 立即更新数据”，等待更新结果。更新的是服务端已发布的行情快照，刷新不保证获得此刻的交易所实时行情。")
                        guideStep("3", "点击底部“行情”，搜索股票名称或六位代码，例如“平安银行”或“000001”。")
                        guideStep("4", "点击股票行进入详情，先看价格、涨跌幅和 K 线。")
                        guideStep("5", "点击标题栏星标，将股票加入自选。")
                        guideStep("6", "如需在线分析，进入“我的 → API 配置”，配置服务并测试连通。")
                        guideStep("7", "回到个股详情，点击“AI分析”，阅读结论后点击价位或证据日期，对照图表核查。")
                        guideNote("不配置在线 API 也可以浏览本地行情。在线 AI 请求可能消耗所配置服务的额度。")
                    }

                    guideSection(ctx, "g02", "二", "五个主要入口", "各 Tab 能做什么") {
                        guideTable(
                            listOf("入口", "可以做什么", "建议使用场景"),
                            listOf(
                                listOf("首页", "盘面概况、今日关注、研究工作台入口与刷新", "每次打开先确认数据日期"),
                                listOf("行情", "搜索股票，按涨幅/跌幅/成交量排序，进个股详情", "找股票、看走势"),
                                listOf("研究", "与 AI 对话、追问、管理历史会话", "提出具体研究问题"),
                                listOf("自选", "管理关注股票、持仓信息和提醒", "持续跟踪少量标的"),
                                listOf("我的", "配置 API、查看行情状态、手动更新数据", "初始化与排查问题"),
                            )
                        )
                        guideNote("首页读取本地数据，本身不会自动发起付费 AI 分析。列表和页面较长时上下滑动；键盘挡住列表时先收起键盘。")
                    }

                    guideSection(ctx, "g03", "三", "行情列表：找到并打开股票", "搜索、排序、进入详情") {
                        guideStep("1", "点击底部“行情”进入全市场列表。")
                        guideStep("2", "在搜索框输入名称或代码，点击“搜索”。")
                        guideStep("3", "使用“默认”“涨幅”“跌幅”“成交量”切换列表排序。")
                        guideStep("4", "点击一整行股票，打开详情页。")
                        guideStep("5", "使用左上角“返回”回到上一页。")
                        guidePara("列表通常以红色表示上涨、绿色表示下跌、灰色表示平盘。遇到价格为 0、数据为空或长期不变的股票，应先检查数据是否缺失、标的是否停牌或退市，不能直接把显示值当作可成交报价。")
                        guideNote("“000001”存在股票与指数代码重叠：平安银行为 000001.SZ，上证指数为 000001.SH。AI 提问时优先写名称，或加市场后缀。")
                    }

                    guideSection(ctx, "g04", "四", "个股详情：从上到下怎么看", "区域与使用要点") {
                        guidePara("建议依次查看“行情 → 同业 → 官方板块 → K线 → 分时 → 盘口与指标 → 资金 → AI依据”。")
                        guideTable(
                            listOf("区域", "主要内容", "使用要点"),
                            listOf(
                                listOf("标题栏", "名称、代码、星标、AI分析", "星标加入或移除自选"),
                                listOf("实时行情", "最新价、涨跌、开收高低、量额、估值", "仍需核对快照日期"),
                                listOf("同业", "样本均值和个股相对表现", "点击“排行”展开"),
                                listOf("官方板块", "所属板块指数涨幅、领涨股、涨跌家数、市值、个股相对板块与板块内排名", "点击“排行”按涨幅排序；点击“板块对照问 AI”可结合板块成分提问；快照日期见卡内"),
                                listOf("K线走势", "日/周/月K、均线、成交量、指标副图", "结合日期和周期读图"),
                                listOf("分时", "分钟价格、均价和成交量", "数据可能与行情快照不同时点"),
                                listOf("五档盘口", "买卖档位和数量", "不代表实时撮合队列"),
                                listOf("技术指标", "MACD、KDJ、RSI 数值与副图", "副图可切 MACD/KDJ/RSI"),
                                listOf("主力资金", "最近交易日、近5日、近10日", "点击某日跳转日K"),
                                listOf("AI智能解读", "结论、价位、风险和行情依据", "用证据核查结论"),
                                listOf("分析记录", "本机历史分析", "查看生成时间及依据行情日期"),
                            )
                        )
                        guideNote("AI 结论生成后，行情旁和 K 线附近会显示关联提示。历史结论可能沿用旧数据，看到“日期不同”等提示时，应先核对日期，再决定是否重新分析。")
                    }

                    guideSection(ctx, "g05", "五", "K线操作详解", "周期、指标、手势与 AI 联动") {
                        guidePara("周期和指标：")
                        guideBullet("日K / 周K / 月K：切换观察周期。周、月K由已有日线聚合，切换不会凭空增加历史数据。")
                        guideBullet("MA：切换均线显示。")
                        guideBullet("量：切换成交量显示。")
                        guideBullet("副图“关 / MACD / KDJ / RSI”：选择一个指标副图，与主图日期对齐；RSI 为 6/12/24 三线，附 30/70 超买超卖参考线。")
                        guideBullet("趋势：叠加自动支撑/压力趋势线，连接近期摆动高、低点并向右延伸。这是自动识别的参考线，不是自由绘制，也不会保存。")
                        guideBullet("“− / +”：改变可见K线数量；按钮可作为手势缩放的替代方式。")
                        guideBullet("左右箭头：移动可见区间。")
                        guideBullet("重置：恢复图表视口，便于重新观察。")
                        guideNote("显示根数受本地历史长度限制。例如总共只有 23 根，缩小视图也不会显示数年历史。")
                        guidePara("触摸手势：")
                        guideTable(
                            listOf("操作", "预期效果", "注意事项"),
                            listOf(
                                listOf("点击一根K线", "选中该日并查看数据", "有AI价位时可对照距离"),
                                listOf("未选中时横向拖动", "平移历史窗口", "已显示全部数据时移动空间有限"),
                                listOf("已选中后横向拖动", "移动查看位置，逐根读数", "与未选中时的平移行为不同"),
                                listOf("长按后横向拖动", "选择区间并查看统计", "先保持按住，再移动手指"),
                                listOf("双指张开或收拢", "缩放可见区间", "受最少显示数量和总数据量约束"),
                                listOf("竖向滑动", "滚动详情页", "尽量保持明确的竖向方向"),
                            )
                        )
                        guideNote("“趋势”开关提供的是自动识别的支撑/压力线；仍没有自由绘制、保存趋势线的工具。上述手势已接入 Android，但不同设备的手势冲突与流畅度仍需进一步回归。")
                        guidePara("AI 与图表如何联动：")
                        guideStep("1", "点击“AI分析”，等待结果生成。")
                        guideStep("2", "点击支撑、压力、目标或止损等价位，在K线上对照虚线标注。")
                        guideStep("3", "点击某根K线，查看当日数据及可用的AI价位距离。")
                        guideStep("4", "在“AI引用的行情依据”中点击“定位某日期的K线与成交量”，跳转到对应位置。")
                        guideStep("5", "日期定位会使用日K；如果本地没有该日数据，会显示提示，不会补造K线。")
                        guideNote("价位虚线是分析参考，不是委托单，也不会自动交易。")
                    }

                    guideSection(ctx, "g06", "六", "分时、盘口与技术指标", "三类数据的边界") {
                        guidePara("分时图：")
                        guideBullet("点击或横向拖动可查看对应分钟，使用均线与成交量开关调整显示。")
                        guideBullet("加载失败时点击“重试分时”。")
                        guideBullet("分时图当前没有双指缩放功能，也没有分钟级资金流叠加。")
                        guidePara("盘口：")
                        guideBullet("查看买卖五档价格与数量，并核对是否有可用数据。")
                        guideBullet("当前不应按逐笔成交、大单追踪或完整盘口队列来使用。")
                        guidePara("技术指标：")
                        guideBullet("先看数值，再切换K线中的 MACD、KDJ 或 RSI 副图进行对照。")
                        guideBullet("缺数据或样本太短时，指标解释能力有限；RSI 既有数值也可切换副图（6/12/24 三线）。")
                    }

                    guideSection(ctx, "g07", "七", "主力资金", "周期、日期定位和追问") {
                        guideStep("1", "向下滑动到“主力资金”卡片。")
                        guideStep("2", "点击“最近交易日”“近5日”或“近10日”切换统计窗口。")
                        guideStep("3", "查看“截至日期”和“实际几个交易日”，确认本次统计覆盖范围。")
                        guideStep("4", "阅读各日主力净流入、净占比和区间累计。")
                        guideStep("5", "点击一行日期，定位到对应日K。")
                        guideStep("6", "点击“结合这段资金流问 AI”，进入研究室，对当前区间的资金和K线做联合分析。")
                        guidePara("横条表示该窗口内净流入金额绝对值的相对大小；红色为净流入、绿色为净流出。切换窗口后横条的比例会重新计算，不适合跨窗口直接比较长度。")
                        guidePara("金额会以万、亿等形式显示；净占比是百分比。区间累计是已展示交易日的净流入合计。选择“近10日”但实际只有 5 日时，应按 5 日解读，不能当成完整 10 日数据。")
                        guideNote("当前以日级主力数据为主，不保证每只股票都有资金数据，也不保证超大单、大单、中单、小单分档齐全。“最近交易日”不一定是今天；没有数据时会显示空状态。")
                    }

                    guideSection(ctx, "g08", "八", "同业比较与官方板块", "样本均值、板块快照、排行与跳转") {
                        guideStep("1", "在行情卡下方找到“同业”卡片。")
                        guideStep("2", "查看“样本均值”和“个股相对”。")
                        guideStep("3", "点击“排行”，查看样本日期和同日有效股票数量。")
                        guideStep("4", "点击“切换涨幅排序”，在从高到低、从低到高之间切换。")
                        guideStep("5", "在列表内滚动查看其他股票；点击股票行进入该股详情。")
                        guideStep("6", "点击“同业对照问 AI”，让AI结合这组样本解释相对表现。")
                        guidePara("这里使用本地同一行业、同一快照日期且报价有效的股票，计算等权平均涨跌幅。无行业信息时可能不显示此卡。")
                        guidePara("“个股相对”是个股涨跌幅减去样本平均涨跌幅，单位为百分点。例如个股上涨 2%，样本平均上涨 0.5%，相对表现为 +1.5 个百分点。")
                        guideNote("这不是官方行业指数，也不是全市场实时板块榜；样本可能包含当前股票。查看强弱时应同时看日期、有效样本数量和覆盖范围。")
                        guidePara("官方板块：对照与排行")
                        guideStep("1", "在“同业”下方找到“官方板块·xxx”，显示所属东方财富行业板块快照。")
                        guideStep("2", "查看板块涨幅、领涨股、上涨/下跌家数与板块总市值；下方为“个股相对板块”和“板块内排名”。")
                        guideStep("3", "点击“排行”展开成分股列表，可切换涨幅从高到低/从低到高。")
                        guideStep("4", "点击成分股行进入该股详情；点击“板块对照问 AI”可结合板块成分与个股位置提问。")
                        guideStep("5", "卡内“快照日期”为数据日期（日级快照，非逐笔实时）；个股相对板块 = 个股涨跌幅 − 板块成分等权平均涨跌幅（百分点）。")
                        guideNote("板块数据来自东方财富官方行业板块列表与成分股，随每日数据构建生成；当日数据盘中未收盘时可能为上一交易日快照。")
                    }

                    guideSection(ctx, "g09", "九", "AI研究室", "配置、提问、追问") {
                        guidePara("配置在线服务：")
                        guideStep("1", "入口：“我的 → API配置”。填写配置名称、服务地址、模型ID和API Key。")
                        guideStep("2", "使用服务商提供的准确地址和模型名称，不要把模型显示昵称误当作模型ID。")
                        guideStep("3", "测试连通后启用。Android 端 API Key 通过本机加密存储保存。")
                        guideNote("在线提问会把问题及相关分析上下文发送到你配置的服务，请按自己的数据使用要求选择服务。")
                        guidePara("推荐的提问方式：")
                        guideTable(
                            listOf("目的", "示例"),
                            listOf(
                                listOf("初次分析", "请分析平安银行，先写明行情日期，再说明趋势、支撑压力和风险。"),
                                listOf("核查结论", "你说它偏强，具体由哪些日期的价格和成交量支持？"),
                                listOf("资金对照", "结合当前提供的近5日资金数据，说明哪些变化与K线一致，哪些存在分歧。"),
                                listOf("同业比较", "解释该股相对同业样本的表现，并注明样本数量和日期。"),
                                listOf("多股比较", "对比贵州茅台和五粮液，分别说明趋势与风险，缺失数据请明确指出。"),
                                listOf("历史复核", "这份旧分析的依据日期是什么？与最新行情有哪些变化？"),
                            )
                        )
                        guidePara("对话管理：")
                        guideBullet("点击建议追问，可沿当前主题继续提问。")
                        guideBullet("长按消息可使用复制、引用或删除等操作。")
                        guideBullet("生成中可停止；失败后可重试。")
                        guideBullet("从“历史”打开会话列表，管理新建、切换、置顶、重命名或删除。")
                        guideBullet("会话更多菜单提供 Markdown 导出或 JSON 备份等操作。")
                        guideNote("离线模式使用本地数据与模板，能力和表达深度不同于在线模型，也不会自动获得最新网络信息。")
                    }

                    guideSection(ctx, "g10", "十", "分析历史与自选提醒", "历史、持仓、提醒") {
                        guidePara("分析历史：")
                        guideBullet("个股分析生成后尝试自动保存到本机。向下找到“分析记录”，点击“展开 / 收起历史”，再选择“查看”或“删除”。")
                        guideBullet("查看历史会切换到当时的结论，应同时核对生成时间、行情日期和来源。")
                        guideBullet("个股分析记录与 AI 聊天会话是两类历史。")
                        guidePara("自选：在个股详情标题栏点击星标加入，再次点击可移除。进入“自选”管理关注股票。")
                        guidePara("持仓：在自选中录入股数与成本，供市值、盈亏和组合风险计算使用。这是本地记录，不会连接券商账户或执行交易。")
                        guidePara("提醒：可设置价格涨破、跌破或涨跌幅阈值；AI价位提供提醒入口时，先核对方向和阈值再保存。")
                        guideNote("提醒依赖应用对最新本地数据进行检查，不能视为持续后台实时监控，也不能保证锁屏后即时推送。")
                    }

                    guideSection(ctx, "g11", "十一", "组合风险", "市值、集中度与回撤") {
                        guidePara("从首页研究工作台进入“组合风险”。先在自选中录入持仓，否则可能只显示空状态。")
                        guidePara("重点查看：")
                        guideBullet("组合市值、盈亏。")
                        guideBullet("单股和行业集中度。")
                        guideBullet("命中的风险规则和提醒。")
                        guideNote("所有计算依赖已录入持仓及可用行情快照，缺失持仓或行情会影响结果。")
                    }

                    guideSection(ctx, "g12", "十二", "数据更新：判断信息是否新鲜", "三个时间点核对") {
                        guidePara("每次研究至少核对三个时间：")
                        guideStep("1", "首页或数据状态中的行情快照日期。")
                        guideStep("2", "图表、资金、同业各自显示的日期。")
                        guideStep("3", "AI分析的生成时间与依据行情日期。")
                        guidePara("生成时间较新，不代表底层行情较新。不同模块来自不同数据链路，日期可能不一致。")
                        guidePara("点击刷新是获取已发布数据，并不保证每个模块同时更新。离线时仍可读本地数据，但无法获取新的在线结果。")
                        guideNote("本文的新资金和同业能力以 Android 版为准；不要据此假定 iOS、Web 具有同等数据覆盖和手势表现。")
                    }

                    guideSection(ctx, "g13", "十三", "常见问题", "问题与处理顺序") {
                        guideTable(
                            listOf("问题", "处理方式"),
                            listOf(
                                listOf("点击个股后退回上一页", "该版本曾出现资金卡渲染崩溃，修复版已安装并验证平安银行入口；若再发生，记录股票代码、入口、时间和复现步骤"),
                                listOf("搜不到股票", "核对六位代码或完整名称，刷新后重试，确认本地库是否包含该标的"),
                                listOf("K线拖不动", "检查是否已经展示全部历史；已选中时横拖用于逐根查看，可先重置"),
                                listOf("图表日期定位失败", "当前日线可能不含该日，或证据日期与本地数据不一致"),
                                listOf("分时为空", "检查网络并点“重试分时”；仍失败时先用日K查看，不把空数据理解为零成交"),
                                listOf("近10日只显示几天", "查看“实际交易日”数量，说明当前可用数据不足10日"),
                                listOf("同业卡没有显示", "当前股票可能没有可用行业归属；不能据此判断它没有同行"),
                                listOf("AI分析失败", "先检查网络、当前启用配置、模型ID和测试连通结果，再重试"),
                                listOf("AI结论与现价不一致", "核对历史分析日期和行情日期，必要时重新分析"),
                                listOf("刷新后日期没变化", "服务端可能尚未发布新快照，或当前已是可用最新版本"),
                                listOf("提醒没有即时出现", "提醒依赖本地数据和检查时机，不是交易所实时推送"),
                            )
                        )
                        guidePara("反馈问题时提供“从哪个入口 → 点击什么 → 实际结果 → 预期结果”，附股票代码和时间；无需提供 API Key。")
                    }

                    guideSection(ctx, "g14", "十四", "一次完整的研究操作示例", "十分钟走完一个研究循环") {
                        guideStep("1", "在“我的”更新数据，确认当前快照日期。")
                        guideStep("2", "在“行情”搜索平安银行并进入详情。")
                        guideStep("3", "查看日K，用 MA 和 MACD 副图对照趋势。")
                        guideStep("4", "点击某根K线读数，再查看分时与盘口的可用数据。")
                        guideStep("5", "展开同业排行，记录样本日期、数量和相对表现。")
                        guideStep("6", "切到近5日资金，点击一天对照日K。")
                        guideStep("7", "发起 AI 分析，点击其证据日期和价位核查。")
                        guideStep("8", "如有疑问，用“结合这段资金流问 AI”或明确的问题追问。")
                        guideStep("9", "将股票加入自选，按需要记录持仓或设置提醒。")
                        guideStep("10", "下次打开时，先检查数据更新，再对照旧分析是否仍适用。")
                    }

                    guideSection(ctx, "g15", "十五", "当前功能边界", "已完成与未完成") {
                        guidePara("已提供的主要能力：")
                        guideBullet("本地行情浏览、K线与分时交互。")
                        guideBullet("AI 价位和日期联动、分析历史。")
                        guideBullet("日级资金周期切换、同业样本排行、官方行业板块快照对照。")
                        guideBullet("自选与持仓记录、价格提醒、组合风险。")
                        guidePara("以下不属于当前已完成功能：")
                        guideBullet("完整分档资金、分钟资金流叠加、板块盘中实时联动。")
                        guideBullet("自由绘制并保存趋势线（“趋势”开关仅提供自动支撑/压力线）。")
                        guideBullet("龙虎榜、融资融券，以及券商下单。")
                        guideNote("行情和 AI 分析用于辅助研究，结论应结合数据日期、覆盖范围及原始证据判断。")
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

private val GUIDE_SECTION_IDS = listOf(
    "g01", "g02", "g03", "g04", "g05", "g06", "g07", "g08",
    "g09", "g10", "g11", "g12", "g13", "g14", "g15",
)

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

/** 轻量表格：表头 + 数据行，各列按权重自动换行，适合指南中的对照表。 */
private fun ViewContainer<*, *>.guideTable(headers: List<String>, rows: List<List<String>>) {
    View {
        attr {
            marginTop(10f)
            padding(10f)
            borderRadius(12f)
            backgroundColor(0xFFF4F7FB)
        }
        // 表头
        View {
            attr { flexDirectionRow(); padding(bottom = 6f) }
            headers.forEach { h ->
                Text {
                    attr {
                        text(h)
                        fontSize(11f)
                        fontWeightBold()
                        color(0xFF0E67D1)
                        flex(1f)
                        marginRight(4f)
                    }
                }
            }
        }
        // 数据行
        rows.forEachIndexed { idx, row ->
            View {
                attr {
                    flexDirectionRow()
                    marginTop(if (idx > 0) 5f else 0f)
                }
                row.forEachIndexed { ci, cell ->
                    Text {
                        attr {
                            text(cell)
                            fontSize(12f)
                            lineHeight(18f)
                            color(0xFF3A4452)
                            flex(if (ci == 0) 1.2f else 1.4f)
                            marginRight(6f)
                        }
                    }
                }
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
