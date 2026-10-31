package com.gamefibre

import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Standard AndroidX drag handling, with equivalent moves available to accessibility services. */
internal class ListOrderView(
    context: Context,
    private val ui: NativeUi,
    private var selectedName: String,
    private val save: (List<String>) -> Boolean,
) : RecyclerView(context) {
    private val names = mutableListOf<String>()
    private val rows = Rows()
    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: ViewHolder): Int =
            if (canMove(viewHolder.bindingAdapterPosition)) super.getMovementFlags(recyclerView, viewHolder) else 0

        override fun canDropOver(recyclerView: RecyclerView, current: ViewHolder, target: ViewHolder) =
            canMove(target.bindingAdapterPosition)

        override fun onMove(recyclerView: RecyclerView, viewHolder: ViewHolder, target: ViewHolder) =
            move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)

        override fun onSwiped(viewHolder: ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(viewHolder: ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) parent?.requestDisallowInterceptTouchEvent(true)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            parent?.requestDisallowInterceptTouchEvent(false)
        }
    })

    init {
        layoutManager = LinearLayoutManager(context)
        adapter = rows
        touchHelper.attachToRecyclerView(this)
    }

    fun submitNames(value: List<String>, selected: String) {
        names.clear()
        names.addAll(value)
        selectedName = selected
        rows.notifyDataSetChanged()
    }

    // Keep a scrollable viewport inside the dialog so AndroidX can scroll while dragging at an edge.
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maximum = minOf(ui.dp(320), resources.displayMetrics.heightPixels / 3)
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.AT_MOST))
    }

    private fun isFixed(name: String) = name in setOf("All games", WishlistItems.LIST, CollectionData.HIDDEN_LIST)

    private fun canMove(position: Int) = position in names.indices && !isFixed(names[position])

    private fun move(from: Int, to: Int): Boolean {
        if (from == to || !canMove(from) || !canMove(to)) return false
        val order = names.toMutableList().apply { add(to, removeAt(from)) }
        if (!save(order)) return false
        names.clear()
        names.addAll(order)
        rows.notifyItemMoved(from, to)
        return true
    }

    private inner class Holder(val row: LinearLayout, val label: TextView, val handle: View, val fixed: TextView) : ViewHolder(row) {
        // The handle only starts a drag; reordering is exposed to accessibility as move actions on the row below.
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        private fun attachDragHandle() {
            handle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && canMove(bindingAdapterPosition)) {
                    touchHelper.startDrag(this)
                    true
                } else false
            }
        }
        init {
            attachDragHandle()
            row.isFocusable = true
            row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            row.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    val position = bindingAdapterPosition
                    if (position in names.indices) AccessibilityNodeInfoCompat.wrap(info).stateDescription = "Position ${position + 1} of ${names.size}"
                    if (!canMove(position)) return
                    if (canMove(position - 1)) info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "Move earlier"))
                    if (canMove(position + 1)) info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "Move later"))
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    val position = bindingAdapterPosition
                    val moved = when (action) {
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> move(position, position - 1)
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> move(position, position + 1)
                        else -> return super.performAccessibilityAction(host, action, args)
                    }
                    if (moved) host.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                    return moved
                }
            }
        }
    }

    private inner class Rows : Adapter<Holder>() {
        override fun getItemCount() = names.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = ui.row().apply {
                minimumHeight = ui.dp(56)
                background = ui.shape(ui.background)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }
            val label = ui.text("", 14).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val handle = ui.icon(R.drawable.ic_drag_handle).apply { setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)) }
            val fixed = ui.text("Fixed", 13, ui.secondary).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(fixed)
            row.addView(handle, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
            return Holder(row, label, handle, fixed)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val name = names[position]
            holder.label.text = name
            holder.label.typeface = android.graphics.Typeface.create(ui.font, if (name == selectedName) 500 else 400, false)
            holder.row.contentDescription = if (isFixed(name)) "$name, fixed" else name
            holder.fixed.visibility = if (isFixed(name)) View.VISIBLE else View.GONE
            holder.handle.visibility = if (isFixed(name)) View.GONE else View.VISIBLE
        }
    }
}
