package com.gamefibre

import org.junit.Assert.*
import org.junit.Test

class CollectionPresentationTest {
    private fun game(store: LibraryStore, id: String, name: String) = LibraryEntry(store, id, name)

    @Test fun collectionTabsAreFixedAndIncludeStoreAndDownloadLists() {
        assertEquals(listOf("All games", "Steam", "GOG", "Epic", "Xbox", "Game Pass", "Favorites", "Downloads", "Wishlist", "Hidden"), CollectionData.BUILT_IN_LISTS)
    }

    @Test fun nameSortingIgnoresArticlesWithoutChangingDisplayedNames() {
        val games = listOf(game(LibraryStore.STEAM, "1", "The Witness"),
            game(LibraryStore.STEAM, "2", "A Short Hike"), game(LibraryStore.STEAM, "3", "Portal"))
        assertEquals(listOf("Portal", "A Short Hike", "The Witness"), filterLibrary(games, "", null, false, false).map { it.name })
        assertEquals(listOf("A Short Hike", "Portal", "The Witness"), filterLibrary(games, "", null, false, false, false).map { it.name })
        assertEquals(listOf("The Witness", "A Short Hike", "Portal"), filterLibrary(games, "", null, false, true).map { it.name })
    }

    @Test fun articleHandlingOnlyStripsWholeArticles() {
        assertEquals("Witness", librarySortName("Witness, The", true))
        assertEquals("Aarklash", librarySortName("Aarklash", true))
        assertEquals("Theatre", librarySortName("Theatre", true))
    }

    @Test fun crossStoreCopiesMergeWhileKeepingDownloadIdentities() {
        val epic = game(LibraryStore.EPIC, "epic-id", "Agent A: A Puzzle in Disguise™")
        val gog = game(LibraryStore.GOG, "gog-id", "Agent A - A Puzzle in Disguise")
        val steam = game(LibraryStore.STEAM, "123", "Agent A: A Puzzle in Disguise")
        val merged = mergeLibraryCopies(listOf(epic, gog, steam)).single()
        assertEquals(steam.key, merged.key)
        assertEquals(listOf(steam.key, gog.key, epic.key), merged.copies.map { it.key })
        assertTrue(merged.copies.all { it.copies.isEmpty() })
        assertEquals(listOf(steam.key, gog.key, epic.key).toSet(), matchingLibraryCopies(epic, listOf(steam, gog, epic)).map { it.key }.toSet())
    }

    @Test fun editionsAndSequelsAreNotMerged() {
        val games = listOf(game(LibraryStore.STEAM, "1", "Game"), game(LibraryStore.GOG, "2", "Game Remastered"),
            game(LibraryStore.EPIC, "3", "Game 2"), game(LibraryStore.GOG, "4", "Game Deluxe Edition"))
        assertEquals(4, mergeLibraryCopies(games).size)
        assertEquals(1, matchingLibraryCopies(games[0], games).size)
    }

    @Test fun xboxJoinsAnExistingGameWithoutReplacingItsDownloadSource() {
        val steam = game(LibraryStore.STEAM, "620", "Portal 2")
        val xbox = game(LibraryStore.XBOX, "1234567", "Portal 2")
        val merged = mergeLibraryCopies(listOf(xbox, steam)).single()
        assertEquals(steam.key, merged.key)
        assertEquals(listOf(steam.key, xbox.key), merged.copies.map { it.key })
        assertTrue(merged.store.downloads)
        assertFalse(xbox.store.downloads)
        assertEquals(listOf(xbox), filterLibrary(listOf(steam, xbox), "", LibraryStore.XBOX, false, false))
        assertEquals(setOf(steam.key, xbox.key), libraryListKeys(listOf(steam, xbox), setOf(xbox.key)))
    }

    @Test fun xboxAmbiguousConsoleEditionsDoNotMergeWithTheWrongPcProduct() {
        val games = listOf(game(LibraryStore.STEAM, "1", "Game"), game(LibraryStore.XBOX, "2", "Game"),
            game(LibraryStore.XBOX, "3", "Game"), game(LibraryStore.XBOX, "4", "Game Remastered"))
        assertEquals(4, mergeLibraryCopies(games).size)
    }

    @Test fun ambiguousSameStoreProductsAreLeftSeparate() {
        val games = listOf(game(LibraryStore.STEAM, "1", "Game"), game(LibraryStore.STEAM, "2", "Game"), game(LibraryStore.EPIC, "3", "Game"))
        assertEquals(3, mergeLibraryCopies(games).size)
        assertEquals(listOf(games[2]), matchingLibraryCopies(games[2], games))
    }

    @Test fun filteringAStoreRetainsItsConcreteCopy() {
        val games = listOf(game(LibraryStore.STEAM, "1", "Game"), game(LibraryStore.GOG, "2", "Game"))
        val filtered = filterLibrary(games, "", LibraryStore.GOG, false, false)
        assertEquals("GOG:2", mergeLibraryCopies(filtered).single().key)
    }
    @Test fun optimizedOrderingKeepsUnknownTimesLastInBothDirections() {
        val index = BeatIndex.parse(sequenceOf("id,main", "1,3600", "2,7200"), emptySequence())
        val games = listOf(game(LibraryStore.STEAM, "3", "Unknown"),
            game(LibraryStore.STEAM, "2", "The Long Game"), game(LibraryStore.STEAM, "1", "A Short Game"))
        assertEquals(listOf("1", "2", "3"), orderLibrary(games, "Time to beat", false, index).map { it.id })
        assertEquals(listOf("2", "1", "3"), orderLibrary(games, "Time to beat", true, index).map { it.id })
        assertEquals(listOf("2", "1", "3"), orderLibrary(games, "Name", false, index).map { it.id })
    }

}
