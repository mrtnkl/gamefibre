package com.gamefibre

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StoreMetadataTest {
    @Test fun storageExtractionIgnoresRamAndSupportsHtmlAndDecimalUnits() {
        assertEquals(20L * 1024 * 1024 * 1024, steamStorageBytes("<li>Memory: 8 GB RAM</li><li><strong>Storage:</strong> 20 GB available space</li>"))
        assertEquals(1536L * 1024 * 1024, steamStorageBytes("Hard drive space: 1.5 GB"))
        assertEquals(512L * 1024 * 1024, steamStorageBytes("Storage: 512 MB available space"))
        assertEquals(1000L * 1024 * 1024 * 1024, steamStorageBytes("Storage: 1,000 GB available space"))
        assertEquals(1536L * 1024 * 1024, steamStorageBytes("Storage: 1,5 GB"))
        assertNull(steamStorageBytes("Memory: 16 GB RAM; Graphics: 4 GB"))
        assertNull(steamStorageBytes("Storage: TBD"))
    }
    @Test fun storeLinksRejectOtherHostsAndUnsafeSchemes() {
        assertEquals("https://www.gog.com/game/game", storePageUrl("GOG", "/game/game"))
        assertNull(storePageUrl("GOG", "https://example.com/game"))
        assertNull(storePageUrl("GOG", "https://www.gog.com@evil.com/game"))
        assertNull(storePageUrl("GOG", "javascript:alert(1)"))
        assertNull(storePageUrl("GOG", ""))
        assertNull(storePageUrl("GOG", "null"))
    }
    @Test fun steamWindowsStorageAndLinksSurviveCacheRoundTrip() {
        val steam = PageDetailsParser.steam(JSONObject("""{"pc_requirements":{"minimum":"Storage: 50 GB available space"},"mac_requirements":{"minimum":"Storage: 30 GB available space"}}"""))
        assertEquals(50L * 1024 * 1024 * 1024, steam.steamStorageBytes)
        val gog = PageDetailsParser.gog(JSONObject("""{"_links":{"store":{"href":"https://www.gog.com/game/example"}}}"""))
        val merged = mergeGameDetails(listOf(steam, gog))
        assertEquals("https://www.gog.com/game/example", merged.storeLinks["GOG"])
        assertEquals(merged, GameDetails.read(JSONObject(merged.json().toString())))
    }
    @Test fun epicProductSlugBecomesAStorePage() {
        val details = PageDetailsParser.epic(JSONObject("""{"customAttributes":{"com.epicgames.app.productSlug":{"value":"example/home"}}}"""))
        assertEquals("https://store.epicgames.com/en-US/p/example", details.storeLinks["Epic Games"])
        assertTrue(PageDetailsParser.epic(JSONObject()).storeLinks.isEmpty())
    }
}
