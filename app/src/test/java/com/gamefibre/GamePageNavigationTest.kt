package com.gamefibre

import org.junit.Assert.*
import org.junit.Test

class GamePageNavigationTest {
    @Test fun followsSourceOrderWithoutWrapping() {
        val row = listOf("c", "a", "b")
        assertEquals("b", adjacentGameKey(row, "a", 1, row.toSet()))
        assertEquals("c", adjacentGameKey(row, "a", -1, row.toSet()))
        assertNull(adjacentGameKey(row, "c", -1, row.toSet()))
        assertNull(adjacentGameKey(row, "b", 1, row.toSet()))
        assertNull(adjacentGameKey(row, "missing", 1, row.toSet()))
    }

    @Test fun relatedRowIsIndependentOfLibraryOrderAndSkipsRemovedEntries() {
        val library = listOf("a", "b", "c", "d")
        val related = listOf("d", "b", "a")
        assertEquals("c", adjacentGameKey(library, "b", 1, library.toSet()))
        assertEquals("a", adjacentGameKey(related, "b", 1, library.toSet()))
        assertEquals("a", adjacentGameKey(related, "d", 1, setOf("a", "d")))
        assertNull(adjacentGameKey(listOf("a"), "a", 1, library.toSet()))
    }

    private fun gesture(atTop: Boolean = false, horizontal: Boolean = true) =
        GamePageGesture(8f, 72f).apply { start(200f, 200f, atTop, horizontal) }

    @Test fun horizontalDragStartsFollowingAfterSlopButShortDragDoesNotNavigate() {
        val drag = gesture()
        drag.move(195f, 200f)
        assertFalse(drag.claimed)
        drag.move(180f, 200f)
        assertTrue(drag.claimed)
        assertTrue(drag.horizontal)
        assertNull(drag.finish(180f, 200f))
    }

    @Test fun pullDownDoesNotBecomeAPageDrag() {
        val drag = gesture(atTop = true)
        drag.move(200f, 280f)
        assertTrue(drag.claimed)
        assertFalse(drag.horizontal)
        drag.move(400f, 280f)
        assertFalse(drag.horizontal)
        assertNull(drag.finish(400f, 280f))
    }

    @Test fun pullDownStartsFollowingAfterSlopAndCanBeCancelledBeforeClosing() {
        val drag = gesture(atTop = true)
        drag.move(200f, 205f)
        assertFalse(drag.claimed)
        drag.move(200f, 220f)
        assertTrue(drag.claimed)
        assertFalse(drag.horizontal)
        assertNull(drag.finish(200f, 220f))

        val cancelled = gesture(atTop = true)
        cancelled.move(200f, 280f)
        cancelled.cancel()
        assertNull(cancelled.finish(200f, 280f))
    }

    @Test fun horizontalSwipesNavigateAtAnyScrollPosition() {
        val next = gesture()
        next.move(100f, 205f)
        assertTrue(next.claimed)
        assertEquals(GamePageGesture.Action.NEXT, next.finish(100f, 205f))
        val previous = gesture(atTop = true)
        previous.move(280f, 200f)
        assertEquals(GamePageGesture.Action.PREVIOUS, previous.finish(280f, 200f))
    }

    @Test fun screenshotAndRelatedRowGesturesNeverNavigateEvenAfterLeavingTheirBounds() {
        val gallery = gesture(horizontal = false)
        gallery.move(180f, 200f)
        gallery.move(20f, 205f)
        assertFalse(gallery.claimed)
        assertNull(gallery.finish(20f, 205f))
    }

    @Test fun verticalScrollAndDiagonalDragCannotBecomeHorizontalNavigation() {
        val vertical = gesture()
        vertical.move(200f, 180f)
        vertical.move(80f, 180f)
        assertNull(vertical.finish(80f, 180f))
        val diagonal = gesture(atTop = true)
        diagonal.move(180f, 220f)
        diagonal.move(80f, 220f)
        assertNull(diagonal.finish(80f, 220f))
    }

    @Test fun pullDownStillClosesOnlyAtTop() {
        val top = gesture(atTop = true)
        top.move(200f, 280f)
        assertEquals(GamePageGesture.Action.CLOSE, top.finish(200f, 280f))
        val scrolled = gesture()
        scrolled.move(200f, 280f)
        assertNull(scrolled.finish(200f, 280f))
    }

    @Test fun shortReversedAndMultitouchGesturesDoNotNavigate() {
        val short = gesture()
        short.move(180f, 200f)
        assertNull(short.finish(180f, 200f))
        val reversed = gesture()
        reversed.move(100f, 200f)
        assertTrue(reversed.claimed)
        assertNull(reversed.finish(190f, 200f))
        val multitouch = gesture()
        multitouch.move(100f, 200f)
        multitouch.cancel()
        assertTrue(multitouch.claimed)
        assertNull(multitouch.finish(100f, 200f))
    }
}
