package com.gamefibre

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PageDetailsParserTest {
    @Test fun steamMetascoresUseTheCriticScoreAndIgnoreMissingOrInvalidValues() {
        assertEquals(96, PageDetailsParser.steam(JSONObject("""{"metacritic":{"score":96},"recommendations":{"total":5000}}""")).metascore)
        assertNull(PageDetailsParser.steam(JSONObject()).metascore)
        for (score in listOf(0, -1, 101)) {
            assertNull(PageDetailsParser.steam(JSONObject().put("metacritic", JSONObject().put("score", score))).metascore)
        }
    }
    @Test fun steamDetailsSupplyScreenshotsAndNormalizedProperties() {
        val data = JSONObject("""{"about_the_game":"Story", "developers":["Developer"], "publishers":["Publisher"],
            "release_date":{"date":"29 Aug, 2019"}, "genres":[{"description":"Adventure"}],
            "categories":[{"description":"Single-player"}],
            "screenshots":[{"path_thumbnail":"https://shared.akamai.steamstatic.com/one.jpg","path_full":"https://shared.akamai.steamstatic.com/full.jpg"}]}""")
        val result = PageDetailsParser.steam(data)
        assertEquals("Story", result.description)
        assertEquals("2019-08-29", result.release)
        assertEquals("Developer", result.developer)
        assertEquals("Publisher", result.publisher)
        assertEquals(listOf("https://shared.akamai.steamstatic.com/one.jpg"), result.screenshots)
        assertEquals(listOf("Single-player"), result.tags)
    }
    @Test fun gogScreenshotTemplatesResolveToAnImageAddress() {
        val data = JSONObject("""{"description":{"full":"Description"},"_embedded":{
            "developers":[{"name":"Developer"}], "publisher":{"name":"Publisher"},
            "screenshots":[{"_links":{"self":{"href":"https://images.gog-statics.com/hash_{formatter}.jpg"}}}]}}""")
        val result = PageDetailsParser.gog(data)
        assertEquals("Description", result.description)
        assertEquals("Publisher", result.publisher)
        assertEquals(listOf("https://images.gog-statics.com/hash_product_card_screenshot_748.jpg"), result.screenshots)
    }
    @Test fun epicGalleryDoesNotTreatBoxArtAsScreenshots() {
        val data = JSONObject("""{"description":"Description","keyImages":[
            {"type":"DieselGameBoxTall","url":"https://example.com/cover.jpg"},
            {"type":"featuredMedia","url":"https://example.com/screenshot.jpg"}],"tags":[{"id":"opaque"},{"name":"Adventure"}]}""")
        val result = PageDetailsParser.epic(data)
        assertEquals(listOf("https://example.com/screenshot.jpg"), result.screenshots)
        assertEquals(listOf("Adventure"), result.tags)
    }
}
