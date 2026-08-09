package com.virb.lite.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import kotlin.math.ceil

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
        val measuredWidth = resolveSize(widthSize, widthMeasureSpec)
        val measuredHeight = resolveSize(MeasureSpec.getSize(heightMeasureSpec), heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)

        val availableWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0)
        val child = getChildAt(0) ?: return

        measureChildForScale(child, availableWidth, 1f)
        var scale = 1f
        if (child.measuredHeight > availableHeight) {
            var low = MIN_CONTENT_SCALE
            var high = 1f
            repeat(SCALE_SEARCH_STEPS) {
                val candidate = (low + high) / 2f
                measureChildForScale(child, availableWidth, candidate)
                if (child.measuredHeight * candidate <= availableHeight) {
                    low = candidate
                } else {
                    high = candidate
                }
            }
            scale = low
            measureChildForScale(child, availableWidth, scale)
        }
        child.scaleX = scale
        child.scaleY = scale
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
        child.pivotX = 0f
        child.pivotY = 0f
    }

    private fun measureChildForScale(child: android.view.View, availableWidth: Int, scale: Float) {
        val logicalWidth = ceil(availableWidth / scale).toInt().coerceAtLeast(0)
        child.measure(
            MeasureSpec.makeMeasureSpec(logicalWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(params: LayoutParams): LayoutParams = LayoutParams(params)

    private companion object {
        private const val MIN_CONTENT_SCALE = 0.2f
        private const val SCALE_SEARCH_STEPS = 12
    }

}
