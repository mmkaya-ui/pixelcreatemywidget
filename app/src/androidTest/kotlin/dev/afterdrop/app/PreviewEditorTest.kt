package dev.afterdrop.app

import android.content.Intent
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.afterdrop.composer.SpecEdit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/** The preview inside the app and the visual editor, driven the way a person would use them. */
@RunWith(AndroidJUnit4::class)
class PreviewEditorTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)

    private fun asset(name: String) = context.assets.open("$name.json").bufferedReader().use { it.readText() }

    private fun onScreen(): List<String> = runCatching {
        device.findObjects(By.pkg(Pattern.compile(".*"))).mapNotNull { runCatching { it.text }.getOrNull() }.filter { it.isNotBlank() }.distinct().take(30)
    }.getOrDefault(emptyList())

    private fun texts(v: View): List<String> {
        val out = ArrayList<String>()
        fun walk(x: View) {
            if (x is TextView && !x.text.isNullOrBlank()) out += x.text.toString()
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        return out
    }

    /** Every gallery widget can be drawn by the preview, and draws what the home screen would. */
    @Test
    fun everyGalleryWidgetHasAPreview() {
        val names = context.assets.list("")!!.filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (name in names) {
                val json = asset(name)
                val s = (dev.afterdrop.spec.SpecReader.read(json) as dev.afterdrop.spec.SpecResult.Ok).spec
                val rv = runBlocking { Preview.render(context, json, Preview.sizeOf(s.cols, s.rows)) }
                assertNotNull("$name could not be drawn", rv)
                var shown = emptyList<String>()
                scenario.onActivity { a ->
                    val frame = FrameLayout(a)
                    val view = rv!!.apply(a, frame)
                    shown = texts(view)
                }
                Log.i(TAG, "preview $name: $shown")
                assertTrue("$name drew no text in its preview", shown.isNotEmpty() || name in setOf("steps", "clock-date", "tea-timer"))
            }
        }
        // Dark and light drawing both work.
        assertNotNull(runBlocking { Preview.render(Preview.themed(context, true), asset("battery"), DpSize(184.dp, 88.dp)) })
    }

    @Test
    fun cardsShowPreviewsAndTheLargerPreviewOpens() {
        ActivityScenario.launch(MainActivity::class.java).use {
            assertNotNull("app did not open", device.wait(Until.findObject(By.text("Afterdrop")), 15_000))
            val list = device.findObject(By.scrollable(true).pkg(context.packageName))
            val preview = device.wait(Until.findObject(By.desc("Widget preview")), 5_000)
                ?: list?.scrollUntil(Direction.DOWN, Until.findObject(By.desc("Widget preview")))
            assertNotNull("no preview on the cards; the screen shows: " + onScreen(), preview)
            Thread.sleep(800)
            Shots.log("app-previews")
            val open = device.findObject(By.text("Preview")) ?: list?.scrollUntil(Direction.DOWN, Until.findObject(By.text("Preview")))
            assertNotNull("no Preview button", open)
            open!!.click()
            assertNotNull("the larger preview did not open", device.wait(Until.findObject(By.textStartsWith("Drawn with your real data")), 5_000))
            device.findObject(By.text("Dark"))?.click()
            Thread.sleep(1200)
            Shots.log("preview-dialog-dark")
            device.findObject(By.text("Close"))?.click()
        }
    }

    /** Pick a part, make its text huge, rename the widget, save: the saved widget has both changes. */
    @Test
    fun theEditorChangesAPartAndSaves() {
        val store = Store(context)
        store.deleteUserSpec("My battery")
        context.startActivity(EditorActivity.intent(context, asset("battery")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertNotNull("the editor did not open; the screen shows: " + onScreen(), device.wait(Until.findObject(By.text("Edit design")), 10_000))
        val part = device.wait(Until.findObject(By.text("{num(bat.level, 0)}%")), 5_000)
        assertNotNull("the parts list does not show the battery text; it shows: " + onScreen(), part)
        part!!.click()
        val list = device.findObjects(By.scrollable(true)).lastOrNull()
        val huge = device.wait(Until.findObject(By.text("Huge")), 3_000) ?: list?.scrollUntil(Direction.DOWN, Until.findObject(By.text("Huge")))
        assertNotNull("the size choices did not appear; the screen shows: " + onScreen(), huge)
        huge!!.click()
        Thread.sleep(1200)
        Shots.log("editor")
        device.findObject(By.text("Look"))!!.click()
        val name = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        assertNotNull("no name field", name)
        name!!.text = "My battery"
        Thread.sleep(500)
        device.findObject(By.text("Save"))!!.click()
        val end = System.currentTimeMillis() + 5_000
        while (store.userSpecs()["My battery"] == null && System.currentTimeMillis() < end) Thread.sleep(200)
        val saved = store.userSpecs()["My battery"]
        assertNotNull("the edited widget was not saved; saved names: ${store.userSpecs().keys}", saved)
        val doc = SpecEdit.parse(saved!!)
        Log.i(TAG, "edited battery: ${SpecEdit.node(doc, listOf("1", "0"))}")
        assertTrue("the text is not huge", SpecEdit.node(doc, listOf("1", "0"))!!["style"] == "display")
    }

    @Test
    fun theEditorRefusesABrokenFormulaButKeepsTheTyping() {
        context.startActivity(EditorActivity.intent(context, asset("battery")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertNotNull(device.wait(Until.findObject(By.text("Edit design")), 10_000))
        // "Plug in soon" is only in the parts list; the preview above does not show it on a charged emulator.
        device.wait(Until.findObject(By.text("Plug in soon")), 5_000)!!.click()
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
        assertNotNull("no text field for the part", field)
        // The battery source has no such field, so this is refused; an unclosed brace would just be text.
        field!!.text = "{bat.nothing}"
        assertNotNull(
            "no warning for a broken formula; the screen shows: " + onScreen(),
            device.wait(Until.findObject(By.text(Pattern.compile("(?s).*(Not applied yet|Not saved yet).*"))), 5_000),
        )
        assertNotNull("the typing was lost; the screen shows: " + onScreen(), device.wait(Until.findObject(By.textContains("bat.nothing")), 3_000))
        // Saving now keeps the last good version: the broken text never reaches the widget.
        val store = Store(context)
        store.deleteUserSpec("Refusal check")
        device.findObject(By.text("Look"))!!.click()
        device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)!!.text = "Refusal check"
        Thread.sleep(500)
        device.findObject(By.text("Save"))!!.click()
        val end = System.currentTimeMillis() + 5_000
        while (store.userSpecs()["Refusal check"] == null && System.currentTimeMillis() < end) Thread.sleep(200)
        val saved = store.userSpecs()["Refusal check"]
        assertNotNull("the widget was not saved", saved)
        assertTrue("the broken text got into the widget", "bat.nothing" !in saved!! && "Plug in soon" in saved)
    }

    private companion object {
        const val TAG = "AfterdropTest"
    }
}
