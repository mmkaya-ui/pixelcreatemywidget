package dev.afterdrop.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.health.connect.AggregateRecordsGroupedByPeriodResponse
import android.health.connect.AggregateRecordsRequest
import android.health.connect.AggregateRecordsResponse
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.LocalTimeRangeFilter
import android.health.connect.datatypes.StepsRecord
import android.os.OutcomeReceiver
import dev.afterdrop.formula.Value
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Steps from Health Connect, which is built into Android 14 and newer. It merges steps the phone
 * counts by itself with those from a watch or fitness app. Read on the phone; never sent anywhere.
 */
object Steps {
    const val READ = "android.permission.health.READ_STEPS"
    /** Lets widgets update while Afterdrop itself is not open. */
    const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    val PERMISSIONS = arrayOf(READ, BACKGROUND)

    private const val TIMEOUT_MS = 3_000L
    internal val direct = Executor { it.run() }

    fun available(context: Context) = context.getSystemService(HealthConnectManager::class.java) != null

    fun granted(context: Context) = context.checkSelfPermission(READ) == PackageManager.PERMISSION_GRANTED

    fun backgroundGranted(context: Context) = context.checkSelfPermission(BACKGROUND) == PackageManager.PERMISSION_GRANTED

    /** Health Connect's own screen, where the person sees and manages what each app may read. */
    fun settingsIntent(): Intent = Intent("android.health.connect.action.HEALTH_HOME_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** `today` and `week` (the last seven days, oldest first, today last), or null without access. */
    fun value(context: Context): Value? {
        if (!granted(context)) return null
        val hc = context.getSystemService(HealthConnectManager::class.java) ?: return null
        val today = LocalDate.now()
        val now = LocalDateTime.now()
        val weekStart = today.minusDays(6).atStartOfDay()

        fun request(from: LocalDateTime) =
            AggregateRecordsRequest.Builder<Long>(LocalTimeRangeFilter.Builder().setStartTime(from).setEndTime(now).build())
                .addAggregationType(StepsRecord.STEPS_COUNT_TOTAL)
                .build()

        val todayCount: Long? = await<AggregateRecordsResponse<Long>> { hc.aggregate(request(today.atStartOfDay()), direct, it) }
            ?.get(StepsRecord.STEPS_COUNT_TOTAL)
        val days = await<List<AggregateRecordsGroupedByPeriodResponse<Long>>> {
            hc.aggregateGroupByPeriod(request(weekStart), Period.ofDays(1), direct, it)
        }
        // A day with no record still gets its place in the week, as 0.
        val byDay = days?.associate { it.startTime.toLocalDate() to (it.get(StepsRecord.STEPS_COUNT_TOTAL) ?: 0L) } ?: emptyMap()
        val week = (6 downTo 0).map { back -> Value.Num((byDay[today.minusDays(back.toLong())] ?: 0L).toDouble()) }
        return Value.Obj(
            mapOf(
                "today" to Value.Num((todayCount ?: byDay[today] ?: 0L).toDouble()),
                "week" to Value.ListV(if (days == null) emptyList() else week),
            )
        )
    }

    /** Health Connect answers through a callback; a widget draw waits a moment for it, never longer. */
    internal fun <T> await(call: (OutcomeReceiver<T, HealthConnectException>) -> Unit): T? {
        val result = AtomicReference<T?>()
        val done = CountDownLatch(1)
        runCatching {
            call(object : OutcomeReceiver<T, HealthConnectException> {
                override fun onResult(r: T) {
                    result.set(r)
                    done.countDown()
                }

                override fun onError(e: HealthConnectException) {
                    done.countDown()
                }
            })
        }.onFailure { return null }
        done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return result.get()
    }
}
