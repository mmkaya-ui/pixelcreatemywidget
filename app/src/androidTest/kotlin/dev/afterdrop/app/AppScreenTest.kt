package dev.afterdrop.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the app screen the way a person would: read it, type a description, press Create. */
@RunWith(AndroidJUnit4::class)
class AppScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun onScreen(): List<String> =
        device.findObjects(By.pkg(java.util.regex.Pattern.compile(".*"))).mapNotNull { it.text }.filter { it.isNotBlank() }.distinct().take(25)

    /**
     * Typed text reaches Compose a frame later, and Create stays disabled until it does, so a click
     * straight after typing can land on a disabled button. Wait for it to be enabled, and press again
     * once if nothing answered.
     */
    private fun pressCreate(expect: String): Any? {
        repeat(2) {
            device.wait(Until.findObject(By.text("Create").enabled(true)), 5_000)?.click()
                ?: device.findObject(By.text("Create"))?.click()
            device.wait(Until.findObject(By.textStartsWith(expect)), 5_000)?.let { return it }
        }
        return null
    }

    @Test
    fun describeCreatesAWidgetAndRefusesHonestly() {
        ActivityScenario.launch(MainActivity::class.java).use {
            var title = device.wait(Until.findObject(By.text("Afterdrop")), 15_000)
            if (title == null) {
                // A freshly booted emulator sometimes covers the app with a "not responding" dialog.
                device.findObject(By.text("Wait"))?.click()
                device.executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
                title = device.wait(Until.findObject(By.text("Afterdrop")), 15_000)
            }
            assertNotNull("title missing; the screen shows: " + onScreen(), title)
            assertNotNull("describe section missing", device.findObject(By.text("Describe a widget")))
            val field = device.findObjects(By.clazz("android.widget.EditText")).firstOrNull()
            assertNotNull("description field missing", field)
            field!!.text = "countdown to 24 December"
            assertNotNull("no confirmation after Create; the screen shows: " + onScreen(), pressCreate("Created \"24 December\""))
            assertNotNull("new widget is not listed", device.wait(Until.findObject(By.text("24 December")), 5_000))

            device.findObjects(By.clazz("android.widget.EditText")).first().text = "a widget that orders pizza"
            assertNotNull("no honest refusal; the screen shows: " + onScreen(), pressCreate("The offline rules cover"))
        }
    }
}
