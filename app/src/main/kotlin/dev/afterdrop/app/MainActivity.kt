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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableIntStateOf
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
class Entry(
    val name: String, val about: String, val json: String, val missing: List<String>, val mine: Boolean, val cols: Int, val rows: Int,
    val hosts: List<String>, val needsPlace: Boolean, val needsCalendar: Boolean,
    val uses: Set<String> = emptySet(),
)

class MainActivity : ComponentActivity() {
    private var bindId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var onCalendarAnswer: (Boolean) -> Unit = {}
    private val askCalendar = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        onCalendarAnswer(granted)
        if (granted) lifecycleScope.launch { runCatching { AfterdropWidget.refreshAll(this@MainActivity) } }
    }

    /** Health Connect shows its own screen for this request, listing exactly what is asked for. */
    private val askSteps = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        checks++
        lifecycleScope.launch { runCatching { AfterdropWidget.refreshAll(this@MainActivity) } }
    }

    /** Counts returns to the app, so permission states are read again after a visit to Settings. */
    private var checks by mutableIntStateOf(0)
    private var granted: List<Boolean> = emptyList()

    override fun onResume() {
        super.onResume()
        checks++
        // Access turned on in Settings shows on the home screen at once.
        val now = listOf(NowPlaying.allowed(this), Steps.granted(this), Steps.backgroundGranted(this))
        if (granted.isNotEmpty() && now != granted) lifecycleScope.launch { runCatching { AfterdropWidget.refreshAll(this@MainActivity) } }
        granted = now
    }

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
        val uses = spec.sources.values.map { it.use }.toSet()
        return Entry(spec.name, spec.about, json, missing, mine, spec.cols, spec.rows, hosts, "weather" in uses || "air" in uses, "calendar" in uses, uses)
    }

    private fun entries(): List<Entry> {
        val bundled = (assets.list("") ?: emptyArray()).filter { it.endsWith(".json") }.sorted().mapNotNull { file ->
            runCatching { assets.open(file).bufferedReader().use { it.readText() } }.getOrNull()?.let { entry(it, false) }
        }
        // Newest first, so a widget just created is at the top where the message says it is.
        val mine = Store(this).userSpecs().values.reversed().mapNotNull { entry(it, true) }
        return mine + bundled.sortedBy { it.missing.size }
    }

    /** @param stack add the spec as another page of the widget being configured instead of replacing it. */
    private fun choose(e: Entry, stack: Boolean = false, say: (String) -> Unit) {
        val store = Store(this)
        if (bindId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            if (stack) {
                if (!store.addPage(bindId, e.json)) {
                    say("A stack holds at most ${Store.MAX_PAGES} widgets. Remove one first.")
                    return
                }
            } else store.bind(bindId, e.json)
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

    // Buttons wrap to a new line instead of squeezing ("Dele/te") on narrow screens or large fonts.
    @OptIn(ExperimentalLayoutApi::class)
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
        var hosts by remember { mutableStateOf(Store(this@MainActivity).approvedHosts()) }
        var stackNames by remember { mutableStateOf(Store(this@MainActivity).pages(bindId).map { Store(this@MainActivity).nameOf(it) }) }
        var calendarOk by remember { mutableStateOf(Sources.canReadCalendar(this@MainActivity)) }
        onCalendarAnswer = { calendarOk = it }
        val stepsOk = remember(checks) { Steps.granted(this@MainActivity) }
        val stepsBackgroundOk = remember(checks) { Steps.backgroundGranted(this@MainActivity) }
        val mediaOk = remember(checks) { NowPlaying.allowed(this@MainActivity) }
        // Back from the editor or from Settings: show what changed.
        LaunchedEffect(checks) { list = entries() }
        var previewing by remember { mutableStateOf<Entry?>(null) }
        previewing?.let { e ->
            PreviewDialog(
                e, onDismiss = { previewing = null },
                onAdd = { previewing = null; choose(e) { report = listOf(it) } },
                onEdit = { previewing = null; startActivity(EditorActivity.intent(this@MainActivity, e.json, e.mine)) },
            )
        }
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
                    TextButton(onClick = { startActivity(Intent(this@MainActivity, PrivacyActivity::class.java)) }) { Text("Privacy: what Afterdrop reads") }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Describe a widget", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = ask, onValueChange = { ask = it.take(500) },
                            modifier = Modifier.fillMaxWidth(), maxLines = 4,
                            label = { Text("For example: countdown to 24 December") },
                        )
                        FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
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
                                        runCatching {
                                            val gemini = Gemini(GeminiTransport, key.trim(), Store(this@MainActivity).model)
                                            Generator.generate(wanted, gemini, LocalDate.now()).also { Store(this@MainActivity).model = gemini.modelName }
                                        }
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
                if (stackNames.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("This widget shows", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (stackNames.size > 1) "It is a stack: tap the dots under it on the home screen to flip to the next one."
                            else "\"Use this\" replaces it. \"Add to stack\" keeps it and adds another you can flip to by tapping the dots.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        stackNames.forEachIndexed { i, name ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}. $name", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                if (stackNames.size > 1) TextButton(onClick = {
                                    Store(this@MainActivity).removePage(bindId, i)
                                    stackNames = Store(this@MainActivity).pages(bindId).map { Store(this@MainActivity).nameOf(it) }
                                    lifecycleScope.launch { runCatching { AfterdropWidget.refreshAll(this@MainActivity) } }
                                }) { Text("Remove") }
                            }
                        }
                    }
                }
                items(list) { e ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // The widget as it will look, drawn by the widget's own code. Tap for a closer look.
                            Box(
                                Modifier.fillMaxWidth().background(WALLPAPER, RoundedCornerShape(16.dp)).clickable { previewing = e }.padding(12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                WidgetPreview(e.json, e.cols, e.rows, limitHeight = 170.dp)
                            }
                            Text(e.name, style = MaterialTheme.typography.titleMedium)
                            if (e.about.isNotEmpty()) Text(e.about, style = MaterialTheme.typography.bodyMedium)
                            if (e.hosts.isNotEmpty()) {
                                Text("Gets data from: ${e.hosts.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                            }
                            for (host in e.hosts.filter { it !in Sources.BUILT_IN_HOSTS && it !in hosts }) {
                                TextButton(onClick = {
                                    Store(this@MainActivity).approveHost(host)
                                    hosts = Store(this@MainActivity).approvedHosts()
                                    RefreshWorker.now(this@MainActivity, force = true)
                                }) { Text("Allow $host") }
                            }
                            if (e.needsCalendar && !calendarOk) {
                                Text("Your calendar is read on the phone and never sent anywhere.", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { askCalendar.launch(android.Manifest.permission.READ_CALENDAR) }) { Text("Allow calendar access") }
                            }
                            if ("health" in e.uses) {
                                when {
                                    !Steps.available(this@MainActivity) ->
                                        Text("Health Connect is not available on this phone, so steps cannot be shown.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    !stepsOk -> {
                                        Text("Steps come from Health Connect on this phone and are never sent anywhere.", style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = { askSteps.launch(Steps.PERMISSIONS) }) { Text("Allow step access") }
                                    }
                                    !stepsBackgroundOk -> {
                                        Text("Also allow \"Access data in the background\" for Afterdrop in Health Connect, so the widget keeps updating while Afterdrop is closed.", style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = { askSteps.launch(Steps.PERMISSIONS) }) { Text("Allow background access") }
                                    }
                                }
                            }
                            if ("media" in e.uses && !mediaOk) {
                                Text(
                                    "To show what is playing, turn on notification access for Afterdrop. It only asks Android which song is playing; your notifications are not read or kept.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = { runCatching { startActivity(NowPlaying.settingsIntent(this@MainActivity)) } }) { Text("Allow notification access") }
                                Text(
                                    "If Android says \"Restricted setting\": open App info, tap \u22EE at the top right, choose \"Allow restricted settings\", then try again.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = { runCatching { startActivity(NowPlaying.appInfoIntent(this@MainActivity)) } }) { Text("Open App info") }
                            }
                            if ("ai" in e.uses && key.isBlank()) {
                                Text("Writes its text with Gemini: add your Gemini API key above.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            if ("notes" in e.uses) {
                                Text("Tap the widget to write. You can also share text from any app to Afterdrop.", style = MaterialTheme.typography.bodySmall)
                            }
                            if (e.needsPlace && place == null) {
                                Text("Set your city below to see weather and air quality.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            if (e.missing.isNotEmpty()) {
                                Text(
                                    "Shows dashes for now: ${e.missing.joinToString(", ")} data is not in this version.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                                )
                            }
                            FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { choose(e) { report = listOf(it) } }) {
                                    Text(if (bindId != AppWidgetManager.INVALID_APPWIDGET_ID) "Use this" else "Add to home screen")
                                }
                                if (stackNames.isNotEmpty()) {
                                    TextButton(onClick = { choose(e, stack = true) { askReport = listOf(it) } }) { Text("Add to stack") }
                                }
                                TextButton(onClick = { previewing = e }) { Text("Preview") }
                                TextButton(onClick = { startActivity(EditorActivity.intent(this@MainActivity, e.json, e.mine)) }) { Text("Edit design") }
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
                                                        runCatching {
                                                            val gemini = Gemini(GeminiTransport, key.trim(), Store(this@MainActivity).model)
                                                            Generator.edit(spec, wanted, gemini).also { Store(this@MainActivity).model = gemini.modelName }
                                                        }
                                                            .getOrElse { Outcome.Failed("Something went wrong: ${it.message}") }
                                                    }
                                                    busy = false
                                                    askReport = when (out) {
                                                        is Outcome.Ok -> {
                                                            val store = Store(this@MainActivity)
                                                            val json = Json.write(out.spec.json)
                                                            if (out.spec.name != e.name) store.deleteUserSpec(e.name)
                                                            store.saveUserSpec(out.spec.name, json)
                                                            val placed = store.rebind(e.name, json)
                                                            if (placed > 0) runCatching { AfterdropWidget.refreshAll(this@MainActivity) }
                                                            list = entries()
                                                            ask = ""
                                                            listOf("Changed \"${out.spec.name}\"." + if (placed > 0) " $placed widget(s) on the home screen were updated." else "")
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

    /**
     * A closer look before adding: the widget at the size you pick, light or dark, with live data
     * fetched for it just now (only from the services the widget itself uses).
     */
    @OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    private fun PreviewDialog(e: Entry, onDismiss: () -> Unit, onAdd: () -> Unit, onEdit: () -> Unit) {
        var size by remember { mutableStateOf(e.cols to e.rows) }
        var dark by remember { mutableStateOf<Boolean?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        LaunchedEffect(e.json) {
            val spec = (SpecReader.read(e.json) as? SpecResult.Ok)?.spec ?: return@LaunchedEffect
            val fetched = withContext(Dispatchers.IO) {
                runCatching { Sources.refresh(this@MainActivity, listOf(spec), HttpsFetcher) + Images.prefetch(this@MainActivity, listOf(spec)) }.getOrDefault(0)
            }
            if (fetched > 0) refresh++
        }
        val sizes = (listOf(e.cols to e.rows) + listOf(2 to 1, 2 to 2, 4 to 1, 4 to 2, 4 to 3)).distinct()
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(e.name, style = MaterialTheme.typography.titleLarge)
                    Box(
                        Modifier.fillMaxWidth().background(WALLPAPER, RoundedCornerShape(20.dp)).padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        WidgetPreview(e.json, size.first, size.second, dark = dark, limitHeight = 400.dp, refresh = refresh)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (s in sizes) FilterChip(size == s, onClick = { size = s }, label = { Text("${s.first} × ${s.second}") })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(dark == null, onClick = { dark = null }, label = { Text("Phone") })
                        FilterChip(dark == false, onClick = { dark = false }, label = { Text("Light") })
                        FilterChip(dark == true, onClick = { dark = true }, label = { Text("Dark") })
                    }
                    Text("Drawn with your real data. On the home screen you can resize it as well.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onAdd) { Text(if (bindId != AppWidgetManager.INVALID_APPWIDGET_ID) "Use this" else "Add to home screen") }
                        TextButton(onClick = onEdit) { Text("Edit design") }
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                }
            }
        }
    }

    companion object {
        /** Behind previews, a backdrop like a home-screen wallpaper. */
        val WALLPAPER = Brush.linearGradient(listOf(Color(0xFF3F6187), Color(0xFF233447)))

        /** Opens the app to choose or change what one placed widget shows. */
        fun bindIntent(context: Context, appWidgetId: Int): Intent =
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse("afterdrop://widget/$appWidgetId"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
