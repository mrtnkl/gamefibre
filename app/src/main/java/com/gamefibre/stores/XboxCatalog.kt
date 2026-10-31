package com.gamefibre.stores

import com.gamefibre.GameDetails
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

internal fun xboxProductId(id: String) = id.matches(Regex("[A-Z0-9]{12}"))

/** Package requirements describe playable platforms; store purchase conditions do not. */
internal fun xboxProductPlatforms(product: JSONObject): List<String> {
    val platforms = mutableSetOf<String>()
    val skus = product.optJSONArray("DisplaySkuAvailabilities") ?: return emptyList()
    for (skuIndex in 0 until skus.length()) {
        val packages = skus.optJSONObject(skuIndex)?.optJSONObject("Sku")?.optJSONObject("Properties")
            ?.optJSONArray("Packages") ?: continue
        for (packageIndex in 0 until packages.length()) {
            val dependencies = packages.optJSONObject(packageIndex)?.optJSONArray("PlatformDependencies") ?: continue
            for (dependencyIndex in 0 until dependencies.length()) {
                when (dependencies.optJSONObject(dependencyIndex)?.optString("PlatformName")) {
                    "Windows.Desktop" -> platforms.add("PC")
                    "Windows.Xbox" -> platforms.add("Xbox")
                }
            }
        }
    }
    return platforms.sorted()
}

/** Only active, full, permanent account licenses count as owned; subscription access stays separate. */
internal fun xboxPurchasedProducts(rows: List<JSONObject>, now: Instant = Instant.now()): Set<String> = rows.mapNotNull { row ->
    val id = row.optString("productId")
    if (!xboxProductId(id) || row.optString("status") != "Active" || row.optString("skuType") != "Full" ||
        row.optString("ownershipType") != "OwnedByBeneficiary") return@mapNotNull null
    val expiry = runCatching { OffsetDateTime.parse(row.getString("endDate")).toInstant() }.getOrNull() ?: return@mapNotNull null
    // Recurring access has an end date; permanent purchases use Microsoft's year-9999 sentinel.
    if (!expiry.isAfter(now) || expiry.atOffset(java.time.ZoneOffset.UTC).year != 9999) return@mapNotNull null
    id
}.toSet()

internal fun xboxCatalogGame(product: JSONObject, access: XboxAccess): StoreGame? {
    val id = product.optString("ProductId")
    if (!xboxProductId(id) || product.optString("ProductType") != "Game") return null
    val localized = product.optJSONArray("LocalizedProperties")?.optJSONObject(0) ?: return null
    fun text(key: String) = (localized.opt(key) as? String)?.takeUnless { it == "null" }.orEmpty()
    val name = text("ProductTitle").replace(Regex("\\s*(?:\\((?:PC|Windows|Windows 10)\\)|(?:for|-) Windows 10)\\s*$", RegexOption.IGNORE_CASE), "").trim()
    if (name.isBlank() || name.length > 512) return null
    val images = localized.optJSONArray("Images") ?: JSONArray()
    fun images(purpose: String): List<String> = (0 until images.length()).mapNotNull { i ->
        val image = images.optJSONObject(i) ?: return@mapNotNull null
        if (image.optString("ImagePurpose") != purpose) return@mapNotNull null
        val raw = image.optString("Uri")
        xboxImageUrl(if (raw.startsWith("//")) "https:$raw" else raw)
    }
    val portrait = listOf("TitledPoster", "Poster", "BoxArt").firstNotNullOfOrNull { images(it).firstOrNull() }.orEmpty()
    val image = portrait.ifBlank { listOf("SuperHeroArt", "Hero").firstNotNullOfOrNull { images(it).firstOrNull() }.orEmpty() }
    val release = product.optJSONArray("MarketProperties")?.optJSONObject(0)?.optString("OriginalReleaseDate").orEmpty().take(10)
    return StoreGame(id, name, imageUrl = image, xboxAccess = access, details = GameDetails(
        description = text("ProductDescription"), developer = text("DeveloperName"), publisher = text("PublisherName"),
        release = release, portrait = portrait, screenshots = images("Screenshot").distinct(),
        platforms = xboxProductPlatforms(product),
        storeLinks = mapOf("Xbox" to "https://www.xbox.com/games/store/$id")))
}
