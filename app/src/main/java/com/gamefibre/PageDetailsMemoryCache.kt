package com.gamefibre

/**
 * A failed fetch can display partial details without being mistaken for a completed load.
 * Entries are bounded by recency; the disk repository remains the durable copy.
 */
internal class PageDetailsMemoryCache(private val limit: Int = 256, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val retryAt = mutableMapOf<String, Long>()
    private val details = object : LinkedHashMap<String, GameDetails>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GameDetails>): Boolean =
            (size > limit).also { if (it) retryAt.remove(eldest.key) }
    }
    operator fun get(key: String) = details[key]
    operator fun contains(key: String) = key in details
    fun shouldLoad(key: String) = key !in details || retryAt[key]?.let { now() >= it } == true
    fun record(keys: List<String>, value: GameDetails, retryNeeded: Boolean) {
        keys.forEach { key ->
            details[key] = if (retryNeeded) mergeGameDetails(listOfNotNull(value, details[key])) else value
            if (retryNeeded) retryAt[key] = now() + 15 * 60 * 1000 else retryAt.remove(key)
        }
    }
    fun invalidate() { details.keys.forEach { retryAt[it] = now() } }
    fun clear() { details.clear(); retryAt.clear() }
    val size get() = details.size
}
