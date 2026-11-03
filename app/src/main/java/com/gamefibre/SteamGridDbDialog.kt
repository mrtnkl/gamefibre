package com.gamefibre

import com.gamefibre.assets.AssetTransport
import com.gamefibre.assets.prepareThumbnails

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import androidx.core.widget.addTextChangedListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

internal fun showSteamGridDbKey(activity: Activity, ui: NativeUi, openUrl: (String) -> Unit, dismissed: () -> Unit = {}, saved: () -> Unit = {}) {
    val vault = SteamGridDbKeyVault(activity)
    val dialog = ContentDialog(activity, ui, "Cover art", secureContent = true)
    val savedKey = vault.read()
    val status = ui.text(if (savedKey == null) "Add your personal SteamGridDB API key to choose custom game covers." else "Your saved API key is shown below. You can edit or replace it.", 14)
    val input = ui.input("API key", password = true).apply {
        background = null
        setText(savedKey.orEmpty())
        transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
    }
    val keyField = ui.row().apply {
        background = ui.shape(ui.background, 8, android.graphics.Color.rgb(128, 134, 139))
    }
    keyField.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
    var keyVisible = false
    keyField.addView(ui.iconButton(R.drawable.ic_eye, "Show API key") {}.apply {
        setOnClickListener {
            val start = input.selectionStart
            val end = input.selectionEnd
            keyVisible = !keyVisible
            input.transformationMethod = if (keyVisible) null else android.text.method.PasswordTransformationMethod.getInstance()
            if (start >= 0 && end >= 0) input.setSelection(start, end)
            contentDescription = if (keyVisible) "Hide API key" else "Show API key"
            (getChildAt(0) as ImageView).setImageResource(if (keyVisible) R.drawable.ic_eye_off else R.drawable.ic_eye)
        }
    })
    ui.add(dialog.content, status); ui.gap(dialog.content)
    ui.add(dialog.content, ui.button("Get an API key") { openUrl("https://www.steamgriddb.com/profile/preferences/api") })
    ui.gap(dialog.content); ui.add(dialog.content, keyField)
    ui.gap(dialog.content)
    ui.add(dialog.content, ui.text("Your key is encrypted on this device. Searches send the game title or Steam ID to SteamGridDB. Selected covers remain available offline.", 13, ui.secondary))
    dialog.setActions(ui.button("Save", primary = true) {
        try { vault.save(input.text.toString().trim()); input.text.clear(); dialog.dismiss(); saved() }
        catch (error: Exception) { status.text = error.message ?: "Could not save the API key." }
    }, ui.button("Remove key") {
        try { vault.clear(); input.text.clear(); dialog.dismiss() }
        catch (error: Exception) { status.text = error.message ?: "Could not remove the API key." }
    })
    dialog.setOnDismissListener { input.text.clear(); dismissed() }
    dialog.show()
}

internal fun showSteamGridDbPicker(activity: Activity, ui: NativeUi, parentScope: CoroutineScope,
    artwork: AssetTransport, name: String, steamId: Int?, hasCustom: Boolean,
    apply: (GridArtwork) -> Unit, reset: () -> Unit) {
    val key = SteamGridDbKeyVault(activity).read() ?: return
    val api = SteamGridDb(key)
    val dialog = ContentDialog(activity, ui, "Choose cover art")
    val job = SupervisorJob(parentScope.coroutineContext[Job])
    val scope = CoroutineScope(parentScope.coroutineContext + job)
    var request: Job? = null
    var selected: GridArtwork? = null
    var applying = false
    lateinit var load: (Int, Boolean, Int) -> Unit
    val query = ui.input("Find a game").apply {
        background = null
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        setText(name)
    }
    val status = ui.text("", 14, ui.secondary)
    val results = ui.column()
    val controls = ui.row()
    val grid = RecyclerView(activity).apply {
        layoutManager = GridLayoutManager(activity, 2)
        isNestedScrollingEnabled = false
        itemAnimator = null
    }
    val applyButton = ui.button("Apply cover", primary = true) {}.apply { isEnabled = false }
    val adapter = GridPickerAdapter(ui, scope, artwork) {
        selected = it; applyButton.isEnabled = true
        status.text = "Selected cover #${it.id}${if (it.author.isBlank()) "" else " by ${it.author}"}"
    }
    grid.adapter = adapter
    val search = ui.row().apply { background = ui.shape(ui.surface, 24) }
    search.addView(query, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
    val clear = ui.iconButton(R.drawable.ic_x, "Clear search") { query.setText("") }
    search.addView(clear)
    clear.visibility = if (query.text.isEmpty()) View.GONE else View.VISIBLE
    ui.add(dialog.content, search)
    fun searchGames(waitMillis: Long = 0) {
        if (applying) return
        val title = query.text.toString().trim()
        request?.cancel(); selected = null; applyButton.isEnabled = false
        adapter.submit(emptyList()); results.removeAllViews(); controls.removeAllViews()
        if (title.isBlank()) { status.text = "Enter a game title."; return }
        status.text = "Searching SteamGridDB…"
        request = scope.launch {
            try {
                delay(waitMillis)
                val games = withContext(Dispatchers.IO) { api.search(title) }
                status.text = if (games.isEmpty()) "No matching games. Try another title." else "Choose the matching game."
                games.forEach { game ->
                    ui.add(results, ui.button(game.name) { load(game.id, false, 0) })
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { status.text = error.message ?: "Could not search SteamGridDB." }
        }
    }
    query.addTextChangedListener {
        clear.visibility = if (query.text.isEmpty()) View.GONE else View.VISIBLE
        searchGames(350)
    }
    query.setOnEditorActionListener { _, action, _ ->
        if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
            searchGames()
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(query.windowToken, 0)
            true
        } else false
    }
    ui.add(dialog.content, status); ui.add(dialog.content, results)
    dialog.content.addView(grid, LinearLayout.LayoutParams(-1, ui.dp(360)))
    ui.add(dialog.content, controls)

    // This local callback also handles page changes and search-result selections.
    load = { id, steam, page ->
        request?.cancel(); selected = null; applyButton.isEnabled = false
        results.removeAllViews(); controls.removeAllViews(); adapter.submit(emptyList())
        status.text = "Loading covers…"
        request = scope.launch {
            try {
                val covers = withContext(Dispatchers.IO) { api.grids(id, steam, page) }
                lateinit var retry: View
                fun prepareCovers() {
                    adapter.submit(covers) { ready, finished ->
                        if (selected == null) {
                            status.text = when {
                                covers.isEmpty() -> "No covers on this page. Try searching by title."
                                !finished -> "Loading covers… $ready of ${covers.size} ready · choose any cover"
                                ready == 0 -> "Could not load cover images. Retry or try another page."
                                else -> "Choose a cover · page ${page + 1} · $ready ready"
                            }
                        }
                        retry.visibility = if (finished && ready < covers.size) View.VISIBLE else View.GONE
                    }
                }
                retry = ui.button("Retry missing covers") { prepareCovers() }.apply { visibility = View.GONE }
                controls.addView(retry)
                prepareCovers()
                if (page > 0) controls.addView(ui.button("Previous") { load(id, steam, page - 1) })
                if (covers.isNotEmpty()) controls.addView(ui.button("Next") { load(id, steam, page + 1) })
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { status.text = error.message ?: "Could not load covers." }
        }
    }
    applyButton.setOnClickListener {
        val choice = selected ?: return@setOnClickListener
        if (applying) return@setOnClickListener
        applying = true; applyButton.isEnabled = false
        // Dismissal cancels thumbnail preparation; saving belongs to the activity.
        dialog.dismiss()
        apply(choice)
    }
    val close = ui.button("Cancel") { dialog.dismiss() }
    if (hasCustom) {
        val resetButton = ui.button("Reset cover") {
            if (!applying) try { reset(); dialog.dismiss() }
            catch (error: Exception) { status.text = error.message ?: "Could not reset cover." }
        }
        dialog.setActions(applyButton, resetButton, close)
    } else dialog.setActions(applyButton, close)
    dialog.setOnDismissListener { job.cancel(); grid.adapter = null }
    dialog.show()
    if (steamId != null) load(steamId, true, 0)
    else searchGames()
}

private class GridPickerAdapter(private val ui: NativeUi, private val scope: CoroutineScope,
    private val artwork: AssetTransport, private val select: (GridArtwork) -> Unit) : RecyclerView.Adapter<GridPickerAdapter.Holder>() {
    private val rows = mutableListOf<GridArtwork>()
    private var selectedId: Int? = null
    class CoverFrame(context: android.content.Context) : FrameLayout(context) {
        var aspectRatio = 2f / 3f
            set(value) { field = value; requestLayout() }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = View.MeasureSpec.getSize(widthMeasureSpec)
            val height = (width / aspectRatio).toInt().coerceAtLeast(1)
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        }
    }
    class Holder(val card: CoverFrame, val image: ImageView, val check: ImageView) : RecyclerView.ViewHolder(card)
    private var preparation: Job? = null
    private val prepared = mutableMapOf<Int, android.graphics.Bitmap>()
    fun submit(value: List<GridArtwork>, progress: (Int, Boolean) -> Unit = { _, _ -> }) {
        preparation?.cancel()
        if (value.isEmpty()) {
            rows.clear(); prepared.clear(); selectedId = null
            notifyDataSetChanged()
            progress(0, true)
            return
        }
        val pending = value.filter { it.id !in prepared }
        progress(rows.size, pending.isEmpty())
        preparation = scope.launch {
            var completed = 0
            artwork.prepareThumbnails(pending).collect { result ->
                result.image?.let { bitmap ->
                    prepared[result.source.id] = bitmap
                    rows.add(result.source)
                    notifyItemInserted(rows.lastIndex)
                }
                completed++
                progress(rows.size, completed == pending.size)
            }
        }
    }
    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val card = CoverFrame(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) }
            isFocusable = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, ui.dp(12).toFloat())
                }
            }
            clipToOutline = true
        }
        val image = ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        card.addView(image, FrameLayout.LayoutParams(-1, -1))
        val check = ui.icon(R.drawable.ic_check, color = ui.onPrimary).apply {
            background = ui.shape(ui.blue, 16)
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
        }
        card.addView(check, FrameLayout.LayoutParams(ui.dp(32), ui.dp(32), Gravity.TOP or Gravity.END).apply {
            topMargin = ui.dp(4); marginEnd = ui.dp(4)
        })
        return Holder(card, image, check)
    }
    private fun bindSelection(holder: Holder, position: Int) {
        val selected = rows[position].id == selectedId
        holder.card.isSelected = selected
        holder.card.foreground = if (selected) ui.shape(android.graphics.Color.TRANSPARENT, 12).apply {
            setStroke(ui.dp(3), ui.blue)
        } else null
        holder.check.visibility = if (selected) View.VISIBLE else View.GONE
        ViewCompat.setStateDescription(holder.card, if (selected) "Selected" else "Not selected")
    }
    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) bindSelection(holder, position)
        else onBindViewHolder(holder, position)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.card.aspectRatio = 2f / 3f
        bindSelection(holder, position)
        val bitmap = requireNotNull(prepared[row.id])
        holder.image.setImageBitmap(bitmap)
        holder.card.aspectRatio = bitmap.width.toFloat() / bitmap.height
        holder.card.contentDescription = "Select cover #${row.id} by ${row.author}"
        holder.card.isEnabled = true
        holder.card.setOnClickListener {
            if (!holder.card.isEnabled) return@setOnClickListener
            val previous = rows.indexOfFirst { it.id == selectedId }
            selectedId = row.id
            if (previous >= 0) notifyItemChanged(previous, "selection")
            val current = holder.bindingAdapterPosition
            if (current != RecyclerView.NO_POSITION) notifyItemChanged(current, "selection")
            select(row)
        }
    }
    override fun onViewRecycled(holder: Holder) = Unit
}
