package com.gamefibre

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

class SteamTitleIndexTest {
    private val asset = File("src/main/assets/steam-titles-v2.bin.xz")
    private fun bundled() = asset.inputStream().use(SteamTitleIndex::read)
    private fun hollowKnightMain() = File("src/main/assets/hltb-min.csv").readLines()
        .first { it.startsWith("367520,") }.split(',')[1].toLong()

    private fun fixture(entries: List<Pair<String, Int>>, blockSize: Int = 2): ByteArray {
        val data = ByteArrayOutputStream()
        fun integer(value: Int) {
            var remaining = value
            while (remaining >= 128) { data.write((remaining and 127) or 128); remaining = remaining ushr 7 }
            data.write(remaining)
        }
        val offsets = mutableListOf<Int>()
        var previous = byteArrayOf()
        entries.sortedBy { it.first }.forEachIndexed { number, (name, id) ->
            if (number % blockSize == 0) { offsets += data.size(); previous = byteArrayOf() }
            val next = name.toByteArray(Charsets.UTF_8)
            var shared = 0
            while (shared < minOf(previous.size, next.size) && previous[shared] == next[shared]) shared++
            integer(shared); integer(next.size - shared); data.write(next, shared, next.size - shared); integer(id)
            previous = next
        }
        val binary = ByteArrayOutputStream()
        DataOutputStream(binary).use {
            it.writeInt(0x53544958); it.writeInt(1); it.writeInt(entries.size)
            it.writeInt(blockSize); it.writeInt(data.size())
            offsets.forEach(it::writeInt); it.write(data.toByteArray())
        }
        return compress(binary.toByteArray())
    }

    private fun compress(bytes: ByteArray) = ByteArrayOutputStream().also { result ->
        XZOutputStream(result, LZMA2Options(6)).use { it.write(bytes) }
    }.toByteArray()

    private fun catalog(entries: List<Pair<String, Int>>): SteamTitleIndex {
        fun unique(key: (String) -> String) = entries.groupBy { key(it.first) }.mapNotNull { (name, matches) ->
            val ids = matches.map { it.second }.toSet()
            if (name.isEmpty() || ids.size != 1) null else name to ids.single()
        }
        val exact = XZInputStream(fixture(unique(BeatIndex::normalize)).inputStream()).use { it.readBytes() }
        val relaxed = XZInputStream(fixture(unique(SteamTitleMatcher::relaxedKey)).inputStream()).use { it.readBytes() }
        val binary = ByteArrayOutputStream()
        DataOutputStream(binary).use {
            it.writeInt(0x53544958); it.writeInt(2)
            it.write(exact, 8, exact.size - 8); it.write(relaxed, 8, relaxed.size - 8)
        }
        return SteamTitleIndex.read(compress(binary.toByteArray()).inputStream())
    }

    @Test fun matchesCrossStorePunctuationArticlesApostrophesAndAmpersands() {
        val index = catalog(listOf("The Witcher 3: Wild Hunt™" to 292030, "Baldur's Gate 3" to 1086940,
            "F.E.A.R." to 21090, "Command & Conquer" to 1213210, "Café: Adventure" to 99, "幽灵 行动" to 100))
        assertEquals(292030, index.steamId("Witcher 3 – Wild Hunt ©"))
        assertEquals(292030, index.steamId("Witcher 3: Wild Hunt, The"))
        assertEquals(1086940, index.steamId("Baldurs Gate 3"))
        assertEquals(1086940, index.steamId("Baldur’s Gate 3"))
        assertEquals(21090, index.steamId("FEAR"))
        assertEquals(1213210, index.steamId("Command and Conquer"))
        assertEquals(99, index.steamId("CAFE-ADVENTURE®"))
        assertEquals(100, index.steamId("幽灵行动"))
        assertNull(index.steamId("The Witcher 2 Wild Hunt"))
        assertNull(index.steamId("Baldurs Gate 3 Deluxe Edition"))
        assertNull(index.steamId("FEAR Remastered"))
        assertNull(index.steamId("幽灵"))
    }

    @Test fun exactTitlesWinAndAmbiguousRelaxedKeysStayUnmatched() {
        val index = catalog(listOf("The Guild" to 10, "Guild" to 20, "Same Title" to 30, "Same Title" to 40))
        assertEquals(10, index.steamId("The Guild"))
        assertEquals(20, index.steamId("Guild"))
        assertNull(index.steamId("G.U.I.L.D."))
        assertNull(index.steamId("Guild, The"))
        assertNull(index.steamId("Same-Title"))
        assertNull(index.steamId("The SameTitle"))
    }

    @Test fun knownSteamIdsTakePriorityForArtworkMetadataAndCompletionTimes() {
        val index = catalog(listOf("Known Game" to 99))
        val beat = File("src/main/assets/hltb-index-v1.bin").inputStream().use { BeatIndex.read(it, index) }
        val gog = LibraryEntry(LibraryStore.GOG, "gog-99", "Known Game")
        val epic = LibraryEntry(LibraryStore.EPIC, "epic-99", "Known Game")
        val known = LibraryEntry(LibraryStore.STEAM, "367520", "Known Game")
        assertEquals("99", beat.steamId(gog))
        assertEquals("367520", beat.steamId(gog, listOf(known)))
        beat.updateKnownSteamGames(listOf(known))
        assertEquals("367520", beat.steamId(epic))
        assertEquals(hollowKnightMain(), beat.find(gog)?.main)
        assertEquals("10", beat.steamId(LibraryEntry(LibraryStore.STEAM, "10", "Known Game")))
        beat.updateKnownSteamGames(listOf(known, LibraryEntry(LibraryStore.STEAM, "20", "Known Game")))
        assertNull(beat.steamId(gog))
        beat.updateKnownSteamGames(emptyList())
        assertEquals("99", beat.steamId(gog))
    }

    @Test fun handlesBlockBoundariesUnicodePrefixesAndAbsentTitles() {
        // UTF-8 prefix sharing can split a multi-byte character; reconstruct bytes before decoding.
        val entries = listOf("a" to 10, "ab" to 20, "abc" to 30, "猫" to 40, "犬" to 50,
            "\uE000" to 60, "\uD800\uDC00" to 70, "z" to 80)
        val index = SteamTitleIndex.read(fixture(entries).inputStream())
        entries.forEach { (name, id) -> assertEquals(name, id, index.findNormalized(name)) }
        listOf("", "0", "aa", "abcd", "zz", "\uFFFF").forEach { assertNull(index.findNormalized(it)) }
        repeat(3) { assertNull(index.findNormalized("not found")) }
        assertNull(SteamTitleIndex.read(fixture(emptyList()).inputStream()).steamId("Anything"))
    }

    @Test fun normalizesTitlesWithoutMergingDifferentEditions() {
        val index = SteamTitleIndex.read(fixture(listOf("cafe the game" to 99)).inputStream())
        assertEquals(99, index.steamId("CAFÉ: The Game™"))
        assertNull(index.steamId("Café: The Game Remastered"))
        assertNull(index.steamId("Café: The Game 2"))
    }

    @Test fun rejectsTruncatedDataInvalidOffsetsAndPrefixes() {
        val valid = fixture(listOf("a" to 1))
        val raw = XZInputStream(valid.inputStream()).use { it.readBytes() }
        val invalidOffset = raw.copyOf().also { it[23] = 100 }
        assertThrows(IllegalArgumentException::class.java) { SteamTitleIndex.read(compress(invalidOffset).inputStream()) }
        val invalidPrefix = raw.copyOf().also { it[24] = 1 }
        assertThrows(IllegalArgumentException::class.java) {
            SteamTitleIndex.read(compress(invalidPrefix).inputStream()).findNormalized("a")
        }
        assertThrows(Exception::class.java) { SteamTitleIndex.read(valid.copyOf(valid.size / 2).inputStream()) }
    }

    @Test fun packagedIndexMatchesManifestAndIncludesBroaderCatalog() {
        val manifest = JSONObject(File("src/main/assets/steam-titles-v2.json").readText())
        val index = bundled()
        assertEquals(manifest.getInt("unique_titles"), index.size)
        assertTrue(index.size > 125_000)
        assertEquals(manifest.getLong("compressed_index_bytes"), asset.length())
        val digest = MessageDigest.getInstance("SHA-256").digest(asset.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(manifest.getString("index_sha256"), digest)
        val aliasesDigest = MessageDigest.getInstance("SHA-256").digest(File("src/main/assets/hltb-names.tsv").readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals("Rebuild the title catalog when aliases change", manifest.getString("aliases_sha256"), aliasesDigest)
        assertEquals(1022310, index.steamId("Warhammer Underworlds - Shadespire Edition"))
        assertEquals(367520, index.steamId("Hollow Knight"))
        listOf("Watch_Dogs® 2", "Watch Dogs 2", "Watch Dogs 2 (TM)", "Watch Dogs(R) 2").forEach {
            assertEquals(it, 447040, index.steamId(it))
        }
        assertNull(index.steamId("Hollow Knight Definitive Imaginary Edition"))
    }

    @Test fun everyBundledTitleIsSearchableAcrossEveryBlock() {
        val index = bundled()
        val input = DataInputStream(XZInputStream(asset.inputStream()))
        input.use {
            input.readInt(); input.readInt()
            repeat(2) { table ->
                val count = input.readInt(); val blockSize = input.readInt(); input.readInt()
                repeat((count + blockSize - 1) / blockSize) { input.readInt() }
                fun integer(): Int {
                    var value = 0; var shift = 0
                    do {
                        val byte = input.readUnsignedByte()
                        value = value or ((byte and 127) shl shift); shift += 7
                        if (byte < 128) return value
                    } while (shift < 35)
                    error("Invalid test data")
                }
                var previous = byteArrayOf()
                var lastTitle: String? = null
                repeat(count) { number ->
                    if (number % blockSize == 0) previous = byteArrayOf()
                    val shared = integer(); val suffix = integer()
                    previous = previous.copyOf(shared + suffix).also { input.readFully(it, shared, suffix) }
                    val id = integer()
                    val title = previous.toString(Charsets.UTF_8)
                    lastTitle?.let { assertTrue("Titles must follow Kotlin's sort order", it < title) }
                    assertEquals(title, id, if (table == 0) index.findNormalized(title) else index.steamId(title))
                    lastTitle = title
                }
            }
            assertEquals(-1, input.read())
        }
    }

    @Test fun mergedCatalogKeepsAliasesAndNeverOverridesTheirIds() {
        val index = bundled()
        val aliases = File("src/main/assets/hltb-names.tsv").readLines().map {
            it.substringBefore('\t').toInt() to it.substringAfter('\t')
        }.groupBy { BeatIndex.normalize(it.second) }
        aliases.forEach { (title, matches) ->
            val ids = matches.map { it.first }.toSet()
            val found = index.findNormalized(title)
            if (ids.size > 1) assertNull(title, found)
            else assertTrue("Existing alias must either retain its ID or be excluded as ambiguous: $title", found == null || found in ids)
        }
        val combined = File("src/main/assets/hltb-index-v1.bin").inputStream().use { BeatIndex.read(it, index) }
        assertEquals(hollowKnightMain(), combined.find(LibraryEntry(LibraryStore.GOG, "gog-id", "Hollow Knight"))?.main)
        assertEquals("1022310", combined.steamId(LibraryEntry(LibraryStore.EPIC, "epic-id", "Warhammer Underworlds - Shadespire Edition")))
        // A match absent from the broader catalog must not silently use the old, potentially ambiguous match.
        val empty = SteamTitleIndex.read(fixture(emptyList()).inputStream())
        val noMatches = File("src/main/assets/hltb-index-v1.bin").inputStream().use { BeatIndex.read(it, empty) }
        assertNull(noMatches.steamId(LibraryEntry(LibraryStore.GOG, "id", "Hollow Knight")))
    }
}
