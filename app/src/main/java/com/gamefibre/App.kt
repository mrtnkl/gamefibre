package com.gamefibre

import android.app.Application
import android.content.Context
import com.gamefibre.assets.AssetRepository
import com.gamefibre.assets.AssetStore
import com.gamefibre.assets.AssetTransport
import com.gamefibre.steam.SteamEngine
import com.gamefibre.stores.StoreAccounts
import com.gamefibre.stores.StoreDownloads

/**
 * Composition root for process-owned services. Each is created on first use so startup
 * pays only for what the current entry point (Activity, service, job) actually needs.
 */
class App : Application() {
    internal val assetTransport by lazy { AssetTransport(this, AssetStore(this)) }
    internal val assetRepository by lazy { AssetRepository(this) }
    val steamEngine by lazy { SteamEngine(this) }
    internal val storeAccounts by lazy { StoreAccounts(this) }
    internal val storeDownloads by lazy { StoreDownloads(this) }
    internal val launcherActivity: Class<out android.app.Activity> get() = MainActivity::class.java

    companion object {
        fun of(context: Context): App = context.applicationContext as App
    }
}
