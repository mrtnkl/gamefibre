package com.gamefibre.steam

import org.junit.Assert.*
import org.junit.Test

class ProgressPublicationTest {
    @Test fun chunkBurstsHaveBoundedPublicationWhileTheLatestProgressCanAdvance() {
        var time = 0L
        val publication = ProgressPublication { time }
        var count = 0
        repeat(1000) {
            time = it.toLong()
            if (publication.allow(false)) count++
        }
        assertEquals(7, count)
        time = 1050
        assertTrue(publication.allow(false))
    }

    @Test fun pauseFailureCompletionAndOtherForcedChangesPublishImmediately() {
        val publication = ProgressPublication { 0L }
        assertTrue(publication.allow(false))
        assertFalse(publication.allow(false))
        repeat(4) { assertTrue(publication.allow(true)) }
        assertFalse(publication.allow(false))
    }
}
