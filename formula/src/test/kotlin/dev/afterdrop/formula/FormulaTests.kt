package dev.afterdrop.formula

import dev.afterdrop.formula.Value.Bool
import dev.afterdrop.formula.Value.ListV
import dev.afterdrop.formula.Value.Null
import dev.afterdrop.formula.Value.Num
import dev.afterdrop.formula.Value.Obj
import dev.afterdrop.formula.Value.Str
import dev.afterdrop.testkit.eq
import dev.afterdrop.testkit.fails
import dev.afterdrop.testkit.runSuites
import dev.afterdrop.testkit.suite
import dev.afterdrop.testkit.yes
import java.time.ZoneId
import java.util.Locale
import java.util.Random

// 2026-11-11T14:30:00Z, a Wednesday.
private const val NOW = 1_794_407_400_000L
private val BERLIN = ZoneId.of("Europe/Berlin")

private fun obj(vararg f: Pair<String, Value>) = Obj(linkedMapOf(*f))
private fun list(vararg v: Value) = ListV(v.toList())
private fun n(v: Number) = Num(v.toDouble())

private val DATA: Map<String, Value> = mapOf(
    "x" to n(7), "y" to n(2), "zero" to n(0), "name" to Str("Mert"), "done" to Bool(false),
    "wx" to obj(
        "now" to obj("wind" to n(18.4), "gust" to n(31)),
        "hourly" to list(obj("rainChance" to n(10)), obj("rainChance" to n(60)), obj("rainChance" to n(35))),
    ),
    "cal" to obj(
        "events" to list(
            obj("title" to Str("Standup"), "start" to n(NOW + 30 * 60_000), "allDay" to Bool(false)),
            obj("title" to Str("Holiday"), "start" to n(NOW + 86_400_000), "allDay" to Bool(true)),
        )
    ),
    "nothing" to Null,
)

private fun ctx(zone: ZoneId = BERLIN, now: Long = NOW) = EvalContext(now, zone, Locale.ROOT) { DATA[it] }
private fun ev(src: String, c: EvalContext = ctx()) = Evaluator.eval(Parser.parse(src), c)
private fun show(src: String) = Evaluator.render(Template.parse(src), ctx())
private val ENV: (String) -> Type? = { name ->
    when (val v = DATA[name]) {
        null -> null
        is Num -> Type.NUM
        is Str -> Type.STR
        is Bool -> Type.BOOL
        is ListV -> Type.LIST
        is Obj -> Type.OBJ
        Null -> Type.ANY
    }
}

private fun issues(src: String) = Checker.analyze(Parser.parse(src), ENV).issues.map { it.code }

private val grammar = suite("grammar") {
    val accept = listOf(
        "1 + 2 * 3", "a ? b : c ? d : e", "!done && (x >= 3 || y != 'no')", "-x % 2",
        "first(cal.events).title", "list[0].name", "fmt(now(), 'HH:mm')", "it.start",
    )
    val reject = listOf("1 +", "a ? b", "x = 1", "foo(", "'unterminated", "a..b", "1 2", "x; y", "a => b", "`cmd`")
    for (src in accept) test("accepts $src") { Parser.parse(src) }
    for (src in reject) test("rejects $src") { fails<FormulaError>(src) { Parser.parse(src) } }

    test("rejects more shapes") {
        for (src in listOf("", "  ", "()", "a b", "1 < 2 < 3", "f(,)", "f(1,)", "a.", "a[", "a[]", "'\\q'", "1.", "x ? : y", "\"double\"", "a.1")) {
            fails<FormulaError>(src) { Parser.parse(src) }
        }
    }
    test("precedence") {
        eq("(1 + (2 * 3))", Parser.parse("1 + 2 * 3").print())
        eq("((1 - 2) - 3)", Parser.parse("1 - 2 - 3").print())
        eq("(a ? b : (c ? d : e))", Parser.parse("a ? b : c ? d : e").print())
        eq("((!a) && ((b + 1) > 2))", Parser.parse("!a && b + 1 > 2").print())
        eq("((-a) * b)", Parser.parse("-a * b").print())
        eq("(a || (b && c))", Parser.parse("a || b && c").print())
        eq("f(a.b[0].c, (1 + 2))", Parser.parse("f(a.b[0].c, 1+2)").print())
    }
    test("string escapes") {
        eq(Str("it's a \\ \n"), ev("'it\\'s a \\\\ \\n'"))
    }
    test("length and depth limits") {
        fails<FormulaError> { Parser.parse("1" + " + 1".repeat(80)) }
        fails<FormulaError> { Parser.parse("(".repeat(60) + "1" + ")".repeat(60)) }
        fails<FormulaError> { Parser.parse("-".repeat(200) + "1") }
    }
}

private val evaluation = suite("evaluation") {
    test("arithmetic") {
        eq(n(7), ev("1 + 2 * 3"))
        eq(n(3.5), ev("x / y"))
        eq(n(1), ev("x % y"))
        eq(n(-7), ev("-x"))
        eq(n(0.1 + 0.2), ev("0.1 + 0.2"))
    }
    test("failures become null, never exceptions") {
        eq(Null, ev("x / zero"))
        eq(Null, ev("x % zero"))
        eq(Null, ev("name * 2"))
        eq(Null, ev("missing.deep.field"))
        eq(Null, ev("wx.hourly[99]"))
        eq(Null, ev("wx.hourly[0.5]"))
        eq(Null, ev("x < name"))
        eq(Null, ev("nothing + 1"))
        eq(Null, ev("unknownFn(1)"))
        eq(Null, ev("round()"))
        eq(Null, ev("fmt(now(), 'not a {pattern')"))
        eq(Null, ev("clamp(1, 5, 2)"))
    }
    test("text") {
        eq(Str("Hi Mert"), ev("'Hi ' + name"))
        eq(Str("7 items"), ev("x + ' items'"))
        eq(Str("MERT"), ev("upper(name)"))
        eq(n(4), ev("len(name)"))
        eq(Str("18.4"), ev("num(wx.now.wind, 1)"))
        eq(Str("18"), ev("num(wx.now.wind)"))
        eq(Str("0"), ev("num(0 - 0.2, 0)"))
        eq(Str("45%"), ev("pct(0.45)"))
        eq(Str("x"), ev("trim('  x ')"))
    }
    test("logic and comparison") {
        eq(Bool(true), ev("!done && (x >= 3 || y != 'no')"))
        eq(Bool(true), ev("name == 'Mert'"))
        eq(Bool(true), ev("nothing == missing"))
        eq(Bool(true), ev("'a' < 'b'"))
        eq(Str("yes"), ev("x > y ? 'yes' : 'no'"))
        eq(Bool(false), ev("nothing || zero || ''"))
        eq(Bool(true), ev("zero == 0 && !nothing"))
    }
    test("short circuit skips the right side") {
        var reads = 0
        val c = EvalContext(NOW) { reads++; Bool(false) }
        Evaluator.eval(Parser.parse("a && b && c"), c)
        eq(1, reads)
    }
    test("lists") {
        eq(n(3), ev("count(wx.hourly)"))
        eq(n(0), ev("count(nothing)"))
        eq(list(n(10), n(60), n(35)), ev("pluck(wx.hourly, 'rainChance')"))
        eq(n(60), ev("max(pluck(wx.hourly, 'rainChance'))"))
        eq(n(10), ev("min(pluck(wx.hourly, 'rainChance'))"))
        eq(n(105), ev("sum(pluck(wx.hourly, 'rainChance'))"))
        eq(n(35), ev("avg(pluck(wx.hourly, 'rainChance'))"))
        eq(Str("Standup"), ev("first(cal.events).title"))
        eq(Str("Holiday"), ev("last(cal.events).title"))
        eq(n(1), ev("count(take(cal.events, 1))"))
        eq(n(0), ev("count(skip(cal.events, 5))"))
        eq(n(2), ev("count(take(cal.events, 99))"))
        eq(n(0), ev("count(take(cal.events, 0 - 3))"))
        eq(Str("Holiday"), ev("first(where(cal.events, 'allDay')).title"))
        eq(Str("Standup"), ev("first(where(cal.events, 'title', 'Standup')).title"))
        eq(Null, ev("first(skip(cal.events, 2))"))
        eq(Null, ev("avg(take(cal.events, 0))"))
        eq(n(2), ev("min(x, y)"))
        eq(Str("fallback"), ev("coalesce(nothing, missing, 'fallback')"))
    }
    test("maths functions") {
        eq(n(3), ev("round(2.5)"))
        eq(n(2.35), ev("round(2.345, 2)"))
        eq(n(-2), ev("round(0 - 2.5)"))
        eq(n(2), ev("floor(2.9)"))
        eq(n(3), ev("ceil(2.1)"))
        eq(n(5), ev("abs(0 - 5)"))
        eq(n(1), ev("clamp(9, 0, 1)"))
        eq(n(212), ev("c2f(100)"))
        eq(n(100), ev("f2c(212)"))
    }
    test("time in Berlin") {
        eq(n(NOW), ev("now()"))
        // Berlin is UTC+1 in November: local midnight is 23:00Z the day before.
        eq(n(1_794_351_600_000L), ev("today()"))
        eq(Str("15:30"), ev("fmt(now(), 'HH:mm')"))
        eq(Str("Wed 11 Nov"), ev("fmt(now(), 'EEE d MMM')"))
        eq(n(43), ev("days(today(), date('2026-12-24'))"))
        eq(n(0), ev("days(now(), today())"))
        eq(n(-1), ev("days(now(), date('2026-11-10'))"))
        eq(n(2), ev("hours(now(), now() + 9000000)"))
        eq(n(30), ev("minutes(now(), first(cal.events).start)"))
        eq(Str("in 30 min"), ev("rel(first(cal.events).start)"))
        eq(Str("in 24 h"), ev("rel(last(cal.events).start)"))
        eq(Str("3 d ago"), ev("rel(now() - 3 * 86400000)"))
        eq(Str("now"), ev("rel(now() + 20000)"))
        eq(Null, ev("date('24.12.2026')"))
        eq(ev("today()"), ev("startOfDay(now())"))
    }
    test("calendar days respect daylight saving") {
        // Europe/Berlin left summer time on 2026-10-25, a 25-hour day.
        eq(n(1), ev("days(date('2026-10-25'), date('2026-10-26'))"))
        eq(n(25), ev("hours(date('2026-10-25'), date('2026-10-26'))"))
        eq(ev("date('2026-10-26')"), ev("addDays(date('2026-10-25'), 1)"))
    }
    test("midnight boundary moves the countdown") {
        val before = ctx(now = 1_794_351_599_000L) // 2026-11-10 23:59:59 Berlin
        val after = ctx(now = 1_794_351_601_000L) // 2026-11-11 00:00:01 Berlin
        eq(n(44), ev("days(today(), date('2026-12-24'))", before))
        eq(n(43), ev("days(today(), date('2026-12-24'))", after))
    }
    test("zone changes the local day") {
        val tokyo = ctx(ZoneId.of("Asia/Tokyo"))
        eq(Str("23:30"), ev("fmt(now(), 'HH:mm')", tokyo))
        eq(Str("11"), ev("fmt(today(), 'd')", tokyo))
    }
    test("out-of-range instants are null") {
        eq(Null, ev("fmt(9000000000000000000, 'yyyy')"))
        eq(Null, ev("addDays(now(), 99999999)"))
    }
}

private val templates = suite("templates") {
    test("renders text and formulas") {
        eq("18 km/h", show("{num(wx.now.wind, 0)} km/h"))
        eq("Hi Mert!", show("Hi {name}!"))
        eq("plain", show("plain"))
        eq("", show(""))
    }
    test("literal braces") {
        eq("{x} is 7}", show("{{x}} is {x}}}"))
    }
    test("a brace inside a string does not end the formula") {
        eq("a}b", show("{'a}b'}"))
        eq("it's", show("{'it\\'s'}"))
    }
    test("missing values show a dash") {
        eq("– km/h", show("{wx.now.nope} km/h"))
        eq(" km/h", show("{coalesce(wx.now.nope, '')} km/h"))
    }
    test("rejects broken templates") {
        for (t in listOf("{wx.now.wind", "oops }", "{}", "{1 +}", "{a{b}}", "{'open}")) {
            fails<FormulaError>(t) { Template.parse(t) }
        }
    }
    test("error position points into the template") {
        val e = fails<FormulaError> { Template.parse("Wind: {1 + }") }
        eq(11, e.pos)
    }
}

private val checking = suite("checker") {
    test("clean formulas have no issues") {
        for (src in listOf(
            "wx.now.wind < 25 && wx.hourly[0].rainChance < 40", "clamp(x / y, 0, 1)", "x + ' items'",
            "days(today(), date('2026-12-24'))", "pluck(take(wx.hourly, 8), 'rainChance')",
            "done ? 'a' : 'b'", "nothing + 1", "fmt(first(cal.events).start, 'HH:mm')", "min(x, y, 3)",
        )) eq(emptyList<String>(), issues(src), src)
    }
    test("reports unknown names and functions") {
        eq(listOf("unknown_name"), issues("ghost + 1"))
        eq(listOf("unknown_function"), issues("exec('rm -rf')"))
        eq(listOf("unknown_name", "unknown_function"), issues("launch(missile)"))
    }
    test("reports arity") {
        eq(listOf("arity"), issues("round()"))
        eq(listOf("arity"), issues("clamp(1, 2)"))
        eq(listOf("arity"), issues("now(1)"))
        eq(listOf("arity"), issues("min()"))
    }
    test("reports impossible types") {
        eq(listOf("type"), issues("name * 2"))
        eq(listOf("type"), issues("-name"))
        eq(listOf("type"), issues("x < name"))
        eq(listOf("type"), issues("upper(x)"))
        eq(listOf("type"), issues("x.field"))
        eq(listOf("type"), issues("done + 1"))
        eq(listOf("type"), issues("count(name)"))
    }
    test("infers result types") {
        val type = { s: String -> Checker.analyze(Parser.parse(s), ENV).type }
        eq(Type.NUM, type("x * 2"))
        eq(Type.STR, type("x + 'a'"))
        eq(Type.BOOL, type("x > 1 && done"))
        eq(Type.ANY, type("wx.now.wind"))
        eq(Type.STR, type("done ? 'a' : 'b'"))
        eq(Type.ANY, type("done ? 'a' : 1"))
        eq(Type.LIST, type("take(wx.hourly, 2)"))
    }
    test("collects names, functions and clock use") {
        val a = Checker.analyze(Parser.parse("days(today(), cal.events[0].start) + x"), ENV)
        eq(setOf("cal", "x"), a.names)
        eq(setOf("days", "today"), a.functions)
        yes(a.readsClock)
        yes(!Checker.analyze(Parser.parse("fmt(x, 'HH')"), ENV).readsClock)
        yes(Checker.analyze(Parser.parse("rel(x)"), ENV).readsClock)
    }
    test("every library function is callable with its minimum arguments") {
        for (name in Library.names) {
            val fn = Library[name]!!
            val args = fn.params.take(fn.minArgs).joinToString(", ") {
                when (it) {
                    Type.NUM -> "1"
                    Type.STR -> "'a'"
                    Type.LIST -> "wx.hourly"
                    else -> "x"
                }
            }
            eq(emptyList<String>(), issues("$name($args)"), name)
            ev("$name($args)")
        }
        eq(37, Library.names.size)
    }
}

/** Seeded generators: the same inputs on every run. */
private val properties = suite("properties") {
    val atoms = listOf("1", "2.5", "0", "'s'", "''", "true", "false", "x", "y", "zero", "name", "done", "wx", "cal", "nothing", "ghost")
    val fns = Library.names.toList() + "nope"
    val ops = listOf("+", "-", "*", "/", "%", "==", "!=", "<", "<=", ">", ">=", "&&", "||")

    fun gen(r: Random, depth: Int): String {
        if (depth <= 0 || r.nextInt(5) == 0) return atoms[r.nextInt(atoms.size)]
        return when (r.nextInt(8)) {
            0 -> "(" + gen(r, depth - 1) + " " + ops[r.nextInt(ops.size)] + " " + gen(r, depth - 1) + ")"
            1 -> "(" + (if (r.nextBoolean()) "!" else "-") + gen(r, depth - 1) + ")"
            2 -> "(" + gen(r, depth - 1) + " ? " + gen(r, depth - 1) + " : " + gen(r, depth - 1) + ")"
            3 -> gen(r, depth - 1) + "." + listOf("now", "wind", "events", "title", "start", "q").let { it[r.nextInt(it.size)] }
            4 -> gen(r, depth - 1) + "[" + gen(r, depth - 1) + "]"
            else -> fns[r.nextInt(fns.size)] + "(" + (0 until r.nextInt(4)).joinToString(", ") { gen(r, depth - 1) } + ")"
        }
    }

    test("evaluation and checking are total on 20,000 generated formulas") {
        val r = Random(20261111)
        var evaluated = 0
        repeat(20_000) {
            val src = gen(r, 4)
            if (src.length <= Parser.MAX_LENGTH) {
                val expr = try {
                    Parser.parse(src)
                } catch (e: FormulaError) {
                    throw AssertionError("generated formula did not parse: $src (${e.message})")
                }
                Evaluator.eval(expr, ctx())
                Checker.analyze(expr, ENV)
                evaluated++
            }
        }
        yes(evaluated > 10_000, "only $evaluated formulas were short enough")
    }
    test("print then parse is stable") {
        val r = Random(7)
        repeat(3_000) {
            val src = gen(r, 3)
            if (src.length <= 120) {
                val once = Parser.parse(src).print()
                if (once.length <= Parser.MAX_LENGTH) eq(once, Parser.parse(once).print(), src)
            }
        }
    }
    test("a formula with no checker issues never evaluates an unknown function") {
        val r = Random(99)
        repeat(5_000) {
            val src = gen(r, 3)
            if (src.length <= Parser.MAX_LENGTH) {
                val a = Checker.analyze(Parser.parse(src), ENV)
                if (a.issues.isEmpty()) yes(a.functions.all { it in Library.names } && "ghost" !in a.names, src)
            }
        }
    }
    test("the parser throws only FormulaError on 50,000 random strings") {
        val r = Random(42)
        val alphabet = "ab1 .,'()[]{}?:+-*/%<>=!&|\\\"`;_\né中"
        repeat(50_000) {
            val s = String(CharArray(r.nextInt(40)) { alphabet[r.nextInt(alphabet.length)] })
            try {
                Parser.parse(s)
            } catch (_: FormulaError) {
            }
            try {
                Template.parse(s)
            } catch (_: FormulaError) {
            }
        }
    }
}

fun main() = runSuites(grammar, evaluation, templates, checking, properties)
