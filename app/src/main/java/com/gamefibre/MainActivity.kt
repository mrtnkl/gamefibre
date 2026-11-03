package com.gamefibre

import android.Manifest
import android.app.Dialog
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.viewpager2.widget.ViewPager2
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.appbar.AppBarLayout
import com.gamefibre.steam.SteamEngine
import com.gamefibre.stores.*
import com.gamefibre.assets.*
import androidx.activity.OnBackPressedCallback
import android.net.Uri
import android.view.inputmethod.EditorInfo
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val collectionTransfer by lazy { androidx.lifecycle.ViewModelProvider(this)[CollectionTransferModel::class.java] }
    private var transferDialog: Dialog? = null
    private val exportCollection = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) startCollectionTransfer(uri, "zip")
    }
    private val exportCollectionCsv = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) startCollectionTransfer(uri, "csv")
    }
    private val importCollection = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startCollectionTransfer(uri, "import")
    }
    private lateinit var ui: NativeUi
    private val appUpdatePrompt by lazy { AppUpdatePrompt(this, ui, scope, ::openUrl) }
    private lateinit var engine: SteamEngine
    private lateinit var customArtwork: CustomArtwork
    private val batchArtworkModel by lazy { androidx.lifecycle.ViewModelProvider(this)[BatchArtworkModel::class.java] }
    private val batchArtwork get() = batchArtworkModel.batch
    private var batchArtworkObservation: Job? = null
    private var batchArtworkDialog: ContentDialog? = null
    private lateinit var artwork: AssetTransport
    private lateinit var assetRepository: AssetRepository
    private var assetObservation: Job? = null
    private var stagedAssetSignature = ""
    private var observedLibraryKeys = emptySet<String>()
    private lateinit var storeDownloads: StoreDownloads
    private var storeSubscription: Job? = null
    private lateinit var collection: CollectionData
    private var beatIndex: BeatIndex? = null
    private var steamTagIndex: SteamTagIndex? = null
    private var storeSearchView: StoreSearchView? = null
    private var storeSearchState: Bundle? = null
    private var libraryMetascores = emptyMap<Int, Int>()
    private var metascoreInput: MergedLibrary? = null
    private var metascoreResult: MergedLibrary? = null
    private var metascoreSource: Map<Int, Int>? = null
    private var metascoreIndex: BeatIndex? = null
    private var metascoreDataRevision = -1L
    private var storeCatalog: SteamCatalog? = null
    private var storeCatalogJob: Job? = null
    private var gameReturnTab = "library"

    private fun renderStoreSearch() {
        val view = ensureStoreSearch()
        (view.parent as? ViewGroup)?.removeView(view)
        body.addView(view, FrameLayout.LayoutParams(-1, -1))
        renderLibraryListControls()
        revealSelectedList()
    }

    private fun ensureStoreSearch(): StoreSearchView {
        val view = storeSearchView ?: StoreSearchView(this, ui, scope,
            open = { game -> openGame(resolveOwnedStoreResult(game, ownedLibrary, ::communitySteamId), emptyList()) }, chooseArtwork = ::changeCoverArtwork,
            artworkReady = { game -> if (tab == "game" && gameReturnTab == "store" && selectedEntry?.let(::communitySteamId) == game.id.toIntOrNull()) render(state, true) },
            saved = storeSearchState).also { storeSearchView = it }
        storeCatalog?.let { view.load(it) }
        if (storeCatalog == null && storeCatalogJob?.isActive != true) storeCatalogJob = scope.launch {
            try {
                val catalog = withContext(Dispatchers.IO) {
                    val tags = SteamTagIndex.load(this@MainActivity)
                    assets.open("steam-catalog-v1.bin.xz").use { stream ->
                        SteamCatalog.read(stream, tags::tags)
                    }
                }
                storeCatalog = catalog
                storeSearchView?.load(catalog)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { this@MainActivity.error = "Could not load store catalog: ${error.message}"; showNotices() }
        }
        return view
    }
    private lateinit var liveSteamTags: LiveSteamTags
    private var tagObservation: Job? = null
    private val tagQueue = kotlinx.coroutines.channels.Channel<Pair<Int, Boolean>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val queuedTagIds = mutableSetOf<Int>()
    private var tagFilters = TagFilters()
    private var tagBarExpanded = false
    private var tagFilterApplyJob: Job? = null
    private var tagFilterBar: TagFilterBar? = null
    private val gameTagSelections = mutableMapOf<String, MutableSet<String>>()
    private var pageTagsUpdate: (() -> Unit)? = null
    private var tagSearchCacheKey = ""
    private var tagSearchCache: LibraryTagSearch? = null
    private var ownedTagFrequencyKey = ""
    private var ownedTagFrequency: LibraryTagSearch? = null
    private var ownedTagRevision = 0L
    private val beatReady = CompletableDeferred<Unit>()
    private var selectedKey = ""
    private var downloadSourceKey = ""
    private var listScrollX = 0
    private var gamePageScrollY = 0
    private val gameHistory = mutableListOf<GamePageVisit>()
    private var gameSequence = emptyList<String>()
    private val relatedRows = mutableListOf<RecyclerView>()
    private var selectedEntry: LibraryEntry? = null
    private var pageProgress: GameProgressView? = null
    private var pageDownloadUpdate: (() -> Unit)? = null
    private var pageDetailsUpdate: (() -> Unit)? = null
    private var pageArtworkJob: Job? = null
    private var pageDetailsJob: Job? = null
    private val youtubeSearch = YouTubeSearch()
    private var pageVideos: YouTubeVideos? = null
    private var retainedVideos: YouTubeVideos? = null
    private var videoLayoutPending = false
    private lateinit var pageDetailsRepository: PageDetailsRepository
    private val pageDetails = PageDetailsMemoryCache()
    @Volatile private var networkRequested = true
    private var listsRow: LibraryListBar? = null
    private lateinit var libraryPageHint: TextView
    private var libraryPageHintJob: Job? = null
    private var searchGameCount: TextView? = null
    private var libraryRecycler: RecyclerView? = null
    private val selectedLibraryKeys = linkedSetOf<String>()
    private var selectionCount: TextView? = null
    private var selectAllButton: View? = null
    private var libraryScroll: android.os.Parcelable? = null
    private var scrollContext = ""
    private lateinit var accounts: StoreAccounts
    private var accountSubscription: Job? = null
    private var entries = emptyList<LibraryEntry>()
    private var ownedLibrary = emptyList<LibraryEntry>()
    private var ownedKeys = emptySet<String>()
    private var wishlistKeys = emptySet<String>()
    private var ownedByTitle = emptyMap<String, List<LibraryEntry>>()
    private val storeFilter get() = listStore(collection.list)
    private fun listStore(name: String) = if (!collection.isAutomaticList(name)) null else when (name) {
        "Steam" -> LibraryStore.STEAM; "GOG" -> LibraryStore.GOG; "Epic" -> LibraryStore.EPIC; "Xbox", "Game Pass" -> LibraryStore.XBOX; else -> null
    }
    private var steamSignIn = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var steamRevision = Long.MIN_VALUE
    private var steamEntries = emptyList<LibraryEntry>()
    private val remoteLibraries = mutableMapOf<LibraryStore, List<StoreGame>>()
    private val remoteEntries = mutableMapOf<LibraryStore, List<LibraryEntry>>()
    private var fullscreenDialog: Dialog? = null
    private var galleryUrls = emptyList<String>()
    private var galleryTitle = ""
    private var galleryIndex = 0
    private var galleryHasCover = false
    private var libraryRevision = 0
    private var retainedLibrary: LibraryScreen? = null
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var appBar: AppBarLayout
    private lateinit var toolbarContent: LinearLayout
    private lateinit var libraryControls: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var collectionAdd: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var bottom: LinearLayout
    private lateinit var rail: LinearLayout
    private lateinit var notice: LinearLayout
    private var screenKey = ""
    private var state = JSONObject()
    private var query = ""
    private var bySize = false
    private var descending = false
    private var tab = "library"
    private var method = "password"
    private var error = ""
    private var errorJob: Job? = null
    private var allGames = emptyList<LibraryGame>()
    private var libraryStatus = ""
    private var downloadDialog: Dialog? = null
    private var collectionMenu: Dialog? = null
    private var dialogUpdate: (() -> Unit)? = null
    private var permissionAction: (() -> Unit)? = null
    private var observing = false
    private val steamUpdates = MutableStateFlow<JSONObject?>(null)
    private var steamSubscription: Job? = null
    private val observer: (JSONObject) -> Unit = { steamUpdates.value = it }
    private val wide get() = resources.configuration.screenWidthDp >= 760
    private val landscape get() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private fun libraryColumns(name: String): Int {
        if (name == "Downloads") return if (landscape) 2 else 1
        if (collection.tileSize == "List") return 1
        val availableWidth = resources.configuration.screenWidthDp - 32
        if (!landscape && collection.tileSize == "Large") {
            val mediumColumns = (availableWidth / 155).coerceAtLeast(2)
            return (availableWidth / 210).coerceIn(1, mediumColumns - 1)
        }
        return (availableWidth / when (collection.tileSize) {
            "Small" -> 115; "Large" -> 210; else -> 155
        }).coerceAtLeast(2)
    }
    private val connected get() = state.optString("status") == "signedIn"
    private val anyAccount get() = collection.libraryGames().isNotEmpty() || connected || state.optBoolean("sessionAvailable") || state.optBoolean("libraryCached") || accounts.state.value.values.any { it.saved || it.libraryLoaded }
    private val libraryLoading get() = state.optBoolean("libraryLoading") || accounts.state.value.values.any { it.loading }
    private val transfer get() = state.optJSONObject("transfer")
    private val busy get() = transfer?.optString("phase") in listOf("preparing", "downloading", "pausing", "exporting")

    override fun onCreate(savedInstanceState: Bundle?) {
        collection = CollectionData(this)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(when (collection.preferences.getString("theme", "System")) {
            "Light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "Dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ui = NativeUi(this)
        customArtwork = CustomArtwork(this)
        assetRepository = AssetRepository.get(this)
        artwork = assetRepository.transport
        pageDetailsRepository = PageDetailsRepository(this)
        liveSteamTags = LiveSteamTags(java.io.File(filesDir, "live-steam-tags"))
        tagFilters = TagFilters(savedInstanceState?.getStringArrayList("includedTags").orEmpty().toSet(),
            savedInstanceState?.getStringArrayList("excludedTags").orEmpty().toSet())
        tagBarExpanded = savedInstanceState?.getBoolean("tagBarExpanded") ?: false
        selectedKey = savedInstanceState?.getString("selectedKey").orEmpty()
        selectedEntry = savedInstanceState?.getString("catalogSelection")?.let { encoded ->
            WishlistItems.read(org.json.JSONArray().put(JSONObject(encoded))).single()
        }
        savedInstanceState?.getStringArrayList("gameTagSelection")?.let { gameTagSelections[selectedKey] = it.toMutableSet() }
        downloadSourceKey = savedInstanceState?.getString("downloadSourceKey").orEmpty()
        listScrollX = savedInstanceState?.getInt("listScrollX") ?: 0
        gamePageScrollY = savedInstanceState?.getInt("gamePageScrollY") ?: 0
        gameSequence = savedInstanceState?.getStringArrayList("gameSequence").orEmpty()
        selectedLibraryKeys.addAll(savedInstanceState?.getStringArrayList("selectedLibraryKeys").orEmpty())
        savedInstanceState?.getStringArrayList("gameHistory")?.forEach { encoded ->
            runCatching {
                val visit = JSONObject(encoded)
                gameHistory.add(GamePageVisit(visit.getString("key"), visit.optString("source"), visit.optInt("scroll"),
                    visit.optJSONArray("sequence")?.let { keys -> List(keys.length()) { keys.getString(it) } }.orEmpty(),
                    visit.optJSONObject("game")?.let { WishlistItems.read(org.json.JSONArray().put(it)).single() }))
            }
        }
        libraryScroll = savedInstanceState?.let { androidx.core.os.BundleCompat.getParcelable(it, "libraryScroll", android.os.Parcelable::class.java) }
        scrollContext = savedInstanceState?.getString("scrollContext").orEmpty()
        scope.launch {
            val beatStarted = android.os.SystemClock.elapsedRealtime()
            beatIndex = withContext(Dispatchers.IO) { runCatching { BeatIndex.load(this@MainActivity) }.getOrNull() }
            steamTagIndex = withContext(Dispatchers.IO) { runCatching { SteamTagIndex.load(this@MainActivity) }.getOrNull() }
            libraryMetascores = withContext(Dispatchers.IO) {
                runCatching { assets.open("steam-catalog-v1.bin.xz").use(SteamCatalog::readMetascores) }.getOrDefault(emptyMap())
            }
            libraryRevision++
            liveSteamTags.load()
            beatIndex?.updateKnownSteamGames(steamEntries)
            logPerformance("index", beatStarted)
            beatReady.complete(Unit)
            scheduleTagEnrichment()
            if (tab == "game" && gameReturnTab == "store") render(state, true)
            else if (tab == "game") {
                pageDetailsUpdate?.invoke()
                screenKey = gameScreenKey()
            } else render(state, true)
        }
        repeat(2) {
            scope.launch {
                beatReady.await()
                for ((id, force) in tagQueue) {
                    try {
                        if (networkRequested && NetworkAvailability.canRequest(this@MainActivity)) liveSteamTags.refresh(id, force)
                    } finally { queuedTagIds.remove(id) }
                }
            }
        }
        storeSearchState = savedInstanceState?.getBundle("storeSearch")
        gameReturnTab = savedInstanceState?.getString("gameReturnTab") ?: "library"
        query = savedInstanceState?.getString("query") ?: ""
        bySize = savedInstanceState?.getBoolean("bySize") ?: false
        descending = savedInstanceState?.getBoolean("descending") ?: collection.preferences.getBoolean("descending", false)
        tab = savedInstanceState?.getString("tab") ?: "library"
        method = savedInstanceState?.getString("method") ?: "password"
        steamSignIn = savedInstanceState?.getBoolean("steamSignIn") ?: false
        engine = SteamEngine.get(this)
        accounts = StoreAccounts.get(this)
        storeDownloads = StoreDownloads.get(this)
        if (savedInstanceState == null) {
            val steam = engine.snapshot()
            val hasAccount = steam.optString("status") == "signedIn" || steam.optBoolean("sessionAvailable") || steam.optBoolean("libraryCached") ||
                accounts.state.value.values.any { it.connected || it.saved || it.libraryLoaded }
            tab = when {
                intent.getBooleanExtra("downloads", false) -> "library"
                hasAccount -> "library"
                else -> "accounts"
            }
        }
        if ((savedInstanceState == null && intent.getBooleanExtra("downloads", false)) || tab == "downloads") { collection.list = "Downloads"; tab = "library" }
        createShell()
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !ui.dark; isAppearanceLightNavigationBars = !ui.dark
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (tab == "library" && selectedLibraryKeys.isNotEmpty()) clearLibrarySelection()
                else if (tab == "game") backFromGame()
                else if (steamSignIn) { steamSignIn = false; render(state, true) }
                else if (tab != "library") selectTab("library")
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        render(engine.snapshot())
        scope.launch {
            collectionTransfer.status.collect { status ->
                if (status?.running == true) {
                    if (transferDialog == null) {
                        val (dialog, content) = modal("Collection")
                        ui.add(content, ui.text(status.message, 15))
                        ui.gap(content, 16)
                        ui.add(content, ProgressBar(this@MainActivity))
                        dialog.setCancelable(false)
                        transferDialog = dialog
                        showModal(dialog)
                    }
                } else {
                    transferDialog?.dismiss(); transferDialog = null
                    if (status != null) {
                        if (status.imported) {
                            collection.refresh()
                            descending = collection.preferences.getBoolean("descending", false)
                            stagedAssetSignature = ""
                            retainedLibrary = null
                            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(when (collection.preferences.getString("theme", "System")) {
                                "Light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                                "Dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                            })
                            render(engine.snapshot(), true)
                        }
                        collectionTransfer.acknowledge()
                        val notice = ContentDialog(this@MainActivity, ui, "Collection")
                        ui.add(notice.content, ui.text(status.message))
                        notice.setActions(ui.button("OK") { notice.dismiss() })
                        notice.show()
                    }
                }
            }
        }
        batchArtwork?.let(::observeBatchArtwork)
        savedInstanceState?.getStringArrayList("galleryUrls")?.takeIf { it.isNotEmpty() }?.let { urls ->
            openScreenshotGallery(urls, savedInstanceState.getString("galleryTitle").orEmpty(), savedInstanceState.getInt("galleryIndex"),
                hasCover = savedInstanceState.getBoolean("galleryHasCover"))
        }
    }

    override fun onStart() {
        super.onStart(); networkRequested = true; observing = true; engine.observe(observer); render(engine.snapshot())
        assetObservation=scope.launch { assetRepository.library.collect { if (observing) render(engine.snapshot()) } }
        steamUpdates.value = engine.snapshot()
        steamSubscription = scope.launch { steamUpdates.collect { value -> if (value != null) renderSteamUpdate(value) } }
        storeSubscription = scope.launch {
            var previous: StoreTransfer? = null
            storeDownloads.state.collect { value ->
                if (previous != null && value != null && previous?.request == value.request && previous?.phase == value.phase) {
                    updateTransferViews()
                } else render(engine.snapshot())
                previous = value
            }
        }
        accountSubscription=scope.launch { accounts.state.collect { render(state) } }
        tagObservation = scope.launch { liveSteamTags.revision.collect {
            if (tab == "library") updateLibrary(true)
            else if (tab == "game") pageTagsUpdate?.invoke()
        } }
        scheduleTagEnrichment()
    }
    override fun onResume() {
        super.onResume()
        engine.restoreSession()
        // A download renews its own store session; avoid racing that account operation.
        if (storeDownloads.state.value?.running != true) accounts.restoreSessions()
        engine.refreshAuthentication(); assetRepository.resume(); render(engine.snapshot()); pageVideos?.resume()
    }
    override fun onPostResume() { super.onPostResume(); appUpdatePrompt.onResume() }
    override fun onPause() { pageVideos?.pause(); super.onPause() }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (pageVideos?.isFullscreen == true) {
            videoLayoutPending = true
        } else if (pageVideos != null) {
            rebuildVideoLayout()
        } else {
            recreate()
        }
    }

    private fun rebuildVideoLayout() {
        val videos = pageVideos ?: return
        videoLayoutPending = false
        videos.preserveForLayoutChange {
            (videos.parent as? ViewGroup)?.removeView(videos)
            pageVideos = null
            retainedVideos = videos
            try {
                header.setPadding(ui.dp(16), ui.dp(if (landscape) 4 else 16), ui.dp(16), ui.dp(if (landscape) 4 else 16))
                render(state, true)
            } finally {
                retainedVideos = null
                if (pageVideos !== videos) videos.dispose()
            }
        }
    }

    private fun videoFullscreenClosed() {
        if (videoLayoutPending) root.post {
            if (videoLayoutPending && pageVideos?.isFullscreen == false) rebuildVideoLayout()
        }
    }
    override fun onStop() { steamSubscription?.cancel(); steamSubscription = null; tagObservation?.cancel(); tagObservation = null; assetObservation?.cancel(); assetObservation=null; networkRequested = false; observing = false; engine.removeObserver(observer); storeSubscription?.cancel(); storeSubscription=null; accountSubscription?.cancel(); accountSubscription=null; super.onStop() }
    override fun onDestroy() {
        appUpdatePrompt.close()
        storeSearchView?.close()
        pageVideos?.dispose(); pageVideos = null
        batchArtworkDialog?.dismiss(); transferDialog?.dismiss(); fullscreenDialog?.dismiss(); collectionMenu?.dismiss(); downloadDialog?.dismiss(); dialogUpdate = null; scope.cancel(); super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (fullscreenDialog?.isShowing == true) {
            outState.putStringArrayList("galleryUrls", ArrayList(galleryUrls))
            outState.putString("galleryTitle", galleryTitle); outState.putInt("galleryIndex", galleryIndex)
            outState.putBoolean("galleryHasCover", galleryHasCover)
        }
        outState.putBundle("storeSearch", storeSearchView?.snapshot() ?: storeSearchState)
        outState.putString("gameReturnTab", gameReturnTab)
        if (tab == "game" && gameReturnTab == "store") selectedEntry?.let {
            outState.putString("catalogSelection", WishlistItems.json(it).toString())
        }
        outState.putParcelable("libraryScroll", libraryRecycler?.layoutManager?.onSaveInstanceState() ?: libraryScroll)
        outState.putString("scrollContext", scrollContext)
        outState.putStringArrayList("gameSequence", ArrayList(gameSequence))
        outState.putStringArrayList("gameHistory", ArrayList(gameHistory.map {
            JSONObject().put("key", it.key).put("source", it.source).put("scroll", it.scrollY).put("sequence", org.json.JSONArray(it.sequence))
                .put("game", it.game?.let(WishlistItems::json)).toString()
        }))
        outState.putStringArrayList("selectedLibraryKeys", ArrayList(selectedLibraryKeys))
        outState.putString("selectedKey", selectedKey)
        outState.putString("downloadSourceKey", downloadSourceKey)
        outState.putInt("gamePageScrollY", if (tab == "game") (body.getChildAt(0) as? ScrollView)?.scrollY ?: gamePageScrollY else gamePageScrollY)
        outState.putInt("listScrollX", (listsRow?.parent as? HorizontalScrollView)?.scrollX ?: listScrollX)
        outState.putBoolean("steamSignIn", steamSignIn)
        outState.putString("query", query); outState.putBoolean("bySize", bySize)
        outState.putStringArrayList("includedTags", ArrayList(tagFilters.included))
        outState.putStringArrayList("excludedTags", ArrayList(tagFilters.excluded))
        outState.putBoolean("tagBarExpanded", tagBarExpanded)
        outState.putStringArrayList("gameTagSelection", ArrayList(gameTagSelections[selectedKey].orEmpty()))
        outState.putBoolean("descending", descending); outState.putString("tab", tab); outState.putString("method", method)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.getBooleanExtra("downloads", false)) { collection.list = "Downloads"; selectTab("library") }
    }

    private fun createShell() {
        root = ui.column().apply {
            setBackgroundColor(ui.background)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
        }
        header = ui.column().apply { setPadding(ui.dp(16), ui.dp(if (landscape) 4 else 16), ui.dp(16), ui.dp(if (landscape) 4 else 16)) }
        notice = ui.column()
        bottom = ui.row().apply { background = ui.shape(ui.surface, 0) }
        rail = ui.column(12)
        body = FrameLayout(this)
        libraryControls = ui.column().apply { setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(8)) }
        toolbarContent = ui.column()
        ui.add(toolbarContent, header)
        ui.add(toolbarContent, libraryControls)
        ui.add(toolbarContent, notice)
        appBar = AppBarLayout(this).apply {
            setBackgroundColor(ui.background)
            elevation = 0f; stateListAnimator = null
            addView(toolbarContent, AppBarLayout.LayoutParams(-1, -2))
        }
        val coordinator = CoordinatorLayout(this)
        val center = ui.row().apply { gravity = Gravity.TOP }
        center.addView(rail, LinearLayout.LayoutParams(ui.dp(220), -1))
        center.addView(body, LinearLayout.LayoutParams(0, -1, 1f))
        coordinator.addView(center, CoordinatorLayout.LayoutParams(-1, -1).apply {
            behavior = AppBarLayout.ScrollingViewBehavior()
        })
        coordinator.addView(appBar, CoordinatorLayout.LayoutParams(-1, -2))
        collectionAdd = com.google.android.material.floatingactionbutton.FloatingActionButton(
            android.view.ContextThemeWrapper(this, R.style.AppMaterialDialogTheme)
        ).apply {
            setImageResource(R.drawable.ic_add)
            contentDescription = "Add to wishlist"
            tooltipText = "Add to wishlist"
            size = com.google.android.material.floatingactionbutton.FloatingActionButton.SIZE_NORMAL
            backgroundTintList = android.content.res.ColorStateList.valueOf(ui.blue)
            imageTintList = android.content.res.ColorStateList.valueOf(ui.onPrimary)
            visibility = View.GONE
            setOnClickListener { if (collection.list == "Xbox") showXboxDiscPicker() else showWishlistPicker() }
        }
        coordinator.addView(collectionAdd, CoordinatorLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
        })
        libraryPageHint = ui.text("", 14, ui.blue, medium = true).apply {
            gravity = Gravity.CENTER
            maxWidth = resources.displayMetrics.widthPixels - ui.dp(48)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
            background = ui.shape(ui.paleBlue, 24)
            elevation = ui.dp(6).toFloat()
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        coordinator.addView(libraryPageHint, CoordinatorLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = ui.dp(12)
        })
        appBar.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { _, _ ->
            if (libraryPageHint.visibility == View.VISIBLE && libraryListBarVisible()) hideLibraryPageHint()
        })
        root.addView(coordinator, LinearLayout.LayoutParams(-1, 0, 1f)); ui.add(root, bottom)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
    }

    private fun logPerformance(stage: String, started: Long, extra: String = "") {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            android.util.Log.d("LibraryPerf", "$stage ${android.os.SystemClock.elapsedRealtime() - started}ms $extra")
    }
    /** Account status and artwork publications do not change the metadata inputs. */
    private fun enrichLibraryMetascores(library: MergedLibrary): MergedLibrary {
        val previous = metascoreInput
        val dataRevision = collection.preferences.getLong("data-revision", 0)
        if (metascoreSource === libraryMetascores && metascoreIndex === beatIndex &&
            metascoreDataRevision == dataRevision && previous != null &&
            previous.owned == library.owned && previous.combined == library.combined &&
            previous.wishlistKeys == library.wishlistKeys && previous.observedKeys == library.observedKeys) {
            metascoreResult?.let { return it }
        }
        return collection.enrichMetascores(library, libraryMetascores, ::communitySteamId).also {
            metascoreInput = library
            metascoreResult = it
            metascoreSource = libraryMetascores
            metascoreIndex = beatIndex
            metascoreDataRevision = collection.preferences.getLong("data-revision", 0)
        }
    }

    private fun render(value: JSONObject, force: Boolean = false) {
        window.protectScreenCapture(tab == "accounts")
        val renderStarted = android.os.SystemClock.elapsedRealtime()
        state = value
        collection.refresh()
        val revision = state.optLong("libraryRevision")
        if (steamRevision != revision) {
            steamRevision = revision
            val games = state.optJSONArray("games")?.let(::readGames) ?: emptyList()
            if (games != allGames) {
                allGames = games
                steamEntries = games.map { LibraryEntry(LibraryStore.STEAM, it.appId.toString(), it.name, steam = it) }
                beatIndex?.updateKnownSteamGames(steamEntries)
            }
        }
        LibraryStore.entries.filter { it.account != null }.forEach { source ->
            val games = accounts.state.value.getValue(source.account!!).games
            if (remoteLibraries[source] !== games) {
                remoteLibraries[source] = games
                remoteEntries[source] = games.map { LibraryEntry(source, it.id, it.name, remote = it) }
            }
        }
        val owners = libraryOwners()
        val merged = enrichLibraryMetascores(mergeLibrary(steamEntries, remoteEntries.values.flatten(), collection.libraryGames(), assetRepository.remembered(owners),
            collection.wishlistGames(), collection.downloaded(), transferEntries()))
        if (ownedLibrary != merged.owned) {
            ownedLibrary = merged.owned
            ownedTagRevision++
            ownedKeys = merged.ownedKeys
            ownedByTitle = merged.ownedByTitle
        }
        wishlistKeys = merged.wishlistKeys
        val combined = merged.combined
        observedLibraryKeys = merged.observedKeys
        // Cover choices are published through refreshCustomArtwork; only library shape changes re-stage everything.
        val assetSignature = "${combined.hashCode()}:${owners}:${observedLibraryKeys.hashCode()}:${ownedKeys.hashCode()}"
        if (stagedAssetSignature != assetSignature) {
            stagedAssetSignature = assetSignature
            assetRepository.synchronize(combined, owners, combined.filter { listStore(collection.list) == it.store }.take(24).map { it.key }.toSet(), observed=observedLibraryKeys, owned=ownedKeys)
        }
        selectedEntry = combined.find { it.key == selectedKey } ?: selectedEntry
        if (tab == "game" && gameReturnTab == "store") selectedEntry?.let { current ->
            val resolved = resolveOwnedStoreResult(current, ownedLibrary, ::communitySteamId)
            if (resolved.key != selectedKey) { selectedKey = resolved.key; downloadSourceKey = resolved.key }
            selectedEntry = resolved
        }
        val status = "$libraryLoading:$anyAccount:${accounts.state.value.values.map { it.error }}:${assetRepository.library.value.revision}"
        val changedData = entries != combined
        if (changedData) libraryRevision++
        val gamesChanged = changedData || libraryStatus != status
        libraryStatus = status; entries = combined
        if (changedData) scheduleTagEnrichment()
        if (changedData && selectedLibraryKeys.isNotEmpty()) {
            val byKey = combined.associateBy { it.key }
            val reconciled = selectedLibraryKeys.mapNotNull { byKey[it] }.map(::librarySelectionKey).toSet()
            if (reconciled != selectedLibraryKeys) {
                selectedLibraryKeys.clear(); selectedLibraryKeys.addAll(reconciled)
                updateLibrarySelection()
            }
        }
        if (connected && steamSignIn) steamSignIn = false
        if (header.childCount == 0 || header.tag != "$tab:$steamSignIn:${collection.list}" || force) renderHeader()
        updateLibraryRefresh()
        showNotices()
        renderNavigation()
        val nextKey = when {
            tab == "store" -> "store"
            tab == "settings" -> "settings"
            tab == "game" -> gameScreenKey()
            tab == "accounts" && steamSignIn -> "auth:${state.optString("status")}:${state.optString("guardError")}:${state.optString("challengeUrl")}:${state.optString("guardEmail")}:${state.optBoolean("canUseGuardCode")}:$method:${state.optBoolean("sessionAvailable")}"
            tab == "accounts" -> "accounts:${state.optString("status")}:${state.optString("connectionError")}:${state.optBoolean("sessionAvailable")}:${allGames.size}:${state.optBoolean("libraryLoading")}:${accounts.state.value.map { (store, account) -> listOf(store, account.saved, account.connected, account.loading, account.games.size, account.error) }}:$busy"
            else -> "library:${collection.tileSize}"
        }
        if (nextKey != screenKey || force) {
            hideLibraryPageHint()
            if (screenKey.startsWith("game:$selectedKey:") && tab == "game") gamePageScrollY = (body.getChildAt(0) as? ScrollView)?.scrollY ?: gamePageScrollY
            screenKey = nextKey
            libraryRecycler?.layoutManager?.onSaveInstanceState()?.let { libraryScroll = it }
            (listsRow?.parent as? HorizontalScrollView)?.let { listScrollX = it.scrollX }
            relatedRows.forEach { it.adapter = null }; relatedRows.clear()
            pageVideos?.dispose(); pageVideos = null
            body.removeAllViews()
            pageArtworkJob?.cancel(); pageArtworkJob = null
            pageDetailsJob?.cancel(); pageDetailsJob = null
            pageProgress = null; pageDownloadUpdate = null; pageDetailsUpdate = null; pageTagsUpdate = null; libraryRecycler = null
            when {
                tab == "store" -> renderStoreSearch()
                tab == "settings" -> renderSettings()
                tab == "game" -> selectedEntry?.let {
                    if (gameReturnTab == "store") ensureStoreSearch()
                    renderGamePage(it)
                } ?: renderLibrary()
                tab == "accounts" && steamSignIn -> renderAuth()
                tab == "accounts" -> renderAccounts()
                else -> renderLibrary()
            }
            libraryControls.visibility = if (tab == "library" || tab == "store") View.VISIBLE else View.GONE
            toolbarContent.layoutParams = (toolbarContent.layoutParams as AppBarLayout.LayoutParams).apply {
                scrollFlags = if ((tab == "library" && selectedLibraryKeys.isEmpty()) || tab == "store") AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                    AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS or AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP else 0
            }
            appBar.setExpanded(true, false)
            if (tab == "game") (body.getChildAt(0) as? ScrollView)?.post { (body.getChildAt(0) as? ScrollView)?.scrollTo(0, gamePageScrollY) }
        }
        if (tab == "library") {
            updateLibrary(gamesChanged || collection.list == "Downloads")
        }
        updateTransferViews()
        logPerformance("render", renderStarted, "tab=$tab force=$force")
    }

    /** Progress never rebuilds the library or its navigation controls. */
    private fun renderSteamUpdate(value: JSONObject) {
        val oldTransfer = state.optJSONObject("transfer")
        val newTransfer = value.optJSONObject("transfer")
        if (screenKey.isNotEmpty() && value.optLong("contentRevision") == state.optLong("contentRevision") &&
            oldTransfer?.optInt("appId") == newTransfer?.optInt("appId") &&
            oldTransfer?.optString("phase") == newTransfer?.optString("phase")) {
            state = value
            updateTransferViews()
        } else render(value)
    }

    private fun updateTransferViews() {
        if (tab == "library") retainedLibrary?.pages?.values?.forEach { page ->
            if (page.name != "Downloads") return@forEach
            val recycler = page.recycler
            for (index in 0 until recycler.childCount) (recycler.getChildViewHolder(recycler.getChildAt(index)) as? GameHolder)?.let { holder ->
                holder.boundGame?.let { bindLibraryProgress(holder.progress, it, page.name) }
            }
        }
        if (tab == "game") {
            pageDownloadUpdate?.invoke()
            selectedEntry?.let { game -> pageProgress?.let { bindProgress(it, game, hideCompleted = true) } }
        }
        dialogUpdate?.invoke()
    }

    private fun renderHeader() {
        val addLabel = if (collection.list == "Xbox") "Add Xbox disc games" else "Add to wishlist"
        collectionAdd.contentDescription = addLabel
        collectionAdd.tooltipText = addLabel
        collectionAdd.visibility = if (tab == "library" && (collection.list == WishlistItems.LIST ||
            (collection.list == "Xbox" && collection.isAutomaticList("Xbox"))) &&
            selectedLibraryKeys.isEmpty()) View.VISIBLE else View.GONE
        header.removeAllViews(); header.tag = "$tab:$steamSignIn:${collection.list}"
        tagFilterBar = null
        searchGameCount = null
        selectionCount = null
        selectAllButton = null
        if (tab == "library" && selectedLibraryKeys.isNotEmpty()) {
            val bar = ui.row().apply {
                background = ui.shape(ui.paleBlue, 12)
                minimumHeight = ui.dp(56)
            }
            bar.addView(ui.iconButton(R.drawable.ic_x, "Clear selection", ::clearLibrarySelection))
            selectionCount = ui.text(selectedLibraryKeys.size.toString(), 20, medium = true).apply {
                contentDescription = "${selectedLibraryKeys.size} selected"
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                bar.addView(this, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
            }
            selectAllButton = ui.button("Select all", action = ::selectAllLibraryGames).apply {
                contentDescription = "Select all games in current view"
                bar.addView(this)
            }
            bar.addView(ui.button(if (collection.list == WishlistItems.LIST) "Remove" else "Manage", action = ::showManageSelection))
            updateSelectAllButton()
            ui.add(header, bar)
            return
        }
        val top = ui.row()
        val showBack = (tab != "library" && tab != "store") || steamSignIn
        top.addView(ui.iconButton(if (showBack) R.drawable.ic_arrow_left else R.drawable.ic_menu,
            if (showBack) "Back" else "Menu") {
            if (showBack) onBackPressedDispatcher.onBackPressed() else showCollectionMenu()
        })
        if (tab == "library" || tab == "store") {
            val storeSearch = if (tab == "store") ensureStoreSearch() else null
            val searchQuery = storeSearch?.queryText ?: query
            val search = ui.row().apply { background = ui.shape(ui.surface, 24) }
            val input = ui.input(if (storeSearch != null) "Search Steam games" else "Search your collection").apply { background = null; setText(searchQuery) }
            search.addView(input, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
            searchGameCount = ui.text("0", 12, ui.secondary).apply {
                isSingleLine = true
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, ui.dp(14), 0)
                search.addView(this, LinearLayout.LayoutParams(-2, -1))
            }
            val clear = ui.iconButton(R.drawable.ic_x, "Clear search") { input.setText("") }
            search.addView(clear); clear.visibility = if (searchQuery.isEmpty()) View.GONE else View.VISIBLE
            if (storeSearch != null) searchGameCount?.let(storeSearch::bindCount)
            input.addTextChangedListener(watcher {
                val text = input.text.toString()
                clear.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                if (storeSearch != null) storeSearch.setQuery(text)
                else { query = text; updateLibrary(true) }
            })
            top.addView(search, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
            top.addView(ui.iconButton(R.drawable.ic_more_vertical, "Collection options", ::showLibraryOptions).apply { tag = "collection-options" })
            ui.add(header, top)
            if (storeSearch != null) {
                (storeSearch.tagBar.parent as? ViewGroup)?.removeView(storeSearch.tagBar)
                header.addView(storeSearch.tagBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
            } else if (tagBarExpanded) {
                tagFilterBar = TagFilterBar(this, ui) { value ->
                    tagFilters = value
                    if (!value.active) {
                        tagBarExpanded = false
                        tagFilterBar?.let { header.removeView(it) }
                        tagFilterBar = null
                    }
                    // Let the native checkbox paint its new state before updating results.
                    tagFilterApplyJob?.cancel()
                    tagFilterApplyJob = scope.launch {
                        delay(16)
                        if (tab == "library") {
                            libraryScroll = null
                            scrollContext = ""
                            libraryRecycler?.scrollToPosition(0)
                            retainedLibrary?.pages?.get(collection.list)?.let(::updateLibraryPage)
                            activateLibraryPage()
                            updateTagFilterBar()
                        }
                    }
                }.also { bar ->
                    header.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
                }
                updateTagFilterBar()
            }
        } else {
            if (tab == "game") {
                top.addView(Space(this), LinearLayout.LayoutParams(0, ui.dp(48), 1f))
                top.addView(ui.iconButton(R.drawable.ic_more_vertical, "Game actions", ::showGameActions).apply { tag = "game-actions" })
            } else {
                top.addView(ui.text(if (tab == "store") "Steam search" else if (steamSignIn) "Connect Steam" else if (tab == "settings") "Settings" else "Accounts", 20, medium = true).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            ui.add(header, top)
        }
    }

    private fun refreshLibraries() {
        if (!NetworkAvailability.canRequest(this)) {
            retainedLibrary?.refresh?.dismissRefreshIndicator()
            return
        }
        networkRequested = true
        retainedLibrary?.refresh?.acknowledgeRefresh()
        if (libraryLoading) {
            updateLibraryRefresh()
            return
        }
        act {
            pageDetailsJob?.cancel(); pageDetailsJob = null
            pageDetails.invalidate()
            pageDetailsRepository.invalidate()
            // Refresh account data without invalidating usable artwork or rebinding unchanged cards.
            if (connected) engine.refreshLibrary() else if (state.optBoolean("sessionAvailable") && state.optString("status") == "signedOut") engine.signIn(forceLibraryRefresh = true)
            GameStore.entries.filter { accounts.state.value.getValue(it).saved }.forEach(accounts::refresh)
            assetRepository.synchronize(entries, libraryOwners(), libraryVisibleKeys(), refresh = true, observed=observedLibraryKeys, owned=ownedKeys)
            scheduleTagEnrichment(force = true)
        }
    }

    private fun libraryOwners(): Map<LibraryStore, String> = buildMap {
        engine.libraryCache.owner()?.let { put(LibraryStore.STEAM, it) }
        LibraryStore.entries.forEach { store -> store.account?.let { account -> accounts.libraryOwner(account)?.let { put(store, it) } } }
    }
    private fun libraryVisibleKeys(): Set<String> = retainedLibrary?.pages?.get(collection.list)?.adapter?.viewportKeys().orEmpty()

    private fun updateLibraryRefresh() {
        val refreshing = libraryLoading || (state.optBoolean("sessionAvailable") &&
            state.optString("status") in listOf("connecting", "restoring", "loggingIn"))
        retainedLibrary?.refresh?.apply {
            isEnabled = anyAccount || wishlistKeys.isNotEmpty()
            if (!refreshing) dismissRefreshIndicator()
        }
    }

    private fun renderNavigation() {
        rail.visibility = View.GONE
        val pagination = storeSearchView?.pagination?.takeIf { tab == "store" }
        bottom.visibility = if (pagination != null) View.VISIBLE else View.GONE
        if (pagination != null && pagination.parent !== bottom) {
            (pagination.parent as? ViewGroup)?.removeView(pagination)
            bottom.removeAllViews()
            bottom.addView(pagination, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun selectTab(name: String) {
        if (selectedLibraryKeys.isNotEmpty()) clearLibrarySelection()
        tab = if (name == "downloads") "library" else name
        if (name == "downloads") collection.list = "Downloads"
        if (tab != "game") { gameHistory.clear(); gameSequence = emptyList(); selectedKey = "" }
        steamSignIn = false; hideKeyboard(); render(state)
    }
    private fun showNotices() {
        notice.removeAllViews()
        val message = error.ifEmpty { state.optString("error") }
        if (message.isNotEmpty() && downloadDialog?.isShowing != true) {
            val banner = ui.row().apply { background = ui.shape(if (ui.dark) Color.rgb(71,36,38) else Color.rgb(252,232,230), 12); setPadding(ui.dp(14), ui.dp(10), ui.dp(8), ui.dp(10)) }
            banner.addView(ui.text(message, 12, ui.error), LinearLayout.LayoutParams(0, -2, 1f))
            if (error.isNotEmpty()) banner.addView(ui.iconButton(R.drawable.ic_x, "Dismiss notice") { error = ""; showNotices() })
            notice.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(20), 0, ui.dp(20), ui.dp(12)) })
        }
    }

    private fun scrollColumn(padding: Int = 20, swipeGame: ((Int) -> Unit)? = null, target: FrameLayout = body, closeOnPullDown: (() -> Unit)? = null): LinearLayout {
        val scroll = (if (closeOnPullDown != null) GamePageScrollView(this, closeOnPullDown, swipeGame) else ViewportScrollView(this))
            .apply { isFillViewport = true; clipToPadding = false }
        val content = ui.column(padding)
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2)); target.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        return content
    }

    private fun renderAccounts() {
        val content = scrollColumn(if (landscape) 12 else 20)
        if (!landscape) {
            ui.add(content, ui.text("Connect your stores to bring their games into one library.", 14, ui.secondary)); ui.gap(content, 20)
        }
        val cards = if (landscape) ui.column().also { ui.add(content, it) } else content
        var accountRow: LinearLayout? = null
        LibraryStore.entries.forEachIndexed { index, source ->
            val remote = source.account?.let { accounts.state.value.getValue(it) }
            val saved = remote?.saved ?: state.optBoolean("sessionAvailable")
            val active = remote?.connected ?: connected
            val working = remote?.loading ?: (state.optString("status") !in listOf("signedOut", "signedIn"))
            val count = remote?.games?.count { it.xboxAccess != XboxAccess.GAME_PASS } ?: allGames.size
            val cached = remote?.libraryLoaded ?: state.optBoolean("libraryCached")
            val accountError = if (source == LibraryStore.STEAM) state.optString("connectionError") else remote?.error.orEmpty()
            val card = ui.column(if (landscape) 12 else 20).apply { background = ui.shape(ui.surface, 20) }
            val heading = ui.row()
            heading.addView(ui.icon(R.drawable.ic_gamepad_2, color = ui.blue), LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)).apply { marginEnd = ui.dp(12) })
            heading.addView(ui.text(source.label, 21, medium = true)); ui.add(card, heading); ui.gap(card, 8)
            val statusText = when {
                working -> "Connecting and refreshing…"
                !active && (saved || cached) -> "Reconnect needed · $count ${if (count == 1) "game" else "games"}"
                accountError.isNotBlank() -> if (active) "Refresh unavailable · $count ${if (count == 1) "game" else "games"}" else "Could not connect · Try again"
                active -> "Connected · $count ${if (count == 1) "game" else "games"}"
                else -> "Not connected"
            }
            ui.add(card, if (!active && !working && (saved || cached)) ui.link(statusText, 13) {
                reconnectAccount(source) {
                    if (source == LibraryStore.STEAM) { steamSignIn = true; render(engine.snapshot(), true) }
                    else showStoreLogin(source.account!!)
                }
            } else ui.text(statusText, 13, ui.secondary))
            if (source == LibraryStore.XBOX) {
                ui.gap(card, 8)
                ui.add(card, ui.text("Owned games and Game Pass appear in separate lists.", 13, ui.secondary))
            }
            ui.gap(card, 12)
            if (saved || active || cached) {
                val actions = ui.row()
                actions.addView(ui.button(if (landscape) "Games" else "View games", true) { collection.list = if (source == LibraryStore.EPIC) "Epic" else source.label; query = ""; selectTab("library") }, LinearLayout.LayoutParams(0, -2, 1f))
                actions.addView(ui.iconButton(R.drawable.ic_refresh_cw, if (active) "Refresh ${source.label} library" else if (saved) "Reconnect ${source.label}" else "Connect ${source.label}") {
                    if (!saved && !active) {
                        if (source == LibraryStore.STEAM) { steamSignIn = true; render(state, true) } else showStoreLogin(source.account!!)
                    } else act { source.account?.let { accounts.refresh(it) } ?: if (connected) engine.refreshLibrary() else engine.signIn(forceLibraryRefresh = true) }
                }.apply { enable(this, !working) })
                actions.addView(ui.iconButton(R.drawable.ic_log_out, "Sign out of ${source.label}") {
                    if (source == LibraryStore.STEAM) confirmSignOut() else confirmStoreSignOut(source.account!!)
                }.apply { if (source == LibraryStore.STEAM) enable(this, !busy) })
                ui.add(card, actions)
            } else if (working) {
                ui.add(card, ui.button("Continue sign-in", true) {
                    if (source == LibraryStore.STEAM) { steamSignIn = true; render(state, true) }
                }.apply { visibility = if (source == LibraryStore.STEAM) View.VISIBLE else View.GONE })
                ui.add(card, ui.button("Cancel sign-in") { act { source.account?.let(accounts::signOut) ?: engine.signOut() } })
            } else ui.add(card, ui.button("Connect ${source.label}", true) {
                if (source == LibraryStore.STEAM) { steamSignIn = true; render(state, true) }
                else showStoreLogin(source.account!!)
            })
            if (landscape) {
                if (index % 2 == 0) {
                    accountRow = ui.row().apply { gravity = Gravity.TOP }
                    ui.add(cards, accountRow!!); ui.gap(cards, 12)
                }
                accountRow!!.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index % 2 == 0) marginEnd = ui.dp(12)
                })
            } else { ui.add(content, card); ui.gap(content, 16) }
        }
    }

    private fun confirmStoreSignOut(store: GameStore) {
        val (dialog, content) = modal(); downloadDialog = dialog
        ui.add(content, ui.text("Sign out of ${store.label}?", 23, medium = true)); ui.gap(content)
        ui.add(content, ui.text("This removes this store’s saved session, library, local lists, download history, and saved game files. Files already exported to Android Downloads are kept. Your browser stays signed in separately.", 13, ui.secondary)); ui.gap(content, 20)
        dialogActions(dialog, ui.button("Sign out", true) { act { accounts.signOut(store); dialog.dismiss() } },
            ui.button("Cancel") { dialog.dismiss() }); showModal(dialog)
    }

    private fun showStoreLogin(store: GameStore) {
        downloadDialog?.dismiss()
        if (store == GameStore.XBOX) startActivity(Intent(this, XboxLoginActivity::class.java))
        else startActivity(Intent(this, StoreLoginActivity::class.java).putExtra("store", store.name))
    }

    private fun renderAuth() {
        val content = scrollColumn()
        val card = ui.column(28).apply { background = ui.shape(ui.surface, 24) }
        content.addView(card, LinearLayout.LayoutParams(ui.dp(440).coerceAtMost(resources.displayMetrics.widthPixels - ui.dp(40)), -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val status = state.optString("status", "signedOut")
        if (status == "signedOut" && !state.optBoolean("sessionAvailable")) {
            val choices = ui.row().apply { background = ui.shape(ui.surface, 24) }
            listOf("password" to "Account & password", "qr" to "QR code").forEach { (value, label) ->
                val choice = ui.button(label) { method = value; render(state) }
                (choice.getChildAt(0) as TextView).apply { textSize = 11f; gravity = Gravity.CENTER }
                choice.background = ui.shape(if (method == value) ui.paleBlue else Color.TRANSPARENT, 24)
                choice.isSelected = method == value
                choices.addView(choice, LinearLayout.LayoutParams(0, -2, 1f))
            }
            ui.add(card, choices); ui.gap(card, 28)
        } else if (status != "restoring" && !state.optBoolean("sessionAvailable")) {
            ui.add(card, ui.button("Cancel sign-in", icon = R.drawable.ic_x) { act { engine.signOut() } }); ui.gap(card, 24)
        }
        fun heading(text: String) { ui.add(card, ui.text(text, 20, medium = true).apply { gravity = Gravity.CENTER }); ui.gap(card, 14) }
        fun paragraph(text: String) { ui.add(card, ui.text(text, 13, ui.secondary).apply { gravity = Gravity.CENTER }); ui.gap(card, 20) }
        when {
            status == "restoring" || (status == "signedOut" && state.optBoolean("sessionAvailable")) -> {
                heading("Opening your library…")
                paragraph(if (status == "restoring") "Reconnecting to Steam with your saved session…" else "Your session is saved. Reconnect to open your library.")
                if (status == "restoring") addSpinner(card) else ui.add(card, ui.button("Reconnect", true) { act { engine.signIn() } })
                ui.add(card, ui.button("Sign out", action = ::confirmSignOut))
            }
            status in listOf("awaitingApproval", "awaitingCode", "verifyingGuard") -> {
                heading(when (status) { "awaitingApproval" -> "Approve in Steam"; "awaitingCode" -> "Enter your Steam Guard code"; else -> "Checking with Steam…" })
                val email = state.optString("guardKind") == "email"
                paragraph(when (status) {
                    "awaitingApproval" -> "Open the Steam app on this phone, approve the sign-in for GameFibre · Android, then return here."
                    "awaitingCode" -> if (email) "Enter the code Steam sent to ${state.optString("guardEmail").ifEmpty { "your email address" }}." else "In the Steam app, open Steam Guard and tap Show Steam Guard code. Return here to enter it."
                    else -> "Keep this screen open while Steam completes your sign-in."
                })
                if (state.optString("guardError").isNotEmpty()) { ui.add(card, ui.text(state.optString("guardError"), 13, ui.error)); ui.gap(card) }
                when (status) {
                    "awaitingApproval" -> {
                        ui.add(card, ui.button("Open Steam app", true) { act(::openSteam) })
                        if (state.optBoolean("canUseGuardCode")) ui.add(card, ui.button("Use a Steam Guard code instead") { act { engine.useGuardCode() } })
                    }
                    "awaitingCode" -> {
                        val code = ui.input("Steam Guard code").apply {
                            gravity = Gravity.CENTER; textSize = 23f; letterSpacing = .25f
                            filters = arrayOf(InputFilter { source, start, end, _, _, _ ->
                                source.subSequence(start, end).filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }
                            }, InputFilter.AllCaps(), InputFilter.LengthFilter(5))
                        }
                        ui.add(card, code); ui.gap(card)
                        val confirm = ui.button("Confirm code", true) {
                            val value = code.text.toString(); code.text.clear(); hideKeyboard(); act { engine.submitGuardCode(value) }
                        }
                        enable(confirm, false); code.addTextChangedListener(watcher { enable(confirm, code.length() == 5) })
                        ui.add(card, confirm)
                        if (!email) ui.add(card, ui.button("Open Steam app") { act(::openSteam) })
                    }
                    else -> addSpinner(card)
                }
            }
            status == "qr" && state.optString("challengeUrl").isNotEmpty() -> {
                val qr = ImageView(this).apply { contentDescription = "Scan this sign-in QR code with Steam Guard"; setBackgroundColor(Color.WHITE) }
                card.addView(qr, LinearLayout.LayoutParams(ui.dp(210), ui.dp(210)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                val challenge = state.getString("challengeUrl")
                scope.launch {
                    try {
                        val bitmap = withContext(Dispatchers.Default) {
                            val matrix = QRCodeWriter().encode(challenge, BarcodeFormat.QR_CODE, 512, 512, mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
                            val pixels = IntArray(512 * 512) { i -> if (matrix[i % 512, i / 512]) Color.BLACK else Color.WHITE }
                            Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888)
                        }
                        qr.setImageBitmap(bitmap)
                    } catch (e: Exception) { if (e is CancellationException) throw e; showError("Could not display the QR code. Cancel sign-in and try again.") }
                }
                ui.gap(card, 24); heading("Scan to connect")
                paragraph("Open Steam Guard on another device, scan this code, and approve the sign-in.")
                ui.add(card, ui.text("GameFibre · Android", 11, ui.secondary).apply { gravity = Gravity.CENTER })
            }
            status != "signedOut" -> { heading(if (status == "loggingIn") "Finishing sign-in…" else "Connecting to Steam…"); addSpinner(card) }
            method == "qr" -> ui.add(card, ui.button("Connect with Steam", true, R.drawable.ic_qr_code) { act { engine.signIn() } })
            else -> {
                heading("Sign in to Steam")
                val account = ui.input("Steam account name").apply { hint = "" }
                val password = ui.input("Password", true).apply { hint = "" }
                ui.add(card, ui.text("Steam account name", 12, ui.secondary)); ui.gap(card, 8); ui.add(card, account)
                ui.gap(card, 18); ui.add(card, ui.text("Password", 12, ui.secondary)); ui.gap(card, 8); ui.add(card, password); ui.gap(card, 22)
                val submit = ui.button("Sign in", true) {
                    val name = account.text.toString().trim(); val secret = password.text.toString()
                    account.text.clear(); password.text.clear(); hideKeyboard()
                    act { engine.signInWithCredentials(name, secret) }
                }
                enable(submit, false)
                val validate = watcher { enable(submit, account.text.isNotBlank() && password.text.isNotEmpty()) }
                account.addTextChangedListener(validate); password.addTextChangedListener(validate)
                password.setOnEditorActionListener { _, _, _ -> if (submit.isEnabled) { submit.performClick(); true } else false }
                ui.add(card, submit)
            }
        }
    }

    private fun compactLibraryButton(label: String, icon: Int? = null, action: () -> Unit) = ui.button(label, icon = icon, action = action).apply {
        minimumHeight = ui.dp(48)
        setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
        for (index in 0 until childCount) when (val child = getChildAt(index)) {
            is TextView -> { child.textSize = 14f; child.isSingleLine = true }
            is ImageView -> child.layoutParams = LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { marginEnd = ui.dp(5) }
        }
    }

    private fun renderLibraryListControls() {
        libraryControls.removeAllViews()
        val previousListScroll = listScrollX
        val lists = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setOnScrollChangeListener { _, x, _, _, _ -> listScrollX = x }
        }
        listsRow = LibraryListBar(this, ui.paleBlue, ui.dp(20).toFloat()).also { lists.addView(it) }
        lists.post { lists.scrollTo(previousListScroll, 0) }
        ui.add(libraryControls, lists)
        renderListBar()
    }

    private fun renderLibrary() {
        val content = ui.column().apply { setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(8)) }
        body.addView(content, FrameLayout.LayoutParams(-1, -1))
        renderLibraryListControls()
        val screen = retainedLibrary ?: LibraryScreen(content).also { retainedLibrary = it }
        if (screen.view !== content) {
            body.removeView(content)
            body.addView(screen.view, FrameLayout.LayoutParams(-1, -1))
        }
        updateLibraryRefresh()
        updateLibrary(true)
        activateLibraryPage()
        screen.updateReadiness()
        revealSelectedList()
    }

    private inner class LibraryScreen(val view: LinearLayout) {
        val pages = mutableMapOf<String, LibraryPage>()
        var populatedLists = populatedLibraryLists()
        // Keep an unnamed browsing page when every disposable list has been removed.
        val pageNames get() = populatedLists.ifEmpty { listOf("") }
        var syncing = false
        private var verticalGesture=false
        fun lockVerticalGesture(locked:Boolean) {
            if(verticalGesture==locked)return
            verticalGesture=locked
            updateReadiness()
        }
        val pager = ViewPager2(this@MainActivity).apply {
            // The pill order is physical left-to-right, including on RTL devices.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            offscreenPageLimit = 1
            visibility = View.INVISIBLE
            isUserInputEnabled = false
        }
        val refresh = LibraryRefreshLayout(this@MainActivity).apply {
            setColorSchemeColors(ui.blue)
            setProgressBackgroundColorSchemeColor(ui.surface)
            setOnChildScrollUpCallback { _, _ ->
                pager.scrollState != ViewPager2.SCROLL_STATE_IDLE ||
                    pages[collection.list]?.recycler?.canScrollVertically(-1) == true || appBar.top < 0
            }
            setOnRefreshListener { refreshLibraries() }
            addView(pager, ViewGroup.LayoutParams(-1, -1))
        }
        private var requestedPage: Int? = null
        private fun ensureAround(currentPosition: Int) {
            val position=requestedPage ?: currentPosition
            val keep = ((position-1).coerceAtLeast(0)..(position+1).coerceAtMost(pageNames.lastIndex)).map { pageNames[it] }.toSet()
            pages.values.filter { it.name !in keep && it.holder==null }.toList().forEach { it.close();pages.remove(it.name,it) }
            for (index in (position-1).coerceAtLeast(0)..(position+1).coerceAtMost(pageNames.lastIndex)) {
                val name = pageNames[index]
                val page = pages.getOrPut(name) { LibraryPage(name) }
                updateLibraryPage(page)
            }
        }
        fun navigate(position: Int) {
            if(collection.list != pageNames[position]) {
                collection.list = pageNames[position]; query = ""
                renderHeader(); renderListBar()
            }
            requestedPage = position
            assetRepository.promote(libraryListEntries(pageNames[position]).take(32).map { it.key }.toSet())
            ensureAround(position)
            updateReadiness()
        }
        private fun snapshotReady(position: Int): Boolean {
            val current = pages[pageNames[position]] ?: return false
            return current.adapter.itemCount > 0 || !current.adapter.hasCandidates
        }
        fun updateReadiness() {
            val position = requestedPage ?: pager.currentItem
            val required = ((position-1).coerceAtLeast(0)..(position+1).coerceAtMost(pageNames.lastIndex)).map { pageNames[it] }
            val ready = required.all { pages[it]?.adapter?.ready == true } && (snapshotReady(position))
            if (ready) {
                pager.visibility = View.VISIBLE
                pager.isUserInputEnabled = !verticalGesture
                requestedPage?.let { target ->
                    syncing=true
                    pager.setCurrentItem(target,false)
                    syncing=false
                    requestedPage=null
                    activateLibraryPage()
                }
            } else {
                pager.isUserInputEnabled = false
            }
        }
        init {
            ensureAround(pageNames.indexOf(collection.list).coerceAtLeast(0))
            view.addView(refresh, LinearLayout.LayoutParams(-1, 0, 1f))
            pager.adapter = object : RecyclerView.Adapter<LibraryPageHolder>() {
                override fun getItemCount() = pageNames.size
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = LibraryPageHolder(
                    FrameLayout(parent.context).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) })
                override fun onBindViewHolder(holder: LibraryPageHolder, position: Int) {
                    holder.page?.takeIf { it.holder===holder }?.let { it.holder=null }
                    holder.page=null
                    holder.frame.removeAllViews()
                    val page = pages.getOrPut(pageNames[position]) { LibraryPage(pageNames[position]) }
                    page.holder?.page=null
                    (page.frame.parent as? ViewGroup)?.removeView(page.frame)
                    page.holder=holder
                    holder.page = page
                    holder.frame.addView(page.frame, FrameLayout.LayoutParams(-1, -1))
                    updateLibraryPage(page)
                    if (page.name == collection.list) activateLibraryPage()
                }
                override fun onViewRecycled(holder: LibraryPageHolder) {
                    val previous = holder.page
                    if(previous?.holder===holder)previous.holder=null
                    holder.page = null; holder.frame.removeAllViews()
                    val anchor=requestedPage ?: pager.currentItem
                    if (previous != null && previous.holder==null && kotlin.math.abs(pageNames.indexOf(previous.name)-anchor)>1) {previous.close();pages.remove(previous.name,previous)}
                }
            }
            pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                    if (tab == "library") listsRow?.pagePosition = position + positionOffset
                }
                override fun onPageSelected(position: Int) {
                    if (!syncing && pager.scrollState == ViewPager2.SCROLL_STATE_IDLE) selectPage(position)
                }
                override fun onPageScrollStateChanged(scrollState: Int) {
                    if (!syncing && scrollState == ViewPager2.SCROLL_STATE_IDLE) selectPage(pager.currentItem)
                }
                private fun selectPage(position: Int) {
                    if (tab != "library" || (requestedPage!=null && requestedPage!=position)) return
                    val name = pageNames.getOrNull(position) ?: return
                    if (collection.list != name) {
                        val showHint = !libraryListBarVisible()
                        collection.list = name; query = ""
                        renderHeader(); renderListBar()
                        updateLibrary(true)
                        if (showHint) showLibraryPageHint(name)
                    }
                    ensureAround(position)
                    updateReadiness()
                    activateLibraryPage()
                    revealSelectedList()
                }
            })
            syncing = true
            pager.setCurrentItem(pageNames.indexOf(collection.list).coerceAtLeast(0), false)
            syncing = false
        }
    }

    private inner class LibraryPageHolder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame) {
        var page: LibraryPage? = null
    }

    private inner class LibraryPage(val name: String) {
        var holder:LibraryPageHolder?=null
        fun close() { recycler.stopScroll();recycler.adapter=null;adapter.releaseImages() }
        var builtQuery = ""
        val adapter = GameAdapter(name)
        val recycler = RecyclerView(this@MainActivity).apply {
            layoutManager = this@LibraryPage.adapter.layoutManager()
            adapter = this@LibraryPage.adapter
            clipToPadding = false; itemAnimator = null
            if (name == WishlistItems.LIST) setPadding(0, 0, 0, ui.dp(88))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    this@LibraryPage.adapter.promoteViewport()
                }
                override fun onScrollStateChanged(recyclerView:RecyclerView,newState:Int) {
                    if(newState==RecyclerView.SCROLL_STATE_IDLE)this@LibraryPage.adapter.commitLatest()
                }
            })
        }
        val empty = ui.column(24).apply { gravity = Gravity.CENTER }
        val frame = LibraryGestureHost(this@MainActivity) {retainedLibrary?.lockVerticalGesture(it)}.apply {
            addView(recycler, FrameLayout.LayoutParams(-1, -1))
            addView(empty, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun activateLibraryPage() {
        val page = retainedLibrary?.pages?.get(collection.list) ?: return
        libraryRecycler = page.recycler
        updateSelectAllButton()
        searchGameCount?.apply {
            val count = page.adapter.candidateCount
            text = count.toString()
            contentDescription = "$count matching games"
        }
        libraryScroll?.let {
            page.recycler.layoutManager?.onRestoreInstanceState(it)
            libraryScroll = null
        }
        page.adapter.promoteViewport()
    }

    private fun updateLibrary(updateItems: Boolean) {
        val screen = retainedLibrary ?: return
        if (tab != "library") return
        val populated = populatedLibraryLists()
        val listsChanged = screen.populatedLists != populated
        val selectionChanged = collection.list !in populated.ifEmpty { listOf("") }
        if (selectionChanged) {
            collection.list = populated.firstOrNull().orEmpty()
            query = ""
            libraryScroll = null
            renderHeader()
        }
        if (listsChanged) {
            screen.syncing = true
            screen.populatedLists = populated
            screen.pager.adapter?.notifyDataSetChanged()
            screen.navigate(screen.pageNames.indexOf(collection.list).coerceAtLeast(0))
            screen.syncing = false
        }
        if (listsChanged || selectionChanged) {
            renderListBar()
            revealSelectedList()
        }
        val position = screen.pageNames.indexOf(collection.list).coerceAtLeast(0)
        if (screen.pager.scrollState == ViewPager2.SCROLL_STATE_IDLE && screen.pager.currentItem != position) {
            screen.syncing = true
            screen.navigate(position)
            screen.syncing = false
            renderListBar()
        }
        activateLibraryPage()
        bySize = collection.sort == "Size"
        val sizeAvailable = storeFilter == null || storeFilter == LibraryStore.STEAM
        val resetSort = !sizeAvailable && bySize
        if (resetSort) { bySize = false; descending = false; collection.sort = "Name" }
        if (!updateItems && !resetSort && !listsChanged && !selectionChanged) return
        val context = "${collection.list}:$storeFilter:$query:$tagFilters:${collection.sort}:${collection.randomSeed}:$descending:${collection.tileSize}"
        if (scrollContext != context) { libraryScroll = null; libraryRecycler?.scrollToPosition(0); scrollContext = context }
        screen.pages.values.toList().forEach(::updateLibraryPage)
        activateLibraryPage()
        updateTagFilterBar()
    }

    private fun updateLibraryPage(page: LibraryPage) {
        val libraryStarted = android.os.SystemClock.elapsedRealtime()
        val filter = listStore(page.name)
        val pageQuery = if (page.name == collection.list) query else ""
        val pageTags = if (page.name == collection.list) tagFilters else TagFilters()
        val resetSizeSort = collection.sort == "Size" && filter != null && filter != LibraryStore.STEAM
        val sort = if (resetSizeSort) "Name" else collection.sort
        val reverse = !resetSizeSort && descending
        (page.recycler.layoutManager as GridLayoutManager).spanCount = libraryColumns(page.name)
        val downloadState = if (page.name == "Downloads") "${collection.downloadRevision}:${transferCards().keys}" else ""
        val queryKey = "$downloadState:$libraryRevision:${collection.membershipRevision}:${page.name}:$pageQuery:$pageTags:${liveSteamTags.revision.value}:${collection.sort}:${collection.randomSeed}:$descending:${beatIndex != null}:${steamTagIndex != null}:${assetRepository.library.value.revision}:$libraryLoading:$anyAccount:${entries.isEmpty()}:${collection.tileSize}:${collection.showTitles}:${collection.fitCoverArt}:${collection.showHiddenGames}"
        if (queryKey == page.builtQuery) return
        val grouped = tagSearchCandidates(page.name)
        val filtered = if (pageTags.active) {
            val matching = tagSearch(page.name, grouped).keys(pageQuery, pageTags)
            grouped.filter { it.key in matching }
        } else grouped.filter { game -> game.name.contains(pageQuery.trim(), true) ||
            game.copies.any { it.name.contains(pageQuery.trim(), true) } }
        val games = orderLibrary(filtered, sort, reverse, beatIndex, collection.randomSeed, ::mergedDetails)
        page.builtQuery = queryKey
        logPerformance("build-list", libraryStarted, "games=${games.size} sort=${collection.sort}")
        page.adapter.submit(games)
        page.adapter.promoteViewport()
        retainedLibrary?.updateReadiness()
        val noSearch = pageQuery.isBlank() && !pageTags.active
        page.empty.apply {
            removeAllViews(); visibility = if (games.isEmpty() && !libraryLoading) View.VISIBLE else View.GONE
            addView(ui.icon(R.drawable.ic_search), LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)))
            ui.gap(this)
            ui.add(this, ui.text(if (!anyAccount) "All your games, together" else if (entries.isEmpty()) "Your library is empty" else if (noSearch && page.name.isNotEmpty() && page.name != "All games") "No games in ${page.name}" else "No games found", 20).apply { gravity = Gravity.CENTER })
            ui.add(this, ui.text(if (page.name == WishlistItems.LIST) "Search the stores to add games to your wishlist." else if (page.name == "Xbox" && collection.isAutomaticList("Xbox")) "Use + to add Xbox disc games, or connect Xbox in Accounts to see your digital games." else if (!anyAccount) "Connect Steam, GOG, Epic Games or Xbox to explore your library." else if (entries.isEmpty()) "Refresh your libraries or connect another store." else if (noSearch && page.name == "Downloads") "Start a download from a game’s page." else if (noSearch && filter != null) "Connect ${page.name} in Accounts to see its games." else if (noSearch && page.name.isNotEmpty() && page.name != "All games") "Open a game to add it to this list." else "Try different search text or clear the tag filters.", 13, ui.secondary).apply { gravity = Gravity.CENTER })
            if (page.name != WishlistItems.LIST && (!anyAccount || entries.isEmpty())) {
                ui.gap(this); ui.add(this, ui.button("Manage accounts", true) { selectTab("accounts") })
            }
            if (noSearch && collection.canRemoveList(page.name) && games.isEmpty() && !libraryLoading && libraryListEntries(page.name, includeHidden = true).isEmpty()) {
                ui.gap(this)
                ui.add(this, ui.button("Remove list") { showRemoveEmptyList(page.name) })
            }
        }
    }

    private fun tagSearchCandidates(name: String): List<LibraryEntry> {
        val games = libraryListEntries(name)
        return if (name == "Downloads") games else mergeLibraryCopies(games)
    }

    private fun tagSearch(name: String, games: List<LibraryEntry>): LibraryTagSearch {
        val key = "$name:$libraryRevision:${collection.membershipRevision}:${collection.downloadRevision}:${collection.showHiddenGames}:${beatReady.isCompleted}:${liveSteamTags.revision.value}"
        if (tagSearchCacheKey == key) tagSearchCache?.let { return it }
        return LibraryTagSearch(games.map { TagSearchGame(it.key, it.name, communityTags(it), it.copies.map { copy -> copy.name }) }).also {
            tagSearchCacheKey = key; tagSearchCache = it
        }
    }

    private fun updateTagFilterBar() {
        if (tab != "library") return
        tagFilterBar?.update(tagSearch(collection.list, tagSearchCandidates(collection.list)).facets(query, tagFilters), tagFilters)
    }

    private fun showRemoveEmptyList(name: String) {
        if (!collection.canRemoveList(name) || libraryLoading || libraryListEntries(name, includeHidden = true).isNotEmpty()) return
        val (dialog, content) = modal()
        downloadDialog = dialog
        ui.add(content, ui.text("Remove $name?", 24, medium = true)); ui.gap(content, 12)
        val message = "Delete this empty list everywhere? You can create a new list with the same name later."
        ui.add(content, ui.text(message, 14, ui.secondary))
        dialogActions(dialog, ui.button("Remove", true) {
            dialog.dismiss()
            // A refresh or download may have populated the list while confirmation was open.
            if (libraryLoading || !collection.canRemoveList(name) || libraryListEntries(name, includeHidden = true).isNotEmpty()) return@button
            collection.removeList(name)
            query = ""; libraryScroll = null; scrollContext = ""
            hideLibraryPageHint()
            renderHeader()
            updateLibrary(true)
        }, ui.button("Cancel") { dialog.dismiss() })
        showModal(dialog)
    }

    private fun librarySelectionKey(game: LibraryEntry) = (if (collection.list == WishlistItems.LIST)
        game.copies.ifEmpty { listOf(game) } else listCopies(game)).minBy { it.store.ordinal }.key

    private fun isLibrarySelected(game: LibraryEntry) = librarySelectionKey(game) in selectedLibraryKeys

    private fun toggleLibrarySelection(game: LibraryEntry) {
        val key = librarySelectionKey(game)
        if (!selectedLibraryKeys.add(key)) selectedLibraryKeys.remove(key)
        hideKeyboard()
        updateLibrarySelection()
    }

    private fun clearLibrarySelection() {
        selectedLibraryKeys.clear()
        updateLibrarySelection()
    }

    private fun currentLibrarySelectionKeys() = retainedLibrary?.pages?.get(collection.list)?.adapter?.viewportKeys().orEmpty()

    private fun selectAllLibraryGames() {
        selectedLibraryKeys.addAll(currentLibrarySelectionKeys())
        updateLibrarySelection()
    }

    private fun updateSelectAllButton() {
        selectAllButton?.apply {
            val keys = currentLibrarySelectionKeys()
            isEnabled = keys.any { it !in selectedLibraryKeys }
            alpha = if (isEnabled) 1f else .38f
        }
    }

    private fun updateLibrarySelection() {
        val selecting = selectedLibraryKeys.isNotEmpty()
        if (tab == "library") {
            if (selecting && selectionCount != null) selectionCount?.apply {
                text = selectedLibraryKeys.size.toString()
                contentDescription = "${selectedLibraryKeys.size} selected"
            }
            else renderHeader()
            updateSelectAllButton()
            toolbarContent.layoutParams = (toolbarContent.layoutParams as AppBarLayout.LayoutParams).apply {
                scrollFlags = if (selecting) 0 else AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                    AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS or AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
            }
            if (selecting) appBar.setExpanded(true, true)
        }
        retainedLibrary?.pages?.values?.forEach { page ->
            for (index in 0 until page.recycler.childCount) {
                (page.recycler.getChildViewHolder(page.recycler.getChildAt(index)) as? GameHolder)?.let {
                    bindLibrarySelection(it, animate = true)
                }
            }
        }
    }

    private fun bindLibrarySelection(holder: GameHolder, animate: Boolean = false) {
        val selected = holder.boundGame?.let(::isLibrarySelected) == true
        holder.card.isSelected = selected
        ViewCompat.setStateDescription(holder.card, if (selected) "Selected" else null)
        holder.selectionMark.visibility = if (selectedLibraryKeys.isNotEmpty()) View.VISIBLE else View.GONE
        holder.selectionMark.setImageResource(if (selected) R.drawable.ic_check else 0)
        holder.selectionMark.background = ui.shape(if (selected) ui.blue else ui.surface, 14,
            if (selected) ui.blue else ui.secondary)
        holder.card.animate().cancel()
        val scale = if (selected && !holder.landscapeArt) .94f else 1f
        if (animate) holder.card.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
        else { holder.card.scaleX = scale; holder.card.scaleY = scale }
        // Compact rows use a tinted background instead of shrinking their text.
        holder.card.background = if (selected && holder.landscapeArt) ui.shape(ui.paleBlue, 12) else null
    }

    private fun showManageSelection() {
        val games = if (collection.list == WishlistItems.LIST)
            mergeLibraryCopies(libraryListEntries(WishlistItems.LIST)).filter { librarySelectionKey(it) in selectedLibraryKeys }
        else selectedLibraryKeys.mapNotNull { key -> entries.find { it.key == key } }
        if (games.isEmpty()) return
        if (collection.list == WishlistItems.LIST) {
            val keys = games.flatMap { it.copies.ifEmpty { listOf(it) } }.map { it.key }.toSet()
            val removed = collection.wishlistGames().filter { it.key in keys }
            collection.removeWishlist(keys)
            clearLibrarySelection(); render(state, true)
            appSnackbar(root, "Removed ${removed.size} games from wishlist", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction("Undo") { collection.addWishlist(removed); render(state, true) }.show()
            return
        }
        val (dialog, content) = modal("Manage games")
        downloadDialog = dialog
        ui.add(content, ui.text("${games.size} ${if (games.size == 1) "game" else "games"} selected", 14, ui.secondary))
        ui.add(content, ui.text("Update progress, add to lists, change artwork, or hide games.", 14, ui.secondary))
        ui.gap(content, 16)
        var selectedProgress: GameProgress? = null
        val selectedLists = linkedSetOf<String>()
        val keys = games.flatMap(::listCopies).map { it.key }.toSet()
        fun added(lists: Set<String>) {
            lists.forEach { collection.setMembership(it, keys, true) }
            selectedProgress?.let { collection.setProgress(keys, it) }
            clearLibrarySelection()
            updateLibrary(true)
            appSnackbar(root,
                "Updated ${games.size} ${if (games.size == 1) "game" else "games"}", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
        }
        val add = ui.button("Apply", true) {
            added(selectedLists)
            dialog.dismiss()
        }
        enable(add, false)
        val progressButton = ui.button("Progress: Keep current") {}
        progressButton.setOnClickListener {
            val options = listOf("Keep current") + GameProgress.entries.map { it.label }
            showChoiceDialog(this, ui, "Progress", options, selectedProgress?.let { it.ordinal + 1 } ?: 0) { index ->
                selectedProgress = if (index == 0) null else GameProgress.entries[index - 1]
                (progressButton.getChildAt(0) as TextView).text = "Progress: ${selectedProgress?.label ?: "Keep current"}"
                enable(add, selectedLists.isNotEmpty() || selectedProgress != null)
            }
        }
        ui.add(content, ui.text("Progress", 18, medium = true))
        ui.add(content, progressButton)
        ui.gap(content, 12)
        ui.add(content, ui.text("Add to lists", 18, medium = true))
        val listChips = CenteredChipGroup(this)
        collection.editableLists.forEach { list ->
            listChips.addView(ui.listChip(list, false) { selected ->
                if (selected) selectedLists.add(list) else selectedLists.remove(list)
                enable(add, selectedLists.isNotEmpty() || selectedProgress != null)
            })
        }
        listChips.addView(ui.button("+ New list") {
            dialog.dismiss()
            showCreateList(games) { list -> added(selectedLists + list) }
        }.apply {
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x220b57d0),
                ui.shape(Color.TRANSPARENT, 24, ui.secondary), null)
        })
        ui.add(content, listChips)
        ui.gap(content, 12)
        ui.add(content, ui.text("Artwork", 18, medium = true))
        ui.add(content, ui.button(if (batchArtwork?.status?.value?.running == true) "View artwork progress" else "Auto-apply SGDB artwork") {
            dialog.dismiss()
            if (batchArtwork?.status?.value?.running == true) showArtworkProgress()
            else startBatchArtwork(games)
        })
        ui.gap(content, 12)
        ui.add(content, ui.text("Visibility", 18, medium = true))
        val allHidden = games.all(::gameIsHidden)
        ui.add(content, ui.button(if (allHidden) "Unhide selected games" else "Hide selected games") {
            dialog.dismiss()
            changeGameVisibility(games, hidden = !allHidden)
        })
        dialogActions(dialog, add, ui.button("Close") { dialog.dismiss() })
        showModal(dialog)
    }

    private inner class GameHolder(val card: LinearLayout, val art: ImageView, val title: TextView,
        val subtitle: TextView, val coverTitle: TextView, val progress: GameProgressView,
        val backdrop: ImageView, val backdropShade: View,
        val selectionMark: ImageView, val metascoreBadge: TextView) : RecyclerView.ViewHolder(card) {
        var boundGame: LibraryEntry? = null
        var landscapeArt = false
    }
    private inner class GameAdapter(private val list: String) : RecyclerView.Adapter<GameHolder>() {
        private val window = ReadyArtworkWindow(assetRepository, scope)
        private var submitted: List<PublishedCard> = emptyList()
        private var latest=emptyList<LibraryEntry>()
        val hasCandidates get() = latest.isNotEmpty()
        val candidateCount get() = latest.size
        private var latestIndex=emptyMap<String,Int>()
        var ready = false; private set
        fun releaseImages() { window.close();games=emptyList();latest=emptyList();latestIndex=emptyMap();ready = false;submitted = emptyList() }
        fun layoutManager() = ArtworkGridLayoutManager(this@MainActivity, libraryColumns(list), window, ::promoteViewport)
        fun viewportKeys():Set<String> {
            val recycler=retainedLibrary?.pages?.get(list)?.recycler
            val layout=recycler?.layoutManager as? GridLayoutManager
            val first=layout?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
            val last=layout?.findLastVisibleItemPosition()?.coerceAtLeast(first) ?: first
            val start=games.getOrNull(first)?.let { latestIndex[it.key] } ?: 0
            val end=games.getOrNull(last)?.let { latestIndex[it.key] } ?: start
            return libraryViewportRange(start, end, latest.size, libraryColumns(list))
                .flatMap { latest[it].copies.ifEmpty { listOf(latest[it]) } }.map { it.key }.toSet()
        }
        fun promoteViewport() { if(collection.list==list)assetRepository.promote(viewportKeys()) }
        fun commitLatest() { submit(latest) }
        private var games = emptyList<LibraryEntry>()
        fun selectionKeys() = games.map(::librarySelectionKey).toSet()
        private var presentation = ""
        private var metascores = emptyMap<String, Int?>()
        fun submit(value: List<LibraryEntry>) {
            latest=value
            latestIndex=buildMap { value.forEachIndexed { index,game -> game.copies.ifEmpty { listOf(game) }.forEach { put(it.key,index) } } }
            val published = value.mapNotNull(assetRepository::published)
            val next = "${collection.showTitles}:${collection.fitCoverArt}:${collection.tileSize}:${collection.sort}:${beatIndex != null}"
            if (submitted == published && presentation == next) return
            val recycler = retainedLibrary?.pages?.get(list)?.recycler
            if(collection.list!=list || recycler?.isAttachedToWindow!=true)recycler?.stopScroll()
            if (collection.list==list && recycler?.isAttachedToWindow==true && recycler.scrollState != RecyclerView.SCROLL_STATE_IDLE) return
            val layout = recycler?.layoutManager as? ArtworkGridLayoutManager
            val (position, offset) = layout?.publicationAnchor { first ->
                games.getOrNull(first)?.key?.let { shown -> published.indexOfFirst { it.game.key == shown }.takeIf { it >= 0 } } ?: first
            } ?: (0 to 0)
            val anchor = position.coerceIn(0, (published.size - 1).coerceAtLeast(0))
            val range = (anchor - libraryColumns(list)*12).coerceAtLeast(0) until (anchor+libraryColumns(list)*16).coerceAtMost(published.size)
            if (published.isEmpty() && value.isNotEmpty()) {
                ready = false
                retainedLibrary?.updateReadiness()
                return
            }
            window.prepare(published, range, canCommit={collection.list!=list || recycler?.isAttachedToWindow!=true || recycler.scrollState==RecyclerView.SCROLL_STATE_IDLE}) {
                val items = published.map { it.game }
                val scores = if (collection.sort == "Metascore") items.associate { it.key to mergedDetails(it).metascore } else emptyMap()
                val diff = DiffUtil.calculateDiff(LibraryDiff(games, items, presentation != next || submitted.map { it.asset } != published.map { it.asset }, metascores, scores))
                submitted = published; games = items; presentation = next; metascores = scores; ready = true
                // Land on the prepared viewport rather than letting DiffUtil moves carry an old child off it.
                layout?.scrollToPositionWithOffset(anchor, offset)
                diff.dispatchUpdatesTo(this)
                retainedLibrary?.updateReadiness()
            }
        }
        override fun getItemCount() = games.size
        override fun getItemViewType(position: Int) = if (list == "Downloads") 2 else if (collection.tileSize == "List") 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameHolder {
            val views = createGameCardViews(parent.context, ui, viewType, libraryColumns(list))
            return GameHolder(views.card, views.art, views.title, views.subtitle, views.coverTitle,
                views.progress, views.backdrop, views.backdropShade, views.selectionMark, views.metric)
        }
        override fun onBindViewHolder(holder: GameHolder, position: Int) {
            val game = games[position]
            val bitmap = window.bitmap(position)
            holder.boundGame = game
            holder.landscapeArt = getItemViewType(position) != 0
            holder.title.text = game.name; holder.coverTitle.text = game.name
            holder.subtitle.text = game.copies.ifEmpty { listOf(game) }.map { it.store.label }.distinct().joinToString(" · ")
            val metascore = metascores[game.key]
            bindCardMetric(holder.metascoreBadge, metascore?.toString(), metascore?.let { "Metascore: $it out of 100" })
            holder.card.contentDescription = "${game.name}, ${holder.subtitle.text}" +
                metascore?.let { ", Metascore: $it out of 100" }.orEmpty()
            holder.card.setOnClickListener {
                if (selectedLibraryKeys.isNotEmpty()) toggleLibrarySelection(game)
                else openGame(game, games.map { it.key })
            }
            holder.card.setOnLongClickListener {
                if (!isLibrarySelected(game)) toggleLibrarySelection(game)
                true
            }
            holder.card.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.isCheckable = true
                    info.isChecked = isLibrarySelected(game)
                    info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK, "Select game"))
                }
            }
            bindLibrarySelection(holder)
            holder.art.setImageBitmap(bitmap)
            holder.art.alpha = 1f
            holder.art.scaleType = if (holder.landscapeArt || collection.fitCoverArt || bitmap.height > bitmap.width) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            holder.backdrop.visibility = View.GONE; holder.backdropShade.visibility = View.GONE
            holder.coverTitle.visibility = if (!holder.landscapeArt && collection.showTitles) View.VISIBLE else View.GONE
            holder.title.visibility = if (holder.landscapeArt) View.VISIBLE else View.GONE
            holder.subtitle.visibility = if (holder.landscapeArt && listStore(list) == null) View.VISIBLE else View.GONE
            bindLibraryProgress(holder.progress, game, list)
        }
        override fun onViewRecycled(holder: GameHolder) { holder.boundGame = null; super.onViewRecycled(holder) }
    }

    private fun coverSteamId(game: LibraryEntry): Int? = assetRepository.steamId(game)

    private fun transferEntries(): List<LibraryEntry> = buildList {
        transfer?.let { t -> add(LibraryEntry(LibraryStore.STEAM, t.optInt("appId").toString(), t.optString("name"))) }
        storeDownloads.state.value?.request?.let { r -> add(LibraryEntry(LibraryStore.valueOf(r.store.name), r.game.id, r.game.name, remote = r.game)) }
    }
    private fun transferCards(): Map<String, TransferCard> = buildMap {
        transfer?.let { t ->
            val phase = t.optString("phase")
            val progress = when {
                phase == "complete" -> 1.0
                phase == "exporting" && t.optLong("exportTotalBytes") > 0 -> t.optLong("exportBytes").toDouble() / t.optLong("exportTotalBytes")
                t.has("depotPercent") -> t.optDouble("depotPercent").takeIf { it.isFinite() }
                else -> null
            }
            val key = "STEAM:${t.optInt("appId")}"
            put(key, TransferCard(key, "Steam", t.optString("name"), phase, t.optString("message"), progress,
                "", "", busy || t.optBoolean("resuming"), t.optBoolean("canPause"), t.optBoolean("canResume"), t.optString("output")))
        }
        storeDownloads.state.value?.let { t ->
            val key = "${t.request.store.name}:${t.request.game.id}"
            put(key, TransferCard(key, t.request.store.label, t.request.game.name, t.phase, t.message,
                if (t.phase == "complete") 1.0 else if (t.total > 0) t.done.toDouble() / t.total else null,
                "", "", t.running, t.running && t.phase in listOf("preparing", "downloading", "exporting"), t.canResume, t.output))
        }
    }
    private fun isOwned(game: LibraryEntry) = game.key in ownedKeys
    private fun ownedCopies(game: LibraryEntry) = when {
        gameReturnTab == "store" && !isOwned(game) -> listOf(game)
        !isOwned(game) && game.key in wishlistKeys -> game.copies.ifEmpty { listOf(game) }
        else -> matchingLibraryCopies(game, ownedByTitle[game.normalizedName].orEmpty())
    }
    private fun listCopies(game: LibraryEntry) = ownedCopies(game)
    private fun inGameList(list: String, game: LibraryEntry) = listCopies(game).any { collection.contains(list, it.key) }
    private fun setGameList(list: String, game: LibraryEntry, included: Boolean) {
        collection.setMembership(list, listCopies(game).map { it.key }.toSet(), included)
    }
    private fun progressSource(game: LibraryEntry, list: String): LibraryEntry {
        if (list == "Downloads") return game
        val copies = game.copies.ifEmpty { ownedCopies(game) }
        val cards = transferCards()
        return copies.firstOrNull { cards[it.key]?.running == true }
            ?: copies.firstOrNull { cards[it.key]?.canResume == true }
            ?: copies.firstOrNull { it.key in cards || collection.output(it.key).isNotBlank() } ?: game
    }
    private fun bindLibraryProgress(view: GameProgressView, game: LibraryEntry, list: String) {
        if (list != "Downloads") {
            view.visibility = View.GONE
            return
        }
        bindProgress(view, game, list)
    }
    private fun bindProgress(view: GameProgressView, entry: LibraryEntry, list: String = collection.list, hideCompleted: Boolean = false) {
        val game = progressSource(entry, list)
        val transfer = transferCards()[game.key] ?: collection.output(game.key).takeIf { it.isNotBlank() }?.let {
            TransferCard(game.key, game.store.label, game.name, "complete", "", 1.0, "", "", false, false, false, it)
        }
        if (transfer == null || (hideCompleted && transfer.phase == "complete")) { view.visibility = View.GONE; return }
        view.render(transfer,
            pause = { act { if (game.store == LibraryStore.STEAM) engine.pauseDownload() else storeDownloads.pause() } },
            resume = { withNotifications { act { if (game.store == LibraryStore.STEAM) engine.resumeDownload() else storeDownloads.resume() } } },
            cancel = { act { if (game.store == LibraryStore.STEAM) engine.cancelDownload() else storeDownloads.cancel() } },
            open = { act { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } },
            remove = { act {
                if (transferCards()[game.key]?.phase == "complete") {
                    if (game.store == LibraryStore.STEAM) engine.removeCompletedDownload() else storeDownloads.removeCompletedDownload()
                } else if (transferCards().containsKey(game.key)) {
                    if (game.store == LibraryStore.STEAM) engine.removeFinishedDownload() else storeDownloads.removeFinishedDownload()
                }
                collection.forget(game.key); render(state, true)
                appSnackbar(root,
                    "Removed from list. Files kept on device.", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
            } })
    }

    private var progressCacheRevision = ""
    private var progressByKey = emptyMap<String, GameProgress>()
    private fun gameProgress(game: LibraryEntry) = collection.progress(listCopies(game).map { it.key })
    private var hiddenCacheRevision = ""
    private var hiddenKeys = emptySet<String>()
    private fun hiddenGameKeys(): Set<String> {
        val revision = "$libraryRevision:${collection.membershipRevision}"
        if (hiddenCacheRevision != revision) {
            hiddenKeys = libraryListKeys(ownedLibrary, collection.members(CollectionData.HIDDEN_LIST))
            hiddenCacheRevision = revision
        }
        return hiddenKeys
    }
    private fun gameIsHidden(game: LibraryEntry) = game.key in hiddenGameKeys()
    private fun libraryListEntries(name: String, includeHidden: Boolean = collection.showHiddenGames): List<LibraryEntry> {
        if (name == WishlistItems.LIST) return entries.filter { it.key in wishlistKeys && (includeHidden || !gameIsHidden(it)) }
        GameProgress.fromLabel(name)?.let { state ->
            val revision = "$libraryRevision:${collection.membershipRevision}"
            if (progressCacheRevision != revision) {
                progressByKey = buildMap {
                    mergeLibraryCopies(ownedLibrary).forEach { game ->
                        val copies = game.copies.ifEmpty { listOf(game) }
                        val progress = collection.progress(copies.map { it.key })
                        copies.forEach { put(it.key, progress) }
                    }
                }
                progressCacheRevision = revision
            }
            return ownedLibrary.filter { progressByKey[it.key] == state && (includeHidden || !gameIsHidden(it)) }
        }
        val filter = listStore(name)
        val members = if (collection.isAutomaticList(name) || name == "Downloads") emptySet()
            else libraryListKeys(ownedLibrary, collection.members(name))
        val cards = if (name == "Downloads") transferCards() else emptyMap()
        return entries.filter { game ->
            (name == "Downloads" || includeHidden || !gameIsHidden(game)) && (filter == null || game.store == filter) && when {
                collection.isAutomaticList(name) && name == "Game Pass" -> game.remote?.xboxAccess == XboxAccess.GAME_PASS
                collection.isAutomaticList(name) -> isOwned(game) && game.remote?.xboxAccess != XboxAccess.GAME_PASS
                else -> when (name) {
                    "" -> isOwned(game) && game.remote?.xboxAccess != XboxAccess.GAME_PASS
                    "Downloads" -> game.key in cards || collection.output(game.key).isNotBlank()
                    else -> game.key in members
                }
            }
        }
    }

    private fun populatedLibraryLists(): List<String> {
        val connectedStores = connectedLibraryStores()
        // Saved/cached accounts still count while browsing offline after a cold start.
        val stores = buildSet {
            if (connected || state.optBoolean("sessionAvailable") || state.optBoolean("libraryCached")) add(LibraryStore.STEAM)
            collection.libraryGames().forEach { add(it.store) }
            accounts.state.value.filterValues { it.saved || it.connected || it.libraryLoaded }.keys.forEach {
                add(LibraryStore.valueOf(it.name))
            }
        }
        return collection.visibleLists { libraryListEntries(it).isNotEmpty() }.filter { name ->
            val store = listStore(name)
            when {
                name == "Favorites" -> connectedStores.isNotEmpty()
                store == LibraryStore.XBOX -> LibraryStore.XBOX in connectedStores
                else -> store == null || (stores.size > 1 && store in stores)
            }
        }
    }

    private fun renderListBar() {
        val row = listsRow ?: return
        row.removeAllViews()
        val names = retainedLibrary?.populatedLists ?: populatedLibraryLists()
        val browsingStore = tab == "store"
        row.pagePosition = if (browsingStore) names.size.toFloat() else names.indexOf(collection.list).coerceAtLeast(0).toFloat()
        (row.parent as? View)?.visibility = View.VISIBLE
        names.forEach { name ->
            row.addView(compactLibraryButton(name) {
                if (browsingStore) { collection.list = name; query = ""; selectTab("library") }
                else if (name == collection.list) {
                    query = ""; renderHeader(); updateLibrary(true)
                } else retainedLibrary?.pager?.setCurrentItem(retainedLibrary?.pageNames?.indexOf(name) ?: 0, true)
            }.apply {
                background = ui.shape(Color.TRANSPARENT, 20)
                isSelected = !browsingStore && collection.list == name
                tooltipText = "Hold to manage lists"
                setOnLongClickListener { showListManager(name); true }
            },
                LinearLayout.LayoutParams(-2, ui.dp(48)).apply { marginEnd = ui.dp(4) })
        }
        row.addView(compactLibraryButton("Steam search") { selectTab("store") }.apply {
            background = ui.shape(Color.TRANSPARENT, 20)
            isSelected = browsingStore
        },
            LinearLayout.LayoutParams(-2, ui.dp(48)))
    }

    private fun revealSelectedList() {
        val row = listsRow ?: return
        row.post { row.revealPosition() }
    }

    private fun libraryListBarVisible(): Boolean {
        val row = listsRow ?: return false
        val visible = android.graphics.Rect()
        return row.getGlobalVisibleRect(visible) && visible.height() > 0
    }

    private fun hideLibraryPageHint() {
        libraryPageHintJob?.cancel(); libraryPageHintJob = null
        libraryPageHint.animate().cancel()
        libraryPageHint.visibility = View.GONE
    }

    private fun showLibraryPageHint(name: String) {
        hideLibraryPageHint()
        libraryPageHint.apply {
            text = name
            alpha = 0f
            translationY = -ui.dp(16).toFloat()
            visibility = View.VISIBLE
            animate().alpha(1f).translationY(0f).setDuration(160).start()
        }
        libraryPageHintJob = scope.launch {
            delay(1400)
            libraryPageHint.animate().alpha(0f).translationY(-ui.dp(64).toFloat()).setDuration(240)
                .withEndAction { libraryPageHint.visibility = View.GONE }.start()
        }
    }

    private fun showXboxDiscPicker() {
        networkRequested = true
        showCollectionSearch(this, ui, scope, artwork, listOf(LibraryStore.XBOX),
            contains = { key -> collection.discGames().any { it.key == key } },
            add = { game -> collection.addDisc(game); render(state, true) },
            remove = { game -> collection.removeDisc(game.key); render(state, true) },
            importSteam = null, destination = "Xbox disc collection")
    }

    private fun connectedLibraryStores(): Set<LibraryStore> = LibraryStore.entries.filterTo(mutableSetOf()) { store ->
        if (store == LibraryStore.STEAM) connected || state.optBoolean("sessionAvailable")
        else store.account?.let { accounts.state.value.getValue(it).let { account -> account.connected || account.saved } } == true
    }

    private fun showWishlistPicker() {
        networkRequested = true
        val connectedStores = connectedLibraryStores()
        val available = StoreCatalog.SEARCH_STORES.filter { it in connectedStores }
        showCollectionSearch(this, ui, scope, artwork, available,
            contains = { key -> collection.wishlistGames().any { it.key == key } },
            add = { game -> collection.addWishlist(listOf(game)); render(state, true) },
            remove = { game -> collection.removeWishlist(setOf(game.key)); render(state, true) },
            importSteam = if (LibraryStore.STEAM in available) ({ importSteamWishlist() }) else null)
    }

    private fun importSteamWishlist() {
        showSteamWishlistImport(this, ui, scope, com.gamefibre.steam.TokenVault(this).ownerId().orEmpty()) { games ->
            val added = collection.addWishlist(games)
            networkRequested = true; render(state, true)
            added
        }
    }

    private fun isWishlisted(game: LibraryEntry) = game.copies.ifEmpty { listOf(game) }.any { it.key in wishlistKeys }

    private fun setWishlist(game: LibraryEntry, included: Boolean) {
        val copies = game.copies.ifEmpty { listOf(game) }
        if (included) {
            collection.addWishlist(copies)
            render(state, true)
        } else {
            val keys = copies.map { it.key }.toSet()
            val removed = collection.wishlistGames().filter { it.key in keys }
            collection.removeWishlist(keys)
            if (tab == "game" && !isOwned(game)) {
                collection.list = WishlistItems.LIST; selectTab("library")
            }
            render(state, true)
            appSnackbar(root, "Removed from wishlist", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction("Undo") { collection.addWishlist(removed); render(state, true) }.show()
        }
    }

    private fun showSortPicker() {
        val (dialog, content) = modal(); downloadDialog = dialog
        ui.add(content, ui.text("Sort collection", 24, medium = true)); ui.gap(content, 12)
        val storeSearch = if (tab == "store") storeSearchView else null
        val currentSort = storeSearch?.sortLabel ?: collection.sort
        val currentDescending = storeSearch?.isDescending ?: descending
        val options = if (storeSearch != null) CatalogSort.entries.map { it.label } else listOf("Name", "Time to beat", "Playtime", "Metascore", "Random") +
            if (storeFilter == null || storeFilter == LibraryStore.STEAM) listOf("Size") else emptyList()
        fun choice(label: String, value: String) = RadioButton(this).apply {
            id = View.generateViewId(); tag = value; text = label; textSize = 14f; typeface = ui.font
            setTextColor(ui.ink); buttonTintList = android.content.res.ColorStateList.valueOf(ui.blue)
            minHeight = ui.dp(48)
        }
        val sorts = RadioGroup(this)
        options.forEach { name -> sorts.addView(choice(name, name).apply { isChecked = currentSort == name }) }
        ui.add(content, sorts); ui.gap(content, 16)
        val orderLabel = ui.text("Order", 12, ui.secondary)
        ui.add(content, orderLabel)
        val order = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("Ascending" to false, "Descending" to true).forEach { (label, reversed) ->
            order.addView(choice(label, reversed.toString()).apply { isChecked = currentDescending == reversed }, RadioGroup.LayoutParams(0, -2, 1f))
        }
        ui.add(content, order); ui.gap(content, 20)
        fun updateOrderVisibility() {
            val random = sorts.findViewById<RadioButton>(sorts.checkedRadioButtonId)?.tag == "Random"
            orderLabel.visibility = if (random) View.GONE else View.VISIBLE
            order.visibility = if (random) View.GONE else View.VISIBLE
        }
        sorts.setOnCheckedChangeListener { _, _ -> updateOrderVisibility() }
        updateOrderVisibility()
        dialogActions(dialog, ui.button("Apply", true) {
            if (storeSearch != null) {
                val sort = sorts.findViewById<RadioButton>(sorts.checkedRadioButtonId)?.tag?.toString() ?: currentSort
                val reverse = order.findViewById<RadioButton>(order.checkedRadioButtonId)?.tag == "true"
                storeSearch.applySort(sort, reverse)
                dialog.dismiss()
                return@button
            }
            collection.sort = sorts.findViewById<RadioButton>(sorts.checkedRadioButtonId)?.tag?.toString() ?: "Name"
            if (collection.sort == "Random") collection.randomSeed = kotlin.random.Random.nextLong()
            descending = collection.sort != "Random" && order.findViewById<RadioButton>(order.checkedRadioButtonId)?.tag == "true"
            collection.preferences.edit().putBoolean("descending", descending).apply()
            bySize = collection.sort == "Size"
            dialog.dismiss(); selectTab("library"); updateLibrary(true)
        }, ui.button("Cancel") { dialog.dismiss() })
        showModal(dialog)
    }

    private fun showLibraryOptions() {
        val anchor = header.findViewWithTag<View>("collection-options") ?: return
        val storeSearch = if (tab == "store") storeSearchView else null
        androidx.appcompat.widget.PopupMenu(this, anchor).apply {
            if (storeSearch != null) {
                menu.add(0, 1, 0, "Sort by: ${storeSearch.sortLabel} · ${if (storeSearch.isDescending) "Descending" else "Ascending"}")
                if (storeSearch.pendingCount > 0) menu.add(0, 8, 1, getString(R.string.catalog_preparation, storeSearch.pendingCount))
            } else {
                menu.add(0, 1, 0, "Sort by: ${collection.sort}" + if (collection.sort == "Random") "" else " · ${if (descending) "Descending" else "Ascending"}")
                menu.add(0, 2, 1, "View: ${collection.tileSize}")
                menu.add(0, 5, 2, "Fit cover art").apply { isCheckable = true; isChecked = collection.fitCoverArt }
                menu.add(0, 3, 3, "Show titles").apply { isCheckable = true; isChecked = collection.showTitles }
                menu.add(0, 4, 4, "Show hidden games").apply { isCheckable = true; isChecked = collection.showHiddenGames }
                menu.add(0, 6, 5, "Show Game Pass").apply { isCheckable = true; isChecked = collection.showGamePass }
                if (batchArtwork?.status?.value?.running == true) menu.add(0, 7, 6, "Artwork progress")
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    8 -> storeSearch?.showPreparation()
                    7 -> showArtworkProgress()
                    6 -> {
                        collection.showGamePass = !collection.showGamePass
                        if (!collection.showGamePass && collection.list == "Game Pass" && collection.isAutomaticList(collection.list)) collection.list = "All games"
                        clearLibrarySelection()
                        updateLibrary(true)
                    }
                    1 -> showSortPicker()
                    2 -> showChoiceDialog(this@MainActivity, ui, "View", listOf("Small", "Medium", "Large", "List"),
                            listOf("Small", "Medium", "Large", "List").indexOf(collection.tileSize)) { index ->
                            collection.tileSize = listOf("Small", "Medium", "Large", "List")[index]
                            render(state, true)
                        }
                    5 -> { collection.fitCoverArt = !collection.fitCoverArt; updateLibrary(true) }
                    3 -> { collection.showTitles = !collection.showTitles; updateLibrary(true) }
                    4 -> {
                        collection.showHiddenGames = !collection.showHiddenGames
                        clearLibrarySelection()
                        updateLibrary(true)
                    }
                }
                true
            }
            show()
        }
    }

    private fun showCollectionMenu() {
        if (collectionMenu?.isShowing == true) return
        val drawer = SwipeDismissMenu(this).apply { setCanceledOnTouchOutside(true) }
        collectionMenu = drawer
        drawer.setOnDismissListener { if (collectionMenu === drawer) collectionMenu = null }
        val menu = ui.column().apply { background = ui.shape(ui.background, 28); clipToOutline = true }
        val scroll = ScrollView(this)
        val content = ui.column(20).apply { setBackgroundColor(ui.background) }
        scroll.addView(content)
        menu.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        drawer.setContentView(menu)
        val heading = ui.row()
        heading.addView(ui.text("Your collection", 22, medium = true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(ui.iconButton(R.drawable.ic_x, "Close menu") { drawer.dismiss() }); ui.add(content, heading)
        fun item(label: String, selected: Boolean = false, action: () -> Unit) {
            ui.add(content, ui.button(label) { drawer.dismiss(); action() }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                isSelected = selected
                if (selected) background = ui.shape(ui.paleBlue, 24)
            })
        }
        ui.gap(content, 12)
        val inCollection = tab == "library" || (tab == "game" && gameReturnTab == "library")
        val inWishlist = collection.list == WishlistItems.LIST
        item("Library", inCollection && !inWishlist) {
            if (inWishlist) { collection.list = "All games"; query = "" }
            selectTab("library")
        }
        item("Wishlist", inCollection && inWishlist) {
            collection.list = WishlistItems.LIST; query = ""
            selectTab("library")
        }
        item("Steam search", tab == "store" || (tab == "game" && gameReturnTab == "store")) { selectTab("store") }
        item("Accounts", tab == "accounts") { selectTab("accounts") }
        item("Settings", tab == "settings") { selectTab("settings") }
        if (appUpdatePrompt.enabled) item(appUpdatePrompt.menuLabel) { appUpdatePrompt.fromMenu() }
        drawer.window?.protectScreenCapture(isScreenCaptureProtected())
        drawer.window?.setBackgroundDrawableResource(android.R.color.transparent)
        drawer.show()
        drawer.window?.apply { setGravity(Gravity.START or Gravity.TOP); setLayout(ui.dp(340).coerceAtMost(resources.displayMetrics.widthPixels - ui.dp(32)), -1) }
    }

    private fun renderSettings() {
        val content = scrollColumn()
        populateSettings(content, ui, collection.preferences,
            theme = {
                val values = listOf("System", "Light", "Dark")
                showChoiceDialog(this, ui, "Theme", values,
                    values.indexOf(collection.preferences.getString("theme", "System"))) { index ->
                    val value = values[index]
                    collection.preferences.edit().putString("theme", value).apply()
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(when (value) {
                        "Light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                        "Dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                        else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    })
                    render(state, true)
                }
            },
            youtube = ::editYouTubeSearches,
            coverArt = {
                showSteamGridDbKey(this, ui, ::openUrl, dismissed = { render(state, true) })
            },
            export = ::showCollectionExports,
            importBackup = ::showCollectionImport,
            storage = {
                showStorageDialog(this, ui, scope) {
                    pageDetailsJob?.cancel(); pageDetailsJob = null
                    pageDetails.clear(); pageDetailsRepository.invalidate()
                    stagedAssetSignature = ""
                    render(engine.snapshot(), true)
                }
            },
            attribution = ::showAttributions)
    }

    private fun editYouTubeSearches() {
        showYouTubeSettings(this, ui, collection.preferences) { render(state, true) }
    }

    private fun showCollectionImport() {
        val (confirmation, details) = modal("Import collection")
        downloadDialog = confirmation
        ui.add(details, ui.text("Merge lists and favorites from a backup. Imported progress and covers replace matching entries, and collection display settings are restored. Existing games and other lists are kept.", 15))
        ui.gap(details, 12)
        ui.add(details, ui.text("Library metadata is restored for browsing. Sign in to download games. Images load from their saved URLs when online; no asset files are included.", 14, ui.secondary))
        dialogActions(confirmation, ui.button("Choose ZIP", true) {
            confirmation.dismiss()
            act { importCollection.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
        }, ui.button("Cancel") { confirmation.dismiss() })
        showModal(confirmation)
    }

    private fun showCollectionExports() {
        val (dialog, content) = modal("Backup and export")
        downloadDialog = dialog
        ui.add(content, ui.text("Back up your library, wishlist, lists, progress, settings, and custom cover URLs. Images and game files are excluded.", 14, ui.secondary))
        ui.gap(content, 16)
        ui.add(content, ui.button("Export backup (ZIP)") {
            dialog.dismiss()
            act { exportCollection.launch(libraryExportFilename("zip")) }
        })
        ui.add(content, ui.button("Export spreadsheet (CSV)") {
            dialog.dismiss()
            act { exportCollectionCsv.launch(libraryExportFilename("csv")) }
        })
        ui.gap(content, 12)
        ui.add(content, ui.text("CSV includes your current library, stores, progress, lists, playtime, and time to beat. Use ZIP to restore library metadata, settings, and custom cover URLs.", 13, ui.secondary))
        showModal(dialog)
    }

    private fun libraryExportFilename(extension: String): String {
        val date = java.time.LocalDate.now()
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val monthDay = date.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT))
        return "gamefibre-library-${date.year}-v$version-$monthDay.$extension"
    }

    private fun startCollectionTransfer(uri: Uri, format: String) {
        val app = applicationContext
        val games = if (format == "csv") (ownedLibrary + collection.wishlistGames()).distinctBy { it.key } else ownedLibrary
        val beat = beatIndex
        collectionTransfer.run(if (format == "import") "Importing collection…" else "Exporting collection…", format == "import") {
            val backup = CollectionBackup(app)
            if (format == "import") {
                app.contentResolver.openInputStream(uri)?.use(backup::importZip) ?: error("Could not open this backup.")
            } else {
                app.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    if (format == "csv") backup.exportCsv(output, games, beat) else backup.exportZip(output, games)
                } ?: error("Could not write this file.")
                "Collection ${if (format == "csv") "CSV" else "backup"} exported."
            }
        }
    }

    private fun showAttributions() {
        val (dialog, content) = modal("Attribution")
        appAttributions.forEach { credit ->
            ui.add(content, ui.button(credit.title) {
                val (noticeDialog, noticeContent) = modal(credit.title)
                ui.add(noticeContent, ui.text(getAssets().open(credit.notice).bufferedReader().use { it.readText() },
                    12, ui.secondary).apply { setTextIsSelectable(true) })
                dialogActions(noticeDialog, ui.button("Close") { noticeDialog.dismiss() })
                showModal(noticeDialog)
            }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })
        }
        ui.gap(content, 24)
        dialogActions(dialog, ui.button("Close") { dialog.dismiss() })
        showModal(dialog)
    }

    private data class GamePageVisit(val key: String, val source: String, val scrollY: Int, val sequence: List<String>, val game: LibraryEntry? = null)

    private fun backFromGame() {
        while (gameHistory.isNotEmpty()) {
            val visit = gameHistory.removeAt(gameHistory.lastIndex)
            val previous = entries.find { it.key == visit.key } ?: visit.game ?: continue
            selectedKey = previous.key; selectedEntry = previous
            downloadSourceKey = visit.source; gamePageScrollY = visit.scrollY; gameSequence = visit.sequence
            screenKey = "" // Keep the destination's saved scroll position during the rebuild.
            render(state, true)
            return
        }
        selectTab(gameReturnTab)
    }

    private fun openGame(game: LibraryEntry, sequence: List<String>) {
        if (tab == "game" && selectedKey == game.key) return
        if (tab == "game") gameHistory.add(GamePageVisit(selectedKey, downloadSourceKey,
            (body.getChildAt(0) as? ScrollView)?.scrollY ?: gamePageScrollY, gameSequence,
            selectedEntry.takeIf { gameReturnTab == "store" }))
        else { gameHistory.clear(); gameReturnTab = if (tab == "store") "store" else "library" }
        gameSequence = sequence
        showGame(game)
    }

    private fun swipeGame(direction: Int) {
        val next = adjacentGameKey(gameSequence, selectedKey, direction, entries.map { it.key }.toSet()) ?: return
        val game = entries.firstOrNull { it.key == next } ?: return
        // Siblings replace the current visit; Back still returns to the parent list/page.
        showGame(game)
    }

    private fun showGame(game: LibraryEntry) {
        networkRequested = true
        screenKey = ""
        gamePageScrollY = 0; selectedKey = game.key; selectedEntry = game; downloadSourceKey = game.key; hideKeyboard(); tab = "game"; render(state, true)
        if (gameReturnTab != "store") scope.launch { beatReady.await(); communitySteamId(game)?.let { requestLiveTags(it) } }
    }

    private fun mergedDetails(game: LibraryEntry): GameDetails {
        val sources = ownedCopies(game).sortedWith(compareBy<LibraryEntry> { customArtwork.address(it.key) == null }.thenBy { it.store.ordinal })
        return mergeGameDetails(sources.map { it.details } + sources.mapNotNull { pageDetails[it.key] })
    }
    private fun gameScreenKey() = "game:$selectedKey:${selectedEntry?.let { assetRepository.published(it)?.let { card -> "${card.asset.blob}:${card.currentAccess}" } }}:${selectedEntry?.let { mergedDetails(it).json() }}:${beatIndex != null}:${selectedEntry?.let(::ownedCopies)?.map { it.key }}:$libraryRevision:$ownedTagRevision"
    private fun fetchPageDetails(game: LibraryEntry) {
        if (!pageDetails.shouldLoad(game.key)) return
        val sources = ownedCopies(game).sortedWith(compareBy<LibraryEntry> { customArtwork.address(it.key) == null }.thenBy { it.store.ordinal })
        pageDetailsJob = scope.launch {
            if (coverSteamId(game) == null && beatIndex == null) beatReady.await()
            val steamId = coverSteamId(game)
            val keys = (sources.map { it.key } + game.key).distinct()
            // Publish disk details before authentication or any request can stall.
            val cached = withContext(Dispatchers.IO) {
                buildList {
                    steamId?.let { pageDetailsRepository.cachedSteam(it)?.let(::add) }
                    sources.forEach { source ->
                        when (source.store) {
                            LibraryStore.GOG -> pageDetailsRepository.cachedGog(source.id)?.let(::add)
                            LibraryStore.EPIC -> source.remote?.let(pageDetailsRepository::cachedEpic)?.let(::add)
                            else -> Unit
                        }
                    }
                }
            }
            if (cached.isNotEmpty()) {
                val details = mergeGameDetails(cached)
                details.metascore?.let { score -> collection.rememberMetascores(keys.associateWith { score }) }
                pageDetails.record(keys, details, retryNeeded = true)
                if (tab == "game" && selectedKey == game.key) {
                    pageDetailsUpdate?.invoke()
                    screenKey = gameScreenKey()
                }
            }
            if (!networkRequested || !NetworkAvailability.canRequest(this@MainActivity)) return@launch
            val loaded = withContext(Dispatchers.IO) {
                val details = mutableListOf<GameDetails>()
                var retryNeeded = false
                fun add(value: GameDetails?) {
                    ensureActive()
                    if (value == null) retryNeeded = true else details.add(value)
                }
                steamId?.let { add(pageDetailsRepository.steam(it)) }
                if (details.none { it.screenshots.isNotEmpty() }) {
                    sources.firstOrNull { it.store == LibraryStore.GOG }?.let { add(pageDetailsRepository.gog(it.id)) }
                }
                if (details.none { it.screenshots.isNotEmpty() }) {
                    sources.firstOrNull { it.store == LibraryStore.EPIC }?.remote?.takeIf { it.namespace.isNotBlank() && it.catalogId.isNotBlank() }?.let { remote ->
                        val epicDetails = runCatching {
                            pageDetailsRepository.currentEpic(remote) ?: run {
                                val session = accounts.downloadSession(GameStore.EPIC, accounts.owner(GameStore.EPIC))
                                pageDetailsRepository.epic(remote, session)
                            }
                        }.getOrNull()
                        ensureActive()
                        add(epicDetails)
                    }
                }
                mergeGameDetails(details) to retryNeeded
            }
            pageDetails.record((sources.map { it.key } + game.key).distinct(), loaded.first, loaded.second)
            loaded.first.metascore?.let { score -> collection.rememberMetascores(keys.associateWith { score }) }
            if (collection.sort == "Metascore") retainedLibrary?.pages?.values?.forEach { it.builtQuery = "" }
            if (tab == "game" && selectedKey == game.key) {
                pageDetailsUpdate?.invoke()
                screenKey = gameScreenKey()
            }
        }
    }

    private fun openUrl(address: String) {
        act { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address))) }
    }

    private fun showGameActions() {
        val game = selectedEntry ?: return
        val anchor = header.findViewWithTag<View>("game-actions") ?: return
        val sources = ownedCopies(game)
        val source = sources.firstOrNull { it.key == downloadSourceKey } ?: sources.firstOrNull() ?: game
        val downloaded = transferCards()[source.key]?.phase == "complete" || collection.output(source.key).isNotBlank()
        androidx.appcompat.widget.PopupMenu(this, anchor).apply {
            menu.add(0, 2, 0, if (!isOwned(source) || !source.store.downloads) "View on ${source.store.label}" else if (downloaded) "Open Downloads" else "Download from ${source.store.label}")
            if (downloaded && isOwned(source) && source.store.downloads) menu.add(0, 9, 0, "Download again")
            if (isOwned(game)) menu.add(0, 3, 1, "Set progress: ${gameProgress(game).label}")
            if (gameReturnTab != "store" || isOwned(game)) menu.add(0, 4, 2, "Set lists")
            menu.add(0, 1, 3, "Change cover art")
            menu.add(0, 6, 4, if (isWishlisted(game)) "Remove from wishlist" else "Add to wishlist")
            if (gameReturnTab != "store" || isOwned(game)) menu.add(1, 5, 5, if (gameIsHidden(game)) "Unhide game" else "Hide game")
            androidx.core.view.MenuCompat.setGroupDividerEnabled(menu, true)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> changeCoverArtwork(game)
                    2 -> downloadGame(source)
                    9 -> showDownload(source)
                    3 -> showGameProgress(game) { render(state, true) }
                    4 -> showGameLists(game)
                    5 -> changeGameVisibility(listOf(game), hidden = !gameIsHidden(game))
                    6 -> setWishlist(game, !isWishlisted(game))
                }
                true
            }
            show()
        }
    }

    private fun downloadGame(source: LibraryEntry) {
        if (!isOwned(source) || !source.store.downloads) {
            openUrl(storePageUrl(source.store.label, source.details.storeLinks[source.store.label].orEmpty())
                ?: storeSearchUrl(source.store, source.name))
            return
        }
        val transfer = transferCards()[source.key]
        if (transfer?.phase == "complete" || collection.output(source.key).isNotBlank()) act { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
        else if (transfer?.running == true || transfer?.canResume == true) { collection.list = "Downloads"; selectTab("library") }
        else if (isOwned(source)) showDownload(source) else selectTab("accounts")
    }

    private fun showGameProgress(game: LibraryEntry, changed: () -> Unit) {
        val (dialog, content) = modal("Progress")
        downloadDialog = dialog
        val choices = RadioGroup(this)
        GameProgress.entries.forEach { progress ->
            choices.addView(RadioButton(this).apply {
                id = View.generateViewId(); text = progress.label; typeface = ui.font; textSize = 15f
                setTextColor(ui.ink); buttonTintList = android.content.res.ColorStateList.valueOf(ui.blue)
                minHeight = ui.dp(48); isChecked = gameProgress(game) == progress
                setOnClickListener {
                    collection.setProgress(listCopies(game).map { it.key }.toSet(), progress)
                    dialog.dismiss()
                    changed()
                }
            })
        }
        ui.add(content, choices)
        showModal(dialog)
    }

    private fun showGameLists(game: LibraryEntry) {
        val (dialog, content) = modal("Set lists")
        downloadDialog = dialog
        val selected = collection.editableLists.filter { inGameList(it, game) }.toMutableSet()
        val chips = CenteredChipGroup(this)
        collection.editableLists.forEach { list ->
            chips.addView(ui.listChip(if (list == "Favorites") "Favorite" else list, list in selected) { included ->
                if (included) selected.add(list) else selected.remove(list)
            })
        }
        fun applyLists() {
            collection.editableLists.forEach { setGameList(it, game, it in selected) }
            render(state, true)
        }
        chips.addView(ui.button("+ New list") {
            dialog.dismiss()
            // Apply pending choices only after a new list is successfully created.
            showCreateList(listOf(game)) { created -> selected.add(created); applyLists() }
        })
        ui.add(content, chips)
        dialogActions(dialog, ui.button("Apply", true) { applyLists(); dialog.dismiss() }, ui.button("Cancel") { dialog.dismiss() })
        showModal(dialog)
    }

    private fun changeGameVisibility(games: List<LibraryEntry>, hidden: Boolean) {
        val keys = games.flatMap(::listCopies).map { it.key }.toSet()
        val previouslyHidden = collection.members(CollectionData.HIDDEN_LIST).intersect(keys)
        collection.setMembership(CollectionData.HIDDEN_LIST, keys, hidden)
        clearLibrarySelection()
        if (tab == "game" && hidden && !collection.showHiddenGames) selectTab("library")
        else render(state, true)
        updateLibrary(true)
        val message = if (games.size == 1) { if (hidden) "Game hidden" else "Game unhidden" }
            else if (hidden) "Games hidden" else "Games unhidden"
        appSnackbar(root, message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setAction("Undo") {
                collection.setMembership(CollectionData.HIDDEN_LIST, keys - previouslyHidden, false)
                collection.setMembership(CollectionData.HIDDEN_LIST, previouslyHidden, true)
                render(state, true)
                updateLibrary(true)
            }.show()
    }

    private fun changeCoverArtwork(game: LibraryEntry) {
        networkRequested = true
        val keys = ownedCopies(game).map { it.key }
        fun changed() { scope.launch { refreshCustomArtwork(keys.toSet()) } }
        if (SteamGridDbKeyVault(this).read() == null) {
            if (keys.any { customArtwork.address(it) != null }) {
                showChoiceDialog(this, ui, "Cover art", listOf("Choose SteamGridDB cover", "Reset cover")) { index ->
                        if (index == 0) showSteamGridDbKey(this, ui, ::openUrl) { changeCoverArtwork(game) }
                        else act { customArtwork.reset(keys); changed() }
                }
            } else showSteamGridDbKey(this, ui, ::openUrl) { changeCoverArtwork(game) }
            return
        }
        showSteamGridDbPicker(this, ui, scope, artwork, game.name, coverSteamId(game),
            keys.any { customArtwork.address(it) != null }, apply = { choice ->
                scope.launch {
                    try { applyCoverArtwork(keys, choice) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        appSnackbar(root,
                            error.message ?: "Could not save cover.",
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                            .setAction("Retry") { changeCoverArtwork(game) }.show()
                    }
                }
            }, reset = { customArtwork.reset(keys); changed() })
    }

    private suspend fun applyCoverArtwork(keys: List<String>, choice: GridArtwork) {
        saveGridArtwork(artwork, customArtwork, keys, choice) { refreshCustomArtwork(keys.toSet()) }
    }

    private suspend fun refreshCustomArtwork(keys: Set<String>) {
        assetRepository.refreshArtwork(entries.filter { it.key in keys }, libraryOwners()).join()
        storeSearchView?.retryArtwork()
        render(engine.snapshot())
    }

    private fun startBatchArtwork(games: List<LibraryEntry>) {
        if (batchArtwork?.status?.value?.running == true) { showArtworkProgress(); return }
        val key = SteamGridDbKeyVault(this).read()
        if (key == null) {
            showSteamGridDbKey(this, ui, ::openUrl, saved = { startBatchArtwork(games) })
            return
        }
        networkRequested = true
        val targets = games.map { ArtworkTarget(it.name, ownedCopies(it).map { copy -> copy.key }.distinct()) }
            .distinctBy { it.keys.sorted() }
        val batch = batchArtworkModel.start(applicationContext, key, targets)
        observeBatchArtwork(batch)
        showArtworkProgress(batch)
    }

    private fun observeBatchArtwork(batch: BatchArtwork) {
        batchArtworkObservation?.cancel()
        batchArtworkObservation = scope.launch {
            var processed = 0
            batch.status.first { status ->
                if (status == null) return@first false
                val fresh = status.results.drop(processed)
                processed = status.results.size
                val keys = fresh.flatMap { it.appliedKeys }.toSet()
                if (keys.isNotEmpty()) refreshCustomArtwork(keys)
                if (!status.running && batchArtworkDialog?.isShowing != true && !isFinishing && !isDestroyed) {
                    appSnackbar(root, status.message,
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                }
                !status.running
            }
        }
    }

    private fun showArtworkProgress(batch: BatchArtwork? = batchArtwork) {
        batch ?: return
        if (batchArtworkDialog?.isShowing == true) return
        batchArtworkDialog = showBatchArtworkDialog(this, ui, scope, batch) {
            batchArtworkDialog = null
            if (batch.status.value?.running == true && !isFinishing && !isDestroyed && !isChangingConfigurations) {
                appSnackbar(root,
                    "Applying artwork · continues while browsing",
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .setAction("View") { showArtworkProgress() }.show()
            }
        }
    }

    private fun renderGamePage(game: LibraryEntry, preview: Boolean = false): ScrollView {
        val sources = ownedCopies(game).sortedWith(compareBy<LibraryEntry> { customArtwork.address(it.key) == null }.thenBy { it.store.ordinal })
        var sourceKey = downloadSourceKey
        fun chosenSource() = sources.firstOrNull { it.key == sourceKey } ?: sources.firstOrNull { it.store.downloads } ?: sources.first()
        if (!preview) downloadSourceKey = chosenSource().key
        // Previews use the same layout, without changing the selected page or its subscriptions.
        val content = if (preview) scrollColumn(if (landscape) 16 else 20, target = FrameLayout(this))
            else scrollColumn(if (landscape) 16 else 20, swipeGame = ::swipeGame) { onBackPressedDispatcher.onBackPressed() }
        val scroll = content.parent as ScrollView
        if (!preview) (scroll as GamePageScrollView).adjacentPage = { direction ->
            val key = adjacentGameKey(gameSequence, game.key, direction, entries.map { it.key }.toSet())
            entries.firstOrNull { it.key == key }?.let { next ->
                renderGamePage(next, preview = true).also { (it.parent as ViewGroup).removeView(it) }
            }
        }
        content.setPadding(content.paddingLeft, ui.dp(8), content.paddingRight, content.paddingBottom)
        var heroActionsHeight: ((Int) -> Int)? = null
        val hero = if (landscape) ui.row().apply { gravity = Gravity.TOP }.also { ui.add(content, it) } else content
        val cover = FrameLayout(this).apply { background = ui.shape(ui.surface, 16); clipToOutline = true }
        val backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        val shade = View(this).apply {
            setBackgroundColor(0x88000000.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        val art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        fun displayCover(bitmap: Bitmap?, key: String) {
            if (bitmap != null) { art.setImageBitmap(bitmap); cover.visibility = View.VISIBLE } else cover.visibility = View.INVISIBLE
            art.scaleType = if (collection.fitCoverArt) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            val landscapeArt = !collection.fitCoverArt && bitmap != null && bitmap.height <= bitmap.width
            backdrop.visibility = if (landscapeArt) View.VISIBLE else View.GONE
            shade.visibility = backdrop.visibility
            if (landscapeArt) backdrop.setImageBitmap(bitmap)
            else backdrop.setImageDrawable(null)
        }
        displayCover(sources.firstNotNullOfOrNull(assetRepository::peek) ?: storeSearchView?.bitmap(communitySteamId(game)), game.key)
        cover.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        cover.addView(shade, FrameLayout.LayoutParams(-1, -1))
        cover.addView(art, FrameLayout.LayoutParams(-1, -1))
        cover.addView(ui.iconButton(R.drawable.ic_pencil, "Change cover art") {
            if (!preview) changeCoverArtwork(game)
        }.apply {
            background = ui.shape(ui.surface, 24)
            elevation = ui.dp(2).toFloat()
        }, FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = ui.dp(8); bottomMargin = ui.dp(8)
        })
        if (landscape) {
            val height = (resources.configuration.screenHeightDp - 112).coerceIn(180, 340)
            hero.addView(cover, LinearLayout.LayoutParams(ui.dp(height * 2 / 3), ui.dp(height)).apply { marginEnd = ui.dp(24) })
        }
        else {
            val frame = object : FrameLayout(this) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val width = View.MeasureSpec.getSize(widthMeasureSpec)
                    val available = (scroll as ViewportScrollView).viewportHeight.takeIf { it > 0 }
                        ?: ui.dp(resources.configuration.screenHeightDp - 100)
                    val reserved = heroActionsHeight?.invoke(width) ?: ui.dp(240)
                    val height = minOf(ui.dp(440), width * 3 / 2, (available - reserved - ui.dp(64)).coerceAtLeast(ui.dp(100)))
                    cover.layoutParams = (cover.layoutParams as FrameLayout.LayoutParams).apply { this.width = height * 2 / 3; this.height = height }
                    super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                }
            }
            frame.addView(cover, FrameLayout.LayoutParams(ui.dp(240), ui.dp(360), Gravity.CENTER))
            ui.add(content, frame); ui.gap(content, 16)
        }
        val artworkJob = scope.launch {
            var best: Bitmap? = null
            var bestKey = game.key
            for (source in sources) {
                val bitmap = assetRepository.bitmap(source)
                if (best == null) { best = bitmap; bestKey = source.key }
                if (bitmap != null && bitmap.height > bitmap.width) { best = bitmap; bestKey = source.key; break }
            }
            if (best != null) displayCover(best, bestKey)
            if (!preview && best != null) {
                val source = sources.first { it.key == bestKey }
                val address = withContext(Dispatchers.IO) { assetRepository.address(source) }
                if (address != null) {
                    cover.contentDescription = "Open fullscreen cover art for ${game.name}"
                    cover.isFocusable = true
                    cover.setOnClickListener {
                        openScreenshotGallery((listOf(address) + mergedDetails(game).screenshots).distinct(), game.name, 0, hasCover = true)
                    }
                }
            }
        }
        if (!preview) pageArtworkJob = artworkJob
        else scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { artworkJob.cancel() }
        })
        val info = if (landscape) ui.column().also { hero.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) } else content
        val title = ui.text(game.name, 26, medium = true).apply { gravity = Gravity.CENTER_HORIZONTAL }
        ui.add(info, title)
        if(assetRepository.published(game)?.currentAccess==false) {
            ui.add(info,ui.text("Kept offline · missing from the latest store snapshot. Access needs checking.",12,ui.secondary))
        }
        val localActions = ui.column()
        val listChips = CenteredChipGroup(this)
        fun updateListChips() {
            listChips.removeAllViews()
            val progress = gameProgress(game)
            if (isOwned(game)) listChips.addView(ui.dropdownChip(progress.label, progress != GameProgress.UNPLAYED,
                "Progress: ${progress.label}. Choose progress") {
                showGameProgress(game, ::updateListChips)
            })
            if (!isOwned(game)) listChips.addView(ui.listChip("Wishlist", isWishlisted(game)) { included -> setWishlist(game, included) })
            if (gameReturnTab == "store" && !isOwned(game)) return
            collection.editableLists.forEach { list ->
                val label = if (list == "Favorites") "Favorite" else list
                listChips.addView(ui.listChip(label, inGameList(list, game)) { included ->
                    setGameList(list, game, included)
                })
            }
            listChips.addView(ui.button("+ New list") {
                showCreateList(game, ::updateListChips)
            }.apply {
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x220b57d0),
                    ui.shape(Color.TRANSPARENT, 24, ui.secondary), null)
            })
        }
        updateListChips()
        ui.add(localActions, listChips)
        ui.gap(info, 12)
        ui.add(info, localActions)
        ui.gap(info, 12)
        val storeActions = GameStoreActions(this, ui, sources, ::chosenSource,
            select = { source -> sourceKey = source.key; if (!preview) downloadSourceKey = source.key },
            owned = ::isOwned,
            downloaded = { source -> transferCards()[source.key]?.phase == "complete" || collection.output(source.key).isNotBlank() },
            action = ::downloadGame, redownload = ::showDownload)
        if (!preview) pageDownloadUpdate = storeActions::update
        ui.add(info, storeActions)
        heroActionsHeight = { width ->
            listOf(title, localActions, storeActions).sumOf { view ->
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.measuredHeight
            } + ui.dp(24)
        }
        val progress = GameProgressView(this).also { ui.add(info, it); bindProgress(it, game, hideCompleted = true) }
        if (!preview) pageProgress = progress
        ui.gap(info, 8)
        val detailsContent = ui.column()
        ui.add(content, detailsContent)
        val videoQueries = YouTubeQueries.lines(collection.preferences.getString(YouTubeQueries.PREFERENCE,
            YouTubeQueries.defaults.joinToString("\n")).orEmpty()).map { YouTubeQueries.resolve(it, game.name) }
        if (!preview && videoQueries.isNotEmpty()) {
            pageVideos = (retainedVideos ?: YouTubeVideos(this, scope, youtubeSearch, game.name, videoQueries,
                ::openUrl, ::videoFullscreenClosed, ::editYouTubeSearches)).also { ui.add(content, it) }
        }
        val relatedContent = ui.column()
        ui.add(content, relatedContent)
        val tagsContent = ui.column()
        ui.add(content, tagsContent)
        fun updateTags() {
            tagsContent.removeAllViews()
            renderCommunityTags(tagsContent, game)
        }
        fun updateDetails() {
            if (!preview) {
                relatedRows.forEach { it.adapter = null }
                relatedRows.clear()
            }
            detailsContent.removeAllViews()
            renderGameDetails(detailsContent, game, preview)
            relatedContent.removeAllViews()
            addRelatedGames(relatedContent, game, mergedDetails(game), trackRows = !preview)
            updateTags()
        }
        if (!preview) { pageDetailsUpdate = ::updateDetails; pageTagsUpdate = if (gameReturnTab == "store") null else ::updateTags }
        updateDetails()
        if (!preview) fetchPageDetails(game)
        return scroll
    }

    private fun communitySteamId(game: LibraryEntry): Int? =
        coverSteamId(game) ?: beatIndex?.steamId(game, game.copies)?.toIntOrNull()

    private fun communityTags(game: LibraryEntry): List<String> {
        val id = communitySteamId(game) ?: return emptyList()
        return liveSteamTags.tags(id) ?: steamTagIndex?.tags(id).orEmpty()
    }

    private fun requestLiveTags(id: Int, force: Boolean = false) {
        if (!beatReady.isCompleted || !networkRequested || id <= 0 || !queuedTagIds.add(id)) return
        if (tagQueue.trySend(id to force).isFailure) queuedTagIds.remove(id)
    }

    private fun scheduleTagEnrichment(force: Boolean = false) {
        if (!beatReady.isCompleted || !networkRequested) return
        entries.mapNotNull(::communitySteamId).distinct().forEach { id ->
            if (steamTagIndex?.tags(id).isNullOrEmpty() || liveSteamTags.contains(id)) requestLiveTags(id, force)
        }
        if (tab == "game") selectedEntry?.let(::communitySteamId)?.let { requestLiveTags(it, force) }
    }

    private fun renderCommunityTags(content: LinearLayout, game: LibraryEntry) {
        if (gameReturnTab == "store") return
        ui.gap(content, 24)
        val selected = gameTagSelections.getOrPut(game.key) { mutableSetOf() }
        val frequencyKey = "$ownedTagRevision:${beatReady.isCompleted}:${liveSteamTags.revision.value}"
        if (ownedTagFrequencyKey != frequencyKey || ownedTagFrequency == null) {
            val games = mergeLibraryCopies(ownedLibrary.filter { it.remote?.xboxAccess != XboxAccess.GAME_PASS })
            ownedTagFrequency = LibraryTagSearch(games.map { TagSearchGame(it.key, it.name, communityTags(it)) })
            ownedTagFrequencyKey = frequencyKey
        }
        val rankedTags = ownedTagFrequency!!.rankByLibraryFrequency(communityTags(game))
        ui.add(content, gameTagsPane(ui, rankedTags, selected, !beatReady.isCompleted) { tags ->
            tagFilters = TagFilters(included = tags)
            tagBarExpanded = true
            collection.list = "All games"
            query = ""
            selectTab("library")
        })
    }

    private fun renderGameDetails(content: LinearLayout, game: LibraryEntry, preview: Boolean) {
        val sources = ownedCopies(game).sortedWith(compareBy<LibraryEntry> { customArtwork.address(it.key) == null }.thenBy { it.store.ordinal })
        val details = mergedDetails(game)
        if (networkRequested && game.key !in pageDetails && NetworkAvailability.canRequest(this))
            ui.add(content, ui.text("Loading game details…", 12, ui.secondary))
        if (details.description.isNotBlank()) {
            ui.gap(content, 20)
            val descriptionText = android.text.Html.fromHtml(descriptionHtmlWithoutImages(details.description), android.text.Html.FROM_HTML_MODE_COMPACT)
                .toString().replace("\uFFFC", "").trim()
            val description = ui.text(descriptionText, 15).apply { maxLines = 5; ellipsize = android.text.TextUtils.TruncateAt.END }
            ui.add(content, description)
            ui.add(content, ui.button("Read more") { description.maxLines = Int.MAX_VALUE; description.ellipsize = null })
        }
        val timeToBeat = sources.firstNotNullOfOrNull { beatIndex?.find(it) }?.let { beat ->
            listOf("Main story" to beat.main, "Main + extras" to beat.extras, "Completionist" to beat.complete)
                .filter { it.second > 0 }
                .map { (label, seconds) ->
                    label to String.format(Locale.getDefault(), "%.1f hours", seconds / 3600.0)
                }
        }.orEmpty()
        val properties = listOf("Developer" to details.developer, "Publisher" to details.publisher,
            "Platform" to details.platforms.joinToString(", "),
            "Released" to details.release, "Category" to details.category,
            "Metascore" to details.metascore?.let { "$it / 100" }.orEmpty(),
            "Steam storage estimate" to details.steamStorageBytes?.takeIf { sources.none { source -> source.sizeBytes != null } }?.let(::fileSize).orEmpty(),
            "Playtime" to sources.mapNotNull { it.steam?.minutes }.maxOrNull()?.takeIf { it > 0 }?.let { String.format(Locale.getDefault(), "%.1f hours", it / 60.0) }.orEmpty(),
            "Xbox access" to when {
                sources.any { it.remote?.xboxAccess == XboxAccess.DISC } -> "Disc"
                sources.any { it.remote?.xboxAccess == XboxAccess.OWNED } -> "Owned"
                sources.any { it.remote?.xboxAccess == XboxAccess.GAME_PASS } -> "Game Pass"
                else -> ""
            },
            "Size" to sources.firstNotNullOfOrNull { it.sizeBytes }?.let(::fileSize).orEmpty()).filter { it.second.isNotBlank() } + timeToBeat
        val steamPageId = coverSteamId(game)
        val linkedStores = (sources.map { it.store } + if (steamPageId != null) listOf(LibraryStore.STEAM) else emptyList()).distinct()
        if (properties.isNotEmpty() || linkedStores.isNotEmpty()) {
            ui.gap(content, 20)
            val propertiesBox = ui.column(16).apply { background = ui.shape(ui.surface) }
            properties.forEach { (label, value) ->
                val row = ui.row()
                row.addView(ui.text(label, 12, ui.secondary), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(ui.text(android.text.Html.fromHtml(value, android.text.Html.FROM_HTML_MODE_COMPACT).toString(), 14), LinearLayout.LayoutParams(0, -2, 1.5f)); ui.add(propertiesBox, row); ui.gap(propertiesBox, 8)
            }
            if (linkedStores.isNotEmpty()) {
                val row = ui.row()
                row.addView(ui.text("Store page", 12, ui.secondary), LinearLayout.LayoutParams(0, -2, 1f))
                val links = android.text.SpannableStringBuilder()
                linkedStores.forEachIndexed { index, store ->
                    if (index > 0) links.append(" · ")
                    val start = links.length
                    links.append(store.label)
                    val direct = if (store == LibraryStore.STEAM && steamPageId != null) "https://store.steampowered.com/app/$steamPageId"
                        else storePageUrl(store.label, details.storeLinks[store.label].orEmpty())
                    links.setSpan(object : android.text.style.ClickableSpan() {
                        override fun onClick(widget: View) {
                            act { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(direct ?: storeSearchUrl(store, game.name)))) }
                        }
                    }, start, links.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                row.addView(ui.text("", 14).apply {
                    text = links
                    setLinkTextColor(ui.blue)
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }, LinearLayout.LayoutParams(0, -2, 1.5f))
                ui.add(propertiesBox, row)
            }
            ui.add(content, propertiesBox)
        }
        if (details.screenshots.isNotEmpty()) {
            ui.gap(content, 20); ui.add(content, ui.text("Screenshots", 18, medium = true))
            ui.add(content, ScreenshotCarousel(this, scope, details.screenshots, game.name, load = { address ->
                withContext(Dispatchers.IO) { artwork.image(address, large = true)?.let { BitmapFactory.decodeFile(it.path) } }
            }, open = { index -> openScreenshotGallery(details.screenshots, game.name, index) }))
        }
    }

    private fun addRelatedGames(content: LinearLayout, game: LibraryEntry, details: GameDetails, trackRows: Boolean = true) {
        val games = mergeLibraryCopies(entries.filter { it.remote?.xboxAccess != XboxAccess.GAME_PASS && (collection.showHiddenGames || !gameIsHidden(it)) })
        val matches = relatedLibraryGames(game, details, games, ::mergedDetails)
        listOf("More by the same developer" to matches.developer,
            "More by the same publisher" to matches.publisher).forEach { (heading, related) ->
            ui.gap(content, 24); ui.add(content, ui.text(heading, 18, medium = true)); ui.gap(content, 10)
            if (related.isEmpty()) {
                ui.add(content, ui.text("No other matching games in your collection.", 12, ui.secondary))
            } else {
                val row = RecyclerView(this).apply {
                    layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
                    isNestedScrollingEnabled = false
                    scope.launch {
                        val cards = related.mapNotNull(assetRepository::published)
                        val readyImages = cards.associate { it.game.key to assetRepository.bitmap(it) }
                        adapter = RelatedGameAdapter(cards.map { it.game }, readyImages)
                    }
                    contentDescription = heading
                }
                if (trackRows) relatedRows.add(row)
                ui.add(content, row, 222)
            }
        }
    }

    private inner class RelatedGameHolder(val card: LinearLayout, val art: ImageView, val title: TextView,
        val backdrop: ImageView, val backdropShade: View) : RecyclerView.ViewHolder(card) {
        var game: LibraryEntry? = null
    }

    /** Fixed compact cards, independent of the collection's tile and title preferences. */
    private inner class RelatedGameAdapter(val games: List<LibraryEntry>, val readyImages: Map<String, Bitmap>) : RecyclerView.Adapter<RelatedGameHolder>() {
        override fun getItemCount() = games.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RelatedGameHolder {
            val card = ui.column().apply {
                layoutParams = RecyclerView.LayoutParams(ui.dp(112), -1).apply { marginEnd = ui.dp(12) }
                isFocusable = true
            }
            val frame = FrameLayout(this@MainActivity).apply { background = ui.shape(ui.surface, 12); clipToOutline = true }
            val backdrop = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val shade = View(this@MainActivity).apply {
                setBackgroundColor(0x88000000.toInt())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            frame.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
            frame.addView(shade, FrameLayout.LayoutParams(-1, -1))
            val art = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            frame.addView(art, FrameLayout.LayoutParams(-1, -1))
            ui.add(card, frame, 168); ui.gap(card, 6)
            val title = ui.text("", 12, medium = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            ui.add(card, title)
            return RelatedGameHolder(card, art, title, backdrop, shade)
        }
        override fun onBindViewHolder(holder: RelatedGameHolder, position: Int) {
            val game = games[position]; holder.game = game
            holder.title.text = game.name; holder.card.contentDescription = game.name
            holder.card.setOnClickListener { openGame(game, games.map { it.key }) }
            holder.art.setImageBitmap(requireNotNull(readyImages[game.key]))
            holder.art.scaleType = if (collection.fitCoverArt) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            holder.backdrop.visibility = View.GONE; holder.backdropShade.visibility = View.GONE
        }
    }

    private fun openScreenshotGallery(urls: List<String>, title: String, index: Int, hasCover: Boolean = false) {
        if (urls.isEmpty()) return
        fullscreenDialog?.dismiss()
        galleryUrls = urls; galleryTitle = title; galleryIndex = index.coerceIn(0, urls.lastIndex)
        galleryHasCover = hasCover
        fullscreenDialog = showFullscreenScreenshots(this, scope, urls, title, galleryIndex,
            changed = { galleryIndex = it }, closed = { fullscreenDialog = null }, imageLabel = if (hasCover) "Artwork" else "Screenshot") { address ->
            withContext(Dispatchers.IO) {
                val portrait = hasCover && address == urls.first()
                val file = customArtwork.fileForAddress(address)
                    ?: artwork.image(address, portrait = portrait, large = true, fullscreen = true)
                    // The carousel's saved image remains viewable without its fullscreen variant.
                    ?: artwork.image(address, portrait = portrait, large = true, cachedOnly = true)
                    ?: artwork.image(address, portrait = portrait, cachedOnly = true)
                file?.let { BitmapFactory.decodeFile(it.path) }
            }
        }
    }

    private fun showListManager(selectedName: String) {
        val (dialog, content) = modal("List manager")
        downloadDialog = dialog
        ui.add(content, ui.text("Drag lists to change their left-to-right order. All games stays first. Wishlist stays at the end, before Hidden when visible. Changes are saved immediately.", 14, ui.secondary))
        ui.gap(content)
        val rows = ListOrderView(this, ui, collection.list) { names ->
            val visible = names.toSet()
            val reordered = names.iterator()
            val order = collection.lists.map { if (it in visible) reordered.next() else it }
            try {
                collection.reorderLists(order)
                hideLibraryPageHint()
                updateLibrary(true)
                true
            } catch (error: IllegalStateException) {
                showChoiceDialog(this, ui, error.message ?: "Could not save list order.", listOf("OK")) {}
                false
            }
        }
        fun renderRows() = rows.submitNames(populatedLibraryLists(), collection.list)
        if (selectedName in collection.customLists) {
            var currentName = selectedName
            val name = ui.input("List name").apply {
                imeOptions = EditorInfo.IME_ACTION_DONE
                setText(currentName)
            }
            ui.add(content, name)
            val rename = ui.button("Rename") {
                try {
                    currentName = collection.renameList(currentName, name.text.toString())
                    name.setText(currentName)
                    hideLibraryPageHint()
                    updateLibrary(true)
                    renderRows()
                } catch (error: IllegalArgumentException) {
                    name.error = error.message
                } catch (error: IllegalStateException) {
                    name.error = error.message
                }
            }
            fun validate() {
                val error = collection.renameListError(currentName, name.text.toString())
                name.error = error
                enable(rename, error == null && name.text.toString().trim() != currentName)
            }
            name.addTextChangedListener(watcher { validate() })
            name.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
                    if (rename.isEnabled) rename.performClick()
                    true
                } else false
            }
            validate()
            ui.add(content, rename)
            ui.gap(content)
        }
        ui.add(content, rows)
        renderRows()
        dialogActions(dialog, ui.button("Done", true) { dialog.dismiss() })
        showModal(dialog)
    }

    private fun showCreateList(game: LibraryEntry, onCreated: () -> Unit) = showCreateList(listOf(game)) { onCreated() }

    private fun showCreateList(games: List<LibraryEntry>, onCreated: (String) -> Unit) {
        val (dialog, content) = modal()
        downloadDialog = dialog
        ui.add(content, ui.text("New list", 24, medium = true)); ui.gap(content, 12)
        ui.add(content, ui.text(if (games.size == 1) "Create a list and add ${games.single().name} to it." else "Create a list and add ${games.size} games to it.", 14, ui.secondary)); ui.gap(content, 16)
        val name = ui.input("List name").apply { imeOptions = EditorInfo.IME_ACTION_DONE }
        ui.add(content, name); ui.gap(content, 12)
        val create = ui.button(if (games.size == 1) "Create & add game" else "Create & add games", true) {
            try {
                val list = collection.createList(name.text.toString(), games.flatMap(::listCopies).map { it.key }.toSet())
                dialog.dismiss()
                onCreated(list)
            } catch (e: IllegalArgumentException) {
                name.error = e.message
            }
        }
        fun validate() {
            create.isEnabled = name.text.isNotBlank()
            create.alpha = if (create.isEnabled) 1f else .45f
            name.error = null
        }
        name.addTextChangedListener(watcher { validate() })
        name.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                if (create.isEnabled) create.performClick()
                true
            } else false
        }
        validate()
        dialogActions(dialog, create, ui.button("Cancel") { dialog.dismiss() })
        name.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        showModal(dialog)
    }

    private fun modal(title: String? = null): Pair<Dialog, LinearLayout> {
        val dialog = ContentDialog(this, ui, title)
        dialog.setOnDismissListener { if (downloadDialog === dialog) { downloadDialog = null; dialogUpdate = null; showNotices() } }
        return dialog to dialog.content
    }

    private fun dialogActions(dialog: Dialog, vararg actions: View) {
        (dialog as ContentDialog).setActions(*actions)
    }

    private fun showModal(dialog: Dialog) {
        dialog.show()
    }

    private fun accountConnected(store: LibraryStore): Boolean =
        store.account?.let { accounts.state.value.getValue(it).connected }
            ?: (engine.snapshot().optString("status") == "signedIn")

    private fun accountConnecting(store: LibraryStore): Boolean =
        store.account?.let { accounts.state.value.getValue(it).loading }
            ?: (engine.snapshot().optString("status") in listOf("connecting", "restoring", "loggingIn"))

    private fun reconnectAccount(store: LibraryStore, accountsPage: () -> Unit) {
        scope.launch {
            val restored = reconnectSavedAccount(
                connected = { accountConnected(store) },
                connecting = { accountConnecting(store) },
                saved = {
                    store.account?.let { accounts.state.value.getValue(it).saved }
                        ?: engine.snapshot().optBoolean("sessionAvailable")
                },
                reconnect = { store.account?.let { accounts.refresh(it, fetchLibrary = false) } ?: engine.restoreSession() },
            )
            if (!restored) accountsPage()
        }
    }

    private fun bindReconnectAction(
        dialog: ContentDialog, game: LibraryEntry,
        needsConnection: () -> Boolean = { true }, ready: (Boolean) -> Unit = {},
    ) {
        val link = ui.link("Reconnect needed", 13) {
            reconnectAccount(game.store) {
                if (dialog.isShowing) { dialog.dismiss(); selectTab("accounts") }
            }
        }
        ui.add(dialog.content, link)
        fun update() {
            val connected = accountConnected(game.store) || !needsConnection()
            val connecting = accountConnecting(game.store)
            link.visibility = if (connected) View.GONE else View.VISIBLE
            link.text = if (connecting) "Reconnecting…" else "Reconnect needed"
            enable(link, !connecting)
            ready(connected)
        }
        update()
        dialog.setOnShowListener {
            scope.launch {
                while (dialog.isShowing) { update(); delay(100) }
            }
        }
    }

    private fun showDownload(game: LibraryEntry) {
        if (!game.store.downloads) { downloadGame(game); return }
        error = ""
        val (dialog, content) = modal(); downloadDialog = dialog
        val title = ui.row()
        title.addView(ui.icon(R.drawable.ic_arrow_down_to_line, color = ui.blue), LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)))
        title.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        title.addView(ui.iconButton(R.drawable.ic_x, "Close") { dialog.dismiss() }); ui.add(content, title)
        ui.add(content, ui.text("Download ${game.name}", 25, medium = true)); ui.gap(content, 8)
        ui.add(content, ui.text(game.store.label, 13, ui.blue)); ui.gap(content, 20)
        val languages = listOf("english", "dutch", "german", "french", "spanish", "italian", "portuguese", "brazilian", "polish", "russian", "japanese", "koreana", "schinese", "tchinese")
        val languageNames = mapOf("brazilian" to "Brazilian Portuguese", "koreana" to "Korean", "schinese" to "Simplified Chinese", "tchinese" to "Traditional Chinese")
        val fields = ui.row()
        val platformField = ui.column()
        val languageField = ui.column()
        ui.add(platformField, ui.text("Platform", 12, ui.secondary)); ui.gap(platformField, 8)
        ui.add(platformField, ui.text("Windows", 14))
        ui.add(languageField, ui.text("Language", 12, ui.secondary)); ui.gap(languageField, 8)
        val language = ui.spinner(languages.map { languageNames[it] ?: it.replaceFirstChar { c -> c.uppercase() } }).apply { contentDescription = "Language" }
        if (game.store == LibraryStore.EPIC) ui.add(languageField, ui.text("All included languages", 13, ui.secondary)) else ui.add(languageField, language)
        fields.addView(platformField, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(12) })
        fields.addView(languageField, LinearLayout.LayoutParams(0, -2, 1f))
        ui.add(content, fields); ui.gap(content, 24)
        ui.add(content, ui.text("Save as", 12, ui.secondary)); ui.gap(content, 8)
        val formats = RadioGroup(this)
        listOf("ZIP archive", "Game folder").forEachIndexed { index, label ->
            formats.addView(RadioButton(this).apply {
                id = View.generateViewId(); tag = if (index == 0) "zip" else "folder"
                text = label; typeface = ui.font; textSize = 14f; minHeight = ui.dp(56)
                buttonTintList = android.content.res.ColorStateList.valueOf(ui.blue)
                setCompoundDrawablesWithIntrinsicBounds(0, 0, if (index == 0) R.drawable.ic_folder_zip else R.drawable.ic_folder_open, 0)
                compoundDrawableTintList = android.content.res.ColorStateList.valueOf(ui.blue)
                background = ui.shape(ui.background, 12, Color.rgb(218,220,224)); setPadding(ui.dp(12), 0, ui.dp(12), 0)
            }, RadioGroup.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
        }
        ui.add(content, formats)
        val destination = ui.text("", 11, ui.secondary)
        fun selectedFormat() = formats.findViewById<RadioButton>(formats.checkedRadioButtonId)?.tag?.toString() ?: "zip"
        formats.setOnCheckedChangeListener { _, _ ->
            destination.text = "Downloads / ${game.name}${if (selectedFormat() == "zip") ".zip" else " /"}"
            for (i in 0 until formats.childCount) (formats.getChildAt(i) as RadioButton).apply { background = ui.shape(if (isChecked) ui.paleBlue else ui.background, 12, if (isChecked) ui.blue else Color.rgb(218,220,224)) }
        }
        formats.check(formats.getChildAt(0).id)
        ui.add(content, destination); ui.gap(content)
        val message = ui.text("", 13, ui.error); ui.add(content, message)
        bindReconnectAction(dialog as ContentDialog, game)
        ui.gap(content)
        val download = ui.button("Download", true, R.drawable.ic_download) {
            withNotifications {
                act {
                    check(!busy && storeDownloads.state.value?.running != true) { "Pause or cancel the current download before starting another." }
                    if (game.store == LibraryStore.STEAM) engine.startDownload(game.steam!!.appId, selectedFormat(), "windows", languages[language.selectedItemPosition])
                    else storeDownloads.start(game.store.account!!, game.remote!!, "windows",
                        if (game.store == LibraryStore.EPIC) "en" else listOf("en","nl","de","fr","es","it","pt","pt-br","pl","ru","ja","ko","zh-hans","zh-hant")[language.selectedItemPosition], selectedFormat())
                    dialog.dismiss(); collection.list = "Downloads"; tab = "library"; render(engine.snapshot(), true)
                }
            }
        }
        dialogActions(dialog, download, ui.button("Cancel") { dialog.dismiss() })
        dialogUpdate = {
            val account = game.store.account?.let { accounts.state.value.getValue(it) }
            val saved = account?.saved ?: state.optBoolean("sessionAvailable")
            val isConnected = account?.connected ?: connected
            val connecting = account?.loading ?: (state.optString("status") in listOf("connecting", "restoring", "loggingIn"))
            val active = busy || storeDownloads.state.value?.running == true
            message.text = error.ifEmpty { if (active) "Pause or cancel the current download before starting another." else "" }
            message.visibility = if (message.text.isEmpty()) View.GONE else View.VISIBLE
            enable(download, !active && !connecting && (isConnected || (saved && game.store != LibraryStore.STEAM)) && permissionAction == null)
        }
        showModal(dialog); dialogUpdate?.invoke(); showNotices()
    }

    private fun confirmSignOut() {
        if (busy) return
        val (dialog, content) = modal(); downloadDialog = dialog
        ui.add(content, ui.text("Sign out of Steam?", 25, medium = true)); ui.gap(content)
        ui.add(content, ui.text("This removes Steam’s saved session, local lists, download history, and saved game files. Files already exported to Android Downloads are kept.", 13, ui.secondary)); ui.gap(content, 24)
        dialogActions(dialog, ui.button("Sign out", true) { act { engine.signOut(); dialog.dismiss(); steamSignIn = false; render(engine.snapshot()) } },
            ui.button("Cancel") { dialog.dismiss() }); showModal(dialog)
    }
    private fun openSteam() {
        val intent = packageManager.getLaunchIntentForPackage("com.valvesoftware.android.steam.community")
            ?: error("Install the Steam app on this phone, or enter a Steam Guard code")
        startActivity(intent)
    }
    private fun withNotifications(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (permissionAction != null) return
            permissionAction = action; requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 31); dialogUpdate?.invoke()
        } else action()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 31) { val action = permissionAction; permissionAction = null; action?.invoke(); dialogUpdate?.invoke() }
    }
    private fun act(action: () -> Unit) {
        networkRequested = true
        error = ""
        try { action() } catch (e: Exception) { showError(e.message ?: "Could not complete this action") }
        render(engine.snapshot())
    }
    private fun showError(message: String) {
        error = message; showNotices(); dialogUpdate?.invoke(); errorJob?.cancel()
        errorJob = scope.launch { delay(8000); error = ""; showNotices(); dialogUpdate?.invoke() }
    }
    private fun enable(view: View, enabled: Boolean) { view.isEnabled = enabled; view.alpha = if (enabled) 1f else .45f }
    private fun addSpinner(parent: LinearLayout) { parent.addView(ProgressBar(this), LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL }) }
    private fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0) }
    private fun watcher(action: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = action()
        override fun afterTextChanged(s: Editable?) = Unit
    }
}
