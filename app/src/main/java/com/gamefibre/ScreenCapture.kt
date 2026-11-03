package com.gamefibre

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager

internal fun Window.protectScreenCapture(protected: Boolean) {
    val flag = WindowManager.LayoutParams.FLAG_SECURE
    setFlags(if (protected) flag else 0, flag)
}

/** Dialogs over Accounts must also protect the account content beneath them. */
internal fun Context.isScreenCaptureProtected(): Boolean = when (this) {
    is Activity -> window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    is ContextWrapper -> baseContext.isScreenCaptureProtected()
    else -> false
}
