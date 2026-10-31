package com.gamefibre

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveSteamTagsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun liveCacheSurvivesRestartAndFailureWithoutTouchingStaticAssets() = runBlocking {
        val folder = temporary.newFolder()
        var time = 1_000_000L
        var fail = false
        val cache = LiveSteamTags(folder, { if (fail) error("offline") else listOf("Space", "RPG") }, { time })
        cache.load()
        assertNull(cache.tags(123456789))
        assertTrue(cache.refresh(123456789))
        assertEquals(listOf("Space", "RPG"), cache.tags(123456789))
        val restored = LiveSteamTags(folder, { error("offline") }, { time })
        restored.load()
        assertEquals(cache.tags(123456789), restored.tags(123456789))
        time += 8 * 86_400_000L
        fail = true
        assertFalse(cache.refresh(123456789, true))
        assertEquals(listOf("Space", "RPG"), cache.tags(123456789))
        assertEquals(listOf("123456789.json"), folder.listFiles()!!.map { it.name })
    }

    @Test fun throttlesRetriesAndFreshEntriesAndKeepsOldDataOnEmptyResponse() = runBlocking {
        var time = 1_000_000L; var calls = 0; var result = listOf("Space")
        val cache = LiveSteamTags(temporary.newFolder(), { calls++; result }, { time })
        assertTrue(cache.refresh(1))
        assertFalse(cache.refresh(1, true)); assertEquals(1, calls)
        time += 16 * 60_000L
        assertFalse(cache.refresh(1)); assertEquals(1, calls)
        result = emptyList()
        assertFalse(cache.refresh(1, true))
        assertEquals(listOf("Space"), cache.tags(1))
        time += 16 * 60_000L; result = listOf("RPG")
        assertTrue(cache.refresh(1, true))
        assertEquals(listOf("RPG"), cache.tags(1))
    }

    @Test fun parsesOnlyTheRequestedGamesRankedTagsAndRejectsAgeGates() {
        val html = "InitAppTagModal( 620, [{\"tagid\":1,\"name\":\"Space ] \\\"game\\\"\"},{\"tagid\":2,\"name\":\"RPG\"}], []);"
        assertEquals(listOf("Space ] \"game\"", "RPG"), parseSteamTags(620, html))
        assertThrows(Exception::class.java) { parseSteamTags(621, html) }
        assertThrows(Exception::class.java) { parseSteamTags(620, "<html>agecheck</html>") }
        assertThrows(Exception::class.java) { parseSteamTags(620, "InitAppTagModal(620, [{") }
        assertEquals(emptyList<String>(), parseSteamTags(620, "InitAppTagModal(620, [], []);"))
    }
}
