package com.gamefibre.assets

import android.content.Context
import android.graphics.Bitmap
import com.gamefibre.CustomArtwork
import com.gamefibre.LibraryEntry
import com.gamefibre.LibraryStore
import com.gamefibre.testPreferences
import kotlinx.coroutines.*
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
import java.io.File

/** Saved covers publish offline, in one staging pass, and only their own group is touched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssetRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var store: AssetStore
    private lateinit var custom: CustomArtwork
    private lateinit var repository: AssetRepository
    private val job = SupervisorJob()
    private val databaseName = "asset-repository-test.sqlite"
    private var network: (() -> Boolean)? = null

    @Before fun open() {
        context = RuntimeEnvironment.getApplication()
        store = AssetStore(context, databaseName, temporary.newFolder())
        custom = CustomArtwork(testPreferences(), temporary.newFolder())
        repository = AssetRepository(context, store, custom, CoroutineScope(job + Dispatchers.IO))
        network = repository.transport.networkAvailable
        repository.transport.networkAvailable = { false }
    }
    @After fun close() {
        runBlocking { job.cancelAndJoin() }
        network?.let { repository.transport.networkAvailable = it }
        store.close(); context.deleteDatabase(databaseName)
    }

    private fun cover(seed: Int): File {
        val image = Bitmap.createBitmap(60 + seed, 90 + seed, Bitmap.Config.ARGB_8888)
        val file = File(temporary.root, "choice-$seed.jpg")
        file.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        return file
    }

    @Test fun savedCoverPublishesOfflineForEveryCopyAndReplacementChangesHandle() = runBlocking {
        val games = listOf(
            LibraryEntry(LibraryStore.STEAM, "1", "Shared"),
            LibraryEntry(LibraryStore.GOG, "1", "Shared"),
            LibraryEntry(LibraryStore.EPIC, "shared", "Shared"),
            LibraryEntry(LibraryStore.XBOX, "BNBWFH89FCF1", "Shared")
        )
        val keys = games.map { it.key }
        val requests = repository.transport.requests.get()
        withTimeout(10_000) { repository.synchronize(games, emptyMap(), keys.toSet()).join() }
        assertTrue("games without artwork wait in preparation", store.jobs() > 0)
        assertNull(repository.published(games.first()))

        var firstBlob: String? = null
        for (index in 0..1) {
            val address = "https://cdn2.steamgriddb.com/grid/test-$index.jpg"
            custom.apply(keys, address, cover(index))
            withTimeout(10_000) { repository.refreshArtwork(games, emptyMap()).join() }
            games.forEach { game ->
                val published = assertNotNull(repository.published(game))
                assertEquals(address, published.asset.source)
                assertNotNull("decoded before exposure", repository.peek(game))
                if (index == 0) firstBlob = published.asset.blob else assertNotEquals(firstBlob, published.asset.blob)
            }
            assertEquals("saved choice needs no background job", 0, store.jobs())
        }
        assertEquals("no network for a local publication", requests, repository.transport.requests.get())
    }

    @Test fun splitGroupsKeepArtworkAndPublishIndependentCoverChoices() = runBlocking {
        val gog = LibraryEntry(LibraryStore.GOG, "1719198803", "Kingdom Come: Deliverance")
        val epic = LibraryEntry(LibraryStore.EPIC, "Eel", "Kingdom Come Deliverance")
        val games = listOf(gog, epic)
        val original = "https://cdn2.steamgriddb.com/grid/original.jpg"
        custom.apply(games.map { it.key }, original, cover(0))
        withTimeout(10_000) { repository.synchronize(games, emptyMap(), emptySet()).join() }
        val first = assertNotNull(repository.published(gog))
        assertEquals(first.asset.group, repository.published(epic)?.asset?.group)
        custom.reset(games.map { it.key })

        // Two products on another store make the previously merged title ambiguous.
        val ambiguous = listOf("BNBWFH89FCF1", "9P3T7HVKM6D0").map { LibraryEntry(LibraryStore.XBOX, it, gog.name) }
        withTimeout(10_000) { repository.synchronize(games + ambiguous, emptyMap(), emptySet()).join() }
        assertNotEquals(repository.published(gog)?.asset?.group, repository.published(epic)?.asset?.group)
        games.forEach { assertEquals("retain usable artwork on split", first.asset.blob, repository.published(it)?.asset?.blob) }

        val replacement = "https://cdn2.steamgriddb.com/grid/replacement.jpg"
        custom.apply(listOf(gog.key), replacement, cover(1))
        withTimeout(10_000) { repository.refreshArtwork(listOf(gog), emptyMap()).join() }
        assertEquals(replacement, repository.published(gog)?.asset?.source)
        assertNotNull("replacement decoded before display", repository.peek(gog))
        assertEquals(original, repository.published(epic)?.asset?.source)
        withTimeout(10_000) { repository.synchronize(games + ambiguous, emptyMap(), emptySet()).join() }
        assertEquals(replacement, repository.published(gog)?.asset?.source)
    }

    @Test fun refreshArtworkLeavesUnrelatedGamesUntouched() = runBlocking {
        val target = LibraryEntry(LibraryStore.GOG, "1", "Target")
        val other = LibraryEntry(LibraryStore.GOG, "2", "Other")
        withTimeout(10_000) { repository.synchronize(listOf(target, other), emptyMap(), emptySet()).join() }
        val otherBefore = store.row(other.key)!!
        custom.apply(listOf(target.key), "https://cdn2.steamgriddb.com/grid/t.jpg", cover(5))
        withTimeout(10_000) { repository.refreshArtwork(listOf(target), emptyMap()).join() }
        val otherAfter = store.row(other.key)!!
        assertEquals(otherBefore.fingerprint, otherAfter.fingerprint)
        assertEquals(otherBefore.group, otherAfter.group)
        assertNull(otherAfter.selected)
        assertNotNull(repository.published(target))
        assertEquals(1, store.jobs())
    }

    @Test fun resetKeepsPreviousCoverVisibleUntilReplacementIsPrepared() = runBlocking {
        val game = LibraryEntry(LibraryStore.GOG, "1", "Reset me")
        custom.apply(listOf(game.key), "https://cdn2.steamgriddb.com/grid/r.jpg", cover(7))
        withTimeout(10_000) { repository.synchronize(listOf(game), emptyMap(), emptySet()).join() }
        val chosen = assertNotNull(repository.published(game))
        custom.reset(listOf(game.key))
        withTimeout(10_000) { repository.refreshArtwork(listOf(game), emptyMap()).join() }
        assertEquals("old cover stays until a real replacement exists", chosen.asset.blob, repository.published(game)?.asset?.blob)
        assertEquals("replacement is queued", 1, store.jobs())
    }

    @Test fun synchronizeRetiresGamesFromAnotherAccount() = runBlocking {
        val previous = LibraryEntry(LibraryStore.GOG, "1", "Old account")
        withTimeout(10_000) { repository.synchronize(listOf(previous), mapOf(LibraryStore.GOG to "old"), emptySet()).join() }
        val current = LibraryEntry(LibraryStore.GOG, "2", "New account")
        withTimeout(10_000) { repository.synchronize(listOf(current), mapOf(LibraryStore.GOG to "new"), emptySet()).join() }
        assertEquals(setOf(current.key), store.games().map { it.game.key }.toSet())
    }

    @Test fun preparedWishlistGamesNeverBecomeRememberedOwnership() = runBlocking {
        val owned = LibraryEntry(LibraryStore.XBOX, "9NBLGGH4R315", "Owned Xbox game", remote = com.gamefibre.stores.StoreGame("9NBLGGH4R315", "Owned Xbox game"))
        val wish = LibraryEntry(LibraryStore.XBOX, "9NBLGGH4R316", "Wishlist Xbox game", remote = com.gamefibre.stores.StoreGame("9NBLGGH4R316", "Wishlist Xbox game"))
        val owners = mapOf(LibraryStore.XBOX to "account")
        val games = listOf(owned, wish)
        games.forEachIndexed { index, game ->
            custom.apply(listOf(game.key), "https://cdn2.steamgriddb.com/grid/ownership-$index.jpg", cover(index))
        }
        withTimeout(10_000) {
            repository.synchronize(games, owners, emptySet(), owned = setOf(owned.key)).join()
        }
        assertNotNull(repository.published(wish))
        assertEquals(listOf(owned), repository.remembered(owners))
        // Artwork changes and refreshes must preserve both the cover and the ownership boundary.
        withTimeout(10_000) { repository.refreshArtwork(listOf(wish), owners).join() }
        withTimeout(10_000) {
            repository.synchronize(games, owners, emptySet(), refresh = true, owned = setOf(owned.key)).join()
        }
        assertNotNull(repository.published(wish))
        assertEquals(listOf(owned), repository.remembered(owners))
        withTimeout(10_000) {
            repository.synchronize(listOf(owned), owners, emptySet(), owned = setOf(owned.key)).join()
        }
        assertEquals(listOf(owned), repository.remembered(owners))
    }

    private fun <T : Any> assertNotNull(value: T?): T { org.junit.Assert.assertNotNull(value); return value!! }
}
