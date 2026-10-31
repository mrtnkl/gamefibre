package com.gamefibre

import kotlin.math.abs

/** Keep the originating row's order, skipping games removed since that row was opened. */
internal fun adjacentGameKey(sequence: List<String>, current: String, direction: Int, available: Set<String>): String? {
    val index = sequence.indexOf(current)
    if (index < 0 || (direction != -1 && direction != 1)) return null
    var next = index + direction
    while (next in sequence.indices) {
        if (sequence[next] in available) return sequence[next]
        next += direction
    }
    return null
}

/** Lock the initial direction so vertical scrolling cannot turn into a page swipe. */
internal class GamePageGesture(private val slop: Float, private val distance: Float) {
    enum class Action { PREVIOUS, NEXT, CLOSE }
    private var startX = 0f
    private var startY = 0f
    private var atTop = false
    private var horizontalAllowed = false
    private var tracking = false
    private var direction: Action? = null
    val horizontal: Boolean get() = direction == Action.NEXT || direction == Action.PREVIOUS
    var claimed = false
        private set

    fun start(x: Float, y: Float, atTop: Boolean, horizontalAllowed: Boolean) {
        startX = x; startY = y
        this.atTop = atTop; this.horizontalAllowed = horizontalAllowed
        tracking = true; direction = null; claimed = false
    }

    fun move(x: Float, y: Float) {
        if (!tracking) return
        val dx = x - startX
        val dy = y - startY
        if (direction == null && maxOf(abs(dx), abs(dy)) > slop) {
            direction = when {
                horizontalAllowed && abs(dx) > abs(dy) * 1.5f ->
                    if (dx < 0) Action.NEXT else Action.PREVIOUS
                atTop && dy > abs(dx) * 1.5f -> Action.CLOSE
                else -> { tracking = false; return }
            }
        }
        // Follow either navigation gesture after touch slop; commit only on release.
        if (direction != null) claimed = true
    }

    fun finish(x: Float, y: Float): Action? {
        val action = direction?.takeIf { tracking && claimed && matches(x - startX, y - startY) }
        tracking = false
        return action
    }

    // A second finger cancels navigation, while a claimed stream stays consumed until release.
    fun cancel() { tracking = false }

    private fun matches(dx: Float, dy: Float): Boolean = when (direction) {
        Action.NEXT -> -dx >= distance && -dx > abs(dy) * 1.5f
        Action.PREVIOUS -> dx >= distance && dx > abs(dy) * 1.5f
        Action.CLOSE -> dy >= distance && dy > abs(dx) * 1.5f
        null -> false
    }
}
