package com.gamefibre.assets

import android.content.Context
import com.gamefibre.LibraryEntry
import com.gamefibre.LibraryStore
import com.gamefibre.stores.StoreGame
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssetStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var store: AssetStore
    private val databaseName = "asset-store-test.sqlite"

    @Before fun open() {
        context = RuntimeEnvironment.getApplication()
        store = AssetStore(context, databaseName, temporary.newFolder())
    }
    @After fun close() { store.close(); context.deleteDatabase(databaseName) }

    private fun game(store: LibraryStore, id: String, name: String = "Game $id") =
        LibraryEntry(store, id, name, remote = StoreGame(id, name))

    @Test fun stagingInsideOneTransactionCommitsEveryRow() {
        val games = (1..5).map { game(LibraryStore.GOG, it.toString()) }
        store.transaction { games.forEach { store.stage(it, "owner", "group-${it.id}", "r1", 2, false) } }
        assertEquals(games.map { it.key }.toSet(), store.games().map { it.game.key }.toSet())
        assertEquals(5, store.jobs())
        assertTrue(store.games().all { it.selected == null })
    }

    @Test fun failedTransactionLeavesNoPartialRows() {
        runCatching {
            store.transaction {
                store.stage(game(LibraryStore.GOG, "1"), "owner", "g1", "r1", 2, false)
                error("abort")
            }
        }
        assertTrue(store.games().isEmpty())
        assertEquals(0, store.jobs())
    }

    @Test fun retireInactiveRemovesOtherAccountsAndMissingLocalGamesOnly() {
        store.transaction {
            store.stage(game(LibraryStore.GOG, "1"), "current", "g1", "r1", 2, false)
            store.stage(game(LibraryStore.GOG, "2"), "previous", "g2", "r1", 2, false)
            store.stage(game(LibraryStore.EPIC, "3"), "epic-owner", "g3", "r1", 2, false)
            store.stage(game(LibraryStore.STEAM, "4"), "local", "g4", "r1", 2, false)
            store.stage(game(LibraryStore.STEAM, "5"), "local", "g5", "r1", 2, false)
        }
        store.retireInactive(mapOf("GOG" to "current", "EPIC" to "epic-owner"), setOf("STEAM:4"))
        assertEquals(setOf("GOG:1", "EPIC:3", "STEAM:4"), store.games().map { it.game.key }.toSet())
        assertEquals(3, store.jobs())
    }

    @Test fun publishSelectsGroupAndClearsJobs() {
        val first = game(LibraryStore.GOG, "1", "Shared Title")
        val second = game(LibraryStore.EPIC, "9", "Shared Title")
        store.transaction {
            store.stage(first, "gog", "shared", "r1", 0, false)
            store.stage(second, "epic", "shared", "r1", 0, false)
        }
        assertEquals(1, store.jobs())
        val blob = writeImage(1)
        assertTrue(store.publish(first.key, "gog", "r1", blob, "https://cdn2.steamgriddb.com/grid/a.jpg"))
        val rows = store.games()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.selected?.blob == blob })
        assertEquals(0, store.jobs())
    }

    @Test fun publishRejectsStaleFingerprint() {
        val game = game(LibraryStore.GOG, "1")
        store.stage(game, "gog", "g1", "r2", 0, false)
        val blob = writeImage(2)
        assertFalse(store.publish(game.key, "gog", "r1", blob, "source"))
        assertNull(store.games().single().selected)
    }

    @Test fun restagingSameFingerprintKeepsPublication() {
        val game = game(LibraryStore.GOG, "1")
        store.stage(game, "gog", "g1", "r1", 0, false)
        val blob = writeImage(3)
        assertTrue(store.publish(game.key, "gog", "r1", blob, "source"))
        store.stage(game.copy(name = "Renamed"), "gog", "g1", "r1", 2, false)
        val row = store.games().single()
        assertEquals(blob, row.selected?.blob)
        assertEquals("Renamed", row.game.name)
        assertEquals(0, store.jobs())
    }

    @Test fun parsedCandidateCacheFollowsCandidateChanges() {
        val game = game(LibraryStore.GOG, "1", "Before")
        store.stage(game, "gog", "g1", "r1", 2, false)
        assertEquals("Before", store.candidate(game.key)?.name)
        store.stage(game.copy(name = "After"), "gog", "g1", "r2", 2, false)
        assertEquals("After", store.candidate(game.key)?.name)
        assertEquals("After", store.games().single().game.name)
    }

    /** Distinct dimensions give distinct content-addressed blobs under Robolectric's bitmap shadows. */
    private fun writeImage(seed: Int): String {
        val image = android.graphics.Bitmap.createBitmap(32 + seed, 48 + seed, android.graphics.Bitmap.Config.ARGB_8888)
        val output = java.io.ByteArrayOutputStream()
        check(image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output))
        return store.write(output.toByteArray())
    }
}
