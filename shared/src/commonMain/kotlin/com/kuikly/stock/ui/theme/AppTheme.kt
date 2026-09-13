package com.kuikly.stock.ui.theme

import com.kuikly.stock.data.StockColors
import com.tencent.kuikly.core.manager.PagerManager

private class Palette(

    val PRIMARY: Long,
    val PRIMARY_SOFT: Long,
    val PRIMARY_BG: Long,
    val PRIMARY_BG_LIGHT: Long,
    val PRIMARY_TEXT: Long,
    val INK_PANEL: Long,
    val INK_PANEL_ALT: Long,
    val INK_PANEL_SOFT: Long,

    val TEXT_INK: Long,
    val TEXT_GRAY: Long,
    val TEXT_HINT: Long,
    val TEXT_HINT_SOFT: Long,
    val TEXT_DEEP: Long,
    val TEXT_SUB_DEEP: Long,
    val TEXT_WARM: Long,

    val TITLE: Long,
    val TEXT: Long,
    val TEXT_STRONG: Long,
    val TEXT_SUB: Long,
    val TEXT_MUTED: Long,
    val ON_DARK: Long,
    val ON_DARK_SUB: Long,
    val ON_DARK_MUTED: Long,

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

    val DANGER: Long,
    val DANGER_BG: Long,
    val DANGER_TEXT: Long,
    val SUCCESS: Long,
    val SUCCESS_BG: Long,
    val WARNING: Long,
    val WARNING_BG: Long,
    val WARNING_TEXT: Long,
    val WARNING_TEXT_DEEP: Long,

    val VIOLET: Long,
    val VIOLET_DEEP: Long,
    val VIOLET_BG: Long,

    val HIGHLIGHT_BG: Long,
    val SUCCESS_LINE: Long,
    val SKELETON_BG: Long,
    val SKELETON_BG_STRONG: Long,
    val PRESS_BG: Long,
    val ACCENT: Long,
    val NEUTRAL: Long,
    val INFO_BG: Long,

    val UP: Long,
    val DOWN: Long,
    val FLAT: Long,
    val UP_ALT: Long,
    val DOWN_ALT: Long,
)

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
    SKELETON_BG = 0xFF333A44,
    SKELETON_BG_STRONG = 0xFF3D4550,
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

private fun themeGeneration(): Int {
    val host = runCatching { PagerManager.getCurrentPager() }.getOrNull() as? ThemeHost
    return host?.themeGeneration ?: 0
}

private fun activePalette(): Palette {
    themeGeneration()
    return if (ThemeManager.isDark) DARK_PALETTE else LIGHT_PALETTE
}

object AppColor {

    val PRIMARY: Long get() = activePalette().PRIMARY

    val PRIMARY_SOFT: Long get() = activePalette().PRIMARY_SOFT

    val PRIMARY_BG: Long get() = activePalette().PRIMARY_BG

    val PRIMARY_BG_LIGHT: Long get() = activePalette().PRIMARY_BG_LIGHT

    val PRIMARY_TEXT: Long get() = activePalette().PRIMARY_TEXT

    val INK_PANEL: Long get() = activePalette().INK_PANEL

    val INK_PANEL_ALT: Long get() = activePalette().INK_PANEL_ALT

    val INK_PANEL_SOFT: Long get() = activePalette().INK_PANEL_SOFT

    val TEXT_INK: Long get() = activePalette().TEXT_INK

    val TEXT_GRAY: Long get() = activePalette().TEXT_GRAY

    val TEXT_HINT: Long get() = activePalette().TEXT_HINT

    val TEXT_HINT_SOFT: Long get() = activePalette().TEXT_HINT_SOFT

    val TEXT_DEEP: Long get() = activePalette().TEXT_DEEP

    val TEXT_SUB_DEEP: Long get() = activePalette().TEXT_SUB_DEEP

    val TEXT_WARM: Long get() = activePalette().TEXT_WARM

    val TITLE: Long get() = activePalette().TITLE

    val TEXT: Long get() = activePalette().TEXT

    val TEXT_STRONG: Long get() = activePalette().TEXT_STRONG

    val TEXT_SUB: Long get() = activePalette().TEXT_SUB

    val TEXT_MUTED: Long get() = activePalette().TEXT_MUTED

    val ON_DARK: Long get() = activePalette().ON_DARK

    val ON_DARK_SUB: Long get() = activePalette().ON_DARK_SUB

    val ON_DARK_MUTED: Long get() = activePalette().ON_DARK_MUTED

    val SURFACE: Long get() = activePalette().SURFACE

    val SURFACE_ALT: Long get() = activePalette().SURFACE_ALT

    val BG: Long get() = activePalette().BG

    val DIVIDER: Long get() = activePalette().DIVIDER

    val DIVIDER_SOFT: Long get() = activePalette().DIVIDER_SOFT

    val SURFACE_SOFT: Long get() = activePalette().SURFACE_SOFT

    val TRACK: Long get() = activePalette().TRACK

    val SURFACE_TINT: Long get() = activePalette().SURFACE_TINT

    val BG_SOFT: Long get() = activePalette().BG_SOFT

    val SCRIM: Long get() = activePalette().SCRIM

    val DISABLED: Long get() = activePalette().DISABLED

    val ON_DARK_ACCENT: Long get() = activePalette().ON_DARK_ACCENT

    val ON_DARK_ACCENT_STRONG: Long get() = activePalette().ON_DARK_ACCENT_STRONG

    val DANGER: Long get() = activePalette().DANGER
    val DANGER_BG: Long get() = activePalette().DANGER_BG
    val DANGER_TEXT: Long get() = activePalette().DANGER_TEXT

    val SUCCESS: Long get() = activePalette().SUCCESS
    val SUCCESS_BG: Long get() = activePalette().SUCCESS_BG

    val WARNING: Long get() = activePalette().WARNING
    val WARNING_BG: Long get() = activePalette().WARNING_BG
    val WARNING_TEXT: Long get() = activePalette().WARNING_TEXT

    val WARNING_TEXT_DEEP: Long get() = activePalette().WARNING_TEXT_DEEP

    val VIOLET: Long get() = activePalette().VIOLET

    val VIOLET_DEEP: Long get() = activePalette().VIOLET_DEEP

    val VIOLET_BG: Long get() = activePalette().VIOLET_BG

    val HIGHLIGHT_BG: Long get() = activePalette().HIGHLIGHT_BG

    val SUCCESS_LINE: Long get() = activePalette().SUCCESS_LINE

    val SKELETON_BG: Long get() = activePalette().SKELETON_BG

    val SKELETON_BG_STRONG: Long get() = activePalette().SKELETON_BG_STRONG

    val PRESS_BG: Long get() = activePalette().PRESS_BG

    val ACCENT: Long get() = activePalette().ACCENT

    val NEUTRAL: Long get() = activePalette().NEUTRAL

    val INFO_BG: Long get() = activePalette().INFO_BG

    val UP: Long get() = activePalette().UP

    val DOWN: Long get() = activePalette().DOWN

    val FLAT: Long get() = activePalette().FLAT

    val UP_ALT: Long get() = activePalette().UP_ALT

    val DOWN_ALT: Long get() = activePalette().DOWN_ALT
}

object AppRadius {

    const val SM: Float = 8f

    const val MD: Float = 12f

    const val LG: Float = 16f

    const val XL: Float = 18f

    const val PILL: Float = 999f
}

object AppSpace {

    const val PAGE: Float = 16f

    const val CARD: Float = 16f

    const val GAP: Float = 12f

    const val TIGHT: Float = 8f

    const val LOOSE: Float = 20f

    const val AIRY: Float = 22f
}

object AppFont {

    const val CAPTION: Float = 11f

    const val NOTE: Float = 12f

    const val BODY: Float = 13f

    const val LABEL: Float = 14f

    const val TITLE: Float = 17f

    const val HEAD: Float = 18f

    const val DISPLAY: Float = 28f
}

object AppSize {

    const val NAV_BAR: Float = 64f

    const val TITLE_BAR: Float = 58f

    const val ROW_MIN: Float = 56f

    const val TOUCH_MIN: Float = 44f
}

object AppMotion {

    const val TAB_OUT_MS: Int = 90
    const val TAB_GAP_MS: Int = 30
    const val TAB_IN_MS: Int = 210

    const val TAB_TOTAL_MS: Int = TAB_OUT_MS + TAB_GAP_MS + TAB_IN_MS

    const val SEGMENT_MS: Int = 200

    const val PUSH_MS: Int = 300

    const val OVERLAY_IN_MS: Int = 160

    const val OVERLAY_OUT_MS: Int = 130

    const val DRAWER_IN_MS: Int = 260

    const val DRAWER_OUT_MS: Int = 200

    const val SCRIM_MS: Int = 180

    const val PRESS_SCALE: Float = 0.96f
}

fun themeTint(base: Long, alpha: Int = 0xE6): Long =
    (base and 0x00FFFFFFL) or ((alpha.toLong() and 0xFFL) shl 24)
