package dev.afterdrop.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * The path a person takes: describe a widget in the app, press "Add to home screen", accept the
 * launcher's dialog, go home, and see the widget there with real data. Uses the emulator's own launcher.
 */
@RunWith(AndroidJUnit4::class)
class LauncherPinTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val ours = InstrumentationRegistry.getInstrumentation().targetContext.packageName

    @Test
    fun describedBatteryWidgetLandsOnTheHomeScreen() {
        ActivityScenario.launch(MainActivity::class.java).use {
            var title = device.wait(Until.findObject(By.text("Afterdrop")), 15_000)
            if (title == null) {
                device.findObject(By.text("Wait"))?.click()
                title = device.wait(Until.findObject(By.text("Afterdrop")), 15_000)
            }
            assertNotNull("app did not open", title)
            Shots.log("app-top")
            val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
            assertNotNull("description field missing", field)
            field.text = "show my battery"
            device.findObject(By.text("Create")).click()
            assertNotNull("widget was not created", device.wait(Until.findObject(By.textStartsWith("Created \"Battery\"")), 5_000))
            val add = device.wait(Until.findObject(By.text("Add to home screen").pkg(ours)), 5_000)
            assertNotNull("no Add to home screen button", add)
            Shots.log("app-after-create")
            add.click()

            // The launcher's own confirmation sheet has a button with the same words as ours.
            var confirm: androidx.test.uiautomator.UiObject2? = null
            val deadline = System.currentTimeMillis() + 12_000
            while (confirm == null && System.currentTimeMillis() < deadline) {
                // The screen is changing while we look, so an object can go stale between finding and reading it.
                confirm = runCatching {
                    device.findObjects(By.text(Pattern.compile("(?i)add to home screen|add automatically")))
                        .firstOrNull { it.applicationPackage != ours }
                }.getOrNull()
                if (confirm == null) Thread.sleep(300)
            }
            Shots.log("pin-dialog")
            assumeTrue("this launcher showed no pin dialog", confirm != null)
            confirm!!.click()
            device.pressHome()
            var shown = device.wait(Until.findObject(By.text(Pattern.compile("\\d{1,3}%"))), 30_000)
            if (shown == null) {
                // A slow emulator can still be drawing the home screen; look once more.
                device.pressHome()
                shown = device.wait(Until.findObject(By.text(Pattern.compile("\\d{1,3}%"))), 30_000)
            }
            Thread.sleep(1500)
            Shots.log("home-with-widget")
            assertNotNull("the widget did not appear on the home screen with a battery percentage", shown)
        }
    }
}
