package com.gamefibre.stores

import org.junit.Assert.*
import org.junit.Test

class StoreRequestCooldownTest {
    @Test fun rateLimitBlocksQueuedRequestsForTheHostUntilRetryAfter() {
        var time = 0L
        val cooldown = StoreRequestCooldown { time }
        cooldown.rateLimited("catalog", "120")
        assertThrows(StoreRateLimited::class.java) { cooldown.check("catalog") }
        cooldown.check("authentication")
        time = 119_999
        assertThrows(StoreRateLimited::class.java) { cooldown.check("catalog") }
        time = 120_000
        cooldown.check("catalog")
    }
    @Test fun retryAfterDatesAndInvalidHeadersAreHandledWithoutImmediateRetry() {
        var time = 0L
        val cooldown = StoreRequestCooldown { time }
        cooldown.rateLimited("dated", "Thu, 1 Jan 1970 00:02:00 GMT")
        cooldown.rateLimited("fallback", "invalid")
        time = 60_000
        cooldown.check("fallback")
        assertThrows(StoreRateLimited::class.java) { cooldown.check("dated") }
        time = 120_000
        cooldown.check("dated")
    }
}
