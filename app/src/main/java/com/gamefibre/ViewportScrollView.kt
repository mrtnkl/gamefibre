package com.gamefibre

import android.content.Context
import android.widget.ScrollView

/** Expose this measure pass's viewport before measuring children, rather than the previous layout. */
internal open class ViewportScrollView(context: Context) : ScrollView(context) {
    var viewportHeight = 0
        private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        viewportHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) 0
            else MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
