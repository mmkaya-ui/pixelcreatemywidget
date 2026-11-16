package dev.afterdrop.spec

/**
 * Puts a widget's words into the person's language. A widget is written in English; a language is a
 * plain list of "English text" to "text in that language", and every text a person reads in a widget
 * (its name, description, texts, button labels, empty message and prompt title) is looked up whole.
 * What is not in the list stays as written, so a missing translation shows English, never nothing.
 *
 * A translation may reword the words inside a `{ }` formula and change where the data goes in the
 * sentence, but it must not add data the English did not use or drop data it showed: [check] says.
 */
object Localize {
    /** The widget fields whose values are words for people. */
    private val KEYS = setOf("name", "about", "text", "label", "empty", "title")

    fun apply(json: String, words: Map<String, String>): String {
        if (words.isEmpty()) return json
        val root = try {
            Json.parse(json)
        } catch (e: JsonError) {
            return json
        }
        return Json.write(translate(root, null, words))
    }

    private fun translate(v: JsonValue, key: String?, words: Map<String, String>): JsonValue = when (v) {
        is JObject -> JObject(v.fields.entries.associateTo(LinkedHashMap()) { it.key to translate(it.value, it.key, words) })
        is JArray -> JArray(v.items.map { translate(it, key, words) })
        is JString -> if (key in KEYS) JString(words[v.value] ?: v.value) else v
        else -> v
    }

    /** Every text in [json] that [apply] would look up, in order, without repeats. */
    fun texts(json: String): List<String> {
        val out = LinkedHashSet<String>()
        fun walk(v: JsonValue, key: String?) {
            when (v) {
                is JObject -> v.fields.forEach { (k, x) -> walk(x, k) }
                is JArray -> v.items.forEach { walk(it, key) }
                is JString -> if (key in KEYS) out += v.value
                else -> {}
            }
        }
        walk(Json.parse(json), null)
        return out.toList()
    }

    /** Reads a language file: one JSON object of text to text. Anything else in it is ignored. */
    fun words(file: String): Map<String, String> {
        val root = Json.parse(file) as? JObject ?: return emptyMap()
        return root.fields.entries.mapNotNull { (k, v) -> (v as? JString)?.let { k to it.value } }.toMap()
    }

    private val FORMULA = Regex("\\{[^{}]*\\}")
    private val LITERAL = Regex("'[^']*'")

    /** The `{ }` parts of a text with the words in quotes blanked out, which is what must stay the same. */
    fun formulas(text: String): List<String> = FORMULA.findAll(text).map { LITERAL.replace(it.value, "''") }.toList()

    /** A formula that only chooses between two pieces of text, such as `{n == 1 ? 'day' : 'days'}`. */
    private fun onlyWords(formula: String) = Regex("^\\{[^'{}]*\\?\\s*''\\s*:\\s*''\\}$").matches(formula)

    /** Null when [translated] uses the same data as [english]; otherwise what is wrong. */
    fun check(english: String, translated: String): String? {
        val want = formulas(english)
        val have = formulas(translated).toMutableList()
        for (f in formulas(translated)) if (f !in want) return "adds $f, which the English does not use"
        for (f in want) {
            if (f in have) have.remove(f) else if (!onlyWords(f)) return "drops $f, which the English shows"
        }
        if (have.isNotEmpty()) return "uses ${have.first()} more often than the English"
        return null
    }

    /** Texts that read the same in every language, so they need no entry. */
    fun needsTranslation(text: String): Boolean {
        if (Regex("^#[0-9A-Fa-f]{3,8}$").matches(text) || text in NEUTRAL) return false
        val outside = FORMULA.replace(text, "")
        if (outside.any { it in 'A'..'Z' || it in 'a'..'z' }) return true
        val quoted = FORMULA.findAll(text).flatMap { f -> LITERAL.findAll(f.value).map { it.value.trim('\'') } }.toList()
        return quoted.any { it !in DATE_PATTERNS && it.any { c -> c in 'A'..'Z' || c in 'a'..'z' } }
    }

    /** Units and currency codes that are written the same in the languages Afterdrop has. */
    private val NEUTRAL = setOf("1 EUR", "{num(fx.rate, 2)} TRY", "{num(wx.now.wind, 0)} km/h")

    private val DATE_PATTERNS = setOf("HH:mm", "EEE d MMM", "EEEE d MMMM", "EEE HH:mm", "EEE d MMM yyyy", "EEEE", "yyyy", "yyyy-MM-dd")
}
