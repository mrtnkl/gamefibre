package com.gamefibre

import com.gamefibre.stores.StoreGame
import com.gamefibre.stores.XboxAccess
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CollectionSnapshotTest {
    private val details = GameDetails(description = "Description", developer = "Developer", publisher = "Publisher",
        release = "2026", portrait = "https://cdn.akamai.steamstatic.com/portrait.jpg",
        screenshots = listOf("https://cdn.akamai.steamstatic.com/screenshot.jpg"), tags = listOf("Adventure"),
        metascore = 91, steamStorageBytes = 1000, platforms = listOf("PC", "Xbox"))
    private val games = listOf(
        LibraryEntry(LibraryStore.STEAM, "620", "Portal 2", steam = LibraryGame(620, "Portal 2", 321, listOf("windows"), 12345, details)),
        LibraryEntry(LibraryStore.GOG, "42", "GOG game", remote = StoreGame("42", "GOG game", imageUrl = "https://images.gog.com/cover.jpg", details = details)),
        LibraryEntry(LibraryStore.EPIC, "epic_id", "Epic game", remote = StoreGame("epic_id", "Epic game", "namespace", "catalog-id", "https://cdn.epicgames.com/cover.jpg", details)),
        LibraryEntry(LibraryStore.XBOX, "9NBLGGH4R315", "Xbox owned", remote = StoreGame("9NBLGGH4R315", "Xbox owned", details = details, xboxAccess = XboxAccess.OWNED)),
        LibraryEntry(LibraryStore.XBOX, "9NBLGGH4R316", "Game Pass", remote = StoreGame("9NBLGGH4R316", "Game Pass", details = details, xboxAccess = XboxAccess.GAME_PASS)),
    )

    @Test fun fullCollectionSurvivesExportImportRestartAndSecondExport() {
        val source = CollectionData(testPreferences(mutableMapOf("private-session" to "secret", "theme" to "Dark",
            YouTubeQueries.PREFERENCE to "review\nlongplay", "descending" to true)))
        source.createList("Weekend", setOf(games[0].key, games[4].key))
        source.createList("Later", setOf(games[2].key))
        source.removeList("GOG")
        source.reorderLists(source.lists.reversed())
        source.list = "Weekend"
        source.sort = "Random"; source.randomSeed = 123456789L
        source.tileSize = "List"; source.showTitles = true; source.fitCoverArt = true
        source.showHiddenGames = true; source.showGamePass = false
        source.setMembership("Favorites", setOf(games[1].key, games[3].key), true)
        source.setMembership("Hidden", setOf(games[2].key), true)
        source.setProgress(setOf(games[0].key, games[3].key), GameProgress.FINISHED)
        val wish = LibraryEntry(LibraryStore.STEAM, "999", "Wishlist only", remote = StoreGame("999", "Wishlist only", details = details))
        source.addWishlist(listOf(wish))
        val covers = mapOf(games[0].key to "https://cdn2.steamgriddb.com/grid/selected.jpg")
        val data = CollectionSnapshot.create(source, games, covers)
        assertFalse(data.toString().contains("secret"))
        assertFalse(data.toString().contains("private-session"))
        val output = ByteArrayOutputStream()
        CollectionArchive.write(output, CollectionArchive.Contents(data))
        val imported = CollectionArchive.read(ByteArrayInputStream(output.toByteArray())).manifest
        val prefs = testPreferences()
        val destination = CollectionData(prefs)
        destination.mergeBackup(imported)
        destination.mergeBackup(imported)
        val restarted = CollectionData(prefs)
        assertEquals(games.associateBy { it.key }, restarted.libraryGames().associateBy { it.key })
        assertEquals(listOf(wish), restarted.wishlistGames())
        assertFalse(restarted.libraryGames().any { it.key == wish.key })
        assertEquals(source.lists, restarted.lists)
        assertEquals(source.list, restarted.list)
        assertEquals(source.customLists.toSet(), restarted.customLists.toSet())
        source.lists.forEach { assertEquals(source.members(it), restarted.members(it)) }
        assertEquals(GameProgress.FINISHED, restarted.progress(listOf(games[3].key)))
        assertEquals(source.sort, restarted.sort)
        assertEquals(source.tileSize, restarted.tileSize)
        assertEquals(source.showTitles, restarted.showTitles)
        assertEquals(source.fitCoverArt, restarted.fitCoverArt)
        assertEquals(source.showHiddenGames, restarted.showHiddenGames)
        assertEquals(source.showGamePass, restarted.showGamePass)
        assertEquals(source.randomSeed, restarted.randomSeed)
        assertEquals("Dark", prefs.getString("theme", null))
        assertEquals("review\nlongplay", prefs.getString(YouTubeQueries.PREFERENCE, null))
        assertTrue(prefs.getBoolean("descending", false))
        assertTrue(restarted.downloaded().isEmpty())
        val second = CollectionSnapshot.create(restarted, restarted.libraryGames(), covers)
        for (field in listOf("settings", "lists", "wishlist", "progress", "artwork", "removedBuiltIns"))
            assertEquals(field, data.get(field).toString(), second.get(field).toString())
    }

    @Test fun malformedLibraryCannotPartiallyChangeCollection() {
        val destination = CollectionData(testPreferences())
        val before = destination.preferences.all
        val data = CollectionSnapshot.create(destination, games, emptyMap())
        data.getJSONArray("games").getJSONObject(4).put("xboxAccess", "INVALID")
        assertThrows(IllegalArgumentException::class.java) { destination.mergeBackup(data) }
        assertEquals(before, destination.preferences.all)
    }

    @Test fun mergedCardsExportEveryStoreCopy() {
        val shared = games.take(2).map { it.copy(name = "Shared title") }
        val data = CollectionSnapshot.create(CollectionData(testPreferences()), mergeLibraryCopies(shared), emptyMap())
        assertEquals(shared.map { it.key }.toSet(), LibraryRecords.read(data.getJSONArray("games")).map { it.key }.toSet())
    }

    @Test fun signOutRemovesImportedLibraryForOnlyThatStore() {
        val destination = CollectionData(testPreferences())
        destination.mergeBackup(CollectionSnapshot.create(destination, games, emptyMap()))
        val changes = collectionChangesAfterSignOut(destination.preferences.all, "XBOX")
        assertEquals(setOf("library:XBOX:9NBLGGH4R315", "library:XBOX:9NBLGGH4R316"), changes.keys)
        assertTrue(changes.values.all { it == null })
    }
}
