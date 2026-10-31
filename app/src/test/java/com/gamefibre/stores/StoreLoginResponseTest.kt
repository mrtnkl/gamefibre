package com.gamefibre.stores

import org.junit.Assert.*
import org.junit.Test

class StoreLoginResponseTest {
    private val gog = "https://embed.gog.com/on_login_success"
    private val epic = "https://www.epicgames.com/id/api/redirect"

    @Test fun capturesGogRedirectWithoutLoadingItsPage() {
        assertEquals("new-code", StoreLoginResponse.codeFromUrl(GameStore.GOG, "$gog?origin=client&code=new%2Dcode"))
        assertNull(StoreLoginResponse.codeFromUrl(GameStore.GOG, "https://login.gog.com/auth?code=not-a-callback"))
    }

    @Test fun capturesEpicUrlAndBothFinalJsonResponseShapes() {
        assertEquals("new-code", StoreLoginResponse.codeFromUrl(GameStore.EPIC, "$epic?authorizationCode=new-code"))
        assertNull(StoreLoginResponse.codeFromUrl(GameStore.EPIC, "$epic?clientId=launcher&responseType=code"))
        assertEquals("new-code", StoreLoginResponse.epicPageCode(epic, """{"authorizationCode":"new-code"}"""))
        assertEquals("new-code", StoreLoginResponse.epicPageCode(epic, """{"code":"new-code"}"""))
        assertEquals("new-code", StoreLoginResponse.epicPageCode(epic, """{"authorizationCode":null,"redirectUrl":"com.epicgames.launcher://authorize?code=new-code"}"""))
    }

    @Test fun ignoresLookalikesAndOtherOrigins() {
        for ((store, base) in listOf(GameStore.GOG to gog, GameStore.EPIC to epic)) {
            for (url in listOf(base.replace("https:", "http:"), base.replace(".com", ".com.evil.test"),
                base.replace("https://", "https://user@"), base.replace(".com/", ".com:443/"),
                "$base/extra", "$base#fragment", base.replace("/id/", "/%69d/").replace("/on_login_success", "/%6fn_login_success"))) {
                assertFalse(url, StoreLoginResponse.isCallback(store, url))
                assertNull(StoreLoginResponse.codeFromUrl(store, "$url?code=new-code"))
                assertNull(StoreLoginResponse.epicPageCode(url, """{"authorizationCode":"new-code"}"""))
            }
        }
        assertNull(StoreLoginResponse.codeFromUrl(GameStore.GOG, "$epic?code=new-code"))
        assertNull(StoreLoginResponse.codeFromUrl(GameStore.EPIC, "$gog?code=new-code"))
    }

    @Test fun rejectsDeclinedMalformedDuplicateAndOversizedResponses() {
        for (query in listOf("error=access_denied", "code=", "code=bad%20code", "code=first&code=second", "code=first&authorizationCode=second")) {
            assertThrows(IllegalArgumentException::class.java) { StoreLoginResponse.codeFromUrl(GameStore.GOG, "$gog?$query") }
        }
        assertNull(StoreLoginResponse.epicPageCode(epic, "Not JSON"))
        assertNull(StoreLoginResponse.epicPageCode(epic, "{}"))
        for (body in listOf("""{"errorCode":"corrective_action_required"}""", """{"authorizationCode":"bad code"}""",
            """{"redirectUrl":"https://example.test/?code=first&code=second"}""", "x".repeat(16385))) {
            assertThrows(IllegalArgumentException::class.java) { StoreLoginResponse.epicPageCode(epic, body) }
        }
    }
}
