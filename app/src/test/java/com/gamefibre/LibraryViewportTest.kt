package com.gamefibre

import org.junit.Assert.*
import org.junit.Test

class LibraryViewportTest {
    @Test fun reversedEndpointsDuringSortKeepBothVisibleGamesInDemand() {
        // The reported crash mapped the old visible endpoints to 205 and 83 (three columns).
        val range = libraryViewportRange(205, 83, 300, 3)
        assertEquals(77 until 229, range)
        assertTrue(205 in range && 83 in range)
        assertEquals(range, libraryViewportRange(83, 205, 300, 3))
    }

    @Test fun normalViewportRetainsExistingLookAheadAndBounds() {
        assertEquals(14 until 64, libraryViewportRange(20, 40, 300, 3))
        assertEquals(0 until 30, libraryViewportRange(0, 6, 300, 3))
        assertEquals(284 until 300, libraryViewportRange(290, 299, 300, 3))
    }

    @Test fun shrinkingAndEmptyListsCannotProduceInvalidPositions() {
        assertTrue(libraryViewportRange(205, 83, 0, 3).isEmpty())
        assertTrue(libraryViewportRange(205, 83, 10, 3).isEmpty())
        assertEquals(0 until 10, libraryViewportRange(205, 0, 10, 3))
    }

    @Test fun allVisibleEndpointPairsSurviveReorderingAcrossGridSizes() {
        for (columns in 1..6) for (first in 0 until 40) for (last in 0 until 40) {
            val range = libraryViewportRange(first, last, 40, columns)
            assertTrue(first in range && last in range)
            assertTrue(range.all { it in 0 until 40 })
        }
    }
}
