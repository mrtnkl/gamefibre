package com.gamefibre.steam

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class SessionRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun request() = DownloadRequest(10, "Game", "zip", "windows", "english", "76561198000000001")
    private fun token(issuer: String = "steam", audience: String = "client", expiry: Long = System.currentTimeMillis()/1000 + 3600): String {
        val claims = JSONObject().put("iss", issuer).put("aud", JSONArray().put(audience))
            .put("sub", request().ownerId).put("exp", expiry).toString()
        return "header.${Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray())}.signature"
    }

    @Test fun expiredTokensCanIdentifyOfflineCachesButCannotAuthenticate() {
        val expired = token(expiry=1)
        assertThrows(IllegalArgumentException::class.java) { tokenSteamId(expired) }
        assertEquals(request().ownerId.toLong(), tokenSteamId(expired, requireUnexpired=false))
        assertEquals(request().ownerId.toLong(), tokenSteamId(token()))
    }

    @Test fun tokenRoutingRejectsWrongIssuerAudienceAndMalformedTokens() {
        for (invalid in listOf(token(issuer="other"), token(audience="web"), "not-a-token")) {
            assertThrows(IllegalArgumentException::class.java) { tokenSteamId(invalid) }
        }
    }

    @Test fun processRecoveryPreservesOptionsAndRequiresOriginalAccount() {
        val store = DownloadCheckpointStore(temporary.newFile("checkpoint"))
        val saved = DownloadCheckpoint(request(), DownloadPhase.EXPORTING, "Exporting", readyToExport=true)
        store.save(saved)
        val recovered = store.load()!!.recover()
        assertEquals(saved.request, recovered.request)
        assertEquals(DownloadPhase.PAUSED, recovered.phase)
        assertTrue(recovered.readyToExport)
        recovered.request.requireOwner(request().ownerId)
        assertThrows(IllegalStateException::class.java) { recovered.request.requireOwner("76561198000000002") }
        store.clear()
        assertNull(store.load())
    }

    @Test fun pendingResumeIsForgottenOnSignOutAndCannotStartLater() {
        var started = 0
        var reconnects = 0
        val resume = ResumeAfterReconnect({ reconnects++ }, { started++ }, {})
        resume.request(request(), "signedOut", hasSavedSession=true)
        assertEquals(1, reconnects)
        resume.clear()
        resume.authenticated()
        assertNull(resume.pending)
        assertEquals(0, started)
    }

    @Test fun resumeJoinsExistingAuthenticationAndStartsOnlyOnce() {
        var started = 0
        val resume = ResumeAfterReconnect({ fail("Must join the existing connection") }, { started++ }, {})
        resume.request(request(), "restoring", hasSavedSession=true)
        resume.request(request(), "restoring", hasSavedSession=true)
        resume.authenticated(); resume.authenticated()
        assertEquals(1, started)
    }
}
