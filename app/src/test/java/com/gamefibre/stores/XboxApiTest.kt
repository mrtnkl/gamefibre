package com.gamefibre.stores

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XboxApiTest {
    private val session = StoreSession("store-access", "refresh", "12345", Long.MAX_VALUE)
    private fun tokens(owner: String = "12345", refresh: Boolean = true): (XboxRequest) -> JSONObject = { request ->
        when (java.net.URI(request.url).host) {
            "login.live.com" -> JSONObject().put("access_token", "t=live-access").apply { if (refresh) put("refresh_token", "rotated-refresh") }
            "user.auth.xboxlive.com" -> JSONObject().put("Token", "user-token")
            "xsts.auth.xboxlive.com" -> JSONObject().put("Token", "xsts-token").put("NotAfter", "2099-01-01T00:00:00Z")
                .put("DisplayClaims", JSONObject().put("xui", JSONArray().put(JSONObject().put("xid", owner).put("uhs", "userhash"))))
            else -> error("Unexpected request")
        }
    }

    @Test fun oauthCallbacksRequireTheExactMicrosoftRedirectAndRequestState() {
        val base = "https://login.live.com/oauth20_desktop.srf"
        assertEquals("code+value", xboxOAuthCode("$base?code=code%2Bvalue&state=expected", "expected"))
        assertNull(xboxOAuthCode("https://login.live.com.evil.example/oauth20_desktop.srf?code=code&state=expected", "expected"))
        assertNull(xboxOAuthCode("http://login.live.com/oauth20_desktop.srf?code=code&state=expected", "expected"))
        assertNull(xboxOAuthCode("https://user@login.live.com/oauth20_desktop.srf?code=code&state=expected", "expected"))
        for (query in listOf("code=code&state=wrong", "code=code", "code=code&state=expected&state=expected",
            "code=code&code=other&state=expected", "error=access_denied&state=expected")) {
            assertThrows(IllegalArgumentException::class.java) { xboxOAuthCode("$base?$query", "expected") }
        }
    }

    @Test fun exchangesMicrosoftCodeForAnAccountBoundXboxSession() {
        val requests = mutableListOf<XboxRequest>()
        val api = XboxApi { request -> requests.add(request); tokens()(request) }
        val result = api.token("authorization_code", "a+code")
        assertEquals("12345", result.owner)
        assertEquals("rotated-refresh", result.refreshToken)
        assertEquals("t=live-access", result.accessToken)
        assertTrue(requests[0].body!!.contains("code=a%2Bcode"))
        assertEquals("application/x-www-form-urlencoded", requests[0].contentType)
        val properties = JSONObject(requests[1].body!!).getJSONObject("Properties")
        assertEquals("t=live-access", properties.getString("RpsTicket"))
        assertEquals("http://xboxlive.com", JSONObject(requests[2].body!!).getString("RelyingParty"))
    }

    @Test fun microsoftAuthorizationCodesAreOpaqueAndEncodedOnlyForTransport() {
        val opaque = "M.code/with+symbols=*and:punctuation"
        assertTrue(validXboxAuthorizationCode(opaque))
        assertTrue(validXboxAuthorizationCode("a".repeat(5000)))
        assertFalse(validXboxAuthorizationCode("short\ncode"))
        assertFalse(validXboxAuthorizationCode("a".repeat(16385)))
        val url = "https://login.live.com/oauth20_desktop.srf?state=expected&code=" + java.net.URLEncoder.encode(opaque, "UTF-8")
        assertEquals(opaque, xboxOAuthCode(url, "expected"))
        val requests = mutableListOf<XboxRequest>()
        XboxApi { request -> requests.add(request); tokens()(request) }.token("authorization_code", opaque)
        assertTrue(requests[0].body!!.contains("code=" + java.net.URLEncoder.encode(opaque, "UTF-8")))
    }

    @Test fun callbackErrorsExplainTheFailureWithoutExposingCodesOrProviderDescriptions() {
        val base = "https://login.live.com/oauth20_desktop.srf"
        fun failure(query: String) = assertThrows(XboxSignInException::class.java) { xboxOAuthCode("$base?$query", "expected") }
        assertEquals("missing_state", failure("code=secret-code").reason)
        assertEquals("state_mismatch", failure("code=secret-code&state=wrong").reason)
        assertEquals("missing_code", failure("state=expected").reason)
        val refused = failure("error=unsupported_response_type&error_description=private-account-details")
        assertEquals("unsupported_response_type", refused.reason)
        assertFalse(refused.message!!.contains("private-account-details"))
        assertFalse(failure("code=secret-code&state=wrong").message!!.contains("secret-code"))
    }

    @Test(expected = IllegalStateException::class) fun refreshingCannotSilentlySwitchAccounts() {
        XboxApi(tokens(owner = "99999")).token("refresh_token", "old-refresh", "12345")
    }

    @Test fun unchangedRefreshTokensRemainReusableWhenMicrosoftDoesNotRotateThem() {
        assertEquals("old-refresh", XboxApi(tokens(refresh = false)).token("refresh_token", "old-refresh", "12345").refreshToken)
    }

    @Test(expected = IllegalStateException::class) fun firstSignInRequiresAReusableRefreshToken() {
        XboxApi(tokens(refresh = false)).token("authorization_code", "a-code")
    }

    @Test fun collectionTokensRemainOpaqueOnSignInAndRefresh() {
        for (grant in listOf("authorization_code", "refresh_token")) {
            for (token in listOf("opaque+token/with=padding", "t=opaque+token/with=padding", "d=opaque+token/with=padding")) {
                val requests = mutableListOf<XboxRequest>()
                val api = XboxApi { request ->
                    requests.add(request)
                    if (requests.size == 4) JSONObject().put("access_token", token).put("refresh_token", "store-refresh").put("expires_in", 3600)
                    else tokens()(request)
                }
                val result = api.token(grant, "credential", "12345")
                assertEquals(token, result.accessToken)
                assertEquals("store-refresh", result.refreshToken)
                assertTrue(requests[3].body!!.contains("scope=service%3A%3Acollections.mp.microsoft.com%3A%3AMBI_SSL"))
                assertTrue(requests[3].body!!.contains("refresh_token=rotated-refresh"))
                assertTrue(result.expiresAt > System.currentTimeMillis())
                assertTrue(result.expiresAt <= System.currentTimeMillis() + 3600_000)
            }
        }
    }

    @Test fun invalidStoreTokensAreRejectedBeforeTheyCanBeUsedAsHeaders() {
        for (token in listOf("", "with space", "with\r\nheader", JSONObject.NULL, 123)) {
            var calls = 0
            val api = XboxApi { request ->
                if (++calls == 4) JSONObject().put("access_token", token) else tokens()(request)
            }
            assertThrows(IllegalStateException::class.java) { api.token("authorization_code", "code") }
        }
    }

    private fun entitlement(id: String = "9PNG83MDD6SH") = JSONObject().put("productId", id)
        .put("status", "Active").put("skuType", "Full").put("ownershipType", "OwnedByBeneficiary")
        .put("endDate", "9999-12-31T23:59:59.9999999+00:00")

    private fun product(id: String, name: String) = JSONObject().put("ProductId", id).put("ProductType", "Game")
        .put("LocalizedProperties", JSONArray().put(JSONObject().put("ProductTitle", name)))

    @Test fun importsAllEntitlementPagesAndKeepsPurchasesSeparateFromGamePass() = runBlocking {
        val requests = mutableListOf<XboxRequest>()
        var pages = 0
        val api = XboxApi { request ->
            requests.add(request)
            when (java.net.URI(request.url).host) {
                "collections.mp.microsoft.com" -> if (++pages == 1)
                    JSONObject().put("items", JSONArray().put(entitlement())).put("continuationToken", "next+page")
                    else JSONObject().put("items", JSONArray().put(entitlement()).put(entitlement("9NBLGGH42CFD")))
                "catalog.gamepass.com" -> JSONObject().put("items", JSONArray().put(JSONObject().put("id", "9NBLGGH4R315")))
                "displaycatalog.mp.microsoft.com" -> JSONObject().put("Products", JSONArray()
                    .put(product("9PNG83MDD6SH", "PC purchase")).put(product("9NBLGGH42CFD", "Console purchase"))
                    .put(product("9NBLGGH4R315", "Game Pass")))
                else -> error("Unexpected request")
            }
        }
        val games = api.library(session)
        assertEquals(3, games.size)
        assertEquals(XboxAccess.OWNED, games.single { it.id == "9PNG83MDD6SH" }.xboxAccess)
        assertEquals(XboxAccess.GAME_PASS, games.single { it.id == "9NBLGGH4R315" }.xboxAccess)
        assertEquals(XboxAccess.OWNED, games.single { it.id == "9NBLGGH42CFD" }.xboxAccess)
        assertEquals("next+page", JSONObject(requests[1].body!!).getString("continuationToken"))
        for (request in requests.filter { java.net.URI(it.url).host == "collections.mp.microsoft.com" }) {
            val body = JSONObject(request.body!!)
            assertEquals("store-access", body.getJSONArray("beneficiaries").getJSONObject(0).getString("identityValue"))
            assertTrue(body.getJSONArray("productTypes").toString().contains("Game"))
        }
    }

    @Test(expected = IllegalStateException::class) fun repeatedContinuationFailsInsteadOfReplacingTheCacheWithAPartialLibrary(): Unit = runBlocking {
        XboxApi { JSONObject().put("items", JSONArray()).put("continuationToken", "same") }.library(session)
        Unit
    }

    @Test(expected = org.json.JSONException::class) fun malformedLibraryIsAnErrorRatherThanAnEmptyLibrary(): Unit = runBlocking {
        XboxApi { JSONObject() }.library(session)
        Unit
    }

    private fun platformSku(vararg platforms: String) = JSONObject().put("Sku", JSONObject()
        .put("Properties", JSONObject().put("Packages", JSONArray(platforms.map { platform ->
            JSONObject().put("PlatformDependencies", JSONArray().put(JSONObject().put("PlatformName", platform)))
        }))))

    @Test fun sameNamePcAndConsoleProductsKeepTheirOwnPlatforms() {
        val pc = product("9NKGNMNK3K3Z", "Fallout 76").put("DisplaySkuAvailabilities",
            JSONArray().put(platformSku("Windows.Desktop")))
        val console = product("BS6WJ2L56B10", "Fallout 76").put("DisplaySkuAvailabilities",
            JSONArray().put(platformSku("Windows.Xbox", "Windows.Xbox")))
        val games = listOf(pc, console).map { xboxCatalogGame(it, XboxAccess.OWNED)!! }
        assertEquals(games[0].name, games[1].name)
        assertNotEquals(games[0].id, games[1].id)
        assertEquals(listOf("PC"), games[0].details.platforms)
        assertEquals(listOf("Xbox"), games[1].details.platforms)
    }

    @Test fun crossPlatformProductCombinesPackagesAndSkusWithoutDuplicates() {
        val game = product("9PNG83MDD6SH", "Cross platform game").put("DisplaySkuAvailabilities", JSONArray()
            .put(platformSku("Windows.Xbox")).put(platformSku("Windows.Desktop", "Windows.Xbox")))
        for (access in XboxAccess.entries) {
            assertEquals(listOf("PC", "Xbox"), xboxCatalogGame(game, access)!!.details.platforms)
        }
    }

    @Test fun storePurchasePlatformsNeverClaimAPlayablePcOrXboxVersion() {
        val sku = platformSku("Windows.Xbox").put("Availabilities", JSONArray().put(JSONObject()
            .put("Conditions", JSONObject().put("ClientConditions", JSONObject()
                .put("AllowedPlatforms", JSONArray().put(JSONObject().put("PlatformName", "Windows.Desktop")))))))
        val game = product("9PNG83MDD6SH", "Console game").put("DisplaySkuAvailabilities", JSONArray().put(sku))
        assertEquals(listOf("Xbox"), xboxProductPlatforms(game))
        assertTrue(xboxProductPlatforms(JSONObject()).isEmpty())
        assertTrue(xboxProductPlatforms(JSONObject().put("DisplaySkuAvailabilities", JSONArray()
            .put(JSONObject.NULL).put(platformSku("Windows.Mobile", "Windows.Universal", "Unknown")))).isEmpty())
    }

    @Test fun purchasesExcludeExpiredRevokedTrialSharedAndSubscriptionAccess() {
        val now = java.time.Instant.parse("2026-10-29T12:00:00Z")
        assertEquals(setOf("9PNG83MDD6SH"), xboxPurchasedProducts(listOf(entitlement(), entitlement()), now))
        for ((key, value) in listOf("status" to "Revoked", "status" to "Expired", "skuType" to "Trial",
            "ownershipType" to "Shared", "endDate" to "2027-01-01T00:00:00Z", "endDate" to "invalid",
            "productId" to "invalid")) {
            assertTrue("$key=$value", xboxPurchasedProducts(listOf(entitlement().put(key, value)), now).isEmpty())
        }
    }

    @Test(expected = IllegalArgumentException::class) fun xboxNeverGrantsFileDownloadAccess() {
        StoreDownloadRequest(GameStore.XBOX, StoreGame("123", "Game"), "12345", "windows", "en", "zip")
    }
}
