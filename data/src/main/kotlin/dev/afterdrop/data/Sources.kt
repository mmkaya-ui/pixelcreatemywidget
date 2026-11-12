package dev.afterdrop.data

import dev.afterdrop.formula.Value
import dev.afterdrop.spec.JArray
import dev.afterdrop.spec.JBool
import dev.afterdrop.spec.JNull
import dev.afterdrop.spec.JNumber
import dev.afterdrop.spec.JObject
import dev.afterdrop.spec.JString
import dev.afterdrop.spec.Json
import dev.afterdrop.spec.JsonError
import dev.afterdrop.spec.JsonValue
import dev.afterdrop.spec.Source
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Fetches one https address and returns the body, or null when it cannot. Never throws. */
fun interface Fetcher {
    fun get(url: String): String?
}

/** A place on the map, as the weather and air sources need it. */
class Place(val name: String, val lat: Double, val lon: Double)

/** One network request a source needs, and how to turn its answer into widget data. */
class Request(val url: String, val parse: (String) -> Value?)

/**
 * The network data sources. Each turns a spec's source into the request it needs and parses the
 * answer into plain values. No Android types, so parsing is tested against recorded answers.
 * Only public, keyless services are used: Open-Meteo for weather and Frankfurter for exchange rates.
 */
object NetworkSources {
    /** Source kinds this object can fetch. */
    val KINDS = setOf("weather", "rates", "feed", "http")
    const val MAX_ITEMS = 20

    /** The hosts a source will contact, shown to the person before they add a widget. */
    fun host(source: Source): String? = when (source.use) {
        "weather" -> "api.open-meteo.com"
        "rates" -> "api.frankfurter.dev"
        "feed", "http" -> text(source, "url")?.let { hostOf(it) }
        else -> null
    }

    fun hostOf(url: String): String? =
        Regex("^https://([A-Za-z0-9.-]+)(?::\\d+)?(?:[/?#].*)?$").find(url)?.groupValues?.get(1)?.lowercase()

    /** A stable key for caching: two widgets with the same source share one fetch. */
    fun cacheKey(source: Source, home: Place?): String =
        source.use + "|" + Json.write(source.with) + (if (source.use == "weather") "|" + (home?.let { "${it.lat},${it.lon}" } ?: "") else "")

    /** How often to refetch, in milliseconds. Never faster than 15 minutes. */
    fun interval(source: Source): Long = when (source.every) {
        "15m" -> 15L
        "30m" -> 30L
        "3h" -> 180L
        "6h" -> 360L
        "12h" -> 720L
        "1d" -> 1440L
        "1w" -> 10080L
        else -> 60L
    } * 60_000L

    /**
     * @param home the person's saved place, used when a weather source says "current" or names none.
     * @return null when the source cannot be fetched as written, for example weather with no place set.
     */
    fun request(source: Source, home: Place?, today: LocalDate): Request? = when (source.use) {
        "weather" -> home?.let { place ->
            val imperial = text(source, "units") == "imperial"
            Request(weatherUrl(place, imperial)) { parseWeather(it) }
        }
        "rates" -> {
            val base = text(source, "base")?.uppercase()
            val quote = text(source, "quote")?.uppercase()
            val days = ((source.with["days"] as? JNumber)?.value ?: 30.0).coerceIn(1.0, 365.0).toLong()
            if (base == null || quote == null || !CODE.matches(base) || !CODE.matches(quote)) null
            else Request("https://api.frankfurter.dev/v1/${today.minusDays(days)}..?base=$base&symbols=$quote") { parseRates(it, quote) }
        }
        "feed" -> text(source, "url")?.takeIf { hostOf(it) != null }?.let { Request(it) { body -> parseFeed(body) } }
        "http" -> text(source, "url")?.takeIf { hostOf(it) != null }?.let { Request(it) { body -> parseJson(body) } }
        else -> null
    }

    private val CODE = Regex("[A-Z]{3}")
    private fun text(source: Source, key: String): String? = (source.with[key] as? JString)?.value

    // ---- weather (Open-Meteo) ----

    fun geocodeUrl(name: String): String =
        "https://geocoding-api.open-meteo.com/v1/search?count=1&format=json&name=" + URLEncoder.encode(name.trim().take(80), "UTF-8")

    fun parseGeocode(body: String): Place? {
        val first = ((json(body)?.get("results") as? JArray)?.items?.firstOrNull() as? JObject) ?: return null
        val lat = (first["latitude"] as? JNumber)?.value ?: return null
        val lon = (first["longitude"] as? JNumber)?.value ?: return null
        val name = (first["name"] as? JString)?.value ?: return null
        val country = (first["country_code"] as? JString)?.value
        return Place(if (country != null) "$name, $country" else name, lat, lon)
    }

    fun weatherUrl(place: Place, imperial: Boolean): String =
        "https://api.open-meteo.com/v1/forecast?latitude=${round4(place.lat)}&longitude=${round4(place.lon)}" +
            "&current=temperature_2m,apparent_temperature,wind_speed_10m,wind_gusts_10m,weather_code" +
            "&hourly=temperature_2m,precipitation_probability&forecast_hours=24&timeformat=unixtime" +
            (if (imperial) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else "")

    /** Two decimals, about a kilometre: enough for weather, and it keeps the exact position off the wire. */
    private fun round4(v: Double) = String.format(java.util.Locale.ROOT, "%.2f", v)

    fun parseWeather(body: String): Value? {
        val root = json(body) ?: return null
        val current = root["current"] as? JObject ?: return null
        fun cur(key: String): Value = (current[key] as? JNumber)?.let { Value.Num(it.value) } ?: Value.Null
        val hourly = root["hourly"] as? JObject
        fun column(key: String) = (hourly?.get(key) as? JArray)?.items ?: emptyList()
        val times = column("time")
        val temps = column("temperature_2m")
        val rain = column("precipitation_probability")
        val hours = times.indices.take(24).map { i ->
            Value.Obj(
                mapOf(
                    "time" to ((times[i] as? JNumber)?.let { Value.Num(it.value * 1000) } ?: Value.Null),
                    "temp" to ((temps.getOrNull(i) as? JNumber)?.let { Value.Num(it.value) } ?: Value.Null),
                    "rainChance" to ((rain.getOrNull(i) as? JNumber)?.let { Value.Num(it.value) } ?: Value.Null),
                )
            )
        }
        val nowSeconds = (current["time"] as? JNumber)?.value
        val soon = rain.take(3).mapNotNull { (it as? JNumber)?.value }
        return Value.Obj(
            mapOf(
                "now" to Value.Obj(
                    mapOf(
                        "temp" to cur("temperature_2m"), "feels" to cur("apparent_temperature"),
                        "wind" to cur("wind_speed_10m"), "gust" to cur("wind_gusts_10m"),
                        "summary" to Value.Str(summary((current["weather_code"] as? JNumber)?.value?.toInt())),
                    )
                ),
                "next3h" to Value.Obj(
                    mapOf(
                        "rainChance" to (soon.maxOrNull()?.let { Value.Num(it) } ?: Value.Null),
                        "until" to (nowSeconds?.let { Value.Num((it + 3 * 3600) * 1000) } ?: Value.Null),
                    )
                ),
                "hourly" to Value.ListV(hours),
            )
        )
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    fun summary(code: Int?): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        95, 96, 99 -> "Thunderstorm"
        else -> ""
    }

    // ---- exchange rates (Frankfurter, European Central Bank reference rates) ----

    fun parseRates(body: String, quote: String): Value? {
        val rates = json(body)?.get("rates") as? JObject ?: return null
        val series = rates.fields.entries.sortedBy { it.key }.mapNotNull { (_, day) -> ((day as? JObject)?.get(quote) as? JNumber)?.value }
        if (series.isEmpty()) return null
        return Value.Obj(
            mapOf(
                "rate" to Value.Num(series.last()),
                "history" to Value.ListV(series.map { Value.Num(it) }),
                "date" to Value.Str(rates.fields.keys.max()),
            )
        )
    }

    // ---- feeds (RSS 2.0 and Atom) ----

    fun parseFeed(body: String): Value? {
        val doc = try {
            val f = DocumentBuilderFactory.newInstance()
            // No DTDs and no external entities: a feed must not be able to make the app read files or call out.
            for (feature in listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
            )) runCatching { f.setFeature(feature.first, feature.second) }
            runCatching { f.isExpandEntityReferences = false }
            f.isNamespaceAware = false
            if (Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(body.take(4096))) return null
            f.newDocumentBuilder().apply { setErrorHandler(null) }.parse(body.byteInputStream(Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        val root = doc.documentElement ?: return null
        fun children(e: Element, name: String): List<Element> {
            val out = ArrayList<Element>()
            val nodes = e.childNodes
            for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let { if (it.tagName.substringAfter(':') == name || it.tagName == name) out += it }
            return out
        }
        fun textOf(e: Element, name: String): String? = children(e, name).firstOrNull()?.textContent?.replace(Regex("\\s+"), " ")?.trim()?.take(300)?.takeIf { it.isNotEmpty() }
        val atom = root.tagName == "feed"
        val channel = if (atom) root else children(root, "channel").firstOrNull() ?: return null
        val entries = children(channel, if (atom) "entry" else "item").take(MAX_ITEMS)
        val items = entries.mapNotNull { e ->
            val title = textOf(e, "title") ?: return@mapNotNull null
            val link = if (atom) children(e, "link").firstOrNull { it.getAttribute("rel").let { r -> r.isEmpty() || r == "alternate" } }?.getAttribute("href") else textOf(e, "link")
            val published = time(textOf(e, if (atom) "updated" else "pubDate") ?: textOf(e, "published") ?: textOf(e, "date"))
            Value.Obj(
                mapOf(
                    "title" to Value.Str(title),
                    "link" to (link?.takeIf { it.startsWith("https://") }?.let { Value.Str(it.take(500)) } ?: Value.Null),
                    "published" to (published?.let { Value.Num(it.toDouble()) } ?: Value.Null),
                )
            )
        }
        return Value.Obj(mapOf("title" to (textOf(channel, "title")?.let { Value.Str(it) } ?: Value.Null), "items" to Value.ListV(items)))
    }

    private fun time(s: String?): Long? {
        if (s == null) return null
        return runCatching { ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(s).toEpochMilli() }
            .recoverCatching { LocalDate.parse(s.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
            .getOrNull()
    }

    // ---- generic JSON ----

    /** Any JSON answer, trimmed so a huge document cannot bloat a widget: lists keep their first 50 entries. */
    fun parseJson(body: String): Value? = json(body, any = true)?.let { trim(it, 0) }

    private fun trim(v: JsonValue, depth: Int): Value = when (v) {
        is JNumber -> Value.Num(v.value)
        is JBool -> Value.Bool(v.value)
        is JString -> Value.Str(v.value.take(500))
        is JArray -> if (depth > 8) Value.Null else Value.ListV(v.items.take(50).map { trim(it, depth + 1) })
        is JObject -> if (depth > 8) Value.Null else Value.Obj(v.fields.entries.take(100).associate { it.key to trim(it.value, depth + 1) })
        JNull -> Value.Null
    }

    private fun json(body: String, any: Boolean = false): JObject? = try {
        val v = Json.parse(body)
        if (v is JObject) v else if (any) JObject(mapOf("items" to v)) else null
    } catch (_: JsonError) {
        null
    }
}

/** Storage form of a value, the reverse of reading JSON into values. */
fun Value.toJson(): JsonValue = when (this) {
    is Value.Num -> JNumber(v)
    is Value.Bool -> JBool(v)
    is Value.Str -> JString(v)
    is Value.ListV -> JArray(items.map { it.toJson() })
    is Value.Obj -> JObject(fields.mapValues { it.value.toJson() })
    Value.Null -> JNull
}
