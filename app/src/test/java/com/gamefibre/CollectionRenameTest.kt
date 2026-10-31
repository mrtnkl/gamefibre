package com.gamefibre

import org.junit.Assert.*
import org.junit.Test

class CollectionRenameTest {
    @Test fun renamePersistsGamesOrderAndSelectedList() {
        val prefs = testPreferences()
        val collection = CollectionData(prefs)
        collection.createList("First", emptySet())
        collection.createList("Old name", setOf("STEAM:1", "GOG:2"))
        collection.createList("Last", setOf("EPIC:3"))
        collection.list = "Old name"
        collection.renameList("Old name", "  New name  ")
        val reopened = CollectionData(prefs)
        assertEquals(listOf("First", "New name", "Last"), reopened.customLists)
        assertEquals(setOf("STEAM:1", "GOG:2"), reopened.members("New name"))
        assertTrue(reopened.members("Old name").isEmpty())
        assertEquals(setOf("EPIC:3"), reopened.members("Last"))
        assertEquals("New name", reopened.list)
    }

    @Test fun invalidNamesCannotChangeCollectionIncludingHiddenReservedNames() {
        val collection = CollectionData(testPreferences())
        collection.createList("Mine", setOf("STEAM:1"))
        collection.createList("Other", emptySet())
        collection.removeList("GOG")
        val before = collection.preferences.all.toMap()
        val invalidNames = listOf("", "   ", " other ", "a\nb", "a".repeat(201)) +
            CollectionData.BUILT_IN_LISTS.map { " ${it.lowercase()} " } + GameProgress.entries.map { it.label.uppercase() }
        invalidNames.forEach { name ->
            assertNotNull(collection.renameListError("Mine", name))
            assertThrows(IllegalArgumentException::class.java) { collection.renameList("Mine", name) }
            assertEquals(before, collection.preferences.all)
        }
        assertThrows(IllegalArgumentException::class.java) { collection.renameList("Downloads", "New") }
    }

    @Test fun caseOnlyRenameAndEmptyListsWorkWithoutSwitchingTheActiveList() {
        val collection = CollectionData(testPreferences())
        collection.createList("mine", emptySet())
        collection.list = "All games"
        collection.renameList("mine", "Mine")
        assertEquals(listOf("Mine"), collection.customLists)
        assertTrue(collection.members("Mine").isEmpty())
        assertEquals("All games", collection.list)
        val before = collection.preferences.all.toMap()
        collection.renameList("Mine", "Mine")
        assertEquals(before, collection.preferences.all)
    }
}
