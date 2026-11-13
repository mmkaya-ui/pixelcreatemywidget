package dev.afterdrop.spec

/**
 * What each data source accepts and returns. One table serves three users: the linter refuses a
 * widget that reads a field a source does not have, the AI prompt tells a model exactly which fields
 * exist, and the people writing widgets by hand get the same list.
 */
object SourceShapes {
    sealed interface Shape
    /** A plain value: number, text or true/false. */
    data object Leaf : Shape
    /** Anything: the shape is only known when the data arrives. */
    data object Unknown : Shape
    class Obj(val fields: Map<String, Shape>) : Shape
    class Arr(val of: Shape) : Shape

    private fun obj(vararg f: Pair<String, Shape>) = Obj(linkedMapOf(*f))
    private fun leaves(vararg names: String) = Obj(names.associateWith { Leaf })

    class Kind(val shape: Shape, val params: String, val live: Boolean)

    val KINDS: Map<String, Kind> = linkedMapOf(
        "battery" to Kind(leaves("level", "charging", "temp"), "none", true),
        "device" to Kind(leaves("storageFreeGb", "storageTotalGb"), "none", true),
        "calendar" to Kind(obj("events" to Arr(leaves("title", "start", "end", "allDay", "location"))), "daysAhead (1-31), limit (1-20)", true),
        "weather" to Kind(
            obj(
                "now" to leaves("temp", "feels", "wind", "gust", "summary"),
                "next3h" to leaves("rainChance", "until"),
                "hourly" to Arr(leaves("time", "temp", "rainChance")),
            ),
            "place: \"current\", units: \"metric\" or \"imperial\"", true,
        ),
        "rates" to Kind(obj("rate" to Leaf, "history" to Arr(Leaf), "date" to Leaf), "base and quote (three-letter currency codes), days (1-365)", true),
        "feed" to Kind(obj("title" to Leaf, "items" to Arr(leaves("title", "link", "published"))), "url (https address of an RSS or Atom feed)", true),
        "http" to Kind(Unknown, "url (https address returning JSON; a top-level list is under .items)", true),
        "ai" to Kind(leaves("text"), "prompt", false),
        "air" to Kind(leaves("aqi", "pm25", "pm10"), "place: \"current\"", true),
        "alarm" to Kind(leaves("next"), "none (next is 0 when no alarm is set)", true),
        "health" to Kind(leaves("today"), "metric: \"steps\"", false),
        "notes" to Kind(leaves("text"), "note", false),
        "media" to Kind(leaves("title", "artist", "art"), "none", false),
    )

    /** Field paths of a source, like `now.temp` and `hourly[].rainChance`. */
    fun paths(kind: String): List<String> {
        val out = ArrayList<String>()
        fun walk(s: Shape, prefix: String) {
            when (s) {
                is Obj -> for ((k, v) in s.fields) walk(v, if (prefix.isEmpty()) k else "$prefix.$k")
                is Arr -> if (s.of == Leaf) out += "$prefix[]" else walk(s.of, "$prefix[]")
                Leaf -> out += prefix
                Unknown -> out += "(any JSON)"
            }
        }
        KINDS[kind]?.let { walk(it.shape, "") }
        return out
    }

    /** One line per source for the AI prompt. Times are epoch milliseconds. */
    fun describe(): String = KINDS.entries.joinToString("\n") { (name, k) ->
        "- $name" + (if (k.live) "" else " (no live data yet: shows a dash)") + ": with {${k.params}}; gives " + paths(name).joinToString(", ")
    }
}
