package com.gamefibre.steam

/** Bound UI work while retaining every checkpoint update; transitions publish immediately. */
internal class ProgressPublication(private val now: () -> Long) {
    private var publishedAt: Long? = null
    @Synchronized fun allow(force: Boolean): Boolean {
        val time = now()
        if (!force && publishedAt?.let { time - it < 150 } == true) return false
        publishedAt = time
        return true
    }
}
