package dev.afterdrop.composer

import dev.afterdrop.spec.JNumber
import dev.afterdrop.spec.JString
import dev.afterdrop.spec.JsonValue
import java.time.LocalDate

/** What the offline rules made of a description: a brief, or a plain reason they could not. */
sealed interface Described {
    class Ok(val brief: Brief) : Described
    class No(val reason: String) : Described
}

/**
 * Turns a plain-English description into a [Brief] with keyword rules. No model, no network.
 * It covers the common asks and says so honestly when a request is outside them.
 */
object Describe {
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val SIZE = Regex("\\b([1-5])\\s*(?:x|×|by)\\s*([1-5])\\b")
    private val NUMBER = Regex("\\b(\\d{1,6})(?:\\.\\d+)?\\b")

    fun fromText(description: String, today: LocalDate): Described {
        val raw = description.trim().take(500)
        if (raw.isEmpty()) return Described.No("Describe the widget you want, for example \"countdown to 24 December\".")
        val t = raw.lowercase()
        fun has(vararg words: String) = words.any { Regex("\\b" + Regex.escape(it)).containsMatchIn(t) }
        val size = SIZE.find(t)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
        val extras = LinkedHashMap<String, JsonValue>()
        fun brief(intent: String, title: String, layout: String, cols: Int, rows: Int, show: List<String> = emptyList()) =
            Described.Ok(Brief(intent, title.take(40), layout, size?.first ?: cols, size?.second ?: rows, show, extras = extras))

        return when {
            has("countdown", "days until", "days to", "days till", "how long until") -> {
                val date = date(t, today) ?: return Described.No("Which date should it count down to? Try \"countdown to 24 December\".")
                extras["date"] = JString(date.toString())
                brief("countdown", label(raw, "to", "until", "till") ?: "Countdown", "stat", 2, 1)
            }
            has("timer", "minute timer", "min timer") -> {
                val minutes = Regex("(\\d{1,3})\\s*(?:-\\s*)?(?:min|minute)").find(t)?.groupValues?.get(1)?.toInt()
                    ?: return Described.No("How many minutes should the timer run? Try \"5 minute timer\".")
                if (minutes !in 1..600) return Described.No("A timer can run from 1 to 600 minutes.")
                extras["kind"] = JString("timer")
                extras["minutes"] = JNumber(minutes.toDouble())
                brief("custom", "$minutes minute timer", "stat", 2, 2)
            }
            has("checklist", "todo", "to-do", "to do list", "shopping list", "packing list") -> {
                val items = raw.substringAfter(':', "").split(',', ';', '\n').map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }.take(6)
                if (items.isEmpty()) return Described.No("List the items after a colon, for example \"checklist: milk, bread, eggs\".")
                if (has("daily", "every day")) extras["reset"] = JString("daily")
                if (has("weekly", "every week")) extras["reset"] = JString("weekly")
                brief("todo", raw.substringBefore(':').trim().replaceFirstChar { it.uppercase() }.ifEmpty { "Checklist" }, "list", 2, if (items.size > 3) 3 else 2, items.map { it.take(32) })
            }
            has("track", "tracker", "counter", "count my", "count how", "glasses", "cups", "push-ups", "pushups", "habit") -> {
                val unit = listOf("glasses", "cups", "push-ups", "pushups", "pages", "steps", "minutes", "km", "times")
                    .firstOrNull { it in t }
                NUMBER.find(t.replace(SIZE, " "))?.groupValues?.get(1)?.toDouble()?.takeIf { it >= 1 }?.let { extras["goal"] = JNumber(it) }
                unit?.let { extras["unit"] = JString(it) }
                if (has("weekly", "per week", "a week")) extras["reset"] = JString("weekly")
                if (has("never reset", "total", "all time")) extras["reset"] = JString("never")
                val title = when {
                    has("water") -> "Water today"
                    unit != null -> unit.replaceFirstChar { it.uppercase() }
                    else -> "Counter"
                }
                brief("tracker", title, if ("goal" in extras) "ring" else "stat", 2, 2)
            }
            has("battery") -> brief("battery", "Battery", "ring", 2, 1)
            has("weather", "rain", "wind", "temperature", "forecast") ->
                brief("weather", "Weather", "stat", 2, 2, listOfNotNull("wind".takeIf { has("wind") }, "rain".takeIf { has("rain") }))
            has("calendar", "agenda", "meeting", "meetings", "events", "appointments", "schedule") -> brief("agenda", "Next up", "list", 4, 2)
            has("quote", "affirmation", "motivation", "motivational") -> {
                listOf("stoic", "funny", "motivational", "calm", "kind").firstOrNull { it in t }?.let { extras["topic"] = JString(it) }
                brief("quote", "Daily line", "hero", 4, 2)
            }
            has("exchange rate", "currency", "eur", "usd", "try", "gbp", "lira", "euro", "dollar") -> {
                val codes = Regex("\\b(eur|usd|try|gbp|chf|jpy|cad|aud|sek|nok|pln)\\b").findAll(t).map { it.value.uppercase() }.distinct().toList()
                val named = listOfNotNull("EUR".takeIf { "euro" in t }, "USD".takeIf { "dollar" in t }, "TRY".takeIf { "lira" in t }, "GBP".takeIf { "pound" in t })
                val all = (codes + named).distinct()
                if (all.size < 2) return Described.No("Which two currencies? Try \"EUR to TRY rate\".")
                extras["base"] = JString(all[0])
                extras["quote"] = JString(all[1])
                brief("rates", "${all[0]} to ${all[1]}", "spark", 3, 2)
            }
            has("news", "headlines", "rss", "feed") -> {
                val url = Regex("https://\\S+").find(raw)?.value?.trimEnd('.', ',', ')')
                    ?: return Described.No("Paste the feed's https address in the description, for example \"headlines from https://example.org/feed.xml\".")
                extras["url"] = JString(url)
                brief("feed", "Headlines", "list", 4, 3)
            }
            has("dashboard", "overview", "at a glance") -> brief("dashboard", "Overview", "grid", 3, 2)
            has("clock", "time in", "what time", "date and time", "world clock") -> {
                ZONES.entries.firstOrNull { it.key in t }?.let {
                    extras["zone"] = JString(it.value)
                    return brief("clock", it.key.replaceFirstChar { c -> c.uppercase() }, "hero", 2, 1)
                }
                brief("clock", "Clock", "hero", 2, 1)
            }
            has("note", "reminder", "remember", "sticky") -> {
                val body = (label(raw, ":", "that says", "saying") ?: raw).take(40)
                brief("custom", body, "hero", 2, 2)
            }
            else -> Described.No(
                "The offline rules cover countdowns, counters, checklists, timers, clocks, battery, notes, weather, calendar, " +
                    "currency rates, news feeds and daily quotes. For anything else, copy the chatbot prompt and paste the answer back."
            )
        }
    }

    private val ZONES = linkedMapOf(
        "tokyo" to "Asia/Tokyo", "istanbul" to "Europe/Istanbul", "berlin" to "Europe/Berlin", "london" to "Europe/London",
        "new york" to "America/New_York", "los angeles" to "America/Los_Angeles", "paris" to "Europe/Paris",
        "dubai" to "Asia/Dubai", "sydney" to "Australia/Sydney", "delhi" to "Asia/Kolkata", "singapore" to "Asia/Singapore",
        "utc" to "UTC",
    )

    /** The words after the first of [markers], tidied into a short label. */
    private fun label(raw: String, vararg markers: String): String? {
        for (m in markers) {
            val at = raw.lowercase().indexOf(if (m == ":") m else " $m ")
            if (at >= 0) {
                val rest = raw.substring(at + (if (m == ":") 1 else m.length + 2)).trim().trimEnd('.', '!')
                if (rest.isNotEmpty()) return rest.replaceFirstChar { it.uppercase() }.take(40)
            }
        }
        return null
    }

    /** Finds a date in [t]. A date without a year means its next occurrence on or after [today]. */
    internal fun date(t: String, today: LocalDate): LocalDate? {
        fun make(y: Int, m: Int, d: Int) = runCatching { LocalDate.of(y, m, d) }.getOrNull()
        fun next(m: Int, d: Int): LocalDate? {
            val thisYear = make(today.year, m, d)
            return if (thisYear != null && !thisYear.isBefore(today)) thisYear else make(today.year + 1, m, d)
        }
        Regex("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b").find(t)?.let { m -> return make(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        Regex("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\\b").find(t)?.let { m -> return make(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }
        val month = "(" + MONTHS.joinToString("|") + ")[a-z]*"
        val year = "(?:,?\\s+(\\d{4}))?"
        Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?$month$year").find(t)?.let { m ->
            val mo = MONTHS.indexOf(m.groupValues[2]) + 1
            val d = m.groupValues[1].toInt()
            return m.groupValues[3].toIntOrNull()?.let { make(it, mo, d) } ?: next(mo, d)
        }
        Regex("\\b$month\\s+(\\d{1,2})(?:st|nd|rd|th)?$year").find(t)?.let { m ->
            val mo = MONTHS.indexOf(m.groupValues[1]) + 1
            val d = m.groupValues[2].toInt()
            return m.groupValues[3].toIntOrNull()?.let { make(it, mo, d) } ?: next(mo, d)
        }
        if ("christmas" in t) return next(12, 25)
        if ("new year" in t) return next(1, 1)
        if ("halloween" in t) return next(10, 31)
        return null
    }
}
