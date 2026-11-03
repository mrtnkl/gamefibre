package com.gamefibre

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenCaptureTest {
    @Test fun leavingProtectedScreenClearsOnlySecureFlag() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.window.protectScreenCapture(true)
            assertTrue(activity.isScreenCaptureProtected())
            assertTrue(ContextWrapper(activity).isScreenCaptureProtected())
            activity.window.protectScreenCapture(false)
            assertFalse(activity.isScreenCaptureProtected())
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        }
    }

    @Test fun dialogsFollowHostProtectionAndKeyDialogCanOptIn() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val ui = NativeUi(activity)
            fun protected(dialog: ContentDialog) =
                dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            assertFalse(protected(ContentDialog(activity, ui)))
            activity.window.protectScreenCapture(true)
            assertTrue(protected(ContentDialog(activity, ui)))
            activity.window.protectScreenCapture(false)
            assertFalse(protected(ContentDialog(activity, ui)))
            assertTrue(protected(ContentDialog(activity, ui, secureContent = true)))
            assertFalse(activity.isScreenCaptureProtected())
        }
    }
}
