package com.gamefibre

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale

internal class CollectionData(val preferences: android.content.SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("collection-v1", Context.MODE_PRIVATE))
    fun progress(keys: Collection<String>): GameProgress = keys.mapNotNull { key ->
        GameProgress.fromId(preferences.getString("progress:$key", null))
    }.maxByOrNull { it.ordinal } ?: GameProgress.UNPLAYED

    fun setProgress(keys: Set<String>, progress: GameProgress) = synchronized(Companion) {
        val editor = preferences.edit()
        keys.forEach { editor.putString("progress:$it", progress.id) }
        editor.apply()
        membershipRevision++
    }

    var membershipRevision = 0
        private set
    var downloadRevision = 0
        private set
    private var savedLibrary: List<LibraryEntry>? = null
    private var savedWishlist: List<LibraryEntry>? = null
    private var savedDownloads: Map<String, Pair<LibraryEntry, String>>? = null
    private var dataRevision = preferences.getLong("data-revision", 0)

    /** Collection metadata is restored before publication, independently of catalog/page loading. */
    private fun restoreMetascore(game: LibraryEntry): LibraryEntry {
        val score = preferences.getString("metascore:${game.key}", null)?.toIntOrNull()?.takeIf { it in 1..100 }
            ?: return game
        if (game.details.metascore == score) return game
        val details = game.details.copy(metascore = score)
        return game.copy(steam = game.steam?.copy(details = details),
            remote = game.remote?.copy(details = details))
    }

    fun rememberMetascores(scores: Map<String, Int>) = synchronized(Companion) {
        val changed = scores.filter { (key, score) ->
            score in 1..100 && preferences.getString("metascore:$key", null) != score.toString()
        }
        if (changed.isEmpty()) return@synchronized
        val editor = preferences.edit()
        changed.forEach { (key, score) -> editor.putString("metascore:$key", score.toString()) }
        check(editor.putLong("data-revision", preferences.getLong("data-revision", 0) + 1).commit()) {
            "Could not save collection Metascores."
        }
        refresh()
    }

    /** Fill existing and newly added games once; account refreshes cannot erase learned scores. */
    fun enrichMetascores(library: MergedLibrary, scores: Map<Int, Int>, steamId: (LibraryEntry) -> Int?): MergedLibrary {
        val restored = library.combined.map(::restoreMetascore)
        val byTitle = restored.groupBy { it.normalizedName }
        val enriched = restored.map { game ->
            if (game.details.metascore in 1..100) game else {
                val copies = matchingLibraryCopies(game, byTitle[game.normalizedName].orEmpty())
                val score = copies.asReversed().firstNotNullOfOrNull { it.details.metascore?.takeIf { score -> score in 1..100 } }
                    ?: if (scores.isEmpty()) null else libraryDetails(copies, scores, steamId) { null }.metascore
                if (score == null) game else game.copy(steam = game.steam?.copy(details = game.details.copy(metascore = score)),
                    remote = game.remote?.copy(details = game.details.copy(metascore = score)))
            }
        }
        rememberMetascores(enriched.mapNotNull { game -> game.details.metascore?.let { game.key to it } }.toMap())
        val byKey = enriched.associateBy { it.key }
        return MergedLibrary(library.owned.map { byKey.getValue(it.key) }, enriched, library.wishlistKeys, library.observedKeys)
    }
    fun refresh() {
        val revision = preferences.getLong("data-revision", 0)
        if (revision != dataRevision) {
            dataRevision = revision
            savedLibrary = null
            savedDownloads = null
            savedWishlist = null
            membershipRevision++
            downloadRevision++
        }
    }
    fun members(list: String) = preferences.getStringSet("members:$list", emptySet()).orEmpty()
    var list: String
        get() = preferences.getString("list", "All games")?.takeIf { it.isEmpty() || it in lists } ?: lists.firstOrNull().orEmpty()
        set(value) { preferences.edit().putString("list", value).apply() }
    var showTitles: Boolean
        get() = preferences.getBoolean("titles", false)
        set(value) { preferences.edit().putBoolean("titles", value).apply() }
    var fitCoverArt: Boolean
        get() = preferences.getBoolean("fit-cover-art", false)
        set(value) { preferences.edit().putBoolean("fit-cover-art", value).apply() }
    var showHiddenGames: Boolean
        get() = preferences.getBoolean("show-hidden-games", false)
        set(value) { preferences.edit().putBoolean("show-hidden-games", value).apply() }
    var showGamePass: Boolean
        get() = preferences.getBoolean("show-game-pass", true)
        set(value) { preferences.edit().putBoolean("show-game-pass", value).apply() }
    var tileSize: String
        get() = preferences.getString("tiles", "Medium")!!
        set(value) { preferences.edit().putString("tiles", value).apply() }
    var sort: String
        get() = preferences.getString("sort", "Name")!!
        set(value) { preferences.edit().putString("sort", value).apply() }
    var randomSeed: Long
        get() = preferences.getLong("random-sort-seed", 0)
        set(value) { preferences.edit().putLong("random-sort-seed", value).apply() }
    val customLists: List<String>
        get() = runCatching {
            val names = org.json.JSONArray(preferences.getString("custom-lists", "[]"))
            List(names.length()) { names.getString(it) }
        }.getOrDefault(emptyList())
    private val removedBuiltIns get() = preferences.getStringSet("removed-built-in-lists", emptySet()).orEmpty()
    private val savedListOrder: List<String>
        get() = runCatching {
            val names = JSONArray(preferences.getString("list-order", "[]"))
            List(names.length()) { names.getString(it) }
        }.getOrDefault(emptyList())
    val lists: List<String> get() {
        val available = (BUILT_IN_LISTS.filter { it in PERMANENT_LISTS || it !in removedBuiltIns } +
            GameProgress.entries.filter { it != GameProgress.UNPLAYED }.map { it.label } + customLists).distinct()
        return (listOf("All games") + (savedListOrder + available).filter { it !in setOf(WishlistItems.LIST, HIDDEN_LIST) } + listOf(WishlistItems.LIST, HIDDEN_LIST))
            .distinct().filter { it in available }
    }
    fun reorderLists(names: List<String>) = synchronized(Companion) {
        require(names.size == lists.size && names.toSet() == lists.toSet()) { "Include every list exactly once." }
        val order = (listOf("All games") + names.filter { it !in setOf(WishlistItems.LIST, HIDDEN_LIST) } + listOf(WishlistItems.LIST, HIDDEN_LIST)).distinct().filter { it in names }
        check(preferences.edit().putString("list-order", JSONArray(order).toString()).commit()) { "Could not save list order." }
    }
    fun visibleLists(hasGames: (String) -> Boolean) = lists.filter { if (it == "Game Pass" && isAutomaticList(it)) showGamePass else if (it == HIDDEN_LIST) showHiddenGames else (it != "Downloads" && GameProgress.fromLabel(it) == null) || hasGames(it) }
    val editableLists get() = lists.filter { it !in setOf("Downloads", WishlistItems.LIST) && GameProgress.fromLabel(it) == null && !isAutomaticList(it) && (it != HIDDEN_LIST || showHiddenGames) }
    fun isAutomaticList(name: String) = name in listOf("All games", "Steam", "GOG", "Epic", "Xbox", "Game Pass") && name !in customLists
    fun canRemoveList(name: String) = name in lists && name !in PERMANENT_LISTS && GameProgress.fromLabel(name) == null
    fun removeList(name: String) = synchronized(Companion) {
        require(canRemoveList(name)) { "This list cannot be removed." }
        val editor = preferences.edit()
            .putString("custom-lists", org.json.JSONArray(customLists - name).toString())
            .putString("list-order", JSONArray(lists - name).toString())
            .remove("members:$name")
        if (name in BUILT_IN_LISTS) editor.putStringSet("removed-built-in-lists", removedBuiltIns + name)
        if (list == name) editor.putString("list", (lists - name).firstOrNull().orEmpty())
        editor.apply()
        membershipRevision++
    }
    fun createList(name: String, keys: Set<String>): String = synchronized(Companion) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 200 && !trimmed.any(Char::isISOControl)) { "Enter a list name of 1–200 characters." }
        require(GameProgress.fromLabel(trimmed) == null && lists.none { it.equals(trimmed, ignoreCase = true) }) { "A list with this name already exists." }
        preferences.edit()
            .putString("custom-lists", org.json.JSONArray(customLists + trimmed).toString())
            .putStringSet("members:$trimmed", keys)
            .apply()
        membershipRevision++
        trimmed
    }
    fun renameListError(oldName: String, name: String): String? {
        val trimmed = name.trim()
        return when {
            oldName !in customLists -> "Only custom lists can be renamed."
            trimmed.isEmpty() -> "Enter a list name."
            trimmed.length > 200 || trimmed.any(Char::isISOControl) -> "Use 1–200 characters without control characters."
            BUILT_IN_LISTS.any { it.equals(trimmed, ignoreCase = true) } || GameProgress.fromLabel(trimmed) != null ->
                "This name is reserved for a built-in or progress list."
            customLists.any { it != oldName && it.equals(trimmed, ignoreCase = true) } -> "A list with this name already exists."
            else -> null
        }
    }

    fun renameList(oldName: String, name: String): String = synchronized(Companion) {
        val error = renameListError(oldName, name)
        require(error == null) { error.orEmpty() }
        val trimmed = name.trim()
        if (trimmed == oldName) return@synchronized oldName
        val editor = preferences.edit()
            .putString("custom-lists", JSONArray(customLists.map { if (it == oldName) trimmed else it }).toString())
            .putString("list-order", JSONArray(lists.map { if (it == oldName) trimmed else it }).toString())
            .remove("members:$oldName")
            .putStringSet("members:$trimmed", members(oldName).toSet())
        if (list == oldName) editor.putString("list", trimmed)
        check(editor.commit()) { "Could not rename the list." }
        membershipRevision++
        trimmed
    }

    fun libraryGames(): List<LibraryEntry> {
        refresh()
        return savedLibrary ?: preferences.all.entries
        .filter { it.key.startsWith("library:") }
        .map { restoreMetascore(LibraryRecords.read(JSONArray().put(JSONObject(it.value as String))).single()) }
        .also { savedLibrary = it }
    }

    fun mergeBackup(data: JSONObject) = synchronized(Companion) {
        CollectionArchive.validate(data)
        val importedWishlist = WishlistItems.read(data.getJSONArray("wishlist"))
        val mergedWishlist = (wishlistGames() + importedWishlist).distinctBy { it.key }
        require(mergedWishlist.size <= WishlistItems.MAX_ITEMS) { "The combined wishlist is too large." }
        val editor = preferences.edit().putString("wishlist-items", JSONArray(mergedWishlist.map(WishlistItems::json)).toString())
        val custom = customLists.toMutableList()
        val importedLists = data.getJSONArray("lists")
        val importedNames = mutableSetOf<String>()
        val importedDestinations = mutableMapOf<String, String>()
        repeat(importedLists.length()) { index ->
            val item = importedLists.getJSONObject(index)
            val importedName = item.getString("name")
            val name = importedName
            // Reuse existing spelling to avoid creating two lists that differ only by case.
            val existing = lists.find { it.equals(name, true) }
            val destination = existing ?: name
            if (existing == null && destination !in CollectionData.BUILT_IN_LISTS) custom.add(destination)
            // A recreated built-in name is a manual list and keeps that identity in the backup.
            if (item.getBoolean("custom") && destination !in custom) custom.add(destination)
            importedNames.add(destination)
            importedDestinations[importedName] = destination
            editor.putStringSet("members:$destination", members(destination) + CollectionArchive.strings(item.getJSONArray("games")))
        }
        val removed = CollectionArchive.strings(data.getJSONArray("removedBuiltIns")).toSet()
        editor.putStringSet("removed-built-in-lists", (removed + preferences.getStringSet("removed-built-in-lists", emptySet()).orEmpty()) - importedNames + custom.filter { it in BUILT_IN_LISTS })
        editor.putString("custom-lists", JSONArray(custom).toString())
        val progress = data.getJSONObject("progress")
        progress.keys().forEach { key -> editor.putString("progress:$key", progress.getString(key)) }
        LibraryRecords.read(data.getJSONArray("games")).forEach { game ->
            editor.putString("library:${game.key}", LibraryRecords.json(game).toString())
            game.details.metascore?.let { editor.putString("metascore:${game.key}", it.toString()) }
        }
        mergedWishlist.forEach { game ->
            game.details.metascore?.let { editor.putString("metascore:${game.key}", it.toString()) }
        }
        val settings = data.getJSONObject("settings")
        run {
            val order = CollectionArchive.strings(settings.getJSONArray("listOrder")).map { imported ->
                importedDestinations[imported] ?: imported
            }
            editor.putString("list-order", JSONArray((order + lists).distinct()).toString())
        }
        editor.putBoolean("show-game-pass", settings.getBoolean("showGamePass"))
        editor.putLong("random-sort-seed", settings.getLong("randomSeed"))
        editor.putString("theme", settings.getString("theme"))
        editor.putString(YouTubeQueries.PREFERENCE, settings.getString("youtubeQueries"))
        editor.putString("list", importedDestinations[settings.getString("selectedList")] ?: settings.getString("selectedList"))
        editor.putBoolean("fit-cover-art", settings.getBoolean("fitCoverArt"))
        editor.putBoolean("show-hidden-games", settings.getBoolean("showHiddenGames"))
        editor.putString("sort", settings.getString("sort")).putString("tiles", settings.getString("tileSize"))
            .putBoolean("titles", settings.getBoolean("showTitles")).putBoolean("descending", settings.getBoolean("descending"))
            .putLong("data-revision", preferences.getLong("data-revision", 0) + 1)

        check(editor.commit()) { "Could not restore collection data." }
        refresh()
    }

    companion object {
        const val HIDDEN_LIST = "Hidden"
        private val PERMANENT_LISTS = setOf("Downloads", WishlistItems.LIST, HIDDEN_LIST)
        val BUILT_IN_LISTS = listOf("All games", "Steam", "GOG", "Epic", "Xbox", "Game Pass", "Favorites", "Downloads", WishlistItems.LIST, HIDDEN_LIST)
        @Synchronized fun clearStore(context: Context, store: String) {
            CustomArtwork(context).clearStore(store)
            val preferences = context.getSharedPreferences("collection-v1", Context.MODE_PRIVATE)
            val editor = preferences.edit()
            collectionChangesAfterSignOut(preferences.all, store).forEach { (key, value) ->
                if (value == null) editor.remove(key)
                else {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
            }
            check(editor.putLong("data-revision", preferences.getLong("data-revision", 0) + 1).commit()) {
                "Could not clear local collection data."
            }
        }
        @Synchronized fun clearDownloadHistory(context: Context) {
            val preferences = context.getSharedPreferences("collection-v1", Context.MODE_PRIVATE)
            val editor = preferences.edit()
            preferences.all.keys.filter { it.startsWith("download:") || it == "download-keys" }.forEach(editor::remove)
            check(editor.putLong("data-revision", preferences.getLong("data-revision", 0) + 1).commit()) {
                "Could not clear download history."
            }
        }
    }
    fun contains(list: String, key: String) = key in preferences.getStringSet("members:$list", emptySet()).orEmpty()
    fun toggle(list: String, key: String) = synchronized(Companion) {
        val members = preferences.getStringSet("members:$list", emptySet()).orEmpty()
        preferences.edit().putStringSet("members:$list", if (key in members) members - key else members + key).apply()
        membershipRevision++
    }
    fun setMembership(list: String, keys: Set<String>, included: Boolean) = synchronized(Companion) {
        val members = preferences.getStringSet("members:$list", emptySet()).orEmpty()
        preferences.edit().putStringSet("members:$list", if (included) members + keys else members - keys).apply()
        membershipRevision++
    }
    fun discGames(): List<LibraryEntry> = libraryGames().filter { it.remote?.xboxAccess == com.gamefibre.stores.XboxAccess.DISC }

    fun addDisc(game: LibraryEntry) = synchronized(Companion) {
        require(game.store == LibraryStore.XBOX && game.remote != null) { "Choose an Xbox game." }
        val disc = game.copy(remote = game.remote.copy(xboxAccess = com.gamefibre.stores.XboxAccess.DISC), copies = emptyList())
        val row = LibraryRecords.json(disc)
        LibraryRecords.read(JSONArray().put(row))
        check(preferences.edit().putString("library:${disc.key}", row.toString())
            .putLong("data-revision", preferences.getLong("data-revision", 0) + 1).commit()) { "Could not save the disc game." }
        refresh()
    }

    fun removeDisc(key: String) = synchronized(Companion) {
        if (discGames().none { it.key == key }) return@synchronized
        check(preferences.edit().remove("library:$key")
            .putLong("data-revision", preferences.getLong("data-revision", 0) + 1).commit()) { "Could not remove the disc game." }
        refresh()
    }

    fun wishlistGames(): List<LibraryEntry> {
        refresh()
        return savedWishlist ?: WishlistItems.read(JSONArray(preferences.getString("wishlist-items", "[]"))).map(::restoreMetascore)
            .also { savedWishlist = it }
    }
    fun addWishlist(games: List<LibraryEntry>): Int = synchronized(Companion) {
        val old = wishlistGames()
        val updated = (old + games).distinctBy { it.key }
        saveWishlist(updated)
        updated.size - old.size
    }
    fun removeWishlist(keys: Set<String>) = synchronized(Companion) {
        saveWishlist(wishlistGames().filterNot { it.key in keys })
    }
    // The KTX helper hides the commit result needed to report a failed save.
    @android.annotation.SuppressLint("UseKtx")
    private fun saveWishlist(games: List<LibraryEntry>) {
        val data = JSONArray(games.map(WishlistItems::json))
        WishlistItems.read(data)
        check(preferences.edit().putString("wishlist-items", data.toString())
            .putLong("data-revision", preferences.getLong("data-revision", 0) + 1).commit()) { "Could not save the wishlist." }
        refresh()
    }
    private fun records(): Map<String, Pair<LibraryEntry, String>> {
        refresh()
        return savedDownloads ?: preferences.getStringSet("download-keys", emptySet()).orEmpty().mapNotNull { key ->
            runCatching { val data = JSONObject(preferences.getString("download:$key", "")!!)
                val store = LibraryStore.valueOf(data.getString("store"))
                val details = GameDetails.read(data.optJSONObject("details"))
                val game = LibraryEntry(store, data.getString("id"), data.getString("name"), remote =
                    com.gamefibre.stores.StoreGame(data.getString("id"), data.getString("name"),
                        imageUrl = data.optString("artwork"), details = details))
                key to (restoreMetascore(game) to data.optString("output"))
            }.getOrNull()
        }.toMap().also { savedDownloads = it }
    }
    fun downloaded() = records().values.map { it.first }
    fun remember(game: LibraryEntry, output: String) = synchronized(Companion) {
        val data = JSONObject().put("store", game.store.name).put("id", game.id).put("name", game.name)
            .put("artwork", game.artwork).put("details", game.details.json()).put("output", output).toString()
        if (preferences.getString("download:${game.key}", null) == data) return@synchronized
        preferences.edit {
            putString("download:${game.key}", data)
            putStringSet("download-keys", preferences.getStringSet("download-keys", emptySet()).orEmpty() + game.key)
            putLong("data-revision", preferences.getLong("data-revision", 0) + 1)
        }
        savedDownloads = null
        downloadRevision++
    }
    fun output(key: String) = records()[key]?.second.orEmpty()
    fun forget(key: String) = synchronized(Companion) {
        preferences.edit {
            remove("download:$key")
            putStringSet("download-keys", preferences.getStringSet("download-keys", emptySet()).orEmpty() - key)
            putLong("data-revision", preferences.getLong("data-revision", 0) + 1)
        }
        savedDownloads = null
        downloadRevision++
    }
}

internal data class BeatTime(val main: Long, val extras: Long, val complete: Long)
internal class BeatIndex(private val times: Map<String, BeatTime>, private val titles: Map<String, String>,
    private val catalog: SteamTitleIndex? = null) {
    @Volatile private var knownSteam = emptyMap<String, Set<String>>()
    fun updateKnownSteamGames(games: List<LibraryEntry>) {
        knownSteam = games.filter { it.store == LibraryStore.STEAM }.groupBy { it.normalizedName }
            .mapValues { (_, matches) -> matches.map { it.id }.toSet() }
    }
    fun steamId(game: LibraryEntry, knownCopies: List<LibraryEntry> = emptyList()): String? {
        if (game.store == LibraryStore.STEAM) return game.id
        val known = knownSteam[game.normalizedName].orEmpty() + knownCopies.filter {
            it.store == LibraryStore.STEAM && it.normalizedName == game.normalizedName
        }.map { it.id }
        if (known.size > 1) return null
        return known.singleOrNull()
            ?: if (catalog != null) catalog.steamId(game.name)?.toString() else titles[game.normalizedName]
    }
    fun find(game: LibraryEntry): BeatTime? = steamId(game)?.let(times::get)
    companion object {
        private val marks = Regex("\\p{M}+")
        private val trademarks = Regex("[™®©]|\\((?:tm|r|c)\\)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        private val separators = Regex("[^\\p{L}\\p{N}]+")
        @Volatile private var loaded: BeatIndex? = null
        fun normalize(name: String) = Normalizer.normalize(name.replace(trademarks, ""), Normalizer.Form.NFKD)
            .replace(marks, "").lowercase(Locale.ROOT).replace(separators, " ").trim()
        fun parse(csv: Sequence<String>, names: Sequence<String>): BeatIndex {
            val times = linkedMapOf<String, BeatTime>()
            csv.drop(1).forEach { line ->
                val cells = line.split(',')
                if (cells.size >= 2 && cells[0].toLongOrNull()?.let { it > 0 } == true) {
                    fun number(index: Int) = cells.getOrNull(index)?.toLongOrNull()?.coerceAtLeast(0) ?: 0
                    val time = BeatTime(number(1), number(2), number(3))
                    if (time.main + time.extras + time.complete > 0 && !times.containsKey(cells[0])) times[cells[0]] = time
                }
            }
            val candidates = linkedMapOf<String, MutableSet<String>>()
            names.forEach { line ->
                val cells = line.split('\t', limit = 2)
                if (cells.size == 2 && cells[0].toLongOrNull()?.let { it > 0 } == true) candidates.getOrPut(normalize(cells[1])) { linkedSetOf() }.add(cells[0])
            }
            return BeatIndex(times, candidates.filterValues { it.size == 1 }.mapValues { it.value.single() })
        }
        fun load(context: Context): BeatIndex {
            loaded?.let { return it }
            return synchronized(this) {
                loaded ?: run {
                    val catalog = context.assets.open("steam-titles-v2.bin.xz").use(SteamTitleIndex::read)
                    context.assets.open("hltb-index-v1.bin").use { read(it, catalog) }.also { loaded = it }
                }
            }
        }
        fun read(stream: java.io.InputStream): BeatIndex = read(stream, null)
        fun read(stream: java.io.InputStream, catalog: SteamTitleIndex?): BeatIndex {
            val input = java.io.DataInputStream(stream.buffered())
            require(input.readInt() == 0x484c5442 && input.readInt() == 1)
            val count = input.readInt().also { require(it in 0..200_000) }
            val times = HashMap<String, BeatTime>(count * 4 / 3 + 1)
            repeat(count) { times[input.readInt().toString()] = BeatTime(input.readLong(), input.readLong(), input.readLong()) }
            val nameCount = input.readInt().also { require(it in 0..200_000) }
            val names = HashMap<String, String>(if (catalog == null) nameCount * 4 / 3 + 1 else 0)
            repeat(nameCount) {
                // The broader catalog already contains these aliases and checks their ambiguity.
                val length = input.readInt().also { require(it in 0..16_384) }
                if (catalog == null) {
                    val bytes = ByteArray(length); input.readFully(bytes)
                    names[String(bytes, Charsets.UTF_8)] = input.readInt().toString()
                } else {
                    require(input.skipBytes(length) == length) { "Truncated title aliases" }
                    input.readInt()
                }
            }
            return BeatIndex(times, names, catalog)
        }
    }
}
