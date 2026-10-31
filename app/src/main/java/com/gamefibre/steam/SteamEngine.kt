package com.gamefibre.steam

import android.content.Context
import android.content.Intent
import com.gamefibre.DownloadGate
import com.gamefibre.PrivateFiles
import com.gamefibre.CollectionData
import com.gamefibre.LibraryEntry
import com.gamefibre.LibraryStore
import com.gamefibre.readGames
import com.gamefibre.AccountCleanup
import androidx.core.content.ContextCompat
import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.AppItem
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.networking.steam3.ProtocolTypes
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPlayerSteamclient.CPlayer_GetOwnedGames_Request
import `in`.dragonbra.javasteam.rpc.service.Player
import `in`.dragonbra.javasteam.steam.authentication.AuthSession
import `in`.dragonbra.javasteam.steam.authentication.AuthPollResult
import `in`.dragonbra.javasteam.steam.authentication.AuthenticationException
import `in`.dragonbra.javasteam.steam.authentication.CredentialsAuthSession
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.EAuthSessionGuardType
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.Security
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Process-owned Steam connection: independent of Activity recreation. */
class SteamEngine internal constructor(private val context: android.app.Application) {
    companion object {
        fun get(context: Context): SteamEngine = com.gamefibre.App.of(context).steamEngine
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vault = TokenVault(context)
    val libraryCache = LibraryCache(context)
    private val lock = Any()
    private val observers = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    private val state = JSONObject().put("status", "signedOut").put("games", JSONArray()).put("libraryLoading", false)
    @Volatile private var client: SteamClient? = null
    private var connectionJob: Job? = null
    private var authJob: Job? = null
    private var libraryJob: Job? = null
    private var transferJob: Job? = null
    private val transferLock = Any()
    private val checkpointStore = DownloadCheckpointStore(File(context.filesDir, "download-checkpoint.properties"))
    private var checkpoint: DownloadCheckpoint? = null
    private var activeRunId: String? = null
    private var stagingCleanup: Job? = null
    private val cleanup = AccountCleanup(context)
    private var lastCheckpointSave = 0L
    private var licenses = CompletableDeferred<List<License>>()
    @Volatile private var authSession: AuthSession? = null
    @Volatile private var guardFlow: SteamGuardFlow<AuthPollResult>? = null
    private class PasswordLogin(val username: String, val password: CharArray) { fun clear() { password.fill('\u0000') } }
    private var pendingLogin: PasswordLogin? = null
    private var reconnects = 0
    private var credentials: String? = null
    private val transferBusy = AtomicBoolean(false)
    @Volatile private var stopped = true
    private val resumeAfterReconnect = ResumeAfterReconnect(
        reconnect = { signIn() }, start = { resumeAuthenticated(it) }, changed = { publishTransfer() },
    )
    init {
        vault.ownerId()?.let { owner ->
            libraryCache.load(owner)?.let { state.put("games", it).put("libraryCached", true) }
        }
        state.put("sessionAvailable", vault.read() != null)
        DownloadGate.recover(context)
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
        runCatching {
            checkpoint = checkpointStore.load()?.recover()
            checkpoint?.let {
                checkpointStore.save(it)
                state.put("transfer", transferJson(it))
            }
        }
        if (cleanup.pending("STEAM")) forgetAccountData()
    }
    fun observe(observer: (JSONObject) -> Unit) { observers.add(observer) }
    fun removeObserver(observer: (JSONObject) -> Unit) { observers.remove(observer) }
    /** The last published copy. Consumers treat it as read-only; it is shared by every observer and snapshot reader. */
    @Volatile private var published: JSONObject = JSONObject(state.toString())
    fun snapshot(): JSONObject = published
    private fun update(block: (JSONObject) -> Unit) {
        val copy = synchronized(lock) {
            val previousGames = state.optJSONArray("games")
            block(state)
            if (previousGames !== state.optJSONArray("games")) state.put("libraryRevision", state.optLong("libraryRevision") + 1)
            JSONObject(state.toString()).also { published = it }
        }
        observers.forEach { runCatching { it(copy) } }
    }
    fun signIn(forceLibraryRefresh: Boolean = false) = startSignIn(null, forceLibraryRefresh)
    fun signInWithCredentials(username: String, password: String) {
        require(username.isNotBlank()) { "Enter your Steam account name" }
        require(password.isNotEmpty()) { "Enter your Steam password" }
        val login = PasswordLogin(username.trim(), password.toCharArray())
        try { startSignIn(login) } catch (e: Exception) { login.clear(); throw e }
    }
    fun refreshAuthentication() { guardFlow?.refresh() }
    fun submitGuardCode(code: String) { requireNotNull(guardFlow) { "There is no active Steam Guard sign-in" }.submit(code) }
    fun useGuardCode() { requireNotNull(guardFlow) { "There is no active Steam Guard sign-in" }.useCode() }
    private fun clearGuardState(json: JSONObject) {
        listOf("guardKind", "guardEmail", "guardError", "canUseGuardCode", "authMethod").forEach { json.remove(it) }
    }
    @Synchronized private fun startSignIn(login: PasswordLogin?, forceLibraryRefresh: Boolean = false) {
        check(!transferBusy.get()) { "A download is running" }
        check(snapshot().getString("status") == "signedOut") { "Sign-in is already in progress" }
        stopped = false
        credentials = if (login == null) vault.read() else null
        pendingLogin = login
        authSession = null
        guardFlow?.close(); guardFlow = null
        reconnects = 0
        licenses = CompletableDeferred()
        update {
            clearGuardState(it)
            it.remove("connectionError")
            it.put("status", if (credentials != null) "restoring" else "connecting")
                .put("sessionAvailable", credentials != null).remove("error")
            if (credentials == null) it.put("authMethod", if (login == null) "qr" else "password")
            it.remove("challengeUrl")
        }
        val config = SteamConfiguration.create { it.withProtocolTypes(EnumSet.of(ProtocolTypes.WEB_SOCKET)) }
        val local = SteamClient(config)
        client = local
        val manager = CallbackManager(local)
        val subscriptions = mutableListOf<java.io.Closeable>()
        subscriptions += manager.subscribe(ConnectedCallback::class.java) {
            if (local !== client || stopped) return@subscribe
            // A repeated connect callback must not consume the same sign-in twice.
            // This also covers the interval before Steam returns an auth session.
            if (authJob?.isActive == true) return@subscribe
            authJob = scope.launch {
                var stage = "starting"
                try {
                    withTimeout(300_000) {
                        val saved = credentials
                        val credential = if (saved != null) saved else {
                            val details = AuthSessionDetails().apply {
                                deviceFriendlyName = "GameFibre · Android"
                                clientOSType = EOSType.WinUnknown
                                persistentSession = true
                            }
                            val passwordLogin = synchronized(this@SteamEngine) {
                                pendingLogin.also { pendingLogin = null }
                            }
                            val result = if (snapshot().optString("authMethod") == "qr") {
                                val session = local.authentication.beginAuthSessionViaQR(details).await()
                                stage = "guard"
                                authSession = session
                                session.challengeUrlChanged = `in`.dragonbra.javasteam.steam.authentication.IChallengeUrlChanged { changed ->
                                    if (local === client && !stopped) update { it.put("challengeUrl", changed?.challengeUrl) }
                                }
                                update { it.put("status", "qr").put("challengeUrl", session.challengeUrl) }
                                var response: AuthPollResult? = null
                                while (response == null) {
                                    response = steamAuthRequest({ local.isConnected }) { session.pollAuthSessionStatus().await() }
                                    if (response == null) delay((session.pollingInterval * 1000).toLong().coerceAtLeast(1000))
                                }
                                response
                            } else {
                                checkNotNull(passwordLogin) { "Password sign-in was interrupted. Please try again." }
                                val session = try {
                                    details.username = passwordLogin.username
                                    details.password = String(passwordLogin.password)
                                    local.authentication.beginAuthSessionViaCredentials(details).await()
                                } finally { passwordLogin.clear(); details.password = null; details.username = null }
                                authSession = session
                                stage = "guard"
                                authenticateWithGuard(local, session)
                            }
                            result.refreshToken
                        }
                        synchronized(this@SteamEngine) {
                            ensureActive()
                            if (local !== client || stopped) return@withTimeout
                            stage = "saving"
                            if (vault.ownerId() != tokenSteamId(credential).toString()) {
                                forgetAccountData()
                            }
                            vault.save(credential)
                            credentials = credential
                            stage = "loggingOn"
                            guardFlow = null
                            update { clearGuardState(it); it.put("status", if (saved != null) "restoring" else "loggingIn").remove("challengeUrl") }
                        }
                        scope.launch {
                            delay(30_000)
                            if (local === client && snapshot().optString("status") in listOf("loggingIn", "restoring")) failConnection("Steam login timed out. Try again.")
                        }
                        withTimeout(30_000) { while (!local.isConnected) delay(250) }
                        local.send(tokenLogonMessage(credential, local.configuration.cellID))
                    }
                } catch (e: Exception) {
                    if (local === client && !stopped && (e !is CancellationException || e is TimeoutCancellationException)) failConnection(when {
                        e is TimeoutCancellationException -> "Steam sign-in expired. Please try again."
                        e is AuthenticationException && e.result == EResult.InvalidPassword -> "Steam did not accept that account name or password. Please try again."
                        e is AuthenticationException -> "Steam could not complete sign-in (${e.result}). Please try again."
                        stage == "saving" -> "Steam approved sign-in, but the session could not be saved on this device. Please try again."
                        stage == "loggingOn" -> "Steam approved sign-in. Reconnect to finish using the saved session."
                        else -> "Steam could not finish sign-in (${e.javaClass.simpleName}). Please try again."
                    })
                }
            }
        }
        subscriptions += manager.subscribe(LoggedOnCallback::class.java) { result ->
            synchronized(this@SteamEngine) {
                if (local !== client || stopped) return@subscribe
                if (result.result != EResult.OK) {
                    // Service outages and rate limits do not invalidate a saved session.
                    val expired = result.result in listOf(EResult.InvalidPassword, EResult.Expired)
                    if (expired) vault.invalidate()
                    failConnection(if (expired) "Your Steam session expired. Sign in again to continue."
                        else "Steam could not reconnect (${result.result}). Please try again.")
                } else {
                    authSession = null; reconnects = 0
                    update { it.put("status", "signedIn").put("account", currentOwner()).put("sessionAvailable", true).remove("challengeUrl") }
                    val cachedLibrary = libraryCache.load(currentOwner())
                    cachedLibrary?.let { games -> update { it.put("games", games).put("libraryCached", true) } }
                    renewSavedSession(local)
                    if (forceLibraryRefresh || cachedLibrary == null) refreshLibrary()
                    else if ((0 until cachedLibrary.length()).any { needsSteamMetadata(cachedLibrary.getJSONObject(it)) }) {
                        refreshLibrary(forceMetadata = false)
                    }
                    finishPendingResume()
                }
            }
        }
        subscriptions += manager.subscribe(LicenseListCallback::class.java) { result ->
            synchronized(this@SteamEngine) {
                if (local !== client || stopped) return@subscribe
                if (result.result == EResult.OK) licenses.complete(result.licenseList)
                else licenses.completeExceptionally(IllegalStateException("Steam could not retrieve licenses: ${result.result}"))
            }
        }
        subscriptions += manager.subscribe(LoggedOffCallback::class.java) { result ->
            if (local === client && !stopped) failConnection("Steam logged this session off: ${result.result}. Reconnect to continue.")
        }
        subscriptions += manager.subscribe(DisconnectedCallback::class.java) {
            if (local !== client || stopped) return@subscribe
            // Steam disconnects unauthenticated clients after ~60 s. Keep the Guard session alive.
            if (authSession != null && authJob?.isActive == true && reconnects++ < 5) {
                scope.launch { delay(1000); if (local === client && !stopped) local.connect() }
            } else if (credentials != null && !transferBusy.get() && vault.read() != null && reconnects++ < 3) {
                // Approval may already have produced a durable token before Android suspended the socket.
                // Reuse it for CM logon rather than discarding an approved sign-in or requesting Guard again.
                authJob?.cancel(); authSession = null; libraryJob?.cancel()
                licenses = CompletableDeferred()
                update { clearGuardState(it); it.put("status", "restoring").put("sessionAvailable", true).put("libraryLoading", false).remove("challengeUrl") }
                scope.launch { delay(1000); if (local === client && !stopped) local.connect() }
            } else failConnection("Steam’s server connection was interrupted. Your saved session and library are kept; reconnect to continue.")
        }
        connectionJob = scope.launch {
            try {
                local.connect()
                while (isActive && local === client && !stopped) manager.runWaitCallbacks(500L)
            } catch (e: Exception) { if (!stopped && local === client) failConnection("Could not connect to Steam. Check your connection.") }
            finally { subscriptions.forEach { runCatching { it.close() } } }
        }
        scope.launch {
            delay(30_000)
            if (local === client && snapshot().optString("status") in listOf("connecting", "restoring")) failConnection("Steam connection timed out. Try again.")
        }
    }
    /** Steam can rotate a persistent refresh token after a successful token logon. Renewal is optional. */
    private fun renewSavedSession(local: SteamClient) {
        val token = credentials ?: return
        val user = local.getHandler(SteamUser::class.java)?.steamID ?: return
        scope.launch {
            try {
                val renewed = withTimeout(15_000) { local.authentication.generateAccessTokenForApp(user, token, true).await() }.refreshToken
                if (renewed.isNotBlank()) synchronized(this@SteamEngine) {
                    if (local === client && !stopped && tokenSteamId(renewed) == user.convertToUInt64()) {
                        vault.save(renewed); credentials = renewed
                    }
                }
            } catch (_: Exception) { /* The current token and cached games remain usable if renewal is unavailable. */ }
        }
    }

    private suspend fun authenticateWithGuard(local: SteamClient, session: CredentialsAuthSession): AuthPollResult {
        val allowed = session.allowedConfirmations.map { it.confirmationType }
        val codeType = when {
            EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode in allowed -> EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode
            EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode in allowed -> EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode
            else -> null
        }
        val adapter = object : SteamGuardFlow.Session<AuthPollResult> {
            override val approvalAvailable = EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceConfirmation in allowed
            override val noGuardRequired = EAuthSessionGuardType.k_EAuthSessionGuardType_None in allowed
            override val codeKind = when (codeType) {
                EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode -> "device"
                EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode -> "email"
                else -> null
            }
            override val email = session.allowedConfirmations.firstOrNull { it.confirmationType == codeType }?.associatedMessage
            override val pollingMillis = (session.pollingInterval * 1000).toLong()
            override suspend fun poll() = steamAuthRequest({ local.isConnected }) { session.pollAuthSessionStatus().await() }
            override suspend fun sendCode(code: String) {
                try { steamAuthRequest({ local.isConnected }) { session.sendSteamGuardCode(code, codeType).await() } }
                catch (e: AuthenticationException) {
                    if (e.result in listOf(EResult.InvalidLoginAuthCode, EResult.TwoFactorCodeMismatch, EResult.Expired)) throw SteamGuardFlow.CodeRejected()
                    throw e
                }
            }
        }
        val flow = SteamGuardFlow(adapter) { prompt ->
            if (local === client && !stopped) update {
                it.put("status", when (prompt.mode) {
                    SteamGuardFlow.Mode.APPROVAL -> "awaitingApproval"
                    SteamGuardFlow.Mode.CODE -> "awaitingCode"
                    SteamGuardFlow.Mode.VERIFYING -> "verifyingGuard"
                }).put("guardKind", prompt.codeKind).put("guardEmail", prompt.email)
                    .put("canUseGuardCode", prompt.codeKind != null).put("guardError", prompt.error)
            }
        }
        guardFlow = flow
        try { return flow.awaitResult() } finally { if (guardFlow === flow) guardFlow = null }
    }
    @Synchronized private fun failConnection(message: String) {
        val resuming = resumeAfterReconnect.pending != null
        resumeAfterReconnect.clear()
        stopped = true
        pendingLogin?.clear(); pendingLogin = null
        guardFlow?.close(); guardFlow = null; authSession = null
        authJob?.cancel()
        libraryJob?.cancel()
        if (transferBusy.get()) cancelDownload(message, "failed")
        val old = client
        client = null
        old?.disconnect()
        credentials = null
        val saved = vault.read()
        update {
            clearGuardState(it)
            it.put("status", "signedOut").put("sessionAvailable", saved != null)
                .put("libraryLoading", false).put("connectionError", message).remove("error")
            if (resuming) it.put("error", message)
            it.remove("challengeUrl")
            it.remove("account")
        }
    }
    @Synchronized fun signOut() {
        check(!transferBusy.get()) { "Cancel the download before signing out" }
        cleanup.mark("STEAM")
        resumeAfterReconnect.clear()
        stopped = true
        pendingLogin?.clear(); pendingLogin = null
        guardFlow?.close(); guardFlow = null; authSession = null
        authJob?.cancel(); connectionJob?.cancel(); libraryJob?.cancel()
        client?.disconnect(); client = null; authSession = null; credentials = null; vault.clear()
        forgetAccountData()
        update { clearGuardState(it); it.put("status", "signedOut").put("sessionAvailable", false).put("libraryCached", false).put("games", JSONArray()).put("libraryLoading", false).remove("account"); it.remove("challengeUrl"); it.remove("error"); it.remove("connectionError") }
    }
    private fun forgetAccountData() {
        cleanup.mark("STEAM")
        libraryCache.clear()
        com.gamefibre.CollectionData.clearStore(context, "STEAM")
        synchronized(transferLock) {
            check(!transferBusy.get()) { "Wait for the download to stop before clearing account data." }
            checkpointStore.clear(); checkpoint = null
            update { it.remove("transfer"); it.put("games", JSONArray()).put("libraryCached", false) }
            if (stagingCleanup?.isActive != true) stagingCleanup = scope.launch {
                val removed = runCatching {
                    PrivateFiles.deleteTree(File(context.filesDir, "downloads"))
                    cleanup.complete("STEAM")
                }.isSuccess
                if (!removed) update { it.put("error", "Some saved game files could not be removed. Open Storage to clear them.") }
            }
        }
    }

    fun clearSavedFiles() = synchronized(transferLock) {
        check(!transferBusy.get() && stagingCleanup?.isActive != true) { "Wait for downloads and account cleanup to finish." }
        PrivateFiles.deleteTree(File(context.filesDir, "downloads"))
        cleanup.complete("STEAM")
        resumeAfterReconnect.clear()
        checkpointStore.clear(); checkpoint = null
        update { it.remove("transfer") }
    }
    @Synchronized fun refreshLibrary(forceMetadata: Boolean = true) {
        check(snapshot().optString("status") == "signedIn") { "Sign in to Steam first" }
        if (libraryJob?.isActive == true) return
        val local = requireNotNull(client)
        update { it.put("libraryLoading", true).remove("error"); it.remove("connectionError") }
        libraryJob = scope.launch {
            try {
                withTimeout(180_000) {
                    val user = local.getHandler(SteamUser::class.java)!!
                    val player = local.getHandler(SteamUnifiedMessages::class.java)!!.createService(Player::class.java)
                    val response = player.getOwnedGames(CPlayer_GetOwnedGames_Request.newBuilder()
                        .setSteamid(user.steamID!!.convertToUInt64()).setIncludeAppinfo(true)
                        .setIncludePlayedFreeGames(true).setIncludeFreeSub(true).setSkipUnvettedApps(false).build()).await()
                    check(response.result == EResult.OK) { "Steam library request failed: ${response.result}" }
                    val owned = response.body.gamesList
                    val previous = snapshot().getJSONArray("games")
                    val cached = (0 until previous.length()).associate {
                        val game = previous.getJSONObject(it)
                        game.getInt("appId") to game
                    }
                    val refreshAll = refreshAllSteamMetadata(cached.values.toList(), forceMetadata)
                    val games = owned.map { game -> JSONObject().put("appId", game.appid)
                        .put("name", game.name.ifBlank { "Steam app ${game.appid}" }).put("minutes", game.playtimeForever)
                        .put("platforms", cached[game.appid]?.optJSONArray("platforms") ?: JSONArray())
                        .put("details", cached[game.appid]?.optJSONObject("details"))
                        .put("metadataPending", refreshAll || cached[game.appid]?.let(::needsSteamMetadata) != false)
                        .also { value -> cached[game.appid]?.opt("sizeBytes")?.let { value.put("sizeBytes", it) } } }
                    publishLibrary(games, local)
                    val apps = local.getHandler(SteamApps::class.java)!!
                    // Metadata drives OS filters and size sorting. No public-profile/API-key requirement.
                    for (batch in games.filter(::needsSteamMetadata).chunked(50)) {
                        ensureActive()
                        val ids = batch.map { it.getInt("appId") }
                        val tokens = apps.picsGetAccessTokens(ids, emptyList()).await().appTokens
                        val info = apps.picsGetProductInfo(ids.map { PICSRequest(it, tokens[it] ?: 0L) }, emptyList()).await()
                        check(info.complete && !info.failed) { "Steam returned incomplete platform metadata; refresh the library to retry." }
                        val products = info.results.flatMap { it.apps.values }.associateBy { it.id }
                        check(ids.all { it in products }) { "Steam returned incomplete game metadata; refresh the library to retry." }
                        for (game in batch) {
                            val metadata = products[game.getInt("appId")]?.keyValues
                            val platforms = metadata?.get("common")?.get("oslist")?.asString().orEmpty().split(',').filter { it.isNotBlank() }
                            game.put("platforms", JSONArray(platforms))
                            game.put("sizeBytes", librarySizeBytes(metadata))
                            fun text(section: String, field: String) = metadata?.get(section)?.get(field)?.asString().orEmpty()
                            val release = text("common", "steam_release_date").toLongOrNull()?.takeIf { it > 0 }?.let {
                                java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
                            }.orEmpty()
                            game.put("details", com.gamefibre.GameDetails(
                                developer = text("extended", "developer"), publisher = text("extended", "publisher"), release = release,
                                metascore = text("common", "metacritic_score").toIntOrNull()?.takeIf { it in 1..100 }
                            ).json())
                            game.put("metadataPending", false)
                        }
                        publishLibrary(games, local)
                    }
                }
            } catch (e: Exception) {
                if (isActive) update { it.put("connectionError", e.message ?: "Could not load your Steam library") }
            } finally { if (local === client) update { it.put("libraryLoading", false) } }
        }
    }
    private fun publishLibrary(games: List<JSONObject>, local: SteamClient) {
        val value = JSONArray(games.map { JSONObject(it.toString()) })
        synchronized(this) {
            if (local !== client || stopped || snapshot().optString("status") != "signedIn") return
            runCatching { libraryCache.save(currentOwner(), value) }
            update { it.put("games", value).put("libraryCached", true) }
        }
    }
    private fun currentOwner(): String = requireNotNull(client?.getHandler(SteamUser::class.java)?.steamID) { "Sign in to Steam first" }.convertToUInt64().toString()
    fun startDownload(appId: Int, format: String, os: String, language: String) = synchronized(transferLock) {
        check(stagingCleanup?.isActive != true) { "Account files are being cleared. Try again in a moment." }
        check(resumeAfterReconnect.pending == null) { "Wait for your download to reconnect, or cancel it first" }
        check(snapshot().optString("status") == "signedIn") { "Sign in to Steam first" }
        check(checkpoint?.phase != DownloadPhase.PAUSED) { "Resume or cancel the paused download before starting another" }
        val games = snapshot().getJSONArray("games")
        val game = (0 until games.length()).map { games.getJSONObject(it) }.find { it.getInt("appId") == appId }
            ?: error("Select an owned game from your library")
        val request = DownloadRequest(appId, game.getString("name"), format, os, language, currentOwner())
        launchTransfer(DownloadCheckpoint(request, DownloadPhase.PREPARING, "Retrieving licensed depots from Steam…"))
    }
    @Synchronized fun resumeDownload() {
        val saved = synchronized(transferLock) {
            val current = requireNotNull(checkpoint) { "There is no saved download to resume" }
            check(current.phase.resumable) { "This download cannot be resumed" }
            check(!transferBusy.get()) { "Wait for the current transfer to finish stopping" }
            current
        }
        val status = snapshot().optString("status")
        val token = if (status != "signedIn") vault.read() else null
        // Check the saved token's account now, and the authenticated account again before launch.
        token?.let { saved.request.requireOwner(tokenSteamId(it).toString()) }
        resumeAfterReconnect.request(saved.request, status, token != null)
    }
    @Synchronized private fun finishPendingResume() {
        if (snapshot().optString("status") != "signedIn") return
        try { resumeAfterReconnect.authenticated() }
        catch (e: Exception) { update { it.put("error", e.message ?: "Could not resume your download. Try again.") } }
    }
    private fun resumeAuthenticated(request: DownloadRequest) = synchronized(transferLock) {
        check(snapshot().optString("status") == "signedIn") { "Steam is not connected yet" }
        val saved = requireNotNull(checkpoint) { "There is no saved download to resume" }
        check(saved.request == request) { "The saved download changed. Select Resume again." }
        check(saved.phase.resumable) { "This download cannot be resumed" }
        saved.request.requireOwner(currentOwner())
        launchTransfer(saved.copy(phase = DownloadPhase.PREPARING, depotPercent = null, exportBytes = null, exportTotalBytes = null,
            message = if (saved.readyToExport) "Resuming export of downloaded files…" else "Checking saved files and resuming download…"))
    }
    private fun launchTransfer(value: DownloadCheckpoint) {
        check(stagingCleanup?.isActive != true) { "Account files are being cleared. Try again in a moment." }
        val runId = java.util.UUID.randomUUID().toString()
        DownloadGate.acquire(runId)
        if (!transferBusy.compareAndSet(false, true)) { DownloadGate.release(runId); error("Wait for the current transfer to finish stopping") }
        try {
            checkpointStore.save(value) // Persist all options before starting any work.
            checkpoint = value
            activeRunId = runId
            transferJob = null
            publishTransfer()
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)
                .putExtra("runId", runId).putExtra("name", value.request.name))
        } catch (e: Exception) {
            DownloadGate.release(runId)
            transferBusy.set(false)
            activeRunId = null
            checkpoint?.let { saveTransfer(it.copy(phase = DownloadPhase.FAILED, message = "Android could not start the download service"), true) }
            throw e
        }
    }
    /** The service must present the current run ID; stale intents cannot restart old work. */
    fun runDownload(runId: String, finished: () -> Unit) = synchronized(transferLock) {
        if (activeRunId != runId || transferJob != null) { finished(); return@synchronized }
        val saved = checkpoint
        if (saved == null || saved.phase != DownloadPhase.PREPARING) {
            finishTransfer(runId, finished)
            return@synchronized
        }
        val request = saved.request
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val stage = request.stagingDirectory(context.filesDir)
            try {
                stage.mkdirs()
                val local = requireNotNull(client) { "Steam connection is unavailable" }
                request.requireOwner(currentOwner())
                if (!saved.readyToExport) {
                    val licenseList = withTimeout(30_000) { licenses.await() }
                    val complete = CompletableDeferred<Unit>()
                    val active = DepotDownloader(local, licenseList, maxDownloads = 4, maxDecompress = 2, maxFileWrites = 1, androidEmulation = true, parentJob = coroutineContext[Job])
                    active.addListener(object : IDownloadListener {
                        override fun onDownloadStarted(item: DownloadItem) { transferUpdate(DownloadPhase.DOWNLOADING, "Downloading and verifying game files…") }
                        override fun onStatusUpdate(message: String) { transferUpdate(DownloadPhase.DOWNLOADING, "Downloading and verifying game files…") }
                        override fun onChunkCompleted(depotId: Int, depotPercentComplete: Float, compressedBytes: Long, uncompressedBytes: Long) {
                            transferUpdate(DownloadPhase.DOWNLOADING, "Downloading and verifying game files…", depotPercentComplete, compressedBytes)
                        }
                        override fun onFileCompleted(depotId: Int, fileName: String, depotPercentComplete: Float) {
                            transferUpdate(DownloadPhase.DOWNLOADING, "Downloading and verifying game files…", depotPercentComplete)
                        }
                        override fun onDownloadCompleted(item: DownloadItem) { complete.complete(Unit) }
                        override fun onDownloadFailed(item: DownloadItem, error: Throwable) { complete.completeExceptionally(error) }
                    })
                    try {
                        // The same installation directory and .DepotDownloader manifests are retained.
                        // JavaSteam validates existing chunks and fetches only missing/invalid data.
                        active.add(AppItem(request.appId, installDirectory = stage.absolutePath, branch = "public", os = request.os, language = request.language))
                        complete.await()
                    } finally { active.close() }
                }
                ensureActive()
                synchronized(transferLock) {
                    ensureActive()
                    val current = requireNotNull(checkpoint)
                    check(current.phase != DownloadPhase.PAUSING)
                    saveTransfer(current.copy(phase = DownloadPhase.EXPORTING, readyToExport = true,
                        message = if (request.format == "zip") "Creating ZIP in Android Downloads…" else "Exporting game folder to Android Downloads…"), true)
                }
                val output = DownloadsExporter(context).export(stage, request.name, request.format) { copied, total ->
                    synchronized(transferLock) {
                        checkpoint?.takeIf { it.phase == DownloadPhase.EXPORTING }?.let {
                            saveTransfer(it.copy(exportBytes = copied, exportTotalBytes = total,
                                message = if (copied == total) "Finalizing files in Android Downloads…" else it.message), false)
                        }
                    }
                }
                withContext(NonCancellable) {
                    synchronized(transferLock) {
                        val game = readGames(snapshot().getJSONArray("games")).find { it.appId == request.appId }
                        CollectionData(context).remember(LibraryEntry(LibraryStore.STEAM, request.appId.toString(), request.name, steam=game), output)
                        checkpoint?.let { saveTransfer(it.copy(phase = DownloadPhase.COMPLETE, message = "Saved to Android Downloads", output = output), true) }
                    }
                    PrivateFiles.deleteTree(stage)
                }
            } catch (_: CancellationException) {
                // Pause/cancel records the reason. Completion below waits for all CDN workers.
            } catch (e: Exception) {
                synchronized(transferLock) {
                    val current = checkpoint
                    if (current != null && current.phase !in setOf(DownloadPhase.PAUSING, DownloadPhase.CANCELLED, DownloadPhase.COMPLETE)) {
                        saveTransfer(current.copy(phase = DownloadPhase.FAILED, message = "Download failed. Check your connection and free storage, then resume to retry."), true)
                    }
                }
            }
        }
        transferJob = job
        // This also runs when cancelled before the coroutine starts, and after its child workers exit.
        job.invokeOnCompletion { finishTransfer(runId, finished) }
        job.start()
    }
    private fun finishTransfer(runId: String, finished: () -> Unit) = synchronized(transferLock) {
        if (activeRunId != runId) return@synchronized
        DownloadGate.release(runId)
        finished()
        transferJob = null
        activeRunId = null
        transferBusy.set(false)
        checkpoint?.let {
            if (it.phase == DownloadPhase.PAUSING) saveTransfer(it.copy(phase = DownloadPhase.PAUSED,
                message = "Paused. Resume to continue using the files already downloaded."), true)
            else publishTransfer()
        }
    }
    fun pauseDownload() = synchronized(transferLock) {
        val current = requireNotNull(checkpoint) { "There is no download to pause" }
        check(transferBusy.get() && current.phase in setOf(DownloadPhase.PREPARING, DownloadPhase.DOWNLOADING)) { "This transfer cannot be paused right now" }
        saveTransfer(current.copy(phase = DownloadPhase.PAUSING, message = "Pausing and finishing pending file writes…"), true)
        transferJob?.cancel()
        // If Android has not delivered the service intent yet, runDownload will settle the pause.
    }
    @Synchronized fun cancelDownload(message: String = "Download cancelled. Saved game files are retained on this device.", phase: String = "cancelled") {
        resumeAfterReconnect.clear()
        synchronized(transferLock) {
            val current = checkpoint ?: return@synchronized
            if (current.phase == DownloadPhase.COMPLETE) return@synchronized
            val next = if (phase == "failed") DownloadPhase.FAILED else DownloadPhase.CANCELLED
            saveTransfer(current.copy(phase = next, message = message), true)
            transferJob?.cancel()
        }
    }
    fun removeCompletedDownload() = synchronized(transferLock) {
        check(checkpoint?.phase == DownloadPhase.COMPLETE) { "Only completed downloads can be removed" }
        checkpointStore.clear()
        checkpoint = null
        update { it.remove("transfer") }
    }
    fun removeFinishedDownload() = synchronized(transferLock) {
        check(!transferBusy.get() && checkpoint?.phase in setOf(DownloadPhase.COMPLETE, DownloadPhase.CANCELLED)) { "Wait for the transfer to finish stopping" }
        checkpointStore.clear(); checkpoint = null; update { it.remove("transfer") }
    }
    private fun transferUpdate(phase: DownloadPhase, message: String, percent: Float? = null, bytes: Long? = null) = synchronized(transferLock) {
        val current = checkpoint ?: return@synchronized
        if (current.phase !in setOf(DownloadPhase.PREPARING, DownloadPhase.DOWNLOADING)) return@synchronized
        saveTransfer(current.withProgress(phase, message, percent, bytes), false)
    }
    private fun transferJson(value: DownloadCheckpoint): JSONObject = JSONObject()
        .put("appId", value.request.appId).put("name", value.request.name)
        .put("format", value.request.format).put("os", value.request.os).put("language", value.request.language)
        .put("phase", value.phase.name.lowercase(java.util.Locale.ROOT))
        .put("message", if (resumeAfterReconnect.pending == value.request) "Reconnecting to Steam to resume your download…" else value.message)
        .put("depotPercent", value.depotPercent?.toDouble()).put("bytes", value.bytes).put("output", value.output)
        .put("exportBytes", value.exportBytes).put("exportTotalBytes", value.exportTotalBytes)
        .put("resuming", resumeAfterReconnect.pending == value.request)
        .put("canResume", value.phase.resumable && !transferBusy.get() && resumeAfterReconnect.pending == null)
        .put("canPause", value.phase in setOf(DownloadPhase.PREPARING, DownloadPhase.DOWNLOADING) && transferBusy.get())
    private fun publishTransfer() { checkpoint?.let { value -> update { it.put("transfer", transferJson(value)) } } }
    private fun saveTransfer(value: DownloadCheckpoint, durable: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        checkpoint = value
        if (durable || now - lastCheckpointSave >= 1000) {
            try {
                checkpointStore.save(value)
                lastCheckpointSave = now
            } catch (_: Exception) {
                update { it.put("error", "Could not save the latest download progress. Check free device storage before closing the app.") }
            }
        }
        publishTransfer()
    }
}
