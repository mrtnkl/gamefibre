package com.gamefibre

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SteamGridDbTest {
    @Test fun onlyTrustedHttpsImagesAreAccepted() {
        assertTrue(SteamGridDb.imageUrl("https://cdn2.steamgriddb.com/grid/cover.png"))
        listOf("http://cdn2.steamgriddb.com/grid/a.png", "https://cdn2.steamgriddb.com.evil.com/a.png",
            "https://cdn2.steamgriddb.com@evil.com/a.png", "https://evil.com/a.png",
            "file:///a.png", "https://cdn2.steamgriddb.com:443/a.png", "invalid").forEach {
            assertFalse(it, SteamGridDb.imageUrl(it))
        }
    }
    @Test fun gridsFilterUnsafeAndExplicitRowsAndDeduplicate() {
        val url = "https://cdn2.steamgriddb.com/grid/a.png"
        val rows = JSONArray("""[
            {"id":1,"url":"$url","thumb":"https://evil.com/track","author":{"name":"Artist"}},
            {"id":2,"url":"$url"},
            {"id":3,"url":"https://evil.com/a.png"},
            {"id":4,"url":"https://cdn2.steamgriddb.com/grid/b.png","nsfw":true},
            {"id":5,"url":"https://cdn2.steamgriddb.com/grid/c.png","humor":true},
            null,{},"invalid"]""")
        val covers = SteamGridDb.parseGrids(rows)
        assertEquals(1, covers.size)
        assertEquals(url, covers.single().thumb)
        assertEquals("Artist", covers.single().author)
    }
    @Test fun searchRequiresSelectableGameIdsAndTitles() {
        val games = SteamGridDb.parseGames(JSONArray("""[
            {"id":123,"name":"Example"},{"id":0,"name":"Missing ID"},
            {"id":124,"name":""},null,{}]"""))
        assertEquals(listOf(GridGame(123, "Example")), games)
    }
}
