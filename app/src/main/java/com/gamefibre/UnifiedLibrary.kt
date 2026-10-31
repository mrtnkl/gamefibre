package com.gamefibre

import com.gamefibre.stores.GameStore
import com.gamefibre.stores.StoreGame
import java.text.Collator

internal enum class LibraryStore(val label: String, val account: GameStore? = null) {
    STEAM("Steam"), GOG("GOG", GameStore.GOG), EPIC("Epic Games", GameStore.EPIC), XBOX("Xbox", GameStore.XBOX);
    val downloads get() = account?.downloads ?: true
}

/** Copies keep their original store identities even when represented by one collection card. */
internal data class LibraryEntry(val store: LibraryStore, val id: String, val name: String,
    val steam: LibraryGame? = null, val remote: StoreGame? = null, val copies: List<LibraryEntry> = emptyList()) {
    val key = "${store.name}:$id"
    val normalizedName by lazy(LazyThreadSafetyMode.PUBLICATION) { BeatIndex.normalize(name) }
    val sortName by lazy(LazyThreadSafetyMode.PUBLICATION) { librarySortName(name, true) }
    val sizeBytes get() = steam?.sizeBytes
    val details get() = steam?.details ?: remote?.details ?: GameDetails()
    val artwork get() = remote?.imageUrl.orEmpty()
}

private val leadingArticle = Regex("^(the|a)\\s+", RegexOption.IGNORE_CASE)
private val trailingArticle = Regex(",\\s*(the|a)$", RegexOption.IGNORE_CASE)
internal fun librarySortName(name: String, ignoreArticles: Boolean): String = if (!ignoreArticles) name else
    name.trim().replace(leadingArticle, "").replace(trailingArticle, "")

/** Full normalized names only: remasters, editions and sequels remain distinct. No lookups. */
internal fun mergeLibraryCopies(games: List<LibraryEntry>): List<LibraryEntry> = games
    .groupBy { it.normalizedName.ifBlank { it.key } }.values.flatMap { group ->
        // Multiple IDs on one store can denote different products; leave ambiguous groups alone.
        if (group.size < 2 || group.map { it.store }.distinct().size != group.size) group
        else listOf(group.minBy { it.store.ordinal }.copy(copies = group.sortedBy { it.store.ordinal }))
    }

internal fun matchingLibraryCopies(game: LibraryEntry, games: List<LibraryEntry>): List<LibraryEntry> {
    val matches = games.filter { it.normalizedName.isNotBlank() && it.normalizedName == game.normalizedName }
    return if (matches.map { it.store }.distinct().size != matches.size) listOf(game) else matches.ifEmpty { listOf(game) }
}

internal fun filterLibrary(games: List<LibraryEntry>, query: String, store: LibraryStore?, bySize: Boolean, descending: Boolean,
    ignoreArticles: Boolean = true): List<LibraryEntry> {
    val collator = Collator.getInstance()
    return games.filter { (store == null || it.store == store) && it.name.contains(query.trim(), ignoreCase = true) }.sortedWith { a, b ->
        val first = a.sizeBytes; val second = b.sizeBytes
        if (bySize) {
            if (first == null && second != null) return@sortedWith 1
            if (second == null && first != null) return@sortedWith -1
            if (first != null && second != null && first != second) return@sortedWith if (descending) second.compareTo(first) else first.compareTo(second)
        }
        val names = collator.compare(if (ignoreArticles) a.sortName else a.name, if (ignoreArticles) b.sortName else b.name)
        if (names != 0) { if (descending) -names else names } else a.key.compareTo(b.key)
    }
}

/** List membership follows unambiguous owned copies, including copies connected later. */
internal fun libraryListKeys(games: List<LibraryEntry>, members: Set<String>): Set<String> = buildSet {
    addAll(members)
    mergeLibraryCopies(games).forEach { game ->
        val copies = game.copies.ifEmpty { listOf(game) }
        if (copies.any { it.key in members }) addAll(copies.map { it.key })
    }
}

/**
 * The browsable library derived from every source. Pure data: no Android, no I/O.
 * Current account data takes precedence over imported metadata, except locally recorded disc ownership.
 */
internal class MergedLibrary(val owned: List<LibraryEntry>, val combined: List<LibraryEntry>, val wishlistKeys: Set<String>, val observedKeys: Set<String>) {
    val ownedKeys: Set<String> by lazy { owned.map { it.key }.toSet() }
    val ownedByTitle: Map<String, List<LibraryEntry>> by lazy { owned.groupBy { it.normalizedName } }
}

/**
 * @param remembered games published offline for the current accounts but absent from the latest inventories;
 *   they stay browsable but are not "observed", which drives the access warning on their page.
 */
internal fun mergeLibrary(
    steam: List<LibraryEntry>, remote: List<LibraryEntry>, imported: List<LibraryEntry>, remembered: List<LibraryEntry>,
    wishlist: List<LibraryEntry>, downloaded: List<LibraryEntry>, transfers: List<LibraryEntry>,
): MergedLibrary {
    // Disc ownership remains valid even when the account only reports Game Pass access.
    // Removed local discs must not return through the offline account artwork inventory.
    val discs = imported.filter { it.remote?.xboxAccess == com.gamefibre.stores.XboxAccess.DISC }
    val retained = remembered.filter { it.remote?.xboxAccess != com.gamefibre.stores.XboxAccess.DISC }
    val owned = (discs + steam + remote + imported + retained).distinctBy { it.key }
    val combined = (owned + wishlist + downloaded + transfers).distinctBy { it.key }
    val observed = (steam + remote + imported + wishlist + downloaded + transfers).map { it.key }.toSet()
    return MergedLibrary(owned, combined, wishlist.map { it.key }.toSet(), observed)
}
