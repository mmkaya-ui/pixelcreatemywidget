package dev.afterdrop.engine

import dev.afterdrop.formula.EvalContext
import dev.afterdrop.formula.Evaluator
import dev.afterdrop.formula.FormulaError
import dev.afterdrop.formula.Parser
import dev.afterdrop.formula.Template
import dev.afterdrop.formula.Value
import dev.afterdrop.formula.truthy
import dev.afterdrop.spec.Node
import dev.afterdrop.spec.WidgetSpec
import java.time.ZoneId
import java.util.Locale

/** How much room the widget has. A node with `from` is shown only at that class or larger. */
enum class SizeClass {
    XS, S, M, L;

    companion object {
        /** Width picks the class; a short widget (under 100 dp) drops one class. */
        fun of(widthDp: Int, heightDp: Int): SizeClass {
            val byWidth = when {
                widthDp < 120 -> XS
                widthDp < 220 -> S
                widthDp < 320 -> M
                else -> L
            }
            return if (heightDp < 100 && byWidth != XS) entries[byWidth.ordinal - 1] else byWidth
        }

        fun parse(s: String?): SizeClass = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: XS
    }
}

/** Everything needed to turn a spec into something drawable. Pure data: no Android types. */
class RenderInput(
    val spec: WidgetSpec,
    /** Source alias to its current data. A missing alias reads as null. */
    val data: Map<String, Value>,
    /** Current variable values, from [Vars.current]. */
    val vars: Map<String, Value>,
    val nowMillis: Long,
    val zone: ZoneId,
    val locale: Locale,
    val size: SizeClass,
)

/** What a tap does, resolved so the platform layer needs no formula knowledge. */
sealed interface Tap {
    data class OpenUrl(val url: String) : Tap
    data class OpenApp(val pkg: String) : Tap
    data class OpenSource(val alias: String, val use: String) : Tap
    data object Edit : Tap
    data object Refresh : Tap
    data class Ask(val variable: String, val title: String, val number: Boolean) : Tap
    /** Assigns a variable. Perform it with [Engine.act] and op "tap". */
    data object SetVar : Tap
}

/** One visible node with every formula already evaluated. */
class RNode internal constructor(
    val node: Node,
    /** Stable address of this node within one render, used to route actions back. */
    val key: String,
    /** Rendered text of a text node, a label, or a list's empty message. */
    val text: String?,
    /** Progress as 0..1. */
    val fraction: Double?,
    /** Chart values. */
    val series: List<Double>?,
    /** Timer target, epoch milliseconds. */
    val instant: Double?,
    /** Current value of the variable a control edits. */
    val state: Value?,
    val kids: List<RNode>,
    val tap: Tap?,
    internal val item: Value?,
) {
    val type: String get() = node.type
    internal fun withKids(kept: List<RNode>): RNode = RNode(node, key, text, fraction, series, instant, state, kept, tap, item)
    fun all(): Sequence<RNode> = sequence {
        yield(this@RNode)
        for (k in kids) yieldAll(k.all())
    }
}

object Engine {
    const val DEFAULT_LIST_LIMIT = 5

    /** Resolves the spec to a tree of visible nodes. Total: bad or missing data never throws. */
    fun resolve(input: RenderInput): RNode =
        Resolver(input).node(input.spec.root, "r", null) ?: RNode(input.spec.root, "r", null, null, null, null, null, emptyList(), null, null)

    /**
     * Applies a control or tap and returns the new variable values, or null when nothing changes.
     * [op] is "inc" or "dec" for a stepper, "toggle" for a toggle or check, "tap" for a `set` action.
     */
    fun act(input: RenderInput, key: String, op: String): Map<String, Value>? {
        val r = Resolver(input)
        val target = (r.node(input.spec.root, "r", null) ?: return null).all().firstOrNull { it.key == key } ?: return null
        val n = target.node
        val name: String
        val next: Value
        when (op) {
            "inc", "dec" -> {
                if (n.type != "stepper") return null
                name = n.text("var") ?: return null
                val cur = (input.vars[name] as? Value.Num)?.v ?: return null
                val step = n.number("step") ?: 1.0
                var v = if (op == "inc") cur + step else cur - step
                n.number("min")?.let { v = Math.max(v, it) }
                n.number("max")?.let { v = Math.min(v, it) }
                next = Value.Num(v)
            }
            "toggle" -> {
                if (n.type != "toggle" && n.type != "check") return null
                name = n.text("var") ?: return null
                next = Value.Bool(!(input.vars[name]?.truthy() ?: false))
            }
            "tap" -> {
                val tap = n.tap ?: return null
                if (tap.kind != "set") return null
                name = tap.text("var") ?: return null
                next = r.eval(tap.text("to") ?: return null, target.item, r.itInScope(target))
            }
            else -> return null
        }
        val variable = input.spec.vars[name] ?: return null
        val coerced = Vars.coerce(variable, next) ?: return null
        if (input.vars[name] == coerced) return null
        return input.vars + (name to coerced)
    }
}

private class Resolver(val input: RenderInput) {
    private val calc = HashMap<String, Value>()
    private val busy = HashSet<String>()
    private val inList = HashSet<String>()

    fun itInScope(n: RNode) = n.key in inList

    private fun ctx(item: Value?, hasItem: Boolean) = EvalContext(input.nowMillis, input.zone, input.locale) { name ->
        when {
            name == "it" && hasItem -> item
            name in input.spec.sources -> input.data[name]
            name in input.spec.vars -> input.vars[name]
            name in input.spec.calc -> calcValue(name)
            else -> null
        }
    }

    private fun calcValue(name: String): Value {
        calc[name]?.let { return it }
        if (!busy.add(name)) return Value.Null
        val v = eval(input.spec.calc.getValue(name), null, false)
        busy.remove(name)
        calc[name] = v
        return v
    }

    fun eval(src: String, item: Value?, hasItem: Boolean): Value = try {
        Evaluator.eval(Parser.parse(src), ctx(item, hasItem))
    } catch (_: FormulaError) {
        Value.Null
    }

    private fun render(tpl: String, item: Value?, hasItem: Boolean): String = try {
        Evaluator.render(Template.parse(tpl), ctx(item, hasItem))
    } catch (_: FormulaError) {
        ""
    }

    fun node(n: Node, key: String, item: Value?, hasItem: Boolean = false): RNode? {
        if (SizeClass.parse(n.text("from")).ordinal > input.size.ordinal) return null
        n.text("if")?.let { if (!eval(it, item, hasItem).truthy()) return null }
        if (hasItem) inList += key

        var text: String? = (n.text("text") ?: n.text("label"))?.let { render(it, item, hasItem) }
        var fraction: Double? = null
        var series: List<Double>? = null
        var instant: Double? = null
        var kids: List<RNode> = emptyList()

        when (n.type) {
            "progress" -> {
                val v = (eval(n.text("value")!!, item, hasItem) as? Value.Num)?.v
                val min = n.number("min") ?: 0.0
                val max = n.number("max") ?: 1.0
                fraction = if (v == null || max <= min) 0.0 else ((v - min) / (max - min)).coerceIn(0.0, 1.0)
            }
            "chart" -> series = (eval(n.text("values")!!, item, hasItem) as? Value.ListV)?.items
                ?.mapNotNull { (it as? Value.Num)?.v } ?: emptyList()
            "timer" -> instant = (eval(n.text("to")!!, item, hasItem) as? Value.Num)?.v
            "list" -> {
                val items = (eval(n.text("of")!!, item, hasItem) as? Value.ListV)?.items ?: emptyList()
                val limit = n.int("limit") ?: Engine.DEFAULT_LIST_LIMIT
                kids = items.take(limit).mapIndexedNotNull { i, v -> node(n.item!!, "$key#$i", v, true) }
                if (kids.isEmpty()) text = n.text("empty")?.let { render(it, item, hasItem) }
            }
            else -> kids = n.kids.mapIndexedNotNull { i, k -> node(k, "$key.$i", item, hasItem) }
        }

        val tap: Tap? = n.tap?.let { a ->
            when (a.kind) {
                "open_url" -> Tap.OpenUrl(a.text("url")!!)
                "open_app" -> Tap.OpenApp(a.text("pkg")!!)
                "open_source" -> a.text("source")!!.let { Tap.OpenSource(it, input.spec.sources[it]?.use ?: "") }
                "edit" -> Tap.Edit
                "refresh" -> Tap.Refresh
                "ask" -> {
                    val name = a.text("var")!!
                    val type = input.spec.vars[name]?.type
                    Tap.Ask(name, a.text("title") ?: name, (a.text("kind") ?: if (type == "text") "text" else "number") == "number")
                }
                "set" -> Tap.SetVar
                else -> null
            }
        }
        return RNode(n, key, text, fraction, series, instant, n.text("var")?.let { input.vars[it] }, kids, tap, item)
    }
}
