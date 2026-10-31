package com.gamefibre.stores

import android.net.Uri
import com.gamefibre.GameDetails
import android.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import com.gamefibre.Http
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

internal class StoreSessionExpired : IllegalStateException("Your saved session expired. Sign in again to continue.")
internal class StoreDownloadDenied : IllegalStateException("The store did not grant access to this build. Check its platform and your account's license.")
internal data class StoreSession(val accessToken: String, val refreshToken: String, val owner: String, val expiresAt: Long)

/** Protocol references are linked in README. No third-party launcher code is bundled. */
internal object StoreApi {
    private val xbox = XboxApi()
    private val requestCooldown = StoreRequestCooldown()
    private const val EPIC_ACCOUNT = "https://account-public-service-prod03.ol.epicgames.com"
    private const val EPIC_LIBRARY = "https://library-service.live.use1a.on.epicgames.com"
    private const val EPIC_CATALOG = "https://catalog-public-service-prod06.ol.epicgames.com"
    private const val EPIC_AGENT = "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit"
    private val hosts = setOf("auth.gog.com", "embed.gog.com", "api.gog.com", "content-system.gog.com", "launcher-public-service-prod06.ol.epicgames.com", "account-public-service-prod03.ol.epicgames.com",
        "library-service.live.use1a.on.epicgames.com", "catalog-public-service-prod06.ol.epicgames.com")

    fun signIn(store: GameStore, code: String): StoreSession = token(store, "authorization_code", code)
    fun refresh(store: GameStore, refreshToken: String, owner: String): StoreSession = token(store, "refresh_token", refreshToken, owner)

    private fun token(store: GameStore, grant: String, value: String, owner: String? = null): StoreSession {
        val refreshing = grant == "refresh_token"
        val fields = linkedMapOf("grant_type" to grant, (if (refreshing) "refresh_token" else "code") to value)
        val response = when (store) {
            GameStore.XBOX -> return xbox.token(grant, value, owner)
            GameStore.GOG -> {
                fields["client_id"] = GameStore.GOG_CLIENT; fields["client_secret"] = GameStore.GOG_SECRET
                if (!refreshing) fields["redirect_uri"] = GameStore.GOG_REDIRECT
                call("https://auth.gog.com/token?${form(fields)}", refreshing = refreshing) as JSONObject
            }
            GameStore.EPIC -> {
                fields["token_type"] = "eg1"
                val basic = Base64.encodeToString("${GameStore.EPIC_CLIENT}:${GameStore.EPIC_SECRET}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                call("$EPIC_ACCOUNT/account/api/oauth/token", body = form(fields), authorization = "Basic $basic", refreshing = refreshing) as JSONObject
            }
        }
        val account = response.optString(if (store == GameStore.GOG) "user_id" else "account_id").ifBlank { owner.orEmpty() }
        require(account.matches(Regex("[A-Za-z0-9-]{1,128}"))) { "The store did not return a usable account. Please sign in again." }
        check(owner == null || owner == account) { "The store returned a different account. Sign out and connect it again." }
        val access = response.optString("access_token")
        val refresh = response.optString("refresh_token")
        check(access.isNotBlank() && refresh.isNotBlank()) { "The store did not return a usable session. Please sign in again." }
        val lifetime = response.optLong("expires_in", 3600).coerceIn(1, 30 * 86400)
        return StoreSession(access, refresh, account, System.currentTimeMillis() + lifetime * 1000)
    }

    suspend fun library(store: GameStore, session: StoreSession, previous: List<StoreGame>, catalog: com.gamefibre.assets.AssetStore): List<StoreGame> = when (store) {
        GameStore.XBOX -> xbox.library(session)
        GameStore.GOG -> gogLibrary(session)
        GameStore.EPIC -> epicLibrary(session, previous, catalog)
    }

    private suspend fun gogLibrary(session: StoreSession): List<StoreGame> {
        val games = linkedMapOf<String, StoreGame>()
        var page = 1
        do {
            coroutineContext.ensureActive()
            val result = call("https://embed.gog.com/account/getFilteredProducts?mediaType=1&page=$page", authorization = "Bearer ${session.accessToken}") as JSONObject
            val products = result.getJSONArray("products")
            for (index in 0 until products.length()) {
                val product = products.getJSONObject(index)
                val id = product.get("id").toString()
                games[id] = StoreGame(id, product.getString("title"), imageUrl = product.optString("image").let { if (it.startsWith("//")) "https:$it" else it }, details = GameDetails(
                    release = product.optJSONObject("releaseDate")?.optString("date").orEmpty().take(10),
                    category = product.optString("category"),
                    storeLinks = com.gamefibre.storePageUrl("GOG", product.optString("url"))?.let { mapOf("GOG" to it) }.orEmpty()))
            }
            val pages = result.getInt("totalPages")
            if (page >= pages) break
            check(page < 1000) { "The store returned too many library pages." }
            page++
        } while (true)
        return games.values.sortedBy { it.name.lowercase() }
    }

    private suspend fun epicLibrary(session: StoreSession, previous: List<StoreGame>, catalog: com.gamefibre.assets.AssetStore): List<StoreGame> = coroutineScope {
        val records = linkedMapOf<String, JSONObject>()
        var cursor: String? = null
        val cursors = mutableSetOf<String>()
        do {
            coroutineContext.ensureActive()
            val url = Uri.parse("$EPIC_LIBRARY/library/api/public/items").buildUpon().appendQueryParameter("includeMetadata", "true")
            cursor?.let { url.appendQueryParameter("cursor", it) }
            val result = call(url.build().toString(), authorization = "Bearer ${session.accessToken}") as JSONObject
            val items = result.getJSONArray("records")
            for (index in 0 until items.length()) {
                val game = items.getJSONObject(index)
                val id = game.optString("appName")
                if (id.isNotBlank() && game.optString("namespace") != "ue" && game.optString("sandboxType") != "PRIVATE") records[id] = game
            }
            cursor = result.optJSONObject("responseMetadata")?.optString("nextCursor")?.takeIf { it.isNotBlank() && it != "null" }
            check(cursor == null || (cursors.size < 1000 && cursors.add(cursor))) { "The store returned an invalid library page." }
        } while (cursor != null)
        val oldNames = previous.associate { it.id to it.name }
        val oldImages = previous.associate { it.id to it.imageUrl }
        val oldDetails = previous.associate { it.id to it.details }
        val semaphore = Semaphore(4)
        records.map { (id, record) -> async {
            semaphore.withPermit {
                coroutineContext.ensureActive()
                val embedded = record.optJSONObject("metadata")?.optString("title").orEmpty()
                fun game(name: String, image: String = oldImages[id].orEmpty(), details: GameDetails = oldDetails[id] ?: GameDetails()) = StoreGame(id, name, record.optString("namespace"), record.optString("catalogItemId"), image, details)
                run {
                    val namespace = record.optString("namespace")
                    val catalogId = record.optString("catalogItemId")
                    if (namespace.isBlank() || catalogId.isBlank()) game(oldNames[id] ?: id)
                    else {
                        val url = Uri.parse("$EPIC_CATALOG/catalog/api/shared/namespace/${Uri.encode(namespace)}/bulk/items").buildUpon()
                            .appendQueryParameter("id", catalogId).appendQueryParameter("locale", "en-US")
                            .appendQueryParameter("includeMainGameDetails", "true").build().toString()
                        val recordKey = "epic:${record.optString("namespace")}:$catalogId"
                        val cached = catalog.provider(recordKey)
                        val details = try {
                            if (cached != null && System.currentTimeMillis()-cached.second < 24*60*60*1000L && embedded.ifBlank { oldNames[id] ?: id } == oldNames[id]) cached.first
                            else (call(url, authorization = "Bearer ${session.accessToken}") as JSONObject).optJSONObject(catalogId)?.also { catalog.provider(recordKey,it) }
                        }
                        // Item-specific unavailability is safe to skip. Network failures and
                        // rate limits abort the refresh so the last good library is retained.
                        catch (_: StoreCatalogUnavailable) { null }
                        if (details?.optJSONObject("mainGameItem") != null) null
                        else if (details == null) game(embedded.ifBlank { oldNames[id] ?: id })
                        else {
                            val images = details?.optJSONArray("keyImages") ?: JSONArray()
                            val art = (0 until images.length()).map { images.getJSONObject(it) }
                            val image = listOf("DieselGameBox", "DieselGameBoxWide", "OfferImageWide", "Thumbnail").firstNotNullOfOrNull { kind ->
                                art.firstOrNull { it.optString("type") == kind }?.optString("url")?.takeIf { it.isNotBlank() }
                            } ?: art.firstOrNull()?.optString("url") ?: oldImages[id].orEmpty()
                            val portrait = listOf("DieselGameBoxTall", "OfferImageTall").firstNotNullOfOrNull { kind ->
                                art.firstOrNull { it.optString("type") == kind }?.optString("url")?.takeIf { it.isNotBlank() }
                            }.orEmpty()
                            fun field(name: String) = (details?.opt(name) as? String)?.takeUnless { it == "null" }.orEmpty()
                            val extra = GameDetails(description = field("longDescription").ifBlank { field("description") },
                                developer = field("developerDisplayName").ifBlank { field("developer") },
                                publisher = field("publisherDisplayName").ifBlank { field("publisher") },
                                release = field("pcReleaseDate").ifBlank { field("releaseDate") }.take(10), portrait = portrait,
                                screenshots = art.filter { it.optString("type") in listOf("Screenshot", "featuredMedia") }.map { it.optString("url") }.filter { it.isNotBlank() },
                                tags = details?.optJSONArray("tags")?.let { tags -> (0 until tags.length()).mapNotNull {
                                    tags.optJSONObject(it)?.optString("name")?.takeIf { name -> name.isNotBlank() && name != "null" }
                                } }.orEmpty(), storeLinks = details?.let(com.gamefibre.PageDetailsParser::epicStoreLinks).orEmpty())
                            game(details.optString("title").takeIf { it.isNotBlank() } ?: embedded.ifBlank { oldNames[id] ?: id }, image,
                                extra)
                        }
                    }
                }
            }
        } }.awaitAll().filterNotNull().distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    private fun form(fields: Map<String, String>) = fields.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }

    fun authorizedJson(url: String, session: StoreSession): JSONObject = call(url, authorization = "Bearer ${session.accessToken}") as JSONObject
    fun authorizedArray(url: String, session: StoreSession): JSONArray = call(url, authorization = "Bearer ${session.accessToken}") as JSONArray
    private fun call(url: String, body: String? = null, authorization: String? = null, refreshing: Boolean = false): Any {
        val uri = Http.validate(url, hosts)
        requestCooldown.check(uri.host)
        val response = Http.exchange(uri, headers = authorization?.let { mapOf("Authorization" to it) }.orEmpty(),
            body = body?.toByteArray(Charsets.UTF_8), userAgent = if (uri.host.endsWith("epicgames.com")) EPIC_AGENT else "GameFibre/1.0",
            maxBytes = 16 * 1024 * 1024, tooLarge = "The store response is too large.")
        val status = response.status
        if (status == 429) {
            requestCooldown.rateLimited(uri.host, response.header("Retry-After"))
            throw StoreRateLimited()
        }
        if (status in listOf(403, 404) && uri.host == "catalog-public-service-prod06.ol.epicgames.com") throw StoreCatalogUnavailable()
        if (status == 401 || status == 403) {
            if(status==403 && uri.host in listOf("content-system.gog.com","api.gog.com","launcher-public-service-prod06.ol.epicgames.com"))throw StoreDownloadDenied()
            if (refreshing || authorization?.startsWith("Bearer ") == true) throw StoreSessionExpired()
            error("The store could not accept this sign-in. Open login again and copy a fresh code.")
        }
        if (status !in 200..299) {
            if (refreshing && status == 400) throw StoreSessionExpired()
            error(if (status == 400) "The sign-in code expired or was already used. Open login again and copy a new code."
                else if (status == 429) "The store is receiving too many requests. Wait a moment and try again."
                else "The store is unavailable (HTTP $status). Please try again later.")
        }
        return JSONTokener(response.text).nextValue().also { check(it is JSONObject || it is JSONArray) { "The store returned an unexpected response." } }
    }
}
