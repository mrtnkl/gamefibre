package com.gamefibre

import java.net.URI
import java.net.URLEncoder

/** Accept only public storefront URLs supplied by metadata. */
internal fun storePageUrl(store: String, value: String): String? = runCatching {
    val base = when (store) {
        "Steam" -> "https://store.steampowered.com"
        "GOG" -> "https://www.gog.com"
        "Epic Games" -> "https://store.epicgames.com"
        "Xbox" -> "https://www.xbox.com"
        else -> return null
    }
    val uri = URI(base).resolve(value)
    uri.toString().takeIf {
        (value.startsWith("/") || value.startsWith("https://")) && uri.scheme == "https" && uri.host == URI(base).host && uri.userInfo == null && uri.port == -1
    }
}.getOrNull()

internal fun storeSearchUrl(store: LibraryStore, name: String): String {
    val query = URLEncoder.encode(name, "UTF-8")
    return when (store) {
        LibraryStore.XBOX -> "https://www.xbox.com/search?q=$query"
        LibraryStore.STEAM -> "https://store.steampowered.com/search/?term=$query"
        LibraryStore.GOG -> "https://www.gog.com/en/games?query=$query"
        LibraryStore.EPIC -> "https://store.epicgames.com/en-US/browse?q=$query"
    }
}

/** Windows disk requirement only; never treat RAM or graphics memory as storage. */
internal fun steamStorageBytes(html: String): Long? {
    val text = html.replace(Regex("<[^>]*>"), " ").replace("&nbsp;", " ")
    val match = Regex("(?:storage|hard\\s*(?:disk|drive)(?:\\s*space)?|disk\\s*space)\\s*:?\\s*(?:at least\\s*)?([0-9]+(?:[.,][0-9]+)?)\\s*(TB|GB|MB)\\b", RegexOption.IGNORE_CASE).find(text)
        ?: return null
    val number = match.groupValues[1]
    val normalized = if (number.matches(Regex("[0-9]{1,3},[0-9]{3}"))) number.replace(",", "") else number.replace(',', '.')
    val amount = normalized.toDoubleOrNull() ?: return null
    val multiplier = when (match.groupValues[2].uppercase(java.util.Locale.ROOT)) {
        "TB" -> 1024.0 * 1024 * 1024 * 1024
        "GB" -> 1024.0 * 1024 * 1024
        else -> 1024.0 * 1024
    }
    return (amount * multiplier).takeIf { it.isFinite() && it > 0 && it < Long.MAX_VALUE }?.toLong()
}
