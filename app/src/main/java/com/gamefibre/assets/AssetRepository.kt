package com.gamefibre.assets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.gamefibre.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class PublishedCard(val game: LibraryEntry, val asset: SelectedAsset, val scope:String="local",val currentAccess:Boolean=true)
internal data class AssetLibrary(val cards: Map<String,PublishedCard> = emptyMap(), val discovered: Int=0, val pending: Int=0, val failures: Map<String,String> = emptyMap(), val revision: Long=0, val initialized:Boolean=false)

/** Application-owned publication and preparation. Views only read publications and express demand. */
internal class AssetRepository internal constructor(
    private val context:Context,
    private val store:AssetStore=AssetTransport.get(context).store,
    private val custom:CustomArtwork=CustomArtwork(context),
    private val scope:CoroutineScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
) {
    val transport=AssetTransport.get(context)
    private val changes=Channel<Unit>(Channel.CONFLATED)
    private val promotions=Channel<Set<String>>(Channel.CONFLATED)
    private val wake=Channel<Unit>(4)
    private val staging=Mutex()
    private val stagingRevision=java.util.concurrent.atomic.AtomicLong()
    private val refreshRequested=java.util.concurrent.atomic.AtomicBoolean()
    private val steamRequests=Mutex()
    @Volatile private var demandKeys:Set<String> = emptySet()
    private val state=MutableStateFlow(AssetLibrary())
    val library=state.asStateFlow()
    private var index:BeatIndex?=null
    @Volatile private var activeOwners:Map<LibraryStore,String> = emptyMap()
    @Volatile private var rememberedOwnedKeys:Set<String> = emptySet()
    private val gameSteam=ConcurrentHashMap<String,Int>()
    private val refreshKeys=ConcurrentHashMap.newKeySet<String>()
    private val completed=java.util.concurrent.atomic.AtomicInteger()
    internal val running=java.util.concurrent.atomic.AtomicInteger()
    private val bitmaps=object:android.util.LruCache<String,Bitmap>(48*1024*1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.byteCount
    }
    companion object {
        fun get(context:Context):AssetRepository=com.gamefibre.App.of(context).assetRepository
    }
    init {
        scope.launch {
            index=runCatching { BeatIndex.load(context) }.getOrNull()
            publishState()
            wakeWorkers()
        }
        scope.launch { for(keys in promotions) {store.promote(keys);wakeWorkers()} }
        scope.launch { for(signal in changes) { delay(400);publishState() } }
        repeat(4) { worker -> scope.launch {
            while(isActive) {
                if(!transport.networkAvailable()){idle(if(store.jobs()>0)30_000 else null);continue}
                val job=store.claim(interactiveOnly=worker==0)
                if(job==null) {
                    // Sleep until the next lease/backoff expiry, or until staging or demand wakes the pool.
                    val next=store.nextWorkAt()
                    idle(if(next==null || worker==0)null else (next-System.currentTimeMillis()).coerceIn(1_000,30_000))
                    continue
                }
                running.incrementAndGet()
                try { prepare(job) } catch(error:CancellationException){throw error} catch(error:Exception) {
                    store.failed(job,error.message ?: "Artwork unavailable")
                    android.util.Log.w("AssetRepository","${job.key}: ${error.message}")
                } finally { running.decrementAndGet() }
                changes.trySend(Unit)
                if(completed.incrementAndGet()%64==0)store.trimTemporaryImages()
            }
        } }
    }
    @Synchronized private fun publishState() {
        val rows=store.games()
        val inventories=rows.map { "inventory:${it.game.store.name}:${it.scope}" }.distinct().associateWith { store.provider(it)?.first }
        val observations=inventories.mapValues { (_, inventory) ->
            inventory?.optJSONArray("keys")?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }.orEmpty()
        }
        // Artwork can be prepared for wishlist and downloaded games without establishing ownership.
        rememberedOwnedKeys=inventories.values.flatMap { inventory ->
            inventory?.optJSONArray("owned")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
        }.toSet()
        val cards=rows.mapNotNull { row -> row.selected?.let {
            row.game.key to PublishedCard(row.game,it,row.scope,row.scope=="local" || row.game.key in observations["inventory:${row.game.store.name}:${row.scope}"].orEmpty())
        } }.toMap()
        val next=AssetLibrary(cards,rows.size,store.jobs(),rows.mapNotNull { it.failure?.let { message -> it.game.key to message } }.toMap(),state.value.revision+1,true)
        state.value=next
        if(next.pending>0) AssetPreparationService.schedule(context)
    }
    private suspend fun idle(timeout:Long?) { if(timeout==null)wake.receive() else withTimeoutOrNull(timeout){wake.receive()} }
    private fun wakeWorkers() { repeat(4) { wake.trySend(Unit) } }
    fun synchronize(games:List<LibraryEntry>,owners:Map<LibraryStore,String>,priority:Set<String>,refresh:Boolean=false,observed:Set<String> = games.map { it.key }.toSet(),owned:Set<String> = games.map { it.key }.toSet()):Job {
        activeOwners = owners.toMap()
        if(refresh)refreshRequested.set(true)
        val request=stagingRevision.incrementAndGet()
        return scope.launch { staging.withLock {
            if(request!=stagingRevision.get())return@withLock
            val forceRefresh=refreshRequested.getAndSet(false)
            val entries=games.flatMap { it.copies.ifEmpty { listOf(it) } }.distinctBy { it.key }
            val known=knownIndex()
            known?.updateKnownSteamGames(entries)
            val groups=mergeLibraryCopies(entries).map { it.copies.ifEmpty { listOf(it) } }
            val local=entries.filter { owners[it.store]==null }.map { it.key }.toSet()
            store.retireInactive(owners.mapKeys { it.key.name },local)
            val previous=store.games().associateBy { it.game.key }
            // One commit for the whole library instead of one write transaction per game.
            val saved=store.transaction {
                owners.forEach { (source,owner) -> store.provider("inventory:${source.name}:$owner",org.json.JSONObject().put("keys",org.json.JSONArray(observed.filter { it.startsWith("${source.name}:") })).put("owned",org.json.JSONArray(owned.filter { it.startsWith("${source.name}:") }))) }
                groups.mapNotNull { copies -> stageGroup(copies,owners,previous,priority,forceRefresh,known) }
            }
            saved.forEach { publishSaved(it,owners) }
            publishState();wakeWorkers()
        } }
    }
    /** A cover choice or reset touches only its own group; the rest of the library is never re-staged for it. */
    fun refreshArtwork(games:List<LibraryEntry>,owners:Map<LibraryStore,String>):Job {
        val entries=games.flatMap { it.copies.ifEmpty { listOf(it) } }.distinctBy { it.key }
        return scope.launch { staging.withLock {
            if(entries.isEmpty())return@withLock
            val known=knownIndex()
            val previous=entries.mapNotNull { store.row(it.key) }.associateBy { it.game.key }
            val priority=entries.map { it.key }.toSet()
            val saved=store.transaction {
                mergeLibraryCopies(entries).map { it.copies.ifEmpty { listOf(it) } }.mapNotNull { copies -> stageGroup(copies,owners,previous,priority,false,known) }
            }
            saved.forEach { publishSaved(it,owners) }
            publishState();wakeWorkers()
        } }
    }
    private fun knownIndex():BeatIndex?=index ?: runCatching { BeatIndex.load(context) }.getOrNull().also { index=it }
    private class SavedChoice(val copies:List<LibraryEntry>,val group:String,val override:String,val fingerprint:String)
    /** Stages one merged group. Returns a saved cover that still needs publication once the staging commit is done. */
    private fun stageGroup(copies:List<LibraryEntry>,owners:Map<LibraryStore,String>,previous:Map<String,StoredGame>,priority:Set<String>,forceRefresh:Boolean,known:BeatIndex?):SavedChoice? {
        val old=copies.mapNotNull { previous[it.key] }
        val selected=old.firstNotNullOfOrNull { it.selected }
        val previousGroup=selected?.group ?: old.firstOrNull()?.group
        val keys=copies.map { it.key }.toSet()
        // A title group can split when another product makes its store identities ambiguous.
        // Never leave independent groups sharing a selection and conflicting fingerprints.
        val group=previousGroup?.takeIf { store.groupCandidates(it).all { member -> member.key in keys } }
            ?: UUID.randomUUID().toString()
        if(group!=previousGroup && selected!=null)store.retainSelection(group,selected)
        val addresses=copies.mapNotNull { custom.address(it.key) }.distinct()
        val override=selected?.source?.takeIf { it in addresses } ?: addresses.firstOrNull().orEmpty()
        val steamId=copies.firstNotNullOfOrNull { known?.steamId(it,copies)?.toIntOrNull() ?: SteamTitleMatcher.knownSteamId(it,copies)?.toIntOrNull() }
        val overrideRevision=copies.firstNotNullOfOrNull { custom.file(it.key) }?.let { "${it.length()}:${it.lastModified()}" }
        val fingerprint=assetDigest((if(override.isNotBlank()) "override:$override:$overrideRevision" else "$steamId|"+copies.sortedBy { it.key }.joinToString { "${it.key}:${it.artwork}:${it.details.portrait}" }).toByteArray())
        copies.forEach { game ->
            steamId?.let { gameSteam[game.key]=it }
            store.stage(game,owners[game.store] ?: "local",group,fingerprint,if(copies.any { it.key in priority || it.key in demandKeys })0 else 2,forceRefresh)
            if(forceRefresh) refreshKeys.add(game.key)
        }
        return if(override.isNotBlank() && selected?.fingerprint!=fingerprint) SavedChoice(copies,group,override,fingerprint) else null
    }
    /** A saved choice is already local: publish it directly instead of queueing it behind network preparation. */
    private fun publishSaved(choice:SavedChoice,owners:Map<LibraryStore,String>) {
        val file=custom.fileForAddress(choice.override) ?: return
        try {
            val blob=store.write(file.readBytes())
            val asset=SelectedAsset(choice.group,blob,choice.override,choice.fingerprint)
            bitmapLocal(PublishedCard(choice.copies.first(),asset))
            if(store.publish(choice.copies.first().key,owners[choice.copies.first().store] ?: "local",choice.fingerprint,blob,choice.override)) {
                choice.copies.forEach { refreshKeys.remove(it.key) }
            }
        } catch(error:Exception) {
            if(error is CancellationException)throw error
            android.util.Log.w("AssetRepository","Saved artwork could not be published: ${error.message}")
        }
    }
    fun promote(keys:Set<String>) {
        if(demandKeys==keys)return
        demandKeys=keys.toSet()
        promotions.trySend(demandKeys)
    }
    fun resume() { wakeWorkers() }
    internal fun nextWorkDelay():Long?=store.nextWorkAt()?.let { (it-System.currentTimeMillis()).coerceAtLeast(if(transport.networkAvailable())0 else 60_000) }
    fun published(game:LibraryEntry):PublishedCard? {
        val copies=game.copies.ifEmpty { listOf(game) }
        val card=copies.firstNotNullOfOrNull { state.value.cards[it.key]?.takeIf { card -> card.scope=="local" || card.scope==activeOwners[it.store] } } ?: return null
        return PublishedCard(card.game.copy(copies=game.copies),card.asset,card.scope,copies.any { state.value.cards[it.key]?.currentAccess==true })
    }
    fun remembered(owners:Map<LibraryStore,String>):List<LibraryEntry> = state.value.cards.values.filter {
        it.scope == owners[it.game.store] && it.game.key in rememberedOwnedKeys
    }.map { it.game }
    fun steamId(game:LibraryEntry):Int?=gameSteam[game.key] ?: index?.steamId(game,game.copies)?.toIntOrNull()
    fun address(game:LibraryEntry):String?=published(game)?.asset?.source
    fun file(game:LibraryEntry):File?=published(game)?.asset?.let { store.blob(it.blob) }
    fun repair(card:PublishedCard) { scope.launch { store.repair(card.asset.blob);changes.trySend(Unit);wakeWorkers() } }
    fun peek(game:LibraryEntry):Bitmap? = published(game)?.let { bitmaps.get(it.asset.blob) }
    suspend fun bitmap(card:PublishedCard):Bitmap = withContext(Dispatchers.IO) {bitmapLocal(card)}
    internal fun bitmapLocal(card:PublishedCard):Bitmap {
        bitmaps.get(card.asset.blob)?.let { return it }
        val bytes=store.blob(card.asset.blob).readBytes()
        check(assetDigest(bytes)==card.asset.blob){"Published artwork checksum failed"}
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        check(bounds.outWidth>3 && bounds.outHeight>5){"Published artwork needs repair"}
        val options=BitmapFactory.Options().apply {
            inSampleSize=1
            while(bounds.outWidth/(inSampleSize*2)>=224 && bounds.outHeight/(inSampleSize*2)>=336)inSampleSize*=2
        }
        val raw=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: error("Published artwork needs repair")
        val ratio=minOf(1.0,224.0/raw.width,336.0/raw.height)
        val bitmap=if(ratio==1.0)raw else Bitmap.createScaledBitmap(raw,(raw.width*ratio).toInt().coerceAtLeast(1),(raw.height*ratio).toInt().coerceAtLeast(1),true).also {raw.recycle()}
        bitmap.prepareToDraw();bitmaps.put(card.asset.blob,bitmap)
        return bitmap
    }
    suspend fun bitmap(game:LibraryEntry):Bitmap?=published(game)?.let { bitmap(it) }
    private suspend fun primeSteam(job:AssetStore.Job, row:StoredGame):Unit = steamRequests.withLock {
        val id=gameSteam[job.key] ?: index?.steamId(store.candidate(job.key) ?: row.game)?.toIntOrNull() ?: return
        gameSteam[job.key]=id
        val cached=store.steam(id)
        val refresh=job.key in refreshKeys
        if(cached!=null && System.currentTimeMillis()-cached.second<(if(refresh)30_000 else if(cached.first.portrait.isBlank() && cached.first.landscape.isBlank())300_000 else 24*60*60*1000))return
        val ids=(listOf(id)+store.queued().mapNotNull { queued -> store.candidate(queued.key)?.takeIf { it.details.portrait.isBlank() && custom.address(it.key)==null }?.let { gameSteam[queued.key] } }).distinct().filter { candidate ->
            store.steam(candidate)?.let { System.currentTimeMillis()-it.second<(if(refresh)30_000 else if(it.first.portrait.isBlank() && it.first.landscape.isBlank())300_000 else 24*60*60*1000) } != true
        }.take(50)
        if(ids.isEmpty())return
        val result=try { StoreCatalog().steamArtworks(ids) } catch(error:Exception) {
            if(error is CancellationException)throw error
            // A Steam outage must not prevent a native store cover from being published.
            android.util.Log.w("AssetRepository","Steam catalog unavailable: ${error.message}")
            return
        }
        ids.forEach { candidate ->
            val value=result[candidate] ?: store.steam(candidate)?.first ?: SteamArtworkUrls("", "")
            store.steam(candidate,value)
        }
    }
    private suspend fun prepare(job:AssetStore.Job) {
        val row=store.row(job.key) ?: return
        val game=store.candidate(job.key) ?: return
        val members=store.groupCandidates(row.group)
        val override=members.mapNotNull { custom.address(it.key) }.firstOrNull()
        val refresh=job.key in refreshKeys
        var selected:File?=null
        var address=""
        suspend fun candidate(url:String,portrait:Boolean,requirePortrait:Boolean=false):Boolean {
            if(url.isBlank())return false
            val image=try {
                if(url==override)members.firstNotNullOfOrNull { custom.file(it.key) } ?: transport.image(url,portrait=true,fullscreen=true)
                else transport.image(url,portrait=portrait,revalidate=refresh,priority=job.priority,requireFresh=refresh && row.selected!=null)
            } catch(error:Exception) { if(error is CancellationException)throw error;return false }
            if(image==null)return false
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(image.path,bounds)
            if(bounds.outWidth<=0 || bounds.outHeight<=0 || (requirePortrait && bounds.outHeight<=bounds.outWidth))return false
            selected=image;address=url;return true
        }
        if(override!=null) check(candidate(override,true)){"Selected cover is unavailable"}
        else {
            val native=members.map { it.details.portrait }.filter { it.isNotBlank() }.distinct()
            for(url in native)if(candidate(url,true,true))break
            if(selected==null) {
                primeSteam(job,row)
                val id=gameSteam[job.key] ?: index?.steamId(game,members)?.toIntOrNull()
                id?.let { store.steam(it)?.first?.portrait }?.let { candidate(it,true,true) }
            }
            val oldBounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            row.selected?.let { BitmapFactory.decodeFile(store.blob(it.blob).path,oldBounds) }
            val allowWide=oldBounds.outHeight<=oldBounds.outWidth || row.selected?.source?.let(SteamGridDb::imageUrl)==true
            if(selected==null && allowWide)for(url in members.map { it.artwork }.distinct())if(candidate(url,false))break
            if(selected==null && allowWide)gameSteam[job.key]?.let { store.steam(it)?.first?.landscape }?.let { candidate(it,false) }
        }
        val image=selected ?: error("No real artwork available; retained in preparation")
        val blob=store.write(image.readBytes())
        if(store.publish(job.key,row.scope,job.revision,blob,address)) refreshKeys.remove(job.key)
    }
}
