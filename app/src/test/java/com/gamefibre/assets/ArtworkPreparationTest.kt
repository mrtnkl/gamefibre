package com.gamefibre.assets

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ArtworkPreparationTest {
    @Test fun readyImagePublishesWhileAnotherDownloadIsStillBlocked() = runBlocking {
        withTimeout(5_000) {
            val slow = CompletableDeferred<Unit>()
            val firstReady = CompletableDeferred<Unit>()
            val results = mutableListOf<PreparedArtwork<Int, String>>()
            val loading = launch {
                prepareArtwork(listOf(1, 2, 3)) {
                    when (it) {
                        1 -> { slow.await(); "slow image" }
                        2 -> "ready image"
                        else -> error("Broken thumbnail")
                    }
                }.collect {
                    results += it
                    if (it.source == 2) firstReady.complete(Unit)
                }
            }
            firstReady.await()
            assertFalse(loading.isCompleted)
            assertEquals("ready image", results.single { it.source == 2 }.image)
            assertFalse(results.any { it.source == 1 })
            slow.complete(Unit)
            loading.join()
            assertEquals(3, results.size)
            assertNull(results.single { it.source == 3 }.image)
            assertEquals("slow image", results.single { it.source == 1 }.image)
        }
    }

    @Test fun preparationHasAtMostFourActiveDownloads() = runBlocking {
        withTimeout(5_000) {
            val release = CompletableDeferred<Unit>()
            val fourStarted = CompletableDeferred<Unit>()
            var active = 0
            var peak = 0
            var completed = 0
            val loading = launch {
                prepareArtwork((1..12).toList()) {
                    active++
                    peak = maxOf(peak, active)
                    if (active == 4) fourStarted.complete(Unit)
                    try { release.await(); it } finally { active-- }
                }.collect { completed++ }
            }
            fourStarted.await()
            assertEquals(4, active)
            release.complete(Unit)
            loading.join()
            assertEquals(4, peak)
            assertEquals(12, completed)
        }
    }

    @Test fun cancellationStopsPreparationWithoutPublishingOldResults() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            val blocked = CompletableDeferred<Unit>()
            var active = 0
            var published = 0
            val loading = launch {
                prepareArtwork((1..12).toList()) {
                    active++
                    started.complete(Unit)
                    try { blocked.await(); it } finally { active-- }
                }.collect { published++ }
            }
            started.await()
            loading.cancelAndJoin()
            assertEquals(0, active)
            assertEquals(0, published)
        }
    }
}
