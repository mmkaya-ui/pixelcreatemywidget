package dev.afterdrop.formula

import dev.afterdrop.formula.Type.ANY
import dev.afterdrop.formula.Type.BOOL
import dev.afterdrop.formula.Type.LIST
import dev.afterdrop.formula.Type.NUM
import dev.afterdrop.formula.Type.OBJ
import dev.afterdrop.formula.Type.STR

/** A static problem in a formula. [code] is one of unknown_name, unknown_function, arity, type. */
data class Issue(val code: String, val message: String, val pos: Int)

/** What static analysis learned about one formula. */
class Analysis(
    val type: Type,
    val issues: List<Issue>,
    /** Root names read as values, such as a source alias, a variable or `it`. */
    val names: Set<String>,
    val functions: Set<String>,
    /** True when the result changes with time alone, so the scheduler must re-render. */
    val readsClock: Boolean,
)

/**
 * Static checker. It resolves names and functions, checks argument counts, and reports operand
 * types that can never work. Data whose shape is only known at run time is [Type.ANY] and passes.
 */
object Checker {
    fun analyze(expr: Expr, env: (String) -> Type?): Analysis {
        val run = Run(env)
        val type = run.infer(expr)
        return Analysis(type, run.issues, run.names, run.functions, run.readsClock)
    }

    private class Run(val env: (String) -> Type?) {
        val issues = ArrayList<Issue>()
        val names = LinkedHashSet<String>()
        val functions = LinkedHashSet<String>()
        var readsClock = false

        private fun typeIssue(message: String, pos: Int) {
            issues += Issue("type", message, pos)
        }

        fun infer(e: Expr): Type = when (e) {
            is Expr.Lit -> when (e.value) {
                is Value.Num -> NUM
                is Value.Str -> STR
                is Value.Bool -> BOOL
                is Value.ListV -> LIST
                is Value.Obj -> OBJ
                Value.Null -> ANY
            }
            is Expr.Name -> {
                names += e.name
                env(e.name) ?: run {
                    issues += Issue("unknown_name", "unknown name '${e.name}'", e.pos)
                    ANY
                }
            }
            is Expr.Call -> call(e)
            is Expr.Unary -> {
                val t = infer(e.operand)
                if (e.op == "!") BOOL else {
                    if (t != NUM && t != ANY) typeIssue("'-' needs a number, not ${word(t)}", e.pos)
                    NUM
                }
            }
            is Expr.Binary -> binary(e)
            is Expr.Ternary -> {
                infer(e.cond)
                val a = infer(e.then)
                val b = infer(e.otherwise)
                if (a == b) a else ANY
            }
            is Expr.Field -> {
                val t = infer(e.target)
                if (t != OBJ && t != ANY) typeIssue("${word(t)} has no field '${e.name}'", e.pos)
                ANY
            }
            is Expr.Index -> {
                val t = infer(e.target)
                infer(e.index)
                if (t != LIST && t != OBJ && t != ANY) typeIssue("${word(t)} cannot be indexed", e.pos)
                ANY
            }
        }

        private fun call(e: Expr.Call): Type {
            functions += e.fn
            val argTypes = e.args.map { infer(it) }
            val fn = Library[e.fn]
            if (fn == null) {
                issues += Issue("unknown_function", "unknown function ${e.fn}()", e.pos)
                return ANY
            }
            if (fn.readsClock) readsClock = true
            if (e.args.size < fn.minArgs || e.args.size > fn.maxArgs) {
                val want = when {
                    fn.variadic -> "at least ${fn.minArgs}"
                    fn.optional > 0 -> "${fn.minArgs} to ${fn.maxArgs}"
                    else -> "${fn.minArgs}"
                }
                issues += Issue("arity", "${e.fn}() takes $want argument(s), not ${e.args.size}", e.pos)
                return fn.returns
            }
            for ((i, got) in argTypes.withIndex()) {
                val want = fn.params.getOrElse(i) { fn.params.last() }
                if (want != ANY && got != ANY && want != got) {
                    typeIssue("argument ${i + 1} of ${e.fn}() must be ${word(want)}, not ${word(got)}", e.pos)
                }
            }
            return fn.returns
        }

        private fun binary(e: Expr.Binary): Type {
            val l = infer(e.left)
            val r = infer(e.right)
            return when (e.op) {
                "&&", "||", "==", "!=" -> BOOL
                "+" -> when {
                    l == STR || r == STR -> STR
                    l == NUM && r == NUM -> NUM
                    l == ANY || r == ANY -> ANY
                    else -> {
                        typeIssue("'+' cannot add ${word(l)} and ${word(r)}", e.pos)
                        ANY
                    }
                }
                "-", "*", "/", "%" -> {
                    for (t in listOf(l, r)) {
                        if (t != NUM && t != ANY) typeIssue("'${e.op}' needs numbers, not ${word(t)}", e.pos)
                    }
                    NUM
                }
                else -> {
                    val comparable = { t: Type -> t == NUM || t == STR || t == ANY }
                    if (!comparable(l) || !comparable(r) || (l != ANY && r != ANY && l != r)) {
                        typeIssue("'${e.op}' cannot compare ${word(l)} with ${word(r)}", e.pos)
                    }
                    BOOL
                }
            }
        }

        private fun word(t: Type) = when (t) {
            NUM -> "a number"
            STR -> "text"
            BOOL -> "true/false"
            LIST -> "a list"
            OBJ -> "an object"
            ANY -> "a value"
        }
    }
}
