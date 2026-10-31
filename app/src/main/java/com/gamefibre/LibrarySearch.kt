package com.gamefibre

import java.util.BitSet
import java.util.Locale

internal enum class TagState { OFF, INCLUDE, EXCLUDE }
private fun tagKey(value: String) = value.lowercase(Locale.ROOT)

internal data class TagFilters(val included: Set<String> = emptySet(), val excluded: Set<String> = emptySet()) {
    val active get() = included.isNotEmpty() || excluded.isNotEmpty()
    fun state(tag: String) = when (tagKey(tag)) {
        in included.map(::tagKey) -> TagState.INCLUDE
        in excluded.map(::tagKey) -> TagState.EXCLUDE
        else -> TagState.OFF
    }
    fun with(tag: String, state: TagState) = TagFilters(
        included.filterNot { it.equals(tag, true) }.toSet() + if (state == TagState.INCLUDE) setOf(tag) else emptySet(),
        excluded.filterNot { it.equals(tag, true) }.toSet() + if (state == TagState.EXCLUDE) setOf(tag) else emptySet())
    fun toggle(tag: String) = with(tag, if (state(tag) == TagState.OFF) TagState.INCLUDE else TagState.OFF)
}

internal data class TagSearchGame(val key: String, val name: String, val tags: List<String>, val aliases: List<String> = emptyList())
internal data class TagFacet(val name: String, val state: TagState, val toggleCount: Int,
    val includeCount: Int, val excludeCount: Int, val clearCount: Int)

/** Counts describe the full resulting text + tag query, with store copies already merged. */
internal class LibraryTagSearch(private val games: List<TagSearchGame>) {
    private val labels = linkedMapOf<String, String>()
    private val byTag = mutableMapOf<String, BitSet>()
    init {
        games.forEachIndexed { index, game -> game.tags.forEach { tag ->
            val key = tagKey(tag)
            labels.putIfAbsent(key, tag)
            byTag.getOrPut(key) { BitSet() }.set(index)
        } }
    }

    private fun textMatches(text: String) = BitSet(games.size).also { result ->
        val term = text.trim()
        games.forEachIndexed { index, game ->
            if (game.name.contains(term, true) || game.aliases.any { it.contains(term, true) }) result.set(index)
        }
    }

    private fun matches(base: BitSet, filters: TagFilters): BitSet {
        val result = base.clone() as BitSet
        filters.included.forEach { result.and(byTag[tagKey(it)] ?: BitSet()) }
        filters.excluded.forEach { byTag[tagKey(it)]?.let(result::andNot) }
        return result
    }

    fun keys(text: String, filters: TagFilters): Set<String> {
        val bits = matches(textMatches(text), filters)
        return buildSet {
            var index = bits.nextSetBit(0)
            while (index >= 0) { add(games[index].key); index = bits.nextSetBit(index + 1) }
        }
    }

    /** Each merged game contributes once. Keep the game's Steam ranking when frequencies tie. */
    fun rankByLibraryFrequency(tags: List<String>): List<String> =
        tags.sortedByDescending { byTag[tagKey(it)]?.cardinality() ?: 0 }

    fun facets(text: String, filters: TagFilters): List<TagFacet> {
        val base = textMatches(text)
        val names = labels.toMutableMap()
        (filters.included + filters.excluded).forEach { names.putIfAbsent(tagKey(it), it) }
        return names.values.map { name ->
            fun count(state: TagState) = matches(base, filters.with(name, state)).cardinality()
            val state = filters.state(name)
            val include = count(TagState.INCLUDE); val exclude = count(TagState.EXCLUDE); val clear = count(TagState.OFF)
            TagFacet(name, state, if (state == TagState.OFF) include else clear, include, exclude, clear)
        }.filter { it.state != TagState.OFF || it.includeCount > 0 }
            .sortedWith(compareBy<TagFacet> { it.state == TagState.OFF }.thenByDescending { it.includeCount }.thenBy { it.name })
    }
}
