package com.gamefibre

import org.junit.Assert.*
import org.junit.Test

class CollectionListsTest {

    @Test fun deletingBuiltInsPersistsAndRemovesThemFromEveryListPicker() {
        val saved = testPreferences()
        val collection = CollectionData(saved)
        for (name in listOf("Favorites", "Steam", "GOG", "Epic", "Xbox", "Game Pass", "All games")) {
            collection.list = name
            collection.removeList(name)
            val reopened = CollectionData(saved)
            assertFalse(name in reopened.lists)
            assertFalse(name in reopened.editableLists)
            assertFalse(name in reopened.visibleLists { true })
            assertNotEquals(name, reopened.list)
        }
        assertEquals(listOf("Downloads") + GameProgress.entries.filter { it != GameProgress.UNPLAYED }.map { it.label } + listOf("Wishlist", "Hidden"), collection.lists)
        assertEquals(listOf("Wishlist"), collection.visibleLists { false })
        collection.list = ""
        assertEquals("", CollectionData(saved).list)
    }

    @Test fun deletedNamesCanBeRecreatedAsOrdinaryListsWithoutOldMembers() {
        val collection = CollectionData(testPreferences())
        for (name in listOf("Favorites", "Steam", "All games")) {
            collection.setMembership(name, setOf("STEAM:old"), true)
            collection.removeList(name)
            assertTrue(collection.members(name).isEmpty())
            collection.createList(name, setOf("GOG:new"))
            assertEquals(1, collection.lists.count { it == name })
            assertTrue(name in collection.editableLists)
            assertFalse(collection.isAutomaticList(name))
            assertEquals(setOf("GOG:new"), collection.members(name))
            collection.removeList(name)
            assertFalse(name in collection.lists)
        }
    }

    @Test fun removedBuiltInsStayDeletedAndTheirNamesCanBeReused() {
        val collection = CollectionData(testPreferences(mutableMapOf("removed-built-in-lists" to setOf("Favorites", "Downloads"))))
        assertFalse("Favorites" in collection.lists)
        collection.createList("favorites", emptySet())
        assertTrue("favorites" in collection.editableLists)
        assertTrue("Downloads" in collection.lists)
    }

    @Test fun downloadsVisibilityFollowsContentsAndCannotBeManuallyDeletedOrDuplicated() {
        val collection = CollectionData(testPreferences())
        assertFalse("Downloads" in collection.visibleLists { false })
        assertTrue("Downloads" in collection.visibleLists { true })
        assertFalse("Downloads" in collection.visibleLists { false })
        assertFalse("Downloads" in collection.editableLists)
        assertThrows(IllegalArgumentException::class.java) { collection.removeList("Downloads") }
        assertThrows(IllegalArgumentException::class.java) { collection.createList("downloads", emptySet()) }
        collection.createList("Empty custom list", emptySet())
        assertTrue("Empty custom list" in collection.visibleLists { false })
        collection.removeList("Empty custom list")
        assertFalse("Empty custom list" in collection.editableLists)
    }
}
