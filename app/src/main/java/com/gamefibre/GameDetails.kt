package com.gamefibre

import org.json.JSONArray
import org.json.JSONObject

// Html.fromHtml turns inline images into U+FFFC (shown as an OBJ box in a plain TextView).
private val descriptionImages = Regex("<img\\b(?:[^'\">]|\"[^\"]*\"|'[^']*')*>", RegexOption.IGNORE_CASE)
internal fun descriptionHtmlWithoutImages(html: String): String = html.replace(descriptionImages, "")

/** Only fields returned by requests already used to load the library. */
data class GameDetails(
    val description: String = "", val developer: String = "", val publisher: String = "",
    val release: String = "", val category: String = "", val portrait: String = "",
    val screenshots: List<String> = emptyList(), val tags: List<String> = emptyList(),
    val metascore: Int? = null,
    val steamStorageBytes: Long? = null, val storeLinks: Map<String, String> = emptyMap(),
    val platforms: List<String> = emptyList(),
) {
    fun json() = JSONObject().put("description", description).put("developer", developer)
        .put("publisher", publisher).put("release", release).put("category", category)
        .put("portrait", portrait).put("screenshots", JSONArray(screenshots)).put("tags", JSONArray(tags))
        .put("metascore", metascore ?: JSONObject.NULL)
        .put("steamStorageBytes", steamStorageBytes ?: JSONObject.NULL).put("storeLinks", JSONObject(storeLinks))
        .put("platforms", JSONArray(platforms))
    companion object {
        fun strings(array: JSONArray?) = if (array == null) emptyList() else
            (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() && it != "null" }
        fun read(data: JSONObject?): GameDetails {
            if (data == null) return GameDetails()
            fun text(key: String) = (data.opt(key) as? String)?.takeUnless { it == "null" }.orEmpty()
            return GameDetails(text("description"), text("developer"), text("publisher"),
                text("release"), text("category"), text("portrait"),
                strings(data.optJSONArray("screenshots")), strings(data.optJSONArray("tags")),
                data.optInt("metascore").takeIf { it in 1..100 },
                data.optLong("steamStorageBytes").takeIf { it > 0 },
                data.optJSONObject("storeLinks")?.let { links -> links.keys().asSequence().mapNotNull { store ->
                    storePageUrl(store, links.optString(store))?.let { store to it }
                }.toMap() }.orEmpty(), strings(data.optJSONArray("platforms")))
        }
    }
}

/** One collection page combines all known copies; download selection never affects this data. */
internal fun mergeGameDetails(copies: List<GameDetails>): GameDetails {
    fun unique(values: List<String>) = values.map(String::trim).filter(String::isNotBlank)
        .distinctBy { it.replace("&amp;", "&").lowercase(java.util.Locale.ROOT) }
    fun join(values: List<String>) = unique(values.flatMap { it.split(" · ") }).joinToString(" · ")
    return GameDetails(
        description = copies.map { it.description }.filter(String::isNotBlank).maxByOrNull { it.length }.orEmpty(),
        developer = join(copies.map { it.developer }), publisher = join(copies.map { it.publisher }),
        release = join(copies.map { it.release }), category = join(copies.map { it.category }),
        portrait = copies.firstNotNullOfOrNull { it.portrait.takeIf(String::isNotBlank) }.orEmpty(),
        screenshots = unique(copies.flatMap { it.screenshots }), tags = unique(copies.flatMap { it.tags }),
        metascore = copies.asReversed().firstNotNullOfOrNull { it.metascore?.takeIf { score -> score in 1..100 } },
        steamStorageBytes = copies.asReversed().firstNotNullOfOrNull { it.steamStorageBytes },
        storeLinks = copies.flatMap { it.storeLinks.entries }.associate { it.key to it.value },
        platforms = unique(copies.flatMap { it.platforms }).sorted())
}
