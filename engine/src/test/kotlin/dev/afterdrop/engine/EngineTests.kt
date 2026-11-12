package dev.afterdrop.engine

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
import java.time.ZoneId
import java.util.Locale

private const val NOW = 1_794_407_400_000L // 2026-11-11 15:30 in Berlin, a Wednesday
private const val DAY = 86_400_000L
private val BERLIN = ZoneId.of("Europe/Berlin")

private fun spec(name: String): WidgetSpec =
    (SpecReader.read(File(goldenDir(), "valid/$name.json").readText()) as SpecResult.Ok).spec

private fun data(json: String): Map<String, Value> = (Json.parse(json).toValue() as Value.Obj).fields

private fun input(
    s: WidgetSpec, data: Map<String, Value> = emptyMap(), vars: Map<String, Value>? = null,
    size: SizeClass = SizeClass.L, now: Long = NOW,
) = RenderInput(s, data, vars ?: Vars.current(s, emptyMap(), now, BERLIN), now, BERLIN, Locale.ROOT, size)

private fun texts(r: RNode) = r.all().mapNotNull { it.text }.toList()

private val WX = data(
    """{"wx":{"now":{"wind":18.4,"gust":31},"next3h":{"rainChance":35,"until":1794418200000},
       "hourly":[{"rainChance":10},{"rainChance":60},{"rainChance":null},{"rainChance":35}]}}"""
)

private val resolving = suite("resolve") {
    test("ride check with data") {
        val r = Engine.resolve(input(spec("ride-check"), WX))
        eq(listOf("18 km/h", "gusts 31", "35% rain by 18:30", "Good to ride"), texts(r))
        eq(listOf(10.0, 60.0, 35.0), r.all().first { it.type == "chart" }.series)
        eq(Tap.OpenSource("wx", "weather"), r.tap)
    }
    test("missing data shows dashes and never throws") {
        val r = Engine.resolve(input(spec("ride-check")))
        eq(listOf("– km/h", "gusts –", "–% rain by –", "Maybe not today"), texts(r))
        eq(emptyList<Double>(), r.all().first { it.type == "chart" }.series)
    }
    test("size class hides nodes marked with from") {
        val s = spec("ride-check")
        yes(texts(Engine.resolve(input(s, WX, size = SizeClass.S))).none { it.startsWith("gusts") })
        yes(Engine.resolve(input(s, WX, size = SizeClass.S)).all().any { it.type == "chart" })
        yes(Engine.resolve(input(s, WX, size = SizeClass.XS)).all().none { it.type == "chart" })
    }
    test("size classes from widget dimensions") {
        eq(SizeClass.XS, SizeClass.of(80, 80))
        eq(SizeClass.S, SizeClass.of(170, 170))
        eq(SizeClass.S, SizeClass.of(300, 70))
        eq(SizeClass.M, SizeClass.of(300, 170))
        eq(SizeClass.L, SizeClass.of(380, 260))
        eq(SizeClass.XS, SizeClass.of(100, 60))
        eq(SizeClass.XS, SizeClass.of(0, 0))
    }
    test("if hides nodes") {
        val s = spec("battery")
        eq(listOf("15%", "Plug in soon"), texts(Engine.resolve(input(s, data("""{"bat":{"level":15,"charging":false}}""")))))
        eq(listOf("15%", "Charging"), texts(Engine.resolve(input(s, data("""{"bat":{"level":15,"charging":true}}""")))))
        eq(0.15, Engine.resolve(input(s, data("""{"bat":{"level":15,"charging":true}}"""))).all().first { it.type == "progress" }.fraction)
    }
    test("list expands items, honours limit, and shows the empty text") {
        val s = spec("next-up")
        val events = (1..9).joinToString(",") { """{"title":"E$it","start":${NOW + it * 3_600_000L}}""" }
        val r = Engine.resolve(input(s, data("""{"cal":{"events":[$events]}}""")))
        val list = r.all().first { it.type == "list" }
        eq(4, list.kids.size)
        eq(listOf("16:30", "E1"), texts(list.kids[0]))
        eq("r.1#2", list.kids[2].key)
        eq(Tap.OpenSource("cal", "calendar"), list.kids[0].tap)
        val empty = Engine.resolve(input(s, data("""{"cal":{"events":[]}}"""))).all().first { it.type == "list" }
        eq(0, empty.kids.size)
        eq("Nothing scheduled", empty.text)
        eq("Nothing scheduled", Engine.resolve(input(s)).all().first { it.type == "list" }.text)
    }
    test("countdown and calc chain") {
        eq(listOf("43", "days to go", "Thu 24 Dec"), texts(Engine.resolve(input(spec("holiday-countdown")))))
    }
    test("progress is clamped to 0..1") {
        val s = spec("water-today")
        eq(1.0, Engine.resolve(input(s, vars = mapOf("glasses" to Value.Num(99.0)))).all().first { it.type == "progress" }.fraction)
        eq(0.0, Engine.resolve(input(s, vars = mapOf("glasses" to Value.Num(-5.0)))).all().first { it.type == "progress" }.fraction)
    }
    test("every golden spec resolves at every size with no data") {
        for (f in File(goldenDir(), "valid").listFiles()!!.filter { it.name.endsWith(".json") }) {
            val s = spec(f.name.removeSuffix(".json"))
            for (size in SizeClass.entries) {
                val r = Engine.resolve(input(s, size = size))
                yes(r.all().map { it.key }.toList().let { it.size == it.toSet().size }, "${f.name}: keys must be unique")
            }
        }
    }
}

private val acting = suite("actions") {
    test("stepper counts and clamps") {
        val s = spec("water-today")
        var vars = Vars.current(s, emptyMap(), NOW, BERLIN)
        val key = Engine.resolve(input(s)).all().first { it.type == "stepper" }.key
        repeat(3) { vars = Engine.act(input(s, vars = vars), key, "inc")!! }
        eq(Value.Num(3.0), vars["glasses"])
        eq(listOf("3 of 8 glasses", "Glasses"), texts(Engine.resolve(input(s, vars = vars))))
        eq(0.375, Engine.resolve(input(s, vars = vars)).all().first { it.type == "progress" }.fraction)
        repeat(30) { vars = Engine.act(input(s, vars = vars), key, "inc") ?: vars }
        eq(Value.Num(20.0), vars["glasses"])
        eq(null, Engine.act(input(s, vars = vars), key, "inc"))
        repeat(30) { vars = Engine.act(input(s, vars = vars), key, "dec") ?: vars }
        eq(Value.Num(0.0), vars["glasses"])
    }
    test("toggle and check flip") {
        val s = spec("focus-toggle")
        val key = Engine.resolve(input(s)).all().first { it.type == "toggle" }.key
        val on = Engine.act(input(s), key, "toggle")!!
        eq(Value.Bool(true), on["focus"])
        eq("Heads down", texts(Engine.resolve(input(s, vars = on))).first())
        eq(Value.Bool(false), Engine.act(input(s, vars = on), key, "toggle")!!["focus"])
    }
    test("set action evaluates its formula") {
        val s = spec("tea-timer")
        val idle = Engine.resolve(input(s))
        eq(listOf("Ready when you are", "Start 3 min"), texts(idle))
        val key = idle.all().first { it.type == "button" }.key
        eq(Tap.SetVar, idle.all().first { it.type == "button" }.tap)
        val vars = Engine.act(input(s), key, "tap")!!
        eq(Value.Num(NOW + 180_000.0), vars["endsAt"])
        val running = Engine.resolve(input(s, vars = vars))
        eq(NOW + 180_000.0, running.all().first { it.type == "timer" }.instant)
        yes(texts(Engine.resolve(input(s, vars = vars, now = NOW + 200_000))).contains("Ready when you are"))
    }
    test("wrong ops and unknown keys change nothing") {
        val s = spec("water-today")
        val key = Engine.resolve(input(s)).all().first { it.type == "stepper" }.key
        eq(null, Engine.act(input(s), key, "toggle"))
        eq(null, Engine.act(input(s), key, "tap"))
        eq(null, Engine.act(input(s), "r.99", "inc"))
        eq(null, Engine.act(input(s), key, "explode"))
    }
    test("ask and link taps are described, not performed") {
        eq(Tap.Ask("kg", "Weight in kg", true), Engine.resolve(input(spec("weight-log"))).tap)
        val taps = Engine.resolve(input(spec("morning-dashboard"))).all().mapNotNull { it.tap }.toList()
        eq(listOf(Tap.OpenUrl("https://example.org/timetable"), Tap.OpenApp("com.example.maps")), taps)
    }
}

private val variables = suite("variables") {
    val s = spec("water-today") // glasses resets daily
    val weekly = spec("shopping-checks") // resets weekly
    fun stored(v: Value, at: Long) = mapOf("glasses" to StoredVar(v, at))
    test("starting values") { eq(mapOf("glasses" to Value.Num(0.0)), Vars.current(s, emptyMap(), NOW, BERLIN)) }
    test("a value set today is kept") {
        eq(Value.Num(5.0), Vars.current(s, stored(Value.Num(5.0), NOW - 3_600_000), NOW, BERLIN)["glasses"])
    }
    test("daily reset happens at local midnight, not after 24 hours") {
        val lateYesterday = 1_794_351_540_000L // 2026-11-10 23:59 Berlin
        eq(Value.Num(0.0), Vars.current(s, stored(Value.Num(5.0), lateYesterday), lateYesterday + 120_000, BERLIN)["glasses"])
        eq(Value.Num(5.0), Vars.current(s, stored(Value.Num(5.0), lateYesterday - 3_600_000), lateYesterday, BERLIN)["glasses"])
    }
    test("weekly reset happens on Monday") {
        val st = mapOf("milk" to StoredVar(Value.Bool(true), NOW))
        eq(Value.Bool(true), Vars.current(weekly, st, NOW + 4 * DAY, BERLIN)["milk"]) // Sunday
        eq(Value.Bool(false), Vars.current(weekly, st, NOW + 5 * DAY, BERLIN)["milk"]) // Monday
    }
    test("a stored value of the wrong type falls back to the start value") {
        eq(Value.Num(0.0), Vars.current(s, stored(Value.Str("five"), NOW), NOW, BERLIN)["glasses"])
    }
    test("whole-number variables round") {
        eq(Value.Num(3.0), Vars.coerce(s.vars.getValue("glasses"), Value.Num(2.6)))
        eq(null, Vars.coerce(s.vars.getValue("glasses"), Value.Null))
    }
    test("storage round trip, and junk decodes to nothing") {
        val m = mapOf("a" to StoredVar(Value.Num(1.5), 10), "b" to StoredVar(Value.Bool(true), 20), "c" to StoredVar(Value.Str("x"), 30))
        val back = Vars.decode(Json.parse(Json.write(Vars.encode(m))))
        eq(m.mapValues { it.value.value to it.value.setAt }, back.mapValues { it.value.value to it.value.setAt })
        eq(emptyMap<String, StoredVar>(), Vars.decode(Json.parse("[1]")))
        eq(emptyMap<String, StoredVar>(), Vars.decode(Json.parse("""{"a":5,"b":{"v":[],"at":1},"c":{"v":1}}""")))
        eq(emptyMap<String, StoredVar>(), Vars.decode(null))
    }
}

private val fitting = suite("fit") {
    fun fit(name: String, height: Int, width: Int = 180, fs: Double = 1.0) = Fit.fit(Engine.resolve(input(spec(name), size = SizeClass.S)), width, height, fs)
    fun types(f: Fitted) = f.root.all().map { it.type }.toList()
    test("a widget with room is left alone") {
        val f = fit("water-today", 180)
        eq(0, f.scale)
        eq(listOf("col", "progress", "text", "stepper"), types(f))
    }
    test("a short widget keeps its control and drops decoration, then text") {
        val f = fit("water-today", 70)
        eq(listOf("col", "stepper"), types(f))
        yes(Fit.height(f.root, f.scale, true) <= 70, "still too tall")
        eq(listOf("col", "text", "stepper"), types(fit("water-today", 110)))
    }
    test("the timer keeps its start button at one row") {
        eq(listOf("col", "button"), types(fit("tea-timer", 70)))
    }
    test("text and padding shrink before anything is dropped") {
        val f = fit("holiday-countdown", 56)
        yes(f.scale > 0, "expected a smaller scale")
        eq(4, f.root.all().count { it.type == "text" || it.type == "col" })
    }
    test("checklists drop rows from the end") {
        val f = fit("shopping-checks", 110)
        val labels = f.root.all().filter { it.type == "check" }.mapNotNull { it.text }.toList()
        eq(listOf("Milk", "Bread"), labels)
    }
    test("something always remains, even with no room at all") {
        for (f in File(goldenDir(), "valid").listFiles()!!.filter { it.name.endsWith(".json") }) {
            for (h in listOf(0, 20, 40, 70, 110, 180, 400)) {
                val out = fit(f.name.removeSuffix(".json"), h)
                yes(out.root.all().count() >= 1, f.name)
                if (h >= 110) yes(Fit.height(out.root, out.scale, true) <= h || out.root.all().count() <= 3, "${f.name} at $h dp is estimated at ${Fit.height(out.root, out.scale, true)}")
            }
        }
    }
    test("a row too wide for the widget drops text before controls") {
        eq(listOf("row", "toggle"), types(fit("focus-toggle", 110, width = 150)))
        eq(listOf("row", "text", "toggle"), types(fit("focus-toggle", 110, width = 330)))
        val dash = fit("morning-dashboard", 250, width = 150).root.all().filter { it.type == "button" }.mapNotNull { it.text }.toList()
        eq(listOf("Timetable"), dash)
        eq(2, fit("morning-dashboard", 250, width = 330).root.all().count { it.type == "button" })
    }
    test("a larger system font drops more") {
        val normal = fit("shopping-checks", 180).root.all().count()
        val large = fit("shopping-checks", 180, fs = 1.6).root.all().count()
        yes(large <= normal, "large=$large normal=$normal")
        yes(Fit.height(fit("battery", 70, fs = 1.5).root, 2, true, 1.5) <= 70 || fit("battery", 70, fs = 1.5).root.all().count() <= 3)
    }
    test("wrapped text counts as two lines, and a control is never dropped for its label width") {
        val large = fit("tea-timer", 80, width = 180, fs = 1.5)
        eq(listOf("col", "button"), types(large))
        val ride = fit("ride-check", 180, width = 180, fs = 1.5)
        yes(Fit.height(ride.root, ride.scale, true, 1.5, 180.0) <= 180, "ride-check is estimated taller than its widget")
    }
    test("keys survive fitting, so taps still find their node") {
        val s = spec("water-today")
        val key = fit("water-today", 70).root.all().first { it.type == "stepper" }.key
        eq(Value.Num(1.0), Engine.act(input(s), key, "inc")!!["glasses"])
    }
}

fun main() = runSuites(resolving, acting, variables, fitting)
