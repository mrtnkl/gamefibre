package com.gamefibre.steam

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Guard interaction is separate from the WebView, so switching to Steam keeps polling. */
internal class SteamGuardFlow<T>(private val session: Session<T>, private val prompt: (Prompt) -> Unit) {
    enum class Mode { APPROVAL, CODE, VERIFYING }
    data class Prompt(val mode: Mode, val codeKind: String?, val email: String?, val error: String? = null)
    class CodeRejected : Exception()
    interface Session<T> {
        val approvalAvailable: Boolean
        val noGuardRequired: Boolean
        val codeKind: String?
        val email: String?
        val pollingMillis: Long
        suspend fun poll(): T?
        suspend fun sendCode(code: String)
    }
    private sealed interface Action {
        data class Code(val value: String) : Action
        data object Refresh : Action
    }
    // Resume hints must never occupy the only slot and reject a submitted code.
    // Code submissions are already limited to one by the VERIFYING mode.
    private val actions = Channel<Action>(Channel.UNLIMITED)
    @Volatile private var mode: Mode? = null
    @Synchronized private fun show(next: Mode, error: String? = null) {
        mode = next
        prompt(Prompt(next, session.codeKind, session.email, error))
    }
    @Synchronized fun useCode() {
        check(mode == Mode.APPROVAL && session.codeKind != null) { "Code entry is not available for this sign-in" }
        show(Mode.CODE)
    }
    @Synchronized fun submit(code: String) {
        check(mode == Mode.CODE) { "Steam is not waiting for a code" }
        val normalized = code.trim().uppercase(java.util.Locale.ROOT)
        require(Regex("[A-Z0-9]{5}").matches(normalized)) { "Enter the 5-character Steam Guard code" }
        show(Mode.VERIFYING)
        check(actions.trySend(Action.Code(normalized)).isSuccess) { "A code is already being checked" }
    }
    fun refresh() { if (mode != null) actions.trySend(Action.Refresh) }
    suspend fun awaitResult(): T {
        check(session.approvalAvailable || session.noGuardRequired || session.codeKind != null) { "This account requires an unsupported Steam Guard confirmation method" }
        show(when {
            session.approvalAvailable -> Mode.APPROVAL
            session.noGuardRequired -> Mode.VERIFYING
            else -> Mode.CODE
        })
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                session.poll()?.let { return it }
                val action = withTimeoutOrNull(session.pollingMillis.coerceIn(1000, 5000)) { actions.receive() }
                if (action is Action.Code) {
                    try { session.sendCode(action.value) }
                    catch (_: CodeRejected) { show(Mode.CODE, "That code was not accepted. Enter the latest Steam Guard code and try again.") }
                }
            }
        } finally { close() }
    }
    @Synchronized fun close() { mode = null; actions.cancel() }
}
