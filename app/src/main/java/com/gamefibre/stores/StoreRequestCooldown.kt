package com.gamefibre.stores

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal class StoreRateLimited : IllegalStateException("The store is receiving too many requests. Wait a moment and try again.")
internal class StoreCatalogUnavailable : IllegalStateException("This game's information is unavailable.")

/** No automatic retry loop: queued calls and manual retries respect the server's cooldown. */
internal class StoreRequestCooldown(private val now: () -> Long = System::currentTimeMillis) {
    private val until = mutableMapOf<String, Long>()
    @Synchronized fun check(host: String) {
        if (now() < (until[host] ?: 0)) throw StoreRateLimited()
        until.remove(host)
    }
    @Synchronized fun rateLimited(host: String, retryAfter: String?) {
        val time = now()
        val seconds = retryAfter?.toLongOrNull()?.coerceIn(1, 86400)
        val date = runCatching { ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        val deadline = if (seconds != null) time + seconds * 1000 else date?.coerceIn(time + 1000, time + 86400_000) ?: (time + 60_000)
        until[host] = maxOf(until[host] ?: 0, deadline)
    }
}
