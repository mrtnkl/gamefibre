package com.gamefibre

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class StorageSafetyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cacheBudgetEvictsOldFilesAndRetainsTheNewDownload() {
        val directory = temporary.newFolder("images")
        val old = File(directory, "old").apply { writeBytes(ByteArray(8)); setLastModified(1000) }
        val recent = File(directory, "recent").apply { writeBytes(ByteArray(8)); setLastModified(2000) }
        val saved = File(directory, "saved").apply { writeBytes(ByteArray(8)); setLastModified(3000) }
        DiskCacheBudget.trim(directory, 16, keep=saved)
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(saved.exists())
        assertEquals(16, PrivateFiles.size(directory))
    }

    @Test fun cacheBudgetBoundsZeroByteMissingImageMarkers() {
        val directory = temporary.newFolder("markers")
        repeat(8) { File(directory, "$it-missing").createNewFile() }
        DiskCacheBudget.trim(directory, 100, maxEntries=3)
        assertEquals(3, directory.listFiles()!!.size)
    }

    @Test fun browsingAnOlderCoverRetainsItAheadOfUnusedArtwork() {
        val directory = temporary.newFolder("browsed")
        val browsed = File(directory, "browsed").apply { writeBytes(ByteArray(8)); setLastModified(1000) }
        val unused = File(directory, "unused").apply { writeBytes(ByteArray(8)); setLastModified(2000) }
        DiskCacheBudget.touch(browsed)
        DiskCacheBudget.trim(directory, 8)
        assertTrue(browsed.exists())
        assertFalse(unused.exists())
    }

    @Test fun cacheEvictionDoesNotInterfereWithAnAtomicWrite() {
        val directory = temporary.newFolder("atomic")
        File(directory, "cover").writeText("old")
        File(directory, "cover.new").writeText("being written")
        File(directory, "another").writeText("evict")
        DiskCacheBudget.trim(directory, 0)
        assertTrue(File(directory, "cover").exists())
        assertTrue(File(directory, "cover.new").exists())
        assertFalse(File(directory, "another").exists())
    }

    @Test fun stagingCleanupDoesNotFollowLinksOrDeleteExportedFiles() {
        val staging = temporary.newFolder("staging")
        val exported = temporary.newFolder("Downloads")
        File(exported, "keep.zip").writeText("exported game")
        File(staging, "game.bin").writeText("game")
        Files.createSymbolicLink(File(staging, "external").toPath(), exported.toPath())
        Files.createSymbolicLink(File(staging, "cycle").toPath(), staging.toPath())
        assertEquals(4, PrivateFiles.size(staging))
        PrivateFiles.deleteTree(staging)
        assertFalse(staging.exists())
        assertEquals("exported game", File(exported, "keep.zip").readText())
    }

    @Test fun xboxSignOutKeepsOtherStoresAndSharedCollectionChoices() {
        val original = mapOf("members:Favorites" to setOf("XBOX:1", "STEAM:620"),
            "progress:XBOX:1" to "playing", "progress:STEAM:620" to "playing", "theme" to "Dark")
        val changes = collectionChangesAfterSignOut(original, "XBOX")
        assertEquals(setOf("STEAM:620"), changes["members:Favorites"])
        assertTrue(changes.containsKey("progress:XBOX:1"))
        assertNull(changes["progress:XBOX:1"])
        assertFalse(changes.containsKey("progress:STEAM:620"))
        assertFalse(changes.containsKey("theme"))
    }

    @Test fun signOutRemovesOnlyTheSelectedStoresListsAndHistory() {
        val original: Map<String, Any> = mapOf(
            "members:Favorites" to setOf("STEAM:10", "GOG:20", "EPIC:30"),
            "members:Hidden" to setOf("STEAM:10", "GOG:20"),
            "members:Completed" to setOf("GOG:20"),
            "download-keys" to setOf("STEAM:10", "GOG:20"),
            "progress:GOG:20" to "playing",
            "progress:STEAM:10" to "finished",
            "download:STEAM:10" to "steam history",
            "download:GOG:20" to "gog history",
            "theme" to "Dark",
            "sort" to "Name",
        )
        val remaining = original.toMutableMap()
        collectionChangesAfterSignOut(original, "GOG").forEach { (key, value) ->
            if (value == null) remaining.remove(key) else remaining[key] = value
        }
        assertEquals(setOf("STEAM:10", "EPIC:30"), remaining["members:Favorites"])
        assertEquals(setOf("STEAM:10"), remaining["members:Hidden"])
        assertEquals(emptySet<String>(), remaining["members:Completed"])
        assertEquals(setOf("STEAM:10"), remaining["download-keys"])
        assertFalse(remaining.containsKey("download:GOG:20"))
        assertFalse(remaining.containsKey("progress:GOG:20"))
        assertEquals("finished", remaining["progress:STEAM:10"])
        assertEquals("steam history", remaining["download:STEAM:10"])
        assertEquals("Dark", remaining["theme"])
        assertEquals("Name", remaining["sort"])
        assertTrue(collectionChangesAfterSignOut(remaining, "GOG").isEmpty())
    }
}
