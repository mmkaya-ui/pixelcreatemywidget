package dev.afterdrop.formula

/** One piece of a text template: literal text or a `{formula}`. */
sealed interface Part {
    data class Text(val text: String) : Part
    data class Code(val expr: Expr, val source: String, val pos: Int) : Part
}

object Template {
    const val MAX_LENGTH = 160

    /**
     * Splits `Hello {name}, {{literal}}` into parts. `{{` and `}}` are literal braces.
     * A `}` inside a quoted string does not end the formula. Throws [FormulaError] only.
     */
    fun parse(tpl: String): List<Part> {
        val parts = ArrayList<Part>()
        val text = StringBuilder()
        val n = tpl.length
        var i = 0
        while (i < n) {
            val c = tpl[i]
            when {
                c == '{' && i + 1 < n && tpl[i + 1] == '{' -> {
                    text.append('{'); i += 2
                }
                c == '}' && i + 1 < n && tpl[i + 1] == '}' -> {
                    text.append('}'); i += 2
                }
                c == '{' -> {
                    val end = closingBrace(tpl, i + 1)
                    if (end < 0) throw FormulaError("unclosed '{' in template", i)
                    if (text.isNotEmpty()) {
                        parts += Part.Text(text.toString()); text.setLength(0)
                    }
                    val src = tpl.substring(i + 1, end)
                    val expr = try {
                        Parser.parse(src)
                    } catch (e: FormulaError) {
                        throw FormulaError(e.message ?: "bad formula", if (e.pos >= 0) i + 1 + e.pos else i)
                    }
                    parts += Part.Code(expr, src, i + 1)
                    i = end + 1
                }
                c == '}' -> throw FormulaError("stray '}' in template", i)
                else -> {
                    text.append(c); i++
                }
            }
        }
        if (text.isNotEmpty()) parts += Part.Text(text.toString())
        return parts
    }

    private fun closingBrace(tpl: String, from: Int): Int {
        var i = from
        var quoted = false
        while (i < tpl.length) {
            val c = tpl[i]
            if (quoted) {
                if (c == '\\') i++ else if (c == '\'') quoted = false
            } else if (c == '\'') quoted = true
            else if (c == '}') return i
            else if (c == '{') return -1
            i++
        }
        return -1
    }
}
