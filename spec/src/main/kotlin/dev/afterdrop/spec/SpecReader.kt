package dev.afterdrop.spec

/** The outcome of reading a spec: a trusted [WidgetSpec], or the reasons it was refused. */
sealed interface SpecResult {
    class Ok(val spec: WidgetSpec) : SpecResult
    class Rejected(val problems: List<Problem>) : SpecResult
}

/** Brings stored or shared JSON up to the current format version. */
object Migrations {
    const val CURRENT = 1

    /** Returns the migrated object, or a problem when the version is missing, invalid or newer than this app. */
    internal fun toCurrent(o: JObject): Pair<JObject?, Problem?> {
        val v = o["v"] as? JNumber
        if (v == null || !v.isInteger || v.value < 1) return o to null // the schema reports a missing or bad version
        if (v.value > CURRENT) {
            return null to Problem(
                Gate.SCHEMA, "schema.newer_version", "v",
                "this widget was made by a newer version of the app (format ${v.value.toLong()}, this app reads $CURRENT)",
            )
        }
        // Format 1 is the first format. Later versions add one step each here: 1 -> 2, 2 -> 3, ...
        return o to null
    }
}

/**
 * The only way to obtain a [WidgetSpec]. Gates run in order and stop at the first that fails:
 * size cap, JSON parse, version migration, schema, lint.
 */
object SpecReader {
    /** Larger input is refused before parsing. A full widget is a few kilobytes. */
    const val MAX_BYTES = 64 * 1024

    /**
     * @param lenient accept model output: code fences, surrounding prose and trailing commas.
     *   Use false for files and shared links.
     */
    fun read(text: String, lenient: Boolean = false): SpecResult {
        if (text.length > MAX_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return reject(Problem(Gate.PARSE, "parse.too_large", "$", "larger than ${MAX_BYTES / 1024} KB"))
        }
        val json = try {
            Json.parse(text, lenient)
        } catch (e: JsonError) {
            return reject(Problem(Gate.PARSE, "parse.json", "$", "${e.message} at character ${e.pos}"))
        }
        return read(json)
    }

    fun read(json: JsonValue): SpecResult {
        if (json !is JObject) return reject(Problem(Gate.SCHEMA, "schema.type", "$", "must be an object"))
        val (migrated, tooNew) = Migrations.toCurrent(json)
        if (migrated == null) return reject(tooNew!!)
        val schema = SchemaCheck().apply { check(migrated) }
        if (schema.problems.isNotEmpty()) return SpecResult.Rejected(schema.problems)
        val lint = Lint(migrated).apply { run() }
        if (lint.problems.isNotEmpty()) return SpecResult.Rejected(lint.problems)
        return SpecResult.Ok(Decoder.spec(migrated))
    }

    private fun reject(p: Problem) = SpecResult.Rejected(listOf(p))
}
