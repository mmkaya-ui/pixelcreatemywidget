package dev.afterdrop.app

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.afterdrop.app.widget.AfterdropReceiver
import dev.afterdrop.app.widget.AfterdropWidget
import dev.afterdrop.composer.Composer
import dev.afterdrop.composer.Describe
import dev.afterdrop.composer.Described
import dev.afterdrop.spec.Json
import dev.afterdrop.spec.SpecResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Hosts the real widget inside a test activity, the way a launcher does, and inspects what it draws.
 * This exercises the whole path: stored spec, Glance session, RemoteViews, inflation and tap callbacks.
 */
@RunWith(AndroidJUnit4::class)
class WidgetHostTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val manager = AppWidgetManager.getInstance(context)
    private lateinit var host: AppWidgetHost
    private lateinit var scenario: ActivityScenario<MainActivity>

    private class Item(val kind: String, val text: String, val rect: Rect, val truncated: Boolean)
    private class Placed(val id: Int, val view: AppWidgetHostView)

    @Before
    fun setUp() {
        shell("appwidget grantbind --package ${context.packageName} --user 0")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        inst.runOnMainSync {
            host = AppWidgetHost(context, 77)
            host.startListening()
        }
    }

    @After
    fun tearDown() {
        inst.runOnMainSync { host.stopListening() }
        host.deleteHost()
        scenario.close()
    }

    private fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(cmd)).bufferedReader().use { it.readText() }

    private fun px(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()

    private fun asset(name: String) = context.assets.open("$name.json").bufferedReader().use { it.readText() }

    private fun place(json: String?, wDp: Int, hDp: Int, xDp: Int = 0, yDp: Int = 0): Placed {
        val id = host.allocateAppWidgetId()
        if (json != null) Store(context).bind(id, json)
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(wDp.toFloat(), hDp.toFloat())))
        }
        assertTrue("the system refused to bind the widget", manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, AfterdropReceiver::class.java), options))
        lateinit var view: AppWidgetHostView
        scenario.onActivity { a ->
            view = host.createView(a, id, manager.getAppWidgetInfo(id))
            // A host may add its own padding; remove it so the widget gets exactly the size it was told.
            view.setPadding(0, 0, 0, 0)
            a.addContentView(view, FrameLayout.LayoutParams(px(wDp), px(hDp)).apply { leftMargin = px(xDp); topMargin = px(yDp) })
        }
        runBlocking { AfterdropWidget.refresh(context, GlanceAppWidgetManager(context).getGlanceIdBy(id)) }
        return Placed(id, view)
    }

    private fun remove(p: Placed) {
        scenario.onActivity { (p.view.parent as? ViewGroup)?.removeView(p.view) }
        host.deleteAppWidgetId(p.id)
    }

    private fun dump(root: View): List<Item> {
        val out = ArrayList<Item>()
        inst.runOnMainSync {
            // A list scrolls and clips its rows, so a row is judged by the part the list shows.
            fun clipOf(v: View): Rect? {
                var up = v.parent as? View
                while (up != null && up !== root) {
                    if (up is android.widget.AbsListView) {
                        val l = IntArray(2)
                        up.getLocationOnScreen(l)
                        return Rect(l[0], l[1], l[0] + up.width, l[1] + up.height)
                    }
                    up = up.parent as? View
                }
                return null
            }
            fun walk(v: View) {
                if (v.visibility != View.VISIBLE) return
                val clip = clipOf(v)
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                if (clip != null && !Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height).intersect(clip)) return
                var height = v.height
                if (v is android.widget.CompoundButton) {
                    // The touch target of a checkbox or switch is the tallest tappable view around it.
                    var up = v.parent as? View
                    while (up != null && up !== root) {
                        if (up.hasOnClickListeners()) height = Math.max(height, up.height)
                        up = up.parent as? View
                    }
                }
                val rect = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + height)
                if (clip != null) rect.intersect(clip)
                when (v) {
                    is TextView -> {
                        val l = v.layout
                        val cut = l != null && (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }
                        out += Item(v.javaClass.simpleName, v.text?.toString() ?: "", rect, cut)
                    }
                    is ImageView -> out += Item("Image", "", rect, false)
                    is ProgressBar -> out += Item("Progress", "${v.progress}/${v.max}", rect, false)
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
        }
        return out
    }

    private fun texts(p: Placed) = dump(p.view).map { it.text }.filter { it.isNotEmpty() }

    private fun waitFor(p: Placed, what: String, seconds: Int = 20, test: (List<String>) -> Boolean): List<String> {
        val end = System.currentTimeMillis() + seconds * 1000
        var last = emptyList<String>()
        while (System.currentTimeMillis() < end) {
            last = texts(p)
            if (test(last)) return last
            Thread.sleep(250)
        }
        fail("timed out waiting for $what; the widget showed: $last")
        return last
    }

    private fun click(p: Placed, label: String) {
        var done = false
        inst.runOnMainSync {
            fun find(v: View): View? {
                if (v is TextView && v.text?.toString() == label) return v
                if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
                return null
            }
            var v: View? = find(p.view)
            if (v is android.widget.CompoundButton) {
                // Switches and checkboxes report through a checked-change response, not a click listener.
                v.toggle()
                done = true
                v = null
            }
            while (v != null && v !== p.view) {
                if (v.hasOnClickListeners()) {
                    done = v.performClick()
                    break
                }
                v = v.parent as? View
            }
        }
        assertTrue("nothing clickable was found for \"$label\"", done)
    }

    @Test
    fun waterTrackerRendersAndCounts() {
        val p = place(asset("water-today"), 180, 180)
        waitFor(p, "the starting count") { "0 of 8 glasses" in it }
        click(p, "+")
        waitFor(p, "one glass after pressing +") { "1 of 8 glasses" in it }
        click(p, "+")
        waitFor(p, "two glasses") { "2 of 8 glasses" in it }
        click(p, "−")
        waitFor(p, "back to one glass after pressing minus") { "1 of 8 glasses" in it }
        remove(p)
    }

    @Test
    fun batteryShowsARealPercentage() {
        val p = place(asset("battery"), 180, 80)
        val shown = waitFor(p, "a battery percentage") { t -> t.any { Regex("\\d{1,3}%").matches(it) } }
        Log.i(TAG, "battery widget shows $shown")
        remove(p)
    }

    @Test
    fun describedCountdownRenders() {
        val brief = (Describe.fromText("countdown to 24 December", LocalDate.now()) as Described.Ok).brief
        val spec = (Composer.compose(brief) as SpecResult.Ok).spec
        val p = place(Json.write(spec.json), 180, 80)
        val shown = waitFor(p, "the countdown") { t -> t.any { it.endsWith("to go") } && "24 December" in t }
        assertTrue("the day count should be a number: $shown", shown.first().toIntOrNull() != null)
        remove(p)
    }

    @Test
    fun toggleFlipsItsText() {
        val p = place(asset("focus-toggle"), 330, 110)
        waitFor(p, "the off state") { "Available" in it }
        click(p, "Focus mode")
        waitFor(p, "the on state after tapping the switch") { "Heads down" in it }
        remove(p)
    }

    @Test
    fun stackFlipsOnTapAndEachPageKeepsItsOwnValues() {
        val p = place(asset("water-today"), 180, 180)
        waitFor(p, "the single page, with no dots") { "0 of 8 glasses" in it && it.none { t -> "\u25CF" in t } }
        click(p, "+")
        waitFor(p, "one glass") { "1 of 8 glasses" in it }
        val store = Store(context)
        assertTrue(store.addPage(p.id, asset("battery")))
        assertTrue(store.addPage(p.id, asset("focus-toggle")))
        val id = GlanceAppWidgetManager(context).getGlanceIdBy(p.id)
        runBlocking { AfterdropWidget.refresh(context, id) }
        // Adding a page shows it: the third page, with its dot filled.
        waitFor(p, "the newest page and three dots") { "\u25CB \u25CB \u25CF" in it && it.any { t -> t == "Available" || t == "Focus mode" } }
        click(p, "\u25CB \u25CB \u25CF")
        waitFor(p, "the first page again, count kept") { "\u25CF \u25CB \u25CB" in it && "1 of 8 glasses" in it }
        click(p, "\u25CF \u25CB \u25CB")
        waitFor(p, "the battery page") { "\u25CB \u25CF \u25CB" in it && it.any { t -> Regex("\\d{1,3}%").matches(t) } }
        // Nothing may fall outside the widget, dots included.
        val loc = IntArray(2)
        inst.runOnMainSync { p.view.getLocationOnScreen(loc) }
        val bounds = Rect(loc[0], loc[1], loc[0] + px(180), loc[1] + px(180))
        for (i in dump(p.view)) assertTrue("${i.kind} \"${i.text}\" is outside the stacked widget", i.rect.width() <= 0 || bounds.contains(i.rect))
        // Removing the page that is showing falls back to a valid page, and one page means no dots.
        assertTrue(store.removePage(p.id, 1))
        assertTrue(store.removePage(p.id, 1))
        assertTrue("the last page must not be removable", !store.removePage(p.id, 0))
        runBlocking { AfterdropWidget.refresh(context, id) }
        waitFor(p, "the single page again, count still kept") { "1 of 8 glasses" in it && it.none { t -> "\u25CF" in t } }
        remove(p)
    }

    @Test
    fun aStackOfSixIsTheLimitAndFitsASmallWidget() {
        val p = place(asset("battery"), 150, 70)
        val store = Store(context)
        for (name in listOf("clock-date", "holiday-countdown", "water-today", "tea-timer", "focus-toggle")) assertTrue(store.addPage(p.id, asset(name)))
        assertTrue("a seventh page was accepted", !store.addPage(p.id, asset("rates")))
        val id = GlanceAppWidgetManager(context).getGlanceIdBy(p.id)
        val loc = IntArray(2)
        repeat(6) { n ->
            runBlocking { AfterdropWidget.refresh(context, id) }
            val dots = AfterdropWidget.dots(6, store.page(p.id))
            waitFor(p, "page ${store.page(p.id)} of the small stack") { dots in it && it.size >= 2 }
            inst.runOnMainSync { p.view.getLocationOnScreen(loc) }
            val bounds = Rect(loc[0], loc[1], loc[0] + px(150), loc[1] + px(70))
            for (i in dump(p.view)) {
                assertTrue("page $n: ${i.kind} \"${i.text}\" is outside or clipped away", i.kind == "Image" || (i.rect.width() > 0 && i.rect.height() > 0 && bounds.contains(i.rect)))
            }
            store.flip(p.id)
        }
        remove(p)
    }

    @Test
    fun typingAValueIntoAWidget() {
        val p = place(asset("weight-log"), 180, 80)
        waitFor(p, "the empty state") { "Tap to log" in it }
        context.startActivity(AskActivity.intent(context, p.id, "kg", "Weight in kg"))
        val device = androidx.test.uiautomator.UiDevice.getInstance(inst)
        val field = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.clazz("android.widget.EditText")), 10_000)
        assertTrue("the dialog did not open", field != null && device.hasObject(androidx.test.uiautomator.By.text("Weight in kg")))
        field.text = "abc"
        device.findObject(androidx.test.uiautomator.By.text("Save")).click()
        assertTrue("a non-number was accepted", device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text("Enter a number")), 3_000))
        device.findObject(androidx.test.uiautomator.By.clazz("android.widget.EditText")).text = "72,5"
        device.findObject(androidx.test.uiautomator.By.text("Save")).click()
        waitFor(p, "the typed weight on the widget") { "72.5 kg" in it }
        remove(p)
    }

    @Test
    fun anEditReachesAWidgetAlreadyPlacedAndKeepsItsCount() {
        val p = place(asset("water-today"), 180, 180)
        waitFor(p, "the starting count") { "0 of 8 glasses" in it }
        click(p, "+")
        waitFor(p, "one glass") { "1 of 8 glasses" in it }
        val changed = Store(context).rebind("Water today", asset("water-today").replace("of {goal} glasses", "of {goal} cups"))
        assertTrue("no placed widget was found by name", changed >= 1)
        runBlocking { AfterdropWidget.refresh(context, GlanceAppWidgetManager(context).getGlanceIdBy(p.id)) }
        waitFor(p, "the new wording with the old count") { "1 of 8 cups" in it }
        remove(p)
    }

    @Test
    fun calendarWidgetWorksWithAndWithoutPermission() {
        val p = place(asset("next-up"), 330, 180)
        waitFor(p, "the empty list without permission") { "Nothing scheduled" in it }
        remove(p)
        shell("pm grant ${context.packageName} android.permission.READ_CALENDAR")
        assertTrue("permission was not granted", Sources.canReadCalendar(context))
        val q = place(asset("next-up"), 330, 180)
        // The emulator's calendar is empty, so the same text must come from a real, successful query.
        waitFor(q, "the empty list with permission") { "Nothing scheduled" in it }
        remove(q)
    }

    private fun online() = HttpsFetcher.get("https://api.open-meteo.com/v1/forecast?latitude=0&longitude=0&current=temperature_2m") != null

    private fun waitUntil(what: String, seconds: Int = 20, test: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < end) {
            if (test()) return
            Thread.sleep(250)
        }
        fail("timed out waiting for $what")
    }

    /** Pictures the widget really drew (a bitmap, not the empty tile), by their description. */
    private fun pictures(root: View, description: String): Int {
        var count = 0
        inst.runOnMainSync {
            fun walk(v: View) {
                if (v.visibility != View.VISIBLE) return
                if (v is ImageView && v.contentDescription?.toString() == description && v.drawable != null && v.width > 0 && v.height > 0) count++
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
        }
        return count
    }

    /** A screenshot, with what the widget's views hold at that moment, to compare the two. */
    private fun shot(p: Placed, name: String) {
        Thread.sleep(1200)
        Log.i(TAG, "$name views: " + dump(p.view).joinToString(" | ") { "${it.kind}:${it.text.take(24)}@${it.rect.toShortString()}" })
        Shots.log(name)
    }

    @Test
    fun noteWidgetShowsTheNote() {
        val store = Store(context)
        store.saveNote("pinned", "Buy flowers")
        val p = place(asset("sticky-note"), 180, 180)
        waitFor(p, "the note") { "Buy flowers" in it }
        store.saveNote("pinned", "")
        runBlocking { AfterdropWidget.refresh(context, GlanceAppWidgetManager(context).getGlanceIdBy(p.id)) }
        waitFor(p, "the empty note prompt") { "Tap to write" in it }
        remove(p)
    }

    /**
     * A real media session, as a music app makes one. Afterdrop sees it through notification access,
     * draws the song and its cover, and redraws by itself when the music stops.
     */
    @Test
    fun nowPlayingFollowsARealMediaSession() {
        val component = "${context.packageName}/${NowPlayingListener::class.java.name}"
        shell("cmd notification allow_listener $component")
        waitUntil("notification access") { NowPlaying.allowed(context) }
        lateinit var session: android.media.session.MediaSession
        inst.runOnMainSync {
            session = android.media.session.MediaSession(context, "AfterdropTest")
            val art = android.graphics.Bitmap.createBitmap(300, 300, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(art).drawColor(0xFFE0457B.toInt())
            session.setMetadata(
                android.media.MediaMetadata.Builder()
                    .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Afterdrop Test Song")
                    .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "Test Artist")
                    .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, "Test Album")
                    .putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                    .build()
            )
            session.setPlaybackState(
                android.media.session.PlaybackState.Builder()
                    .setState(android.media.session.PlaybackState.STATE_PLAYING, 0, 1f)
                    .setActions(android.media.session.PlaybackState.ACTION_PLAY_PAUSE)
                    .build()
            )
            session.isActive = true
        }
        try {
            waitUntil("the session to be seen") { NowPlaying.current(context)?.title == "Afterdrop Test Song" }
            val track = NowPlaying.current(context)!!
            Log.i(TAG, "now playing: ${track.title} / ${track.artist} / ${track.album} playing=${track.playing} art=${track.art}")
            assertTrue("not reported as playing", track.playing)
            assertTrue("cover art was not saved", track.art?.isFile == true)
            val p = place(asset("now-playing"), 330, 80)
            waitFor(p, "the song") { "Afterdrop Test Song" in it && "Test Artist" in it }
            waitUntil("the cover to be drawn") { pictures(p.view, "Cover art") == 1 }
            shot(p, "now-playing")
            // Paused music still shows; the widget follows without being told.
            inst.runOnMainSync {
                session.setPlaybackState(
                    android.media.session.PlaybackState.Builder().setState(android.media.session.PlaybackState.STATE_PAUSED, 0, 0f).build()
                )
            }
            waitUntil("pause to be seen") { NowPlaying.current(context)?.playing == false }
            inst.runOnMainSync { session.release() }
            waitFor(p, "the widget to notice the music stopped", 30) { "Nothing playing" in it }
            remove(p)
        } finally {
            inst.runOnMainSync { runCatching { session.release() } }
            shell("cmd notification disallow_listener $component")
        }
    }

    private fun granted(perms: Array<String>) = perms.all { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    /**
     * Asks for health permissions the way the app does, and answers Health Connect's own screen as a
     * person would. Only a grant made there puts an app among the sources Health Connect adds up.
     */
    private fun grantThroughHealthConnect(perms: Array<String>, shot: String): Boolean {
        val device = androidx.test.uiautomator.UiDevice.getInstance(inst)
        scenario.onActivity { it.requestPermissions(perms, 7) }
        val end = System.currentTimeMillis() + 30_000
        var allTapped = false
        var shotTaken = false
        while (System.currentTimeMillis() < end) {
            if (granted(perms)) break
            runCatching {
                if (!shotTaken && device.findObject(androidx.test.uiautomator.By.text("Allow")) != null) {
                    Thread.sleep(600)
                    Shots.log(shot)
                    shotTaken = true
                }
                device.findObject(androidx.test.uiautomator.By.text(java.util.regex.Pattern.compile("(?i)get started|continue")))?.click()
                if (!allTapped) device.findObject(androidx.test.uiautomator.By.text("Allow all"))?.let { it.click(); allTapped = true; Thread.sleep(600) }
                device.findObject(androidx.test.uiautomator.By.text("Allow").enabled(true))?.click()
            }
            Thread.sleep(700)
        }
        val texts = device.findObjects(androidx.test.uiautomator.By.pkg(java.util.regex.Pattern.compile(".*"))).mapNotNull { runCatching { it.text }.getOrNull() }.filter { it.isNotBlank() }.distinct().take(20)
        Log.i(TAG, "health connect screen for ${perms.toList()}: granted=${granted(perms)}; it showed $texts")
        // Close the screen only if it is still up; once answered it closes by itself.
        if (!granted(perms)) device.pressBack()
        return granted(perms)
    }

    /** A step record written into Health Connect shows up in the steps widget, today and in the week. */
    @Test
    fun stepsComeFromHealthConnect() {
        assertTrue("Health Connect is missing on this emulator", Steps.available(context))
        val write = "android.permission.health.WRITE_STEPS"
        for (perm in listOf(Steps.READ, write, Steps.BACKGROUND)) shell("pm revoke ${context.packageName} $perm")
        val throughScreen = grantThroughHealthConnect(arrayOf(Steps.READ, write), "health-connect-permission")
        if (!throughScreen) for (perm in listOf(Steps.READ, write)) Log.i(TAG, "pm grant $perm: " + shell("pm grant ${context.packageName} $perm").trim())
        if (!grantThroughHealthConnect(arrayOf(Steps.BACKGROUND), "health-connect-background")) shell("pm grant ${context.packageName} ${Steps.BACKGROUND}")
        assertTrue("step access was not granted", Steps.granted(context))
        Log.i(TAG, "steps granted through the Health Connect screen: $throughScreen; background: ${Steps.backgroundGranted(context)}")
        val hc = context.getSystemService(android.health.connect.HealthConnectManager::class.java)!!
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.Instant.now()
        val today = java.time.LocalDate.now(zone)
        // Start clean: remove this app's own step records from the last week.
        Steps.await<Void?> { cb ->
            hc.deleteRecords(
                android.health.connect.datatypes.StepsRecord::class.java,
                android.health.connect.TimeInstantRangeFilter.Builder().setStartTime(today.minusDays(8).atStartOfDay(zone).toInstant()).setEndTime(now).build(),
                Steps.direct, cb,
            )
        }
        fun record(start: java.time.Instant, end: java.time.Instant, count: Long) =
            android.health.connect.datatypes.StepsRecord.Builder(
                android.health.connect.datatypes.Metadata.Builder()
                    .setRecordingMethod(android.health.connect.datatypes.Metadata.RECORDING_METHOD_MANUAL_ENTRY).build(),
                start, end, count,
            ).build()
        val start = maxOf(today.atStartOfDay(zone).toInstant().plusSeconds(1), now.minusSeconds(1800))
        val yesterdayNoon = today.minusDays(1).atTime(12, 0).atZone(zone).toInstant()
        val inserted = Steps.await<android.health.connect.InsertRecordsResponse> { cb ->
            hc.insertRecords(listOf(record(start, now.minusSeconds(5), 4321), record(yesterdayNoon, yesterdayNoon.plusSeconds(1800), 2000)), Steps.direct, cb)
        }
        assertTrue("Health Connect did not take the test records", inserted?.records?.size == 2)
        // The records are there to read, whatever Health Connect's totals include.
        val raw = Steps.await<android.health.connect.ReadRecordsResponse<android.health.connect.datatypes.StepsRecord>> { cb ->
            hc.readRecords(
                android.health.connect.ReadRecordsRequestUsingFilters.Builder(android.health.connect.datatypes.StepsRecord::class.java)
                    .setTimeRangeFilter(android.health.connect.TimeInstantRangeFilter.Builder().setStartTime(today.minusDays(2).atStartOfDay(zone).toInstant()).setEndTime(now).build())
                    .build(),
                Steps.direct, cb,
            )
        }
        val rawTotal = raw?.records?.sumOf { it.count } ?: 0L
        Log.i(TAG, "raw step records: ${raw?.records?.size}, total $rawTotal")
        assertTrue("the test records cannot be read back: $rawTotal", rawTotal >= 6321)
        val v = Steps.value(context) as dev.afterdrop.formula.Value.Obj
        Log.i(TAG, "steps value: $v")
        if (!throughScreen) {
            // Granted behind Health Connect's back, the app is not among the sources it adds up, so the
            // totals leave its records out. The read still has to work and give a well-formed week.
            Log.w(TAG, "Health Connect's screen could not be answered; checking the shape of the totals only")
            assertEquals(7, (v.fields["week"] as dev.afterdrop.formula.Value.ListV).items.size)
            return
        }
        val todaySteps = (v.fields["today"] as dev.afterdrop.formula.Value.Num).v
        val week = (v.fields["week"] as dev.afterdrop.formula.Value.ListV).items.map { (it as dev.afterdrop.formula.Value.Num).v }
        assertTrue("today shows $todaySteps, expected at least 4321", todaySteps >= 4321)
        assertEquals("the week must have seven days", 7, week.size)
        assertEquals("the last day of the week is today", todaySteps, week[6], 0.0)
        assertTrue("yesterday shows ${week[5]}, expected at least 2000", week[5] >= 2000)
        val p = place(asset("steps"), 180, 180)
        waitFor(p, "the step count") { t -> t.any { Regex("4[,.\u202F\u00A0 ]?3\\d\\d").matches(it) } }
        shot(p, "steps")
        remove(p)
    }

    /** A real feed with pictures: the stories show, and so do their thumbnails. */
    @Test
    fun feedStoriesShowTheirPictures() {
        assumeTrue("no internet on this emulator", online())
        Store(context).approveHost("feeds.bbci.co.uk")
        val s = (dev.afterdrop.spec.SpecReader.read(asset("headlines")) as dev.afterdrop.spec.SpecResult.Ok).spec
        val updated = Sources.refresh(context, listOf(s), HttpsFetcher, force = true)
        assumeTrue("the feed is unreachable from this emulator", updated == 1)
        val ready = Images.prefetch(context, listOf(s))
        Log.i(TAG, "feed pictures ready: $ready")
        assertTrue("no story picture was downloaded", ready > 0)
        val p = place(asset("headlines"), 330, 250)
        val shown = waitFor(p, "stories") { it.size > 3 }
        Log.i(TAG, "headlines show $shown")
        waitUntil("story pictures to be drawn") { pictures(p.view, "Picture") > 0 }
        shot(p, "headlines")
        remove(p)
    }

    /** Without a key the AI text widget says what it needs; with a key Google refuses, it says why. */
    @Test
    fun aiTextSaysWhatItNeeds() {
        val store = Store(context)
        val keep = store.apiKey
        try {
            store.apiKey = ""
            val p = place(asset("daily-quote"), 330, 180)
            waitFor(p, "the key message") { Sources.NEEDS_KEY in it }
            remove(p)
            if (online()) {
                store.apiKey = "not-a-real-key"
                store.model = null
                val s = (dev.afterdrop.spec.SpecReader.read(asset("daily-quote")) as dev.afterdrop.spec.SpecResult.Ok).spec
                Sources.refresh(context, listOf(s), HttpsFetcher, force = true)
                val q = place(asset("daily-quote"), 330, 180)
                val shown = waitFor(q, "the refusal") { t -> t.any { it.startsWith("Gemini did not answer: the Gemini API refused the key") } }
                Log.i(TAG, "ai with a bad key shows $shown")
                remove(q)
            }
        } finally {
            store.apiKey = keep
        }
    }

    @Test
    fun unboundWidgetAsksToChoose() {
        val p = place(null, 180, 80)
        waitFor(p, "the choose message") { "Tap to choose a widget" in it }
        remove(p)
    }

    @Test
    fun brokenStoredSpecShowsAMessageInsteadOfCrashing() {
        val p = place("""{"v":1,"name":"x","size":{"cols":1,"rows":1},"root":{"t":"webview"}}""", 180, 80)
        waitFor(p, "the broken-widget message") { t -> t.any { it.startsWith("This widget could not be read") } }
        remove(p)
    }

    /** Every gallery widget at five sizes: it must draw something, and nothing may fall outside the widget. */
    @Test
    fun everyGalleryWidgetStaysInsideItsBoundsAtFiveSizes() {
        // About 2x1, 2x2 and 4x3 cells, then the extremes: one cell wide and five tall, and five wide and one tall.
        checkLayouts(listOf(150 to 70, 180 to 180, 330 to 250, 57 to 400, 400 to 57), "")
    }

    /** The same checks with the system set to dark theme and a 1.5x font, the hardest common combination. */
    @Test
    fun layoutHoldsInDarkThemeWithLargeFont() {
        tearDown()
        try {
            shell("settings put system font_scale 1.5")
            shell("cmd uimode night yes")
            Thread.sleep(2500)
            setUp()
            assertTrue("font scale was not applied", context.resources.configuration.fontScale > 1.4f)
            checkLayouts(listOf(150 to 70, 180 to 180, 330 to 250), "dark+font1.5 ")
            wall("wall-dark-largefont")
        } finally {
            tearDown()
            shell("settings put system font_scale 1.0")
            shell("cmd uimode night no")
            Thread.sleep(2000)
            setUp()
        }
    }

    /** Lays widgets out like a home screen and logs a screenshot, so the design can be looked at. */
    @Test
    fun homeScreenWall() {
        wall("wall-light")
    }

    /**
     * Widgets a real model wrote (Gemini, recorded from the live check), placed at the sizes it chose and
     * fed live data. They must stay inside their bounds like any other widget; the screenshots show how they look.
     */
    @Test
    fun widgetsWrittenByTheModel() {
        val testAssets = inst.context.assets
        val specs = testAssets.list("ai")!!.sorted().associateWith { f -> testAssets.open("ai/$f").bufferedReader().use { it.readText() } }
        assertTrue("no recorded model widgets", specs.size >= 6)
        Store(context).home = dev.afterdrop.data.Place("Stuttgart, DE", 48.78, 9.18)
        val parsed = specs.values.mapNotNull { (dev.afterdrop.spec.SpecReader.read(it) as? dev.afterdrop.spec.SpecResult.Ok)?.spec }
        assertTrue("a recorded model widget is no longer valid", parsed.size == specs.size)
        val fetched = Sources.refresh(context, parsed, HttpsFetcher, force = true)
        Log.i(TAG, "model widgets: fetched $fetched sources")
        scenario.onActivity { a -> a.addContentView(View(a).apply { setBackgroundColor(0xFF35506B.toInt()) }, FrameLayout.LayoutParams(-1, -1)) }
        val problems = ArrayList<String>()
        var y = 48
        var shot = 1
        val onScreen = ArrayList<Placed>()
        fun flush() {
            if (onScreen.isEmpty()) return
            Thread.sleep(1500)
            Shots.log("wall-ai-$shot")
            shot++
            for (p in onScreen) remove(p)
            onScreen.clear()
            y = 48
        }
        for ((file, json) in specs) {
            val s = (dev.afterdrop.spec.SpecReader.read(json) as dev.afterdrop.spec.SpecResult.Ok).spec
            val w = Math.min(s.cols, 4) * 92 + 4
            val h = Math.min(s.rows, 3) * 92 - 4
            if (y + h > 840) flush()
            val p = place(json, w, h, 12, y)
            val shown = waitFor(p, "$file to draw") { it.isNotEmpty() }
            Thread.sleep(400)
            val loc = IntArray(2)
            inst.runOnMainSync { p.view.getLocationOnScreen(loc) }
            val bounds = Rect(loc[0], loc[1], loc[0] + px(w), loc[1] + px(h))
            for (i in dump(p.view)) {
                if (i.kind != "Image" && (i.rect.width() <= 0 || i.rect.height() <= 0)) problems += "$file: ${i.kind} \"${i.text.take(20)}\" has no size"
                else if (i.rect.width() > 0 && !bounds.contains(i.rect)) problems += "$file: ${i.kind} \"${i.text.take(20)}\" is outside its widget"
            }
            Log.i(TAG, "model widget $file ${s.name} ${w}x$h shows $shown")
            onScreen += p
            y += h + 12
        }
        flush()
        if (problems.isNotEmpty()) fail("${problems.size} problem(s) in model-written widgets:\n" + problems.joinToString("\n"))
    }

    private fun wall(name: String) {
        scenario.onActivity { a ->
            a.addContentView(View(a).apply { setBackgroundColor(0xFF35506B.toInt()) }, FrameLayout.LayoutParams(-1, -1))
        }
        val placed = listOf(
            place(asset("water-today"), 180, 180, 12, 48), place(asset("sticky-note"), 180, 180, 204, 48),
            place(asset("ride-check"), 180, 180, 12, 240), place(asset("shopping-checks"), 180, 180, 204, 240),
            place(asset("rates"), 372, 110, 12, 432),
            place(asset("battery"), 180, 80, 12, 554), place(asset("holiday-countdown"), 180, 80, 204, 554),
            place(asset("morning-dashboard"), 372, 110, 12, 646),
            place(asset("clock-date"), 180, 80, 12, 768), place(asset("tea-timer"), 180, 80, 204, 768),
        )
        for (p in placed) waitFor(p, "wall widget to draw") { it.isNotEmpty() }
        Thread.sleep(1500)
        Shots.log(name)
        for (p in placed) remove(p)
    }

    private fun checkLayouts(sizes: List<Pair<Int, Int>>, label: String) {
        val names = context.assets.list("")!!.filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted()
        assertTrue("gallery is missing: $names", names.size >= 15)
        val problems = ArrayList<String>()
        for (name in names) for ((w, h) in sizes) {
            val p = place(asset(name), w, h)
            val end = System.currentTimeMillis() + 15_000
            var items = emptyList<Item>()
            while (System.currentTimeMillis() < end) {
                items = dump(p.view)
                if (items.any { it.kind != "Progress" || it.text != "0/100" } && items.isNotEmpty()) break
                Thread.sleep(250)
            }
            Thread.sleep(300)
            items = dump(p.view)
            val loc = IntArray(2)
            inst.runOnMainSync { p.view.getLocationOnScreen(loc) }
            val bounds = Rect(loc[0], loc[1], loc[0] + px(w), loc[1] + px(h))
            val tag = "$label$name ${w}x$h"
            if (items.isEmpty()) problems += "$tag: drew nothing"
            val density = context.resources.displayMetrics.density
            for (i in items) {
                val what = "${i.kind} \"${i.text.take(24)}\""
                val control = i.kind in setOf("Button", "Switch", "CheckBox")
                if (i.rect.width() <= 0 || i.rect.height() <= 0) {
                    if (i.kind == "Image") Log.w(TAG, "$tag: $what has no size") else problems += "$tag: $what has no size (clipped away)"
                } else if (!bounds.contains(i.rect)) problems += "$tag: $what at ${i.rect.toShortString()} is outside ${bounds.toShortString()}"
                else if (control && i.rect.height() / density < 39.5f) problems += "$tag: $what is only ${(i.rect.height() / density).toInt()} dp tall"
                if (i.truncated) Log.w(TAG, "$tag: $what is cut with an ellipsis")
            }
            Log.i(TAG, "$tag: " + items.joinToString(" | ") { "${it.kind}:${it.text.take(20)}@${(it.rect.height() / density).toInt()}dp" })
            remove(p)
        }
        if (problems.isNotEmpty()) fail("${problems.size} layout problem(s):\n" + problems.joinToString("\n"))
    }

    private companion object {
        const val TAG = "AfterdropTest"
    }
}
