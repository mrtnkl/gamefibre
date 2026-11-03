package com.gamefibre.steam

import `in`.dragonbra.javasteam.depotdownloader.DepotConfigStore
import `in`.dragonbra.javasteam.depotdownloader.Util
import `in`.dragonbra.javasteam.types.ChunkData
import `in`.dragonbra.javasteam.util.Adler32
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadValidationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun resumedDepotsRecheckPreallocatedChunksAndKeepValidData() {
        val stage = temporary.newFolder()
        val metadata = File(stage, ".DepotDownloader").apply { mkdirs() }
        val receipt = File(metadata, "depot.config").apply {
            writeText("""{"installedManifestIDs":{"1":123,"2":456}}""")
        }
        val manifest = File(metadata, "1_123.manifest").apply { writeText("cached manifest") }
        val good = byteArrayOf(0x4d, 0x5a, 1, 2)
        val missing = byteArrayOf(3, 4, 5, 6)
        val file = File(stage, "game.exe").apply { writeBytes(good + ByteArray(4)) }

        requireDownloadValidation(stage)

        // Exercise the dependency's actual receipt reader and checksum validator.
        DepotConfigStore.loadFromFile(receipt.path.toPath())
        assertTrue(DepotConfigStore.getInstance().installedManifestIDs.isEmpty())
        assertEquals("cached manifest", manifest.readText())
        assertArrayEquals(good + ByteArray(4), file.readBytes())
        val chunks = listOf(
            ChunkData(byteArrayOf(1), Adler32.calculate(good), 0L, 4, 4),
            ChunkData(byteArrayOf(2), Adler32.calculate(missing), 4L, 4, 4),
        )
        FileSystem.SYSTEM.openReadOnly(file.path.toPath()).use {
            assertEquals(listOf(chunks[1]), Util.validateSteam3FileChecksums(it, chunks))
        }
        file.writeBytes(good + missing)
        FileSystem.SYSTEM.openReadOnly(file.path.toPath()).use {
            assertTrue(Util.validateSteam3FileChecksums(it, chunks).isEmpty())
        }
    }

    @Test fun freshDownloadsDoNotNeedAReceipt() {
        val stage = temporary.newFolder()
        requireDownloadValidation(stage)
        assertTrue(stage.listFiles()!!.isEmpty())
    }
}
