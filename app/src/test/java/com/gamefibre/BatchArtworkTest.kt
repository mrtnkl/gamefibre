package com.gamefibre

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BatchArtworkTest {
    private val art = GridArtwork(7, "https://cdn2.steamgriddb.com/grid/a.png", "", "Artist")
    private val targets = listOf(ArtworkTarget("Game", listOf("steam:1", "gog:2")), ArtworkTarget("Other", listOf("steam:3")))

    @Test fun sequelNumeralsAndCandidateSubtitlesMatch() {
        listOf(
            "Wing Commander 2" to "Wing Commander II",
            "Wing Commander II" to "Wing Commander 2",
            "Wing Commander 2" to "Wing Commander II: Vengeance of the Kilrathi",
            "Wing Commander II" to "Wing Commander II: Vengeance of the Kilrathi",
            "Wing Commander 3" to "Wing Commander III – Heart of the Tiger",
            "Wing Commander III" to "Wing Commander 3: Heart of the Tiger",
            "Civilization 6" to "Civilization VI",
            "Game" to "Game: A Story"
        ).forEach { (title, candidate) ->
            assertEquals(title, listOf(GridGame(1, candidate)), GridTitleMatcher.matches(title, listOf(GridGame(1, candidate))))
        }
    }

    @Test fun subtitlesCannotHideDifferentSequelsEditionsOrSuppliedSubtitles() {
        listOf(
            "Wing Commander 2" to "Wing Commander III: Heart of the Tiger",
            "Wing Commander II: Special Operations" to "Wing Commander II: Vengeance of the Kilrathi",
            "Wing Commander II: Vengeance of the Kilrathi" to "Wing Commander II",
            "Wing Commander" to "Wing Commander II: Vengeance of the Kilrathi",
            "Game" to "Game: II",
            "Game" to "Game: 2",
            "Game" to "Game: Deluxe Edition",
            "Game" to "Game: Remastered",
            "Game" to "Game Extra Words"
        ).forEach { (title, candidate) ->
            assertTrue("$title / $candidate", GridTitleMatcher.matches(title, listOf(GridGame(1, candidate))).isEmpty())
        }
    }

    @Test fun fullTitlesTakePriorityButMultipleSubtitleCandidatesStayAmbiguous() {
        val full = GridGame(1, "Wing Commander II")
        val first = GridGame(2, "Wing Commander II: Vengeance of the Kilrathi")
        val second = GridGame(3, "Wing Commander 2: Special Operations")
        assertEquals(listOf(full), GridTitleMatcher.matches("Wing Commander 2", listOf(first, second, full)))
        assertEquals(listOf(first, second), GridTitleMatcher.matches("Wing Commander II", listOf(first, second, first)))
    }

    @Test fun lookupRetriesAlternateNumeralsAndAppliesSubtitleMatch() {
        listOf("Wing Commander 2" to "Wing Commander II", "Wing Commander III" to "Wing Commander 3").forEach { (title, alternate) ->
            val searched = mutableListOf<String>()
            val saved = mutableListOf<ArtworkTarget>()
            val target = ArtworkTarget(title, listOf("gog:1"))
            val batch = BatchArtwork(search = {
                searched += it
                if (it == alternate) listOf(GridGame(1, "$alternate: A Story")) else emptyList()
            }, grids = { listOf(art) }, save = { game, _ -> saved += game }, pace = {})
            runBlocking { batch.start(this, listOf(target)) }
            assertEquals(listOf(title, alternate), searched)
            assertEquals(listOf(target), saved)
        }
    }

    @Test fun ambiguousSubtitleResultsNeverApplyACover() {
        val batch = BatchArtwork(search = { listOf(GridGame(1, "Game: One Story"), GridGame(2, "Game: Another Story")) },
            grids = { error("Ambiguous games must not load covers") },
            save = { _, _ -> error("Ambiguous games must not save") }, pace = {})
        runBlocking { batch.start(this, listOf(targets.first())) }
        assertEquals("Skipped · multiple matching titles", batch.status.value!!.results.single().message)
    }

    @Test fun fullNormalizedMatchIgnoresFormattingAndDeduplicatesIds() {
        val games = listOf(GridGame(1, "Game"), GridGame(2, "Game: Deluxe"), GridGame(3, "GAME"), GridGame(1, "Game"))
        assertEquals(listOf(GridGame(1, "Game"), GridGame(3, "GAME")), GridTitleMatcher.matches(" Game ", games))
        assertEquals(listOf(GridGame(2, "Game: Deluxe")), GridTitleMatcher.matches("Game Deluxe", games))
    }

    @Test fun legoFormattingVariantsApplyUsingTheLibraryCopyComparison() {
        val titles = listOf(
            "LEGO® Batman™: The Videogame" to "Lego Batman The Videogame",
            "LEGO Batman (TM) 2: DC Super Heroes" to "Lego Batman 2 DC Super Heroes",
            "LEGO© Batman 3: Beyond Gotham" to "Lego Batman 3 Beyond Gotham",
            "LEGO(c) Harry Potter: Years 1-4" to "Lego Harry Potter Years 1–4",
            "LEGO (C) Harry Potter: Years 5-7" to "Lego Harry Potter Years 5–7",
            "LEGO(R) Star Wars™: The Complete Saga" to "Lego Star Wars The Complete Saga"
        )
        val saved = mutableListOf<ArtworkTarget>()
        val batch = BatchArtwork(
            search = { title -> listOf(GridGame(1, titles.single { it.first == title }.second)) },
            grids = { listOf(art) }, save = { target, _ -> saved += target }, pace = {})
        val targets = titles.mapIndexed { index, (title, candidate) ->
            assertEquals(BeatIndex.normalize(title), BeatIndex.normalize(candidate))
            ArtworkTarget(title, listOf("steam:$index", "gog:$index"))
        }
        runBlocking { batch.start(this, targets) }
        assertEquals(targets, saved)
        assertEquals(targets.map { it.keys.toSet() }, batch.status.value!!.results.map { it.appliedKeys })
    }

    @Test fun differentGamesAndEmptyNormalizedNamesStayUnmatched() {
        val title = "LEGO Batman"
        listOf("LEGO Batman 2", "LEGO Batman Deluxe Edition", "LEGO Batman Demo", "LEGO Batman Remastered").forEach {
            assertTrue(GridTitleMatcher.matches(title, listOf(GridGame(1, it))).isEmpty())
        }
        assertTrue(GridTitleMatcher.matches("™ (c) :", listOf(GridGame(1, "®"))).isEmpty())
        assertTrue(GridTitleMatcher.matches("Drak(c)ula", listOf(GridGame(1, "Drakula"))).isEmpty())
    }

    @Test fun wholeGameCompletesBeforeNextSearchAndFirstCoverIsAppliedToAllCopies() {
        val events = mutableListOf<String>()
        val batch = BatchArtwork(
            search = { events += "search:$it"; listOf(GridGame(if (it == "Game") 1 else 2, it)) },
            grids = { events += "grids:$it"; listOf(art, art.copy(id = 8)) },
            save = { target, cover -> events += "save:${target.keys.joinToString()}:${cover.id}" },
            pace = { events += "pace" })
        runBlocking { batch.start(this, targets) }
        assertEquals(listOf("search:Game", "pace", "grids:1", "save:steam:1, gog:2:7", "pace",
            "search:Other", "pace", "grids:2", "save:steam:3:7", "pace"), events)
        assertFalse(batch.status.value!!.running)
        assertEquals(2, batch.status.value!!.results.size)
    }

    @Test fun unmatchedAmbiguousAndEmptyResultsNeverReplaceExistingCovers() {
        var saved = false
        val batch = BatchArtwork(
            search = { title -> when (title) {
                "Game" -> listOf(GridGame(1, "Game Deluxe"))
                "Other" -> listOf(GridGame(2, "Other"), GridGame(3, "OTHER™"))
                else -> listOf(GridGame(4, title))
            } },
            grids = { emptyList() }, save = { _, _ -> saved = true }, pace = {})
        runBlocking { batch.start(this, targets + ArtworkTarget("Empty", listOf("steam:4"))) }
        assertFalse(saved)
        assertTrue(batch.status.value!!.results.all { it.message.startsWith("Skipped") })
        assertEquals(3, batch.status.value!!.results.size)
    }

    @Test fun failedDownloadDoesNotPreventNextGameFromBeingApplied() {
        val saved = mutableListOf<String>()
        val batch = BatchArtwork(search = { listOf(GridGame(1, it)) }, grids = { listOf(art) },
            save = { target, _ -> if (target.title == "Game") error("Download failed") else saved += target.title }, pace = {})
        runBlocking { batch.start(this, targets) }
        assertEquals(listOf("Other"), saved)
        assertTrue(batch.status.value!!.results.first().message.startsWith("Failed"))
    }

    @Test fun rateLimitStopsQueueWithoutRequestingNextGame() {
        val searched = mutableListOf<String>()
        val batch = BatchArtwork(search = { searched += it; throw GridRequestRejected("Rate limited") },
            grids = { error("Must not load grids") }, save = { _, _ -> error("Must not save") }, pace = {})
        runBlocking { batch.start(this, targets) }
        assertEquals(listOf("Game"), searched)
        assertEquals("Rate limited", batch.status.value!!.message)
        assertFalse(batch.status.value!!.running)
    }

    @Test fun stopFinishesCurrentSaveAndDoesNotStartNextGame() {
        val saved = mutableListOf<String>()
        lateinit var batch: BatchArtwork
        batch = BatchArtwork(search = { listOf(GridGame(1, it)) }, grids = { listOf(art) },
            save = { target, _ -> batch.stop(); saved += target.title }, pace = {})
        runBlocking { batch.start(this, targets) }
        assertEquals(listOf("Game"), saved)
        assertFalse(batch.status.value!!.running)
        assertTrue(batch.status.value!!.message.startsWith("Stopped"))
    }
}
