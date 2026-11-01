package com.gamefibre

import java.io.DataInputStream
import java.io.InputStream
import java.time.LocalDate
import java.util.BitSet
import org.tukaani.xz.XZInputStream

internal enum class CatalogSort(val label: String) {
    METASCORE("Metascore"), RELEASE("Release date"), USER_SCORE("User rating"), REVIEWS("Review count"), NAME("Name")
}

internal data class CatalogGame(val appId: Int, val name: String, val metascore: Int, val releaseDay: Int,
    val positive: Int, val reviewCount: Int) {
    val userScore: Double? get() = if (reviewCount == 0) null else positive * 100.0 / reviewCount
    val release: LocalDate? get() = releaseDay.takeIf { it > 0 }?.let { LocalDate.of(1900, 1, 1).plusDays(it.toLong() - 1) }
    fun metric(sort: CatalogSort): String? = when (sort) {
        CatalogSort.METASCORE -> metascore.takeIf { it > 0 }?.let { String.format(java.util.Locale.getDefault(), "%d", it) }
        CatalogSort.USER_SCORE -> userScore?.let { String.format(java.util.Locale.getDefault(), "%.1f%%", it) }
        CatalogSort.REVIEWS -> reviewCount.takeIf { it > 0 }?.let { String.format(java.util.Locale.getDefault(), "%,d", it) }
        CatalogSort.RELEASE -> release?.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
        CatalogSort.NAME -> null
    }
    fun entry() = LibraryEntry(LibraryStore.STEAM, appId.toString(), name,
        steam = LibraryGame(appId, name, 0, emptyList(), null,
            GameDetails(metascore = metascore.takeIf { it > 0 }, release = release?.toString().orEmpty(),
                storeLinks = mapOf("STEAM" to "https://store.steampowered.com/app/$appId/"))))
}

internal data class CatalogPage(val games: List<CatalogGame>, val total: Int, val page: Int, val facets: List<TagFacet>) {
    val pageCount get() = ((total + 29) / 30).coerceAtLeast(1)
}

/** One catalog snapshot, plus tag bitsets and cached numeric orderings. Never fetches per-game details. */
internal class SteamCatalog(val games: List<CatalogGame>, tags: (Int) -> List<String>) {
    private val byTag = linkedMapOf<String, BitSet>()
    private val tagNames: List<String>
    private val orders = mutableMapOf<Pair<CatalogSort, Boolean>, IntArray>()
    init {
        games.forEachIndexed { index, game -> tags(game.appId).forEach { tag ->
            byTag.getOrPut(tag) { BitSet(games.size) }.set(index)
        } }
        tagNames = byTag.keys.sortedWith(compareByDescending<String> { byTag.getValue(it).cardinality() }.thenBy { it })
    }
    private fun matching(base: BitSet, filters: TagFilters): BitSet = (base.clone() as BitSet).apply {
        filters.included.forEach { and(byTag[it] ?: BitSet()) }
        filters.excluded.forEach { byTag[it]?.let(::andNot) }
    }
    @Synchronized fun search(query: String, filters: TagFilters, sort: CatalogSort, descending: Boolean, requestedPage: Int): CatalogPage {
        val base = BitSet(games.size)
        val text = query.trim()
        games.forEachIndexed { i, game -> if (game.name.contains(text, true)) base.set(i) }
        val matches = matching(base, filters)
        val total = matches.cardinality()
        val page = requestedPage.coerceIn(0, ((total - 1) / 30).coerceAtLeast(0))
        val order = orders.getOrPut(sort to descending) {
            fun value(game: CatalogGame): Double? = when (sort) {
                CatalogSort.METASCORE -> game.metascore.takeIf { it > 0 }?.toDouble()
                CatalogSort.RELEASE -> game.releaseDay.takeIf { it > 0 }?.toDouble()
                CatalogSort.USER_SCORE -> game.userScore
                CatalogSort.REVIEWS -> game.reviewCount.takeIf { it > 0 }?.toDouble()
                CatalogSort.NAME -> null
            }
            games.indices.sortedWith { a, b ->
                val first = value(games[a]); val second = value(games[b])
                when {
                    first == null && second != null -> 1
                    second == null && first != null -> -1
                    first != null && second != null && first != second -> if (descending) second.compareTo(first) else first.compareTo(second)
                    else -> {
                        val name = games[a].name.compareTo(games[b].name, true)
                        (if (sort == CatalogSort.NAME && descending) -name else name).takeIf { it != 0 }
                            ?: games[a].appId.compareTo(games[b].appId)
                    }
                }
            }.toIntArray()
        }
        val selected = order.asSequence().filter { matches[it] }.drop(page * 30).take(30).map { games[it] }.toList()
        val facets = tagNames.map { TagFacet(it, filters.state(it), 0, 0, 0, 0) }
        return CatalogPage(selected, total, page, facets)
    }
    companion object {
        fun read(stream: InputStream, tags: (Int) -> List<String>): SteamCatalog = SteamCatalog(readGames(stream), tags)

        /** Library sorting needs only scores, without constructing store search/tag indexes. */
        fun readMetascores(stream: InputStream): Map<Int, Int> = readGames(stream)
            .filter { it.metascore in 1..100 }.associate { it.appId to it.metascore }

        private fun readGames(stream: InputStream): List<CatalogGame> =
            DataInputStream(XZInputStream(stream, 16 * 1024).buffered()).use { input ->
                require(input.readInt() == 0x53544341 && input.readInt() == 1) { "Unsupported Steam catalog" }
                val count = input.readInt().also { require(it in 1..1_000_000) }
                fun integer(): Int {
                    var value = 0
                    for (shift in 0..28 step 7) {
                        val byte = input.readUnsignedByte()
                        require(shift < 28 || byte <= 7)
                        value = value or ((byte and 127) shl shift)
                        if (byte < 128) return value
                    }
                    error("Invalid catalog integer")
                }
                var appId = 0
                List(count) {
                    val delta = integer().also { require(it > 0 && it <= Int.MAX_VALUE - appId) }
                    appId += delta
                    val length = integer().also { require(it in 1..16384) }
                    val bytes = ByteArray(length); input.readFully(bytes)
                    val score = input.readUnsignedByte().also { require(it in 0..100) }
                    CatalogGame(appId, bytes.toString(Charsets.UTF_8), score, integer(), integer(), integer()).also {
                        require(it.positive <= it.reviewCount && it.releaseDay <= 1_000_000)
                    }
                }.also { require(input.read() == -1) }
            }
    }
}
