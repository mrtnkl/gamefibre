package com.gamefibre

import java.text.Collator
import java.text.CollationKey

/** Decorate once, sort once: no normalization, preference reads or lookups in comparisons. */
internal fun orderLibrary(games: List<LibraryEntry>, sort: String, descending: Boolean, beat: BeatIndex?, randomSeed: Long = 0,
    details: (LibraryEntry) -> GameDetails = { game -> mergeGameDetails(game.copies.ifEmpty { listOf(game) }.map { it.details }) }): List<LibraryEntry> {
    if (sort == "Random") return games.map { game ->
        game to kotlin.random.Random(randomSeed xor game.key.hashCode().toLong()).nextLong()
    }.sortedWith(compareBy<Pair<LibraryEntry, Long>> { it.second }.thenBy { it.first.key }).map { it.first }
    data class Ranked(val game: LibraryEntry, val name: CollationKey, val value: Long?)
    val collator = Collator.getInstance()
    val ranked = games.map { game -> Ranked(game, collator.getCollationKey(game.sortName), when (sort) {
        "Time to beat" -> beat?.find(game)?.main?.takeIf { it > 0 }
        "Playtime" -> game.steam?.minutes?.toLong()
        "Metascore" -> details(game).metascore?.takeIf { it in 1..100 }?.toLong()
        "Size" -> game.sizeBytes
        else -> null
    }) }
    return ranked.sortedWith { a, b ->
        if (sort != "Name") {
            if (a.value == null && b.value != null) return@sortedWith 1
            if (b.value == null && a.value != null) return@sortedWith -1
            if (a.value != null && b.value != null && a.value != b.value)
                return@sortedWith if (descending) b.value.compareTo(a.value) else a.value.compareTo(b.value)
        }
        val names = a.name.compareTo(b.name)
        (if (descending) -names else names).takeIf { it != 0 } ?: a.game.key.compareTo(b.game.key)
    }.map { it.game }
}
