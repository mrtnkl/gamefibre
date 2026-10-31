package com.gamefibre.stores

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** Only the store's exact HTTPS callback may supply a code or be inspected for JSON. */
internal object StoreLoginResponse {
    fun isCallback(store: GameStore, address: String): Boolean {
        val uri = runCatching { URI(address) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1 || uri.rawFragment != null) return false
        return when (store) {
            GameStore.GOG -> uri.host == "embed.gog.com" && uri.rawPath == "/on_login_success"
            GameStore.EPIC -> uri.host == "www.epicgames.com" && uri.rawPath == "/id/api/redirect"
            GameStore.XBOX -> false // Microsoft has its own state-bound callback validation.
        }
    }

    fun codeFromUrl(store: GameStore, address: String): String? {
        if (!isCallback(store, address)) return null
        val query = query(URI(address))
        require("error" !in query) { "Sign-in was declined. Try signing in again." }
        return queryCode(query)
    }

    fun epicPageCode(address: String, body: String): String? {
        if (!isCallback(GameStore.EPIC, address)) return null
        require(body.length <= 16384) { "Epic returned an unusable sign-in response. Try again." }
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        require(!json.has("errorCode") && !json.has("error")) {
            "Epic could not complete sign-in. Check your account on Epic Games, then try again."
        }
        for (key in listOf("authorizationCode", "code")) {
            val value = json.opt(key)
            if (value is String && value.isNotBlank()) return validCode(value)
        }
        // This address comes from the verified Epic response. Extract it; never navigate to it.
        val redirect = json.opt("redirectUrl") as? String ?: return null
        require(redirect.length <= 16384) { "Epic returned an unusable sign-in response. Try again." }
        return queryCode(query(URI(redirect)))
    }

    private fun query(uri: URI): Map<String, List<String>> = uri.rawQuery.orEmpty().split('&')
        .filter { it.isNotEmpty() }.map {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }.groupBy({ it.first }, { it.second })

    private fun queryCode(query: Map<String, List<String>>): String? {
        val values = listOf("code", "authorizationCode").flatMap { query[it].orEmpty() }
        if (values.isEmpty()) return null
        require(values.size == 1) { "The store returned an ambiguous sign-in response. Try again." }
        return validCode(values.single())
    }

    private fun validCode(code: String): String {
        require(code.matches(Regex("[A-Za-z0-9._~-]{4,4096}"))) {
            "The store returned an invalid sign-in code. Try signing in again."
        }
        return code
    }
}
