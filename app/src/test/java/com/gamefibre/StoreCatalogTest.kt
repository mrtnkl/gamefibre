package com.gamefibre

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class StoreCatalogTest {
    @Test fun artworkRefreshBatchesRequestsAndSkipsUnavailableAndUnrequestedRecords() {
        var calls = 0
        val api = StoreCatalog { url ->
            calls++
            val input = JSONObject(URLDecoder.decode(URI(url).rawQuery.substringAfter("input_json="), "UTF-8"))
            assertEquals(listOf(620, 621, 622), (0 until input.getJSONArray("ids").length()).map {
                input.getJSONArray("ids").getJSONObject(it).getInt("appid")
            })
            JSONObject().put("response", JSONObject().put("store_items", JSONArray()
                .put(steamItem(620)).put(steamItem(621, 4)).put(steamItem(622).put("success", 2)).put(steamItem(999))))
        }
        val urls = api.steamArtworks(listOf(620, 621, 622))
        assertEquals(1, calls)
        assertEquals(setOf(620, 621), urls.keys)
        assertTrue(urls.getValue(620).portrait.endsWith("620/hash/library_capsule.jpg?t=1"))
    }
    private fun steamItem(id: Int, type: Int = 0) = JSONObject().put("appid", id).put("success", 1)
        .put("type", type).put("name", "Game $id").put("assets", JSONObject()
            .put("asset_url_format", "steam/apps/$id/\${FILENAME}?t=1").put("library_capsule", "hash/library_capsule.jpg"))

    @Test fun steamSearchKeepsAppIdsAndSkipsPackagesAndInvalidRecords() {
        val data = JSONObject().put("items", JSONArray()
            .put(JSONObject().put("id", 620).put("name", "Portal 2").put("type", "app"))
            .put(JSONObject().put("id", 1).put("name", "A package").put("type", "sub"))
            .put(JSONObject().put("id", 0).put("name", "Invalid").put("type", "app")))
        val game = StoreCatalog.steamSearch(data).single()
        assertEquals("STEAM:620", game.key)
        assertEquals("https://store.steampowered.com/app/620/", game.details.storeLinks["Steam"])
    }

    @Test fun gogSearchRetainsDirectStorePageAndPortraitAndExcludesDlc() {
        val data = JSONObject().put("products", JSONArray().put(JSONObject().put("id", "123").put("title", "Game")
            .put("productType", "game").put("coverVertical", "https://images.gog-statics.com/art.jpg")
            .put("storeLink", "https://www.gog.com/en/game/game").put("releaseDate", "2026.10.30"))
            .put(JSONObject().put("id", "456").put("title", "DLC").put("productType", "dlc")))
        val game = StoreCatalog.gogSearch(data).single()
        assertEquals("GOG:123", game.key)
        assertEquals("2026-10-30", game.details.release)
        assertEquals("https://images.gog-statics.com/art.jpg", game.details.portrait)
    }

    @Test fun xboxSearchDoesNotTreatACatalogResultAsOwnedOrGamePassAccess() {
        val data = JSONObject().put("Products", JSONArray().put(JSONObject().put("ProductId", "9PNG83MDD6SH")
            .put("ProductType", "Game").put("LocalizedProperties", JSONArray().put(JSONObject().put("ProductTitle", "Game")))))
        val game = StoreCatalog.xboxSearch(data).single()
        assertEquals("XBOX:9PNG83MDD6SH", game.key)
        assertNull(game.remote!!.xboxAccess)
    }

    @Test fun xboxStoreLinksAndProductIdsResolveDirectlyIncludingTitlesMissingFromSearch() {
        val inputs = listOf("BZ8TNLDZHNQR", "bz8tnldzhnqr",
            "https://www.xbox.com/en-US/games/store/syndicate/BZ8TNLDZHNQR",
            "https://www.xbox.com/en-US/games/store/syndicate/bz8tnldzhnqr/?source=collection")
        inputs.forEach { input ->
            val api = StoreCatalog { url ->
                assertEquals("https://displaycatalog.mp.microsoft.com/v7.0/products?bigIds=BZ8TNLDZHNQR&market=US&languages=en-US", url)
                JSONObject().put("Products", JSONArray().put(JSONObject().put("ProductId", "BZ8TNLDZHNQR")
                    .put("ProductType", "Game").put("LocalizedProperties", JSONArray().put(JSONObject()
                        .put("ProductTitle", "Syndicate").put("Images", JSONArray().put(JSONObject()
                            .put("ImagePurpose", "BoxArt").put("Uri", "//store-images.s-microsoft.com/cover.jpg")))))))
            }
            val game = api.search(LibraryStore.XBOX, input).single()
            assertEquals("Syndicate", game.name)
            assertEquals("XBOX:BZ8TNLDZHNQR", game.key)
            assertEquals("https://store-images.s-microsoft.com/cover.jpg", game.details.portrait)
            assertNull(game.remote!!.xboxAccess)
        }
    }

    @Test fun xboxLookupNeverRequestsAPastedExternalAddress() {
        val api = StoreCatalog(xboxTitles = { query ->
            assertTrue(query.startsWith("https://www.xbox.com.evil.test/"))
            JSONObject().put("channels", JSONObject().put("games", JSONObject().put("products", JSONArray())))
        }, request = { error("Empty title results should not request catalog details") })
        assertTrue(api.search(LibraryStore.XBOX, "https://www.xbox.com.evil.test/games/store/syndicate/BZ8TNLDZHNQR").isEmpty())
    }

    @Test fun xboxTitleSearchFindsSyndicateAndPreservesRankingWithoutDuplicatesOrUnrequestedGames() {
        val api = StoreCatalog(xboxTitles = { query ->
            assertEquals("Syndicate", query)
            JSONObject().put("channels", JSONObject().put("games", JSONObject().put("products", JSONArray()
                .put(JSONObject().put("productId", "BZ8TNLDZHNQR"))
                .put(JSONObject().put("productId", "9PHPDGTRVGGL"))
                .put(JSONObject().put("productId", "BZ8TNLDZHNQR"))
                .put(JSONObject().put("productId", "bad")))))
        }, request = { url ->
            assertTrue(url.contains("bigIds=BZ8TNLDZHNQR,9PHPDGTRVGGL"))
            fun product(id: String, name: String) = JSONObject().put("ProductId", id).put("ProductType", "Game")
                .put("LocalizedProperties", JSONArray().put(JSONObject().put("ProductTitle", name)))
            JSONObject().put("Products", JSONArray()
                .put(product("9PHPDGTRVGGL", "Assassin's Creed Syndicate"))
                .put(product("BZ8TNLDZHNQR", "Syndicate"))
                .put(product("9NBLGGH4R315", "Unrequested")))
        })
        val games = api.search(LibraryStore.XBOX, "Syndicate")
        assertEquals(listOf("Syndicate", "Assassin's Creed Syndicate"), games.map { it.name })
        assertTrue(games.all { it.remote!!.xboxAccess == null })
    }

    @Test fun steamDetailsResolveActualAssetPathsAndExcludeDlcAndUnavailableItems() {
        val games = StoreCatalog.steamItems(JSONObject().put("response", JSONObject().put("store_items", JSONArray()
            .put(steamItem(620)).put(steamItem(621, 4)).put(steamItem(622).put("success", 2)))))
        assertEquals(listOf("STEAM:620"), games.map { it.key })
        assertEquals("https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/620/hash/library_capsule.jpg?t=1", games.single().artwork)
    }

    @Test fun importedSteamArtworkPreservesSeparateHashedListAndPortraitPaths() {
        val row = steamItem(2215200).put("assets", JSONObject()
            .put("asset_url_format", "steam/apps/2215200/\${FILENAME}?t=1789746326")
            .put("small_capsule", "landscape-hash/capsule_231x87.jpg")
            .put("library_capsule", "portrait-hash/library_capsule.jpg"))
        val game = StoreCatalog.steamItems(JSONObject().put("response", JSONObject()
            .put("store_items", JSONArray().put(row)))).single()
        val saved = WishlistItems.read(JSONArray().put(WishlistItems.json(game))).single()
        val base = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/2215200/"
        assertEquals(base + "landscape-hash/capsule_231x87.jpg?t=1789746326", saved.artwork)
        assertEquals(base + "portrait-hash/library_capsule.jpg?t=1789746326", saved.details.portrait)
        assertEquals(game.key, saved.key)
    }

    @Test fun searchEncodesUserInputAsAQueryParameter() {
        var url = ""
        StoreCatalog { url = it; JSONObject().put("items", JSONArray()) }.search(LibraryStore.STEAM, "a&cc=other + game")
        val uri = URI(url)
        assertEquals("store.steampowered.com", uri.host)
        assertTrue(uri.rawQuery.contains("term=a%26cc%3Dother+%2B+game"))
        assertTrue(uri.rawQuery.endsWith("cc=US"))
    }

    @Test fun steamImportBatchesDeduplicatesAndReportsUnavailableGames() = runBlocking {
        val calls = mutableListOf<String>()
        val result = StoreCatalog { url ->
            calls.add(url)
            if (url.contains("IWishlistService")) JSONObject().put("response", JSONObject().put("items", JSONArray()
                .put(JSONObject().put("appid", 620)).put(JSONObject().put("appid", 621)).put(JSONObject().put("appid", 620))))
            else {
                val input = JSONObject(URLDecoder.decode(URI(url).rawQuery.substringAfter("input_json="), "UTF-8"))
                assertEquals(2, input.getJSONArray("ids").length())
                JSONObject().put("response", JSONObject().put("store_items", JSONArray().put(steamItem(620)).put(steamItem(621, 4))))
            }
        }.steamWishlist("76561198000000000")
        assertEquals(2, calls.size)
        assertEquals(listOf("STEAM:620"), result.games.map { it.key })
        assertEquals(1, result.unavailable)
    }

    @Test fun failedSteamImportReturnsNoPartialResult() = runBlocking {
        val api = StoreCatalog { url ->
            if (url.contains("IWishlistService")) JSONObject().put("response", JSONObject().put("items",
                JSONArray((1..51).map { JSONObject().put("appid", it) })))
            else if (URLDecoder.decode(url, "UTF-8").contains("\"appid\":51")) error("Network failure")
            else JSONObject().put("response", JSONObject().put("store_items", JSONArray().put(steamItem(1))))
        }
        try { api.steamWishlist("76561198000000000"); fail("Import should fail") }
        catch (expected: IllegalStateException) { assertEquals("Network failure", expected.message) }
    }
}
