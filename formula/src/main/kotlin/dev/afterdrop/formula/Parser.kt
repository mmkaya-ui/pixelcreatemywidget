package dev.afterdrop.formula

/** A formula or template that does not follow the grammar. [pos] is a character offset, or -1. */
class FormulaError(message: String, val pos: Int = -1) : Exception(message)

sealed interface Expr {
    data class Lit(val value: Value) : Expr
    data class Name(val name: String, val pos: Int) : Expr
    data class Call(val fn: String, val args: List<Expr>, val pos: Int) : Expr
    data class Unary(val op: String, val operand: Expr, val pos: Int) : Expr
    data class Binary(val op: String, val left: Expr, val right: Expr, val pos: Int) : Expr
    data class Ternary(val cond: Expr, val then: Expr, val otherwise: Expr) : Expr
    data class Field(val target: Expr, val name: String, val pos: Int) : Expr
    data class Index(val target: Expr, val index: Expr, val pos: Int) : Expr
}

internal enum class Tk { NUM, STR, ID, OP, END }

internal data class Token(val kind: Tk, val text: String, val pos: Int)

private val TWO_CHAR_OPS = setOf("||", "&&", "==", "!=", "<=", ">=")
private const val ONE_CHAR_OPS = "-+*/%<>!?:(),.[]"

private fun isIdStart(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_'
private fun isIdPart(c: Char) = isIdStart(c) || c in '0'..'9'

internal fun tokenize(src: String): List<Token> {
    val out = ArrayList<Token>()
    val n = src.length
    var i = 0
    while (i < n) {
        val c = src[i]
        when {
            c == ' ' || c == '\t' || c == '\n' || c == '\r' -> i++
            c in '0'..'9' -> {
                var j = i
                while (j < n && src[j] in '0'..'9') j++
                if (j + 1 < n && src[j] == '.' && src[j + 1] in '0'..'9') {
                    j++
                    while (j < n && src[j] in '0'..'9') j++
                }
                out += Token(Tk.NUM, src.substring(i, j), i)
                i = j
            }
            c == '\'' -> {
                val sb = StringBuilder()
                var j = i + 1
                var closed = false
                while (j < n) {
                    val d = src[j]
                    if (d == '\\') {
                        if (j + 1 >= n) break
                        sb.append(
                            when (val e = src[j + 1]) {
                                'n' -> '\n'
                                't' -> '\t'
                                '\'' -> '\''
                                '\\' -> '\\'
                                else -> throw FormulaError("unknown escape \\$e", j)
                            }
                        )
                        j += 2
                    } else if (d == '\'') {
                        closed = true
                        j++
                        break
                    } else {
                        sb.append(d)
                        j++
                    }
                }
                if (!closed) throw FormulaError("unterminated string", i)
                out += Token(Tk.STR, sb.toString(), i)
                i = j
            }
            isIdStart(c) -> {
                var j = i
                while (j < n && isIdPart(src[j])) j++
                out += Token(Tk.ID, src.substring(i, j), i)
                i = j
            }
            i + 1 < n && src.substring(i, i + 2) in TWO_CHAR_OPS -> {
                out += Token(Tk.OP, src.substring(i, i + 2), i)
                i += 2
            }
            ONE_CHAR_OPS.indexOf(c) >= 0 -> {
                out += Token(Tk.OP, c.toString(), i)
                i++
            }
            else -> throw FormulaError("unexpected character '$c'", i)
        }
    }
    out += Token(Tk.END, "", n)
    return out
}

/**
 * Recursive-descent parser for the formula grammar in docs/schema.
 * Precedence, loosest first: ?: || && compare + - * / % unary postfix.
 */
class Parser private constructor(src: String) {
    private val toks = tokenize(src)
    private var i = 0
    private var depth = 0

    private fun peek() = toks[i]
    private fun isOp(text: String) = peek().kind == Tk.OP && peek().text == text
    private fun next() = toks[i++]

    private fun expect(text: String) {
        if (!isOp(text)) throw FormulaError("expected '$text'" + found(), peek().pos)
        i++
    }

    private fun found() = if (peek().kind == Tk.END) ", found end of formula" else ", found '${peek().text}'"

    private inline fun <T> nested(block: () -> T): T {
        if (++depth > MAX_DEPTH) throw FormulaError("formula is nested too deeply", peek().pos)
        try {
            return block()
        } finally {
            depth--
        }
    }

    private fun ternary(): Expr = nested {
        val cond = or()
        if (isOp("?")) {
            i++
            val a = ternary()
            expect(":")
            val b = ternary()
            Expr.Ternary(cond, a, b)
        } else cond
    }

    private fun or(): Expr {
        var left = and()
        while (isOp("||")) {
            val pos = next().pos
            left = Expr.Binary("||", left, and(), pos)
        }
        return left
    }

    private fun and(): Expr {
        var left = compare()
        while (isOp("&&")) {
            val pos = next().pos
            left = Expr.Binary("&&", left, compare(), pos)
        }
        return left
    }

    private fun compare(): Expr {
        val left = sum()
        if (peek().kind == Tk.OP && peek().text in COMPARE_OPS) {
            val op = next()
            return Expr.Binary(op.text, left, sum(), op.pos)
        }
        return left
    }

    private fun sum(): Expr {
        var left = product()
        while (isOp("+") || isOp("-")) {
            val op = next()
            left = Expr.Binary(op.text, left, product(), op.pos)
        }
        return left
    }

    private fun product(): Expr {
        var left = unary()
        while (isOp("*") || isOp("/") || isOp("%")) {
            val op = next()
            left = Expr.Binary(op.text, left, unary(), op.pos)
        }
        return left
    }

    private fun unary(): Expr = nested {
        if (isOp("!") || isOp("-")) {
            val op = next()
            Expr.Unary(op.text, unary(), op.pos)
        } else postfix()
    }

    private fun postfix(): Expr {
        var e = primary()
        while (true) {
            if (isOp(".")) {
                val pos = next().pos
                val name = peek()
                if (name.kind != Tk.ID) throw FormulaError("field name expected after '.'", name.pos)
                i++
                e = Expr.Field(e, name.text, pos)
            } else if (isOp("[")) {
                val pos = next().pos
                val index = ternary()
                expect("]")
                e = Expr.Index(e, index, pos)
            } else return e
        }
    }

    private fun primary(): Expr {
        val t = peek()
        when (t.kind) {
            Tk.NUM -> {
                i++
                return Expr.Lit(Value.Num(t.text.toDouble()))
            }
            Tk.STR -> {
                i++
                return Expr.Lit(Value.Str(t.text))
            }
            Tk.ID -> {
                i++
                if (t.text == "true") return Expr.Lit(Value.Bool(true))
                if (t.text == "false") return Expr.Lit(Value.Bool(false))
                if (isOp("(")) {
                    i++
                    val args = ArrayList<Expr>()
                    if (!isOp(")")) {
                        args += ternary()
                        while (isOp(",")) {
                            i++
                            args += ternary()
                        }
                    }
                    expect(")")
                    return Expr.Call(t.text, args, t.pos)
                }
                return Expr.Name(t.text, t.pos)
            }
            Tk.OP -> if (t.text == "(") {
                i++
                val e = ternary()
                expect(")")
                return e
            }
            Tk.END -> {}
        }
        throw FormulaError("expected a value" + found(), t.pos)
    }

    private fun parseAll(): Expr {
        val e = ternary()
        if (peek().kind != Tk.END) throw FormulaError("unexpected '${peek().text}'", peek().pos)
        return e
    }

    companion object {
        const val MAX_LENGTH = 240
        private const val MAX_DEPTH = 48
        private val COMPARE_OPS = setOf("==", "!=", "<", "<=", ">", ">=")

        /** Parses one formula. Throws [FormulaError] and nothing else. */
        fun parse(src: String): Expr {
            if (src.length > MAX_LENGTH) throw FormulaError("formula is longer than $MAX_LENGTH characters", MAX_LENGTH)
            return Parser(src).parseAll()
        }
    }
}

/** Fully parenthesised source form. `Parser.parse(print(e))` yields an equal tree. */
fun Expr.print(): String = when (this) {
    is Expr.Lit -> when (val v = value) {
        is Value.Num -> displayLiteral(v.v)
        is Value.Str -> "'" + v.v.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\t", "\\t") + "'"
        is Value.Bool -> v.v.toString()
        else -> "0"
    }
    is Expr.Name -> name
    is Expr.Call -> fn + "(" + args.joinToString(", ") { it.print() } + ")"
    is Expr.Unary -> "(" + op + operand.print() + ")"
    is Expr.Binary -> "(" + left.print() + " " + op + " " + right.print() + ")"
    is Expr.Ternary -> "(" + cond.print() + " ? " + then.print() + " : " + otherwise.print() + ")"
    is Expr.Field -> target.print() + "." + name
    is Expr.Index -> target.print() + "[" + index.print() + "]"
}

private fun displayLiteral(v: Double): String =
    if (v % 1.0 == 0.0 && Math.abs(v) < 1e15) v.toLong().toString() else java.math.BigDecimal(v.toString()).toPlainString()
