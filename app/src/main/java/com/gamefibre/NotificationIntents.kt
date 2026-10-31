package com.gamefibre

import android.content.Context
import android.content.Intent

/** Services address the UI through the composition root so steam/ and stores/ do not import the Activity. */
internal object NotificationIntents {
    const val OPEN_DOWNLOADS = "downloads"
    fun openDownloads(context: Context): Intent = Intent(context, App.of(context).launcherActivity).putExtra(OPEN_DOWNLOADS, true)
}
