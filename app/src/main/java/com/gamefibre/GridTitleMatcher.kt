package com.gamefibre

/** SGDB lookup equivalences only; library identities retain their full original titles. */
internal object GridTitleMatcher {
    private val roman = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
        "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX")
    private val numbers = roman.mapIndexed { index, value -> value.lowercase() to (index + 1).toString() }.toMap()
    private val token = Regex("[\\p{L}\\p{N}]+")
    private val subtitleSeparator = Regex(":|\\s+[–—-]\\s+")
    private val editionWords = setOf("edition", "deluxe", "ultimate", "remaster", "remastered",
        "remake", "demo", "soundtrack", "dlc", "season", "pass", "bundle", "collection")

    fun key(title: String): String = BeatIndex.normalize(title).split(' ').mapIndexed { index, word ->
        if (index > 0) numbers[word] ?: word else word
    }.joinToString(" ")

    /** Search the other spelling too when the original search has no full-title match. */
    fun alternateQuery(title: String): String? {
        var index = 0
        val alternate = token.replace(title) { match ->
            if (index++ == 0) match.value
            else numbers[match.value.lowercase()] ?: match.value.toIntOrNull()
                ?.takeIf { it in 1..roman.size && it.toString() == match.value }
                ?.let { roman[it - 1] } ?: match.value
        }
        return alternate.takeIf { it != title }
    }

    fun fullMatches(title: String, games: List<GridGame>): List<GridGame> {
        val key = key(title)
        return if (key.isBlank()) emptyList() else games.filter { key(it.name) == key }.distinctBy { it.id }
    }

    fun matches(title: String, games: List<GridGame>): List<GridGame> {
        val key = key(title)
        if (key.isBlank()) return emptyList()
        val full = fullMatches(title, games)
        if (full.isNotEmpty()) return full
        return games.filter { game ->
            val separator = subtitleSeparator.find(game.name) ?: return@filter false
            val base = game.name.substring(0, separator.range.first)
            val subtitle = key(game.name.substring(separator.range.last + 1)).split(' ')
            key(base) == key && subtitle.first().isNotBlank() &&
                subtitle.first().toIntOrNull() == null && subtitle.first() !in numbers &&
                subtitle.none { it in editionWords }
        }.distinctBy { it.id }
    }
}
