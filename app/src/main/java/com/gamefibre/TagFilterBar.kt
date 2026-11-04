package com.gamefibre

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Library filters apply on tap; game-page selections use their separate Search action. */
internal class TagFilterBar @JvmOverloads constructor(context: Context, private val ui: NativeUi = NativeUi(context),
    private val alwaysVisible: Boolean = false,
    private val showCounts: Boolean = true,
    private val change: (TagFilters) -> Unit = {}) : LinearLayout(context) {
    private var filters = TagFilters()
    private var facets = emptyList<TagFacet>()
    private val chips = object : RecyclerView(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var startX = 0f
        private var startY = 0f
        private var directionChosen = false
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x; startY = event.y; directionChosen = false
                    // The whole strip owns the initial touch, including gaps and its bottom inset.
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> if (!directionChosen) {
                    val dx = abs(event.x - startX); val dy = abs(event.y - startY)
                    if (maxOf(dx, dy) > slop) {
                        directionChosen = true
                        parent?.requestDisallowInterceptTouchEvent(dx >= dy)
                    }
                }
            }
            return try { super.dispatchTouchEvent(event) } finally {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                    parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }.apply {
        isClickable = true
        layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        isNestedScrollingEnabled = false
        itemAnimator = null
        contentDescription = if (showCounts) "Tag filters with result counts" else "Tag filters"
    }
    private inner class Holder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)
    private val pills = object : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = facets.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(FrameLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(-2, -1).apply { marginEnd = ui.dp(8) }
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val facet = facets[position]
            val selected = filters.state(facet.name) != TagState.OFF
            val count = if (selected) facet.clearCount else facet.includeCount
            holder.frame.removeAllViews()
            holder.frame.addView(ui.listChip(if (showCounts) "${facet.name} · $count" else facet.name, selected) { checked ->
                filters = filters.with(facet.name, if (checked) TagState.INCLUDE else TagState.OFF)
                if (!filters.active && !alwaysVisible) visibility = GONE
                change(filters)
            }.apply {
                contentDescription = "${facet.name}, ${if (selected) "selected" else "not selected"}" +
                    if (showCounts) ". Toggle: $count games" else ""
            }, FrameLayout.LayoutParams(-2, ui.dp(48)))
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        chips.adapter = pills
        addView(chips, LayoutParams(0, ui.dp(56), 1f))
        if (!alwaysVisible) {
            addView(ui.iconButton(R.drawable.ic_x, "Clear tag filters") {
                filters = TagFilters()
                visibility = GONE
                change(filters)
            }.apply {
                tooltipText = "Clear tag filters"
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.Button"
                    }
                }
            }, LayoutParams(ui.dp(48), ui.dp(48)))
        }
    }

    fun update(value: List<TagFacet>, selection: TagFilters) {
        val previous = facets
        val previousSelection = filters
        facets = value; filters = selection
        visibility = VISIBLE
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = value.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) = previous[oldItemPosition].name == value[newItemPosition].name
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                previous[oldItemPosition] == value[newItemPosition] &&
                    previousSelection.state(previous[oldItemPosition].name) == selection.state(value[newItemPosition].name)
        }).dispatchUpdatesTo(pills)
    }
}
