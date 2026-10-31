package com.gamefibre.steam

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SteamGuardFlowTest {
    private class Session : SteamGuardFlow.Session<String> {
        override val approvalAvailable = false
        override val noGuardRequired = false
        override val codeKind = "device"
        override val email: String? = null
        override val pollingMillis = 1000L
        val codes = mutableListOf<String>()
        var approved = false
        override suspend fun poll(): String? = if (approved) "approved" else null
        override suspend fun sendCode(code: String) {
            codes.add(code)
            if (code == "AAAAA") throw SteamGuardFlow.CodeRejected()
            approved = true
        }
    }

    @Test fun rejectedGuardCodeCanBeRetriedAndFlowClosesAfterApproval(): Unit = runBlocking {
        val session = Session()
        lateinit var flow: SteamGuardFlow<String>
        flow = SteamGuardFlow(session) { prompt ->
            if (prompt.mode == SteamGuardFlow.Mode.CODE) flow.submit(if (prompt.error == null) "aaaaa" else "bbbbb")
        }
        assertEquals("approved", withTimeout(2000) { flow.awaitResult() })
        assertEquals(listOf("AAAAA", "BBBBB"), session.codes)
        assertThrows(IllegalStateException::class.java) { flow.submit("CCCCC") }
    }

    @Test fun cancellingSignInClosesTheGuardInputChannel(): Unit = runBlocking {
        val flow = SteamGuardFlow(Session()) {}
        val worker = launch(start=CoroutineStart.UNDISPATCHED) { flow.awaitResult() }
        worker.cancelAndJoin()
        assertThrows(IllegalStateException::class.java) { flow.submit("CCCCC") }
        assertFalse(worker.isActive)
    }
}
