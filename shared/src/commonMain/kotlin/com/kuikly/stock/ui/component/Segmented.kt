// 分段控件：一组互斥选项 + 一个在选项之间平移的滑块。
//
// 抽出来的理由和 ControlButton 一样：项目里原本四处各写一遍（个股页 日/周/月K、
// 行情页 股票/指数、行情页 排序、自选页弹窗的提醒类型），结构完全相同，宽度、字号、
// 取色却各写各的——其中凹槽底色三处是 0xFFF0F4F9、一处是 0xFFEFF3F8，肉眼分不出
// 却让主题统一下不了手。
//
// 收敛之后有三点收益：
//   1. 凹槽 / 滑块 / 文字的取色只有一处，换主题只改 AppColor；
//   2. 选中项和滑块位置都由**同一个下标**推出，不可能出现「字亮了滑块没动」；
//   3. 新增一组选项只要给 itemWidth 和文案，不用再抄一遍绝对定位与百分比位移。

package com.kuikly.stock.ui.component

import com.kuikly.stock.base.HapticStyle
import com.kuikly.stock.base.hapticTick
import com.kuikly.stock.ui.theme.AppColor
import com.kuikly.stock.ui.theme.AppMotion
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Translate
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.attr.AccessibilityRole
import com.tencent.kuikly.core.layout.FlexAlign
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/** 滑块位移曲线。四处的原值都是 easeOut(0.2f)，这里按 AppMotion 统一。 */
private val SEGMENT_ANIMATION: Animation = Animation.easeOut(AppMotion.SEGMENT_MS / 1000f)

/** 凹槽内边距。滑块靠它从凹槽边缘缩进来，选项热区高度与之一致。 */
private const val TRACK_PAD = 3f

/**
 * 分段控件。**互斥**单选：点哪一格，滑块就平移过去。
 *
 * 位移用 `percentageX`（相对滑块自身宽度）而不是 dp——滑块宽度等于单格宽，
 * 跨一格正好 100%，换宽度也不用重算偏移量。
 *
 * 响应式注意：`selectedIndex()` 必须在 `attr { }` 里读，attr 块才会订阅它；
 * 而且缩略图那块要先读下标、再声明动画（顺序反了动画会静默失效，见 Interaction.kt 头部）。
 *
 * @param itemWidth    单格宽度；滑块宽度也取它，否则位移对不齐格子
 * @param selectedIndex 当前选中下标。由调用方从自己的 observable 推出来；
 *                      越界值会被夹回合法区间（派生下标容易算出 -1，不夹会让滑块飞出凹槽）
 * @param onSelect     选中回调，收到的是下标。**不做去重**，重复点同一格也会回调
 * @param trackRadius  凹槽圆角；[thumbRadius] 要比它小 [TRACK_PAD] 左右才贴合
 * @param selfAlign    放在纵向容器里时传 `FlexAlign.FLEX_START`，否则凹槽会被拉满整行
 */
internal fun ViewContainer<*, *>.segmentedControl(
    options: List<String>,
    selectedIndex: () -> Int,
    itemWidth: Float,
    onSelect: (Int) -> Unit,
    height: Float = 28f,
    trackRadius: Float = 14f,
    thumbRadius: Float = 11f,
    trackColor: () -> Long = { AppColor.TRACK },
    thumbColor: () -> Long = { AppColor.PRIMARY_SOFT },
    selectedTextColor: () -> Long = { AppColor.ON_DARK },
    unselectedTextColor: () -> Long = { AppColor.TEXT_GRAY },
    fontSize: Float = 12f,
    marginTop: Float = 0f,
    selfAlign: FlexAlign? = null,
    accessibilityLabel: (String, Boolean) -> String = { label, selected ->
        if (selected) "$label，已选择" else label
    },
) {
    View {
        attr {
            flexDirectionRow()
            backgroundColor(trackColor())
            borderRadius(trackRadius)
            padding(TRACK_PAD)
            marginTop(marginTop)
            selfAlign?.let { alignSelf(it) }
        }

        // 滑块先声明：z 序在选项文字之下，移过去是「垫在选中项下面」而不是盖住它。
        View {
            attr {
                val index = selectedIndex().coerceIn(0, options.lastIndex)
                animate(SEGMENT_ANIMATION, index)
                absolutePosition(top = TRACK_PAD, left = TRACK_PAD, bottom = TRACK_PAD)
                width(itemWidth)
                borderRadius(thumbRadius)
                backgroundColor(thumbColor())
                transform(translate = Translate(percentageX = index.toFloat()))
            }
        }

        options.forEachIndexed { index, label ->
            View {
                attr {
                    width(itemWidth)
                    height(height)
                    allCenter()
                    val selected = selectedIndex().coerceIn(0, options.lastIndex) == index
                    accessibility(accessibilityLabel(label, selected))
                    accessibilityRole(AccessibilityRole.BUTTON)
                    accessibilityInfo(!selected, false)
                }
                event { click { hapticTick(HapticStyle.Light); onSelect(index) } }
                Text {
                    attr {
                        text(label)
                        fontSize(fontSize)
                        fontWeightBold()
                        color(
                            if (selectedIndex().coerceIn(0, options.lastIndex) == index) {
                                selectedTextColor()
                            } else {
                                unselectedTextColor()
                            },
                        )
                    }
                }
            }
        }
    }
}
