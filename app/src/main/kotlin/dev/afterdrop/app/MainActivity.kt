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
import androidx.compose.ui.res.stringResource
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
import java.util.Locale

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

    /** The phone's language in English words ("German"), for asking a model to write in it. */
    private fun language(): String = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }

    private fun entry(json: String, mine: Boolean): Entry? {
        val spec = (SpecReader.read(json) as? SpecResult.Ok)?.spec ?: return null
        val missing = spec.sources.values.map { it.use }.filter { it !in Sources.AVAILABLE }.distinct()
        val hosts = spec.sources.values.mapNotNull { NetworkSources.host(it) }.distinct()
        val uses = spec.sources.values.map { it.use }.toSet()
        return Entry(spec.name, spec.about, json, missing, mine, spec.cols, spec.rows, hosts, "weather" in uses || "air" in uses, "calendar" in uses, uses)
    }

    private fun entries(): List<Entry> {
        val bundled = (assets.list("") ?: emptyArray()).filter { it.endsWith(".json") }.sorted().mapNotNull { file ->
            runCatching { assets.open(file).bufferedReader().use { it.readText() } }.getOrNull()?.let { entry(Languages.localize(this, it), false) }
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
                    say(getString(R.string.stack_full, Store.MAX_PAGES))
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
            say(getString(R.string.launcher_cannot))
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
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        stringResource(if (bindId != AppWidgetManager.INVALID_APPWIDGET_ID) R.string.main_intro_bind else R.string.main_intro),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { startActivity(Intent(this@MainActivity, PrivacyActivity::class.java)) }) { Text(stringResource(R.string.main_privacy)) }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.describe_title), style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = ask, onValueChange = { ask = it.take(500) },
                            modifier = Modifier.fillMaxWidth(), maxLines = 4,
                            label = { Text(stringResource(R.string.describe_hint)) },
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
                                            listOf(getString(R.string.created_first, r.spec.name))
                                        }
                                        is SpecResult.Rejected -> r.problems.take(4).map { it.message }
                                    }
                                }
                            }) { Text(stringResource(R.string.create)) }
                            TextButton(enabled = ask.isNotBlank(), onClick = {
                                val clipboard = getSystemService(ClipboardManager::class.java)
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Afterdrop prompt", ChatPrompt.build(ask, language())))
                                askReport = listOf(getString(R.string.prompt_copied))
                            }) { Text(stringResource(R.string.copy_prompt)) }
                        }
                        Button(enabled = ask.isNotBlank() && !busy, onClick = {
                            if (key.isBlank()) {
                                askReport = listOf(getString(R.string.need_key_first))
                            } else {
                                busy = true
                                askReport = listOf(getString(R.string.asking_model))
                                val wanted = ask
                                lifecycleScope.launch {
                                    val out = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val gemini = Gemini(GeminiTransport, key.trim(), Store(this@MainActivity).model)
                                            Generator.generate(wanted, gemini, LocalDate.now(), language()).also { Store(this@MainActivity).model = gemini.modelName }
                                        }
                                            .getOrElse { Outcome.Failed(getString(R.string.went_wrong, it.message)) }
                                    }
                                    busy = false
                                    askReport = when (out) {
                                        is Outcome.Ok -> {
                                            Store(this@MainActivity).saveUserSpec(out.spec.name, Json.write(out.spec.json))
                                            list = entries()
                                            ask = ""
                                            listOfNotNull(getString(R.string.created_first, out.spec.name), out.note)
                                        }
                                        is Outcome.Failed -> listOf(out.reason)
                                    }
                                }
                            }
                        }) { Text(stringResource(if (busy) R.string.working else R.string.create_ai)) }
                        for (line in askReport) Text(line, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = key, onValueChange = { key = it.take(200); Store(this@MainActivity).apiKey = key },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text(stringResource(R.string.key_label)) },
                        )
                        Text(
                            stringResource(R.string.key_note),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (stackNames.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.stack_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(if (stackNames.size > 1) R.string.stack_many else R.string.stack_one),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        stackNames.forEachIndexed { i, name ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}. $name", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                if (stackNames.size > 1) TextButton(onClick = {
                                    Store(this@MainActivity).removePage(bindId, i)
                                    stackNames = Store(this@MainActivity).pages(bindId).map { Store(this@MainActivity).nameOf(it) }
                                    lifecycleScope.launch { runCatching { AfterdropWidget.refreshAll(this@MainActivity) } }
                                }) { Text(stringResource(R.string.remove)) }
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
                                Text(stringResource(R.string.gets_data, e.hosts.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
                            }
                            for (host in e.hosts.filter { it !in Sources.BUILT_IN_HOSTS && it !in hosts }) {
                                TextButton(onClick = {
                                    Store(this@MainActivity).approveHost(host)
                                    hosts = Store(this@MainActivity).approvedHosts()
                                    RefreshWorker.now(this@MainActivity, force = true)
                                }) { Text(stringResource(R.string.allow_host, host)) }
                            }
                            if (e.needsCalendar && !calendarOk) {
                                Text(stringResource(R.string.calendar_note), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { askCalendar.launch(android.Manifest.permission.READ_CALENDAR) }) { Text(stringResource(R.string.allow_calendar)) }
                            }
                            if ("health" in e.uses) {
                                when {
                                    !Steps.available(this@MainActivity) ->
                                        Text(stringResource(R.string.hc_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    !stepsOk -> {
                                        Text(stringResource(R.string.steps_note), style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = { askSteps.launch(Steps.PERMISSIONS) }) { Text(stringResource(R.string.allow_steps)) }
                                    }
                                    !stepsBackgroundOk -> {
                                        Text(stringResource(R.string.steps_background), style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = { askSteps.launch(Steps.PERMISSIONS) }) { Text(stringResource(R.string.allow_background)) }
                                    }
                                }
                            }
                            if ("media" in e.uses && !mediaOk) {
                                Text(
                                    stringResource(R.string.media_note),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = { runCatching { startActivity(NowPlaying.settingsIntent(this@MainActivity)) } }) { Text(stringResource(R.string.allow_notifications)) }
                                Text(
                                    stringResource(R.string.restricted_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = { runCatching { startActivity(NowPlaying.appInfoIntent(this@MainActivity)) } }) { Text(stringResource(R.string.open_app_info)) }
                            }
                            if ("ai" in e.uses && key.isBlank()) {
                                Text(stringResource(R.string.ai_needs_key), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            if ("notes" in e.uses) {
                                Text(stringResource(R.string.notes_hint), style = MaterialTheme.typography.bodySmall)
                            }
                            if (e.needsPlace && place == null) {
                                Text(stringResource(R.string.needs_place), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            if (e.missing.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.missing_dashes, e.missing.joinToString(", ")),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                                )
                            }
                            FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { choose(e) { report = listOf(it) } }) {
                                    Text(stringResource(if (bindId != AppWidgetManager.INVALID_APPWIDGET_ID) R.string.use_this else R.string.add_home))
                                }
                                if (stackNames.isNotEmpty()) {
                                    TextButton(onClick = { choose(e, stack = true) { askReport = listOf(it) } }) { Text(stringResource(R.string.add_stack)) }
                                }
                                TextButton(onClick = { previewing = e }) { Text(stringResource(R.string.preview)) }
                                TextButton(onClick = { startActivity(EditorActivity.intent(this@MainActivity, e.json, e.mine)) }) { Text(stringResource(R.string.edit_design)) }
                                if (e.mine) {
                                    TextButton(enabled = !busy, onClick = {
                                        val spec = (SpecReader.read(e.json) as? SpecResult.Ok)?.spec
                                        when {
                                            ask.isBlank() -> askReport = listOf(getString(R.string.change_hint))
                                            key.isBlank() -> askReport = listOf(getString(R.string.change_needs_key))
                                            spec != null -> {
                                                busy = true
                                                askReport = listOf(getString(R.string.asking_change, e.name))
                                                val wanted = ask
                                                lifecycleScope.launch {
                                                    val out = withContext(Dispatchers.IO) {
                                                        runCatching {
                                                            val gemini = Gemini(GeminiTransport, key.trim(), Store(this@MainActivity).model)
                                                            Generator.edit(spec, wanted, gemini, language()).also { Store(this@MainActivity).model = gemini.modelName }
                                                        }
                                                            .getOrElse { Outcome.Failed(getString(R.string.went_wrong, it.message)) }
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
                                                            listOf(if (placed > 0) getString(R.string.changed_placed, out.spec.name, placed) else getString(R.string.changed, out.spec.name))
                                                        }
                                                        is Outcome.Failed -> listOf(out.reason, getString(R.string.kept_as_it_was))
                                                    }
                                                }
                                            }
                                        }
                                    }) { Text(stringResource(R.string.change_ai)) }
                                    TextButton(onClick = {
                                        Store(this@MainActivity).deleteUserSpec(e.name)
                                        list = entries()
                                    }) { Text(stringResource(R.string.delete)) }
                                }
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.place_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            place?.let { stringResource(R.string.place_set, it) } ?: stringResource(R.string.place_none),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = city, onValueChange = { city = it.take(80) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.city)) },
                        )
                        Button(enabled = city.isNotBlank(), onClick = {
                            val wanted = city
                            cityReport = getString(R.string.looking_up)
                            lifecycleScope.launch {
                                val found = withContext(Dispatchers.IO) {
                                    HttpsFetcher.get(NetworkSources.geocodeUrl(wanted))?.let { NetworkSources.parseGeocode(it) }
                                }
                                if (found == null) cityReport = getString(R.string.city_not_found)
                                else {
                                    Store(this@MainActivity).home = found
                                    place = found.name
                                    city = ""
                                    cityReport = getString(R.string.city_sent)
                                    RefreshWorker.now(this@MainActivity, force = true)
                                }
                            }
                        }) { Text(stringResource(R.string.set_place)) }
                        if (cityReport.isNotEmpty()) Text(cityReport, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Text(stringResource(R.string.paste_title), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it.take(SpecReader.MAX_BYTES) },
                        modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 12,
                        label = { Text(stringResource(R.string.paste_label)) },
                    )
                    Button(onClick = {
                        when (val r = SpecReader.read(draft, lenient = true)) {
                            is SpecResult.Ok -> {
                                Store(this@MainActivity).saveUserSpec(r.spec.name, Json.write(r.spec.json))
                                list = entries()
                                draft = ""
                                report = listOf(getString(R.string.saved_top, r.spec.name))
                            }
                            is SpecResult.Rejected -> report = r.problems.take(8).map { "${it.path}: ${it.message}" }
                        }
                    }, enabled = draft.isNotBlank()) { Text(stringResource(R.string.check_save)) }
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
                        for (s in sizes) FilterChip(size == s, onClick = { size = s }, label = { Text(stringResource(R.string.size_chip, s.first, s.second)) })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(dark == null, onClick = { dark = null }, label = { Text(stringResource(R.string.phone)) })
                        FilterChip(dark == false, onClick = { dark = false }, label = { Text(stringResource(R.string.light)) })
                        FilterChip(dark == true, onClick = { dark = true }, label = { Text(stringResource(R.string.dark)) })
                    }
                    Text(stringResource(R.string.preview_note), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onAdd) { Text(stringResource(if (bindId != AppWidgetManager.INVALID_APPWIDGET_ID) R.string.use_this else R.string.add_home)) }
                        TextButton(onClick = onEdit) { Text(stringResource(R.string.edit_design)) }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
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
