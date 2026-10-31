package com.gamefibre

import android.content.Context
import com.gamefibre.steam.DownloadsExporter

/** One active transfer/export across all stores. Recover interrupted exports once per process. */
internal object DownloadGate {
    private var owner: String? = null
    private var recovered = false
    @Synchronized fun acquire(id: String) { check(owner == null) { "Another download is running. Pause or cancel it first." }; owner = id }
    @Synchronized fun release(id: String) { if (owner == id) owner = null }
    fun <T> whileIdle(action: () -> T): T {
        val id = java.util.UUID.randomUUID().toString()
        acquire(id)
        try { return action() } finally { release(id) }
    }
    @Synchronized fun recover(context: Context) { if (!recovered) { DownloadsExporter.recover(context); recovered = true } }
}
