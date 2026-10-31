package com.gamefibre

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GameDetailsTest {
    @Test fun descriptionBannersDoNotHideTheOpeningTextBehindImagePlaceholders() {
        val html = """<p><img src="https://example.com/banner.jpg"></p><h2>Night City</h2><p>Become a mercenary.</p><img src="https://example.com/footer.gif" />"""
        assertEquals("<p></p><h2>Night City</h2><p>Become a mercenary.</p>", descriptionHtmlWithoutImages(html))
    }
    @Test fun descriptionImagesCanHaveMultilineAndQuotedAttributes() {
        val html = """<IMG
            alt='Night > City' src="https://example.com/banner.jpg"/><p>Story &amp; choices.</p>"""
        assertEquals("<p>Story &amp; choices.</p>", descriptionHtmlWithoutImages(html))
    }
    @Test fun optionalDetailsSurviveOfflineCacheRoundTrip() {
        val details = GameDetails("Description", "Developer", "Publisher", "2026-10-28", "Adventure",
            "https://example.com/cover.jpg", listOf("https://example.com/screenshot.jpg"), listOf("Single-player"), metascore = 89, platforms = listOf("PC", "Xbox"))
        assertEquals(details, GameDetails.read(JSONObject(details.json().toString())))
    }
    @Test fun olderCachesAndNullFieldsProduceNoPlaceholderText() {
        assertEquals(GameDetails(), GameDetails.read(null))
        assertEquals(GameDetails(), GameDetails.read(JSONObject("""{"developer":null,"publisher":"null","description":{}}""")))
    }
    @Test fun mergedPlatformsSurviveMissingPageMetadataAndDeduplicateAcrossCopies() {
        val merged = mergeGameDetails(listOf(GameDetails(platforms = listOf("Xbox")),
            GameDetails(platforms = listOf("PC", "Xbox")), GameDetails()))
        assertEquals(listOf("PC", "Xbox"), merged.platforms)
        assertEquals(merged.platforms, GameDetails.read(merged.json()).platforms)
        assertTrue(mergeGameDetails(listOf(GameDetails())).platforms.isEmpty())
    }
    @Test fun missingAndInvalidMetascoresStayUnknown() {
        for (value in listOf(JSONObject.NULL, 0, -1, 101, "unknown")) {
            assertNull(GameDetails.read(JSONObject().put("metascore", value)).metascore)
        }
        assertNull(GameDetails.read(GameDetails().json()).metascore)
    }
    @Test fun freshPageScoreOverridesLibraryScoreAndMissingScoresPreserveIt() {
        val library = GameDetails(metascore = 88)
        assertEquals(91, mergeGameDetails(listOf(library, GameDetails(metascore = 91))).metascore)
        assertEquals(88, mergeGameDetails(listOf(library, GameDetails())).metascore)
    }
    @Test fun mergedPageKeepsTheRichestFieldsAndAllScreenshotsFromEveryCopy() {
        val steam = GameDetails(developer = "Yak &amp; Co", publisher = "Publisher", release = "2019-08-29")
        val epic = GameDetails(description = "A longer description with the game's story", developer = "Yak & Co",
            screenshots = listOf("https://example.com/one.jpg", "https://example.com/two.jpg"), tags = listOf("Adventure"))
        val gog = GameDetails(description = "Short", category = "Puzzle", screenshots = listOf("https://example.com/one.jpg"), tags = listOf("adventure", "Single-player"))
        val merged = mergeGameDetails(listOf(steam, epic, gog))
        assertEquals(epic.description, merged.description)
        assertEquals(steam.developer, merged.developer)
        assertEquals("Publisher", merged.publisher)
        assertEquals("2019-08-29", merged.release)
        assertEquals("Puzzle", merged.category)
        assertEquals(epic.screenshots, merged.screenshots)
        assertEquals(listOf("Adventure", "Single-player"), merged.tags)
    }
}
