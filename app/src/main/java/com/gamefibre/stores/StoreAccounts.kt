package com.gamefibre.stores

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Process-owned accounts survive Activity recreation; no credentials are exposed through UI state. */
internal class StoreAccounts internal constructor(private val context: Context) {
    companion object {
        fun get(context: Context): StoreAccounts = com.gamefibre.App.of(context).storeAccounts
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vaults = GameStore.entries.associateWith { StoreTokenVault(context, it) }
    private val files = GameStore.entries.associateWith { AtomicFile(File(context.filesDir, "store-library-${it.name.lowercase()}.json")) }
    private val locks = GameStore.entries.associateWith { Any() }
    private val generations = ConcurrentHashMap(GameStore.entries.associateWith { 0 })
    private val jobs = ConcurrentHashMap<GameStore, Job>()
    private val sessions = ConcurrentHashMap<GameStore, StoreSession>()
    private val authentication = GameStore.entries.associateWith { Mutex() }
    private val mutable = MutableStateFlow(GameStore.entries.associateWith { store ->
        val saved = vaults.getValue(store).read()
        val cached = vaults.getValue(store).owner()?.let { loadLibrary(store, it) }
        StoreAccount(saved = saved != null, games = cached.orEmpty(), libraryLoaded = cached != null)
    })
    val state = mutable.asStateFlow()

    fun libraryOwner(store: GameStore): String? = vaults.getValue(store).owner()

    fun owner(store: GameStore): String = vaults.getValue(store).read()?.getString("owner") ?: throw StoreSessionExpired()

    suspend fun downloadSession(store: GameStore, owner: String, force: Boolean = false): StoreSession = authentication.getValue(store).withLock {
        val revision = synchronized(locks.getValue(store)) { generations.getValue(store) }
        val stored = vaults.getValue(store).read() ?: throw StoreSessionExpired()
        check(stored.getString("owner") == owner) { "Sign in with the account that started this download." }
        val old = sessions[store]
        val session = try {
            if (!force && old?.owner == owner && old.expiresAt > System.currentTimeMillis() + 60_000) old
            else StoreApi.refresh(store, stored.getString("refresh_token"), owner)
        } catch(e:StoreSessionExpired) {
            synchronized(locks.getValue(store)) {
                if(generations[store]==revision) {
                    vaults.getValue(store).invalidate(); sessions.remove(store)
                    mutable.update { it + (store to it.getValue(store).copy(saved=false, connected=false, loading=false, error=e.message)) }
                }
            }
            throw e
        }
        currentCoroutineContext().ensureActive()
        synchronized(locks.getValue(store)) {
            check(generations[store] == revision) { "The account changed. Resume after signing in again." }
            vaults.getValue(store).save(session.refreshToken, session.owner); sessions[store] = session
            mutable.update { it + (store to it.getValue(store).copy(saved = true, connected = true)) }
        }
        session
    }

    fun signIn(store: GameStore, pasted: String) {
        val code = store.authorizationCode(pasted)
        perform(store, authenticating = true) { StoreApi.signIn(store, code) }
    }

    fun refresh(store: GameStore, fetchLibrary: Boolean = true) = perform(store, fetchLibrary = fetchLibrary) {
        val session = sessions[store]
        if (session != null && session.expiresAt > System.currentTimeMillis() + 60_000) session
        else {
            val saved = vaults.getValue(store).read() ?: throw StoreSessionExpired()
            StoreApi.refresh(store, saved.getString("refresh_token"), saved.getString("owner"))
        }
    }

    fun signOut(store: GameStore) {
        StoreDownloads.get(context).forgetAccount(store)
        synchronized(locks.getValue(store)) {
            generations[store] = generations.getValue(store) + 1
            jobs.remove(store)?.cancel(); sessions.remove(store)
            vaults.getValue(store).clear(); files.getValue(store).delete()
            com.gamefibre.CollectionData.clearStore(context, store.name)
            mutable.update { it + (store to StoreAccount()) }
        }
    }

    private fun perform(store: GameStore, authenticating: Boolean = false, fetchLibrary: Boolean = true, session: () -> StoreSession) = synchronized(locks.getValue(store)) {
        if (state.value.getValue(store).loading) return@synchronized
        val revision = generations.getValue(store) + 1
        generations[store] = revision
        val old = state.value.getValue(store)
        mutable.update { it + (store to old.copy(loading = true, error = null)) }
        jobs[store] = scope.launch {
            try {
                var current = authentication.getValue(store).withLock {
                    val result = session()
                    ensureActive()
                    synchronized(locks.getValue(store)) {
                        ensureActive()
                        if (generations[store] != revision) return@launch
                        val previousOwner = vaults.getValue(store).owner()
                        val sameOwner = previousOwner == result.owner
                        if (!sameOwner) {
                            StoreDownloads.get(context).forgetAccount(store)
                            com.gamefibre.CollectionData.clearStore(context, store.name)
                        }
                        vaults.getValue(store).save(result.refreshToken, result.owner)
                        if (authenticating && !sameOwner) files.getValue(store).delete()
                        sessions[store] = result
                        mutable.update { it + (store to StoreAccount(saved = true, connected = true, loading = true,
                            games = if (sameOwner) old.games else emptyList(),
                            libraryLoaded = sameOwner && old.libraryLoaded)) }
                    }
                    result
                }
                // Restoring authentication must not refetch a cached account's library.
                // Account changes above clear libraryLoaded, so they still get a first import.
                if (!fetchLibrary && state.value.getValue(store).libraryLoaded) {
                    synchronized(locks.getValue(store)) {
                        if (generations[store] == revision) {
                            mutable.update { it + (store to it.getValue(store).copy(loading = false)) }
                        }
                    }
                    return@launch
                }
                val previous = state.value.getValue(store).games
                val games = try { StoreApi.library(store, current, previous, com.gamefibre.assets.AssetTransport.get(context).store) }
                catch (_: StoreSessionExpired) {
                    // One token refresh and retry covers a session revoked between API requests.
                    current = downloadSession(store, current.owner, force = true)
                    StoreApi.library(store, current, previous, com.gamefibre.assets.AssetTransport.get(context).store)
                }
                ensureActive()
                synchronized(locks.getValue(store)) {
                    if (generations[store] != revision) return@launch
                    saveLibrary(store, current.owner, games)
                    if (store == GameStore.XBOX) android.util.Log.i("XboxLibrary", "Library loaded: owned=${games.count { it.xboxAccess == XboxAccess.OWNED }} gamePass=${games.count { it.xboxAccess == XboxAccess.GAME_PASS }}")
                    mutable.update { it + (store to StoreAccount(saved = true, connected = true, games = games, libraryLoaded = true)) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                synchronized(locks.getValue(store)) {
                    if (generations[store] != revision) return@launch
                    if (e is StoreSessionExpired) {
                        vaults.getValue(store).invalidate(); sessions.remove(store)
                        mutable.update { it + (store to it.getValue(store).copy(saved=false, connected=false, loading=false, error=e.message)) }
                    } else {
                        val message = when (e) {
                            is IOException -> "Could not reach ${store.label}. Check your connection and try again."
                            is org.json.JSONException, is ClassCastException -> "${store.label} returned an unexpected response. Please try again later."
                            is IllegalStateException, is IllegalArgumentException -> e.message ?: "Could not connect to ${store.label}."
                            else -> "Could not connect to ${store.label}. Please try again."
                        }
                        mutable.update { it + (store to it.getValue(store).copy(loading = false, error = message)) }
                    }
                }
            }
        }
    }

    fun reportCleanupFailure(store: GameStore) {
        mutable.update { it + (store to it.getValue(store).copy(error = "Some saved game files could not be removed. Open Storage to clear them.")) }
    }

    private fun loadLibrary(store: GameStore, owner: String): List<StoreGame>? = runCatching {
        val saved = files.getValue(store).openRead().use { input ->
            check(input.available() <= 16 * 1024 * 1024)
            JSONObject(input.bufferedReader().readText())
        }
        check(saved.getString("owner") == owner)
        val games = saved.getJSONArray("games")
        (0 until games.length()).map { i -> games.getJSONObject(i).let { StoreGame(it.getString("id"), it.getString("name"), it.optString("namespace"), it.optString("catalogId"), it.optString("imageUrl"), com.gamefibre.GameDetails.read(it.optJSONObject("details")),
            if (store == GameStore.XBOX) XboxAccess.valueOf(it.getString("xboxAccess")) else null) } }
    }.getOrNull()

    private fun saveLibrary(store: GameStore, owner: String, games: List<StoreGame>) {
        val array = JSONArray().apply { games.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("namespace", it.namespace).put("catalogId", it.catalogId).put("imageUrl", it.imageUrl).put("details", it.details.json()).apply { if (store == GameStore.XBOX) put("xboxAccess", requireNotNull(it.xboxAccess).name) }) } }
        val file = files.getValue(store)
        val output = file.startWrite()
        try {
            output.write(JSONObject().put("owner", owner).put("games", array).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) { file.failWrite(output); throw e }
    }
}
