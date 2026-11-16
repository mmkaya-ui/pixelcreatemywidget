package dev.afterdrop.composer

import dev.afterdrop.spec.JArray
import dev.afterdrop.spec.JNumber
import dev.afterdrop.spec.JObject
import dev.afterdrop.spec.JString
import dev.afterdrop.spec.Json
import dev.afterdrop.spec.JsonError
import dev.afterdrop.spec.SpecReader
import dev.afterdrop.spec.SpecResult
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Moving widgets between phones, and between a phone and a file.
 *
 * - A **share code** is one widget squeezed into a line of text, `afterdrop1:...`, that survives any
 *   messenger. [share] wraps it in a short message; [decode] finds the code anywhere in pasted text.
 * - A **backup** is a JSON text holding all of a person's own widgets and, if wanted, their notes.
 *
 * Everything that comes in is untrusted. Each widget goes through the same reader as any other widget
 * (size cap, schema, lint), compressed data is only inflated up to a fixed size, and what is kept is
 * the reader's own output, never the incoming text.
 */
object Transfer {
    const val PREFIX = "afterdrop1:"

    /** A backup holds at most this many widgets, so a hostile file cannot fill the phone. */
    const val MAX_WIDGETS = 200

    /** A note, as in the app: longer text is cut. */
    const val MAX_NOTE = 2000
    private const val MAX_NOTES = 50
    private const val MAX_CODE_CHARS = 120_000
    private const val MAX_INFLATED = 8 * 1024 * 1024

    class Item(val name: String, val json: String)

    sealed interface Decoded {
        /** [skipped] lists widgets in a backup that were not valid, each with the reason. */
        class Ok(val widgets: List<Item>, val notes: Map<String, String>, val skipped: List<String>) : Decoded

        class Bad(val reason: String) : Decoded
    }

    // ---- Share code -------------------------------------------------------------------------------

    /** One widget as a code. [json] must be a valid widget; the code holds its re-written, compact form. */
    fun encode(json: String): String {
        val compact = Json.write(Json.parse(json, lenient = true))
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(compact.toByteArray(Charsets.UTF_8)))
    }

    /** The message a person sends: [header], the code on its own line, then [footer] (where to get the app). */
    fun share(header: String, json: String, footer: String): String = "${header.trim()}\n${encode(json)}\n\n${footer.trim()}"

    /** The first share code in [text], or null. The code ends at the first character that cannot be in one. */
    fun find(text: String): String? {
        val start = text.indexOf(PREFIX)
        if (start < 0) return null
        var end = start + PREFIX.length
        while (end < text.length && (text[end].isLetterOrDigit() && text[end].code < 128 || text[end] == '-' || text[end] == '_')) end++
        return text.substring(start, end).takeIf { it.length > PREFIX.length }
    }

    // ---- Backup -----------------------------------------------------------------------------------

    /** All the widgets (name to JSON) and notes (name to text) as one JSON text, ready to be written to a file. */
    fun backup(widgets: Map<String, String>, notes: Map<String, String> = emptyMap()): String {
        val list = widgets.values.map { Json.parse(it, lenient = true) }
        val kept = notes.filter { it.value.isNotEmpty() }.entries.associate { it.key.take(40) to JString(it.value.take(MAX_NOTE)) }
        return Json.write(
            JObject(
                linkedMapOf(
                    "afterdrop" to JNumber(1.0),
                    "widgets" to JArray(list),
                    "notes" to JObject(LinkedHashMap(kept)),
                )
            ),
            indent = true,
        )
    }

    // ---- Reading anything ---------------------------------------------------------------------------

    /**
     * Understands a share code (alone or inside a message), a backup, and a plain widget description,
     * so one "paste or open" door takes all three.
     */
    fun decode(text: String): Decoded {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Decoded.Bad("There is nothing to read.")
        val code = find(trimmed)
        val body = if (code != null) {
            if (code.length > MAX_CODE_CHARS) return Decoded.Bad("That code is too large to be a widget.")
            val raw = try {
                Base64.getUrlDecoder().decode(code.removePrefix(PREFIX))
            } catch (e: IllegalArgumentException) {
                return Decoded.Bad("That code looks cut off or changed. Copy the whole line that starts with $PREFIX")
            }
            val bytes = inflate(raw) ?: return Decoded.Bad("That code looks cut off or changed. Copy the whole line that starts with $PREFIX")
            String(bytes, Charsets.UTF_8)
        } else trimmed
        val root = try {
            Json.parse(body, lenient = code == null)
        } catch (e: JsonError) {
            return Decoded.Bad(if (code != null) "The code does not hold a widget." else "This is not a widget, a share code or a backup.")
        }
        if (root !is JObject) return Decoded.Bad("This is not a widget, a share code or a backup.")
        return if (root["afterdrop"] != null) fromBackup(root) else single(root)
    }

    private fun single(root: JObject): Decoded {
        val json = Json.write(root)
        return when (val r = SpecReader.read(json)) {
            is SpecResult.Ok -> Decoded.Ok(listOf(Item(r.spec.name, Json.write(r.spec.json))), emptyMap(), emptyList())
            is SpecResult.Rejected -> Decoded.Bad("That widget cannot be used: " + (r.problems.firstOrNull()?.let { "${it.path}: ${it.message}" } ?: "it is not valid"))
        }
    }

    private fun fromBackup(root: JObject): Decoded {
        val version = (root["afterdrop"] as? JNumber)?.value?.toInt() ?: return Decoded.Bad("This backup is not readable.")
        if (version != 1) return Decoded.Bad("This backup was made by a newer Afterdrop. Update the app first.")
        val list = (root["widgets"] as? JArray)?.items ?: return Decoded.Bad("This backup holds no widgets.")
        if (list.size > MAX_WIDGETS) return Decoded.Bad("This backup holds more than $MAX_WIDGETS widgets.")
        val widgets = ArrayList<Item>()
        val skipped = ArrayList<String>()
        val seen = HashSet<String>()
        for ((i, w) in list.withIndex()) {
            when (val r = SpecReader.read(Json.write(w))) {
                is SpecResult.Ok -> if (seen.add(r.spec.name)) widgets += Item(r.spec.name, Json.write(r.spec.json)) else skipped += "Widget ${i + 1}: the name \"${r.spec.name}\" is used twice."
                is SpecResult.Rejected -> skipped += "Widget ${i + 1}: " + (r.problems.firstOrNull()?.message ?: "not valid")
            }
        }
        val notes = LinkedHashMap<String, String>()
        (root["notes"] as? JObject)?.fields?.forEach { (name, v) ->
            val text = (v as? JString)?.value
            if (text != null && text.isNotEmpty() && name.isNotBlank() && notes.size < MAX_NOTES) notes[name.take(40)] = text.take(MAX_NOTE)
        }
        if (widgets.isEmpty() && notes.isEmpty()) return Decoded.Bad("This backup holds nothing that can be used." + if (skipped.isNotEmpty()) " " + skipped.first() else "")
        return Decoded.Ok(widgets, notes, skipped)
    }

    // ---- Compression --------------------------------------------------------------------------------

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    /** Inflates, refusing anything that would grow beyond [MAX_INFLATED]; null for damaged data. */
    private fun inflate(data: ByteArray): ByteArray? {
        val inf = Inflater(true)
        try {
            inf.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) return null
                out.write(buf, 0, n)
                if (out.size() > MAX_INFLATED) return null
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            return null
        } finally {
            inf.end()
        }
    }
}
