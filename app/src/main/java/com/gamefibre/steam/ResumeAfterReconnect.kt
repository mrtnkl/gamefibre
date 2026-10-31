package com.gamefibre.steam

/** SteamEngine serializes requests and authentication/cancellation callbacks on its monitor. */
internal class ResumeAfterReconnect(
    private val reconnect: () -> Unit,
    private val start: (DownloadRequest) -> Unit,
    private val changed: () -> Unit,
) {
    @Volatile var pending: DownloadRequest? = null
        private set

    fun request(request: DownloadRequest, status: String, hasSavedSession: Boolean) {
        if (pending != null) return
        if (status == "signedIn") { start(request); return }
        check(hasSavedSession || status in setOf("restoring", "connecting", "loggingIn", "qr", "awaitingApproval", "awaitingCode", "verifyingGuard")) {
            "Your Steam session is unavailable or expired. Sign in again to resume this download."
        }
        pending = request
        changed()
        try {
            // Join an existing restoration instead of starting another login.
            if (status == "signedOut") reconnect()
        } catch (e: Exception) { clear(); throw e }
    }

    fun authenticated() {
        val request = pending ?: return
        pending = null
        try { start(request) } catch (e: Exception) { changed(); throw e }
    }

    fun clear() {
        if (pending == null) return
        pending = null
        changed()
    }
}
