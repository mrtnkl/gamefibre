package com.gamefibre

import org.junit.Assert.*
import org.junit.Test

class LibrarySearchTest {
    private val games = listOf(
        TagSearchGame("a", "Space Alpha", listOf("RPG", "Space", "First-Person")),
        TagSearchGame("b", "Space Beta", listOf("RPG", "Space", "Multiplayer")),
        TagSearchGame("c", "Earth", listOf("RPG", "First-Person")),
        TagSearchGame("d", "Space Unknown", emptyList()),
    )
    private val search = LibraryTagSearch(games)

    @Test fun combinesTitleTextAllIncludedAndAnyExcludedTags() {
        assertEquals(setOf("a", "b", "d"), search.keys(" space ", TagFilters()))
        val filters = TagFilters(setOf("rpg", "Space"), setOf("Multiplayer"))
        assertEquals(setOf("a"), search.keys("Space", filters))
        assertEquals(setOf("a", "c", "d"), search.keys("", TagFilters(excluded = setOf("Multiplayer"))))
        assertEquals(emptySet<String>(), search.keys("", TagFilters(setOf("Absent"))))
        assertEquals(setOf("a", "b", "c", "d"), search.keys("", TagFilters(excluded = setOf("Absent"))))
    }

    @Test fun facetCountsPredictEveryIncludeExcludeAndClearAction() {
        val selections = listOf(TagFilters(), TagFilters(setOf("RPG")), TagFilters(setOf("Space"), setOf("Multiplayer")),
            TagFilters(setOf("Absent")), TagFilters(excluded = setOf("Space")))
        for (text in listOf("", "Space", "Earth", "nothing")) for (filters in selections) {
            search.facets(text, filters).forEach { facet ->
                fun expected(state: TagState): Int {
                    val next = filters.with(facet.name, state)
                    return games.count { game -> game.name.contains(text, true) &&
                        next.included.all { tag -> game.tags.any { it.equals(tag, true) } } &&
                        next.excluded.none { tag -> game.tags.any { it.equals(tag, true) } }
                    }
                }
                assertEquals(expected(TagState.INCLUDE), facet.includeCount)
                assertEquals(expected(TagState.EXCLUDE), facet.excludeCount)
                assertEquals(expected(TagState.OFF), facet.clearCount)
            }
        }
    }

    @Test fun switchingModesKeepsStatesDisjointAndUnknownSelectionsRemovable() {
        val included = TagFilters().with("RPG", TagState.INCLUDE)
        val excluded = included.with("rpg", TagState.EXCLUDE)
        assertTrue(excluded.included.isEmpty())
        assertEquals(TagState.EXCLUDE, excluded.state("RPG"))
        assertFalse(excluded.toggle("RPG").active)
        val facet = search.facets("nothing", TagFilters(setOf("Unknown"))).single()
        assertEquals("Unknown", facet.name)
        assertEquals(TagState.INCLUDE, facet.state)
    }

    @Test fun mergedCopiesMatchAlternateNamesAndCountAsOneGame() {
        val merged = LibraryTagSearch(listOf(TagSearchGame("steam:1", "Café Adventure", listOf("RPG"), listOf("Cafe Adventure"))))
        assertEquals(setOf("steam:1"), merged.keys("Cafe", TagFilters(setOf("RPG"))))
        assertEquals(1, merged.facets("Cafe", TagFilters()).single().includeCount)
    }

    @Test fun gameTagsRankByOwnedLibraryFrequencyWithSteamOrderForTies() {
        assertEquals(listOf("RPG", "First-Person", "Space", "Multiplayer", "Unknown"),
            search.rankByLibraryFrequency(listOf("Multiplayer", "First-Person", "Space", "RPG", "Unknown")))
        // Duplicate/case-varied labels on one game contribute a single occurrence.
        val duplicates = LibraryTagSearch(listOf(
            TagSearchGame("merged", "One game on multiple stores", listOf("RPG", "rpg", "RPG")),
            TagSearchGame("other", "Another game", listOf("Space"))))
        assertEquals(listOf("Space", "RPG"), duplicates.rankByLibraryFrequency(listOf("Space", "RPG")))
        assertEquals(listOf("Missing", "Unknown"), search.rankByLibraryFrequency(listOf("Missing", "Unknown")))
    }
}
