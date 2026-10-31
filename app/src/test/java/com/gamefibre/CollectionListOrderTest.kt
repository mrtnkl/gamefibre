package com.gamefibre

import org.junit.Assert.*
import org.junit.Test

class CollectionListOrderTest {
    @Test fun orderSurvivesRestartAndVisibilityChangesWithFixedEnds() {
        val prefs = testPreferences()
        val collection = CollectionData(prefs)
        collection.createList("Weekend", setOf("STEAM:1"))
        collection.list = "Weekend"
        val reversed = collection.lists.reversed()
        val expected = listOf("All games") + (reversed - setOf("All games", "Wishlist", "Hidden")) + listOf("Wishlist", "Hidden")
        collection.reorderLists(reversed)
        assertEquals("Weekend", expected[1])
        val reopened = CollectionData(prefs)
        assertEquals(expected, reopened.lists)
        assertEquals("Weekend", reopened.list)
        assertEquals(expected.filter { it != "Hidden" }, reopened.visibleLists { true })
        reopened.showHiddenGames = true
        assertEquals(expected, reopened.visibleLists { true })
    }

    @Test fun renameKeepsPositionAndNewListsAppendAfterRemoval() {
        val prefs = testPreferences()
        val collection = CollectionData(prefs)
        collection.createList("Weekend", setOf("STEAM:1"))
        collection.reorderLists(listOf("All games", "Weekend") + collection.lists.filter { it !in setOf("All games", "Weekend") })
        collection.renameList("Weekend", "Later")
        assertEquals("Later", CollectionData(prefs).lists[1])
        assertEquals(setOf("STEAM:1"), collection.members("Later"))
        collection.removeList("Later")
        collection.createList("Later", emptySet())
        assertEquals("Later", CollectionData(prefs).lists.dropLast(2).last())
        assertEquals("Hidden", CollectionData(prefs).lists.last())
    }

    @Test fun hiddenStaysLastWhenListsAreAddedAndVisibilityChanges() {
        val collection = CollectionData(testPreferences())
        collection.reorderLists(collection.lists.reversed())
        collection.createList("Weekend", emptySet())
        collection.showHiddenGames = true
        assertEquals("Hidden", collection.visibleLists { true }.last())
        assertEquals("Weekend", collection.lists.dropLast(2).last())
        collection.showHiddenGames = false
        assertFalse("Hidden" in collection.visibleLists { true })
        collection.createList("Later", emptySet())
        collection.showHiddenGames = true
        assertEquals("Hidden", collection.visibleLists { true }.last())
        assertEquals("Later", collection.lists.dropLast(2).last())
    }

    @Test fun invalidOrderCannotDropOrDuplicateLists() {
        val collection = CollectionData(testPreferences())
        val before = collection.preferences.all.toMap()
        for (order in listOf(collection.lists.drop(1), collection.lists + "Unknown", collection.lists.dropLast(1) + "All games")) {
            assertThrows(IllegalArgumentException::class.java) { collection.reorderLists(order) }
            assertEquals(before, collection.preferences.all)
        }
    }
}
