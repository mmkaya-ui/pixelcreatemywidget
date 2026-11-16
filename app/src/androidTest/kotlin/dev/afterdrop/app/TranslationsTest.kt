package dev.afterdrop.app

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.afterdrop.spec.SpecReader
import dev.afterdrop.spec.SpecResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** The app speaks German and Turkish: every text exists, takes its arguments, and the gallery follows. */
@RunWith(AndroidJUnit4::class)
class TranslationsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun inLanguage(tag: String): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(config)
    }

    private val placeholder = Regex("%(\\d+)\\$([sd])")

    @Test
    fun everyTextExistsInEveryLanguageAndTakesItsArguments() {
        val ids = R.string::class.java.fields.map { it.name to it.getInt(null) }
        assertTrue("no strings found", ids.size > 150)
        for (tag in listOf("en", "de", "tr")) {
            val c = inLanguage(tag)
            for ((name, id) in ids) {
                val text = c.getString(id)
                assertTrue("$tag: $name is empty", text.isNotBlank())
                val found = placeholder.findAll(text).toList()
                val args = Array<Any>((found.maxOfOrNull { it.groupValues[1].toInt() } ?: 0)) { i ->
                    if (found.any { it.groupValues[1].toInt() == i + 1 && it.groupValues[2] == "d" }) 7 else "x"
                }
                val formatted = c.getString(id, *args)
                assertTrue("$tag: $name did not use its arguments", args.isEmpty() || formatted != text)
            }
        }
    }

    @Test
    fun theLanguagesAreReallyDifferent() {
        val english = inLanguage("en")
        for (tag in listOf("de", "tr")) {
            val c = inLanguage(tag)
            val names = R.string::class.java.fields.map { it.name to it.getInt(null) }
            val same = names.count { (_, id) -> c.getString(id) == english.getString(id) }
            assertTrue("$tag has too many texts equal to English ($same of ${names.size})", same < names.size / 5)
        }
        assertEquals("Create", english.getString(R.string.create))
        assertNotEquals("Create", inLanguage("de").getString(R.string.create))
        assertNotEquals("Create", inLanguage("tr").getString(R.string.create))
    }

    @Test
    fun theGalleryFollowsTheLanguage() {
        val files = context.assets.list("")!!.filter { it.endsWith(".json") }
        assertTrue(files.size >= 19)
        for (tag in listOf("de", "tr")) {
            val locale = Locale.forLanguageTag(tag)
            assertTrue("$tag: no words", Languages.words(context, locale).size > 40)
            var changed = 0
            for (file in files) {
                val english = context.assets.open(file).bufferedReader().use { it.readText() }
                val local = Languages.localize(context, english, locale)
                val en = (SpecReader.read(english) as SpecResult.Ok).spec
                val r = SpecReader.read(local)
                assertTrue("$tag $file is not valid in its language", r is SpecResult.Ok)
                if ((r as SpecResult.Ok).spec.name != en.name) changed++
            }
            assertTrue("$tag: only $changed gallery names changed", changed >= files.size - 3)
        }
        assertTrue("English is left as written", Languages.words(context, Locale.ENGLISH).isEmpty())
    }
}
