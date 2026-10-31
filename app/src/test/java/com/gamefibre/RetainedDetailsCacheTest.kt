package com.gamefibre

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RetainedDetailsCacheTest {
    private val saved = GameDetails(description = "Saved description", screenshots = listOf("https://example.com/shot.jpg"))

    @Test fun freshDiskDetailsNeedNoStoreSessionOrNetwork() {
        val cache = RetainedDetailsCache()
        assertEquals(saved, cache.load("epic:game", 1, 1L to saved,
            { error("Must not authenticate") }, { error("Must not rewrite") }))
    }

    @Test fun failedRefreshRetainsDetailsAndScreenshotsAcrossRepositoryRestarts() {
        repeat(2) {
            assertEquals(saved, RetainedDetailsCache().load("steam:620", 2, 1L to saved,
                { throw IOException("Offline") }, { error("Must not overwrite saved data") }))
        }
    }

    @Test fun failedRequestsAreThrottledAndCanRecover() {
        var now = 0L
        var attempts = 0
        val cache = RetainedDetailsCache { now }
        val refreshed = GameDetails(description = "Updated description")
        assertNull(cache.load("game", 1, null, { attempts++; throw IOException() }, {}))
        assertNull(cache.load("game", 1, null, { attempts++; refreshed }, {}))
        assertEquals(1, attempts)
        now = 60_000
        var written: GameDetails? = null
        assertEquals(refreshed, cache.load("game", 1, null, { attempts++; refreshed }, { written = it }))
        assertEquals(refreshed, written)
        assertEquals(2, attempts)
    }

    @Test fun explicitRefreshAllowsRetryWhileKeepingStaleData() {
        val cache = RetainedDetailsCache()
        cache.load("game", 1, 0L to saved, { throw IOException() }, {})
        cache.clearFailures()
        var attempted = false
        assertEquals(saved, cache.load("game", 2, 0L to saved, { attempted = true; throw IOException() }, {}))
        assertTrue(attempted)
    }

    @Test fun storageFailureDoesNotHideNewlyFetchedDetails() {
        val updated = GameDetails(description = "Updated")
        assertEquals(updated, RetainedDetailsCache().load("game", 2, 1L to saved,
            { updated }, { throw IOException("Disk full") }))
    }

    @Test(expected = CancellationException::class) fun cancellationDoesNotBecomeAnOfflineResult() {
        RetainedDetailsCache().load("game", 2, 1L to saved, { throw CancellationException() }, {})
    }
}
