package com.gamefibre

import com.gamefibre.assets.AssetTransport

import android.app.Activity
import android.graphics.BitmapFactory
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.isNotEmpty
import androidx.core.widget.addTextChangedListener
import kotlinx.coroutines.*

/** A store picker, debounced search and checkable Add actions in the app's native dialog pattern. */
internal fun showCollectionSearch(activity: Activity, ui: NativeUi, parentScope: CoroutineScope,
    artwork: AssetTransport, stores: List<LibraryStore>, contains: (String) -> Boolean,
    add: (LibraryEntry) -> Unit, remove: (LibraryEntry) -> Unit, importSteam: (() -> Unit)?, destination: String = "wishlist") {
    val dialog = ContentDialog(activity, ui, "Add to $destination")
    if (stores.isEmpty()) {
        ui.add(dialog.content, ui.text("Connect Steam, GOG, or Xbox in Accounts to search their stores.", 14))
        dialog.show()
        return
    }
    val job = SupervisorJob(parentScope.coroutineContext[Job])
    val scope = CoroutineScope(parentScope.coroutineContext + job)
    val api = StoreCatalog()
    val imageDispatcher = Dispatchers.IO.limitedParallelism(4)
    var request: Job? = null
    var images: Job? = null
    var selectedStore = stores.first()
    val store = ui.spinner(stores.map { it.label }).apply { contentDescription = "Search store" }
    val query = ui.input("Search the store").apply { imeOptions = EditorInfo.IME_ACTION_SEARCH }
    val status = ui.text("Enter a game title.", 14, ui.secondary).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val results = ui.column()
    if (stores.size > 1) {
        ui.add(dialog.content, store); ui.gap(dialog.content, 12)
    }
    val searchRow = ui.row()
    searchRow.addView(query, LinearLayout.LayoutParams(0, -2, 1f))
    ui.add(dialog.content, searchRow); ui.gap(dialog.content, 12)
    ui.add(dialog.content, status); ui.add(dialog.content, results)
    val done = ui.button("Done", primary = true) { dialog.dismiss() }
    val importButton = importSteam?.let { action ->
        ui.button("Import from Steam") { dialog.dismiss(); action() }
    }
    fun updateActions() {
        dialog.setActions(*listOfNotNull(done.takeIf { results.isNotEmpty() }, importButton).toTypedArray())
    }
    fun search(wait: Long = 0) {
        request?.cancel(); images?.cancel(); results.removeAllViews(); updateActions()
        val text = query.text.toString().trim()
        if (text.length < 2) { status.text = "Enter at least two characters."; return }
        val source = selectedStore
        status.text = "Searching ${source.label}…"
        request = scope.launch {
            try {
                delay(wait)
                val games = withContext(Dispatchers.IO) { api.search(source, text) }
                ensureActive()
                status.text = if (games.isEmpty()) "No matching games. Try another title." else "Check a game to save it to your $destination."
                val imageJob = SupervisorJob(job)
                images = imageJob
                val imageScope = CoroutineScope(scope.coroutineContext + imageJob)
                var missingArt = 0
                games.forEach { game ->
                    val bitmap = withContext(imageDispatcher) {
                        artwork.image(game.details.portrait.ifBlank { game.artwork }, portrait = true)?.let { BitmapFactory.decodeFile(it.path) }
                    }
                    if (bitmap == null) { missingArt++; return@forEach }
                    val row = ui.row().apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) }
                    val cover = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                    cover.setImageBitmap(bitmap)
                    row.addView(cover, LinearLayout.LayoutParams(ui.dp(48), ui.dp(64)).apply { marginEnd = ui.dp(12) })
                    val label = ui.column()
                    ui.add(label, ui.text(game.name, 15))
                    ui.add(label, ui.text(listOf(game.store.label, game.details.platforms.joinToString(", "))
                        .filter(String::isNotBlank).joinToString(" · "), 12, ui.secondary))
                    row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                    val toggle = android.widget.CheckBox(activity).apply {
                        this.text = "Add"; textSize = 14f; typeface = ui.font; setTextColor(ui.blue)
                        buttonTintList = android.content.res.ColorStateList.valueOf(ui.blue)
                        minHeight = ui.dp(48)
                    }
                    fun update() {
                        toggle.setOnCheckedChangeListener(null)
                        toggle.text = if (contains(game.key)) "Added" else "Add"
                        toggle.isChecked = contains(game.key)
                        toggle.contentDescription = "${if (contains(game.key)) "Remove ${game.name} from" else "Add ${game.name} to"} $destination"
                    }
                    fun bind() {
                        toggle.setOnClickListener {
                            if (!toggle.isEnabled) return@setOnClickListener
                            toggle.isEnabled = false
                            scope.launch {
                                try {
                                    if (contains(game.key)) { remove(game); status.text = "Removed ${game.name}." }
                                    else {
                                        val full = withContext(Dispatchers.IO) { api.retrieve(game) }
                                        ensureActive(); add(full); status.text = "Added ${game.name}."
                                    }
                                } catch (error: CancellationException) { throw error }
                                catch (error: Exception) { status.text = error.message ?: "Could not save this game." }
                                finally { toggle.isEnabled = true; update() }
                            }
                        }
                    }
                    update(); bind()
                    row.addView(toggle); ui.add(results, row)
                    if (results.childCount == 1) updateActions()
                }
                if (missingArt > 0) status.text = "$missingArt matches could not prepare artwork. Try again when connected."
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { status.text = error.message ?: "Could not search this store." }
        }
    }
    fun submitSearch() {
        search()
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(query.windowToken, 0)
    }
    searchRow.addView(ui.iconButton(R.drawable.ic_search, "Search games", ::submitSearch).apply {
        tooltipText = "Search games"
    }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginStart = ui.dp(8) })
    query.addTextChangedListener { search(400) }
    query.setOnEditorActionListener { _, action, _ ->
        if (action == EditorInfo.IME_ACTION_SEARCH) {
            submitSearch()
            true
        } else false
    }
    store.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            selectedStore = stores[position]; search()
        }
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
    updateActions()
    dialog.setOnDismissListener { job.cancel() }
    dialog.show()
}

internal fun showSteamWishlistImport(activity: Activity, ui: NativeUi, parentScope: CoroutineScope,
    account: String, save: (List<LibraryEntry>) -> Int) {
    val dialog = ContentDialog(activity, ui, "Import Steam wishlist")
    val job = SupervisorJob(parentScope.coroutineContext[Job])
    val scope = CoroutineScope(parentScope.coroutineContext + job)
    val status = ui.text("Importing your Steam wishlist…", 14).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    ui.add(dialog.content, status)
    dialog.setActions(ui.button("Cancel") { dialog.dismiss() })
    dialog.setOnDismissListener { job.cancel() }
    dialog.show()
    scope.launch {
        try {
            val result = withContext(Dispatchers.IO) { StoreCatalog().steamWishlist(account) }
            ensureActive()
            val added = save(result.games)
            status.text = "Added $added ${if (added == 1) "game" else "games"}. ${result.games.size - added} already on your wishlist." +
                if (result.unavailable > 0) " ${result.unavailable} unavailable or non-game items skipped." else ""
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { status.text = error.message ?: "Could not import the Steam wishlist. Try again." }
        dialog.setActions(ui.button("Done", primary = true) { dialog.dismiss() })
    }
}
