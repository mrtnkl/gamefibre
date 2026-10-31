package com.gamefibre

import android.content.Context

/** A sign-out must still finish deleting staging if Android kills the process. No account IDs here. */
internal class AccountCleanup(context: Context) {
    private val preferences = context.getSharedPreferences("account-cleanup", Context.MODE_PRIVATE)
    fun pending(store: String) = preferences.getBoolean(store, false)
    fun mark(store: String) { check(preferences.edit().putBoolean(store, true).commit()) { "Could not schedule account cleanup." } }
    fun complete(store: String) { check(preferences.edit().remove(store).commit()) { "Could not finish account cleanup." } }
}
