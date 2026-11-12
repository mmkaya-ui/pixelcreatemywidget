package dev.afterdrop.engine

import dev.afterdrop.formula.Value
import dev.afterdrop.spec.JBool
import dev.afterdrop.spec.JNumber
import dev.afterdrop.spec.JObject
import dev.afterdrop.spec.JString
import dev.afterdrop.spec.JsonValue
import dev.afterdrop.spec.Variable
import dev.afterdrop.spec.WidgetSpec
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** A variable's saved value and when it was last set. */
class StoredVar(val value: Value, val setAt: Long)

/** Widget variables: starting values, daily and weekly resets, type coercion and storage form. */
object Vars {
    fun initial(v: Variable): Value = when (val i = v.init) {
        is JNumber -> Value.Num(i.value)
        is JBool -> Value.Bool(i.value)
        is JString -> Value.Str(i.value)
        else -> Value.Null
    }

    /** The values to render with: stored ones, unless missing, of the wrong type, or past their reset. */
    fun current(spec: WidgetSpec, stored: Map<String, StoredVar>, nowMillis: Long, zone: ZoneId): Map<String, Value> =
        spec.vars.mapValues { (name, v) ->
            val s = stored[name]
            val kept = s?.takeIf { !expired(v.reset, it.setAt, nowMillis, zone) }?.let { coerce(v, it.value) }
            kept ?: initial(v)
        }

    private fun expired(reset: String, setAt: Long, now: Long, zone: ZoneId): Boolean {
        if (reset == "never") return false
        val was = day(setAt, zone)
        val today = day(now, zone)
        return when (reset) {
            "daily" -> was != today
            else -> monday(was) != monday(today)
        }
    }

    private fun day(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    private fun monday(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Fits [value] to the variable's type, or null when it cannot hold it. Whole-number variables round. */
    fun coerce(v: Variable, value: Value): Value? = when (v.type) {
        "int" -> (value as? Value.Num)?.takeIf { !it.v.isNaN() && !it.v.isInfinite() }?.let { Value.Num(Math.rint(it.v)) }
        "num" -> (value as? Value.Num)?.takeIf { !it.v.isNaN() && !it.v.isInfinite() }
        "bool" -> value as? Value.Bool
        else -> (value as? Value.Str)?.let { Value.Str(it.v.take(500)) }
    }

    fun encode(stored: Map<String, StoredVar>): JsonValue = JObject(stored.mapValues { (_, s) ->
        JObject(
            mapOf(
                "v" to when (val v = s.value) {
                    is Value.Num -> JNumber(v.v)
                    is Value.Bool -> JBool(v.v)
                    is Value.Str -> JString(v.v)
                    else -> JString("")
                },
                "at" to JNumber(s.setAt.toDouble()),
            )
        )
    })

    fun decode(json: JsonValue?): Map<String, StoredVar> {
        val o = json as? JObject ?: return emptyMap()
        val out = LinkedHashMap<String, StoredVar>()
        for ((name, e) in o.fields) {
            val entry = e as? JObject ?: continue
            val at = (entry["at"] as? JNumber)?.value?.toLong() ?: continue
            val value: Value = when (val v = entry["v"]) {
                is JNumber -> Value.Num(v.value)
                is JBool -> Value.Bool(v.value)
                is JString -> Value.Str(v.value)
                else -> continue
            }
            out[name] = StoredVar(value, at)
        }
        return out
    }
}

/** Converts source data held as JSON into formula values. */
fun JsonValue.toValue(): Value = when (this) {
    is JNumber -> Value.Num(value)
    is JBool -> Value.Bool(value)
    is JString -> Value.Str(value)
    is dev.afterdrop.spec.JArray -> Value.ListV(items.map { it.toValue() })
    is JObject -> Value.Obj(fields.mapValues { it.value.toValue() })
    else -> Value.Null
}
