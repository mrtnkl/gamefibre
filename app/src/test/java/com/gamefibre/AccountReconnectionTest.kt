package com.gamefibre

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class AccountReconnectionTest {
    @Test fun savedSessionReconnectsWithoutInteractiveSignIn() = runBlocking {
        var connected = false
        var attempts = 0
        assertTrue(reconnectSavedAccount({ connected }, { false }, { true }, {
            attempts++; connected = true
        }))
        assertEquals(1, attempts)
    }

    @Test fun existingRestorationIsJoinedWithoutStartingAgain() = runBlocking {
        var connected = false
        var connecting = true
        launch { delay(10); connected = true; connecting = false }
        assertTrue(reconnectSavedAccount(
            connected = { connected }, connecting = { connecting }, saved = { true },
            reconnect = { fail("Already restoring") },
        ))
    }

    @Test fun missingOrRejectedSessionReturnsToAccounts() = runBlocking {
        assertFalse(reconnectSavedAccount({ false }, { false }, { false }, { fail("No saved session") }))
        var attempts = 0
        assertFalse(reconnectSavedAccount({ false }, { false }, { true }, { attempts++ }))
        assertEquals(1, attempts)
    }

    @Test fun connectionFailureAndTimeoutReturnToAccounts() = runBlocking {
        assertFalse(reconnectSavedAccount({ false }, { false }, { true }, { error("Network unavailable") }))
        assertFalse(reconnectSavedAccount({ false }, { true }, { true }, { fail("Already connecting") }, timeoutMillis = 1))
    }

    @Test fun connectedAccountDoesNotReconnect() = runBlocking {
        assertTrue(reconnectSavedAccount({ true }, { false }, { true }, { fail("Already connected") }))
    }

    @Test fun leavingTheScreenCancelsWithoutOpeningAccounts() = runBlocking {
        try {
            reconnectSavedAccount({ false }, { false }, { true }, { throw CancellationException("Closed") })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
