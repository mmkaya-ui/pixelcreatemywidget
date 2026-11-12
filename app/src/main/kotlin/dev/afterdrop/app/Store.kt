package dev.afterdrop.app

import android.content.Context
import dev.afterdrop.engine.StoredVar
import dev.afterdrop.engine.Vars
import dev.afterdrop.data.Place
import dev.afterdrop.spec.JNumber
import dev.afterdrop.spec.JObject
import dev.afterdrop.spec.JsonValue
import dev.afterdrop.spec.JString
import dev.afterdrop.spec.Json

/** Everything the app keeps: which spec each placed widget shows, its variables, and saved specs. */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("afterdrop", Context.MODE_PRIVATE)

    /**
     * A placed widget holds a stack of one to [MAX_PAGES] specs and shows one of them at a time.
     * Most widgets have a single page; a stack is flipped by tapping its dots.
     */
    fun pages(appWidgetId: Int): List<String> {
        val text = prefs.getString("stack.$appWidgetId", null) ?: return emptyList()
        val items = runCatching { (Json.parse(text) as? dev.afterdrop.spec.JArray)?.items }.getOrNull() ?: return emptyList()
        return items.mapNotNull { (it as? JString)?.value }
    }

    private fun savePages(appWidgetId: Int, pages: List<String>, page: Int) {
        prefs.edit()
            .putString("stack.$appWidgetId", Json.write(dev.afterdrop.spec.JArray(pages.map { JString(it) })))
            .putInt("page.$appWidgetId", page.coerceIn(0, Math.max(0, pages.size - 1)))
            .commit()
    }

    /** Which page is showing, always a valid index when there are pages. */
    fun page(appWidgetId: Int): Int = prefs.getInt("page.$appWidgetId", 0).coerceIn(0, Math.max(0, pages(appWidgetId).size - 1))

    /** Shows the next page, wrapping round. Returns the new page index. */
    fun flip(appWidgetId: Int): Int {
        val pages = pages(appWidgetId)
        if (pages.size < 2) return 0
        val next = (page(appWidgetId) + 1) % pages.size
        prefs.edit().putInt("page.$appWidgetId", next).commit()
        return next
    }

    /** The spec on the page that is showing. */
    fun spec(appWidgetId: Int): String? = pages(appWidgetId).getOrNull(page(appWidgetId))

    /** Makes the widget show just this spec, dropping any stack and its saved values. */
    fun bind(appWidgetId: Int, json: String) {
        clearVars(appWidgetId)
        savePages(appWidgetId, listOf(json), 0)
    }

    /** Adds a page to the widget's stack and shows it. Returns false when the stack is full. */
    fun addPage(appWidgetId: Int, json: String): Boolean {
        val pages = pages(appWidgetId)
        if (pages.size >= MAX_PAGES) return false
        savePages(appWidgetId, pages + json, pages.size)
        return true
    }

    /** Removes one page. The last remaining page cannot be removed. */
    fun removePage(appWidgetId: Int, index: Int): Boolean {
        val pages = pages(appWidgetId)
        if (pages.size < 2 || index !in pages.indices) return false
        prefs.edit().remove(varsSlot(appWidgetId, pages[index])).commit()
        val showing = page(appWidgetId)
        savePages(appWidgetId, pages.filterIndexed { i, _ -> i != index }, if (index < showing) showing - 1 else showing)
        return true
    }

    fun unbind(appWidgetId: Int) {
        clearVars(appWidgetId)
        prefs.edit().remove("stack.$appWidgetId").remove("page.$appWidgetId").commit()
    }

    private fun clearVars(appWidgetId: Int) {
        val e = prefs.edit()
        for (k in prefs.all.keys) if (k.startsWith("vars.$appWidgetId.")) e.remove(k)
        e.commit()
    }

    fun nameOf(json: String): String =
        runCatching { ((Json.parse(json) as? JObject)?.get("name") as? JString)?.value }.getOrNull() ?: ""

    /**
     * Gives every placed widget page that shows the spec called [name] the new [json], keeping its
     * variables, so an edit reaches the home screen. Returns how many pages changed.
     */
    fun rebind(name: String, json: String): Int {
        var changed = 0
        for (key in prefs.all.keys.filter { it.startsWith("stack.") }) {
            val id = key.removePrefix("stack.").toIntOrNull() ?: continue
            val pages = pages(id)
            if (pages.none { nameOf(it) == name }) continue
            val e = prefs.edit()
            val updated = pages.map { old ->
                if (nameOf(old) != name) old else {
                    changed++
                    // Values are filed under the page's name, so carry them over if the edit renamed it.
                    prefs.getString(varsSlot(id, old), null)?.let { e.remove(varsSlot(id, old)).putString(varsSlot(id, json), it) }
                    json
                }
            }
            e.commit()
            savePages(id, updated, page(id))
        }
        return changed
    }

    /** Internet hosts the person has allowed for feed and JSON widgets. The built-in services need no approval. */
    fun approvedHosts(): Set<String> = prefs.getStringSet("hosts", emptySet()) ?: emptySet()

    fun approveHost(host: String) {
        prefs.edit().putStringSet("hosts", approvedHosts() + host).commit()
    }

    /** The spec chosen in the app and waiting for the launcher to create its widget. */
    var pending: String?
        get() = prefs.getString("pending", null)?.takeIf {
            System.currentTimeMillis() - prefs.getLong("pendingAt", 0) < PENDING_TTL
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove("pending").remove("pendingAt")
                else putString("pending", value).putLong("pendingAt", System.currentTimeMillis())
            }.commit()
        }

    /** Each page keeps its own values, filed under its spec's name. */
    private fun varsSlot(appWidgetId: Int, json: String) = "vars.$appWidgetId." + Integer.toHexString(nameOf(json).hashCode())

    /** The saved values of the page that is showing. */
    fun vars(appWidgetId: Int): Map<String, StoredVar> {
        val json = spec(appWidgetId) ?: return emptyMap()
        val text = prefs.getString(varsSlot(appWidgetId, json), null) ?: return emptyMap()
        return runCatching { Vars.decode(Json.parse(text)) }.getOrDefault(emptyMap())
    }

    fun saveVars(appWidgetId: Int, vars: Map<String, StoredVar>) {
        val json = spec(appWidgetId) ?: return
        prefs.edit().putString(varsSlot(appWidgetId, json), Json.write(Vars.encode(vars))).commit()
    }

    /** The person's own Gemini API key. Kept in app-private storage and sent only to the Gemini API. */
    var apiKey: String
        get() = prefs.getString("gemini.key", "") ?: ""
        set(value) {
            prefs.edit().putString("gemini.key", value.trim()).commit()
        }

    /** The place weather is shown for. Set by city name in the app; no location permission is used. */
    var home: Place?
        get() {
            val name = prefs.getString("home.name", null) ?: return null
            return Place(name, prefs.getFloat("home.lat", 0f).toDouble(), prefs.getFloat("home.lon", 0f).toDouble())
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove("home.name").remove("home.lat").remove("home.lon")
                else putString("home.name", value.name).putFloat("home.lat", value.lat.toFloat()).putFloat("home.lon", value.lon.toFloat())
            }.commit()
        }

    private fun cacheSlot(key: String) = "cache." + Integer.toHexString(key.hashCode()) + "." + key.length

    /** The last fetched data for a source and when it was fetched, or null. */
    fun cached(key: String): Pair<JsonValue, Long>? {
        val text = prefs.getString(cacheSlot(key), null) ?: return null
        val o = runCatching { Json.parse(text) as? JObject }.getOrNull() ?: return null
        if ((o["k"] as? JString)?.value != key) return null
        val at = (o["at"] as? JNumber)?.value?.toLong() ?: return null
        return (o["v"] ?: return null) to at
    }

    fun cache(key: String, value: JsonValue, at: Long) {
        val entry = JObject(mapOf("k" to JString(key), "at" to JNumber(at.toDouble()), "v" to value))
        prefs.edit().putString(cacheSlot(key), Json.write(entry)).commit()
    }

    /** The Gemini model that last answered, so the next request does not have to search for one again. */
    var model: String?
        get() = prefs.getString("gemini.model", null)
        set(value) {
            prefs.edit().putString("gemini.model", value).commit()
        }

    /** Specs the user pasted, by name. */
    fun userSpecs(): Map<String, String> {
        val text = prefs.getString("user", null) ?: return emptyMap()
        val o = runCatching { Json.parse(text) as? JObject }.getOrNull() ?: return emptyMap()
        return o.fields.mapNotNull { (k, v) -> (v as? JString)?.let { k to it.value } }.toMap()
    }

    fun saveUserSpec(name: String, json: String) {
        val all = LinkedHashMap(userSpecs())
        all[name] = json
        while (all.size > MAX_USER_SPECS) all.remove(all.keys.first())
        prefs.edit().putString("user", Json.write(JObject(all.mapValues { JString(it.value) }))).commit()
    }

    fun deleteUserSpec(name: String) {
        val all = LinkedHashMap(userSpecs())
        all.remove(name)
        prefs.edit().putString("user", Json.write(JObject(all.mapValues { JString(it.value) }))).commit()
    }

    companion object {
        private const val PENDING_TTL = 2 * 60_000L
        const val MAX_USER_SPECS = 50
        const val MAX_PAGES = 6
    }
}
