package dev.afterdrop.spec

import dev.afterdrop.formula.Parser
import dev.afterdrop.formula.Template

/** Which check rejected a spec. The AI repair loop sends these back to the model. */
enum class Gate { PARSE, SCHEMA, LINT }

/** One reason a spec was rejected. [code] is stable, [path] points into the JSON, [message] is for people. */
data class Problem(val gate: Gate, val code: String, val path: String, val message: String) {
    override fun toString() = "${gate.name.lowercase()} $code at $path: $message"
}

/** The closed vocabulary of WidgetSpec v1. It mirrors docs/schema/widget-spec.v1.schema.json. */
object Vocabulary {
    val ROLES = setOf(
        "bg", "surface", "surfaceVariant", "primary", "onPrimary", "secondary", "tertiary",
        "text", "muted", "outline", "error", "success", "warning",
    )
    val NODE_TYPES = setOf(
        "col", "row", "box", "list", "text", "icon", "image", "progress", "chart",
        "clock", "timer", "button", "toggle", "stepper", "check", "spacer", "divider",
    )
    val SOURCE_KINDS = setOf(
        "weather", "air", "calendar", "battery", "alarm", "health", "rates",
        "feed", "http", "ai", "notes", "media", "device",
    )
    /** Sources whose values must never reach a network request. */
    val PRIVATE_SOURCES = setOf("calendar", "health", "notes", "media")
    /** Sources that send their parameters over the network. */
    val NETWORK_SOURCES = setOf("http", "feed", "ai")
    val INTERVALS = setOf("15m", "30m", "1h", "3h", "6h", "12h", "1d", "1w")
    val VAR_TYPES = setOf("int", "num", "bool", "text")
    val ACTIONS = setOf("refresh", "edit", "open_url", "open_app", "open_source", "set", "ask")
    val TEXT_STYLES = setOf("display", "headline", "title", "body", "label", "caption")
    const val MAX_DEPTH = 5
    const val MAX_NODES = 60

    private val COMMON = setOf("t", "id", "grow", "w", "h", "bg", "radius", "from", "if", "tap", "pad")
    private val TYPED = setOf("style", "color", "bold")

    /** The properties each node type accepts, beyond which lint rejects. */
    val PROPS: Map<String, Set<String>> = mapOf(
        "col" to setOf("kids", "gap", "align", "cross"),
        "row" to setOf("kids", "gap", "align", "cross"),
        "box" to setOf("kids", "align", "cross"),
        "list" to setOf("of", "item", "limit", "empty", "gap"),
        "text" to TYPED + setOf("text", "lines"),
        "icon" to setOf("icon", "iconSize", "color"),
        "image" to setOf("src", "fit"),
        "progress" to setOf("value", "shape", "color", "min", "max"),
        "chart" to setOf("kind", "values", "min", "max", "color"),
        "clock" to TYPED + setOf("format", "zone"),
        "timer" to TYPED + setOf("to", "mode"),
        "button" to setOf("label", "kind", "icon"),
        "toggle" to setOf("var", "label"),
        "stepper" to setOf("var", "step", "min", "max", "label"),
        "check" to setOf("var", "label"),
        "spacer" to emptySet(),
        "divider" to setOf("color"),
    ).mapValues { COMMON + it.value }

    val KINDS: Map<String, Set<String>> = mapOf(
        "chart" to setOf("spark", "bars"),
        "button" to setOf("filled", "tonal", "plain"),
    )
}

private val IDENT = Regex("[a-z][a-zA-Z0-9]{0,23}")
private val HEX = Regex("#[0-9a-fA-F]{6}")
private val ICON = Regex("[a-z][a-z0-9_]{0,39}")
private val SRC = Regex("(asset|source):[A-Za-z0-9_./-]{1,80}")
private val PKG = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")

/** Structural validation: the hand-written equivalent of the JSON Schema, with stable problem codes. */
internal class SchemaCheck {
    val problems = ArrayList<Problem>()

    private fun bad(code: String, path: String, message: String) {
        problems += Problem(Gate.SCHEMA, "schema.$code", path, message)
    }

    private fun obj(v: JsonValue?, path: String): JObject? {
        if (v is JObject) return v
        bad("type", path, "must be an object")
        return null
    }

    private fun keys(o: JObject, path: String, allowed: Set<String>, required: List<String>) {
        for (k in o.fields.keys) if (k !in allowed) bad("unknown_property", "$path.$k", "unknown property \"$k\"")
        for (k in required) if (k !in o.fields) bad("required", path, "missing required property \"$k\"")
    }

    private fun string(v: JsonValue, path: String, min: Int = 0, max: Int = Int.MAX_VALUE): String? {
        if (v !is JString) {
            bad("type", path, "must be text")
            return null
        }
        val length = v.value.codePointCount(0, v.value.length)
        if (length < min) bad("length", path, "must be at least $min character(s)")
        if (length > max) bad("length", path, "must be at most $max characters")
        return v.value
    }

    private fun pattern(v: JsonValue, path: String, regex: Regex, what: String) {
        val s = string(v, path) ?: return
        if (!regex.matches(s)) bad("pattern", path, "must be $what")
    }

    private fun choice(v: JsonValue, path: String, allowed: Set<String>) {
        if (v !is JString || v.value !in allowed) bad("enum", path, "must be one of: ${allowed.joinToString(", ")}")
    }

    private fun integer(v: JsonValue, path: String, min: Int, max: Int) {
        if (v !is JNumber || !v.isInteger) return bad("type", path, "must be a whole number")
        if (v.value < min || v.value > max) bad("range", path, "must be between $min and $max")
    }

    private fun number(v: JsonValue, path: String) {
        if (v !is JNumber) bad("type", path, "must be a number")
    }

    private fun formula(v: JsonValue, path: String) {
        string(v, path, 1, Parser.MAX_LENGTH)
    }

    private fun template(v: JsonValue, path: String) {
        string(v, path, 0, Template.MAX_LENGTH)
    }

    private fun ident(v: JsonValue, path: String) = pattern(v, path, IDENT, "a short name like \"wx\" or \"stepsToday\"")

    private fun named(v: JsonValue, path: String, max: Int, each: (JsonValue, String) -> Unit) {
        val o = obj(v, path) ?: return
        if (o.fields.size > max) bad("max_items", path, "has ${o.fields.size} entries, the limit is $max")
        for ((k, item) in o.fields) {
            if (!IDENT.matches(k)) bad("pattern", "$path.$k", "\"$k\" is not a valid name")
            each(item, "$path.$k")
        }
    }

    fun check(root: JsonValue) {
        val o = obj(root, "$") ?: return
        keys(o, "$", setOf("v", "name", "about", "size", "theme", "sources", "vars", "calc", "root"), listOf("v", "name", "size", "root"))
        o["v"]?.let { if (it != JNumber(1.0)) bad("const", "v", "must be 1") }
        o["name"]?.let { string(it, "name", 1, 40) }
        o["about"]?.let { string(it, "about", 0, 200) }
        o["size"]?.let { size(it) }
        o["theme"]?.let { theme(it) }
        o["sources"]?.let { named(it, "sources", 6, ::source) }
        o["vars"]?.let { named(it, "vars", 8, ::variable) }
        o["calc"]?.let { named(it, "calc", 12, ::formula) }
        o["root"]?.let { node(it, "root", 1) }
    }

    private fun size(v: JsonValue) {
        val o = obj(v, "size") ?: return
        keys(o, "size", setOf("cols", "rows"), listOf("cols", "rows"))
        o["cols"]?.let { integer(it, "size.cols", 1, 5) }
        o["rows"]?.let { integer(it, "size.rows", 1, 5) }
    }

    private fun theme(v: JsonValue) {
        val o = obj(v, "theme") ?: return
        keys(o, "theme", setOf("palette", "colors", "shape", "density"), emptyList())
        o["palette"]?.let { choice(it, "theme.palette", setOf("dynamic", "light", "dark", "custom")) }
        o["shape"]?.let { choice(it, "theme.shape", setOf("system", "soft", "sharp")) }
        o["density"]?.let { choice(it, "theme.density", setOf("compact", "regular")) }
        o["colors"]?.let { colors ->
            val c = obj(colors, "theme.colors") ?: return@let
            for ((k, hex) in c.fields) {
                if (k !in Vocabulary.ROLES) bad("enum", "theme.colors.$k", "\"$k\" is not a colour role")
                pattern(hex, "theme.colors.$k", HEX, "a colour like #1A73E8")
            }
        }
    }

    private fun source(v: JsonValue, path: String) {
        val o = obj(v, path) ?: return
        keys(o, path, setOf("use", "with", "every"), listOf("use"))
        o["use"]?.let { choice(it, "$path.use", Vocabulary.SOURCE_KINDS) }
        o["with"]?.let { obj(it, "$path.with") }
        o["every"]?.let { choice(it, "$path.every", Vocabulary.INTERVALS) }
    }

    private fun variable(v: JsonValue, path: String) {
        val o = obj(v, path) ?: return
        keys(o, path, setOf("type", "init", "reset"), listOf("type", "init"))
        o["type"]?.let { choice(it, "$path.type", Vocabulary.VAR_TYPES) }
        o["init"]?.let { if (it !is JNumber && it !is JBool && it !is JString) bad("type", "$path.init", "must be a number, true/false or text") }
        o["reset"]?.let { choice(it, "$path.reset", setOf("never", "daily", "weekly")) }
    }

    private fun action(v: JsonValue, path: String) {
        val o = obj(v, path) ?: return
        keys(o, path, setOf("do", "url", "pkg", "source", "var", "to", "title", "kind"), listOf("do"))
        o["do"]?.let { choice(it, "$path.do", Vocabulary.ACTIONS) }
        o["url"]?.let {
            val url = string(it, "$path.url", 0, 300)
            if (url != null && !url.startsWith("https://")) bad("pattern", "$path.url", "must start with https://")
        }
        o["pkg"]?.let { pattern(it, "$path.pkg", PKG, "an app package name like com.example.app") }
        o["source"]?.let { ident(it, "$path.source") }
        o["var"]?.let { ident(it, "$path.var") }
        o["to"]?.let { formula(it, "$path.to") }
        o["title"]?.let { string(it, "$path.title", 0, 40) }
        o["kind"]?.let { choice(it, "$path.kind", setOf("number", "text")) }
        val needs = when ((o["do"] as? JString)?.value) {
            "open_url" -> listOf("url")
            "open_app" -> listOf("pkg")
            "open_source" -> listOf("source")
            "set" -> listOf("var", "to")
            "ask" -> listOf("var")
            else -> emptyList()
        }
        for (k in needs) if (k !in o.fields) bad("required", path, "\"${(o["do"] as JString).value}\" needs \"$k\"")
    }

    private fun dim(v: JsonValue, path: String) {
        if (v is JString) {
            if (v.value != "wrap" && v.value != "fill") bad("enum", path, "must be \"wrap\", \"fill\" or a number of dp")
        } else integer(v, path, 8, 400)
    }

    private fun node(v: JsonValue, path: String, depth: Int) {
        // Lint enforces the real limit of five; this only keeps a hostile tree from exhausting the stack.
        if (depth > Json.MAX_DEPTH) return bad("depth", path, "nested too deeply")
        val o = obj(v, path) ?: return
        val type = (o["t"] as? JString)?.value
        if ("t" !in o.fields) bad("required", path, "missing required property \"t\"")
        else if (type == null || type !in Vocabulary.NODE_TYPES) bad("enum", "$path.t", "unknown node type")
        for ((k, item) in o.fields) if (k != "t") nodeProp(k, item, "$path.$k", depth)
        val needs = when (type) {
            "col", "row", "box" -> listOf("kids")
            "list" -> listOf("of", "item")
            "text" -> listOf("text")
            "icon" -> listOf("icon")
            "image" -> listOf("src")
            "progress" -> listOf("value")
            "chart" -> listOf("kind", "values")
            "clock" -> listOf("format")
            "timer" -> listOf("to")
            "button" -> listOf("label", "tap")
            "toggle", "stepper", "check" -> listOf("var")
            else -> emptyList()
        }
        for (k in needs) if (k !in o.fields) bad("required", path, "a \"$type\" node needs \"$k\"")
    }

    private fun nodeProp(key: String, v: JsonValue, p: String, depth: Int) {
        when (key) {
            "id", "var" -> ident(v, p)
            "kids" -> {
                if (v !is JArray) return bad("type", p, "must be a list of nodes")
                if (v.items.size > 10) bad("max_items", p, "has ${v.items.size} children, the limit is 10")
                for ((i, kid) in v.items.withIndex()) node(kid, "$p[$i]", depth + 1)
            }
            "item" -> node(v, p, depth + 1)
            "gap", "pad" -> integer(v, p, 0, 24)
            "align" -> choice(v, p, setOf("start", "center", "end", "between"))
            "cross" -> choice(v, p, setOf("start", "center", "end"))
            "grow" -> integer(v, p, 0, 4)
            "w", "h" -> dim(v, p)
            "bg" -> choice(v, p, Vocabulary.ROLES + "none")
            "radius" -> integer(v, p, 0, 28)
            "from" -> choice(v, p, setOf("xs", "s", "m", "l"))
            "if", "value", "values", "to", "of" -> formula(v, p)
            "tap" -> action(v, p)
            "text", "label", "empty" -> template(v, p)
            "style" -> choice(v, p, Vocabulary.TEXT_STYLES)
            "color" -> choice(v, p, Vocabulary.ROLES)
            "lines" -> integer(v, p, 1, 6)
            "bold" -> if (v !is JBool) bad("type", p, "must be true or false")
            "icon" -> pattern(v, p, ICON, "an icon name like \"rainy\"")
            "iconSize" -> integer(v, p, 12, 64)
            "src" -> pattern(v, p, SRC, "\"asset:<name>\" or \"source:<alias.field>\"")
            "fit" -> choice(v, p, setOf("cover", "contain"))
            "shape" -> choice(v, p, setOf("bar", "ring"))
            "kind" -> choice(v, p, setOf("spark", "bars", "filled", "tonal", "plain"))
            "min", "max" -> number(v, p)
            "format" -> string(v, p, 0, 32)
            "zone" -> string(v, p, 0, 40)
            "mode" -> choice(v, p, setOf("down", "up"))
            "step" -> if (v !is JNumber || v.value <= 0) bad("range", p, "must be a number above 0")
            "limit" -> integer(v, p, 1, 20)
            else -> bad("unknown_property", p, "unknown property \"$key\"")
        }
    }
}
