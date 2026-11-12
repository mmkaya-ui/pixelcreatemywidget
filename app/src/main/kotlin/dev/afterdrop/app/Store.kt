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

    fun spec(appWidgetId: Int): String? = prefs.getString("spec.$appWidgetId", null)

    fun bind(appWidgetId: Int, json: String) {
        prefs.edit().putString("spec.$appWidgetId", json).putString("name.$appWidgetId", nameOf(json)).remove("vars.$appWidgetId").commit()
    }

    private fun nameOf(json: String): String =
        runCatching { ((Json.parse(json) as? JObject)?.get("name") as? JString)?.value }.getOrNull() ?: ""

    /**
     * Gives every placed widget that shows the spec called [name] the new [json], keeping its
     * variables, so an edit reaches the home screen. Returns how many widgets changed.
     */
    fun rebind(name: String, json: String): Int {
        val ids = prefs.all.keys.filter { it.startsWith("name.") && prefs.getString(it, null) == name }.map { it.removePrefix("name.") }
        if (ids.isEmpty()) return 0
        val e = prefs.edit()
        for (id in ids) e.putString("spec.$id", json).putString("name.$id", nameOf(json))
        e.commit()
        return ids.size
    }

    /** Internet hosts the person has allowed for feed and JSON widgets. The built-in services need no approval. */
    fun approvedHosts(): Set<String> = prefs.getStringSet("hosts", emptySet()) ?: emptySet()

    fun approveHost(host: String) {
        prefs.edit().putStringSet("hosts", approvedHosts() + host).commit()
    }

    fun unbind(appWidgetId: Int) {
        prefs.edit().remove("spec.$appWidgetId").remove("name.$appWidgetId").remove("vars.$appWidgetId").commit()
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

    fun vars(appWidgetId: Int): Map<String, StoredVar> {
        val text = prefs.getString("vars.$appWidgetId", null) ?: return emptyMap()
        return runCatching { Vars.decode(Json.parse(text)) }.getOrDefault(emptyMap())
    }

    fun saveVars(appWidgetId: Int, vars: Map<String, StoredVar>) {
        prefs.edit().putString("vars.$appWidgetId", Json.write(Vars.encode(vars))).commit()
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

    private companion object {
        const val PENDING_TTL = 2 * 60_000L
        const val MAX_USER_SPECS = 50
    }
}
