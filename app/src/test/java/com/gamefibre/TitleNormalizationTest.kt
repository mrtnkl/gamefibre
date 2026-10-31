package com.gamefibre

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TitleNormalizationTest {
    @Test fun runtimeMatchesAssetBuilderFixtures() {
        File("src/test/resources/title-normalization.tsv").forEachLine { line ->
            val (title, exact, relaxed) = line.split('\t')
            assertEquals(title, exact, BeatIndex.normalize(title))
            assertEquals(title, relaxed, SteamTitleMatcher.relaxedKey(title))
            assertEquals("Idempotent: $title", exact, BeatIndex.normalize(exact))
        }
    }

    @Test fun watchDogsCopiesAndTextualTrademarksMergeAcrossStores() {
        val games = listOf(
            LibraryEntry(LibraryStore.STEAM, "447040", "Watch_Dogs® 2"),
            LibraryEntry(LibraryStore.GOG, "gog", "Watch Dogs 2 (TM)"),
            LibraryEntry(LibraryStore.EPIC, "epic", "Watch Dogs(R) 2"),
        )
        assertEquals(games.map { it.key }.toSet(), mergeLibraryCopies(games).single().copies.map { it.key }.toSet())
        assertEquals(games, matchingLibraryCopies(games.first(), games))
        assertEquals(games.map { it.key }.toSet(), libraryListKeys(games, setOf(games.last().key)))
        val beat = BeatIndex.parse(emptySequence(), sequenceOf("447040\tWatch_Dogs® 2"))
        assertEquals("447040", beat.steamId(games[1]))
    }

    @Test fun titleWordsEditionsAndKnownConflictsStayDistinct() {
        val base = LibraryEntry(LibraryStore.STEAM, "447040", "Watch Dogs 2")
        listOf("Watch Dogs 2 Deluxe Edition", "Watch Dogs 2 Demo", "Watch Dogs 3").forEach { title ->
            val other = LibraryEntry(LibraryStore.GOG, title, title)
            assertEquals(2, mergeLibraryCopies(listOf(base, other)).size)
        }
        assertNotEquals(BeatIndex.normalize("(R)Evolution"), BeatIndex.normalize("Evolution"))
        assertNotEquals(BeatIndex.normalize("Heaven & Hell"), BeatIndex.normalize("Heaven and Hell"))
        assertNotEquals(BeatIndex.normalize("No Man's Land"), BeatIndex.normalize("No Mans Land"))
    }

    @Test fun writtenCopyrightMarkersMergeUsingTheSameKeyAsArtwork() {
        val games = listOf(
            LibraryEntry(LibraryStore.STEAM, "1", "LEGO© Batman: The Videogame"),
            LibraryEntry(LibraryStore.GOG, "2", "Lego(c) Batman The Videogame"),
            LibraryEntry(LibraryStore.EPIC, "3", "LEGO (C) Batman™ The Videogame")
        )
        assertEquals(games.map { it.key }.toSet(), mergeLibraryCopies(games).single().copies.map { it.key }.toSet())
        assertEquals(games, matchingLibraryCopies(games.first(), games))
    }
}
