package com.gamefibre

/** Visible endpoints can exchange places in the pending order before its artwork is committed. */
internal fun libraryViewportRange(first: Int, last: Int, count: Int, columns: Int): IntRange {
    val start = (minOf(first, last) - columns * 2).coerceIn(0, count)
    val end = (maxOf(first, last) + columns * 8).coerceIn(start, count)
    return start until end
}
