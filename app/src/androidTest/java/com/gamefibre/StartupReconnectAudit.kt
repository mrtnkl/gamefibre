package com.gamefibre

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Hold credential access during a real startup: reconnect and library interaction must not wait. */
internal object StartupReconnectAudit {
    fun run(instrumentation: Instrumentation): String {
        fun field(value: Any, name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
        fun <T> ui(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return result!!.getOrThrow()
        }
        val app = App.of(instrumentation.targetContext)
        val engine = app.steamEngine
        val accounts = app.storeAccounts
        check(engine.snapshot().optBoolean("sessionAvailable")) { "Requires a saved Steam session" }
        val owners = com.gamefibre.stores.GameStore.entries.associateWith(accounts::libraryOwner)
        @Suppress("UNCHECKED_CAST")
        val vaults = (field(accounts, "vaults") as Map<*, *>).values.filterNotNull() + field(engine, "vault")!!
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = thread(name = "ReconnectAudit-vault-delay") {
            fun hold(index: Int) {
                if (index == vaults.size) {
                    locked.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "Audit timed out waiting for the UI" }
                } else synchronized(vaults[index]) { hold(index + 1) }
            }
            hold(0)
        }
        var activity: Activity? = null
        try {
            check(locked.await(5, TimeUnit.SECONDS))
            val reconnectStarted = SystemClock.elapsedRealtime()
            ui { engine.restoreSession() }
            val reconnectMs = SystemClock.elapsedRealtime() - reconnectStarted
            check(reconnectMs < 1000) { "Reconnect blocked the UI for ${reconnectMs}ms" }
            check(engine.snapshot().optString("status") == "restoring") { "Reconnect was not reserved immediately" }
            val connection = field(engine, "connectionJob")
            ui { engine.restoreSession() }
            check(field(engine, "connectionJob") === connection) { "Foreground restoration restarted active authentication" }
            val screen = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            activity = screen
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var card: View? = null
            var heartbeats = 0
            var worstHeartbeatMs = 0L
            while (SystemClock.elapsedRealtime() < deadline && card == null) {
                val start = SystemClock.elapsedRealtime()
                card = ui {
                    val recycler = field(screen, "libraryRecycler") as? RecyclerView
                    recycler?.getChildAt(0)?.takeIf { it.isShown }
                }
                worstHeartbeatMs = maxOf(worstHeartbeatMs, SystemClock.elapsedRealtime() - start)
                heartbeats++
                if (card == null) SystemClock.sleep(100)
            }
            check(card != null) { "No cached library tile became available during reconnect" }
            val openStarted = SystemClock.elapsedRealtime()
            ui {
                check(com.gamefibre.stores.GameStore.entries.associateWith(accounts::libraryOwner) == owners)
                val recycler = field(screen, "libraryRecycler") as RecyclerView
                val holder = recycler.getChildViewHolder(card!!)
                val art = field(holder, "art") as ImageView
                check((art.drawable as? BitmapDrawable)?.bitmap?.let { !it.isRecycled && it.width > 3 && it.height > 5 } == true)
                check(card!!.performClick()) { "Tile did not accept a tap" }
                check(field(screen, "tab") == "game") { "Tile did not open its game page" }
                check(engine.snapshot().optString("status") == "restoring") { "Test did not keep reconnect pending" }
            }
            val openMs = SystemClock.elapsedRealtime() - openStarted
            check(openMs < 1000) { "Game page blocked for ${openMs}ms" }
            check(worstHeartbeatMs < 1000) { "Main-thread heartbeat stalled for ${worstHeartbeatMs}ms" }
            return "PASS: credential access held; reconnect ${reconnectMs}ms; game tile opened ${openMs}ms; $heartbeats UI heartbeats (worst ${worstHeartbeatMs}ms); cached artwork retained"
        } finally {
            release.countDown()
            worker.join(1000)
            activity?.let { screen -> ui { screen.finish() } }
        }
    }
}
