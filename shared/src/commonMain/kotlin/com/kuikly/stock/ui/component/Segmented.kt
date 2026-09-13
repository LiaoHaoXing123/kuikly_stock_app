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

private val SEGMENT_ANIMATION: Animation = Animation.easeOut(AppMotion.SEGMENT_MS / 1000f)

private const val TRACK_PAD = 3f

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
