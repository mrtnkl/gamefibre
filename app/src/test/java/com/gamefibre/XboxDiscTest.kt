package com.gamefibre

import com.gamefibre.stores.StoreGame
import com.gamefibre.stores.XboxAccess
import org.junit.Assert.*
import org.junit.Test

class XboxDiscTest {
    private val game = LibraryEntry(LibraryStore.XBOX, "9NBLGGH4R315", "Disc game",
        remote = StoreGame("9NBLGGH4R315", "Disc game", imageUrl = "https://store-images.s-microsoft.com/cover.jpg",
            details = GameDetails(platforms = listOf("Xbox One"))))

    @Test fun discOwnershipSurvivesRestartBackupAndAccountSignOut() {
        val prefs = testPreferences()
        val collection = CollectionData(prefs)
        collection.addDisc(game)
        collection.addDisc(game)
        collection.setMembership("Favorites", setOf(game.key), true)
        collection.setProgress(setOf(game.key), GameProgress.FINISHED)
        val disc = CollectionData(prefs).discGames().single()
        assertEquals(XboxAccess.DISC, disc.remote!!.xboxAccess)
        assertEquals(game.details, disc.details)
        assertTrue(collection.downloaded().isEmpty())
        assertTrue(collection.wishlistGames().isEmpty())
        assertTrue(collectionChangesAfterSignOut(prefs.all, "XBOX").isEmpty())
        val restored = CollectionData(testPreferences())
        restored.mergeBackup(CollectionSnapshot.create(collection, collection.libraryGames(), emptyMap()))
        assertEquals(listOf(disc), restored.discGames())
        assertEquals(GameProgress.FINISHED, restored.progress(listOf(game.key)))
        assertTrue(restored.contains("Favorites", game.key))
    }

    @Test fun discIsOwnedDespiteGamePassAndRemovalDoesNotResurrectCachedDisc() {
        val collection = CollectionData(testPreferences())
        collection.addDisc(game)
        val disc = collection.discGames().single()
        val pass = game.copy(remote = game.remote!!.copy(xboxAccess = XboxAccess.GAME_PASS))
        fun merged(remote: List<LibraryEntry>) = mergeLibrary(emptyList(), remote, collection.libraryGames(),
            listOf(disc), emptyList(), emptyList(), emptyList())
        assertEquals(listOf(disc), merged(listOf(pass)).owned)
        assertTrue(game.key in merged(listOf(pass)).observedKeys)
        collection.removeDisc(game.key)
        assertTrue(merged(emptyList()).owned.isEmpty())
        assertEquals(listOf(pass), merged(listOf(pass)).owned)
    }

    @Test fun invalidSelectionCannotChangeSavedDiscs() {
        val collection = CollectionData(testPreferences())
        collection.addDisc(game)
        val before = collection.preferences.all
        assertThrows(IllegalArgumentException::class.java) { collection.addDisc(game.copy(store = LibraryStore.STEAM)) }
        assertThrows(IllegalArgumentException::class.java) { collection.addDisc(game.copy(id = "invalid")) }
        assertEquals(before, collection.preferences.all)
    }
}
