package com.gamefibre

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BeatIndexTest {
    private val csv = sequenceOf("steam_id,comp_main,comp_plus,comp_100", "10,3600,7200,10800", "20,1800,0,0")
    private fun game(store: LibraryStore, id: String, title: String) = LibraryEntry(store, id, title)

    @Test fun steamMatchesByIdRatherThanTitle() {
        val index = BeatIndex.parse(csv, emptySequence())
        assertEquals(3600L, index.find(game(LibraryStore.STEAM, "10", "Different title"))?.main)
        assertNull(index.find(game(LibraryStore.STEAM, "99", "Game")))
    }

    @Test fun otherStoresMatchUnambiguousNormalizedNamesOffline() {
        val index = BeatIndex.parse(csv, sequenceOf("10\tCafé: The Game™"))
        assertEquals(7200L, index.find(game(LibraryStore.GOG, "gog-id", "Cafe - The Game"))?.extras)
        assertEquals(10800L, index.find(game(LibraryStore.EPIC, "epic-id", "CAFÉ: THE GAME"))?.complete)
    }

    @Test fun ambiguousNamesAndDifferentEditionsStayUnmatched() {
        val index = BeatIndex.parse(csv, sequenceOf("10\tGame", "20\tGame", "10\tOther Game"))
        assertNull(index.find(game(LibraryStore.GOG, "1", "Game")))
        assertNull(index.find(game(LibraryStore.GOG, "2", "Other Game Remastered")))
    }

    @Test fun sparseRowsAndDuplicatesAreDeterministic() {
        val index = BeatIndex.parse(sequenceOf("steam_id,comp_main,comp_plus,comp_100", "0,2", "10,0,0,0", "10,3600", "10,7200", "bad,9"), sequenceOf("10\tGame", "10\tGame"))
        assertEquals(BeatTime(3600, 0, 0), index.find(game(LibraryStore.GOG, "1", "Game")))
    }

    @Test fun ambiguousNamesStayUnmatchedEvenWhenOnlyOneEditionHasTimes() {
        val index = BeatIndex.parse(csv, sequenceOf("10\tSame title", "99\tSame title"))
        assertNull(index.find(game(LibraryStore.EPIC, "epic-id", "Same title")))
    }

    @Test fun steamArtworkIdsCanBeResolvedEvenWithoutCompletionEstimates() {
        val index = BeatIndex.parse(csv, sequenceOf("99\tAnother Game", "10\tKnown Game"))
        val gog = game(LibraryStore.GOG, "gog-id", "Another Game")
        assertEquals("99", index.steamId(gog))
        assertNull(index.find(gog))
        assertNull(index.steamId(game(LibraryStore.EPIC, "epic-id", "Another Game Remastered")))
        assertEquals("10", index.steamId(game(LibraryStore.STEAM, "10", "Any title")))
    }

    @Test fun bundledDatasetCanBeReadWithoutAnyNetwork() {
        val csv = File("src/main/assets/hltb-min.csv").readLines()
        val names = File("src/main/assets/hltb-names.tsv").readLines()
        val index = BeatIndex.parse(csv.asSequence(), names.asSequence())
        assertEquals(97025L, index.find(game(LibraryStore.STEAM, "367520", "Hollow Knight"))?.main)
        assertEquals(97025L, index.find(game(LibraryStore.GOG, "other-id", "Hollow Knight"))?.main)
    }
    @Test fun binaryIndexMatchesSourceDataForEveryIdAndTitle() {
        val csv = File("src/main/assets/hltb-min.csv").readLines()
        val names = File("src/main/assets/hltb-names.tsv").readLines()
        val source = BeatIndex.parse(csv.asSequence(), names.asSequence())
        val binary = File("src/main/assets/hltb-index-v1.bin").inputStream().use(BeatIndex::read)
        csv.drop(1).forEach { row ->
            val game = game(LibraryStore.STEAM, row.substringBefore(','), "")
            assertEquals(source.find(game), binary.find(game))
        }
        names.forEach { row ->
            val game = game(LibraryStore.GOG, "id", row.substringAfter('\t'))
            assertEquals(game.name, source.steamId(game), binary.steamId(game))
            assertEquals(source.find(game), binary.find(game))
        }
    }

}
