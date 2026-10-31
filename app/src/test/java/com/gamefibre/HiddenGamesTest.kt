package com.gamefibre

import org.junit.Assert.*
import org.junit.Test

class HiddenGamesTest {
    @Test fun hideAndUnhidePersistWithoutChangingProgressListsOrArtworkPreferences() {
        val prefs = testPreferences(mutableMapOf("members:Favorites" to setOf("STEAM:1"), "custom-cover" to "keep"))
        val collection = CollectionData(prefs)
        collection.setProgress(setOf("STEAM:1"), GameProgress.PLAYING)
        collection.setMembership(CollectionData.HIDDEN_LIST, setOf("STEAM:1", "GOG:2"), true)
        collection.showHiddenGames = true
        val reopened = CollectionData(prefs)
        assertEquals(setOf("STEAM:1", "GOG:2"), reopened.members(CollectionData.HIDDEN_LIST))
        assertTrue(reopened.showHiddenGames)
        reopened.setMembership(CollectionData.HIDDEN_LIST, setOf("STEAM:1", "GOG:2"), false)
        assertTrue(reopened.members(CollectionData.HIDDEN_LIST).isEmpty())
        assertEquals(GameProgress.PLAYING, reopened.progress(listOf("STEAM:1")))
        assertEquals(setOf("STEAM:1"), reopened.members("Favorites"))
        assertEquals("keep", prefs.getString("custom-cover", null))
    }

    @Test fun hiddenListVisibilityFollowsSettingEvenWhenEmptyAndCannotBeDeleted() {
        val collection = CollectionData(testPreferences())
        assertTrue("Hidden" in collection.lists)
        assertFalse("Hidden" in collection.visibleLists { true })
        assertFalse("Hidden" in collection.editableLists)
        assertThrows(IllegalArgumentException::class.java) { collection.createList("hidden", emptySet()) }
        collection.showHiddenGames = true
        assertTrue("Hidden" in collection.visibleLists { false })
        assertTrue("Hidden" in collection.editableLists)
        assertFalse(collection.canRemoveList("Hidden"))
        assertThrows(IllegalArgumentException::class.java) { collection.removeList("Hidden") }
        assertThrows(IllegalArgumentException::class.java) { collection.renameList("Hidden", "Something") }
        collection.setMembership("Hidden", setOf("STEAM:1"), true)
        collection.showHiddenGames = false
        assertFalse("Hidden" in collection.visibleLists { true })
        assertEquals(setOf("STEAM:1"), collection.members("Hidden"))
    }

    @Test fun hiddenChoiceFollowsMergedCopiesIncludingNewlyConnectedStores() {
        val steam = LibraryEntry(LibraryStore.STEAM, "1", "The Game")
        val gog = LibraryEntry(LibraryStore.GOG, "2", "The Game")
        val epic = LibraryEntry(LibraryStore.EPIC, "3", "Other Game")
        assertEquals(setOf(steam.key, gog.key), libraryListKeys(listOf(steam, gog, epic), setOf(gog.key)))
        assertTrue(libraryListKeys(listOf(steam, gog, epic), emptySet()).isEmpty())
    }

    @Test fun ambiguousProductsAndDifferentEditionsAreNotHiddenByTitleGuessing() {
        val games = listOf(LibraryEntry(LibraryStore.STEAM, "1", "Game"),
            LibraryEntry(LibraryStore.STEAM, "2", "Game"), LibraryEntry(LibraryStore.GOG, "3", "Game"),
            LibraryEntry(LibraryStore.EPIC, "4", "Game Remastered"))
        assertEquals(setOf("STEAM:1"), libraryListKeys(games, setOf("STEAM:1")))
    }
}
