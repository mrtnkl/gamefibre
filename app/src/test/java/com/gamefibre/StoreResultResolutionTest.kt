package com.gamefibre

import com.gamefibre.stores.StoreGame
import org.junit.Assert.*
import org.junit.Test

class StoreResultResolutionTest {
    private val result = CatalogGame(620, "Portal 2", 95, 0, 0, 0).entry()
    private fun id(game: LibraryEntry) = if (game.store == LibraryStore.STEAM) game.id.toIntOrNull() else null
    private fun gog(id: String, name: String = "Portal 2") = LibraryEntry(LibraryStore.GOG, id, name,
        remote = StoreGame(id, name, details = GameDetails(developer = "Owned metadata")))

    @Test fun steamIdentityWinsOverRenamedCatalogAndRetainsOwnedData() {
        val owned = LibraryEntry(LibraryStore.STEAM, "620", "Current title", steam =
            LibraryGame(620, "Current title", 350, listOf("windows"), 1234))
        val actual = resolveOwnedStoreResult(result, listOf(owned), ::id)
        assertEquals(owned, actual)
        assertEquals(350, actual.steam!!.minutes)
        assertEquals(1234L, actual.sizeBytes)
    }

    @Test fun anotherStoreResolvesThroughItsSteamIdEvenWhenNamesDiffer() {
        val owned = gog("123", "Owned title alias")
        val actual = resolveOwnedStoreResult(result, listOf(owned)) { if (it.key == owned.key) 620 else id(it) }
        assertEquals(owned.key, actual.key)
        assertSame(owned.remote, actual.remote)
        assertEquals("Owned metadata", actual.details.developer)
    }

    @Test fun unambiguousOtherStoreTitleCanResolveWithoutSteamMetadata() {
        val owned = gog("123")
        assertEquals(owned, resolveOwnedStoreResult(result, listOf(owned), ::id))
    }

    @Test fun differentSteamAppWithSameNameAndConflictingOtherStoreIdsStayUnowned() {
        val otherSteam = result.copy(id = "621")
        assertSame(result, resolveOwnedStoreResult(result, listOf(otherSteam), ::id))
        val otherGog = gog("123")
        assertSame(result, resolveOwnedStoreResult(result, listOf(otherGog)) { if (it.store == LibraryStore.GOG) 621 else id(it) })
    }

    @Test fun ambiguousOtherStoreTitlesAreNotGuessed() {
        assertSame(result, resolveOwnedStoreResult(result, listOf(gog("123"), gog("456")), ::id))
    }

    @Test fun normalMergedOwnedCopiesArePreserved() {
        val steam = result.copy(steam = result.steam!!.copy(minutes = 400))
        val gog = gog("123")
        val actual = resolveOwnedStoreResult(result, listOf(gog, steam), ::id)
        assertEquals(steam.key, actual.key)
        assertEquals(listOf(steam.key, gog.key), actual.copies.map { it.key })
        assertEquals(400, actual.steam!!.minutes)
    }
}
