package dev.afterdrop.composer

import dev.afterdrop.spec.Gate
import dev.afterdrop.spec.JArray
import dev.afterdrop.spec.JBool
import dev.afterdrop.spec.JNumber
import dev.afterdrop.spec.JObject
import dev.afterdrop.spec.JString
import dev.afterdrop.spec.Json
import dev.afterdrop.spec.JsonError
import dev.afterdrop.spec.JsonValue
import dev.afterdrop.spec.Problem
import dev.afterdrop.spec.Vocabulary

/**
 * A short description of a widget: what it is for and what to show, with no layout detail.
 * Small models and the offline rules write briefs; [Composer] expands one into a full spec.
 * Mirrors docs/schema/brief.v1.schema.json.
 */
class Brief(
    val intent: String,
    val title: String,
    val layout: String,
    val cols: Int,
    val rows: Int,
    val show: List<String> = emptyList(),
    val palette: String? = null,
    val density: String? = null,
    val accent: String = "primary",
    val extras: Map<String, JsonValue> = emptyMap(),
) {
    fun text(key: String): String? = (extras[key] as? JString)?.value
    fun number(key: String): Double? = (extras[key] as? JNumber)?.value

    fun toJson(): JObject {
        val o = LinkedHashMap<String, JsonValue>()
        o["v"] = JNumber(1.0)
        o["intent"] = JString(intent)
        o["title"] = JString(title)
        o["layout"] = JString(layout)
        o["size"] = JObject(mapOf("cols" to JNumber(cols.toDouble()), "rows" to JNumber(rows.toDouble())))
        if (show.isNotEmpty()) o["show"] = JArray(show.map { JString(it) })
        val style = LinkedHashMap<String, JsonValue>()
        palette?.let { style["palette"] = JString(it) }
        density?.let { style["density"] = JString(it) }
        style["accent"] = JString(accent)
        o["style"] = JObject(style)
        if (extras.isNotEmpty()) o["extras"] = JObject(extras)
        return JObject(o)
    }

    companion object {
        val INTENTS = setOf(
            "weather", "agenda", "countdown", "tracker", "todo", "clock", "battery",
            "quote", "rates", "feed", "calculator", "dashboard", "custom",
        )
        val LAYOUTS = setOf("stat", "list", "ring", "spark", "grid", "hero")
    }
}

sealed interface BriefResult {
    class Ok(val brief: Brief) : BriefResult
    class Rejected(val problems: List<Problem>) : BriefResult
}

object BriefReader {
    fun read(text: String, lenient: Boolean = false): BriefResult {
        if (text.length > 16 * 1024) return reject("parse.too_large", "$", "a brief is at most 16 KB")
        val json = try {
            Json.parse(text, lenient)
        } catch (e: JsonError) {
            return reject("parse.json", "$", "${e.message} at character ${e.pos}")
        }
        return read(json)
    }

    private fun reject(code: String, path: String, message: String) =
        BriefResult.Rejected(listOf(Problem(if (code.startsWith("parse")) Gate.PARSE else Gate.SCHEMA, code, path, message)))

    fun read(json: JsonValue): BriefResult {
        val bad = ArrayList<Problem>()
        fun no(code: String, path: String, message: String) {
            bad += Problem(Gate.SCHEMA, "brief.$code", path, message)
        }
        val o = json as? JObject ?: return reject("brief.type", "$", "must be an object")
        val allowed = setOf("v", "intent", "title", "layout", "size", "sources", "show", "style", "extras")
        for (k in o.fields.keys) if (k !in allowed) no("unknown_property", k, "unknown property \"$k\"")
        for (k in listOf("v", "intent", "title", "layout", "size")) if (k !in o.fields) no("required", "$", "missing \"$k\"")
        if ("v" in o.fields && o["v"] != JNumber(1.0)) no("const", "v", "must be 1")

        fun choice(v: JsonValue?, path: String, set: Set<String>): String? {
            if (v == null) return null
            val s = (v as? JString)?.value
            if (s == null || s !in set) {
                no("enum", path, "must be one of: ${set.joinToString(", ")}")
                return null
            }
            return s
        }
        val intent = choice(o["intent"], "intent", Brief.INTENTS)
        val layout = choice(o["layout"], "layout", Brief.LAYOUTS)
        val title = (o["title"] as? JString)?.value
        if ("title" in o.fields && (title == null || title.isEmpty() || title.codePointCount(0, title.length) > 40)) {
            no("length", "title", "must be text of 1 to 40 characters")
        }
        fun cells(v: JsonValue?, path: String): Int? {
            val n = v as? JNumber
            if (n == null || !n.isInteger || n.value < 1 || n.value > 5) {
                no("range", path, "must be a whole number from 1 to 5")
                return null
            }
            return n.value.toInt()
        }
        val size = o["size"]
        var cols: Int? = null
        var rows: Int? = null
        if (size != null) {
            val s = size as? JObject
            if (s == null) no("type", "size", "must be an object") else {
                for (k in s.fields.keys) if (k != "cols" && k != "rows") no("unknown_property", "size.$k", "unknown property \"$k\"")
                cols = cells(s["cols"], "size.cols")
                rows = cells(s["rows"], "size.rows")
            }
        }
        o["sources"]?.let { v ->
            val list = (v as? JArray)?.items
            if (list == null || list.size > 3) no("max_items", "sources", "must be a list of at most 3") else {
                for ((i, item) in list.withIndex()) {
                    val src = item as? JObject
                    if (src == null) no("type", "sources[$i]", "must be an object") else {
                        for (k in src.fields.keys) if (k != "use" && k != "with") no("unknown_property", "sources[$i].$k", "unknown property \"$k\"")
                        if (choice(src["use"], "sources[$i].use", Vocabulary.SOURCE_KINDS) == null && "use" !in src.fields) no("required", "sources[$i]", "missing \"use\"")
                        src["with"]?.let { if (it !is JObject) no("type", "sources[$i].with", "must be an object") }
                    }
                }
            }
        }
        val show = ArrayList<String>()
        o["show"]?.let { v ->
            val list = (v as? JArray)?.items
            if (list == null || list.size > 6) no("max_items", "show", "must be a list of at most 6") else {
                for ((i, item) in list.withIndex()) {
                    val s = (item as? JString)?.value
                    if (s == null || s.codePointCount(0, s.length) > 32) no("length", "show[$i]", "must be text of at most 32 characters") else show += s
                }
            }
        }
        var palette: String? = null
        var density: String? = null
        var accent: String? = null
        o["style"]?.let { v ->
            val s = v as? JObject
            if (s == null) no("type", "style", "must be an object") else {
                for (k in s.fields.keys) if (k !in setOf("palette", "density", "accent")) no("unknown_property", "style.$k", "unknown property \"$k\"")
                palette = choice(s["palette"], "style.palette", setOf("dynamic", "light", "dark"))
                density = choice(s["density"], "style.density", setOf("compact", "regular"))
                accent = choice(s["accent"], "style.accent", setOf("primary", "secondary", "tertiary"))
            }
        }
        var extras: Map<String, JsonValue> = emptyMap()
        o["extras"]?.let { v ->
            val e = v as? JObject
            if (e == null || e.fields.size > 6) no("max_items", "extras", "must be an object with at most 6 entries") else {
                for ((k, item) in e.fields) if (item !is JString && item !is JNumber && item !is JBool) no("type", "extras.$k", "must be text, a number or true/false")
                extras = e.fields
            }
        }
        if (bad.isNotEmpty()) return BriefResult.Rejected(bad)
        return BriefResult.Ok(Brief(intent!!, title!!, layout!!, cols!!, rows!!, show, palette, density, accent ?: "primary", extras))
    }
}
