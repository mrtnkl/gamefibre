package com.gamefibre

import org.junit.Assert.*
import org.junit.Test

class PageDetailsMemoryCacheTest {
    @Test fun successfulRefreshReplacesOldSourceValuesEvenWhenTheyAreShorter() {
        val cache = PageDetailsMemoryCache { 0 }
        cache.record(listOf("game"), GameDetails(description="Long outdated description",portrait="https://example.com/old.jpg",release="2020"), false)
        cache.invalidate()
        cache.record(listOf("game"), GameDetails(description="New",portrait="https://example.com/new.jpg",release="2026"), false)
        assertEquals("New",cache["game"]?.description)
        assertEquals("https://example.com/new.jpg",cache["game"]?.portrait)
        assertEquals("2026",cache["game"]?.release)
    }

    @Test fun refreshMakesDetailsRetryableWithoutRemovingUsableMetadata() {
        val cache = PageDetailsMemoryCache { 100 }
        cache.record(listOf("game", "copy"), GameDetails(description = "Saved description", metascore = 88), retryNeeded = false)
        assertFalse(cache.shouldLoad("game"))
        cache.invalidate()
        assertTrue(cache.shouldLoad("game"))
        assertTrue(cache.shouldLoad("copy"))
        assertEquals("Saved description", cache["game"]?.description)
        assertEquals(88, cache["copy"]?.metascore)
    }
    @Test fun failedLoadBecomesRetryableWithoutRestartOrLibraryRefresh() {
        var time = 0L
        val cache = PageDetailsMemoryCache { time }
        assertTrue(cache.shouldLoad("game"))
        cache.record(listOf("game", "copy"), GameDetails(), retryNeeded = true)
        assertFalse(cache.shouldLoad("game"))
        time = 15 * 60 * 1000L
        assertTrue(cache.shouldLoad("game"))
        assertTrue(cache.shouldLoad("copy"))
        cache.record(listOf("game", "copy"), GameDetails(metascore = 88), retryNeeded = false)
        time += 24 * 60 * 60 * 1000L
        assertFalse(cache.shouldLoad("game"))
        assertEquals(88, cache["copy"]?.metascore)
    }
    @Test fun partialFailuresKeepPreviouslyLoadedDetails() {
        val cache = PageDetailsMemoryCache { 0 }
        cache.record(listOf("game"), GameDetails(description = "Story", metascore = 88), retryNeeded = false)
        cache.record(listOf("game"), GameDetails(), retryNeeded = true)
        assertEquals("Story", cache["game"]?.description)
        assertEquals(88, cache["game"]?.metascore)
        cache.clear()
        assertTrue(cache.shouldLoad("game"))
        assertNull(cache["game"])
    }
    @Test fun successfulEmptyResponseIsNotRetried() {
        val cache = PageDetailsMemoryCache { Long.MAX_VALUE / 2 }
        cache.record(listOf("game"), GameDetails(), retryNeeded = false)
        assertFalse(cache.shouldLoad("game"))
    }

    @Test fun recentlyUsedDetailsSurviveWhenTheBoundIsReached() {
        val cache = PageDetailsMemoryCache(limit = 3) { 0 }
        listOf("a", "b", "c").forEach { cache.record(listOf(it), GameDetails(description = it), retryNeeded = false) }
        assertEquals("a", cache["a"]?.description)
        cache.record(listOf("d"), GameDetails(description = "d"), retryNeeded = false)
        assertEquals(3, cache.size)
        assertTrue("a" in cache)
        assertFalse("b" in cache)
        assertTrue(cache.shouldLoad("b"))
    }
}
