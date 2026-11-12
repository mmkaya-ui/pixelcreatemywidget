package dev.afterdrop.composer

import dev.afterdrop.spec.Gate
import dev.afterdrop.spec.JObject
import dev.afterdrop.spec.Json
import dev.afterdrop.spec.Problem
import dev.afterdrop.spec.SpecReader
import dev.afterdrop.spec.SpecResult
import java.time.LocalDate
import java.time.ZoneId

/**
 * Expands a [Brief] into a full widget spec from built-in archetypes. Deterministic: the same brief
 * always gives the same spec. Every result goes through [SpecReader], so a composed widget is held
 * to the same rules as any other, and user text can never become a formula.
 */
object Composer {
    private class Unsupported(val code: String, message: String) : Exception(message)

    fun compose(brief: Brief): SpecResult {
        val doc = try {
            build(brief)
        } catch (e: Unsupported) {
            return SpecResult.Rejected(listOf(Problem(Gate.SCHEMA, "brief.${e.code}", "extras", e.message!!)))
        }
        return SpecReader.read(Json.fromKotlin(doc))
    }

    /** Text from a person or a model, made safe to sit inside a template: no control characters, braces literal. */
    internal fun plain(text: String, max: Int): String {
        val clean = text.filter { it >= ' ' && it != ' ' && it != ' ' }.trim()
        val cut = clean.substring(0, clean.offsetByCodePoints(0, Math.min(max, clean.codePointCount(0, clean.length))))
        return cut.replace("{", "{{").replace("}", "}}")
    }

    private fun n(vararg f: Pair<String, Any?>): MutableMap<String, Any?> = linkedMapOf(*f)
    private fun kids(vararg k: Any?): MutableList<Any?> = k.filterNotNull().toMutableList()
    private fun text(t: String, style: String, vararg more: Pair<String, Any?>) = n("t" to "text", "text" to t, "style" to style, *more)

    private fun build(b: Brief): Map<String, Any?> {
        val title = plain(b.title, 40)
        val doc = n("v" to 1, "name" to title.replace("{{", "{").replace("}}", "}").ifEmpty { "Widget" }, "size" to n("cols" to b.cols, "rows" to b.rows))
        val theme = n()
        b.palette?.let { theme["palette"] = it }
        b.density?.let { theme["density"] = it }
        if (theme.isNotEmpty()) doc["theme"] = theme
        val tall = b.rows >= 2
        val wide = b.cols >= 3
        val accent = b.accent

        when (b.intent) {
            "countdown" -> {
                val date = b.text("date") ?: throw Unsupported("countdown_date", "a countdown needs extras.date as YYYY-MM-DD")
                if (runCatching { LocalDate.parse(date) }.isFailure) throw Unsupported("countdown_date", "\"$date\" is not a date like 2026-12-24")
                doc["calc"] = n("left" to "max(days(today(), date('$date')), 0)")
                doc["root"] = n(
                    "t" to "row", "pad" to 12, "gap" to 10, "cross" to "center",
                    "kids" to kids(
                        text("{left}", "display", "bold" to true, "color" to accent),
                        n("t" to "col", "kids" to kids(
                            text("{left == 1 ? 'day' : 'days'} to go", "title"),
                            text(title, "caption", "color" to "muted", "lines" to 1),
                        )),
                    ),
                )
            }
            "tracker" -> {
                val unit = plain(b.text("unit") ?: "", 16)
                val goal = b.number("goal")?.takeIf { it >= 1 && it <= 100_000 }
                val step = b.number("step")?.takeIf { it > 0 && it <= 10_000 } ?: 1.0
                val reset = b.text("reset")?.takeIf { it in setOf("never", "daily", "weekly") } ?: "daily"
                doc["vars"] = n("count" to n("type" to "int", "init" to 0, "reset" to reset))
                val label = if (goal != null) "{count} of {goal} $unit".trim() else "{count} $unit".trim()
                val stepper = n("t" to "stepper", "var" to "count", "step" to step, "min" to 0, "max" to (goal?.times(10) ?: 100_000.0), "label" to title)
                if (goal != null) doc["calc"] = n("goal" to fmt(goal), "done" to "clamp(count / goal, 0, 1)")
                doc["root"] = if (tall) n(
                    "t" to "col", "pad" to 12, "gap" to 8, "cross" to "center",
                    "kids" to kids(
                        if (goal != null) n("t" to "progress", "shape" to (if (b.layout == "stat") "bar" else "ring"), "value" to "done", "color" to accent, "grow" to (if (b.layout == "stat") 0 else 1)) else text(title, "label", "color" to "muted"),
                        text(label, "title"),
                        stepper,
                    ),
                ) else n(
                    "t" to "row", "pad" to 10, "gap" to 8, "cross" to "center",
                    "kids" to kids(text(label, "title", "grow" to 1, "lines" to 1), stepper),
                )
            }
            "todo" -> {
                val items = b.show.map { plain(it, 32) }.filter { it.isNotEmpty() }
                if (items.isEmpty()) throw Unsupported("todo_items", "a checklist needs its items in \"show\"")
                val reset = b.text("reset")?.takeIf { it in setOf("never", "daily", "weekly") } ?: "never"
                doc["vars"] = items.indices.associateTo(LinkedHashMap()) { "c$it" to n("type" to "bool", "init" to false, "reset" to reset) }
                doc["calc"] = n("got" to items.indices.joinToString(" + ") { "(c$it ? 1 : 0)" })
                doc["root"] = n(
                    "t" to "col", "pad" to 12, "gap" to 4,
                    "kids" to kids(
                        text("$title  {got}/${items.size}", "label", "color" to "muted", "lines" to 1),
                        *items.mapIndexed { i, label -> n("t" to "check", "var" to "c$i", "label" to label) }.toTypedArray(),
                    ),
                )
            }
            "clock" -> {
                val zone = b.text("zone")
                if (zone != null && runCatching { ZoneId.of(zone) }.isFailure) throw Unsupported("clock_zone", "\"$zone\" is not a time zone like Europe/Berlin")
                fun clock(format: String, style: String, vararg more: Pair<String, Any?>) =
                    n("t" to "clock", "format" to format, "style" to style, *more).also { if (zone != null) it["zone"] = zone }
                doc["root"] = n(
                    "t" to "col", "pad" to 12, "gap" to 2, "cross" to "center", "align" to "center",
                    "kids" to kids(
                        if (zone != null) text(title, "label", "color" to "muted", "lines" to 1) else null,
                        clock("HH:mm", if (wide || tall) "display" else "headline", "bold" to true, "color" to accent),
                        clock("EEE d MMM", "caption", "color" to "muted"),
                    ),
                )
            }
            "battery" -> {
                doc["sources"] = n("bat" to n("use" to "battery"))
                doc["root"] = n(
                    "t" to "row", "pad" to 12, "gap" to 10, "cross" to "center",
                    "kids" to kids(
                        n("t" to "progress", "shape" to "ring", "value" to "bat.level / 100", "color" to accent, "w" to 40, "h" to 40),
                        n("t" to "col", "grow" to 1, "kids" to kids(
                            text("{num(bat.level, 0)}%", "headline", "bold" to true),
                            text("Charging", "caption", "color" to "success", "if" to "bat.charging"),
                            text("Plug in soon", "caption", "color" to "error", "if" to "bat.level < 20 && !bat.charging"),
                        )),
                    ),
                )
            }
            "weather" -> {
                doc["sources"] = n("wx" to n("use" to "weather", "with" to n("place" to "current", "units" to (b.text("units")?.takeIf { it == "imperial" } ?: "metric")), "every" to "30m"))
                val want = b.show.map { it.lowercase() }
                doc["root"] = n(
                    "t" to "col", "pad" to 12, "gap" to 4, "tap" to n("do" to "open_source", "source" to "wx"),
                    "kids" to kids(
                        text(title, "label", "color" to "muted", "lines" to 1),
                        text("{num(wx.now.temp, 0)}°", "display", "bold" to true, "color" to accent),
                        if (want.any { "wind" in it }) text("Wind {num(wx.now.wind, 0)}", "body") else null,
                        if (want.any { "rain" in it }) text("Rain {num(wx.next3h.rainChance, 0)}%", "body") else null,
                        if (tall) n("t" to "chart", "kind" to "bars", "values" to "pluck(take(wx.hourly, 8), 'rainChance')", "min" to 0, "max" to 100, "color" to accent, "grow" to 1, "from" to "s") else null,
                    ),
                )
            }
            "agenda" -> {
                doc["sources"] = n("cal" to n("use" to "calendar", "with" to n("daysAhead" to 2, "limit" to 5), "every" to "15m"))
                doc["root"] = n(
                    "t" to "col", "pad" to 12, "gap" to 6,
                    "kids" to kids(
                        n("t" to "row", "align" to "between", "cross" to "center", "kids" to kids(
                            text(title, "label", "color" to "muted", "lines" to 1),
                            n("t" to "clock", "format" to "EEE d MMM", "style" to "label", "color" to "muted"),
                        )),
                        n("t" to "list", "of" to "cal.events", "limit" to (if (tall) 4 else 1), "grow" to 1, "empty" to "Nothing scheduled",
                            "item" to n("t" to "row", "gap" to 8, "cross" to "center", "tap" to n("do" to "open_source", "source" to "cal"),
                                "kids" to kids(
                                    text("{fmt(it.start, 'HH:mm')}", "label", "color" to accent),
                                    text("{it.title}", "body", "lines" to 1, "grow" to 1),
                                ))),
                    ),
                )
            }
            "quote" -> {
                val topic = plain((b.text("topic") ?: "uplifting").filter { it.isLetterOrDigit() || it == ' ' }, 40).ifEmpty { "uplifting" }
                doc["sources"] = n("q" to n("use" to "ai", "with" to n("prompt" to "One short $topic line. No quotation marks."), "every" to "1d"))
                doc["root"] = n(
                    "t" to "col", "pad" to 16, "gap" to 8, "align" to "center",
                    "kids" to kids(
                        text("{coalesce(q.text, 'Thinking it over')}", "title", "lines" to (if (tall) 4 else 2)),
                        n("t" to "button", "label" to "Another", "kind" to "tonal", "tap" to n("do" to "refresh"), "from" to "m"),
                    ),
                )
            }
            "rates" -> {
                val code = Regex("[A-Z]{3}")
                val base = b.text("base")?.uppercase() ?: "EUR"
                val quote = b.text("quote")?.uppercase() ?: "USD"
                if (!code.matches(base) || !code.matches(quote)) throw Unsupported("rates_currency", "currencies are three letters, like EUR and USD")
                doc["sources"] = n("fx" to n("use" to "rates", "with" to n("base" to base, "quote" to quote, "days" to 30), "every" to "3h"))
                doc["root"] = n(
                    "t" to "col", "pad" to 12, "gap" to 4,
                    "kids" to kids(
                        text("1 $base", "label", "color" to "muted"),
                        text("{num(fx.rate, 2)} $quote", "headline", "bold" to true),
                        if (tall) n("t" to "chart", "kind" to "spark", "values" to "fx.history", "color" to accent, "grow" to 1, "from" to "s") else null,
                    ),
                )
            }
            "feed" -> {
                val url = b.text("url") ?: throw Unsupported("feed_url", "a feed needs extras.url, an https address")
                if (!Regex("https://[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]{4,280}").matches(url)) throw Unsupported("feed_url", "the feed address must be a plain https link")
                doc["sources"] = n("news" to n("use" to "feed", "with" to n("url" to url), "every" to "1h"))
                doc["root"] = n(
                    "t" to "col", "pad" to 12, "gap" to 8,
                    "kids" to kids(
                        text(title, "label", "color" to "muted", "lines" to 1),
                        n("t" to "list", "of" to "news.items", "limit" to (if (tall) 5 else 1), "gap" to 6, "grow" to 1, "empty" to "No stories yet",
                            "item" to n("t" to "text", "text" to "{it.title}", "style" to "body", "lines" to 2, "tap" to n("do" to "open_source", "source" to "news"))),
                    ),
                )
            }
            "dashboard" -> {
                doc["sources"] = n("bat" to n("use" to "battery"), "dev" to n("use" to "device"))
                doc["root"] = n(
                    "t" to "col", "pad" to 12, "gap" to 6,
                    "kids" to kids(
                        n("t" to "clock", "format" to "HH:mm", "style" to "headline", "bold" to true, "color" to accent),
                        n("t" to "clock", "format" to "EEEE d MMMM", "style" to "caption", "color" to "muted"),
                        n("t" to "divider", "color" to "outline", "from" to "s"),
                        text("Battery {num(bat.level, 0)}%", "body"),
                        text("{num(dev.storageFreeGb, 0)} GB free", "body", "from" to "s"),
                    ),
                )
            }
            "custom" -> when (b.text("kind")) {
                "timer" -> {
                    val minutes = b.number("minutes")?.takeIf { it >= 1 && it <= 600 && it % 1.0 == 0.0 } ?: throw Unsupported("timer_minutes", "a timer needs extras.minutes, a whole number from 1 to 600")
                    doc["vars"] = n("endsAt" to n("type" to "num", "init" to 0))
                    doc["calc"] = n("running" to "endsAt > now()")
                    doc["root"] = n(
                        "t" to "col", "pad" to 12, "gap" to 8, "cross" to "center", "align" to "center",
                        "kids" to kids(
                            n("t" to "timer", "to" to "endsAt", "mode" to "down", "style" to "display", "bold" to true, "if" to "running"),
                            text(title, "body", "color" to "muted", "if" to "!running", "lines" to 1),
                            n("t" to "button", "label" to "Start ${minutes.toInt()} min", "kind" to "filled", "tap" to n("do" to "set", "var" to "endsAt", "to" to "now() + ${minutes.toLong() * 60_000}")),
                        ),
                    )
                }
                else -> {
                    val lines = b.show.map { plain(it, 32) }.filter { it.isNotEmpty() }
                    doc["root"] = n(
                        "t" to "col", "pad" to 14, "gap" to 4,
                        "kids" to kids(
                            text(title, "title", "bold" to true, "lines" to 2),
                            *lines.map { text(it, "body", "lines" to 2) }.toTypedArray(),
                        ),
                    )
                }
            }
            else -> throw Unsupported("unsupported", "\"${b.intent}\" widgets cannot be built yet")
        }
        return doc
    }

    private fun fmt(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
}
