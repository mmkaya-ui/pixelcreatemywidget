package dev.afterdrop.formula

import dev.afterdrop.formula.Type.ANY
import dev.afterdrop.formula.Type.LIST
import dev.afterdrop.formula.Type.NUM
import dev.afterdrop.formula.Type.STR
import dev.afterdrop.formula.Value.ListV
import dev.afterdrop.formula.Value.Null
import dev.afterdrop.formula.Value.Num
import dev.afterdrop.formula.Value.Obj
import dev.afterdrop.formula.Value.Str
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Static type of a formula. [ANY] is used wherever data shape is only known at run time. */
enum class Type { NUM, STR, BOOL, LIST, OBJ, ANY }

/**
 * One library function. [params] lists positional parameter types; the last [optional] of them
 * may be omitted. With [variadic], any number of further arguments of the last type is allowed.
 */
class Fn internal constructor(
    val name: String,
    val params: List<Type>,
    val optional: Int,
    val variadic: Boolean,
    val returns: Type,
    val readsClock: Boolean,
    internal val impl: (List<Value>, EvalContext) -> Value,
) {
    val minArgs: Int get() = params.size - optional
    val maxArgs: Int get() = if (variadic) Int.MAX_VALUE else params.size
}

/** The closed function library. A formula can call nothing else. */
object Library {
    private val fns = LinkedHashMap<String, Fn>()

    val names: Set<String> get() = fns.keys

    operator fun get(name: String): Fn? = fns[name]

    private fun def(
        name: String, params: List<Type>, returns: Type,
        optional: Int = 0, variadic: Boolean = false, readsClock: Boolean = false,
        impl: (List<Value>, EvalContext) -> Value,
    ) {
        fns[name] = Fn(name, params, optional, variadic, returns, readsClock, impl)
    }

    private fun List<Value>.num(i: Int): Double? = (getOrNull(i) as? Num)?.v
    private fun List<Value>.str(i: Int): String? = (getOrNull(i) as? Str)?.v
    private fun List<Value>.list(i: Int): List<Value>? = (getOrNull(i) as? ListV)?.items

    private fun num1(name: String, f: (Double) -> Double) =
        def(name, listOf(NUM), NUM) { a, _ -> a.num(0)?.let { Value.of(f(it)) } ?: Null }

    private fun decimals(a: List<Value>, i: Int): Int = (a.num(i) ?: 0.0).coerceIn(0.0, 6.0).toInt()

    private fun fixed(v: Double, places: Int, ctx: EvalContext): String {
        val text = String.format(ctx.locale, "%.${places}f", v)
        return if (text.startsWith("-") && text.none { it in '1'..'9' }) text.substring(1) else text
    }

    private fun numbers(items: List<Value>): List<Double>? {
        val out = ArrayList<Double>(items.size)
        for (item in items) {
            when (item) {
                is Num -> out += item.v
                Null -> {}
                else -> return null
            }
        }
        return out
    }

    private fun extreme(a: List<Value>, pick: (List<Double>) -> Double): Value {
        val items = if (a.size == 1 && a[0] is ListV) (a[0] as ListV).items else a
        if (items.any { it !is Num }) return Null
        if (items.isEmpty()) return Null
        return Value.of(pick(items.map { (it as Num).v }))
    }

    private const val MAX_MILLIS = 8.64e15

    private fun zoned(millis: Double, ctx: EvalContext): ZonedDateTime? {
        if (millis.isNaN() || Math.abs(millis) > MAX_MILLIS) return null
        return Instant.ofEpochMilli(millis.toLong()).atZone(ctx.zone)
    }

    private fun millisOf(z: ZonedDateTime): Value = Value.of(z.toInstant().toEpochMilli())

    private fun between(a: List<Value>, unitMillis: Double): Value {
        val from = a.num(0) ?: return Null
        val to = a.num(1) ?: return Null
        return Value.of(Math.floor((to - from) / unitMillis))
    }

    init {
        // Maths
        def("round", listOf(NUM, NUM), NUM, optional = 1) { a, _ ->
            val x = a.num(0) ?: return@def Null
            val scale = Math.pow(10.0, decimals(a, 1).toDouble())
            Value.of(Math.floor(x * scale + 0.5) / scale)
        }
        num1("floor") { Math.floor(it) }
        num1("ceil") { Math.ceil(it) }
        num1("abs") { Math.abs(it) }
        def("min", listOf(ANY), NUM, variadic = true) { a, _ -> extreme(a) { it.min() } }
        def("max", listOf(ANY), NUM, variadic = true) { a, _ -> extreme(a) { it.max() } }
        def("clamp", listOf(NUM, NUM, NUM), NUM) { a, _ ->
            val x = a.num(0) ?: return@def Null
            val lo = a.num(1) ?: return@def Null
            val hi = a.num(2) ?: return@def Null
            if (lo > hi) Null else Value.of(Math.min(Math.max(x, lo), hi))
        }

        // Text and numbers
        def("num", listOf(NUM, NUM), STR, optional = 1) { a, ctx ->
            a.num(0)?.let { Str(fixed(it, decimals(a, 1), ctx)) } ?: Null
        }
        def("pct", listOf(NUM, NUM), STR, optional = 1) { a, ctx ->
            a.num(0)?.let { Str(fixed(it * 100, decimals(a, 1), ctx) + "%") } ?: Null
        }
        def("upper", listOf(STR), STR) { a, ctx -> a.str(0)?.let { Str(it.uppercase(ctx.locale)) } ?: Null }
        def("lower", listOf(STR), STR) { a, ctx -> a.str(0)?.let { Str(it.lowercase(ctx.locale)) } ?: Null }
        def("trim", listOf(STR), STR) { a, _ -> a.str(0)?.let { Str(it.trim()) } ?: Null }
        def("len", listOf(ANY), NUM) { a, _ ->
            when (val v = a[0]) {
                is Str -> Value.of(v.v.codePointCount(0, v.v.length))
                is ListV -> Value.of(v.items.size)
                else -> Null
            }
        }

        // Lists
        def("count", listOf(LIST), NUM) { a, _ ->
            when (val v = a[0]) {
                is ListV -> Value.of(v.items.size)
                Null -> Value.of(0)
                else -> Null
            }
        }
        def("first", listOf(LIST), ANY) { a, _ -> a.list(0)?.firstOrNull() ?: Null }
        def("last", listOf(LIST), ANY) { a, _ -> a.list(0)?.lastOrNull() ?: Null }
        def("take", listOf(LIST, NUM), LIST) { a, _ ->
            val items = a.list(0) ?: return@def Null
            val n = a.num(1) ?: return@def Null
            ListV(items.take(n.coerceIn(0.0, items.size.toDouble()).toInt()))
        }
        def("skip", listOf(LIST, NUM), LIST) { a, _ ->
            val items = a.list(0) ?: return@def Null
            val n = a.num(1) ?: return@def Null
            ListV(items.drop(n.coerceIn(0.0, items.size.toDouble()).toInt()))
        }
        def("sum", listOf(LIST), NUM) { a, _ ->
            val xs = a.list(0)?.let { numbers(it) } ?: return@def Null
            Value.of(xs.sum())
        }
        def("avg", listOf(LIST), NUM) { a, _ ->
            val xs = a.list(0)?.let { numbers(it) } ?: return@def Null
            if (xs.isEmpty()) Null else Value.of(xs.sum() / xs.size)
        }
        def("pluck", listOf(LIST, STR), LIST) { a, _ ->
            val items = a.list(0) ?: return@def Null
            val field = a.str(1) ?: return@def Null
            ListV(items.map { (it as? Obj)?.fields?.get(field) ?: Null })
        }
        def("where", listOf(LIST, STR, ANY), LIST, optional = 1) { a, _ ->
            val items = a.list(0) ?: return@def Null
            val field = a.str(1) ?: return@def Null
            ListV(items.filter {
                val v = (it as? Obj)?.fields?.get(field) ?: Null
                if (a.size > 2) v == a[2] else v.truthy()
            })
        }

        // Time. Instants are epoch milliseconds; calendar maths uses the context's zone.
        def("now", emptyList(), NUM, readsClock = true) { _, ctx -> Value.of(ctx.nowMillis) }
        def("today", emptyList(), NUM, readsClock = true) { _, ctx ->
            zoned(ctx.nowMillis.toDouble(), ctx)?.let { millisOf(it.toLocalDate().atStartOfDay(ctx.zone)) } ?: Null
        }
        def("date", listOf(STR), NUM) { a, ctx ->
            val s = a.str(0)?.trim() ?: return@def Null
            runCatching { millisOf(LocalDate.parse(s).atStartOfDay(ctx.zone)) }
                .recoverCatching { millisOf(LocalDateTime.parse(s).atZone(ctx.zone)) }
                .recoverCatching { Value.of(OffsetDateTime.parse(s).toInstant().toEpochMilli()) }
                .getOrDefault(Null)
        }
        def("days", listOf(NUM, NUM), NUM) { a, ctx ->
            val from = a.num(0)?.let { zoned(it, ctx) } ?: return@def Null
            val to = a.num(1)?.let { zoned(it, ctx) } ?: return@def Null
            Value.of(ChronoUnit.DAYS.between(from.toLocalDate(), to.toLocalDate()))
        }
        def("hours", listOf(NUM, NUM), NUM) { a, _ -> between(a, 3_600_000.0) }
        def("minutes", listOf(NUM, NUM), NUM) { a, _ -> between(a, 60_000.0) }
        def("addDays", listOf(NUM, NUM), NUM) { a, ctx ->
            val t = a.num(0)?.let { zoned(it, ctx) } ?: return@def Null
            val n = a.num(1) ?: return@def Null
            if (Math.abs(n) > 36_600) Null else millisOf(t.plusDays(n.toLong()))
        }
        def("startOfDay", listOf(NUM), NUM) { a, ctx ->
            a.num(0)?.let { zoned(it, ctx) }?.let { millisOf(it.toLocalDate().atStartOfDay(ctx.zone)) } ?: Null
        }
        def("fmt", listOf(NUM, STR), STR) { a, ctx ->
            val t = a.num(0)?.let { zoned(it, ctx) } ?: return@def Null
            val pattern = a.str(1) ?: return@def Null
            if (pattern.length > 32) Null else Str(DateTimeFormatter.ofPattern(pattern, ctx.locale).format(t))
        }
        def("rel", listOf(NUM), STR, readsClock = true) { a, ctx ->
            val t = a.num(0) ?: return@def Null
            val diff = t - ctx.nowMillis
            val mins = Math.floor(Math.abs(diff) / 60_000.0).toLong()
            val amount = when {
                mins < 1 -> return@def Str("now")
                mins < 60 -> "$mins min"
                mins < 48 * 60 -> "${mins / 60} h"
                else -> "${mins / 1440} d"
            }
            Str(if (diff > 0) "in $amount" else "$amount ago")
        }

        // Other
        def("coalesce", listOf(ANY), ANY, variadic = true) { a, _ -> a.firstOrNull { it != Null } ?: Null }
        num1("c2f") { it * 9 / 5 + 32 }
        num1("f2c") { (it - 32) * 5 / 9 }
        num1("km2mi") { it * 0.621371 }
        num1("mi2km") { it / 0.621371 }
    }
}
