package com.github.bluetrees2.novpn

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import kotlin.math.max
import kotlin.math.min

class MainLayout(context: Context, attrs: AttributeSet?): LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        if (orientation != VERTICAL || getChildAt(0).visibility == View.GONE)
            return

        val fullHeight = MeasureSpec.getSize(heightMeasureSpec)
        val newHeight: Int = getChildAt(0).run {
            val rowHeight: Int = context.resources::getDimensionPixelSize.run {
                arrayOf(
                    invoke(R.dimen.app_info_view_height),
                    invoke(R.dimen.app_info_view_marginTop),
                    invoke(R.dimen.app_info_view_marginBottom)
                ).sum()
            }
            val verticalPadding = paddingTop + paddingBottom
            val maxHeight = fullHeight / 2
            val minHeight = min(rowHeight + verticalPadding, maxHeight)
            when {
                measuredHeight <= maxHeight
                    -> max(measuredHeight, minHeight)
                maxHeight < rowHeight * 2
                    -> minHeight
                else
                    -> (maxHeight / rowHeight) * rowHeight
            }
        }

        fun setHeight(view: View, height: Int) = with(view) {
            val oldValue = layoutParams.height
            layoutParams.height = height
            measureChildWithMargins(this,
                MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                0,
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
                0
            )
            layoutParams.height = oldValue
        }
        setHeight(getChildAt(0), newHeight)
        setHeight(getChildAt(1), fullHeight - newHeight)
    }
}
