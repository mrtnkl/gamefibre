package com.gamefibre.steam

import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.authentication.AuthenticationException
import `in`.dragonbra.javasteam.steam.steamclient.AsyncJobFailedException
import kotlinx.coroutines.*
import java.io.IOException

/** A CM disconnect cancels RPC jobs, not the Guard approval. Keep the same auth session and retry. */
internal suspend fun <T> steamAuthRequest(isConnected: () -> Boolean, timeoutMillis: Long = 15_000,
    retryMillis: Long = 1_000, operation: suspend () -> T): T {
    while (true) {
        currentCoroutineContext().ensureActive()
        try {
            return withTimeout(timeoutMillis) {
                while (!isConnected()) delay(retryMillis)
                operation()
            }
        } catch (e: Exception) {
            // A cancelled RPC future can throw CancellationException while our sign-in remains active.
            // Cancellation of the caller (sign-out, timeout, a newer attempt) must still stop immediately.
            currentCoroutineContext().ensureActive()
            val transient = e is CancellationException || e is AsyncJobFailedException || e is IOException ||
                (e is AuthenticationException && e.result in setOf(EResult.NoConnection, EResult.ServiceUnavailable,
                    EResult.Timeout, EResult.Busy, EResult.RemoteCallFailed, EResult.TryAnotherCM, EResult.RateLimitExceeded))
            if (!transient) throw e
            delay(retryMillis)
        }
    }
}
