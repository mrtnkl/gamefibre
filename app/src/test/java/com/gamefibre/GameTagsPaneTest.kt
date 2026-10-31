package com.gamefibre

import android.widget.CheckBox
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameTagsPaneTest {
    @Test fun multipleTagsStaySelectedUntilSearchIsPressed() {
        val ui = NativeUi(RuntimeEnvironment.getApplication())
        val selected = mutableSetOf<String>()
        var searched: Set<String>? = null
        val pane = gameTagsPane(ui, listOf("RPG", "Space"), selected, false) { searched = it }
        val pills = (0 until pane.childCount).map(pane::getChildAt).filterIsInstance<CenteredChipGroup>().single()
        val button = pane.getChildAt(pane.childCount - 1) as LinearLayout
        assertFalse(button.isEnabled)
        (pills.getChildAt(0) as CheckBox).performClick()
        (pills.getChildAt(1) as CheckBox).performClick()
        assertNull(searched)
        assertEquals(setOf("RPG", "Space"), selected)
        assertTrue(button.isEnabled)
        button.performClick()
        assertEquals(selected, searched)
        val rebuilt = gameTagsPane(ui, listOf("RPG", "Space"), selected, false) { searched = it }
        val rebuiltPills = (0 until rebuilt.childCount).map(rebuilt::getChildAt).filterIsInstance<CenteredChipGroup>().single()
        assertTrue((rebuiltPills.getChildAt(0) as CheckBox).isChecked)
        assertTrue((rebuiltPills.getChildAt(1) as CheckBox).isChecked)
    }
}
