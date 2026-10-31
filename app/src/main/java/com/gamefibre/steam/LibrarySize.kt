package com.gamefibre.steam

import `in`.dragonbra.javasteam.types.KeyValue

/** Public-branch, English base-game estimate, preferring the Windows build. */
internal fun librarySizeBytes(metadata: KeyValue?): Long? {
    if (metadata == null) return null
    val platforms = metadata["common"]["oslist"].asString().orEmpty().split(',')
    val platform = listOf("windows", "linux", "macos").firstOrNull { it in platforms } ?: "windows"
    val sizes = metadata["depots"].children.mapNotNull { depot ->
        if (depot.name?.toIntOrNull() == null) return@mapNotNull null
        if (depot["dlcappid"].asString()?.toIntOrNull()?.let { it > 0 } == true) return@mapNotNull null
        val config = depot["config"]
        val os = config["oslist"].asString().orEmpty()
        if (os.isNotBlank() && platform !in os.split(',')) return@mapNotNull null
        val language = config["language"].asString().orEmpty()
        if (language.isNotBlank() && language != "english") return@mapNotNull null
        depot["manifests"]["public"]["size"].asString()?.toLongOrNull()?.takeIf { it >= 0 }
    }
    return sizes.takeIf { it.isNotEmpty() }?.sum()
}
