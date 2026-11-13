package dev.afterdrop.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import dev.afterdrop.data.Fetcher
import dev.afterdrop.data.NetworkSources
import dev.afterdrop.data.toJson
import dev.afterdrop.engine.toValue
import dev.afterdrop.formula.Value
import dev.afterdrop.spec.JNumber
import dev.afterdrop.spec.Source
import dev.afterdrop.spec.WidgetSpec
import java.time.LocalDate

/**
 * Data for a spec's sources. `battery` and `device` are read on the spot and need no permission.
 * Network sources (weather, rates, feed, http) are read from the cache that [refresh] fills, so
 * drawing a widget never waits on the network. Sources not provided yet read as missing.
 */
object Sources {
    val AVAILABLE = setOf("battery", "device", "calendar", "alarm") + NetworkSources.KINDS

    /** Services the app itself chose. Any other host must be allowed by the person first. */
    val BUILT_IN_HOSTS = setOf("api.open-meteo.com", "air-quality-api.open-meteo.com", "api.frankfurter.dev")

    fun allowed(context: Context, source: Source): Boolean {
        val host = NetworkSources.host(source) ?: return true
        return host in BUILT_IN_HOSTS || host in Store(context).approvedHosts()
    }

    fun canReadCalendar(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun collect(context: Context, spec: WidgetSpec): Map<String, Value> {
        val out = HashMap<String, Value>()
        val store = Store(context)
        val home = store.home
        for ((alias, source) in spec.sources) {
            val value = runCatching {
                when (source.use) {
                    "battery" -> battery(context)
                    "device" -> device()
                    "calendar" -> calendar(context, source)
                    // The next alarm the system knows about. Needs no permission; 0 means none is set.
                    "alarm" -> Value.Obj(mapOf("next" to Value.Num(
                        (context.getSystemService(android.app.AlarmManager::class.java)?.nextAlarmClock?.triggerTime ?: 0L).toDouble()
                    )))
                    in NetworkSources.KINDS -> store.cached(NetworkSources.cacheKey(source, home))?.first?.toValue()
                    else -> null
                }
            }.getOrNull()
            if (value != null) out[alias] = value
        }
        return out
    }

    /**
     * Fetches what the given specs need and is due. Call off the main thread.
     * @return how many sources were updated.
     */
    fun refresh(context: Context, specs: List<WidgetSpec>, fetcher: Fetcher, force: Boolean = false): Int {
        val store = Store(context)
        val home = store.home
        val now = System.currentTimeMillis()
        val done = HashSet<String>()
        var updated = 0
        for (spec in specs) for (source in spec.sources.values) {
            if (source.use !in NetworkSources.KINDS || !allowed(context, source)) continue
            val key = NetworkSources.cacheKey(source, home)
            if (!done.add(key)) continue
            val age = store.cached(key)?.let { now - it.second }
            if (!force && age != null && age < NetworkSources.interval(source)) continue
            val request = NetworkSources.request(source, home, LocalDate.now()) ?: continue
            val body = fetcher.get(request.url) ?: continue
            val value = runCatching { request.parse(body) }.getOrNull() ?: continue
            store.cache(key, value.toJson(), now)
            updated++
        }
        return updated
    }

    /**
     * Upcoming events, read on the phone from the system calendar. Nothing here is ever sent anywhere:
     * the widget format refuses any widget that would put calendar values into a request.
     */
    private fun calendar(context: Context, source: Source): Value? {
        if (!canReadCalendar(context)) return null
        val days = ((source.with["daysAhead"] as? JNumber)?.value ?: 2.0).coerceIn(1.0, 31.0).toLong()
        val limit = ((source.with["limit"] as? JNumber)?.value ?: 5.0).coerceIn(1.0, 20.0).toInt()
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, now + days * 86_400_000L)
            it.build()
        }
        val columns = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
        )
        val events = ArrayList<Value>()
        context.contentResolver.query(uri, columns, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext() && events.size < limit) {
                events += Value.Obj(
                    mapOf(
                        "title" to Value.Str((c.getString(0) ?: "").take(200)),
                        "start" to Value.Num(c.getLong(1).toDouble()),
                        "end" to Value.Num(c.getLong(2).toDouble()),
                        "allDay" to Value.Bool(c.getInt(3) != 0),
                        "location" to Value.Str((c.getString(4) ?: "").take(200)),
                    )
                )
            }
        }
        return Value.Obj(mapOf("events" to Value.ListV(events)))
    }

    private fun battery(context: Context): Value? {
        // A sticky broadcast: reading it registers nothing and wakes nothing.
        val i = context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return Value.Obj(
            mapOf(
                "level" to Value.Num(Math.rint(level * 100.0 / scale)),
                "charging" to Value.Bool(status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL),
                "temp" to Value.Num(i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0),
            )
        )
    }

    private fun device(): Value {
        val fs = StatFs(Environment.getDataDirectory().path)
        val gb = 1024.0 * 1024.0 * 1024.0
        return Value.Obj(
            mapOf(
                "storageFreeGb" to Value.Num(fs.availableBytes / gb),
                "storageTotalGb" to Value.Num(fs.totalBytes / gb),
            )
        )
    }
}
