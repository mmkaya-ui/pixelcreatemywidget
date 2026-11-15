package dev.afterdrop.composer

import dev.afterdrop.spec.Json

/**
 * The edits the visual editor makes, on a widget's JSON. Every edit returns a new document and leaves
 * the old one alone, so undo is just keeping the old one. Whether the result is a valid widget is
 * decided by the usual reader afterwards; these functions only refuse what makes no sense at all,
 * such as removing the root.
 *
 * A node is found by its path from the root: kid indexes, with "item" stepping into a list's item.
 * The root is the empty path.
 */
object SpecEdit {
    class Part(
        val path: List<String>,
        val depth: Int,
        val type: String,
        /** A short human label: the text, the icon name, the button label, or the type. */
        val label: String,
        /** Can hold other parts. */
        val container: Boolean,
    )

    val CONTAINERS = setOf("col", "row", "box")

    /** What "Add" offers, as ready nodes. */
    val NEW_PARTS: Map<String, () -> MutableMap<String, Any?>> = linkedMapOf(
        "Text" to { obj("t" to "text", "text" to "New text", "style" to "body") },
        "Big number" to { obj("t" to "text", "text" to "42", "style" to "display", "bold" to true, "color" to "primary") },
        "Icon" to { obj("t" to "icon", "icon" to "star", "iconSize" to 24, "color" to "primary") },
        "Card" to {
            obj(
                "t" to "col", "bg" to "primaryContainer", "radius" to 16, "pad" to 12, "gap" to 4,
                "kids" to mutableListOf<Any?>(obj("t" to "text", "text" to "Card", "style" to "title")),
            )
        },
        "Row" to { obj("t" to "row", "gap" to 8, "cross" to "center", "kids" to mutableListOf<Any?>(obj("t" to "text", "text" to "New text"))) },
        "Column" to { obj("t" to "col", "gap" to 4, "kids" to mutableListOf<Any?>(obj("t" to "text", "text" to "New text"))) },
        "Divider" to { obj("t" to "divider") },
        "Space" to { obj("t" to "spacer", "grow" to 1) },
    )

    @Suppress("UNCHECKED_CAST")
    fun parse(json: String): MutableMap<String, Any?> = Json.toKotlin(Json.parse(json)) as MutableMap<String, Any?>

    fun write(doc: Map<String, Any?>): String = Json.write(Json.fromKotlin(doc))

    private fun copy(doc: Map<String, Any?>) = parse(write(doc))

    private fun obj(vararg p: Pair<String, Any?>): MutableMap<String, Any?> = linkedMapOf(*p)

    @Suppress("UNCHECKED_CAST")
    private fun kids(node: Map<String, Any?>): MutableList<Any?>? = node["kids"] as? MutableList<Any?>

    /** The node at [path], or null when the path leads nowhere. */
    @Suppress("UNCHECKED_CAST")
    fun node(doc: Map<String, Any?>, path: List<String>): MutableMap<String, Any?>? {
        var cur = doc["root"] as? MutableMap<String, Any?> ?: return null
        for (step in path) {
            cur = if (step == "item") cur["item"] as? MutableMap<String, Any?> ?: return null
            else kids(cur)?.getOrNull(step.toIntOrNull() ?: return null) as? MutableMap<String, Any?> ?: return null
        }
        return cur
    }

    /** Every part, depth first, in the order they appear. */
    fun parts(doc: Map<String, Any?>): List<Part> {
        val out = ArrayList<Part>()
        fun walk(n: Map<String, Any?>, path: List<String>, depth: Int) {
            val type = n["t"] as? String ?: return
            out += Part(path, depth, type, label(n), type in CONTAINERS)
            kids(n)?.forEachIndexed { i, k -> (k as? Map<*, *>)?.let { @Suppress("UNCHECKED_CAST") walk(it as Map<String, Any?>, path + i.toString(), depth + 1) } }
            @Suppress("UNCHECKED_CAST")
            (n["item"] as? Map<String, Any?>)?.let { walk(it, path + "item", depth + 1) }
        }
        node(doc, emptyList())?.let { walk(it, emptyList(), 0) }
        return out
    }

    fun label(n: Map<String, Any?>): String {
        val type = n["t"] as? String ?: "?"
        val text = (n["text"] ?: n["label"] ?: n["format"]) as? String
        return when {
            text != null -> text.take(40)
            type == "icon" -> "Icon: ${n["icon"]}"
            type == "list" -> "List of ${n["of"]}"
            type == "progress" -> if (n["shape"] == "ring") "Ring" else "Bar"
            type == "image" -> "Picture"
            type == "col" -> if (n["bg"] != null && n["bg"] != "none") "Card" else "Column"
            type == "row" -> "Row"
            type == "box" -> "Layer"
            type == "spacer" -> "Space"
            else -> type.replaceFirstChar { it.uppercase() }
        }
    }

    /** Sets one property of one part; a null value removes it. */
    fun set(doc: Map<String, Any?>, path: List<String>, key: String, value: Any?): MutableMap<String, Any?>? {
        if (key == "t" || key == "kids" || key == "item") return null
        val out = copy(doc)
        val n = node(out, path) ?: return null
        if (value == null) n.remove(key) else n[key] = value
        return out
    }

    /** Sets a top-level field, such as "name", or a theme field when [key] starts with "theme.". */
    @Suppress("UNCHECKED_CAST")
    fun setTop(doc: Map<String, Any?>, key: String, value: Any?): MutableMap<String, Any?> {
        val out = copy(doc)
        if (key.startsWith("theme.")) {
            val theme = (out["theme"] as? MutableMap<String, Any?>) ?: linkedMapOf<String, Any?>().also { out["theme"] = it }
            val k = key.removePrefix("theme.")
            if (value == null) theme.remove(k) else theme[k] = value
            if (theme.isEmpty()) out.remove("theme")
        } else if (value == null) out.remove(key) else out[key] = value
        return out
    }

    /** Removes a part. The root and a list's item cannot be removed. */
    fun remove(doc: Map<String, Any?>, path: List<String>): MutableMap<String, Any?>? {
        if (path.isEmpty() || path.last() == "item") return null
        val out = copy(doc)
        val parent = node(out, path.dropLast(1)) ?: return null
        val list = kids(parent) ?: return null
        val i = path.last().toIntOrNull() ?: return null
        if (i !in list.indices) return null
        list.removeAt(i)
        return out
    }

    /** Moves a part among its siblings by [delta] places; null when it cannot move that way. */
    fun move(doc: Map<String, Any?>, path: List<String>, delta: Int): MutableMap<String, Any?>? {
        if (path.isEmpty() || path.last() == "item") return null
        val out = copy(doc)
        val list = node(out, path.dropLast(1))?.let { kids(it) } ?: return null
        val i = path.last().toIntOrNull() ?: return null
        val j = i + delta
        if (i !in list.indices || j !in list.indices) return null
        val moved = list.removeAt(i)
        list.add(j, moved)
        return out
    }

    /** The path a part has after [move]. */
    fun moved(path: List<String>, delta: Int): List<String> = path.dropLast(1) + ((path.last().toIntOrNull() ?: 0) + delta).toString()

    /** A copy of a part, placed right after it. */
    fun duplicate(doc: Map<String, Any?>, path: List<String>): MutableMap<String, Any?>? {
        if (path.isEmpty() || path.last() == "item") return null
        val out = copy(doc)
        val list = node(out, path.dropLast(1))?.let { kids(it) } ?: return null
        val i = path.last().toIntOrNull() ?: return null
        val original = list.getOrNull(i) as? Map<*, *> ?: return null
        @Suppress("UNCHECKED_CAST")
        list.add(i + 1, copy(original as Map<String, Any?>))
        return out
    }

    /**
     * Adds [part] inside the selected part when it can hold parts, otherwise right after it.
     * @return the new document and the path of the added part, or null when there is nowhere to put it.
     */
    fun add(doc: Map<String, Any?>, selected: List<String>, part: Map<String, Any?>): Pair<MutableMap<String, Any?>, List<String>>? {
        val out = copy(doc)
        val target = node(out, selected) ?: return null
        @Suppress("UNCHECKED_CAST")
        val fresh = copy(part as Map<String, Any?>)
        if ((target["t"] as? String) in CONTAINERS) {
            val list = kids(target) ?: mutableListOf<Any?>().also { target["kids"] = it }
            list.add(fresh)
            return out to (selected + (list.size - 1).toString())
        }
        if (selected.isEmpty() || selected.last() == "item") return null
        val list = node(out, selected.dropLast(1))?.let { kids(it) } ?: return null
        val i = (selected.last().toIntOrNull() ?: return null) + 1
        list.add(i, fresh)
        return out to (selected.dropLast(1) + i.toString())
    }
}
