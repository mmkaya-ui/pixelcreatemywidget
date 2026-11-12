package dev.afterdrop.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.afterdrop.data.NetworkSources
import dev.afterdrop.data.toJson
import dev.afterdrop.data.Place
import dev.afterdrop.engine.Engine
import dev.afterdrop.engine.RenderInput
import dev.afterdrop.engine.SizeClass
import dev.afterdrop.spec.SpecReader
import dev.afterdrop.spec.SpecResult
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.util.Locale

/**
 * Calls the real services once from the emulator, to prove the request and answer shapes are right.
 * Skipped, not failed, when the emulator has no internet, so an outage cannot break the build.
 */
@RunWith(AndroidJUnit4::class)
class LiveDataTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun spec(name: String) =
        (SpecReader.read(context.assets.open("$name.json").bufferedReader().use { it.readText() }) as SpecResult.Ok).spec

    private fun texts(name: String): List<String> {
        val s = spec(name)
        val input = RenderInput(s, Sources.collect(context, s), emptyMap(), System.currentTimeMillis(), ZoneId.systemDefault(), Locale.ROOT, SizeClass.L)
        return Engine.resolve(input).all().mapNotNull { it.text }.toList()
    }

    private fun online() = HttpsFetcher.get("https://api.open-meteo.com/v1/forecast?latitude=0&longitude=0&current=temperature_2m") != null

    @Test
    fun weatherIsRealOnceAPlaceIsSet() {
        assumeTrue("no internet on this emulator", online())
        val place = HttpsFetcher.get(NetworkSources.geocodeUrl("Stuttgart"))?.let { NetworkSources.parseGeocode(it) }
        Log.i(TAG, "geocoded: ${place?.name} ${place?.lat} ${place?.lon}")
        assertTrue("geocoding gave nothing", place != null && place.name.startsWith("Stuttgart"))
        Store(context).home = place
        val updated = Sources.refresh(context, listOf(spec("ride-check")), HttpsFetcher, force = true)
        val shown = texts("ride-check")
        Log.i(TAG, "weather updated=$updated shows $shown")
        assertTrue("weather was not fetched", updated == 1)
        assertTrue("wind is not a number: $shown", shown.any { Regex("\\d+ km/h").matches(it) })
        assertTrue("rain line is not filled: $shown", shown.any { Regex("\\d+% rain by \\d\\d:\\d\\d").matches(it) })
    }

    @Test
    fun exchangeRatesAreReal() {
        assumeTrue("no internet on this emulator", online())
        val updated = Sources.refresh(context, listOf(spec("rates")), HttpsFetcher, force = true)
        val shown = texts("rates")
        Log.i(TAG, "rates updated=$updated shows $shown")
        assertTrue("rates were not fetched", updated == 1)
        assertTrue("rate is not a number: $shown", shown.any { Regex("\\d+\\.\\d\\d TRY").matches(it) })
    }

    @Test
    fun aRealFeedParses() {
        assumeTrue("no internet on this emulator", online())
        val body = HttpsFetcher.get("https://www.tagesschau.de/index~rss2.xml")
        assumeTrue("the test feed is unreachable", body != null)
        val feed = NetworkSources.parseFeed(body!!)
        Log.i(TAG, "feed: ${(feed as? dev.afterdrop.formula.Value.Obj)?.fields?.get("title")}")
        assertTrue("feed did not parse", feed != null)
    }

    @Test
    fun everyNamedIconHasADrawable() {
        val names = dev.afterdrop.spec.IconNames
        for (n in names.ALL) assertTrue("no drawable for $n", dev.afterdrop.app.widget.iconRes(n) != null)
        for ((alias, target) in names.ALIASES) assertTrue("alias $alias points at a missing icon $target", target in names.ALL)
    }

    @Test
    fun cachedDataSurvivesWithoutTheNetwork() {
        val s = spec("rates")
        val key = NetworkSources.cacheKey(s.sources.getValue("fx"), Store(context).home)
        val value = NetworkSources.parseRates("{\"rates\":{\"2026-11-11\":{\"TRY\":49.5}}}", "TRY")!!
        Store(context).cache(key, value.toJson(), System.currentTimeMillis())
        // Nothing is due, so a fetcher that always fails must leave the cached value in place.
        val updated = Sources.refresh(context, listOf(s), { null })
        assertTrue("a fresh cache entry was refetched", updated == 0)
        assertTrue("cached rate is not shown: ${texts("rates")}", "49.50 TRY" in texts("rates"))
    }

    private companion object {
        const val TAG = "AfterdropTest"
    }
}
