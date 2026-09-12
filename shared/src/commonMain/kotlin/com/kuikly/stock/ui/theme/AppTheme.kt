// 设计 token 唯一来源：颜色 / 圆角 / 间距 / 字号 / 动效 / 尺寸。
//
// 【为什么需要这个文件】
// 收敛前，同一个色值在 90 个文件里以字面量形式重复出现（抽样：0xFFFFFFFF ×120、
// 0xFF1976D2 ×79、0xFF0E67D1 ×34、0xFF172A43 ×12、0xFFF4F7FB ×11……）。
// 后果有两个：一是改一次主色要全仓搜索替换，二是同语义的两个值会慢慢漂移
// （例如「辅助文字」同时存在 0xFF8792A1 / 0xFF9AA4B2 / 0xFF8A94A3 三种）。
//
// 所以这里做的是「给语义命名」，不是「换个颜色」——**浅色那一套的值全部取自现有代码里
// 已经在用的颜色**，引用 token 不会改变任何一处的现有观感。
//
// 【深色模式怎么接进来的】
// `AppColor` 不再是 `const val` 的平铺常量，而是**每次取值都问一次当前色板**
// （`activePalette()` 依据 `ThemeManager.isDark` 在 `LIGHT_PALETTE` / `DARK_PALETTE`
// 之间选）。取值过程还顺手读一次当前页面的主题世代，这一步是「订阅主题」的关键 ——
// 机制与理由写在 `Theme.kt` 头部，那里是事实来源。
//
// 对调用方来说什么都没变：`AppColor.SURFACE` 依旧是一个 `Long` 表达式，
// 全文 800 多处引用一行都不用改。
//
// 【纪律】
//   - 新增颜色/圆角/字号一律先用这里的 token；确实是一次性特例才允许字面量；
//   - 涨跌方向色**不在**这里定义，仍以 `data/StockColors` 为唯一来源。
//     这里只做转发，免得组件代码为了一个红绿再引第二个包。
//   - **涨跌这五个 token 深浅两套取值必须一致**，理由见 `UP_ALT` 处注释。
//   - token 命名按语义（TEXT_SUB / SURFACE / DANGER），不按外观（GRAY_500）。
//     外观会随换肤变，语义不会。
//
// Kuikly 的 `color(...)` / `Color(...)` 均接受 Long(ARGB)，所以 token 直接用 Long。

package com.kuikly.stock.ui.theme

import com.kuikly.stock.data.StockColors
import com.tencent.kuikly.core.manager.PagerManager

/**
 * 一套完整色板。字段名与 [AppColor] 一一对应。
 *
 * 故意 `private`：外部只应该经 [AppColor] 取色。谁要是直接抓某一套色板用，
 * 就等于绕过了主题，深色模式下必然漏掉那一处。
 */
private class Palette(
    // --- 品牌主色 ---
    val PRIMARY: Long,
    val PRIMARY_SOFT: Long,
    val PRIMARY_BG: Long,
    val PRIMARY_BG_LIGHT: Long,
    val PRIMARY_TEXT: Long,
    val INK_PANEL: Long,
    val INK_PANEL_ALT: Long,
    val INK_PANEL_SOFT: Long,
    // --- 遗留灰阶 ---
    val TEXT_INK: Long,
    val TEXT_GRAY: Long,
    val TEXT_HINT: Long,
    val TEXT_HINT_SOFT: Long,
    val TEXT_DEEP: Long,
    val TEXT_SUB_DEEP: Long,
    val TEXT_WARM: Long,
    // --- 文字 ---
    val TITLE: Long,
    val TEXT: Long,
    val TEXT_STRONG: Long,
    val TEXT_SUB: Long,
    val TEXT_MUTED: Long,
    val ON_DARK: Long,
    val ON_DARK_SUB: Long,
    val ON_DARK_MUTED: Long,
    // --- 面与线 ---
    val SURFACE: Long,
    val SURFACE_ALT: Long,
    val BG: Long,
    val DIVIDER: Long,
    val DIVIDER_SOFT: Long,
    val SURFACE_SOFT: Long,
    val TRACK: Long,
    val SURFACE_TINT: Long,
    val BG_SOFT: Long,
    val SCRIM: Long,
    val DISABLED: Long,
    val ON_DARK_ACCENT: Long,
    val ON_DARK_ACCENT_STRONG: Long,
    // --- 语义色 ---
    val DANGER: Long,
    val DANGER_BG: Long,
    val DANGER_TEXT: Long,
    val SUCCESS: Long,
    val SUCCESS_BG: Long,
    val WARNING: Long,
    val WARNING_BG: Long,
    val WARNING_TEXT: Long,
    val WARNING_TEXT_DEEP: Long,
    // --- 紫色族（AI 结论卡 / 自选入口标记 / 日历「财报」点）---
    val VIOLET: Long,
    val VIOLET_DEEP: Long,
    val VIOLET_BG: Long,
    // --- 强调底色 / 描边 / 占位 ---
    val HIGHLIGHT_BG: Long,
    val SUCCESS_LINE: Long,
    val SKELETON_BG: Long,
    val SKELETON_BG_STRONG: Long,
    val PRESS_BG: Long,
    val ACCENT: Long,
    val NEUTRAL: Long,
    val INFO_BG: Long,
    // --- 涨跌方向色 ---
    val UP: Long,
    val DOWN: Long,
    val FLAT: Long,
    val UP_ALT: Long,
    val DOWN_ALT: Long,
)

/** 浅色（原有配色，一像素不动）。 */
private val LIGHT_PALETTE = Palette(
    PRIMARY = 0xFF0E67D1,
    PRIMARY_SOFT = 0xFF1976D2,
    PRIMARY_BG = 0xFFE3F2FD,
    PRIMARY_BG_LIGHT = 0xFFE8F2FF,
    PRIMARY_TEXT = 0xFF165D9E,
    INK_PANEL = 0xFF0B2B50,
    INK_PANEL_ALT = 0xFF174D7C,
    INK_PANEL_SOFT = 0xFF12355F,
    TEXT_INK = 0xFF333333,
    TEXT_GRAY = 0xFF666666,
    TEXT_HINT = 0xFF999999,
    TEXT_HINT_SOFT = 0xFF888888,
    TEXT_DEEP = 0xFF26384A,
    TEXT_SUB_DEEP = 0xFF627083,
    TEXT_WARM = 0xFF5D4037,
    TITLE = 0xFF12263F,
    TEXT = 0xFF1D3048,
    TEXT_STRONG = 0xFF172A43,
    TEXT_SUB = 0xFF8792A1,
    TEXT_MUTED = 0xFF9AA4B2,
    ON_DARK = 0xFFFFFFFF,
    ON_DARK_SUB = 0xFFA9CBEA,
    ON_DARK_MUTED = 0xFF9EB2C7,
    SURFACE = 0xFFFFFFFF,
    SURFACE_ALT = 0xFFF7F8FA,
    BG = 0xFFF4F7FB,
    DIVIDER = 0xFFE7EBF2,
    DIVIDER_SOFT = 0xFFEEEEEE,
    SURFACE_SOFT = 0xFFF5F5F5,
    TRACK = 0xFFF0F4F9,
    SURFACE_TINT = 0xFFF7F9FC,
    BG_SOFT = 0xFFF0F2F5,
    SCRIM = 0x88000000,
    DISABLED = 0xFFBBBBBB,
    ON_DARK_ACCENT = 0xFF9EC8F5,
    ON_DARK_ACCENT_STRONG = 0xFFB3D9FF,
    DANGER = 0xFFD32F2F,
    DANGER_BG = 0xFFFFF0F0,
    DANGER_TEXT = 0xFFC34C4C,
    SUCCESS = 0xFF2E7D32,
    SUCCESS_BG = 0xFFE8F5E9,
    WARNING = 0xFFFF9800,
    WARNING_BG = 0xFFFFF3E8,
    WARNING_TEXT = 0xFFA56100,
    WARNING_TEXT_DEEP = 0xFF8B4C12,
    VIOLET = 0xFF7B1FA2,
    VIOLET_DEEP = 0xFF4A148C,
    VIOLET_BG = 0xFFF3E5F5,
    HIGHLIGHT_BG = 0xFFFFE0B2,
    SUCCESS_LINE = 0xFFC8E6C9,
    SKELETON_BG = 0xFFE9EDF2,
    SKELETON_BG_STRONG = 0xFFDDE3EA,
    PRESS_BG = 0xFFE8EBEF,
    ACCENT = 0xFF5B7FFF,
    NEUTRAL = StockColors.FLAT,
    INFO_BG = 0xFFE8F2FF,
    UP = StockColors.UP,
    DOWN = StockColors.DOWN,
    FLAT = StockColors.FLAT,
    UP_ALT = 0xFFD64545,
    DOWN_ALT = 0xFF2E9E5B,
)

/**
 * 深色。
 *
 * 取值原则（不是把浅色简单取反，逐条有理由）：
 *   - 层级靠**亮度**排，不靠色相：`BG` 最暗 → `SURFACE` 提一档 → `SURFACE_ALT/_SOFT`
 *     再提一档。深色底上「更亮 = 更靠前」，和浅色底上「更白 = 更靠前」方向相反。
 *   - 文字不用纯白：`TEXT` 落在 0xFFE8EDF3 一档。纯白在深色底上过冲、发晕。
 *   - 品牌色整体提亮（`PRIMARY` 0xFF0E67D1 → 0xFF4C9AFF）：深色底上低饱和深蓝会糊掉。
 *   - 语义色的「底」用同色相的极暗版（`DANGER_BG` 0xFF3A1F22），保持「浅色底 + 深色字」
 *     在深色模式下变成「深色底 + 亮色字」的关系，而不是把浅底直接搬过来。
 *   - `SCRIM` 压得更实（0x88 → 0xB3）：深色下 53% 黑的遮罩分不出层次。
 */
private val DARK_PALETTE = Palette(
    PRIMARY = 0xFF4C9AFF,
    PRIMARY_SOFT = 0xFF3D8BFD,
    PRIMARY_BG = 0xFF12304F,
    PRIMARY_BG_LIGHT = 0xFF16283D,
    PRIMARY_TEXT = 0xFF8AB9F0,
    INK_PANEL = 0xFF152A45,
    INK_PANEL_ALT = 0xFF1E4A75,
    INK_PANEL_SOFT = 0xFF17395E,
    TEXT_INK = 0xFFE6E8EC,
    TEXT_GRAY = 0xFFB6BCC6,
    TEXT_HINT = 0xFF8B93A0,
    TEXT_HINT_SOFT = 0xFF939BA8,
    TEXT_DEEP = 0xFFD2D8E0,
    TEXT_SUB_DEEP = 0xFF9BA6B5,
    TEXT_WARM = 0xFFD8C0AE,
    TITLE = 0xFFF2F5F9,
    TEXT = 0xFFE8EDF3,
    TEXT_STRONG = 0xFFEDF1F6,
    TEXT_SUB = 0xFF9AA5B4,
    TEXT_MUTED = 0xFF7E8896,
    ON_DARK = 0xFFFFFFFF,
    ON_DARK_SUB = 0xFF9FC2E3,
    ON_DARK_MUTED = 0xFF93A8BE,
    SURFACE = 0xFF1B1F26,
    SURFACE_ALT = 0xFF232830,
    BG = 0xFF121418,
    DIVIDER = 0xFF2C323C,
    DIVIDER_SOFT = 0xFF2A2F37,
    SURFACE_SOFT = 0xFF22262D,
    TRACK = 0xFF272C34,
    SURFACE_TINT = 0xFF20252C,
    BG_SOFT = 0xFF1E222A,
    SCRIM = 0xB3000000,
    DISABLED = 0xFF5A6068,
    ON_DARK_ACCENT = 0xFF8FC0F0,
    ON_DARK_ACCENT_STRONG = 0xFFA8D4FF,
    DANGER = 0xFFFF6B6B,
    DANGER_BG = 0xFF3A1F22,
    DANGER_TEXT = 0xFFFF8A8A,
    SUCCESS = 0xFF4CD07A,
    SUCCESS_BG = 0xFF16301F,
    WARNING = 0xFFFFB74D,
    WARNING_BG = 0xFF3A2A12,
    WARNING_TEXT = 0xFFFFC078,
    WARNING_TEXT_DEEP = 0xFFE0A96D,
    VIOLET = 0xFFC9A9F0,
    VIOLET_DEEP = 0xFFB49BE0,
    VIOLET_BG = 0xFF2A2340,
    HIGHLIGHT_BG = 0xFF3E3220,
    SUCCESS_LINE = 0xFF2E5A3A,
    SKELETON_BG = 0xFF232830,
    SKELETON_BG_STRONG = 0xFF2C323C,
    PRESS_BG = 0x14FFFFFF,
    ACCENT = 0xFF7C9BFF,
    NEUTRAL = StockColors.FLAT,
    INFO_BG = 0xFF16283D,
    UP = StockColors.UP,
    DOWN = StockColors.DOWN,
    FLAT = StockColors.FLAT,
    UP_ALT = 0xFFD64545,
    DOWN_ALT = 0xFF2E9E5B,
)

/**
 * 读一次当前页面的主题世代，把 [AppColor] 的这次取值登记进该页面 attr 块的依赖里。
 *
 * 没有页面上下文（单元测试、启动早期）时返回 0，此时 `AppColor` 就是一套普通常量，
 * 不会因为「读不到页面」而抛异常。
 */
private fun themeGeneration(): Int {
    val host = runCatching { PagerManager.getCurrentPager() }.getOrNull() as? ThemeHost
    return host?.themeGeneration ?: 0
}

/**
 * 当前生效的色板。
 *
 * **必须先读一次 [themeGeneration]**（哪怕结果不用）：这一读就是「订阅主题」这个动作，
 * 缺了它切主题时页面不会重画。
 */
private fun activePalette(): Palette {
    themeGeneration()
    return if (ThemeManager.isDark) DARK_PALETTE else LIGHT_PALETTE
}

/**
 * 色板。取值语义见各字段注释；浅色那一套的值全部来自仓库里已在使用的颜色。
 *
 * 每次取值都会问一次 [activePalette]，也就是每次都会登记一次主题依赖 —— 这是
 * 有意为之，不要「优化」成把色板缓存进局部变量，那会让深色模式在部分位置失效。
 */
object AppColor {

    // --- 品牌主色 ---
    /** 主色：底部导航选中、主按钮、链接、强调边框。 */
    val PRIMARY: Long get() = activePalette().PRIMARY

    /** 主色的浅一档变体：聊天抽屉顶栏、chip 选中态等大面积用色。 */
    val PRIMARY_SOFT: Long get() = activePalette().PRIMARY_SOFT

    /** 主色底：选中行、次级按钮底色。 */
    val PRIMARY_BG: Long get() = activePalette().PRIMARY_BG

    /** 主色底（更浅）：段落高亮、序号圆底。 */
    val PRIMARY_BG_LIGHT: Long get() = activePalette().PRIMARY_BG_LIGHT

    /** 主色文字（压在浅主色底上时的可读文字色）。 */
    val PRIMARY_TEXT: Long get() = activePalette().PRIMARY_TEXT

    /** 深色卡（统计卡 / 我的页 Hero 卡）底色。 */
    val INK_PANEL: Long get() = activePalette().INK_PANEL

    /** 深色卡上的次级面（头像底、次级块）。 */
    val INK_PANEL_ALT: Long get() = activePalette().INK_PANEL_ALT

    /** 深色卡渐变的亮端：比 [INK_PANEL] 亮一档，压在浅色页底上边界不会那么硬。 */
    val INK_PANEL_SOFT: Long get() = activePalette().INK_PANEL_SOFT

    // --- 遗留灰阶 ---
    // 收敛前各页自带一套纯灰，与蓝调色板并存。这里只做命名，浅色值原样保留——
    // 换成蓝调的值会让「正文/提示」的深浅关系整体移位，不属于本次规整的范围。
    /** 正文深灰。 */
    val TEXT_INK: Long get() = activePalette().TEXT_INK

    /** 次要正文灰。 */
    val TEXT_GRAY: Long get() = activePalette().TEXT_GRAY

    /** 提示灰（占位、脚注）。 */
    val TEXT_HINT: Long get() = activePalette().TEXT_HINT

    /** 提示灰（略深一档）。 */
    val TEXT_HINT_SOFT: Long get() = activePalette().TEXT_HINT_SOFT

    /** 深蓝灰正文（分析记录、AI 卡片）。 */
    val TEXT_DEEP: Long get() = activePalette().TEXT_DEEP

    /** 辅助文字的深一档（元信息、表格说明）。 */
    val TEXT_SUB_DEEP: Long get() = activePalette().TEXT_SUB_DEEP

    /**
     * 暖调正文（AI 结论卡这类压在同色系浅底上的大段文字）。
     *
     * 单独一个 token 而不是并进 [TEXT_DEEP]：浅色模式下它和蓝调正文的色相差得远，
     * 并掉会改变现有观感；而深色模式下又必须整体提亮，否则一片糊。
     */
    val TEXT_WARM: Long get() = activePalette().TEXT_WARM

    // --- 文字 ---
    /** 页面主标题（最深）。 */
    val TITLE: Long get() = activePalette().TITLE

    /** 正文/列表主文字。 */
    val TEXT: Long get() = activePalette().TEXT

    /** 小标题、卡片标题。 */
    val TEXT_STRONG: Long get() = activePalette().TEXT_STRONG

    /** 辅助文字（副标题、说明）。 */
    val TEXT_SUB: Long get() = activePalette().TEXT_SUB

    /** 更弱的辅助文字（脚注、占位）。 */
    val TEXT_MUTED: Long get() = activePalette().TEXT_MUTED

    /** 深色底上的主文字。 */
    val ON_DARK: Long get() = activePalette().ON_DARK

    /** 深色底上的辅助文字。 */
    val ON_DARK_SUB: Long get() = activePalette().ON_DARK_SUB

    /** 深色底上的弱辅助文字。 */
    val ON_DARK_MUTED: Long get() = activePalette().ON_DARK_MUTED

    // --- 面与线 ---
    /** 卡片/白底。 */
    val SURFACE: Long get() = activePalette().SURFACE

    /** 次级面（底部操作区、交替行）。 */
    val SURFACE_ALT: Long get() = activePalette().SURFACE_ALT

    /** 页面背景。 */
    val BG: Long get() = activePalette().BG

    /** 分割线 / 边框。 */
    val DIVIDER: Long get() = activePalette().DIVIDER

    /** 弱分割线（列表内、卡片内）。 */
    val DIVIDER_SOFT: Long get() = activePalette().DIVIDER_SOFT

    /** 次级面（更深一档的灰）。 */
    val SURFACE_SOFT: Long get() = activePalette().SURFACE_SOFT

    /**
     * 分段控件（一组互斥选项）的凹槽底色。
     *
     * 用途是把「白/主色滑块 + 若干选项」这套结构托出来：凹槽比页面底浅一档，
     * 滑块才立得住。原先各页各写一个十六进制（`0xFFF0F4F9` / `0xFFEFF3F8`），
     * 差异肉眼不可见却让主题无从统一下手，收敛成一个 token。
     */
    val TRACK: Long get() = activePalette().TRACK

    /** 带蓝调的次级面（代码块、引用块）。 */
    val SURFACE_TINT: Long get() = activePalette().SURFACE_TINT

    /** 偏冷的页底 / 列表灰底。 */
    val BG_SOFT: Long get() = activePalette().BG_SOFT

    /** 弹窗与抽屉背后的遮罩（浅色 53% 黑 / 深色 70% 黑）。 */
    val SCRIM: Long get() = activePalette().SCRIM

    /** 禁用态。 */
    val DISABLED: Long get() = activePalette().DISABLED

    /** 深色底上的亮蓝辅助文字。 */
    val ON_DARK_ACCENT: Long get() = activePalette().ON_DARK_ACCENT

    /** 深色底上的更亮蓝（次级按钮文字）。 */
    val ON_DARK_ACCENT_STRONG: Long get() = activePalette().ON_DARK_ACCENT_STRONG

    // --- 语义色 ---
    /** 危险：删除、风险警告。 */
    val DANGER: Long get() = activePalette().DANGER
    val DANGER_BG: Long get() = activePalette().DANGER_BG
    val DANGER_TEXT: Long get() = activePalette().DANGER_TEXT

    /** 成功 / 已生效。 */
    val SUCCESS: Long get() = activePalette().SUCCESS
    val SUCCESS_BG: Long get() = activePalette().SUCCESS_BG

    /** 警示橙：预警、图表指标线（MA / DEA）。 */
    val WARNING: Long get() = activePalette().WARNING
    val WARNING_BG: Long get() = activePalette().WARNING_BG
    val WARNING_TEXT: Long get() = activePalette().WARNING_TEXT

    /**
     * 警示琥珀的深一档：风险条目里成段的正文（比 [WARNING_TEXT] 的标签色更重）。
     * 浅色模式取值 0xFF8B4C12 —— 仓库里原本就是它在用，纯命名，不改观感。
     */
    val WARNING_TEXT_DEEP: Long get() = activePalette().WARNING_TEXT_DEEP

    /** 紫色强调前景：AI 结论卡标题、自选入口标记、日历「财报」点。 */
    val VIOLET: Long get() = activePalette().VIOLET

    /** 紫色正文：压在同色系浅底上的成段文字。 */
    val VIOLET_DEEP: Long get() = activePalette().VIOLET_DEEP

    /** 紫色浅底。 */
    val VIOLET_BG: Long get() = activePalette().VIOLET_BG

    /** 强调行底色：盘口选中价、信号卡。 */
    val HIGHLIGHT_BG: Long get() = activePalette().HIGHLIGHT_BG

    /** 成功态卡片的描边。 */
    val SUCCESS_LINE: Long get() = activePalette().SUCCESS_LINE

    /** 骨架屏占位块底色。 */
    val SKELETON_BG: Long get() = activePalette().SKELETON_BG

    /** 骨架屏次级元素（文字行）底色。 */
    val SKELETON_BG_STRONG: Long get() = activePalette().SKELETON_BG_STRONG

    /** 中性面按下时的高亮底色。 */
    val PRESS_BG: Long get() = activePalette().PRESS_BG

    /** 强调蓝：AI 焦点、可点操作文字。 */
    val ACCENT: Long get() = activePalette().ACCENT

    /** 中性灰：无数据占位、非涨非跌的文字。与「平」同值。 */
    val NEUTRAL: Long get() = activePalette().NEUTRAL

    /** 中性提示底（信息条）。 */
    val INFO_BG: Long get() = activePalette().INFO_BG

    // --- 涨跌方向色（转发，避免调用点引两个包）---
    // ⚠️ 这五个 token **深浅两套取值刻意保持一致**，不要「顺手给它也配一套暗色」：
    // K 线 / 分时的红绿是 Canvas 自绘的，取色来自 `data/StockColors`，
    // 而且多处是在 draw 回调**外面**先取成一个局部变量（例如
    // `ChatMainPage` 的 `val upColor = AppColor.UP_ALT`）——主题切换时那些局部变量
    // 不会重新求值，一分叉就会变成「文字换了色、图没换」。要动这块，
    // 得连 `StockColors` 与各处画布取色一起改，是独立的一件事。
    /** 涨 / 正。来源见 `data/StockColors`。 */
    val UP: Long get() = activePalette().UP

    /** 跌 / 负。 */
    val DOWN: Long get() = activePalette().DOWN

    /** 平 / 中性。 */
    val FLAT: Long get() = activePalette().FLAT

    /** 涨色变体：压力位、涨跌文字，比 [UP] 略深。 */
    val UP_ALT: Long get() = activePalette().UP_ALT

    /** 跌色变体：支撑位、涨跌文字，比 [DOWN] 略深。 */
    val DOWN_ALT: Long get() = activePalette().DOWN_ALT
}

/** 圆角。原先 8/10/12/13/15/16/18 混用，这里收敛成四档 + 胶囊。 */
object AppRadius {
    /** 小：标签、日期格、代码块。 */
    const val SM: Float = 8f

    /** 中：行内卡片、输入框、提示条。 */
    const val MD: Float = 12f

    /** 大：内容卡片。 */
    const val LG: Float = 16f

    /** 特大：整块的 Hero 卡 / 统计卡。 */
    const val XL: Float = 18f

    /** 胶囊：按钮、chip。 */
    const val PILL: Float = 999f
}

/** 间距。卡片内边距原先 12/14/15/16/18/22 混用，这里收敛成一套 4 的倍数。 */
object AppSpace {
    /** 页面左右边距。 */
    const val PAGE: Float = 16f

    /** 卡片内边距。 */
    const val CARD: Float = 16f

    /** 卡片之间的间距。 */
    const val GAP: Float = 12f

    /** 元素之间的紧间距。 */
    const val TIGHT: Float = 8f

    /** 段落之间的松间距。 */
    const val LOOSE: Float = 20f

    /** 「空状态」这类需要呼吸感的场景。 */
    const val AIRY: Float = 22f
}

/** 字号。层级固定下来之后，标题轻重就不会各页不一。 */
object AppFont {
    /** 脚注。 */
    const val CAPTION: Float = 11f

    /** 辅助说明。 */
    const val NOTE: Float = 12f

    /** 正文。 */
    const val BODY: Float = 13f

    /** 列表项标题 / 强调正文。 */
    const val LABEL: Float = 14f

    /** 卡片标题。 */
    const val TITLE: Float = 17f

    /** 页面主标题。 */
    const val HEAD: Float = 18f

    /** 数字大字（统计卡）。 */
    const val DISPLAY: Float = 28f
}

/** 关键尺寸。 */
object AppSize {
    /** 底部导航栏高度。 */
    const val NAV_BAR: Float = 64f

    /** 页面标题栏内容高度（不含状态栏）。 */
    const val TITLE_BAR: Float = 58f

    /** 列表行最小高度。 */
    const val ROW_MIN: Float = 56f

    /**
     * 最小可点区域。
     * 44dp 是 iOS HIG 的下限，也是本项目所有图标按钮的既有尺寸——
     * 「视觉上 20dp 的箭头，热区要 44dp」这件事靠它统一。
     */
    const val TOUCH_MIN: Float = 44f
}

/** 动效参数。原先时长散落在各文件，这里把三类转场固定下来。 */
object AppMotion {
    /**
     * 同级目的地切换（底部 Tab）：**三段式** 出场 → 间隙 → 入场，合计 330ms。
     *
     * 为什么不能两页叠着一起淡：同步交叉淡出的中段会同时看到两页内容（版式相近、文案不同），
     * 观感就是「错位」。要消掉重影，任一时刻就只能有一层在变透明度，中间必须有一段
     * 「只有中性底、没有内容」的空窗——这就是 [TAB_GAP_MS] 的由来。
     *
     * 三端各写各的动画，做不到共享常量，所以这里只是**规格的事实来源**：
     *   - Android `NavTransition`：入场不能走窗口 alpha（窗口 alpha 连窗口底色一起淡，
     *     中段会透出桌面），改成「不透明底色先盖住旧页 + 内容延迟 [TAB_OUT_MS]+[TAB_GAP_MS]
     *     后淡入 [TAB_IN_MS]」；返回那段仍用 `kr_module_fade_out`。
     *   - iOS `KRRouterHandler`：hostView 的 alpha 三段式，App 窗口底色天然不参与。
     *   - 鸿蒙 `Index.pageTransition`：PageTransitionEnter 用 `delay` 做空窗。
     * 改任一段都要三端一起改，用 [TAB_TOTAL_MS] 自检。
     */
    const val TAB_OUT_MS: Int = 90
    const val TAB_GAP_MS: Int = 30
    const val TAB_IN_MS: Int = 210

    /** 三段合计。改任一段后同步三端时，用它自检。 */
    const val TAB_TOTAL_MS: Int = TAB_OUT_MS + TAB_GAP_MS + TAB_IN_MS

    /** 分段控件的滑块位移时长。位移本身要看得见，所以比按压反馈慢。 */
    const val SEGMENT_MS: Int = 200

    /** 层级下钻（进入详情）：iOS push 风格的方向性横切。 */
    const val PUSH_MS: Int = 300

    /** 浮层（弹窗）出场：短、干脆。 */
    const val OVERLAY_IN_MS: Int = 160

    /** 浮层退场：比出场更快。 */
    const val OVERLAY_OUT_MS: Int = 130

    /** 侧边抽屉滑入：比弹窗长，位移需要时间被看清。 */
    const val DRAWER_IN_MS: Int = 260

    /** 侧边抽屉滑出。 */
    const val DRAWER_OUT_MS: Int = 200

    /** 遮罩淡入淡出：比面板略快，让焦点先落到面板上。 */
    const val SCRIM_MS: Int = 180

    /** 按压缩放比例：能感觉到但不跳。 */
    const val PRESS_SCALE: Float = 0.96f
}

/**
 * 给 token 色叠 alpha（默认 0xE6 ≈ 90%），返回 ARGB。
 *
 * 用于半透明浮层 / 按压态：先取到当前色板的值，再叠透明度 —— 这样浅色模式下是
 * 浅色板的半透明，深色模式下自动换成深色板对应色，不会出现「换主题后浮层颜色
 * 还是旧色板」的穿帮。
 *
 * 注意 `base and 0x00FFFFFF` 只保留 RGB：传进来的必须是标准 ARGB（alpha 位在前），
 * 位掩码类的值（如 `0x00FFFFFF` 本身）不能用它叠。
 */
fun themeTint(base: Long, alpha: Int = 0xE6): Long =
    (base and 0x00FFFFFFL) or ((alpha.toLong() and 0xFFL) shl 24)
