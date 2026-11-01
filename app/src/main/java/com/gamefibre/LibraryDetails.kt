package com.gamefibre

/** Bundled scores fill gaps before any game page is opened, including saved libraries and wishlists. */
internal fun libraryDetails(
    sources: List<LibraryEntry>, metascores: Map<Int, Int>, steamId: (LibraryEntry) -> Int?,
    pageDetails: (LibraryEntry) -> GameDetails?,
): GameDetails {
    val details = mergeGameDetails(sources.map { it.details } + sources.mapNotNull(pageDetails))
    return if (details.metascore != null) details else details.copy(metascore = sources.firstNotNullOfOrNull { source ->
        val id = SteamTitleMatcher.knownSteamId(source, sources)?.toIntOrNull() ?: steamId(source)
        id?.let(metascores::get)?.takeIf { it in 1..100 }
    })
}
