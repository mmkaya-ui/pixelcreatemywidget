package dev.afterdrop.app

import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import dev.afterdrop.app.widget.receiverFor
import dev.afterdrop.app.widget.AfterdropWidget
import dev.afterdrop.app.widget.RefreshWorker
import dev.afterdrop.ai.Gemini
import dev.afterdrop.ai.Generator
import dev.afterdrop.ai.Outcome
import dev.afterdrop.composer.ChatPrompt
import dev.afterdrop.data.NetworkSources
import dev.afterdrop.composer.Composer
import dev.afterdrop.composer.Describe
import dev.afterdrop.composer.Described
import dev.afterdrop.spec.Json
import dev.afterdrop.spec.SpecReader
import dev.afterdrop.spec.SpecResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** One gallery entry: a spec that is known to be valid. */
class Entry(val name: String, val about: String, val json: String, val missing: List<String>, val mine: Boolean, val cols: Int, val rows: Int, val hosts: List<String>, val needsPlace: Boolean)

class MainActivity : ComponentActivity() {
    private var bindId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        bindId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (bindId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            // Leaving without choosing must cancel a launcher-started configuration.
            setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, bindId))
        }
        RefreshWorker.schedule(this)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)) {
                Screen()
            }
        }
    }

    private fun entry(json: String, mine: Boolean): Entry? {
        val spec = (SpecReader.read(json) as? SpecResult.Ok)?.spec ?: return null
        val missing = spec.sources.values.map { it.use }.filter { it !in Sources.AVAILABLE }.distinct()
        val hosts = spec.sources.values.mapNotNull { NetworkSources.host(it) }.distinct()
        return Entry(spec.name, spec.about, json, missing, mine, spec.cols, spec.rows, hosts, spec.sources.values.any { it.use == "weather" })
    }

    private fun entries(): List<Entry> {
        val bundled = (assets.list("") ?: emptyArray()).filter { it.endsWith(".json") }.sorted().mapNotNull { file ->
            runCatching { assets.open(file).bufferedReader().use { it.readText() } }.getOrNull()?.let { entry(it, false) }
        }
        // Newest first, so a widget just created is at the top where the message says it is.
        val mine = Store(this).userSpecs().values.reversed().mapNotNull { entry(it, true) }
        return mine + bundled.sortedBy { it.missing.size }
    }

    private fun choose(e: Entry, say: (String) -> Unit) {
        val store = Store(this)
        if (bindId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            store.bind(bindId, e.json)
            lifecycleScope.launch {
                runCatching {
                    AfterdropWidget.refresh(this@MainActivity, GlanceAppWidgetManager(this@MainActivity).getGlanceIdBy(bindId))
                }
                RefreshWorker.now(this@MainActivity)
                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, bindId))
                finish()
            }
            return
        }
        val manager = AppWidgetManager.getInstance(this)
        store.pending = e.json
        val asked = manager.isRequestPinAppWidgetSupported &&
            manager.requestPinAppWidget(ComponentName(this, receiverFor(e.cols, e.rows)), null, null)
        if (!asked) {
            store.pending = null
            say("Your launcher cannot add widgets from an app. Long-press the home screen, add the Afterdrop widget, then tap it.")
        }
    }

    @Composable
    private fun Screen() {
        var list by remember { mutableStateOf(entries()) }
        var draft by remember { mutableStateOf("") }
        var report by remember { mutableStateOf(listOf<String>()) }
        var ask by remember { mutableStateOf("") }
        var askReport by remember { mutableStateOf(listOf<String>()) }
        var key by remember { mutableStateOf(Store(this@MainActivity).apiKey) }
        var busy by remember { mutableStateOf(false) }
        var city by remember { mutableStateOf("") }
        var place by remember { mutableStateOf(Store(this@MainActivity).home?.name) }
        var cityReport by remember { mutableStateOf("") }
        Scaffold(Modifier.fillMaxSize().imePadding()) { inner ->
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = inner.calculateTopPadding() + 16.dp, bottom = inner.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text("Afterdrop", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (bindId != AppWidgetManager.INVALID_APPWIDGET_ID) "Choose what this widget shows."
                        else "Pick a widget to add to your home screen, or paste a widget description below.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Describe a widget", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = ask, onValueChange = { ask = it.take(500) },
                            modifier = Modifier.fillMaxWidth(), maxLines = 4,
                            label = { Text("For example: countdown to 24 December") },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(enabled = ask.isNotBlank(), onClick = {
                                askReport = when (val d = Describe.fromText(ask, LocalDate.now())) {
                                    is Described.No -> listOf(d.reason)
                                    is Described.Ok -> when (val r = Composer.compose(d.brief)) {
                                        is SpecResult.Ok -> {
                                            Store(this@MainActivity).saveUserSpec(r.spec.name, Json.write(r.spec.json))
                                            list = entries()
                                            ask = ""
                                            listOf("Created \"${r.spec.name}\". It is first in the list below.")
                                        }
                                        is SpecResult.Rejected -> r.problems.take(4).map { it.message }
                                    }
                                }
                            }) { Text("Create") }
                            TextButton(enabled = ask.isNotBlank(), onClick = {
                                val clipboard = getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Afterdrop prompt", ChatPrompt.build(ask)))
                                askReport = listOf(
                                    "Prompt copied. Paste it into any chat assistant, then paste its answer under \"Paste a widget description\" at the bottom."
                                )
                            }) { Text("Copy chatbot prompt") }
                        }
                        Button(enabled = ask.isNotBlank() && !busy, onClick = {
                            if (key.isBlank()) {
                                askReport = listOf("Add a Gemini API key below first. A free key comes from aistudio.google.com/apikey.")
                            } else {
                                busy = true
                                askReport = listOf("Asking the model. This usually takes a few seconds.")
                                val wanted = ask
                                lifecycleScope.launch {
                                    val out = withContext(Dispatchers.IO) {
                                        runCatching { Generator.generate(wanted, Gemini(GeminiTransport, key.trim()), LocalDate.now()) }
                                            .getOrElse { Outcome.Failed("Something went wrong: ${it.message}") }
                                    }
                                    busy = false
                                    askReport = when (out) {
                                        is Outcome.Ok -> {
                                            Store(this@MainActivity).saveUserSpec(out.spec.name, Json.write(out.spec.json))
                                            list = entries()
                                            ask = ""
                                            listOfNotNull("Created \"${out.spec.name}\". It is first in the list below.", out.note)
                                        }
                                        is Outcome.Failed -> listOf(out.reason)
                                    }
                                }
                            }
                        }) { Text(if (busy) "Working" else "Create with AI") }
                        for (line in askReport) Text(line, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = key, onValueChange = { key = it.take(200); Store(this@MainActivity).apiKey = key },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text("Gemini API key (optional, for Create with AI)") },
                        )
                        Text(
                            "With a key, your description is sent to Google's Gemini API. Nothing else leaves the phone. Without a key, Create uses rules on the phone.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                items(list) { e ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(e.name, style = MaterialTheme.typography.titleMedium)
                            if (e.about.isNotEmpty()) Text(e.about, style = MaterialTheme.typography.bodyMedium)
                            if (e.hosts.isNotEmpty()) {
                                Text("Gets data from: ${e.hosts.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (e.needsPlace && place == null) {
                                Text("Set your city below to see weather.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            if (e.missing.isNotEmpty()) {
                                Text(
                                    "Shows dashes for now: ${e.missing.joinToString(", ")} data is not in this version.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { choose(e) { report = listOf(it) } }) {
                                    Text(if (bindId != AppWidgetManager.INVALID_APPWIDGET_ID) "Use this" else "Add to home screen")
                                }
                                if (e.mine) {
                                    TextButton(enabled = !busy, onClick = {
                                        val spec = (SpecReader.read(e.json) as? SpecResult.Ok)?.spec
                                        when {
                                            ask.isBlank() -> askReport = listOf("Type what to change in the box at the top, for example \"bigger number, secondary colour\", then press Change with AI on the widget.")
                                            key.isBlank() -> askReport = listOf("Changing a widget needs a Gemini API key. Add one above.")
                                            spec != null -> {
                                                busy = true
                                                askReport = listOf("Asking the model to change \"${e.name}\".")
                                                val wanted = ask
                                                lifecycleScope.launch {
                                                    val out = withContext(Dispatchers.IO) {
                                                        runCatching { Generator.edit(spec, wanted, Gemini(GeminiTransport, key.trim())) }
                                                            .getOrElse { Outcome.Failed("Something went wrong: ${it.message}") }
                                                    }
                                                    busy = false
                                                    askReport = when (out) {
                                                        is Outcome.Ok -> {
                                                            val store = Store(this@MainActivity)
                                                            if (out.spec.name != e.name) store.deleteUserSpec(e.name)
                                                            store.saveUserSpec(out.spec.name, Json.write(out.spec.json))
                                                            list = entries()
                                                            ask = ""
                                                            listOf("Changed \"${out.spec.name}\". Widgets already on the home screen keep their old look; add it again to use the new one.")
                                                        }
                                                        is Outcome.Failed -> listOf(out.reason, "The widget was left as it was.")
                                                    }
                                                }
                                            }
                                        }
                                    }) { Text("Change with AI") }
                                    TextButton(onClick = {
                                        Store(this@MainActivity).deleteUserSpec(e.name)
                                        list = entries()
                                    }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Weather place", style = MaterialTheme.typography.titleMedium)
                        Text(
                            place?.let { "Weather is shown for $it." } ?: "No place set. Weather widgets show dashes until you set one.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = city, onValueChange = { city = it.take(80) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("City") },
                        )
                        Button(enabled = city.isNotBlank(), onClick = {
                            val wanted = city
                            cityReport = "Looking it up."
                            lifecycleScope.launch {
                                val found = withContext(Dispatchers.IO) {
                                    HttpsFetcher.get(NetworkSources.geocodeUrl(wanted))?.let { NetworkSources.parseGeocode(it) }
                                }
                                if (found == null) cityReport = "Could not find that place. Check the spelling and the connection."
                                else {
                                    Store(this@MainActivity).home = found
                                    place = found.name
                                    city = ""
                                    cityReport = "The city name was sent to open-meteo.com to find it. No location permission is used."
                                    RefreshWorker.now(this@MainActivity, force = true)
                                }
                            }
                        }) { Text("Set place") }
                        if (cityReport.isNotEmpty()) Text(cityReport, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Text("Paste a widget description", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it.take(SpecReader.MAX_BYTES) },
                        modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 12,
                        label = { Text("Widget JSON") },
                    )
                    Button(onClick = {
                        when (val r = SpecReader.read(draft, lenient = true)) {
                            is SpecResult.Ok -> {
                                Store(this@MainActivity).saveUserSpec(r.spec.name, Json.write(r.spec.json))
                                list = entries()
                                draft = ""
                                report = listOf("Saved \"${r.spec.name}\". It is at the top of the list.")
                            }
                            is SpecResult.Rejected -> report = r.problems.take(8).map { "${it.path}: ${it.message}" }
                        }
                    }, enabled = draft.isNotBlank()) { Text("Check and save") }
                    for (line in report) Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    companion object {
        /** Opens the app to choose or change what one placed widget shows. */
        fun bindIntent(context: Context, appWidgetId: Int): Intent =
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse("afterdrop://widget/$appWidgetId"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
