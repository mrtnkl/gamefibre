package com.gamefibre

import org.junit.Assert.*
import org.junit.Test

class GameProgressTest {
    @Test fun unplayedIsDefaultAndNeverCreatesAnAutomaticTab() {
        val collection = CollectionData(testPreferences())
        assertEquals(GameProgress.UNPLAYED, collection.progress(listOf("STEAM:1")))
        assertFalse("Unplayed" in collection.lists)
        assertTrue(collection.visibleLists { false }.none { GameProgress.fromLabel(it) != null })
        collection.setProgress(setOf("STEAM:1"), GameProgress.PLAYING)
        assertEquals(listOf("Playing"), collection.visibleLists { it == "Playing" }.filter { GameProgress.fromLabel(it) != null })
        collection.setProgress(setOf("STEAM:1"), GameProgress.UNPLAYED)
        assertTrue(collection.visibleLists { false }.none { GameProgress.fromLabel(it) != null })
        collection.createList("Empty", emptySet())
        assertTrue("Empty" in collection.visibleLists { false })
    }

    @Test fun progressIsExclusiveAndSharedAcrossOwnedCopies() {
        val collection = CollectionData(testPreferences())
        val keys = setOf("STEAM:1", "GOG:2", "EPIC:3")
        GameProgress.entries.forEach { state ->
            collection.setProgress(keys, state)
            keys.forEach { assertEquals(state, collection.progress(listOf(it))) }
        }
        assertThrows(IllegalArgumentException::class.java) { collection.removeList("Finished") }
        assertThrows(IllegalArgumentException::class.java) { collection.createList("playing", emptySet()) }
    }

}
