package com.github.bluetrees2.novpn

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView

class LinearLayoutManagerSnapEnd(context: Context) : LinearLayoutManager(context) {
    override fun smoothScrollToPosition(recyclerView: RecyclerView?, state: RecyclerView.State?,
                                        position: Int) {
        val linearSmoothScroller = SnapEndSmoothScroller(recyclerView!!.context)
        linearSmoothScroller.targetPosition = position
        startSmoothScroll(linearSmoothScroller)
    }
}

class SnapEndSmoothScroller(context: Context) : LinearSmoothScroller(context) {
    override fun getVerticalSnapPreference(): Int = SNAP_TO_END
    override fun getHorizontalSnapPreference(): Int = SNAP_TO_END
}