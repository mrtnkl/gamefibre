package com.gamefibre

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject

internal data class AppUpdate(val version: String, val downloadUrl: String)

/** Only stable releases with the expected, fully uploaded APK can offer an update. */
internal fun githubAppUpdate(release: JSONObject, installedVersion: String): AppUpdate? {
    if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
    val tag = release.optString("tag_name")
    val version = tag.removePrefix("v")
    fun parts(value: String): List<Long>? {
        if (!value.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) return null
        return value.split('.').map { it.toLongOrNull() ?: return null }
    }
    val latest = parts(version) ?: return null
    val installed = parts(installedVersion) ?: return null
    if (tag != "v$version" || latest.zip(installed).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } != true) return null
    val expectedName = "gamefibre-$version.apk"
    val expectedUrl = "https://github.com/mrtnkl/gamefibre/releases/download/$tag/$expectedName"
    val assets = release.optJSONArray("assets") ?: return null
    val uploaded = (0 until assets.length()).any { index ->
        val asset = assets.optJSONObject(index) ?: return@any false
        asset.optString("name") == expectedName && asset.optString("state") == "uploaded" &&
            asset.optLong("size") > 0 && asset.optString("browser_download_url") == expectedUrl
    }
    return if (uploaded) AppUpdate(version, expectedUrl) else null
}

internal class AppUpdates(
    private val preferences: SharedPreferences,
    private val installedVersion: String,
    private val fetch: () -> JSONObject = {
        val response = Http.exchange(Http.validate("https://api.github.com/repos/mrtnkl/gamefibre/releases/latest", setOf("api.github.com")),
            accept = "application/vnd.github+json", connectTimeout = 8_000, readTimeout = 8_000, maxBytes = 512 * 1024)
        check(response.ok) { "Could not check for updates." }
        JSONObject(response.text)
    },
) {
    val available: AppUpdate? get() = preferences.getString("release", null)?.let {
        runCatching { githubAppUpdate(JSONObject(it), installedVersion) }.getOrNull()
    }

    fun shouldCheck(now: Long): Boolean {
        val previous = preferences.getLong("last-check", 0)
        val interval = if (preferences.getBoolean("check-succeeded", false)) 24 * 60 * 60 * 1000L else 15 * 60 * 1000L
        return previous == 0L || now < previous || now - previous >= interval
    }

    /** A failed/offline check leaves the previously usable update intact. Call on an IO dispatcher. */
    fun check(now: Long) {
        preferences.edit { putLong("last-check", now); putBoolean("check-succeeded", false) }
        val release = fetch()
        // Reject malformed responses without discarding a known update.
        check(release.has("tag_name") && release.has("assets") && release.has("draft") && release.has("prerelease")) {
            "Could not check for updates."
        }
        preferences.edit { putString("release", release.toString()); putBoolean("check-succeeded", true) }
    }

    fun shouldPrompt(update: AppUpdate): Boolean = preferences.getString("prompted-version", null) != update.version
    fun markPrompted(update: AppUpdate) { preferences.edit { putString("prompted-version", update.version) } }
}
