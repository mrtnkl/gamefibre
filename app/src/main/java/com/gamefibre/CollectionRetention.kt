package com.gamefibre

/** Changes to device preferences when an account is forgotten. Display settings are retained. */
internal fun collectionChangesAfterSignOut(values: Map<String, *>, store: String): Map<String, Any?> {
    val prefix = "$store:"
    val discs = values.entries.filter { (key, value) ->
        key.startsWith("library:$prefix") && value is String &&
            org.json.JSONObject(value).optString("xboxAccess") == "DISC"
    }.map { it.key.removePrefix("library:") }.toSet()
    return buildMap {
        values.forEach { (key, value) ->
            when {
                key.startsWith("download:$prefix") || key.startsWith("progress:$prefix") || key.startsWith("library:$prefix") || key.startsWith("metascore:$prefix") -> {
                    if (key.substringAfter(':') !in discs) put(key, null)
                }
                key == "download-keys" || key.startsWith("members:") -> {
                    val old = (value as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
                    val remaining = old.filterNot { it.startsWith(prefix) && it !in discs }.toSet()
                    if (remaining != old) put(key, remaining)
                }
            }
        }
    }
}
