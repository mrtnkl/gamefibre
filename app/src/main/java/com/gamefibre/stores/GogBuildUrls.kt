package com.gamefibre.stores

import org.json.JSONObject

/** Version 2 build listings return CDN endpoints; older listings return a single link. */
internal fun gogBuildUrls(build: JSONObject): List<String> {
    fun JSONObject.address(key: String) = optString(key).takeIf { it.isNotBlank() && it != "null" }
    val endpoints = build.optJSONArray("urls")
    val urls = if (endpoints == null) emptyList() else (0 until endpoints.length()).mapNotNull { index ->
        val endpoint = endpoints.getJSONObject(index)
        endpoint.address("url") ?: endpoint.address("url_format")?.let { format ->
            var url = format
            endpoint.optJSONObject("parameters")?.let { parameters ->
                parameters.keys().forEach { key -> url = url.replace("{$key}", parameters.get(key).toString()) }
            }
            url.takeUnless { '{' in it || '}' in it }
        }
    }
    return (urls + listOfNotNull(build.address("link"))).distinct().also {
        check(it.isNotEmpty()) { "GOG returned no download address for this build. Please try again later." }
    }
}
