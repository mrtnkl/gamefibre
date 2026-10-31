package com.gamefibre

import java.util.Locale

internal data class RelatedGames(val developer: List<LibraryEntry>, val publisher: List<LibraryEntry>)

/** Match individual company names, never substrings or developer-to-publisher fields. */
internal fun relatedLibraryGames(current: LibraryEntry, details: GameDetails, games: List<LibraryEntry>,
    detailsFor: (LibraryEntry) -> GameDetails): RelatedGames {
    fun companies(value: String) = value.split(" · ").map {
        it.replace("&amp;", "&").replace("&#38;", "&").trim()
            .replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    }.filter(String::isNotBlank).toSet()
    val developers = companies(details.developer)
    val publishers = companies(details.publisher)
    val developer = mutableListOf<LibraryEntry>()
    val publisher = mutableListOf<LibraryEntry>()
    val currentKeys = current.copies.ifEmpty { listOf(current) }.map { it.key }.toSet()
    for (game in filterLibrary(games, "", null, false, false)) {
        if (game.key in currentKeys || game.copies.any { it.key in currentKeys }) continue
        val candidate = detailsFor(game)
        if (companies(candidate.developer).any { it in developers }) developer.add(game)
        if (companies(candidate.publisher).any { it in publishers }) publisher.add(game)
    }
    return RelatedGames(developer, publisher)
}
