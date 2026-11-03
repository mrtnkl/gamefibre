package com.gamefibre

import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.snackbar.Snackbar

/** Use Material's paired inverse surface/text/action colors in both day and night mode. */
internal fun appSnackbar(anchor: View, message: CharSequence, duration: Int): Snackbar =
    Snackbar.make(ContextThemeWrapper(anchor.context, R.style.AppSnackbarTheme), anchor, message, duration)
