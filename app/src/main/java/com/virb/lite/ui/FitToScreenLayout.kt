package com.virb.lite.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import kotlin.math.min

/**
 * Displays one child at its natural size when possible, then scales it uniformly
 * when necessary so the complete child remains visible without scrolling.
 */
class FitToScreenLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        require(childCount <= 1) { "FitToScreenLayout supports only one direct child" }

        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val availableWidth = (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)
        val child = getChildAt(0)
        child?.measure(
            MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )

        val desiredHeight = paddingTop + (child?.measuredHeight ?: 0) + paddingBottom
        val measuredWidth = resolveSize(widthSize, widthMeasureSpec)
        val measuredHeight = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)

        val availableHeight = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0)
        val naturalHeight = child?.measuredHeight ?: 0
        val scale = if (naturalHeight > 0) {
            min(1f, availableHeight.toFloat() / naturalHeight)
        } else {
            1f
        }
        child?.scaleX = scale
        child?.scaleY = scale
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        val childLeft = paddingLeft
        val childTop = paddingTop
        child.layout(
            childLeft,
            childTop,
            childLeft + child.measuredWidth,
            childTop + child.measuredHeight,
        )
        child.pivotX = child.measuredWidth / 2f
        child.pivotY = 0f
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(params: LayoutParams): LayoutParams = LayoutParams(params)

}
