package dev.afterdrop.formula

import java.math.BigDecimal
import java.math.RoundingMode

/** A runtime value. Instants are [Num] epoch milliseconds. */
sealed interface Value {
    data class Num(val v: Double) : Value
    data class Str(val v: String) : Value
    data class Bool(val v: Boolean) : Value
    data class ListV(val items: List<Value>) : Value
    data class Obj(val fields: Map<String, Value>) : Value
    data object Null : Value

    companion object {
        fun of(v: Double): Value = if (v.isNaN() || v.isInfinite()) Null else Num(v)
        fun of(v: Int): Value = Num(v.toDouble())
        fun of(v: Long): Value = Num(v.toDouble())
        fun of(v: String): Value = Str(v)
        fun of(v: Boolean): Value = Bool(v)
    }
}

/** Truthiness used by `!`, `&&`, `||`, `?:` and node `if`. */
fun Value.truthy(): Boolean = when (this) {
    is Value.Bool -> v
    is Value.Num -> v != 0.0
    is Value.Str -> v.isNotEmpty()
    is Value.ListV -> items.isNotEmpty()
    is Value.Obj -> true
    Value.Null -> false
}

/** Text form used when a value is concatenated or shown. Null is empty here; templates show a dash instead. */
fun Value.display(): String = when (this) {
    is Value.Num -> displayNumber(v)
    is Value.Str -> v
    is Value.Bool -> if (v) "true" else "false"
    is Value.ListV -> items.joinToString(", ") { it.display() }
    is Value.Obj -> ""
    Value.Null -> ""
}

internal fun displayNumber(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return ""
    if (v % 1.0 == 0.0 && Math.abs(v) < 1e15) return v.toLong().toString()
    val text = BigDecimal(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    return if (text == "-0") "0" else text
}
