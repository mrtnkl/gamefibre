package com.gamefibre.stores

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import com.gamefibre.DownloadGate
import com.gamefibre.PrivateFiles
import com.gamefibre.CollectionData
import com.gamefibre.LibraryEntry
import com.gamefibre.LibraryStore
import com.gamefibre.AccountCleanup
import com.gamefibre.steam.DownloadsExporter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class StoreDownloadRequest(val store:GameStore,val game:StoreGame,val owner:String,val platform:String,val language:String,val format:String) {
    init {
        require(store.downloads) { "Downloads from this store are not supported." }
        require(game.id.isNotBlank() && game.id.length<=256 && game.name.isNotBlank())
        require(owner.matches(Regex("[A-Za-z0-9-]{1,128}")))
        require(platform in listOf("windows","linux","macos") && (store!=GameStore.EPIC || platform!="linux"))
        require(language in listOf("en","nl","de","fr","es","it","pt","pt-br","pl","ru","ja","ko","zh-hans","zh-hant"))
        require(format in listOf("zip","folder"))
    }
}
internal data class StoreTransfer(val request:StoreDownloadRequest,val phase:String,val message:String,val done:Long=0,val total:Long=0,
    val received:Long=0,val output:String="",val staging:String="",val ready:Boolean=false,val running:Boolean=false) {
    val canResume get()=phase in listOf("paused","failed") && !running
}

/** Durable options and verified staging files survive process death; signed URLs and tokens do not enter checkpoints. */
internal class StoreDownloads internal constructor(private val context:Context) {
    companion object {
        fun get(context:Context):StoreDownloads=com.gamefibre.App.of(context).storeDownloads
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val accounts=StoreAccounts.get(context)
    private val checkpoint=AtomicFile(File(context.filesDir,"store-download.json"))
    private val stagingRoot=File(context.filesDir,"store-downloads")
    private val cleanup = AccountCleanup(context)
    private val mutable=MutableStateFlow<StoreTransfer?>(null)
    val state=mutable.asStateFlow()
    private var active:String?=null
    private var job:Job?=null
    private var io:DownloadIo?=null
    private var savedAt=0L
    private var publishedAt=0L
    private var received=0L
    private val forgotten = mutableSetOf<GameStore>()
    private val cleaning = mutableSetOf<GameStore>()
    init {
        DownloadGate.recover(context)
        runCatching {
            val data=checkpoint.openRead().use { check(it.available()<=256*1024); JSONObject(it.bufferedReader().readText()) }
            val request=StoreDownloadRequest(GameStore.valueOf(data.getString("store")),StoreGame(data.getString("id"),data.getString("name"),data.optString("namespace"),data.optString("catalogId")),
                data.getString("owner"),data.getString("platform"),data.getString("language"),data.getString("format"))
            val old=data.getString("phase"); val interrupted=old in listOf("preparing","downloading","pausing","exporting")
            val recovered=StoreTransfer(request,if(interrupted)"paused"else old,
                if(interrupted)"Download interrupted. Resume to continue using saved files."else data.getString("message"),
                data.optLong("done"),data.optLong("total"),data.optLong("received"),data.optString("output"),data.optString("staging"),data.optBoolean("ready"))
            mutable.value=recovered; save(recovered)
        }
        GameStore.entries.filter { cleanup.pending(it.name) }.forEach(::forgetAccount)
    }
    @Synchronized fun start(store:GameStore,game:StoreGame,platform:String,language:String,format:String) {
        check(store !in forgotten && store !in cleaning) { "Account files are being cleared. Try again in a moment." }
        check(active==null && state.value?.phase!="paused") { "Resume or cancel the existing download first." }
        check(accounts.state.value.getValue(store).games.any { it.id==game.id }) { "Select a game from your owned library." }
        begin(StoreTransfer(StoreDownloadRequest(store,game,accounts.owner(store),platform,language,format),"preparing","Connecting and retrieving your licensed game files…"))
    }
    @Synchronized fun resume() {
        val saved=requireNotNull(state.value) { "No download is available to resume." }
        check(active==null && saved.canResume) { "Wait for the current transfer to finish stopping." }
        check(accounts.owner(saved.request.store)==saved.request.owner) { "Sign in with the account that started this download." }
        begin(saved.copy(phase="preparing",message="Reconnecting and checking saved game files…",output=""))
    }
    private fun begin(value:StoreTransfer) {
        val id=UUID.randomUUID().toString(); DownloadGate.acquire(id)
        try {
            save(value); active=id; received=value.received; mutable.value=value.copy(running=true)
            ContextCompat.startForegroundService(context,Intent(context,StoreDownloadService::class.java).putExtra("runId",id).putExtra("name",value.request.game.name))
        } catch(e:Exception) {
            active=null; DownloadGate.release(id); mutable.value=value.copy(phase="failed",message="Android could not start the download. Please try again.")
            runCatching { save(mutable.value!!) }; throw e
        }
    }
    @Synchronized fun run(id:String,finished:()->Unit) {
        if(id!=active || job!=null) { finished(); return }
        if(state.value?.phase!="preparing") { finish(id,finished); return }
        val initial=state.value!!
        val task=scope.launch(start=CoroutineStart.LAZY) {
            try {
                val request=initial.request
                val network=DownloadIo { count -> synchronized(this@StoreDownloads) {
                    received+=count
                    val current=state.value
                    if(active==id && current!=null && current.request.store !in forgotten && TransferLifecycle.acceptsWorkerUpdate(current.phase) && SystemClock.elapsedRealtime()-publishedAt>=150) emit(current.copy(received=received),false)
                } }; synchronized(this@StoreDownloads) { io=network }
                var session=accounts.downloadSession(request.store,request.owner)
                var directory=initial.staging.takeIf { it.isNotBlank() }?.let { gamePath(stagingRoot,it) }
                var root=directory?.let { File(it,"payload") }
                if(!initial.ready || root?.isDirectory!=true || directory?.resolve("verified")?.isFile!=true) {
                    val payload=try { StorePayloads.resolve(request,session,network) } catch(e:Exception) {
                        if(e !is StoreSessionExpired && e !is StoreDownloadDenied)throw e
                        session=accounts.downloadSession(request.store,request.owner,force=true)
                        StorePayloads.resolve(request,session,network)
                    }
                    ensureActive()
                    val relative="${request.store.name}/${digest("${request.owner}:${request.game.id}:${request.platform}:${request.language}".toByteArray())}/${payload.fingerprint}"
                    directory=gamePath(stagingRoot,relative).apply { mkdirs() }
                    publish(id) { it.copy(phase="downloading",message="Downloading and verifying game files…",done=0,total=0,staging=relative,ready=false) }
                    root=payload.install(directory,network) { done,total ->
                        ensureActive(); if(SystemClock.elapsedRealtime()-publishedAt>=150 || done==total) publish(id,false) { it.copy(done=done,total=total,received=received) }
                    }
                    File(directory,"verified").writeText(payload.fingerprint)
                    publish(id) { it.copy(ready=true) }
                }
                ensureActive()
                publish(id) { it.copy(phase="exporting",message=if(request.format=="zip")"Creating ZIP in Android Downloads…"else"Saving game folder to Android Downloads…",done=0,total=0) }
                val output=DownloadsExporter(context).export(requireNotNull(root),request.game.name,request.format) { done,total -> publish(id,false) { it.copy(done=done,total=total) } }
                // Export has committed its public files. Record that outcome even if a pause
                // arrived just after commit; otherwise Resume would duplicate the export.
                synchronized(this@StoreDownloads) {
                    if (active == id && request.store !in forgotten) state.value?.let {
                        CollectionData(context).remember(LibraryEntry(LibraryStore.valueOf(request.store.name), request.game.id,
                            request.game.name, remote=request.game), output)
                        emit(it.copy(phase="complete",message="Saved to Android Downloads",output=output,done=it.total,ready=false,staging=""),true)
                    }
                }
                directory?.let(PrivateFiles::deleteTree)
            } catch(_:CancellationException) { /* Pause/cancel keeps its recorded reason and staged files. */ }
            catch(e:Exception) {
                if(isActive) publish(id) { it.copy(phase="failed",message=when(e) {
                    is StoreSessionExpired -> "Your ${it.request.store.label} session expired. Sign in again, then resume."
                    is java.io.IOException -> "Download interrupted. Check your connection and free space, then resume."
                    is org.json.JSONException, is java.nio.BufferUnderflowException -> "The store returned an unsupported game manifest. Please try again later."
                    is IllegalArgumentException, is IllegalStateException -> e.message ?: "The game download failed. Resume to retry."
                    else -> "The game download failed. Resume to retry."
                }) }
            }
        }
        job=task; task.invokeOnCompletion { finish(id,finished) }; task.start()
    }
    @Synchronized private fun finish(id:String,finished:()->Unit) {
        if(active!=id)return
        active=null; job=null; io=null; DownloadGate.release(id)
        state.value?.let { current ->
            if (current.request.store in forgotten) {
                checkpoint.delete(); mutable.value = null
                cleanStoreFiles(current.request.store)
            } else {
                val phase = TransferLifecycle.stoppedPhase(current.phase)
                val next = current.copy(phase=phase, message=if(phase=="paused") "Paused. Resume to continue using saved files." else current.message, running=false)
                mutable.value=next; runCatching { save(next) }
            }
        }
        finished()
    }
    @Synchronized fun pause() {
        val current=requireNotNull(state.value)
        check(active!=null && current.phase in listOf("preparing","downloading","exporting")) { "This download cannot be paused right now." }
        try { emit(current.copy(phase="pausing",message="Pausing and saving progress…"),true) } finally { job?.cancel(); io?.let { network -> scope.launch { network.interrupt() } } }
    }
    @Synchronized fun cancel(message:String="Download cancelled. Saved game files are retained for reuse.",failed:Boolean=false) {
        val current=state.value?:return
        if(current.phase=="complete")return
        try { emit(current.copy(phase=if(failed)"failed"else"cancelled",message=message),true) } finally { job?.cancel(); io?.let { network -> scope.launch { network.interrupt() } } }
    }
    @Synchronized fun removeCompletedDownload() {
        check(active == null && state.value?.phase == "complete") { "Wait for the transfer to finish." }
        checkpoint.delete()
        mutable.value = null
    }
    @Synchronized fun removeFinishedDownload() {
        check(active == null && state.value?.phase in listOf("complete", "cancelled")) { "Wait for the transfer to finish stopping." }
        checkpoint.delete(); mutable.value = null
    }
    @Synchronized fun forgetAccount(store: GameStore) {
        cleanup.mark(store.name)
        forgotten.add(store)
        if (state.value?.request?.store == store) {
            if (active != null) {
                cancel("Signed out. Local account files are being cleared.")
                return // Wait for every worker/export to stop before deleting staging.
            }
            checkpoint.delete(); mutable.value = null
        }
        cleanStoreFiles(store)
    }

    private fun cleanStoreFiles(store: GameStore) {
        if (!cleaning.add(store)) return
        scope.launch {
            val removed = runCatching {
                PrivateFiles.deleteTree(File(stagingRoot, store.name))
                cleanup.complete(store.name)
            }.isSuccess
            try {
                if (!removed) accounts.reportCleanupFailure(store)
            } finally {
                synchronized(this@StoreDownloads) {
                    cleaning.remove(store)
                    if (removed) forgotten.remove(store)
                }
            }
        }
    }

    @Synchronized fun clearSavedFiles() {
        check(active == null && cleaning.isEmpty()) { "Wait for downloads and account cleanup to finish." }
        PrivateFiles.deleteTree(stagingRoot)
        GameStore.entries.forEach { cleanup.complete(it.name) }
        checkpoint.delete(); mutable.value = null
        forgotten.clear()
    }

    @Synchronized private fun publish(id:String,durable:Boolean=true,transform:(StoreTransfer)->StoreTransfer) {
        if(active!=id)return
        state.value?.takeIf { it.request.store !in forgotten && TransferLifecycle.acceptsWorkerUpdate(it.phase) }
            ?.let { emit(transform(it),durable) }
    }
    private fun emit(value:StoreTransfer,durable:Boolean) {
        mutable.value=value; publishedAt=SystemClock.elapsedRealtime()
        if(durable || publishedAt-savedAt>=1000) { save(value); savedAt=publishedAt }
    }
    private fun save(t:StoreTransfer) {
        val r=t.request
        val data=JSONObject().put("store",r.store.name).put("id",r.game.id).put("name",r.game.name).put("namespace",r.game.namespace).put("catalogId",r.game.catalogId)
            .put("owner",r.owner).put("platform",r.platform).put("language",r.language).put("format",r.format)
            .put("phase",t.phase).put("message",t.message).put("done",t.done).put("total",t.total).put("received",t.received)
            .put("output",t.output).put("staging",t.staging).put("ready",t.ready)
        val stream=checkpoint.startWrite()
        try { stream.write(data.toString().toByteArray(Charsets.UTF_8)); checkpoint.finishWrite(stream) }
        catch(e:Exception) { checkpoint.failWrite(stream); throw e }
    }
}
