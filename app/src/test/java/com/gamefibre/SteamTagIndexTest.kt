package com.gamefibre

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream

class SteamTagIndexTest {
    private val asset = File("src/main/assets/steam-tags-v1.bin.xz")
    private val index by lazy { asset.inputStream().use(SteamTagIndex::read) }

    @Test fun bundledTagsContainCommunityLabelsInVoteOrder() {
        assertEquals(listOf("Platformer", "Puzzle", "First-Person", "Dark Humor", "Story Rich"), index.tags(620).take(5))
        assertTrue(index.tags(1091500).containsAll(listOf("RPG", "First-Person", "Cyberpunk")))
        assertEquals("FPS", index.tags(730).first())
        assertFalse(index.tags(620).contains("Steam Cloud"))
        assertTrue(index.tags(0).isEmpty())
        assertTrue(index.tags(-1).isEmpty())
        assertTrue(index.tags(Int.MAX_VALUE).isEmpty())
        assertTrue(index.tags(2539430).isEmpty()) // Source has no community tags for this game.
    }

    @Test fun sameTagsResolveThroughAllStoreIdentitiesAndWishlistEntries() {
        val catalog = File("src/main/assets/steam-titles-v2.bin.xz").inputStream().use(SteamTitleIndex::read)
        val matching = BeatIndex(emptyMap(), emptyMap(), catalog)
        LibraryStore.entries.forEach { store ->
            val game = LibraryEntry(store, if (store == LibraryStore.STEAM) "1091500" else "store-specific-id", "Cyberpunk 2077")
            val appId = matching.steamId(game)?.toIntOrNull()
            assertEquals(1091500, appId)
            assertEquals(index.tags(1091500), index.tags(appId!!))
        }
        // The pinned title catalog has two Portal 2 IDs; only known ownership resolves it.
        val portal = LibraryEntry(LibraryStore.GOG, "portal", "Portal 2")
        assertNull(matching.steamId(portal))
        matching.updateKnownSteamGames(listOf(LibraryEntry(LibraryStore.STEAM, "620", "Portal 2")))
        assertEquals(index.tags(620), index.tags(matching.steamId(portal)!!.toInt()))
        assertNull(matching.steamId(LibraryEntry(LibraryStore.GOG, "unknown", "Definitely not a matching Steam game")))
    }

    @Test fun packagedAssetMatchesProvenanceAndEveryBlockCanBeSearched() {
        val manifest = JSONObject(File("src/main/assets/steam-tags-v1.json").readText())
        assertEquals(manifest.getLong("compressed_index_bytes"), asset.length())
        assertEquals(manifest.getString("index_sha256"), MessageDigest.getInstance("SHA-256")
            .digest(asset.readBytes()).joinToString("") { "%02x".format(it) })
        val data = XZInputStream(asset.inputStream()).use { it.readBytes() }
        val input = java.io.DataInputStream(data.inputStream())
        assertEquals(0x53545447, input.readInt()); assertEquals(1, input.readInt())
        val nameCount = input.readInt(); val count = input.readInt(); val block = input.readInt(); val length = input.readInt()
        fun integer(): Int {
            var result = 0; var shift = 0
            while (true) {
                val byte = input.readUnsignedByte()
                result = result or ((byte and 127) shl shift)
                if (byte < 128) return result
                shift += 7
            }
        }
        val names = List(nameCount) { ByteArray(integer()).also(input::readFully).toString(Charsets.UTF_8) }
        repeat((count + block - 1) / block) { input.readInt() }
        assertEquals(length, input.available())
        var appId = 0
        repeat(count) { row ->
            if (row % block == 0) appId = 0
            appId += integer()
            val tags = List(integer()) { names[integer()] }
            assertEquals("AppID $appId", tags, index.tags(appId))
        }
        assertEquals(-1, input.read())
        assertEquals(83385, count)
        assertEquals(452, nameCount)
    }

    private fun compressed(raw: ByteArray) = ByteArrayOutputStream().also { output ->
        XZOutputStream(output, LZMA2Options(6)).use { it.write(raw) }
    }.toByteArray()

    @Test fun rejectsTruncatedPayloadAndUnsupportedFormat() {
        val raw = XZInputStream(asset.inputStream()).use { it.readBytes() }
        listOf(raw.copyOf(raw.size - 1), raw.copyOf().also { it[it.lastIndex] = 0xff.toByte() },
            raw.copyOf().also { it[7] = 2 }).forEach { invalid ->
            assertThrows(Exception::class.java) { SteamTagIndex.read(compressed(invalid).inputStream()) }
        }
    }
}
