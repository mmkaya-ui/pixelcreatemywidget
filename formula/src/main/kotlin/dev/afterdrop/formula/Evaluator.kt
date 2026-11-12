package dev.afterdrop.formula

import dev.afterdrop.formula.Value.Bool
import dev.afterdrop.formula.Value.ListV
import dev.afterdrop.formula.Value.Null
import dev.afterdrop.formula.Value.Num
import dev.afterdrop.formula.Value.Obj
import dev.afterdrop.formula.Value.Str
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** Everything a formula can see: the clock, the zone, the locale and named values. */
class EvalContext(
    val nowMillis: Long,
    val zone: ZoneId = ZoneOffset.UTC,
    val locale: Locale = Locale.ROOT,
    val lookup: (String) -> Value? = { null },
)

/**
 * Evaluates parsed formulas. Evaluation is total: it never throws, and anything that cannot be
 * computed (missing data, wrong type, division by zero) is [Value.Null].
 */
object Evaluator {
    /** Shown in a template where a `{formula}` has no value. */
    const val MISSING = "–"
    private const val MAX_TEXT = 2_000

    fun eval(e: Expr, ctx: EvalContext): Value = when (e) {
        is Expr.Lit -> e.value
        is Expr.Name -> ctx.lookup(e.name) ?: Null
        is Expr.Call -> call(e, ctx)
        is Expr.Unary -> {
            val v = eval(e.operand, ctx)
            if (e.op == "!") Bool(!v.truthy()) else if (v is Num) Value.of(-v.v) else Null
        }
        is Expr.Binary -> binary(e, ctx)
        is Expr.Ternary -> if (eval(e.cond, ctx).truthy()) eval(e.then, ctx) else eval(e.otherwise, ctx)
        is Expr.Field -> (eval(e.target, ctx) as? Obj)?.fields?.get(e.name) ?: Null
        is Expr.Index -> {
            val target = eval(e.target, ctx)
            val index = eval(e.index, ctx)
            when {
                target is ListV && index is Num && index.v % 1.0 == 0.0 ->
                    if (index.v >= 0 && index.v < target.items.size) target.items[index.v.toInt()] else Null
                target is Obj && index is Str -> target.fields[index.v] ?: Null
                else -> Null
            }
        }
    }

    private fun call(e: Expr.Call, ctx: EvalContext): Value {
        val fn = Library[e.fn] ?: return Null
        if (e.args.size < fn.minArgs || e.args.size > fn.maxArgs) return Null
        val args = e.args.map { eval(it, ctx) }
        return try {
            fn.impl(args, ctx)
        } catch (_: RuntimeException) {
            Null
        }
    }

    private fun binary(e: Expr.Binary, ctx: EvalContext): Value {
        if (e.op == "&&") return Bool(eval(e.left, ctx).truthy() && eval(e.right, ctx).truthy())
        if (e.op == "||") return Bool(eval(e.left, ctx).truthy() || eval(e.right, ctx).truthy())
        val l = eval(e.left, ctx)
        val r = eval(e.right, ctx)
        return when (e.op) {
            "==" -> Bool(l == r)
            "!=" -> Bool(l != r)
            "+" -> when {
                l is Num && r is Num -> Value.of(l.v + r.v)
                l is Str || r is Str -> Str((l.display() + r.display()).take(MAX_TEXT))
                else -> Null
            }
            "-", "*", "/", "%" -> {
                if (l !is Num || r !is Num) return Null
                when (e.op) {
                    "-" -> Value.of(l.v - r.v)
                    "*" -> Value.of(l.v * r.v)
                    "/" -> if (r.v == 0.0) Null else Value.of(l.v / r.v)
                    else -> if (r.v == 0.0) Null else Value.of(l.v % r.v)
                }
            }
            else -> {
                val c = when {
                    l is Num && r is Num -> l.v.compareTo(r.v)
                    l is Str && r is Str -> l.v.compareTo(r.v)
                    else -> return Null
                }
                Bool(
                    when (e.op) {
                        "<" -> c < 0
                        "<=" -> c <= 0
                        ">" -> c > 0
                        else -> c >= 0
                    }
                )
            }
        }
    }

    /** Renders a parsed template. A `{formula}` with no value shows [MISSING]. */
    fun render(parts: List<Part>, ctx: EvalContext): String {
        val sb = StringBuilder()
        for (p in parts) {
            when (p) {
                is Part.Text -> sb.append(p.text)
                is Part.Code -> {
                    val v = eval(p.expr, ctx)
                    sb.append(if (v == Null) MISSING else v.display())
                }
            }
            if (sb.length > MAX_TEXT) return sb.substring(0, MAX_TEXT)
        }
        return sb.toString()
    }
}
