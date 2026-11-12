package dev.afterdrop.spec

import dev.afterdrop.formula.Analysis
import dev.afterdrop.formula.Checker
import dev.afterdrop.formula.Expr
import dev.afterdrop.formula.FormulaError
import dev.afterdrop.formula.Parser
import dev.afterdrop.formula.Part
import dev.afterdrop.formula.Template
import dev.afterdrop.formula.Type

/**
 * Rules the schema cannot express: names resolve, formulas type-check, size limits hold, each node
 * uses only its own properties, and no private value can reach a network request.
 * Runs only on a spec that already passed [SchemaCheck], so shapes can be trusted here.
 */
internal class Lint(private val spec: JObject) {
    val problems = ArrayList<Problem>()

    private val sources: Map<String, JObject> = section("sources")
    private val vars: Map<String, JObject> = section("vars")
    private val calc: Map<String, String> =
        (spec["calc"] as? JObject)?.fields?.mapValues { (it.value as JString).value } ?: emptyMap()

    /** What each name reads, for the information-flow check. `set` actions add edges into variables. */
    private val reads = HashMap<String, MutableSet<String>>()
    private val calcTypes = HashMap<String, Type>()
    private val resolving = HashSet<String>()
    private var nodes = 0
    private val ids = HashSet<String>()

    @Suppress("UNCHECKED_CAST")
    private fun section(key: String): Map<String, JObject> =
        ((spec[key] as? JObject)?.fields ?: emptyMap()) as Map<String, JObject>

    private fun bad(code: String, path: String, message: String) {
        problems += Problem(Gate.LINT, "lint.$code", path, message)
    }

    private fun use(alias: String): String? = (sources[alias]?.get("use") as? JString)?.value
    private fun varType(name: String): String? = (vars[name]?.get("type") as? JString)?.value

    private fun typeOfVar(name: String): Type = when (varType(name)) {
        "int", "num" -> Type.NUM
        "bool" -> Type.BOOL
        else -> Type.STR
    }

    /** Names visible to a formula. [itReads] is non-null inside a list item, where `it` is in scope. */
    private fun env(itReads: Set<String>?): (String) -> Type? = { name ->
        when {
            name == "it" && itReads != null -> Type.ANY
            name in sources -> Type.OBJ
            name in vars -> typeOfVar(name)
            name in calc -> calcType(name)
            else -> null
        }
    }

    private fun calcType(name: String): Type {
        calcTypes[name]?.let { return it }
        if (!resolving.add(name)) {
            bad("calc_cycle", "calc.$name", "\"$name\" is defined in terms of itself")
            return Type.ANY
        }
        val a = formula(calc.getValue(name), "calc.$name", null, null)
        resolving.remove(name)
        val type = a?.type ?: Type.ANY
        calcTypes[name] = type
        reads.getOrPut(name) { HashSet() }.addAll(a?.names ?: emptySet())
        return type
    }

    /** Parses and checks one formula. [want] is the type the property needs, or null for any. */
    private fun formula(src: String, path: String, itReads: Set<String>?, want: Type?): Analysis? {
        val expr = try {
            Parser.parse(src)
        } catch (e: FormulaError) {
            bad("formula_syntax", path, "${e.message} in \"$src\"")
            return null
        }
        return analyze(expr, src, path, itReads, want)
    }

    private fun analyze(expr: Expr, src: String, path: String, itReads: Set<String>?, want: Type?): Analysis {
        val a = Checker.analyze(expr, env(itReads))
        for (issue in a.issues) bad(issue.code, path, "${issue.message} in \"$src\"")
        if (want != null && a.type != Type.ANY && a.type != want) {
            bad("type", path, "\"$src\" must give ${word(want)}")
        }
        return a
    }

    private fun word(t: Type) = when (t) {
        Type.NUM -> "a number"
        Type.STR -> "text"
        Type.BOOL -> "true or false"
        Type.LIST -> "a list"
        else -> "a value"
    }

    private fun template(tpl: String, path: String, itReads: Set<String>?): Set<String> {
        val parts = try {
            Template.parse(tpl)
        } catch (e: FormulaError) {
            bad("template_syntax", path, "${e.message} in \"$tpl\"")
            return emptySet()
        }
        val names = HashSet<String>()
        for (p in parts) if (p is Part.Code) names += analyze(p.expr, p.source, path, itReads, null).names
        return names
    }

    fun run() {
        names()
        for (name in calc.keys) calcType(name)
        for ((name, v) in vars) variable(name, v)
        walk(spec["root"] as JObject, "root", 1, null, false)
        if (nodes > Vocabulary.MAX_NODES) bad("node_count", "root", "$nodes nodes, the limit is ${Vocabulary.MAX_NODES}")
        flows()
    }

    private fun names() {
        val seen = HashSet<String>()
        for ((section, keys) in listOf("sources" to sources.keys, "vars" to vars.keys, "calc" to calc.keys)) {
            for (k in keys) {
                if (k == "it" || k == "true" || k == "false") bad("reserved_name", "$section.$k", "\"$k\" is reserved")
                else if (!seen.add(k)) bad("duplicate_name", "$section.$k", "\"$k\" is already used by another source, variable or calc")
            }
        }
    }

    private fun variable(name: String, v: JObject) {
        val init = v["init"]
        val ok = when (varType(name)) {
            "int" -> init is JNumber && init.isInteger
            "num" -> init is JNumber
            "bool" -> init is JBool
            else -> init is JString
        }
        if (!ok) bad("var_init", "vars.$name.init", "the starting value does not match type \"${varType(name)}\"")
    }

    private fun str(o: JObject, key: String): String? = (o[key] as? JString)?.value

    private fun walk(node: JObject, path: String, depth: Int, itReads: Set<String>?, inList: Boolean) {
        nodes++
        val type = str(node, "t")!!
        if (depth > Vocabulary.MAX_DEPTH) bad("depth", path, "nested deeper than ${Vocabulary.MAX_DEPTH} levels")

        val allowed = Vocabulary.PROPS.getValue(type)
        for (k in node.fields.keys) {
            if (k !in allowed) bad("property_not_allowed", "$path.$k", "a \"$type\" node has no \"$k\" property")
        }
        val kind = str(node, "kind")
        if (kind != null && "kind" in allowed && kind !in Vocabulary.KINDS.getValue(type)) {
            bad("property_not_allowed", "$path.kind", "\"$kind\" is not a kind of $type")
        }

        str(node, "if")?.let { formula(it, "$path.if", itReads, null) }
        str(node, "value")?.let { formula(it, "$path.value", itReads, Type.NUM) }
        str(node, "values")?.let { formula(it, "$path.values", itReads, Type.LIST) }
        str(node, "to")?.let { formula(it, "$path.to", itReads, Type.NUM) }
        for (key in listOf("text", "label", "empty")) str(node, key)?.let { template(it, "$path.$key", itReads) }

        str(node, "id")?.let { if (!ids.add(it)) bad("duplicate_id", "$path.id", "id \"$it\" is used by more than one node") }
        if (type == "clock") {
            str(node, "format")?.let {
                val ok = it.isNotBlank() && runCatching { java.time.format.DateTimeFormatter.ofPattern(it) }.isSuccess
                if (!ok) bad("clock_format", "$path.format", "\"$it\" is not a date and time pattern such as HH:mm or EEE d MMM")
            }
            str(node, "zone")?.let {
                if (runCatching { java.time.ZoneId.of(it) }.isFailure) bad("clock_zone", "$path.zone", "\"$it\" is not a time zone such as Europe/Berlin")
            }
        }

        val min = (node["min"] as? JNumber)?.value
        val max = (node["max"] as? JNumber)?.value
        if (min != null && max != null && min >= max) bad("range", "$path.min", "min must be below max")

        str(node, "var")?.let { name ->
            if (name !in vars) bad("unknown_var", "$path.var", "variable \"$name\" is not declared in vars")
            else {
                val fits = when (type) {
                    "toggle", "check" -> varType(name) == "bool"
                    "stepper" -> varType(name) == "int" || varType(name) == "num"
                    else -> true
                }
                if (!fits) bad("var_type", "$path.var", "a $type cannot change \"$name\", which is ${varType(name)}")
            }
        }
        (node["tap"] as? JObject)?.let { action(it, "$path.tap", itReads) }

        if (type == "list") {
            if (inList) bad("nested_list", path, "a list cannot sit inside another list")
            val of = formula(str(node, "of")!!, "$path.of", itReads, Type.LIST)
            val inner = (of?.names ?: emptySet()).flatMapTo(HashSet()) { if (it == "it") itReads ?: emptySet() else setOf(it) }
            walk(node["item"] as JObject, "$path.item", depth + 1, inner, true)
        }
        (node["kids"] as? JArray)?.items?.forEachIndexed { i, kid ->
            walk(kid as JObject, "$path.kids[$i]", depth + 1, itReads, inList)
        }
    }

    private fun action(tap: JObject, path: String, itReads: Set<String>?) {
        val name = str(tap, "var")
        if (name != null && name !in vars) bad("unknown_var", "$path.var", "variable \"$name\" is not declared in vars")
        str(tap, "source")?.let {
            if (it !in sources) bad("unknown_source", "$path.source", "source \"$it\" is not declared in sources")
        }
        val known = name != null && name in vars
        when (str(tap, "do")) {
            "set" -> {
                val a = formula(str(tap, "to")!!, "$path.to", itReads, if (known) typeOfVar(name!!) else null)
                if (known && a != null) {
                    val into = reads.getOrPut(name!!) { HashSet() }
                    for (n in a.names) if (n == "it") into.addAll(itReads ?: emptySet()) else into += n
                }
            }
            "ask" -> if (known) {
                val wantsNumber = varType(name!!) == "int" || varType(name) == "num"
                val kind = str(tap, "kind") ?: if (wantsNumber) "number" else "text"
                if (varType(name) == "bool" || (kind == "number") != wantsNumber) {
                    bad("var_type", "$path.var", "asking for $kind cannot fill \"$name\", which is ${varType(name)}")
                }
            }
        }
    }

    /** True when [name] can carry a value that came from a private source, directly or through calc and variables. */
    private fun carriesPrivate(name: String, seen: MutableSet<String> = HashSet()): Boolean {
        if (!seen.add(name)) return false
        if (use(name) in Vocabulary.PRIVATE_SOURCES) return true
        return reads[name]?.any { carriesPrivate(it, seen) } == true
    }

    /** Information flow: parameters of http, feed and ai sources are sent out, so they may not read private data. */
    private fun flows() {
        for ((alias, src) in sources) {
            if (use(alias) !in Vocabulary.NETWORK_SOURCES) continue
            strings(src["with"], "sources.$alias.with") { text, path ->
                if ('{' in text || '}' in text) {
                    for (name in template(text, path, null)) {
                        if (carriesPrivate(name)) {
                            bad("private_flow", path, "\"$name\" holds private data and cannot be sent in a ${use(alias)} request")
                        }
                    }
                }
            }
        }
    }

    private fun strings(v: JsonValue?, path: String, visit: (String, String) -> Unit) {
        when (v) {
            is JString -> visit(v.value, path)
            is JArray -> v.items.forEachIndexed { i, item -> strings(item, "$path[$i]", visit) }
            is JObject -> for ((k, item) in v.fields) strings(item, "$path.$k", visit)
            else -> {}
        }
    }
}
