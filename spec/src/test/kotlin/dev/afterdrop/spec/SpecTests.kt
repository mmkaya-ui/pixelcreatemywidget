package dev.afterdrop.spec

import dev.afterdrop.testkit.eq
import dev.afterdrop.testkit.fails
import dev.afterdrop.testkit.goldenDir
import dev.afterdrop.testkit.runSuites
import dev.afterdrop.testkit.suite
import dev.afterdrop.testkit.yes
import java.io.File
import java.util.Random

private typealias Doc = MutableMap<String, Any?>

private val validFiles: List<File> =
    File(goldenDir(), "valid").listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }

@Suppress("UNCHECKED_CAST")
private fun load(name: String): Doc =
    Json.toKotlin(Json.parse(File(goldenDir(), "valid/$name.json").readText())) as Doc

@Suppress("UNCHECKED_CAST")
private fun Any?.m(key: String): Doc = (this as Doc)[key] as Doc

@Suppress("UNCHECKED_CAST")
private fun Any?.l(key: String): MutableList<Any?> = (this as Doc)[key] as MutableList<Any?>

private fun node(vararg f: Pair<String, Any?>): Doc = linkedMapOf(*f)

private fun codes(doc: Any?): List<String> = when (val r = SpecReader.read(Json.fromKotlin(doc))) {
    is SpecResult.Ok -> emptyList()
    is SpecResult.Rejected -> r.problems.map { it.code }
}

private fun codesOf(text: String, lenient: Boolean = false): List<String> = when (val r = SpecReader.read(text, lenient)) {
    is SpecResult.Ok -> emptyList()
    is SpecResult.Rejected -> r.problems.map { it.code }
}

/** Asserts the mutated spec is rejected and that [code] is among the reasons. */
private fun rejected(code: String, base: String = "ride-check", change: (Doc) -> Unit) {
    val doc = load(base)
    change(doc)
    val got = codes(doc)
    yes(code in got, "expected $code, got $got")
}

private val golden = suite("golden corpus") {
    test("has at least 15 valid specs") { yes(validFiles.size >= 15, "only ${validFiles.size}") }
    for (f in validFiles) {
        test("accepts ${f.name}") {
            when (val r = SpecReader.read(f.readText())) {
                is SpecResult.Ok -> {}
                is SpecResult.Rejected -> throw AssertionError(r.problems.joinToString("; "))
            }
        }
    }
    test("write then read gives the same JSON") {
        for (f in validFiles) {
            val json = Json.parse(f.readText())
            eq(json, Json.parse(Json.write(json)), f.name)
            eq(json, Json.parse(Json.write(json, indent = true)), f.name)
        }
    }
    test("decoded model matches the file") {
        val spec = (SpecReader.read(File(goldenDir(), "valid/next-up.json").readText()) as SpecResult.Ok).spec
        eq("Next up", spec.name)
        eq(4, spec.cols)
        eq(2, spec.rows)
        eq("calendar", spec.sources.getValue("cal").use)
        eq("15m", spec.sources.getValue("cal").every)
        eq("col", spec.root.type)
        val list = spec.root.kids[1]
        eq("list", list.type)
        eq(4, list.int("limit"))
        eq("open_source", list.item!!.tap!!.kind)
        eq("cal", list.item!!.tap!!.text("source"))
        eq(8, spec.root.all().count())
        val water = (SpecReader.read(File(goldenDir(), "valid/water-today.json").readText()) as SpecResult.Ok).spec
        eq("daily", water.vars.getValue("glasses").reset)
        eq(JNumber(0.0), water.vars.getValue("glasses").init)
    }
}

/** The 17 broken specs from the design check, each with the reason it must be refused. */
private val broken = suite("broken specs") {
    test("missing version") { rejected("schema.required") { it.remove("v") } }
    test("unknown node type") { rejected("schema.enum") { it.m("root").l("kids") += node("t" to "webview") } }
    test("raw hex colour on a node") {
        rejected("schema.enum") { (it.m("root").l("kids")[0].l("kids")[0] as Doc)["color"] = "#ff0000" }
    }
    test("link that is http, not https") {
        rejected("schema.pattern") { it.m("root")["tap"] = node("do" to "open_url", "url" to "http://example.com") }
    }
    test("link with an intent: scheme") {
        rejected("schema.pattern") { it.m("root")["tap"] = node("do" to "open_url", "url" to "intent://x#Intent;end") }
    }
    test("eleven children in one container") {
        rejected("schema.max_items") { it.m("root")["kids"] = MutableList(11) { node("t" to "spacer") } }
    }
    test("text node without text") { rejected("schema.required") { it.m("root").l("kids") += node("t" to "text") } }
    test("refresh interval under 15 minutes") { rejected("schema.enum") { it.m("sources").m("wx")["every"] = "1m" } }
    test("seven sources") {
        rejected("schema.max_items") { for (i in 0 until 6) it.m("sources")["s$i"] = node("use" to "battery") }
    }
    test("unknown function") { rejected("lint.unknown_function") { it.m("calc")["x"] = "exec('rm -rf')" } }
    test("unknown name in a formula") { rejected("lint.unknown_name") { it.m("calc")["x"] = "calendar.events" } }
    test("unbalanced formula") { rejected("lint.formula_syntax") { it.m("calc")["x"] = "(wx.now.wind + 1" } }
    test("unclosed brace in a template") {
        rejected("lint.template_syntax") { it.m("root").l("kids") += node("t" to "text", "text" to "{wx.now.wind") }
    }
    test("stepper on an undeclared variable") {
        rejected("lint.unknown_var") { it.m("root").l("kids") += node("t" to "stepper", "var" to "ghost") }
    }
    test("nesting deeper than five levels") {
        rejected("lint.depth") {
            var n: Doc = it.m("root")
            repeat(6) {
                val kid = node("t" to "col", "kids" to mutableListOf<Any?>())
                n["kids"] = mutableListOf<Any?>(kid)
                n = kid
            }
        }
    }
    test("list inside a list") {
        rejected("lint.nested_list", "next-up") {
            it.m("root").l("kids")[1].m("item").l("kids") +=
                node("t" to "list", "of" to "cal.events", "item" to node("t" to "text", "text" to "x"))
        }
    }
    test("calendar value in a request URL") {
        rejected("lint.private_flow", "next-up") {
            it.m("sources")["leak"] = node("use" to "http", "with" to node("url" to "https://x.example/?q={cal.events}"))
        }
    }
}

private val lint = suite("lint") {
    test("private data cannot be laundered through a calc") {
        rejected("lint.private_flow", "next-up") {
            it["calc"] = node("a" to "first(cal.events).title", "b" to "upper(a)")
            it.m("sources")["leak"] = node("use" to "ai", "with" to node("prompt" to "Summarise {b}"))
        }
    }
    test("private data cannot be laundered through a variable") {
        rejected("lint.private_flow", "next-up") {
            it["vars"] = node("memo" to node("type" to "text", "init" to ""))
            it.m("root")["tap"] = node("do" to "set", "var" to "memo", "to" to "first(cal.events).title")
            it.m("sources")["leak"] = node("use" to "feed", "with" to node("url" to "https://x.example/{memo}"))
        }
    }
    test("private data cannot be laundered through a list item") {
        rejected("lint.private_flow", "next-up") {
            it["vars"] = node("memo" to node("type" to "text", "init" to ""))
            it.m("root").l("kids")[1].m("item")["tap"] = node("do" to "set", "var" to "memo", "to" to "it.title")
            it.m("sources")["leak"] = node("use" to "http", "with" to node("q" to node("deep" to mutableListOf<Any?>("{memo}"))))
        }
    }
    test("public data may be sent, and private data may be shown") {
        val doc = load("next-up")
        doc.m("sources")["wx"] = node("use" to "weather")
        doc.m("sources")["tip"] = node("use" to "ai", "with" to node("prompt" to "What to wear at {num(wx.now.temp, 0)} degrees?"))
        eq(emptyList<String>(), codes(doc))
    }
    test("each node type accepts only its own properties") {
        rejected("lint.property_not_allowed") { (it.m("root").l("kids")[3] as Doc)["kids"] = mutableListOf<Any?>() }
        rejected("lint.property_not_allowed") { (it.m("root").l("kids")[2] as Doc)["text"] = "hi" }
        rejected("lint.property_not_allowed") { (it.m("root").l("kids")[2] as Doc)["kind"] = "tonal" }
        rejected("lint.property_not_allowed") { it.m("root").l("kids") += node("t" to "spacer", "label" to "x") }
    }
    test("formulas must give the type the property needs") {
        rejected("lint.type") { (it.m("root").l("kids")[2] as Doc)["values"] = "wx.now.wind > 3" }
        rejected("lint.type") { it.m("root").l("kids") += node("t" to "progress", "value" to "'half'") }
        rejected("lint.type") { it.m("calc")["x"] = "rideable * 2" }
        rejected("lint.type", "water-today") { it.m("root")["tap"] = node("do" to "set", "var" to "glasses", "to" to "'many'") }
    }
    test("argument counts are checked") { rejected("lint.arity") { it.m("calc")["x"] = "clamp(1)" } }
    test("controls must fit their variable") {
        rejected("lint.var_type", "water-today") { it.m("root").l("kids") += node("t" to "toggle", "var" to "glasses") }
        rejected("lint.var_type", "focus-toggle") { it.m("root").l("kids") += node("t" to "stepper", "var" to "focus") }
        rejected("lint.var_type", "focus-toggle") { it.m("root")["tap"] = node("do" to "ask", "var" to "focus") }
        rejected("lint.var_type", "weight-log") { it.m("root").m("tap")["kind"] = "text" }
    }
    test("starting value must match the variable type") {
        rejected("lint.var_init", "water-today") { it.m("vars").m("glasses")["init"] = 1.5 }
        rejected("lint.var_init", "water-today") { it.m("vars").m("glasses")["init"] = "0" }
        rejected("lint.var_init", "focus-toggle") { it.m("vars").m("focus")["init"] = 0 }
    }
    test("calc cycles are refused") {
        rejected("lint.calc_cycle") { it.m("calc")["a"] = "b + 1"; it.m("calc")["b"] = "a + 1" }
        rejected("lint.calc_cycle") { it.m("calc")["a"] = "a" }
    }
    test("names must be unique and not reserved") {
        rejected("lint.duplicate_name") { it.m("calc")["wx"] = "1" }
        rejected("lint.reserved_name") { it.m("calc")["it"] = "1" }
    }
    test("it is only visible inside a list item") {
        rejected("lint.unknown_name", "next-up") { it.m("root").l("kids") += node("t" to "text", "text" to "{it.title}") }
    }
    test("undeclared source in an action") {
        rejected("lint.unknown_source") { it.m("root").m("tap")["source"] = "nope" }
    }
    test("more than 60 nodes") {
        rejected("lint.node_count") {
            it.m("root")["kids"] = MutableList(7) { node("t" to "row", "kids" to MutableList<Any?>(9) { node("t" to "spacer") }) }
        }
    }
    test("node ids must be unique") {
        rejected("lint.duplicate_id") {
            (it.m("root").l("kids")[0] as Doc)["id"] = "a"
            (it.m("root").l("kids")[1] as Doc)["id"] = "a"
        }
    }
    test("clock format and zone must be real") {
        rejected("lint.clock_format", "clock-date") { (it.m("root").l("kids")[0] as Doc)["format"] = "{bad" }
        rejected("lint.clock_format", "clock-date") { (it.m("root").l("kids")[0] as Doc)["format"] = " " }
        rejected("lint.clock_zone", "clock-date") { (it.m("root").l("kids")[0] as Doc)["zone"] = "Mars/Olympus" }
        val ok = load("clock-date")
        (ok.m("root").l("kids")[0] as Doc)["zone"] = "Asia/Tokyo"
        eq(emptyList<String>(), codes(ok))
    }
    test("odd but legal inputs") {
        val doc = load("ride-check")
        doc["name"] = "\uD83D\uDEB4 Ride \u00e7\u0131k\u0131\u015f \u200F\u0645\u0631\u062d\u0628\u0627"
        doc.m("root").l("kids") += node("t" to "text", "text" to "100% {{sure}} it's {'}'} fine")
        eq(emptyList<String>(), codes(doc))
        val long = load("ride-check")
        long["name"] = "\uD83D\uDEB4".repeat(40)
        eq(emptyList<String>(), codes(long))
        long["name"] = "\uD83D\uDEB4".repeat(41)
        yes("schema.length" in codes(long))
    }
    test("min must be below max") {
        rejected("lint.range", "water-today") { (it.m("root").l("kids")[2] as Doc)["max"] = 0 }
    }
    test("problems carry a path and a readable message") {
        val doc = load("ride-check")
        (doc.m("root").l("kids")[0].l("kids")[1] as Doc)["text"] = "{nope} km/h"
        val p = (SpecReader.read(Json.fromKotlin(doc)) as SpecResult.Rejected).problems.single()
        eq("root.kids[0].kids[1].text", p.path)
        eq(Gate.LINT, p.gate)
        yes("nope" in p.message, p.message)
    }
}

private val schema = suite("schema") {
    test("types, ranges and unknown properties") {
        rejected("schema.unknown_property") { it["script"] = "alert(1)" }
        rejected("schema.unknown_property") { it.m("root")["onClick"] = "x" }
        rejected("schema.range") { it.m("size")["cols"] = 6 }
        rejected("schema.type") { it.m("size")["cols"] = 2.5 }
        rejected("schema.type") { it.m("size")["rows"] = "2" }
        rejected("schema.length") { it["name"] = "" }
        rejected("schema.length") { it["name"] = "x".repeat(41) }
        rejected("schema.const") { it["v"] = 0 }
        rejected("schema.type") { it["root"] = "col" }
        rejected("schema.type") { it.m("root")["kids"] = "none" }
        rejected("schema.pattern") { it.m("sources")["Bad-Name"] = node("use" to "battery") }
        rejected("schema.range") { it.m("root")["pad"] = 99 }
        rejected("schema.enum") { it.m("root")["w"] = "huge" }
        rejected("schema.range") { it.m("root")["w"] = 4 }
        rejected("schema.pattern") { it.m("root").l("kids") += node("t" to "image", "src" to "https://evil.example/x.png") }
        rejected("schema.pattern") { it.m("root").l("kids") += node("t" to "image", "src" to "file:///etc/passwd") }
        rejected("schema.pattern") { it["theme"] = node("colors" to node("primary" to "red")) }
        rejected("schema.required") { it.m("root")["tap"] = node("do" to "set", "var" to "x") }
        rejected("schema.pattern") { it.m("root")["tap"] = node("do" to "open_app", "pkg" to "not a package") }
        rejected("schema.length") { it.m("calc")["x"] = "1" + " + 1".repeat(70) }
        rejected("schema.range", "water-today") { (it.m("root").l("kids")[2] as Doc)["step"] = 0 }
    }
    test("a newer format version is refused with its own reason") {
        rejected("schema.newer_version") { it["v"] = 2 }
    }
    test("the root must be an object") {
        eq(listOf("schema.type"), codesOf("[1, 2]"))
        eq(listOf("schema.type"), codesOf("\"spec\""))
    }
}

private val json = suite("json") {
    val small = """{"v":1,"name":"n","size":{"cols":1,"rows":1},"root":{"t":"spacer"}}"""
    test("strict mode accepts plain JSON only") {
        eq(emptyList<String>(), codesOf(small))
        eq(listOf("parse.json"), codesOf("```json\n$small\n```"))
        eq(listOf("parse.json"), codesOf("Here you go: $small"))
        eq(listOf("parse.json"), codesOf(small.replace("\"spacer\"}", "\"spacer\",}")))
    }
    test("lenient mode accepts model output") {
        eq(emptyList<String>(), codesOf("```json\n$small\n```", lenient = true))
        eq(emptyList<String>(), codesOf("Sure! Here is your widget:\n\n$small\n\nLet me know.", lenient = true))
        eq(emptyList<String>(), codesOf(small.replace("\"spacer\"}", "\"spacer\",}"), lenient = true))
        eq(emptyList<String>(), codesOf("\uFEFF  $small", lenient = true))
        eq(listOf("parse.json"), codesOf("I cannot make that widget.", lenient = true))
    }
    test("duplicate keys are refused in both modes") {
        val dup = small.replace("\"name\":\"n\"", "\"name\":\"n\",\"name\":\"m\"")
        eq(listOf("parse.json"), codesOf(dup))
        eq(listOf("parse.json"), codesOf(dup, lenient = true))
    }
    test("input over 64 KB is refused before parsing") {
        eq(listOf("parse.too_large"), codesOf(small + " ".repeat(SpecReader.MAX_BYTES)))
        eq(listOf("parse.too_large"), codesOf("\u00e9".repeat(SpecReader.MAX_BYTES / 2 + 1)))
    }
    test("deep nesting is refused, not a stack overflow") {
        eq(listOf("parse.json"), codesOf("[".repeat(5000)))
        eq(listOf("parse.json"), codesOf("{\"a\":".repeat(40) + "1" + "}".repeat(40)))
    }
    test("values, escapes and numbers") {
        val v = Json.parse("""{"a":[1,-2.5,1e3,true,false,null],"s":"q\"\\\/\n\u00e9\ud83d\ude00"}""") as JObject
        eq(JArray(listOf(JNumber(1.0), JNumber(-2.5), JNumber(1000.0), JBool(true), JBool(false), JNull)), v["a"])
        eq(JString("q\"\\/\n\u00e9\uD83D\uDE00"), v["s"])
        eq("{\"a\":[1,-2.5,1000,true,false,null],\"s\":\"q\\\"\\\\/\\n\u00e9\uD83D\uDE00\"}", Json.write(v))
    }
    test("a tree built in code, deeper than the parser allows, is refused") {
        var n: JsonValue = JObject(mapOf("t" to JString("spacer")))
        repeat(20_000) { n = JObject(mapOf("t" to JString("col"), "kids" to JArray(listOf(n)))) }
        val base = Json.parse(File(goldenDir(), "valid/ride-check.json").readText()) as JObject
        val r = SpecReader.read(JObject(base.fields + ("root" to n))) as SpecResult.Rejected
        yes(r.problems.any { it.code == "schema.depth" })
    }
    test("malformed JSON is refused") {
        for (bad in listOf("", "{", "{\"a\"}", "{\"a\":}", "[1 2]", "01", "1.", ".5", "+1", "1e", "nul", "\"a", "\"\\x\"", "\"\\u12G4\"", "{'a':1}", "\"tab\there\"", "1e999", "{} {}", "NaN", "// c\n1")) {
            fails<JsonError>(bad) { Json.parse(bad) }
        }
    }
}

/** Seeded fuzzing: the reader must answer every input with Ok or Rejected, never an exception. */
private val fuzz = suite("fuzz") {
    // Built fresh for every mutation, so one mutation can never alias another and form a cycle.
    fun junk(r: Random): Any? = when (r.nextInt(17)) {
        0 -> null
        1 -> true
        2 -> -1
        3 -> 0
        4 -> 1e9
        5 -> 2.5
        6 -> ""
        7 -> "x"
        8 -> "{"
        9 -> "}"
        10 -> "it"
        11 -> "{it.x}"
        12 -> "https://a"
        13 -> "a".repeat(300)
        14 -> mutableListOf<Any?>()
        15 -> linkedMapOf<String, Any?>()
        else -> mutableListOf<Any?>(linkedMapOf<String, Any?>("t" to "list"))
    }

    fun containers(v: Any?, out: MutableList<Any>) {
        when (v) {
            is MutableMap<*, *> -> {
                out += v
                v.values.forEach { containers(it, out) }
            }
            is MutableList<*> -> {
                out += v
                v.forEach { containers(it, out) }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun mutate(doc: Doc, r: Random) {
        val all = ArrayList<Any>().also { containers(doc, it) }
        val target = all[r.nextInt(all.size)]
        val value = junk(r)
        if (target is MutableMap<*, *>) {
            val map = target as MutableMap<String, Any?>
            val keys = map.keys.toList()
            when (r.nextInt(3)) {
                0 -> if (keys.isNotEmpty()) map.remove(keys[r.nextInt(keys.size)])
                1 -> if (keys.isNotEmpty()) map[keys[r.nextInt(keys.size)]] = value
                else -> map[listOf("t", "kids", "item", "of", "tap", "var", "if", "text", "zz")[r.nextInt(9)]] = value
            }
        } else {
            val list = target as MutableList<Any?>
            if (list.isNotEmpty() && r.nextBoolean()) list[r.nextInt(list.size)] = value else list += value
        }
    }

    test("12,000 mutated specs never crash the reader") {
        val r = Random(1111)
        var ok = 0
        var rejected = 0
        repeat(12_000) { n ->
            val doc = load(validFiles[n % validFiles.size].name.removeSuffix(".json"))
            repeat(1 + r.nextInt(3)) { mutate(doc, r) }
            when (SpecReader.read(Json.fromKotlin(doc))) {
                is SpecResult.Ok -> ok++
                is SpecResult.Rejected -> rejected++
            }
        }
        yes(rejected > 3_000 && ok > 300, "ok=$ok rejected=$rejected")
    }
    test("anything accepted reads back identically from its own JSON") {
        val r = Random(2222)
        repeat(3_000) { n ->
            val doc = load(validFiles[n % validFiles.size].name.removeSuffix(".json"))
            mutate(doc, r)
            val first = SpecReader.read(Json.fromKotlin(doc))
            if (first is SpecResult.Ok) {
                val again = SpecReader.read(Json.write(first.spec.json))
                yes(again is SpecResult.Ok, "accepted once, refused after a round trip")
                eq(first.spec.json, (again as SpecResult.Ok).spec.json)
            }
        }
    }
    test("random text never crashes the reader") {
        val r = Random(3333)
        val alphabet = "{}[]\":,0123456789.-+eE tfnul\\u\n\"vnamesizerootcolst"
        repeat(30_000) {
            val s = String(CharArray(r.nextInt(60)) { alphabet[r.nextInt(alphabet.length)] })
            SpecReader.read(s, lenient = r.nextBoolean())
        }
    }
}

fun main() = runSuites(golden, broken, lint, schema, json, fuzz)
