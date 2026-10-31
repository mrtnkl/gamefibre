package com.gamefibre

import org.json.JSONObject

/** Asset filenames are supplied by Steam, including their per-image hash directories. */
internal data class SteamArtworkUrls(val portrait: String, val landscape: String) {
    fun address(portrait: Boolean) = if (portrait) this.portrait.ifBlank { landscape } else landscape.ifBlank { this.portrait }

    companion object {
        fun read(assets: JSONObject?): SteamArtworkUrls {
            val format = assets?.optString("asset_url_format").orEmpty()
            fun asset(key: String): String {
                val filename = assets?.optString(key).orEmpty()
                return if (format.contains("\${FILENAME}") && filename.isNotBlank())
                    WishlistItems.imageUrl("https://shared.akamai.steamstatic.com/store_item_assets/" + format.replace("\${FILENAME}", filename)) else ""
            }
            return SteamArtworkUrls(asset("library_capsule"), asset("small_capsule").ifBlank { asset("header") })
        }
    }
}
