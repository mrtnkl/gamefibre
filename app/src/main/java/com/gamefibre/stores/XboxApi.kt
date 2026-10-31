package com.gamefibre.stores

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import com.gamefibre.Http
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.OffsetDateTime

internal data class XboxRequest(val url: String, val body: String? = null,
    val headers: Map<String, String> = emptyMap(), val contentType: String = "application/json")

internal class XboxSignInException(val reason: String, message: String) : IllegalArgumentException(message)

// Authorization codes are opaque. Encode them for transport rather than limiting them to an invented alphabet.
internal fun validXboxAuthorizationCode(code: String) = code.length in 4..16384 &&
    code.none { it.isISOControl() || it.isWhitespace() }

internal fun xboxOAuthCode(url: String, state: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host != "login.live.com" || uri.path != "/oauth20_desktop.srf" ||
        uri.userInfo != null || uri.port != -1) return null
    val parameters = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }.groupBy({ it.first }, { it.second })
    if (parameters["error"] != null) {
        val error = parameters["error"]?.singleOrNull()?.takeIf { it in setOf("access_denied", "invalid_client", "invalid_request",
            "invalid_scope", "unauthorized_client", "unsupported_response_type", "server_error", "temporarily_unavailable",
            "interaction_required", "login_required", "consent_required") } ?: "authorization_error"
        throw XboxSignInException(error, "Microsoft refused Xbox sign-in ($error).")
    }
    if (parameters["state"] == null) throw XboxSignInException("missing_state", "Microsoft returned no sign-in verification value. Close this window and start sign-in again.")
    if (state.isBlank() || parameters["state"]?.singleOrNull() != state) {
        throw XboxSignInException("state_mismatch", "Microsoft's response does not match this sign-in attempt. Close this window and start again.")
    }
    val code = parameters["code"]?.singleOrNull()
    if (code == null) throw XboxSignInException("missing_code", "Microsoft returned no unique authorization code. Close this window and start sign-in again.")
    if (!validXboxAuthorizationCode(code)) throw XboxSignInException("invalid_code", "Microsoft returned an invalid authorization code. Close this window and start sign-in again.")
    return code
}

/** Native Microsoft OAuth / Xbox Live protocol; only the refresh token is persisted by StoreTokenVault. */
internal class XboxApi(private val request: (XboxRequest) -> JSONObject = ::requestXboxJson) {
    companion object {
        // Microsoft's public Xbox application parameters, as documented by xboxlive-auth.
        const val CLIENT_ID = "000000004C12AE6F"
        const val REDIRECT = "https://login.live.com/oauth20_desktop.srf"
        const val SCOPE = "service::user.auth.xboxlive.com::MBI_SSL"
        private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun form(values: Map<String, String>) = values.entries.joinToString("&") { "${encoded(it.key)}=${encoded(it.value)}" }
        val loginUrl: String get() = "https://login.live.com/oauth20_authorize.srf?" + form(mapOf(
            "client_id" to CLIENT_ID, "response_type" to "code", "redirect_uri" to REDIRECT,
            "scope" to SCOPE, "prompt" to "select_account"))
    }

    fun token(grant: String, value: String, expectedOwner: String? = null): StoreSession {
        require(grant in setOf("authorization_code", "refresh_token"))
        val live = request(XboxRequest("https://login.live.com/oauth20_token.srf", form(mapOf(
            "client_id" to CLIENT_ID, "redirect_uri" to REDIRECT, "scope" to SCOPE,
            "grant_type" to grant, (if (grant == "refresh_token") "refresh_token" else "code") to value)),
            contentType = "application/x-www-form-urlencoded"))
        val refresh = live.optString("refresh_token").ifBlank { if (grant == "refresh_token") value else "" }
        check(refresh.isNotBlank()) { "Microsoft did not return a reusable session. Please sign in again." }
        val user = request(XboxRequest("https://user.auth.xboxlive.com/user/authenticate", JSONObject()
            .put("RelyingParty", "http://auth.xboxlive.com").put("TokenType", "JWT")
            .put("Properties", JSONObject().put("AuthMethod", "RPS").put("SiteName", "user.auth.xboxlive.com")
                .put("RpsTicket", live.getString("access_token"))).toString(), mapOf("x-xbl-contract-version" to "1")))
        val xsts = request(XboxRequest("https://xsts.auth.xboxlive.com/xsts/authorize", JSONObject()
            .put("RelyingParty", "http://xboxlive.com").put("TokenType", "JWT")
            .put("Properties", JSONObject().put("SandboxId", "RETAIL")
                .put("UserTokens", JSONArray().put(user.getString("Token")))).toString(), mapOf("x-xbl-contract-version" to "1")))
        val claims = xsts.getJSONObject("DisplayClaims").getJSONArray("xui")
        check(claims.length() == 1) { "Xbox returned an ambiguous account." }
        val owner = claims.getJSONObject(0).getString("xid")
        val hash = claims.getJSONObject(0).getString("uhs")
        check(owner.matches(Regex("[0-9]{1,20}")) && hash.matches(Regex("[A-Za-z0-9]{1,64}"))) { "Xbox returned an invalid account." }
        check(expectedOwner == null || expectedOwner == owner) { "Microsoft returned a different account. Sign out and connect it again." }
        val token = xsts.getString("Token")
        check(token.isNotBlank() && !token.any(Char::isWhitespace)) { "Xbox returned an invalid session." }
        val expiry = OffsetDateTime.parse(xsts.getString("NotAfter")).toInstant().toEpochMilli()
        check(expiry > System.currentTimeMillis()) { "Xbox returned an expired session. Please sign in again." }
        val store = request(XboxRequest("https://login.live.com/oauth20_token.srf", form(mapOf(
            "client_id" to CLIENT_ID, "scope" to "service::collections.mp.microsoft.com::MBI_SSL",
            "grant_type" to "refresh_token", "refresh_token" to refresh)), contentType = "application/x-www-form-urlencoded"))
        // Collections expects the opaque OAuth token in the beneficiary body, without an Authorization scheme.
        val storeToken = (store.opt("access_token") as? String).orEmpty()
        check(storeToken.isNotEmpty() && storeToken.none { it.isWhitespace() || it.isISOControl() }) {
            "Microsoft did not return a valid Store session. Sign in again."
        }
        val storeExpiry = System.currentTimeMillis() + store.optLong("expires_in", 300).coerceIn(1, 86400) * 1000
        return StoreSession(storeToken, store.optString("refresh_token").ifBlank { refresh }, owner, minOf(expiry, storeExpiry))
    }

    /** Purchase entitlements are authoritative; a history entry is never treated as proof of ownership. */
    suspend fun library(session: StoreSession): List<StoreGame> = coroutineScope {
        val market = java.util.Locale.getDefault().country.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "US"
        val rows = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        var continuation = ""
        var complete = false
        for (page in 0 until 100) {
            currentCoroutineContext().ensureActive()
            // Collections includes console and PC purchases. PCGAFD only exposes the PC library.
            val body = JSONObject().put("maxPageSize", 100).put("market", market)
                .put("productTypes", JSONArray(listOf("Game", "Durable", "Application", "Pass")))
                .put("beneficiaries", JSONArray().put(JSONObject().put("identitytype", "msa")
                    .put("identityValue", session.accessToken).put("localTicketReference", "1")))
            if (continuation.isNotEmpty()) body.put("continuationToken", continuation)
            val data = request(XboxRequest("https://collections.mp.microsoft.com/v7.0/collections/query", body.toString(),
                mapOf("MS-CV" to java.util.UUID.randomUUID().toString(), "Accept-Language" to "en-US")))
            val items = data.getJSONArray("items")
            check(rows.size + items.length() <= 10_000) { "Microsoft returned too many entitlements." }
            for (index in 0 until items.length()) rows.add(items.getJSONObject(index))
            continuation = (data.opt("continuationToken") as? String).orEmpty()
            if (continuation.isBlank()) { complete = true; break }
            check(continuation.length <= 4096 && continuation.none(Char::isISOControl) && seen.add(continuation)) { "Microsoft repeated an entitlement page." }
        }
        check(complete) { "Microsoft returned too many entitlement pages." }
        val purchased = xboxPurchasedProducts(rows)
        val passIds = linkedSetOf<String>()
        for (list in listOf("f6f1f99f-9b49-4ccd-b3bf-4d9767a77f5e", "fdd9e2a7-0fee-49f6-ad69-4354098401ff")) {
            val items = request(XboxRequest("https://catalog.gamepass.com/sigls/v2?id=$list&language=en-us&market=$market")).getJSONArray("items")
            check(items.length() <= 5000) { "Microsoft returned too many Game Pass entries." }
            for (index in 0 until items.length()) items.optJSONObject(index)?.optString("id")?.takeIf(::xboxProductId)?.let(passIds::add)
        }
        val ids = (purchased + passIds).distinct()
        val limit = Semaphore(4)
        ids.chunked(25).map { batch -> async {
            limit.withPermit {
                val data = request(XboxRequest("https://displaycatalog.mp.microsoft.com/v7.0/products?bigIds=${batch.joinToString(",")}&market=$market&languages=en-us,neutral"))
                val products = data.getJSONArray("Products")
                (0 until products.length()).mapNotNull { index ->
                    val product = products.getJSONObject(index)
                    val id = product.optString("ProductId")
                    if (id !in batch) null else xboxCatalogGame(product, if (id in purchased) XboxAccess.OWNED else XboxAccess.GAME_PASS)
                }
            }
        } }.awaitAll().flatten().distinctBy { it.id }.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
    }
}

internal fun xboxImageUrl(value: String): String? = runCatching {
    URI(value).takeIf { it.scheme == "https" && it.userInfo == null && it.port == -1 &&
        it.host in setOf("images-eds-ssl.xboxlive.com", "store-images.s-microsoft.com", "store-images.microsoft.com") }?.toString()
}.getOrNull()

private fun requestXboxJson(request: XboxRequest): JSONObject {
    val uri = Http.validate(request.url, setOf(
        "login.live.com", "user.auth.xboxlive.com", "xsts.auth.xboxlive.com", "collections.mp.microsoft.com", "catalog.gamepass.com", "displaycatalog.mp.microsoft.com"))
    val response = Http.exchange(uri, headers = request.headers, body = request.body?.toByteArray(Charsets.UTF_8), contentType = request.contentType,
        connectTimeout = 15_000, readTimeout = 25_000, userAgent = null, tooLarge = "Xbox returned too much data.")
    val status = response.status
    android.util.Log.i("XboxLibrary", "HTTP ${uri.host} ${uri.path.takeIf { !it.contains("xuid") } ?: "title-history"}: $status")
    if (status !in 200..299 && uri.host in setOf("user.auth.xboxlive.com", "xsts.auth.xboxlive.com")) {
        error("Xbox could not authorize this account. Check that it has an Xbox profile and any required family consent.")
    }
    if (status == 401 || (status == 400 && uri.host == "login.live.com")) throw StoreSessionExpired()
    if (status !in 200..299) {
        if (status == 429) throw IOException("Xbox is rate limiting requests. Try again later.")
        throw IOException("Xbox request failed ($status).")
    }
    val text = response.text
    if (uri.host == "catalog.gamepass.com") return JSONObject().put("items", JSONArray(text))
    return JSONObject(text)
}
