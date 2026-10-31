package com.gamefibre

import com.gamefibre.stores.StoreGame
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class WishlistTest {
    private fun game(id: String = "620", store: LibraryStore = LibraryStore.STEAM) =
        LibraryEntry(store, id, "Portal 2", remote = StoreGame(id, "Portal 2",
            imageUrl = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/620/library_capsule.jpg",
            details = GameDetails(description = "A game", platforms = listOf("PC"),
                storeLinks = mapOf(store.label to if (store == LibraryStore.GOG) "https://www.gog.com/en/game/portal_2" else "https://store.steampowered.com/app/620/"))))

    @Test fun wishlistIsPermanentAndFixedBeforeHiddenEvenAfterReorderingAndNewLists() {
        val collection = CollectionData(testPreferences())
        assertEquals(listOf("Wishlist", "Hidden"), collection.lists.takeLast(2))
        assertEquals("Wishlist", collection.visibleLists { false }.last())
        assertFalse("Wishlist" in collection.editableLists)
        assertFalse(collection.canRemoveList("Wishlist"))
        assertThrows(IllegalArgumentException::class.java) { collection.removeList("Wishlist") }
        assertThrows(IllegalArgumentException::class.java) { collection.createList("wishlist", emptySet()) }
        collection.reorderLists(collection.lists.reversed())
        collection.createList("Weekend", emptySet())
        assertEquals(listOf("Wishlist", "Hidden"), collection.lists.takeLast(2))
        assertEquals("Weekend", collection.lists.dropLast(2).last())
        collection.showHiddenGames = true
        assertEquals(listOf("Wishlist", "Hidden"), collection.visibleLists { false }.takeLast(2))
    }

    @Test fun addingRemovingAndReopeningPreservesStoreIdentityAndMetadataWithoutDownloads() {
        val prefs = testPreferences()
        val collection = CollectionData(prefs)
        val steam = game(); val gog = game("123", LibraryStore.GOG)
        assertEquals(2, collection.addWishlist(listOf(steam, gog, steam)))
        assertEquals(0, collection.addWishlist(listOf(steam)))
        val reopened = CollectionData(prefs)
        assertEquals(listOf(steam, gog), reopened.wishlistGames())
        assertTrue(reopened.downloaded().isEmpty())
        assertTrue(reopened.members("All games").isEmpty())
        reopened.removeWishlist(setOf(steam.key))
        assertEquals(listOf(gog), collection.wishlistGames())
        assertNull(gog.remote!!.xboxAccess)
    }

    @Test fun signOutKeepsLocalWishlistSelections() {
        val prefs = testPreferences()
        val collection = CollectionData(prefs)
        collection.addWishlist(listOf(game()))
        assertFalse(collectionChangesAfterSignOut(prefs.all, "STEAM").keys.any { it.startsWith("wishlist") })
    }

    @Test fun malformedOrDuplicateWishlistEntriesAreRejectedBeforeSaving() {
        val collection = CollectionData(testPreferences())
        val game = game()
        collection.addWishlist(listOf(game))
        val before = collection.preferences.all
        assertThrows(IllegalArgumentException::class.java) { collection.addWishlist(listOf(game.copy(id = "../bad"))) }
        assertEquals(before, collection.preferences.all)
        assertThrows(IllegalArgumentException::class.java) {
            WishlistItems.read(JSONArray().put(WishlistItems.json(game)).put(WishlistItems.json(game)))
        }
        assertEquals("", WishlistItems.imageUrl("https://shared.akamai.steamstatic.com.evil.test/image.jpg"))
        assertEquals("", WishlistItems.imageUrl("http://images.gog-statics.com/image.jpg"))
    }
}
