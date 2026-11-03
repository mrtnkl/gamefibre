package com.gamefibre

import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.google.android.material.R as MaterialR
import com.google.android.material.snackbar.Snackbar
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SnackbarUiTest {
    @Test @Config(qualifiers = "notnight")
    fun lightModeUsesReadableInverseColorsAndWorkingActions() = checkSnackbar(dark = false)

    @Test @Config(qualifiers = "night")
    fun darkModeUsesReadableInverseColorsAndWorkingActions() = checkSnackbar(dark = true)

    private fun checkSnackbar(dark: Boolean) {
        Robolectric.buildActivity(AppCompatActivity::class.java).use { controller ->
            val activity = controller.get()
            activity.setTheme(R.style.AppTheme)
            controller.setup()
            val root = FrameLayout(activity)
            activity.setContentView(root)
            var actions = 0
            for (label in listOf("Undo", "Retry", "View")) {
                val snackbar = appSnackbar(root, "Game hidden", Snackbar.LENGTH_LONG)
                    .setAction(label) { actions++ }
                val text = snackbar.view.findViewById<TextView>(MaterialR.id.snackbar_text)
                val action = snackbar.view.findViewById<TextView>(MaterialR.id.snackbar_action)
                val pixels = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                snackbar.view.background.apply {
                    setBounds(0, 0, 100, 100)
                    draw(Canvas(pixels))
                }
                val background = pixels.getPixel(50, 50)
                pixels.recycle()
                assertEquals(dark, ColorUtils.calculateLuminance(background) > .5)
                assertTrue("Message contrast in dark=$dark", ColorUtils.calculateContrast(text.currentTextColor, background) >= 4.5)
                assertTrue("$label contrast in dark=$dark", ColorUtils.calculateContrast(action.currentTextColor, background) >= 4.5)
                action.performClick()
            }
            assertEquals(3, actions)
        }
    }
}
