package com.gamefibre.stores

import android.net.Uri
import org.json.JSONObject

internal enum class GameStore(val label: String, val downloads: Boolean = true) {
    GOG("GOG"), EPIC("Epic Games"), XBOX("Xbox", downloads = false);

    val loginUrl: String get() = when (this) {
        XBOX -> XboxApi.loginUrl
        GOG -> Uri.parse("https://login.gog.com/auth").buildUpon()
            .appendQueryParameter("client_id", GOG_CLIENT).appendQueryParameter("layout", "galaxy")
            .appendQueryParameter("redirect_uri", GOG_REDIRECT).appendQueryParameter("response_type", "code").build().toString()
        EPIC -> Uri.parse("https://www.epicgames.com/id/login").buildUpon()
            .appendQueryParameter("redirectUrl", "https://www.epicgames.com/id/api/redirect?clientId=$EPIC_CLIENT&responseType=code").build().toString()
    }

    fun authorizationCode(pasted: String): String {
        val text = pasted.trim()
        require(text.length in 4..16384) { "Paste the sign-in code or the final address from your browser." }
        val code = when {
            text.startsWith("{") && this == EPIC -> runCatching { JSONObject(text).getString("authorizationCode") }.getOrNull()
            text.startsWith("https://") -> {
                val uri = Uri.parse(text)
                val valid = uri.userInfo == null && uri.port == -1 && when (this) {
                    XBOX -> uri.host == "login.live.com" && uri.path == "/oauth20_desktop.srf"
                    GOG -> uri.host == "embed.gog.com" && uri.path == "/on_login_success"
                    EPIC -> uri.host == "www.epicgames.com" && uri.path == "/id/api/redirect"
                }
                require(valid) { "Use the final $label sign-in address, or paste just its code." }
                uri.getQueryParameter("code") ?: uri.getQueryParameter("authorizationCode")
            }
            else -> text
        }
        require(code != null && (if (this == XBOX) validXboxAuthorizationCode(code) else code.matches(Regex("[A-Za-z0-9._~-]{4,4096}")))) { "No valid sign-in code found. Open login again and copy the new code." }
        return code
    }

    companion object {
        // Public launcher protocol identifiers also used by gogdl and Legendary.
        const val GOG_CLIENT = "46899977096215655"
        const val GOG_SECRET = "9d85c43b1482497dbbce61f6e4aa173a433796eeae2ca8c5f6129f2dc4de46d9"
        const val GOG_REDIRECT = "https://embed.gog.com/on_login_success?origin=client"
        const val EPIC_CLIENT = "34a02cf8f4414e29b15921876da36f9a"
        const val EPIC_SECRET = "daafbccc737745039dffe53d94fc76cf"
    }
}

internal enum class XboxAccess { OWNED, GAME_PASS, DISC }

internal data class StoreGame(val id: String, val name: String, val namespace: String = "", val catalogId: String = "", val imageUrl: String = "", val details: com.gamefibre.GameDetails = com.gamefibre.GameDetails(), val xboxAccess: XboxAccess? = null)
internal data class StoreAccount(
    val owner: String? = null,
    val saved: Boolean = false,
    val connected: Boolean = false,
    val loading: Boolean = false,
    val games: List<StoreGame> = emptyList(),
    val libraryLoaded: Boolean = false,
    val error: String? = null,
)
