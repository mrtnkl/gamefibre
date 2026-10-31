package com.gamefibre

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class YouTubeSearchTest {
    @Test fun queriesInsertActualGameNamesAndKeepLineOrder() {
        val lines = YouTubeQueries.lines("  longplay no commentary \n\n gameranx before you buy\r\nreview  ")
        assertEquals(listOf("Baldur's Gate 3 longplay no commentary", "Baldur's Gate 3 gameranx before you buy",
            "Baldur's Gate 3 review"), lines.map { YouTubeQueries.resolve(it, "Baldur's Gate 3") })
        assertTrue(YouTubeQueries.lines(" \n\t\r\n").isEmpty())
    }

    @Test fun searchUrlsEncodeTheWholeQuery() {
        assertEquals("https://www.youtube.com/results?search_query=Ratchet+%26+Clank+%2B+review&hl=en",
            YouTubeQueries.searchUrl("Ratchet & Clank + review"))
    }

    @Test fun choosesTheFirstOrganicRecordedVideoAndCombinesTitleRuns() {
        val live = video("LIVE0000000", "Live").apply { getJSONObject("videoRenderer").remove("lengthText") }
        val invalid = video("invalid/id", "Invalid")
        val ad = JSONObject().put("adSlotRenderer", video("AD000000000", "Advertisement"))
        val playlist = JSONObject().put("playlistRenderer", JSONObject().put("videoId", "LIST0000000"))
        val first = video("abc123DEF_-", "Portal 2 \"longplay\" {full}")
        first.getJSONObject("videoRenderer").getJSONObject("title").getJSONArray("runs").put(JSONObject().put("text", " & extras"))
        assertEquals(YouTubeVideo("abc123DEF_-", "Portal 2 \"longplay\" {full} & extras"),
            firstYouTubeVideo(page(ad, live, invalid, playlist, first, video("SECOND00000", "Second"))))
    }

    @Test fun validEmptyResultsAreDifferentFromAnUnreadableSearch() {
        assertNull(firstYouTubeVideo(page()))
    }

    @Test fun skipsUnrelatedRecommendationsAndDifferentSequelNumbers() {
        val html = page(video("WRONG000000", "Mortal Shell 2 - Before You Buy"),
            video("WRONG000001", "Portal 20 review"), video("MATCH000000", "PORTAL 2: Review"))
        assertEquals(YouTubeVideo("MATCH000000", "PORTAL 2: Review"), firstYouTubeVideo(html, "Portal 2"))
        assertNull(firstYouTubeVideo(html, "Portal 3"))
    }

    @Test(expected = IOException::class) fun consentPageIsAnError() {
        firstYouTubeVideo("<html>Please accept cookies before continuing to YouTube</html>")
    }

    @Test(expected = IOException::class) fun unknownSearchStructureIsAnError() {
        firstYouTubeVideo("<script>var ytInitialData = {};</script>")
    }

    private fun video(id: String, title: String) = JSONObject().put("videoRenderer", JSONObject()
        .put("videoId", id).put("lengthText", JSONObject().put("simpleText", "1:30:00"))
        .put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", title)))))

    private fun page(vararg videos: JSONObject): String {
        val data = JSONObject().put("contents", JSONObject().put("twoColumnSearchResultsRenderer", JSONObject()
            .put("primaryContents", JSONObject().put("sectionListRenderer", JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("itemSectionRenderer", JSONObject()
                    .put("contents", JSONArray(videos.toList())))))))))
        return "<script>var ytInitialData = $data;</script><script>unrelated();</script>"
    }
}
