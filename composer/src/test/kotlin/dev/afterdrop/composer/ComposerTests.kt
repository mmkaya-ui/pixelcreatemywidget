package dev.afterdrop.composer

import dev.afterdrop.spec.JNumber
import dev.afterdrop.spec.JString
import dev.afterdrop.spec.Json
import dev.afterdrop.spec.JsonValue
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
import java.util.Random

private val TODAY = LocalDate.of(2026, 11, 12)

private fun ok(b: Brief): WidgetSpec = when (val r = Composer.compose(b)) {
    is SpecResult.Ok -> r.spec
    is SpecResult.Rejected -> throw AssertionError("${b.intent}: " + r.problems.joinToString("; "))
}

private fun codes(b: Brief): List<String> = (Composer.compose(b) as? SpecResult.Rejected)?.problems?.map { it.code } ?: emptyList()

private fun described(text: String): Brief = when (val d = Describe.fromText(text, TODAY)) {
    is Described.Ok -> d.brief
    is Described.No -> throw AssertionError("\"$text\" was refused: ${d.reason}")
}

private fun refused(text: String): String = (Describe.fromText(text, TODAY) as Described.No).reason

/** One working brief per archetype. */
private fun samples(cols: Int = 2, rows: Int = 2, title: String = "My widget"): List<Brief> {
    fun b(intent: String, show: List<String> = emptyList(), vararg extras: Pair<String, JsonValue>) =
        Brief(intent, title, "stat", cols, rows, show, extras = mapOf(*extras))
    return listOf(
        b("countdown", emptyList(), "date" to JString("2026-12-24")),
        b("tracker", emptyList(), "goal" to JNumber(8.0), "unit" to JString("glasses")),
        b("tracker"),
        b("todo", listOf("Milk", "Bread", "Eggs")),
        b("clock"),
        b("clock", emptyList(), "zone" to JString("Asia/Tokyo")),
        b("battery"),
        b("weather", listOf("wind", "rain")),
        b("agenda"),
        b("quote", emptyList(), "topic" to JString("stoic")),
        b("rates", emptyList(), "base" to JString("eur"), "quote" to JString("try")),
        b("feed", emptyList(), "url" to JString("https://example.org/feed.xml")),
        b("dashboard"),
        b("custom", emptyList(), "kind" to JString("timer"), "minutes" to JNumber(5.0)),
        b("custom", listOf("Line one", "Line two")),
    )
}

private val composing = suite("composer") {
    test("every archetype gives a valid spec at every size") {
        for (cols in 1..5) for (rows in 1..5) for (b in samples(cols, rows)) {
            val s = ok(b)
            eq(cols, s.cols)
            eq(rows, s.rows)
        }
    }
    test("hostile titles and items stay text, never formulas") {
        val nasty = listOf("{it}", "}}{{", "{exec('x')}", "a'b\\c", "\u0000\u0007 tab\there", "😀".repeat(40), "x".repeat(40), " ", "{", "}")
        for (title in nasty) for (b in samples(title = title)) {
            val s = ok(b)
            yes(s.name.isNotEmpty() && s.name.codePointCount(0, s.name.length) <= 40, "name: ${s.name}")
        }
        val todo = ok(Brief("todo", "List", "list", 2, 2, listOf("{cal.events}", "}", "ok")))
        eq(3, todo.vars.size)
        yes(todo.root.all().none { n -> n.text("label")?.let { Regex("(?<!\\{)\\{(?!\\{)").containsMatchIn(it) } == true }, "an item became a formula")
    }
    test("a countdown counts days and stops at zero") {
        val s = ok(Brief("countdown", "Holiday", "stat", 2, 1, extras = mapOf("date" to JString("2026-12-24"))))
        eq("max(days(today(), date('2026-12-24')), 0)", s.calc["left"])
    }
    test("a tracker with a goal has a ring, a daily reset and a bounded stepper") {
        val s = ok(Brief("tracker", "Water", "ring", 2, 2, extras = mapOf("goal" to JNumber(8.0), "unit" to JString("glasses"))))
        eq("daily", s.vars.getValue("count").reset)
        val stepper = s.root.all().first { it.type == "stepper" }
        eq(80.0, stepper.number("max"))
        yes(s.root.all().any { it.type == "progress" && it.text("shape") == "ring" })
        yes(s.root.all().any { it.text("text") == "{count} of {goal} glasses" })
    }
    test("bad extras are refused with a reason") {
        fun b(intent: String, vararg e: Pair<String, JsonValue>) = Brief(intent, "T", "stat", 2, 2, extras = mapOf(*e))
        eq(listOf("brief.countdown_date"), codes(b("countdown")))
        eq(listOf("brief.countdown_date"), codes(b("countdown", "date" to JString("24.12.2026"))))
        eq(listOf("brief.countdown_date"), codes(b("countdown", "date" to JString("2026-12-24') + exec('"))))
        eq(listOf("brief.todo_items"), codes(b("todo")))
        eq(listOf("brief.clock_zone"), codes(b("clock", "zone" to JString("Mars/Base"))))
        eq(listOf("brief.rates_currency"), codes(b("rates", "base" to JString("euro"))))
        eq(listOf("brief.feed_url"), codes(b("feed")))
        eq(listOf("brief.feed_url"), codes(b("feed", "url" to JString("http://example.org/feed"))))
        eq(listOf("brief.feed_url"), codes(b("feed", "url" to JString("https://example.org/{cal.events}"))))
        eq(listOf("brief.timer_minutes"), codes(b("custom", "kind" to JString("timer"), "minutes" to JNumber(0.0))))
        eq(listOf("brief.unsupported"), codes(b("calculator")))
    }
    test("the same brief always gives the same spec") {
        for (b in samples()) eq(Json.write(ok(b).json), Json.write(ok(b).json))
    }
    test("random briefs give a valid spec or a refusal, never anything else") {
        val r = Random(5)
        val words = listOf("", "x", "{", "}", "'", "2026-12-24", "2026-02-30", "Asia/Tokyo", "EUR", "https://a.example/f", "timer", "daily", "😀")
        var valid = 0
        repeat(6_000) {
            val extras = LinkedHashMap<String, JsonValue>()
            for (k in listOf("date", "goal", "unit", "step", "reset", "zone", "topic", "base", "quote", "url", "kind", "minutes", "units")) {
                when (r.nextInt(4)) {
                    0 -> extras[k] = JString(words[r.nextInt(words.size)])
                    1 -> extras[k] = JNumber(listOf(-1.0, 0.0, 0.5, 1.0, 8.0, 601.0, 1e9)[r.nextInt(7)])
                }
            }
            val b = Brief(
                Brief.INTENTS.toList()[r.nextInt(Brief.INTENTS.size)], words[r.nextInt(words.size)].ifEmpty { "T" },
                Brief.LAYOUTS.toList()[r.nextInt(Brief.LAYOUTS.size)], 1 + r.nextInt(5), 1 + r.nextInt(5),
                List(r.nextInt(7)) { words[r.nextInt(words.size)] }, extras = extras,
            )
            when (val out = Composer.compose(b)) {
                is SpecResult.Ok -> valid++
                is SpecResult.Rejected -> yes(out.problems.all { it.code.startsWith("brief.") }, "${b.intent}: composer wrote an invalid spec: ${out.problems}")
            }
        }
        yes(valid > 1_500, "only $valid valid")
    }
}

private val briefs = suite("brief reader") {
    test("reads the example brief and round-trips it") {
        val text = File(goldenDir(), "briefs/ride-check.brief.json").readText()
        val b = (BriefReader.read(text) as BriefResult.Ok).brief
        eq("weather", b.intent)
        eq(listOf("wind", "gust", "rainChance"), b.show)
        eq("dynamic", b.palette)
        ok(b)
        val again = (BriefReader.read(Json.write(b.toJson())) as BriefResult.Ok).brief
        eq(Json.write(b.toJson()), Json.write(again.toJson()))
    }
    test("refuses malformed briefs") {
        fun codes(s: String, lenient: Boolean = false) = (BriefReader.read(s, lenient) as BriefResult.Rejected).problems.map { it.code }
        val good = """{"v":1,"intent":"clock","title":"Clock","layout":"hero","size":{"cols":2,"rows":1}}"""
        yes(BriefReader.read(good) is BriefResult.Ok)
        yes(BriefReader.read("```json\n$good\n```", lenient = true) is BriefResult.Ok)
        eq(listOf("parse.json"), codes("not json"))
        eq(listOf("brief.type"), codes("[]"))
        yes("brief.required" in codes("{}"))
        eq(listOf("brief.enum"), codes(good.replace("clock\"", "spaceship\"")))
        eq(listOf("brief.range"), codes(good.replace("\"cols\":2", "\"cols\":9")))
        eq(listOf("brief.length"), codes(good.replace("\"Clock\"", "\"\"")))
        eq(listOf("brief.unknown_property"), codes(good.replace("{\"v\"", "{\"root\":{},\"v\"")))
        eq(listOf("brief.max_items"), codes(good.dropLast(1) + ",\"show\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\"]}"))
        eq(listOf("brief.type"), codes(good.dropLast(1) + ",\"extras\":{\"a\":[1]}}"))
        eq(listOf("brief.const"), codes(good.replace("\"v\":1", "\"v\":2")))
    }
}

private val rules = suite("describe rules") {
    test("countdowns and their dates") {
        val b = described("Countdown to 24 December")
        eq("countdown", b.intent)
        eq("2026-12-24", b.text("date"))
        eq("24 December", b.title)
        eq("2027-03-01", described("days until March 1st").text("date"))
        eq("2026-11-12", described("countdown to 12 nov").text("date"))
        eq("2027-11-11", described("countdown to 11 nov").text("date"))
        eq("2028-02-29", described("countdown to 29 Feb 2028").text("date"))
        eq("2026-12-25", described("how long until christmas").text("date"))
        eq("2027-06-05", described("countdown to 05.06.2027").text("date"))
        eq("2027-01-15", described("days till 2027-01-15 trip").text("date"))
        yes("date" in refused("countdown to my holiday"))
        yes("date" in refused("countdown to 31 feb"))
    }
    test("counters") {
        val b = described("track 8 glasses of water a day")
        eq("tracker", b.intent)
        eq(8.0, b.number("goal"))
        eq("glasses", b.text("unit"))
        eq("Water today", b.title)
        eq("ring", b.layout)
        eq(null, described("a counter").number("goal"))
        eq("weekly", described("count my push-ups, 100 per week").text("reset"))
    }
    test("checklists, timers, clocks and the rest") {
        val todo = described("Shopping list: milk, bread, eggs")
        eq(listOf("milk", "bread", "eggs"), todo.show)
        eq("Shopping list", todo.title)
        yes("colon" in refused("a checklist please"))
        eq(5.0, described("5 minute timer for tea").number("minutes"))
        yes("minutes" in refused("a timer"))
        eq("Asia/Tokyo", described("world clock for Tokyo").text("zone"))
        eq(null, described("a clock").text("zone"))
        eq("battery", described("show my battery").intent)
        eq(listOf("wind", "rain"), described("weather with wind and rain").show)
        eq("agenda", described("my next meetings").intent)
        eq("stoic", described("a stoic quote every day").text("topic"))
        eq("EUR" to "TRY", described("EUR to TRY rate").let { it.text("base") to it.text("quote") })
        eq("EUR" to "TRY", described("euro to lira").let { it.text("base") to it.text("quote") })
        eq("https://example.org/feed.xml", described("headlines from https://example.org/feed.xml.").text("url"))
        yes("https" in refused("news headlines"))
        eq("Buy flowers", described("note: buy flowers").title)
        eq("dashboard", described("an overview dashboard").intent)
    }
    test("size hints") {
        val b = described("battery widget 4x1")
        eq(4 to 1, b.cols to b.rows)
        eq(3 to 2, described("a clock, 3 by 2").let { it.cols to it.rows })
        eq(8.0, described("track 8 glasses 2x2").number("goal"))
    }
    test("says so when a request is outside the rules") {
        yes("chatbot" in refused("a widget that orders pizza"))
        yes("Describe" in refused("   "))
    }
    test("everything the rules accept composes into a valid widget") {
        val asks = listOf(
            "Countdown to 24 December", "track 8 glasses of water a day", "a counter", "Shopping list: milk, bread, eggs",
            "5 minute timer for tea", "world clock for Tokyo", "a clock", "show my battery", "weather with wind and rain",
            "my next meetings", "a stoic quote every day", "EUR to TRY rate", "headlines from https://example.org/feed.xml",
            "note: {it} }} buy 'flowers'", "an overview dashboard", "todo: {a}, }b{, c", "countdown to christmas 5x5", "battery 1x1",
        )
        for (a in asks) ok(described(a))
    }
    test("random descriptions never crash the rules") {
        val r = Random(9)
        val bits = listOf("countdown", "to", "24", "dec", "timer", "5", "min", "track", "glasses", "list:", ",", "clock", "tokyo", "eur", "usd", "news", "https://a.b/c", "note", "{", "}", "2x9", "3x3", "😀", "31.02.2027", "9999-99-99")
        repeat(20_000) {
            val text = List(r.nextInt(8)) { bits[r.nextInt(bits.size)] }.joinToString(" ")
            val d = Describe.fromText(text, TODAY)
            if (d is Described.Ok) {
                val out = Composer.compose(d.brief)
                yes(out is SpecResult.Ok, "\"$text\" -> ${(out as? SpecResult.Rejected)?.problems}")
            }
        }
    }
}

private val prompt = suite("chatbot prompt") {
    test("carries the request and the live vocabulary") {
        val p = ChatPrompt.build("a widget for my bike rides")
        yes("a widget for my bike rides" in p)
        for (word in listOf("stepper", "surfaceVariant", "coalesce", "open_url", "calendar", "15m")) yes(word in p, word)
        yes(p.length < 11_000, "prompt is ${p.length} characters")
        yes("now.temp" in p && "hourly[].rainChance" in p && "events[].title" in p)
        yes("directions_bike" in p && "wb_sunny" in p)
    }
    test("the example inside the prompt is itself a valid widget") {
        val p = ChatPrompt.build("x")
        val example = p.substring(p.indexOf("EXAMPLE") + 8).trim()
        yes(SpecReader.read(example) is SpecResult.Ok)
    }
}

fun main() = runSuites(composing, briefs, rules, prompt)
