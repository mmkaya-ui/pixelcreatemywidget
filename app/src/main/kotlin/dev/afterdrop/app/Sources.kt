package dev.afterdrop.app

import android.content.Context
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
import dev.afterdrop.spec.WidgetSpec
import java.time.LocalDate

/**
 * Data for a spec's sources. `battery` and `device` are read on the spot and need no permission.
 * Network sources (weather, rates, feed, http) are read from the cache that [refresh] fills, so
 * drawing a widget never waits on the network. Sources not provided yet read as missing.
 */
object Sources {
    val AVAILABLE = setOf("battery", "device") + NetworkSources.KINDS

    fun collect(context: Context, spec: WidgetSpec): Map<String, Value> {
        val out = HashMap<String, Value>()
        val store = Store(context)
        val home = store.home
        for ((alias, source) in spec.sources) {
            val value = runCatching {
                when (source.use) {
                    "battery" -> battery(context)
                    "device" -> device()
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
            if (source.use !in NetworkSources.KINDS) continue
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
