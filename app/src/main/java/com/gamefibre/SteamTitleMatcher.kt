package com.gamefibre

/** Additional title equivalences for Steam metadata, without changing library/store identities. */
internal object SteamTitleMatcher {
    private val trailingArticle = Regex(",\\s*(the|an|a)\\s*$", RegexOption.IGNORE_CASE)
    private val leadingArticle = Regex("^(the|an|a) ")
    private val apostrophes = Regex("['’‘ʼ]")

    fun relaxedKey(title: String): String {
        val words = BeatIndex.normalize(title.replace(trailingArticle, "").replace(apostrophes, "").replace("&", " and "))
            .replaceFirst(leadingArticle, "")
        // Keep Unicode letters/numbers; an ASCII-only filter would erase non-Latin game names.
        return words.replace(" ", "").takeIf { it.codePointCount(0, it.length) >= 3 }.orEmpty()
    }

    fun knownSteamId(game: LibraryEntry, copies: List<LibraryEntry>): String? {
        if (game.store == LibraryStore.STEAM) return game.id
        return copies.asSequence().filter { it.store == LibraryStore.STEAM && it.normalizedName == game.normalizedName }
            .map { it.id }.distinct().toList().singleOrNull()
    }
}
