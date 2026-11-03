package com.gamefibre

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Tries a saved session once, or joins authentication that is already running. */
internal suspend fun reconnectSavedAccount(
    connected: () -> Boolean,
    connecting: () -> Boolean,
    saved: () -> Boolean,
    reconnect: () -> Unit,
    timeoutMillis: Long = 35_000,
): Boolean = try {
    if (connected()) true
    else if (!saved() && !connecting()) false
    else {
        if (!connecting()) reconnect()
        withTimeout(timeoutMillis) { while (connecting()) delay(100) }
        connected()
    }
} catch (error: Exception) {
    if (error is CancellationException && error !is TimeoutCancellationException) throw error
    false
}
