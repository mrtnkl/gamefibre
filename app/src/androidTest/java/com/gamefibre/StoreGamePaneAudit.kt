package com.gamefibre

import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.gamefibre.stores.StoreGame
import kotlinx.coroutines.Job

/** Focused first-frame tags and the shared game-pane controls; no account or collection data is changed. */
internal object StoreGamePaneAudit {
    fun run(instrumentation: Instrumentation, activity: Activity): String {
        fun <T> ui(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return result!!.getOrThrow()
        }
        fun field(name: String) = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        var openingMs = 0L
        ui {
            // Hold catalog loading so first-frame controls cannot accidentally depend on results/indexing.
            (field("storeCatalogJob").get(activity) as? Job)?.cancel()
            val gate = Job()
            field("storeCatalogJob").set(activity, gate)
            field("storeCatalog").set(activity, null)
            val started = SystemClock.elapsedRealtime()
            activity.javaClass.getDeclaredMethod("selectTab", String::class.java).apply { isAccessible = true }.invoke(activity, "store")
            openingMs = SystemClock.elapsedRealtime() - started
            val search = field("storeSearchView").get(activity) as StoreSearchView
            val strip = search.tagBar.getChildAt(0) as RecyclerView
            check(strip.adapter!!.itemCount == 452) { "Tag labels waited for catalog results" }
            check(search.pendingCount == 0)
            check(search.javaClass.getDeclaredField("result").apply { isAccessible = true }.get(search) == null)
            check(search.tagBar.visibility == View.VISIBLE)
            gate.cancel()
        }
        val report = ui {
            val saved = listOf("ownedLibrary", "ownedKeys", "ownedByTitle", "gameReturnTab").associateWith { field(it).get(activity) }
            val ownedSteam = LibraryEntry(LibraryStore.STEAM, "620", "Owned current title",
                steam = LibraryGame(620, "Owned current title", 350, listOf("windows"), 1234))
            val ownedGog = LibraryEntry(LibraryStore.GOG, "audit-owned", "Owned GOG title",
                remote = StoreGame("audit-owned", "Owned GOG title"))
            val owned = listOf(ownedSteam, ownedGog)
            try {
                field("ownedLibrary").set(activity, owned)
                field("ownedKeys").set(activity, owned.map { it.key }.toSet())
                field("ownedByTitle").set(activity, owned.groupBy { it.normalizedName })
                field("gameReturnTab").set(activity, "store")
                val render = activity.javaClass.getDeclaredMethod("renderGamePage", LibraryEntry::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                fun pane(game: LibraryEntry) = render.invoke(activity, game, true) as ScrollView
                fun texts(root: View) = views(root).filterIsInstance<TextView>().map { it.text.toString() }
                fun assertOwned(game: LibraryEntry) {
                    val root = pane(game)
                    check(texts(root).contains("+ New list")) { "Owned game lost normal list actions" }
                    check(views(root).any { it.contentDescription?.startsWith("Progress:") == true }) { "Owned game lost progress" }
                    check(!texts(root).contains("Tags")) { "Store-origin owned game showed tags" }
                }
                val catalogSteam = CatalogGame(620, "Old catalog title", 95, 0, 0, 0).entry()
                val resolvedSteam = resolveOwnedStoreResult(catalogSteam, owned) { it.id.toIntOrNull() }
                check(resolvedSteam.key == ownedSteam.key && resolvedSteam.steam!!.minutes == 350)
                assertOwned(resolvedSteam)
                val catalogGog = CatalogGame(9999, "Different catalog title", 90, 0, 0, 0).entry()
                val resolvedGog = resolveOwnedStoreResult(catalogGog, owned) { if (it.key == ownedGog.key) 9999 else it.id.toIntOrNull() }
                check(resolvedGog.key == ownedGog.key)
                assertOwned(resolvedGog)
                // Same name as an owned game must not grant ownership to another Steam AppID.
                val unowned = CatalogGame(621, ownedSteam.name, 90, 0, 0, 0).entry()
                val unownedPane = pane(unowned)
                check(views(unownedPane).filterIsInstance<CheckBox>().map { it.text.toString() } == listOf("Wishlist")) {
                    "Unowned store game showed collection pills"
                }
                check(!texts(unownedPane).contains("+ New list"))
                check(!texts(unownedPane).contains("Tags"))
                check(views(unownedPane).none { it.contentDescription?.startsWith("Progress:") == true })
                val copies = activity.javaClass.getDeclaredMethod("ownedCopies", LibraryEntry::class.java).apply { isAccessible = true }.invoke(activity, unowned) as List<*>
                check(copies == listOf(unowned)) { "Unowned result borrowed another game's owned actions" }
                field("gameReturnTab").set(activity, "library")
                check(texts(pane(ownedSteam)).contains("Tags")) { "Normal library game lost its tags" }
                "owned Steam and GOG records keep normal controls; unowned keeps Wishlist only; store-origin tags hidden; library tags retained"
            } finally { saved.forEach { (name, value) -> field(name).set(activity, value) } }
        }
        return "PASS: 452 tags before any catalog/search load (${openingMs}ms opening); $report"
    }
}
