package com.gamefibre

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CollectionArchiveTest {
    private fun manifest(): JSONObject {
        val collection = CollectionData(testPreferences())
        collection.setMembership("Favorites", setOf("STEAM:1"), true)
        collection.setProgress(setOf("STEAM:1"), GameProgress.PLAYING)
        return CollectionSnapshot.create(collection, listOf(LibraryEntry(LibraryStore.STEAM, "1", "A game")), emptyMap())
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()

    @Test fun wishlistRecordsSurviveZipAndMergeWithoutDuplicates() {
        val data = manifest()
        val game = LibraryEntry(LibraryStore.STEAM, "620", "Portal 2", remote =
            com.gamefibre.stores.StoreGame("620", "Portal 2", details =
                GameDetails(platforms = listOf("PC"), storeLinks = mapOf("Steam" to "https://store.steampowered.com/app/620/"))))
        data.getJSONArray("wishlist").put(WishlistItems.json(game))
        val output = ByteArrayOutputStream()
        CollectionArchive.write(output, CollectionArchive.Contents(data))
        val restored = CollectionArchive.read(ByteArrayInputStream(output.toByteArray()))
        val collection = CollectionData(testPreferences())
        collection.mergeBackup(restored.manifest)
        collection.mergeBackup(restored.manifest)
        assertEquals(listOf(game), collection.wishlistGames())
        assertEquals(listOf("Wishlist", "Hidden"), collection.lists.takeLast(2))
        assertTrue(collection.downloaded().isEmpty())
    }

    @Test fun invalidWishlistBackupCannotChangeExistingCollectionData() {
        val data = manifest().put("wishlist", JSONArray().put(JSONObject().put("store", "STEAM").put("id", "../bad")
            .put("name", "Bad").put("artwork", "").put("details", JSONObject())))
        val collection = CollectionData(testPreferences())
        val before = collection.preferences.all
        assertThrows(IllegalArgumentException::class.java) { collection.mergeBackup(data) }
        assertEquals(before, collection.preferences.all)
    }

    @Test fun xboxGameIdentitiesAndAutomaticListSurviveBackupImport() {
        val data = manifest()

        data.getJSONObject("progress").put("XBOX:9NBLGGH4R315", "playing")
        data.getJSONArray("games").put(LibraryRecords.json(LibraryEntry(LibraryStore.XBOX, "9NBLGGH4R315", "Xbox game")))
        CollectionArchive.validate(data)
        val collection = CollectionData(testPreferences())
        collection.mergeBackup(data)
        assertTrue(collection.isAutomaticList("Xbox"))
        assertEquals(GameProgress.PLAYING, collection.progress(listOf("XBOX:9NBLGGH4R315")))
    }

    @Test fun importedOrderKeepsAllGamesFirstAndPreservesUnrelatedLists() {
        val collection = CollectionData(testPreferences())
        collection.createList("Existing", emptySet())
        val data = manifest()
        data.getJSONArray("lists").put(JSONObject().put("name", "New list").put("automatic", false).put("custom", true).put("games", JSONArray()))
        data.getJSONObject("settings").put("listOrder", JSONArray(listOf("New list", "Favorites", "All games", "Downloads")))
        collection.mergeBackup(data)
        assertEquals(listOf("All games", "New list", "Favorites", "Downloads"), collection.lists.take(4))
        assertTrue("Existing" in collection.lists)
        val expected = collection.lists
        collection.mergeBackup(data)
        assertEquals(expected, collection.lists)
    }

    @Test fun zipRoundTripPreservesCoverUrlsWithoutAssetFiles() {
        val data = manifest()
        val address = "https://cdn2.steamgriddb.com/grid/example.jpg"
        data.getJSONArray("artwork").put(JSONObject().put("game", "STEAM:1").put("url", address))
        val output = ByteArrayOutputStream()
        CollectionArchive.write(output, CollectionArchive.Contents(data))
        java.util.zip.ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            assertEquals("collection.json", zip.nextEntry.name)
            assertNull(zip.nextEntry)
        }
        val restored = CollectionArchive.read(ByteArrayInputStream(output.toByteArray()))
        assertEquals(data.toString(), restored.manifest.toString())
    }

    @Test fun importMergesListsAndProgressWithoutTouchingCredentialsOrDownloads() {
        val collection = CollectionData(testPreferences(mutableMapOf("members:Favorites" to setOf("GOG:2"),
            "custom-lists" to "[\"Existing\"]", "members:Existing" to setOf("GOG:2"),
            "progress:GOG:2" to "finished", "download:STEAM:1" to "keep", "private-session" to "keep")))
        val data = manifest()
        data.getJSONArray("lists").put(JSONObject().put("name", "New list").put("automatic", false).put("custom", true).put("games", JSONArray(listOf("STEAM:1"))))
        collection.mergeBackup(data)
        collection.mergeBackup(data)
        assertEquals(setOf("GOG:2", "STEAM:1"), collection.members("Favorites"))
        assertEquals(listOf("Existing", "New list"), collection.customLists)
        assertEquals(GameProgress.PLAYING, collection.progress(listOf("STEAM:1")))
        assertEquals(GameProgress.FINISHED, collection.progress(listOf("GOG:2")))
        assertEquals("keep", collection.preferences.getString("download:STEAM:1", null))
        assertEquals("keep", collection.preferences.getString("private-session", null))
    }

    @Test fun automaticStoreListsRemainAutomaticAfterImport() {
        val data = manifest()

        val collection = CollectionData(testPreferences())
        collection.mergeBackup(data)
        assertTrue(collection.isAutomaticList("Steam"))
        assertFalse("Steam" in collection.editableLists)
    }

    @Test fun recreatedBuiltInNamesRestoreAsOneManualList() {
        val data = manifest()
        data.put("lists", JSONArray().put(JSONObject().put("name", "Steam").put("automatic", false)
            .put("custom", true).put("games", JSONArray(listOf("GOG:2")))))
        data.getJSONArray("removedBuiltIns").put("Steam")
        val collection = CollectionData(testPreferences())
        collection.mergeBackup(data)
        collection.mergeBackup(data)
        assertEquals(1, collection.lists.count { it == "Steam" })
        assertFalse(collection.isAutomaticList("Steam"))
        assertTrue("Steam" in collection.editableLists)
        assertEquals(setOf("GOG:2"), collection.members("Steam"))
    }

    @Test fun hiddenListMembershipRestoresThroughOrdinaryListsEvenWhenNotShown() {
        val collection = CollectionData(testPreferences())
        val data = manifest()
        data.getJSONArray("lists").getJSONObject(data.getJSONArray("lists").length() - 1).put("games", JSONArray(listOf("STEAM:1")))
        collection.mergeBackup(data)
        collection.mergeBackup(data)
        assertEquals(setOf("STEAM:1"), collection.members("Hidden"))
        assertFalse("Hidden" in collection.customLists)
        assertFalse("Hidden" in collection.visibleLists { true })
    }

    @Test fun backupsRequireCurrentDisplaySettingsAndListIdentity() {
        val collection = CollectionData(testPreferences())
        val before = collection.preferences.all.toMap()
        for (field in listOf("listOrder", "fitCoverArt", "showHiddenGames", "showGamePass", "theme", "selectedList", "randomSeed", "youtubeQueries")) {
            val data = manifest()
            data.getJSONObject("settings").remove(field)
            assertThrows(org.json.JSONException::class.java) { collection.mergeBackup(data) }
            assertEquals(before, collection.preferences.all)
        }
        for (field in listOf("custom", "automatic")) {
            val data = manifest()
            data.getJSONArray("lists").getJSONObject(0).remove(field)
            assertThrows(org.json.JSONException::class.java) { collection.mergeBackup(data) }
            assertEquals(before, collection.preferences.all)
        }
    }

    @Test fun malformedBackupsCannotMutateCollection() {
        val collection = CollectionData(testPreferences())
        val before = collection.preferences.all.toMap()
        val invalid = manifest().put("progress", JSONObject().put("STEAM:1", "invalid"))
        assertThrows(IllegalArgumentException::class.java) { collection.mergeBackup(invalid) }
        assertEquals(before, collection.preferences.all)
        assertThrows(IllegalArgumentException::class.java) { CollectionArchive.validate(manifest().put("version", 99)) }
    }

    @Test fun traversalUnknownFilesAndAssetFilesAreRejected() {
        val json = manifest().toString().toByteArray()
        for (path in listOf("../outside", "/absolute", "artwork/../../outside", "session.json", "artwork/${"a".repeat(64)}.jpg")) {
            val bytes = zip("collection.json" to json, path to byteArrayOf(1))
            assertThrows(IllegalArgumentException::class.java) { CollectionArchive.read(ByteArrayInputStream(bytes)) }
        }
    }

    @Test fun oversizedCompressedMetadataIsRejected() {
        val bytes = zip("collection.json" to ByteArray(CollectionArchive.MAX_JSON + 1))
        assertThrows(IllegalArgumentException::class.java) { CollectionArchive.read(ByteArrayInputStream(bytes)) }
    }

    @Test fun csvQuotesCommasQuotesAndNewlinesAndEscapesSpreadsheetFormulas() {
        assertEquals("\"A, B\",\"a\"\"b\",\"line1\nline2\",\"'=SUM(A1)\",\"'  +cmd\"\r\n",
            csvRow(listOf("A, B", "a\"b", "line1\nline2", "=SUM(A1)", "  +cmd")))
    }
}
