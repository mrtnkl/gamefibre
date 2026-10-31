package com.gamefibre.steam

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryMetadataTest {
    @Test fun interruptedRefreshKeepsCompletedBatchesAndIncludesNewGames() {
        val complete = JSONObject().put("appId", 1).put("metadataPending", false).put("details", JSONObject())
        val interrupted = JSONObject().put("appId", 2).put("metadataPending", true).put("details", JSONObject())
        val newGame = JSONObject().put("appId", 3).put("metadataPending", true)
        assertFalse(refreshAllSteamMetadata(listOf(complete, interrupted), force = true))
        assertEquals(listOf(2, 3), listOf(complete, interrupted, newGame).filter(::needsSteamMetadata).map { it.getInt("appId") })
    }
    @Test fun completeRecordsNeedMetadataOnlyOnExplicitRefresh() {
        val complete = JSONObject().put("metadataPending", false)
        assertFalse(needsSteamMetadata(complete))
        assertFalse(refreshAllSteamMetadata(listOf(complete), force = false))
        assertTrue(refreshAllSteamMetadata(listOf(complete), force = true))
    }
}
