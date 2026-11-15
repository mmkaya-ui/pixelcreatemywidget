package dev.afterdrop.app

import android.content.ComponentName
import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Writes a note in the note dialog, and shares text into it from outside, the way a person would. */
@RunWith(AndroidJUnit4::class)
class NoteTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val device = UiDevice.getInstance(inst)

    private fun onScreen(): List<String> =
        runCatching { device.findObjects(By.pkg(java.util.regex.Pattern.compile(".*"))).mapNotNull { runCatching { it.text }.getOrNull() }.filter { it.isNotBlank() }.distinct().take(25) }.getOrDefault(emptyList())

    /** The dialog redraws while the keyboard opens, so the button is looked up afresh until a press lands. */
    private fun save() {
        assertNotNull("no Save button; the screen shows: " + onScreen(), device.wait(Until.findObject(By.text("Save")), 10_000))
        repeat(5) {
            runCatching { device.findObject(By.text("Save"))?.click() }
            if (device.wait(Until.gone(By.text("Save")), 2_000)) return
        }
    }

    @Test
    fun writingANoteReachesTheWidget() {
        val store = Store(context)
        store.saveNote("pinned", "")
        context.startActivity(NoteActivity.intent(context, "pinned"))
        val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
        assertNotNull("the note dialog did not open; the screen shows: " + onScreen(), field)
        field!!.text = "Call the dentist"
        device.waitForIdle()
        Thread.sleep(500)
        save()
        val end = System.currentTimeMillis() + 5_000
        while (store.note("pinned")?.first != "Call the dentist" && System.currentTimeMillis() < end) Thread.sleep(200)
        assertEquals("Call the dentist", store.note("pinned")?.first)
        val s = (dev.afterdrop.spec.SpecReader.read(context.assets.open("sticky-note.json").bufferedReader().use { it.readText() }) as dev.afterdrop.spec.SpecResult.Ok).spec
        val data = Sources.collect(context, s)
        Log.i(TAG, "note source gives $data")
        assertTrue("the note source does not give the text: $data", data["nt"].toString().contains("Call the dentist"))
    }

    @Test
    fun sharingTextFromAnotherAppAddsToTheNote() {
        val store = Store(context)
        store.saveNote("pinned", "Milk")
        // Afterdrop must be offered in the system share sheet for plain text.
        val targets = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("text/plain"), 0)
        assertTrue("Afterdrop is not a share target", targets.any { it.activityInfo.packageName == context.packageName && it.activityInfo.name.endsWith("NoteActivity") })
        context.startActivity(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Bread and eggs")
                .setComponent(ComponentName(context, NoteActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        assertNotNull("the share dialog did not open; the screen shows: " + onScreen(), device.wait(Until.findObject(By.text("Add to note")), 10_000))
        assertNotNull("the shared text is not shown for editing", device.wait(Until.findObject(By.textContains("Bread and eggs")), 5_000))
        save()
        val end = System.currentTimeMillis() + 5_000
        while (store.note("pinned")?.first != "Milk\nBread and eggs" && System.currentTimeMillis() < end) Thread.sleep(200)
        assertEquals("Milk\nBread and eggs", store.note("pinned")?.first)
    }

    @Test
    fun anEmptyNoteIsRemovedNotStoredBlank() {
        val store = Store(context)
        store.saveNote("pinned", "something")
        store.saveNote("pinned", "   ")
        assertEquals(null, store.note("pinned"))
    }

    private companion object {
        const val TAG = "AfterdropTest"
    }
}
