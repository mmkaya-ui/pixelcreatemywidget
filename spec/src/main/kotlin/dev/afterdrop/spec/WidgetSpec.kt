package dev.afterdrop.spec

/** A validated widget. Instances only come from [SpecReader], so every field can be trusted. */
class WidgetSpec internal constructor(
    val name: String,
    val about: String,
    val cols: Int,
    val rows: Int,
    val theme: JObject,
    val sources: Map<String, Source>,
    val vars: Map<String, Variable>,
    val calc: Map<String, String>,
    val root: Node,
    /** The JSON this spec was read from, after migration. Store and share this. */
    val json: JObject,
)

class Source internal constructor(val use: String, val with: JObject, val every: String?)

class Variable internal constructor(val type: String, val init: JsonValue, val reset: String)

class Action internal constructor(val kind: String, private val props: JObject) {
    fun text(key: String): String? = (props[key] as? JString)?.value
}

/** One node of the layout tree. Properties are read by name; the validator guarantees their types. */
class Node internal constructor(
    val type: String,
    private val props: JObject,
    val kids: List<Node>,
    val item: Node?,
    val tap: Action?,
) {
    fun has(key: String): Boolean = key in props.fields
    fun text(key: String): String? = (props[key] as? JString)?.value
    fun int(key: String): Int? = (props[key] as? JNumber)?.value?.toInt()
    fun number(key: String): Double? = (props[key] as? JNumber)?.value
    fun flag(key: String): Boolean = (props[key] as? JBool)?.value ?: false

    /** This node and everything under it, depth first. */
    fun all(): Sequence<Node> = sequence {
        yield(this@Node)
        item?.let { yieldAll(it.all()) }
        for (k in kids) yieldAll(k.all())
    }
}

internal object Decoder {
    fun spec(o: JObject): WidgetSpec {
        val size = o["size"] as JObject
        return WidgetSpec(
            name = (o["name"] as JString).value,
            about = (o["about"] as? JString)?.value ?: "",
            cols = (size["cols"] as JNumber).value.toInt(),
            rows = (size["rows"] as JNumber).value.toInt(),
            theme = o["theme"] as? JObject ?: JObject(emptyMap()),
            sources = entries(o["sources"]) { s ->
                Source((s["use"] as JString).value, s["with"] as? JObject ?: JObject(emptyMap()), (s["every"] as? JString)?.value)
            },
            vars = entries(o["vars"]) { v ->
                Variable((v["type"] as JString).value, v.fields.getValue("init"), (v["reset"] as? JString)?.value ?: "never")
            },
            calc = (o["calc"] as? JObject)?.fields?.mapValues { (it.value as JString).value } ?: emptyMap(),
            root = node(o["root"] as JObject),
            json = o,
        )
    }

    private fun <T> entries(v: JsonValue?, make: (JObject) -> T): Map<String, T> =
        (v as? JObject)?.fields?.mapValues { make(it.value as JObject) } ?: emptyMap()

    private fun node(o: JObject): Node = Node(
        type = (o["t"] as JString).value,
        props = o,
        kids = (o["kids"] as? JArray)?.items?.map { node(it as JObject) } ?: emptyList(),
        item = (o["item"] as? JObject)?.let { node(it) },
        tap = (o["tap"] as? JObject)?.let { Action((it["do"] as JString).value, it) },
    )
}
