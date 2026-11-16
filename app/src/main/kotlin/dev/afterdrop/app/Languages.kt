package dev.afterdrop.app

import android.content.Context
import dev.afterdrop.spec.Localize
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The gallery widgets are written in English; each language Afterdrop has is a plain list of English
 * text to text in that language, kept in assets/i18n. A language without a list, English included,
 * shows the widgets as written.
 */
object Languages {
    private val loaded = ConcurrentHashMap<String, Map<String, String>>()

    fun words(context: Context, locale: Locale = Locale.getDefault()): Map<String, String> {
        val code = locale.language.lowercase().filter { it in 'a'..'z' }.take(3)
        if (code.isEmpty() || code == "en") return emptyMap()
        return loaded.getOrPut(code) {
            runCatching { context.assets.open("i18n/$code.json").bufferedReader().use { Localize.words(it.readText()) } }.getOrDefault(emptyMap())
        }
    }

    /** A bundled widget in the person's language. */
    fun localize(context: Context, json: String, locale: Locale = Locale.getDefault()): String = Localize.apply(json, words(context, locale))
}
