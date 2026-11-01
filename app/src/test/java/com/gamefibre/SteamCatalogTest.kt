package com.gamefibre

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SteamCatalogTest {
    private fun game(id: Int, score: Int = 0, positive: Int = 0, count: Int = 0, day: Int = 0) =
        CatalogGame(id, "Game %03d".format(id), score, day, positive, count)

    @Test fun pagesPartitionResultsAndClampPastEnd() {
        val catalog = SteamCatalog((1..65).map { game(it) }) { emptyList() }
        val pages = (0..2).map { catalog.search("", TagFilters(), CatalogSort.NAME, false, it) }
        assertEquals(listOf(30, 30, 5), pages.map { it.games.size })
        assertEquals(65, pages.flatMap { it.games }.map { it.appId }.toSet().size)
        assertTrue(pages.all { it.total == 65 && it.pageCount == 3 })
        assertEquals(2, catalog.search("", TagFilters(), CatalogSort.NAME, false, 99).page)
        assertEquals(0, catalog.search("missing", TagFilters(), CatalogSort.NAME, false, 99).page)
    }

    @Test fun allNumericOrdersKeepUnknownLastInBothDirections() {
        val catalog = SteamCatalog(listOf(game(1), game(2, 90, 3, 4, 40000), game(3, 80, 1, 2, 30000))) { emptyList() }
        listOf(CatalogSort.METASCORE, CatalogSort.USER_SCORE, CatalogSort.REVIEWS, CatalogSort.RELEASE).forEach { sort ->
            assertEquals(listOf(2, 3, 1), catalog.search("", TagFilters(), sort, true, 0).games.map { it.appId })
            assertEquals(listOf(3, 2, 1), catalog.search("", TagFilters(), sort, false, 0).games.map { it.appId })
        }
        assertEquals(75.0, catalog.games[1].userScore!!, 0.0)
        assertNull(catalog.games[0].userScore)
    }

    @Test fun textAndTagsCombineAndFacetsKeepGlobalFrequencyOrder() {
        val catalog = SteamCatalog((1..4).map { game(it) }) { id ->
            when (id) { 1 -> listOf("Space", "RPG"); 2, 3 -> listOf("RPG"); else -> emptyList() }
        }
        val result = catalog.search("001", TagFilters(setOf("Space")), CatalogSort.NAME, false, 0)
        assertEquals(listOf(1), result.games.map { it.appId })
        assertEquals(listOf("RPG", "Space"), result.facets.map { it.name })
        assertEquals(4, catalog.search("", TagFilters(), CatalogSort.NAME, false, 0).total)
        assertEquals(1, catalog.search("", TagFilters(excluded = setOf("RPG")), CatalogSort.NAME, false, 0).total)
    }

    @Test fun bundledCatalogHasOriginalNamesAndValidMetadata() {
        val catalog = File("src/main/assets/steam-catalog-v1.bin.xz").inputStream().use { SteamCatalog.read(it) { emptyList() } }
        assertEquals(144498, catalog.games.size)
        assertEquals(4282, catalog.games.count { it.metascore > 0 })
        assertEquals(82988, catalog.games.count { it.reviewCount > 0 })
        assertTrue(catalog.games.zipWithNext().all { (a, b) -> a.appId < b.appId })
        val portal = catalog.games.first { it.appId == 620 }
        assertEquals("Portal 2", portal.name)
        assertEquals(95, portal.metascore)
        assertNotNull(portal.release)
        assertTrue(portal.positive <= portal.reviewCount)
        assertEquals("STEAM:620", portal.entry().key)
    }

    @Test fun libraryScoreIndexMatchesTheBundledCatalog() {
        val file = File("src/main/assets/steam-catalog-v1.bin.xz")
        val catalog = file.inputStream().use { SteamCatalog.read(it) { emptyList() } }
        val scores = file.inputStream().use(SteamCatalog::readMetascores)
        assertEquals(catalog.games.filter { it.metascore > 0 }.associate { it.appId to it.metascore }, scores)
        assertEquals(95, scores[620])
        assertNull(scores[-1])
    }
}
