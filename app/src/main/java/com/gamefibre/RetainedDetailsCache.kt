package com.gamefibre

import java.util.concurrent.ConcurrentHashMap

/** Refresh changes freshness, never the availability of the last successful response. */
internal class RetainedDetailsCache(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val failures = ConcurrentHashMap<String, Pair<Long, Long>>()
    fun clearFailures() = failures.clear()

    fun load(key: String, generation: Long, cached: Pair<Long, GameDetails>?,
        fetch: () -> GameDetails, save: (GameDetails) -> Unit): GameDetails? {
        if (cached?.first == generation) return cached.second
        failures[key]?.let { (failedGeneration, time) ->
            if (failedGeneration == generation && now() - time < 60_000) return cached?.second
        }
        val details = try { fetch() } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException) throw error
            failures[key] = generation to now()
            return cached?.second
        }
        // A full disk must not hide successfully fetched details or destroy the old file.
        runCatching { save(details) }
        failures.remove(key)
        return details
    }
}
