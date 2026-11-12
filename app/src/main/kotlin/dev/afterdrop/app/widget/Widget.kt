package dev.afterdrop.app.widget

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.afterdrop.app.HttpsFetcher
import dev.afterdrop.app.Sources
import dev.afterdrop.app.Store
import dev.afterdrop.engine.Engine
import dev.afterdrop.engine.RenderInput
import dev.afterdrop.engine.SizeClass
import dev.afterdrop.engine.StoredVar
import dev.afterdrop.engine.Vars
import dev.afterdrop.spec.SpecReader
import dev.afterdrop.spec.SpecResult
import dev.afterdrop.spec.WidgetSpec
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One widget class renders every spec: it looks up the spec bound to its id and interprets it. */
class AfterdropWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val store = Store(context)
        if (store.spec(appWidgetId) == null) {
            // Placed through "Add to home screen": the app left the chosen spec waiting.
            store.pending?.let {
                store.bind(appWidgetId, it)
                store.pending = null
                RefreshWorker.now(context)
            }
        }
        provideContent {
            GlanceTheme { WidgetContent(appWidgetId) }
        }
    }

    companion object {
        /** Changing this value in Glance state is what makes a placed widget draw again. */
        val TICK = longPreferencesKey("tick")
        val KEY = ActionParameters.Key<String>("key")
        val OP = ActionParameters.Key<String>("op")

        suspend fun refresh(context: Context, id: GlanceId) {
            updateAppWidgetState(context, id) { it[TICK] = System.currentTimeMillis() }
            AfterdropWidget().update(context, id)
        }

        suspend fun refreshAll(context: Context) {
            for (id in GlanceAppWidgetManager(context).getGlanceIds(AfterdropWidget::class.java)) refresh(context, id)
        }

        /** The row of dots under a stack: a filled one for the page that is showing. */
        fun dots(count: Int, showing: Int): String = (0 until count).joinToString(" ") { if (it == showing) "\u25CF" else "\u25CB" }

        /** Every page of a widget, for fetching data the hidden pages will need when flipped to. */
        fun readAllSpecs(context: Context, appWidgetId: Int): List<WidgetSpec> =
            Store(context).pages(appWidgetId).mapNotNull { (SpecReader.read(it) as? SpecResult.Ok)?.spec }

        fun readSpec(context: Context, appWidgetId: Int): WidgetSpec? {
            val json = Store(context).spec(appWidgetId) ?: return null
            return (SpecReader.read(json) as? SpecResult.Ok)?.spec
        }

        fun input(context: Context, appWidgetId: Int, spec: WidgetSpec, size: SizeClass): RenderInput {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            return RenderInput(
                spec = spec,
                data = Sources.collect(context, spec),
                vars = Vars.current(spec, Store(context).vars(appWidgetId), now, zone),
                nowMillis = now,
                zone = zone,
                locale = Locale.getDefault(),
                size = size,
            )
        }
    }
}

/** The 2 x 2 entry in the launcher's widget list. The others differ only in their starting size. */
open class AfterdropReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AfterdropWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.schedule(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val store = Store(context)
        for (id in appWidgetIds) store.unbind(id)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(RefreshWorker.NAME)
    }
}

class Receiver2x1 : AfterdropReceiver()
class Receiver4x1 : AfterdropReceiver()
class Receiver4x2 : AfterdropReceiver()
class Receiver4x3 : AfterdropReceiver()

/** The receiver whose starting size is closest to a spec's own size. */
fun receiverFor(cols: Int, rows: Int): Class<out AfterdropReceiver> = when {
    cols <= 2 && rows <= 1 -> Receiver2x1::class.java
    cols <= 2 -> AfterdropReceiver::class.java
    rows <= 1 -> Receiver4x1::class.java
    rows == 2 -> Receiver4x2::class.java
    else -> Receiver4x3::class.java
}

/** Runs a control or a tap, saves the changed variables, and redraws that one widget. */
class WidgetCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val op = parameters[AfterdropWidget.OP] ?: return
        val key = parameters[AfterdropWidget.KEY]
        if (op == "flip") Store(context).flip(GlanceAppWidgetManager(context).getAppWidgetId(glanceId))
        if (op == "refresh") RefreshWorker.now(context, force = true)
        if (op != "refresh" && op != "flip" && key != null) {
            val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
            val spec = AfterdropWidget.readSpec(context, appWidgetId) ?: return
            // Node keys do not depend on size, so the largest class finds every node.
            val input = AfterdropWidget.input(context, appWidgetId, spec, SizeClass.L)
            val changed = Engine.act(input, key, op)
            if (changed != null) {
                val store = Store(context)
                val stored = HashMap(store.vars(appWidgetId))
                for ((name, value) in changed) {
                    if (input.vars[name] != value) stored[name] = StoredVar(value, input.nowMillis)
                }
                store.saveVars(appWidgetId, stored)
            }
        }
        AfterdropWidget.refresh(context, glanceId)
    }
}

/**
 * The only background work: one periodic job that redraws placed widgets, so dates, daily resets and
 * battery level stay current. It runs at most every 15 minutes, is batched by the system, and is
 * cancelled when the last widget is removed.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val manager = GlanceAppWidgetManager(context)
        val specs = manager.getGlanceIds(AfterdropWidget::class.java)
            .flatMap { AfterdropWidget.readAllSpecs(context, manager.getAppWidgetId(it)) }
        runCatching { Sources.refresh(context, specs, HttpsFetcher, inputData.getBoolean("force", false)) }
        AfterdropWidget.refreshAll(context)
        return Result.success()
    }

    companion object {
        const val NAME = "refresh"

        /** One fetch soon, for a widget just added or a refresh tap. Waits for a connection instead of failing. */
        fun now(context: Context, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("force" to force))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("$NAME-now", ExistingWorkPolicy.REPLACE, request)
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
