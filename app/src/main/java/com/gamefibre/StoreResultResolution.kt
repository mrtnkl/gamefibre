package com.gamefibre

/** A catalog hit opens the actual owned record, preserving its identity, downloads, and metadata. */
internal fun resolveOwnedStoreResult(result: LibraryEntry, owned: List<LibraryEntry>,
    steamId: (LibraryEntry) -> Int?): LibraryEntry {
    val exact = owned.firstOrNull { it.key == result.key }
    val id = steamId(result)
    fun unambiguous(candidates: List<LibraryEntry>): LibraryEntry? =
        candidates.takeIf { it.map { game -> game.store }.distinct().size == it.size }
            ?.minByOrNull { it.store.ordinal }
    val byId = if (exact == null && id != null) unambiguous(owned.filter { steamId(it) == id }) else null
    val matched = exact ?: byId ?: unambiguous(owned.filter {
        it.store != result.store && it.normalizedName.isNotBlank() && it.normalizedName == result.normalizedName &&
            (id == null || steamId(it).let { candidateId -> candidateId == null || candidateId == id })
    }) ?: return result
    return mergeLibraryCopies(matchingLibraryCopies(matched, owned)).single()
}
