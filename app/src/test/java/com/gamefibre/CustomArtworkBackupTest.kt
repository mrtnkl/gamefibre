package com.gamefibre

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CustomArtworkBackupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun missingCachedImagesDoNotPreventUrlBackupAndRestore() {
        val url = "https://cdn2.steamgriddb.com/grid/selected.jpg"
        val source = CustomArtwork(testPreferences(mutableMapOf("STEAM:620" to url)), temporary.newFolder())
        assertNull(source.file("STEAM:620"))
        val covers = source.backup()
        assertEquals(mapOf("STEAM:620" to url), covers)
        val targetDirectory = temporary.newFolder()
        val destination = CustomArtwork(testPreferences(), targetDirectory)
        var collectionSaved = false
        destination.mergeBackup(covers) { collectionSaved = true }
        assertTrue(collectionSaved)
        assertEquals(url, destination.address("STEAM:620"))
        assertEquals(covers, destination.backup())
        assertTrue(targetDirectory.listFiles().orEmpty().isEmpty())
    }
}
