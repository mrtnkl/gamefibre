package com.gamefibre

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private fun release(version: String = "0.1.2") = JSONObject()
        .put("tag_name", "v$version").put("draft", false).put("prerelease", false)
        .put("assets", JSONArray().put(JSONObject().put("name", "gamefibre-$version.apk")
            .put("state", "uploaded").put("size", 42)
            .put("browser_download_url", "https://github.com/mrtnkl/gamefibre/releases/download/v$version/gamefibre-$version.apk")))

    @Test fun comparesNumericVersionsAndNeverOffersAnOlderOrEqualRelease() {
        assertNotNull(githubAppUpdate(release("0.1.10"), "0.1.9"))
        assertNotNull(githubAppUpdate(release("1.0.0"), "0.99.99"))
        assertNull(githubAppUpdate(release("0.1.2"), "0.1.2"))
        assertNull(githubAppUpdate(release("0.1.2"), "0.1.10"))
    }

    @Test fun requiresStableVersionTagsAndCompletedApkUploads() {
        assertNull(githubAppUpdate(release().put("draft", true), "0.1.1"))
        assertNull(githubAppUpdate(release().put("prerelease", true), "0.1.1"))
        assertNull(githubAppUpdate(release("0.1.2-beta"), "0.1.1"))
        assertNull(githubAppUpdate(release().put("tag_name", "0.1.2"), "0.1.1"))
        assertNull(githubAppUpdate(release().put("assets", JSONArray()), "0.1.1"))
        for (change in listOf("state" to "new", "size" to 0, "name" to "other.apk")) {
            val response = release()
            response.getJSONArray("assets").getJSONObject(0).put(change.first, change.second)
            assertNull(githubAppUpdate(response, "0.1.1"))
        }
        assertNull(githubAppUpdate(release("999999999999999999999999.0.0"), "0.1.1"))
    }

    @Test fun onlyAcceptsTheExpectedRepositoryAndVersionedApkUrl() {
        for (url in listOf("http://github.com/mrtnkl/gamefibre/releases/download/v0.1.2/gamefibre-0.1.2.apk",
            "https://evil.example/update.apk", "https://github.com/other/gamefibre/releases/download/v0.1.2/gamefibre-0.1.2.apk",
            "https://github.com/mrtnkl/gamefibre/releases/download/v0.1.1/gamefibre-0.1.1.apk")) {
            val response = release()
            response.getJSONArray("assets").getJSONObject(0).put("browser_download_url", url)
            assertNull(githubAppUpdate(response, "0.1.1"))
        }
    }

    @Test fun dismissedVersionStaysInMenuAcrossRestartsButANewerVersionCanPrompt() {
        val prefs = testPreferences()
        val updates = AppUpdates(prefs, "0.1.1") { release() }
        updates.check(100)
        val update = updates.available!!
        assertTrue(updates.shouldPrompt(update))
        updates.markPrompted(update)
        val restarted = AppUpdates(prefs, "0.1.1") { release("0.1.3") }
        assertEquals(update, restarted.available)
        assertFalse(restarted.shouldPrompt(restarted.available!!))
        restarted.check(200)
        assertTrue(restarted.shouldPrompt(restarted.available!!))
        assertNull(AppUpdates(prefs, "0.1.3").available)
    }

    @Test fun checksAtMostOncePerDayAndCanRecoverFromClockChanges() {
        val updates = AppUpdates(testPreferences(), "0.1.1") { release() }
        assertTrue(updates.shouldCheck(100))
        updates.check(100)
        assertFalse(updates.shouldCheck(101))
        assertFalse(updates.shouldCheck(100 + 86_400_000L - 1))
        assertTrue(updates.shouldCheck(100 + 86_400_000L))
        assertTrue(updates.shouldCheck(99))
    }

    @Test fun failedAndMalformedChecksRetainKnownUpdateAndDismissal() {
        val prefs = testPreferences()
        val updates = AppUpdates(prefs, "0.1.1") { release() }
        updates.check(100)
        val update = updates.available!!
        updates.markPrompted(update)
        for (fetch in listOf<() -> JSONObject>({ error("Offline") }, { JSONObject() })) {
            val failed = AppUpdates(prefs, "0.1.1", fetch)
            assertThrows(IllegalStateException::class.java) { failed.check(200) }
            assertEquals(update, failed.available)
            assertFalse(failed.shouldPrompt(failed.available!!))
            assertFalse(failed.shouldCheck(201))
            assertTrue(failed.shouldCheck(200 + 15 * 60 * 1000L))
        }
    }
}
