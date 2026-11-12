package dev.afterdrop.spec

/** A parsed JSON value. Objects keep their key order. */
sealed interface JsonValue

data class JObject(val fields: Map<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = fields[key]
}

data class JArray(val items: List<JsonValue>) : JsonValue
data class JString(val value: String) : JsonValue
data class JNumber(val value: Double) : JsonValue {
    val isInteger: Boolean get() = value % 1.0 == 0.0 && Math.abs(value) <= 9_007_199_254_740_992.0
}

data class JBool(val value: Boolean) : JsonValue
data object JNull : JsonValue

class JsonError(message: String, val pos: Int) : Exception(message)

/**
 * A small JSON reader and writer with no dependencies.
 *
 * Strict mode is RFC 8259 plus two safety rules: duplicate keys are an error and nesting is capped.
 * Lenient mode is for model output: it also strips a Markdown code fence, ignores prose around the
 * outermost object, and allows a trailing comma.
 */
object Json {
    const val MAX_DEPTH = 32

    fun parse(text: String, lenient: Boolean = false): JsonValue {
        val src = if (lenient) unwrap(text) else text
        val p = Reader(src, lenient)
        p.skipSpace()
        val v = p.value(0)
        p.skipSpace()
        if (p.i < src.length) throw JsonError("unexpected text after the JSON value", p.i)
        return v
    }

    /** Drops a byte-order mark, a ```json fence, and any prose before `{` or after the last `}`. */
    internal fun unwrap(text: String): String {
        var s = text.removePrefix("﻿").trim()
        if (s.startsWith("```")) {
            val firstLine = s.indexOf('\n')
            if (firstLine >= 0) s = s.substring(firstLine + 1)
            val close = s.lastIndexOf("```")
            if (close >= 0) s = s.substring(0, close)
            s = s.trim()
        }
        val open = s.indexOf('{')
        val close = s.lastIndexOf('}')
        return if (open >= 0 && close > open) s.substring(open, close + 1) else s
    }

    private class Reader(val s: String, val lenient: Boolean) {
        var i = 0

        fun skipSpace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) throw JsonError("JSON is nested deeper than $MAX_DEPTH levels", i)
            if (i >= s.length) throw JsonError("unexpected end of JSON", i)
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JString(string())
                't' -> word("true", JBool(true))
                'f' -> word("false", JBool(false))
                'n' -> word("null", JNull)
                else -> if (c == '-' || c in '0'..'9') number() else throw JsonError("unexpected character '$c'", i)
            }
        }

        private fun word(w: String, v: JsonValue): JsonValue {
            if (!s.startsWith(w, i)) throw JsonError("unexpected text", i)
            i += w.length
            return v
        }

        private fun obj(depth: Int): JsonValue {
            i++
            val map = LinkedHashMap<String, JsonValue>()
            skipSpace()
            if (i < s.length && s[i] == '}') {
                i++
                return JObject(map)
            }
            while (true) {
                skipSpace()
                if (i >= s.length || s[i] != '"') throw JsonError("expected a property name in quotes", i)
                val keyPos = i
                val key = string()
                skipSpace()
                if (i >= s.length || s[i] != ':') throw JsonError("expected ':' after property name", i)
                i++
                skipSpace()
                val v = value(depth + 1)
                if (map.put(key, v) != null) throw JsonError("duplicate property \"$key\"", keyPos)
                skipSpace()
                if (i >= s.length) throw JsonError("unclosed object", i)
                if (s[i] == ',') {
                    i++
                    skipSpace()
                    if (lenient && i < s.length && s[i] == '}') {
                        i++
                        return JObject(map)
                    }
                } else if (s[i] == '}') {
                    i++
                    return JObject(map)
                } else throw JsonError("expected ',' or '}'", i)
            }
        }

        private fun arr(depth: Int): JsonValue {
            i++
            val items = ArrayList<JsonValue>()
            skipSpace()
            if (i < s.length && s[i] == ']') {
                i++
                return JArray(items)
            }
            while (true) {
                skipSpace()
                items += value(depth + 1)
                skipSpace()
                if (i >= s.length) throw JsonError("unclosed array", i)
                if (s[i] == ',') {
                    i++
                    skipSpace()
                    if (lenient && i < s.length && s[i] == ']') {
                        i++
                        return JArray(items)
                    }
                } else if (s[i] == ']') {
                    i++
                    return JArray(items)
                } else throw JsonError("expected ',' or ']'", i)
            }
        }

        private fun string(): String {
            val start = i
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> {
                        i++
                        return sb.toString()
                    }
                    c == '\\' -> {
                        if (i + 1 >= s.length) throw JsonError("unfinished escape", i)
                        when (val e = s[i + 1]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 6 > s.length) throw JsonError("unfinished \\u escape", i)
                                val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                                    ?.takeIf { s.substring(i + 2, i + 6).all { h -> h in '0'..'9' || h in 'a'..'f' || h in 'A'..'F' } }
                                    ?: throw JsonError("bad \\u escape", i)
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw JsonError("unknown escape \\$e", i)
                        }
                        i += 2
                    }
                    c < ' ' -> throw JsonError("control character in string", i)
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            throw JsonError("unclosed string", start)
        }

        private fun number(): JsonValue {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) throw JsonError("bad number", start)
            if (s[i] == '0') i++
            else if (s[i] in '1'..'9') while (i < s.length && s[i] in '0'..'9') i++
            else throw JsonError("bad number", start)
            if (i < s.length && s[i] == '.') {
                i++
                if (i >= s.length || s[i] !in '0'..'9') throw JsonError("bad number", start)
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || s[i] !in '0'..'9') throw JsonError("bad number", start)
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val d = s.substring(start, i).toDouble()
            if (d.isInfinite() || d.isNaN()) throw JsonError("number out of range", start)
            return JNumber(d)
        }
    }

    /** Writes JSON. With [indent] it is pretty-printed; key order is preserved either way. */
    fun write(v: JsonValue, indent: Boolean = false): String {
        val sb = StringBuilder()
        write(v, sb, if (indent) 0 else -1)
        return sb.toString()
    }

    private fun write(v: JsonValue, sb: StringBuilder, level: Int) {
        fun newline(l: Int) {
            if (level >= 0) {
                sb.append('\n')
                repeat(l) { sb.append("  ") }
            }
        }
        when (v) {
            JNull -> sb.append("null")
            is JBool -> sb.append(v.value)
            is JNumber -> sb.append(if (v.isInteger) v.value.toLong().toString() else v.value.toString())
            is JString -> quote(v.value, sb)
            is JArray -> {
                if (v.items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                for ((n, item) in v.items.withIndex()) {
                    if (n > 0) sb.append(',')
                    newline(level + 1)
                    write(item, sb, if (level >= 0) level + 1 else -1)
                }
                newline(level)
                sb.append(']')
            }
            is JObject -> {
                if (v.fields.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                var n = 0
                for ((k, item) in v.fields) {
                    if (n++ > 0) sb.append(',')
                    newline(level + 1)
                    quote(k, sb)
                    sb.append(if (level >= 0) ": " else ":")
                    write(item, sb, if (level >= 0) level + 1 else -1)
                }
                newline(level)
                sb.append('}')
            }
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' || c == ' ' || c == ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Converts to plain Kotlin values: MutableMap, MutableList, String, Double, Boolean, null. */
    fun toKotlin(v: JsonValue): Any? = when (v) {
        JNull -> null
        is JBool -> v.value
        is JNumber -> v.value
        is JString -> v.value
        is JArray -> v.items.mapTo(ArrayList()) { toKotlin(it) }
        is JObject -> v.fields.entries.associateTo(LinkedHashMap()) { it.key to toKotlin(it.value) }
    }

    /** The reverse of [toKotlin]. Numbers of any kind are accepted. */
    fun fromKotlin(v: Any?): JsonValue = when (v) {
        null -> JNull
        is JsonValue -> v
        is Boolean -> JBool(v)
        is Number -> JNumber(v.toDouble())
        is String -> JString(v)
        is List<*> -> JArray(v.map { fromKotlin(it) })
        is Map<*, *> -> JObject(v.entries.associateTo(LinkedHashMap()) { it.key.toString() to fromKotlin(it.value) })
        else -> throw IllegalArgumentException("not a JSON value: ${v::class.simpleName}")
    }
}
