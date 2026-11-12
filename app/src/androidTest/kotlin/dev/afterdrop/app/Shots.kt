package dev.afterdrop.app

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream

/** Writes a reduced screenshot into the log in pieces, so it can be read back from a CI run. */
object Shots {
    fun log(name: String) {
        val full = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val width = 540
        val small = Bitmap.createScaledBitmap(full, width, full.height * width / full.width, true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 60, out)
        val text = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        val parts = text.chunked(3000)
        for ((i, part) in parts.withIndex()) {
            Log.i("AfterdropShot", "$name $i ${parts.size} $part")
            if (i % 8 == 7) Thread.sleep(40) // keep the log daemon from dropping lines
        }
    }
}
