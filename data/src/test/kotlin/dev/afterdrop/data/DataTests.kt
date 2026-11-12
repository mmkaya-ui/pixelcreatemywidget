package dev.afterdrop.data

import dev.afterdrop.formula.EvalContext
import dev.afterdrop.formula.Evaluator
import dev.afterdrop.formula.Parser
import dev.afterdrop.formula.Value
import dev.afterdrop.spec.Json
import dev.afterdrop.spec.SpecReader
import dev.afterdrop.spec.SpecResult
import dev.afterdrop.spec.WidgetSpec
import dev.afterdrop.testkit.eq
import dev.afterdrop.testkit.goldenDir
import dev.afterdrop.testkit.runSuites
import dev.afterdrop.testkit.suite
import dev.afterdrop.testkit.yes
import java.io.File
import java.time.LocalDate

private val TODAY = LocalDate.of(2026, 11, 12)
private val STUTTGART = Place("Stuttgart, DE", 48.78232, 9.17702)

private fun spec(name: String): WidgetSpec = (SpecReader.read(File(goldenDir(), "valid/$name.json").readText()) as SpecResult.Ok).spec
private fun at(v: Value?, path: String): Value = Evaluator.eval(Parser.parse(path), EvalContext(0) { if (it == "d") v else null })
private fun n(v: Number) = Value.Num(v.toDouble())

private const val WEATHER = """{"latitude":48.78,"longitude":9.18,"utc_offset_seconds":0,
 "current":{"time":1794479400,"interval":900,"temperature_2m":7.4,"apparent_temperature":4.9,"wind_speed_10m":18.4,"wind_gusts_10m":31.0,"weather_code":61},
 "hourly":{"time":[1794477600,1794481200,1794484800,1794488400],"temperature_2m":[7.1,7.6,8.0,null],"precipitation_probability":[10,60,35,90]}}"""

private val weather = suite("weather") {
    test("request uses the saved place, rounded, and the right units") {
        val r = NetworkSources.request(spec("ride-check").sources.getValue("wx"), STUTTGART, TODAY)!!
        yes(r.url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=48.78&longitude=9.18&"), r.url)
        yes("fahrenheit" !in r.url)
        yes("timeformat=unixtime" in r.url)
        yes("fahrenheit" in NetworkSources.weatherUrl(STUTTGART, imperial = true))
    }
    test("no saved place means no request") {
        eq(null, NetworkSources.request(spec("ride-check").sources.getValue("wx"), null, TODAY))
    }
    test("answer becomes the fields widgets read") {
        val w = NetworkSources.parseWeather(WEATHER)
        eq(n(7.4), at(w, "d.now.temp"))
        eq(n(18.4), at(w, "d.now.wind"))
        eq(n(31), at(w, "d.now.gust"))
        eq(Value.Str("Rain"), at(w, "d.now.summary"))
        eq(n(60), at(w, "d.next3h.rainChance"))
        eq(n((1794479400L + 10800) * 1000), at(w, "d.next3h.until"))
        eq(n(4), at(w, "count(d.hourly)"))
        eq(n(1794477600000L), at(w, "d.hourly[0].time"))
        eq(Value.Null, at(w, "d.hourly[3].temp"))
        eq(Value.ListV(listOf(n(10), n(60), n(35), n(90))), at(w, "pluck(d.hourly, 'rainChance')"))
    }
    test("broken or partial answers do not throw") {
        eq(null, NetworkSources.parseWeather("<html>"))
        eq(null, NetworkSources.parseWeather("{}"))
        eq(null, NetworkSources.parseWeather("[1]"))
        val thin = NetworkSources.parseWeather("""{"current":{"temperature_2m":"warm"}}""")
        eq(Value.Null, at(thin, "d.now.temp"))
        eq(n(0), at(thin, "count(d.hourly)"))
    }
    test("geocoding") {
        yes(NetworkSources.geocodeUrl(" Stuttgart Botnang ").endsWith("name=Stuttgart+Botnang"))
        val p = NetworkSources.parseGeocode("""{"results":[{"name":"Stuttgart","latitude":48.78232,"longitude":9.17702,"country_code":"DE"}]}""")!!
        eq("Stuttgart, DE", p.name)
        eq(48.78232, p.lat)
        eq(null, NetworkSources.parseGeocode("""{"generationtime_ms":0.5}"""))
        eq(null, NetworkSources.parseGeocode("nope"))
    }
}

private val rates = suite("rates") {
    val src = spec("rates").sources.getValue("fx")
    test("request covers the asked number of days") {
        eq("https://api.frankfurter.dev/v1/2026-10-13..?base=EUR&symbols=TRY", NetworkSources.request(src, null, TODAY)!!.url)
    }
    test("answer gives the latest rate and the history in date order") {
        val body = """{"amount":1.0,"base":"EUR","start_date":"2026-11-09","end_date":"2026-11-11","rates":{"2026-11-10":{"TRY":49.1},"2026-11-09":{"TRY":48.9},"2026-11-11":{"TRY":49.4}}}"""
        val v = NetworkSources.request(src, null, TODAY)!!.parse(body)
        eq(n(49.4), at(v, "d.rate"))
        eq(Value.ListV(listOf(n(48.9), n(49.1), n(49.4))), at(v, "d.history"))
        eq(Value.Str("2026-11-11"), at(v, "d.date"))
        eq(n(0.5), at(v, "round(d.rate - first(d.history), 2)"))
    }
    test("an empty or wrong answer is nothing") {
        eq(null, NetworkSources.parseRates("""{"rates":{}}""", "TRY"))
        eq(null, NetworkSources.parseRates("""{"rates":{"2026-11-11":{"USD":1.1}}}""", "TRY"))
        eq(null, NetworkSources.parseRates("oops", "TRY"))
    }
}

private val feeds = suite("feeds") {
    test("RSS") {
        val rss = """<?xml version="1.0"?><rss version="2.0"><channel><title>Example  News</title>
          <item><title>First &amp; best</title><link>https://example.org/1</link><pubDate>Thu, 12 Nov 2026 08:30:00 GMT</pubDate></item>
          <item><title><![CDATA[Second <b>story</b>]]></title><link>http://example.org/2</link></item>
          <item><link>https://example.org/untitled</link></item></channel></rss>"""
        val v = NetworkSources.parseFeed(rss)
        eq(Value.Str("Example News"), at(v, "d.title"))
        eq(n(2), at(v, "count(d.items)"))
        eq(Value.Str("First & best"), at(v, "d.items[0].title"))
        eq(Value.Str("https://example.org/1"), at(v, "d.items[0].link"))
        eq(n(1794472200000L), at(v, "d.items[0].published"))
        eq(Value.Str("Second <b>story</b>"), at(v, "d.items[1].title"))
        eq(Value.Null, at(v, "d.items[1].link"))
    }
    test("Atom") {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Blog</title>
          <entry><title>Hello</title><link rel="self" href="https://example.org/self"/><link href="https://example.org/hello"/><updated>2026-11-12T08:30:00Z</updated></entry></feed>"""
        val v = NetworkSources.parseFeed(atom)
        eq(Value.Str("Blog"), at(v, "d.title"))
        eq(Value.Str("https://example.org/hello"), at(v, "d.items[0].link"))
        eq(n(1794472200000L), at(v, "d.items[0].published"))
    }
    test("at most 20 items") {
        val many = "<rss><channel>" + (1..50).joinToString("") { "<item><title>T$it</title></item>" } + "</channel></rss>"
        eq(n(20), at(NetworkSources.parseFeed(many), "count(d.items)"))
    }
    test("entity tricks and junk are refused") {
        val xxe = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><title>&x;</title></channel></rss>"""
        eq(null, NetworkSources.parseFeed(xxe))
        eq(null, NetworkSources.parseFeed("not xml"))
        eq(null, NetworkSources.parseFeed("<html><body>hi</body></html>"))
        eq(null, NetworkSources.parseFeed(""))
    }
}

private val general = suite("requests") {
    test("hosts shown to the person") {
        eq("api.open-meteo.com", NetworkSources.host(spec("ride-check").sources.getValue("wx")))
        eq("api.frankfurter.dev", NetworkSources.host(spec("rates").sources.getValue("fx")))
        eq("example.org", NetworkSources.host(spec("headlines").sources.getValue("news")))
        eq(null, NetworkSources.host(spec("battery").sources.getValue("bat")))
        eq("a.example", NetworkSources.hostOf("https://A.example:8443/x?y"))
        eq(null, NetworkSources.hostOf("http://a.example/"))
        eq(null, NetworkSources.hostOf("https://user@a.example/"))
        eq(null, NetworkSources.hostOf("intent://x"))
    }
    test("refresh intervals") {
        eq(30 * 60_000L, NetworkSources.interval(spec("ride-check").sources.getValue("wx")))
        eq(3 * 3_600_000L, NetworkSources.interval(spec("rates").sources.getValue("fx")))
        eq(3_600_000L, NetworkSources.interval(spec("battery").sources.getValue("bat")))
    }
    test("cache keys separate different sources and places") {
        val wx = spec("ride-check").sources.getValue("wx")
        yes(NetworkSources.cacheKey(wx, STUTTGART) != NetworkSources.cacheKey(wx, Place("x", 1.0, 2.0)))
        yes(NetworkSources.cacheKey(wx, STUTTGART) != NetworkSources.cacheKey(spec("rates").sources.getValue("fx"), STUTTGART))
        eq(NetworkSources.cacheKey(wx, STUTTGART), NetworkSources.cacheKey(wx, STUTTGART))
    }
    test("generic JSON is trimmed, and values survive storage") {
        val v = NetworkSources.parseJson("""{"a":{"b":[1,2,3]},"s":"${"x".repeat(900)}","big":[${(1..200).joinToString(",")}]}""")
        eq(n(2), at(v, "d.a.b[1]"))
        eq(n(500), at(v, "len(d.s)"))
        eq(n(50), at(v, "count(d.big)"))
        eq(n(3), at(NetworkSources.parseJson("[1,2,3]"), "count(d.items)"))
        eq(null, NetworkSources.parseJson("nope"))
        val w = NetworkSources.parseWeather(WEATHER)!!
        eq(w.toJson(), Json.parse(Json.write(w.toJson())))
    }
    test("sources that are not network sources give no request") {
        eq(null, NetworkSources.request(spec("battery").sources.getValue("bat"), STUTTGART, TODAY))
        eq(null, NetworkSources.request(spec("next-up").sources.getValue("cal"), STUTTGART, TODAY))
    }
}

fun main() = runSuites(weather, rates, feeds, general)
