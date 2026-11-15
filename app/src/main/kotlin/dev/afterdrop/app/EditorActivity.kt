package dev.afterdrop.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.afterdrop.app.widget.AfterdropWidget
import dev.afterdrop.app.widget.iconRes
import dev.afterdrop.composer.SpecEdit
import dev.afterdrop.spec.IconNames
import dev.afterdrop.spec.SpecReader
import dev.afterdrop.spec.SpecResult
import kotlinx.coroutines.launch

/**
 * The visual editor: the widget drawn live at the top, its parts listed below. Pick a part to change
 * its text, size, colour and spacing, move it, copy it, remove it, or add a new part. The "Look" tab
 * changes the widget's name, colours and starting size. Every change is checked like any widget, so
 * the editor cannot save something broken; undo goes back one step at a time.
 */
class EditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val start = intent.getStringExtra(JSON)?.takeIf { SpecReader.read(it) is SpecResult.Ok }
        if (start == null) {
            finish()
            return
        }
        val originalName = Store(this).nameOf(start)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)) {
                Editor(start, originalName)
            }
        }
    }

    private fun save(json: String, originalName: String) {
        val store = Store(this)
        val name = store.nameOf(json)
        store.saveUserSpec(name, json)
        // Renaming one of your own widgets replaces it; a gallery widget stays in the gallery as it was.
        if (name != originalName && intent.getBooleanExtra(MINE, false)) store.deleteUserSpec(originalName)
        // Widgets already on the home screen that show this one follow the edit.
        store.rebind(originalName, json)
        lifecycleScope.launch {
            runCatching { AfterdropWidget.refreshAll(this@EditorActivity) }
            setResult(RESULT_OK)
            finish()
        }
    }

    @OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
    @Composable
    private fun Editor(start: String, originalName: String) {
        var json by remember { mutableStateOf(start) }
        var undo by remember { mutableStateOf(listOf<String>()) }
        // Typing in one field is one undo step, not one per letter.
        var lastField by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf<List<String>?>(null) }
        var tab by remember { mutableStateOf("parts") }
        var message by remember { mutableStateOf("") }
        var dark by remember { mutableStateOf<Boolean?>(null) }
        val doc = remember(json) { SpecEdit.parse(json) }
        val spec = remember(json) { (SpecReader.read(json) as? SpecResult.Ok)?.spec }
        val parts = remember(json) { SpecEdit.parts(doc) }

        /** Takes an edit only if the result is still a valid widget. */
        fun commit(next: Map<String, Any?>?, field: String = "", why: String = "That cannot be changed here."): Boolean {
            if (next == null) {
                message = why
                return false
            }
            val text = SpecEdit.write(next)
            return when (val r = SpecReader.read(text)) {
                is SpecResult.Ok -> {
                    if (field.isEmpty() || field != lastField) undo = (undo + json).takeLast(50)
                    lastField = field
                    json = text
                    message = ""
                    true
                }
                is SpecResult.Rejected -> {
                    message = "Not saved yet: " + (r.problems.firstOrNull()?.message ?: "the widget would not be valid")
                    false
                }
            }
        }

        Scaffold(Modifier.fillMaxSize().imePadding()) { inner ->
            Column(Modifier.padding(top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding()).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { finish() }) { Text("Cancel") }
                    Text("Edit design", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(enabled = undo.isNotEmpty(), onClick = {
                        json = undo.last()
                        undo = undo.dropLast(1)
                        lastField = ""
                        selected = selected?.takeIf { SpecEdit.node(SpecEdit.parse(json), it) != null }
                    }) { Text("Undo") }
                    Button(onClick = { save(json, originalName) }) { Text("Save") }
                }
                // The widget itself, on a backdrop like a wallpaper.
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(Color(0xFF35506B), RoundedCornerShape(20.dp)).padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (spec != null) WidgetPreview(json, spec.cols, spec.rows, dark = dark, limitHeight = 220.dp)
                }
                FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(tab == "parts", onClick = { tab = "parts" }, label = { Text("Parts") })
                    FilterChip(tab == "look", onClick = { tab = "look" }, label = { Text("Look") })
                    Spacer(Modifier.width(12.dp))
                    FilterChip(dark == null, onClick = { dark = null }, label = { Text("Phone") })
                    FilterChip(dark == false, onClick = { dark = false }, label = { Text("Light") })
                    FilterChip(dark == true, onClick = { dark = true }, label = { Text("Dark") })
                }
                if (message.isNotEmpty()) {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (tab == "look") item { Look(doc, ::commit) }
                    else items(parts, key = { it.path.joinToString("/") + "|" + it.type }) { part ->
                        val isSelected = part.path == selected
                        Column(
                            Modifier.fillMaxWidth()
                                .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable { selected = if (isSelected) null else part.path; lastField = "" }
                                .padding(start = (8 + part.depth * 16).dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        ) {
                            Text(
                                part.label.ifBlank { part.type },
                                style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                                modifier = Modifier.semantics { contentDescription = "Part ${part.label}" },
                            )
                            val kind = TYPE_NAMES[part.type] ?: part.type
                            if (kind != part.label) Text(kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (isSelected) {
                                val node = SpecEdit.node(doc, part.path)
                                if (node != null) {
                                    Spacer(Modifier.height(8.dp))
                                    Actions(doc, part, ::commit, onSelect = { selected = it })
                                    Properties(doc, part.path, node, ::commit)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Actions(doc: Map<String, Any?>, part: SpecEdit.Part, commit: (Map<String, Any?>?, String, String) -> Boolean, onSelect: (List<String>?) -> Unit) {
        var adding by remember { mutableStateOf(false) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { if (commit(SpecEdit.move(doc, part.path, -1), "", "It is already first.")) onSelect(SpecEdit.moved(part.path, -1)) }) { Text("Up") }
            OutlinedButton(onClick = { if (commit(SpecEdit.move(doc, part.path, 1), "", "It is already last.")) onSelect(SpecEdit.moved(part.path, 1)) }) { Text("Down") }
            OutlinedButton(onClick = { commit(SpecEdit.duplicate(doc, part.path), "", "This part cannot be copied.") }) { Text("Copy") }
            OutlinedButton(onClick = { if (commit(SpecEdit.remove(doc, part.path), "", "This part holds the widget together and cannot be removed.")) onSelect(null) }) { Text("Remove") }
            Box {
                Button(onClick = { adding = true }) { Text(if (part.container) "Add inside" else "Add after") }
                DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                    for ((name, make) in SpecEdit.NEW_PARTS) {
                        DropdownMenuItem(text = { Text(name) }, onClick = {
                            adding = false
                            val added = SpecEdit.add(doc, part.path, make())
                            if (commit(added?.first, "", "Nothing can be added here.")) onSelect(added?.second)
                        })
                    }
                }
            }
        }
    }

    /** The settings that make sense for this kind of part. */
    @OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
    @Composable
    private fun Properties(doc: Map<String, Any?>, path: List<String>, node: Map<String, Any?>, commit: (Map<String, Any?>?, String, String) -> Boolean) {
        val type = node["t"] as? String ?: return
        val key = path.joinToString("/")
        fun set(prop: String, value: Any?) = commit(SpecEdit.set(doc, path, prop, value), "", "That cannot be changed here.")
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val textKey = when (type) {
                "text" -> "text"
                "button", "toggle", "check", "stepper" -> "label"
                else -> null
            }
            if (textKey != null) TextProp(key + textKey, (node[textKey] as? String) ?: "", if (textKey == "text") "Text ({ } shows live data)" else "Label") {
                commit(SpecEdit.set(doc, path, textKey, it), key + textKey, "")
            }
            if (type in setOf("text", "clock", "timer")) {
                Choice("Size", STYLES, node["style"] as? String ?: "body") { set("style", it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bold", Modifier.weight(1f))
                    Switch(checked = node["bold"] == true, onCheckedChange = { set("bold", if (it) true else null) })
                }
            }
            if (type == "clock") Choice("Shows", CLOCK_FORMATS, node["format"] as? String ?: "HH:mm") { set("format", it) }
            if (type == "text") Slide("Lines", (node["lines"] as? Number)?.toInt() ?: 2, 1, 6) { set("lines", it) }
            if (type in setOf("text", "clock", "timer", "icon", "progress", "chart", "divider")) {
                Swatches("Colour", INKS, node["color"] as? String ?: if (type in setOf("progress", "chart", "icon")) "primary" else "text") { set("color", it) }
            }
            if (type == "icon") {
                Slide("Icon size", (node["iconSize"] as? Number)?.toInt() ?: 24, 12, 64) { set("iconSize", it) }
                IconPicker(node["icon"] as? String ?: "star") { set("icon", it) }
            }
            if (type == "progress") Choice("Shape", mapOf("bar" to "Bar", "ring" to "Ring"), node["shape"] as? String ?: "bar") { set("shape", it) }
            if (type == "button") Choice("Style", mapOf("filled" to "Filled", "tonal" to "Tonal", "plain" to "Plain"), node["kind"] as? String ?: "filled") { set("kind", it) }
            if (type == "image") Choice("Fit", mapOf("cover" to "Fill", "contain" to "Whole picture"), node["fit"] as? String ?: "cover") { set("fit", it) }
            if (type in SpecEdit.CONTAINERS || type == "list") {
                Swatches("Fill", FILLS, node["bg"] as? String ?: "none") { set("bg", if (it == "none") null else it) }
                Slide("Rounded corners", (node["radius"] as? Number)?.toInt() ?: 0, 0, 28) { set("radius", if (it == 0) null else it) }
                Slide("Space inside", (node["pad"] as? Number)?.toInt() ?: 0, 0, 24) { set("pad", if (it == 0) null else it) }
            }
            if (type in setOf("col", "row", "list")) Slide("Space between", (node["gap"] as? Number)?.toInt() ?: 0, 0, 24) { set("gap", if (it == 0) null else it) }
            if (type in setOf("col", "row", "box")) {
                Choice(if (type == "row") "Across" else "Down", ALIGN, node["align"] as? String ?: "start") { set("align", if (it == "start") null else it) }
                Choice(if (type == "row") "Up and down" else "Side to side", CROSS, node["cross"] as? String ?: "start") { set("cross", if (it == "start") null else it) }
            }
            // For every part: when it shows, and how much room it takes.
            Choice("Show when the widget is", FROM, node["from"] as? String ?: "") { set("from", it.ifEmpty { null }) }
            Choice("Width", WIDTHS, (node["w"] as? String) ?: "") { set("w", it.ifEmpty { null }) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Take the spare room", Modifier.weight(1f))
                Switch(checked = ((node["grow"] as? Number)?.toInt() ?: 0) > 0, onCheckedChange = { set("grow", if (it) 1 else null) })
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
    @Composable
    private fun Look(doc: Map<String, Any?>, commit: (Map<String, Any?>?, String, String) -> Boolean) {
        @Suppress("UNCHECKED_CAST")
        val theme = doc["theme"] as? Map<String, Any?> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val size = doc["size"] as? Map<String, Any?> ?: emptyMap()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextProp("name", doc["name"] as? String ?: "", "Name") { commit(SpecEdit.setTop(doc, "name", it.take(40)), "name", "") }
            Text("Colours", style = MaterialTheme.typography.titleSmall)
            val current = if (theme["palette"] == "custom") PALETTES.entries.firstOrNull { it.value == theme["colors"] }?.key ?: "Your own" else "Phone"
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(current == "Phone", onClick = {
                    commit(SpecEdit.setTop(SpecEdit.setTop(doc, "theme.palette", null), "theme.colors", null), "", "")
                }, label = { Text("Phone colours") })
                for ((name, colors) in PALETTES) {
                    FilterChip(current == name, onClick = {
                        commit(SpecEdit.setTop(SpecEdit.setTop(doc, "theme.palette", "custom"), "theme.colors", colors), "", "")
                    }, label = { Text(name) }, leadingIcon = {
                        Box(Modifier.size(16.dp).background(Color(android.graphics.Color.parseColor(colors.getValue("bg"))), CircleShape).border(1.dp, Color.Gray, CircleShape))
                    })
                }
            }
            Text("Phone colours follow your wallpaper, like Google's own widgets.", style = MaterialTheme.typography.bodySmall)
            Text("Starting size", style = MaterialTheme.typography.titleSmall)
            Slide("Columns", (size["cols"] as? Number)?.toInt() ?: 2, 1, 5) { commit(SpecEdit.setTop(doc, "size", linkedMapOf("cols" to it, "rows" to ((size["rows"] as? Number)?.toInt() ?: 2))), "", "") }
            Slide("Rows", (size["rows"] as? Number)?.toInt() ?: 2, 1, 5) { commit(SpecEdit.setTop(doc, "size", linkedMapOf("cols" to ((size["cols"] as? Number)?.toInt() ?: 2), "rows" to it)), "", "") }
            Text("You can still resize it on the home screen; it adapts.", style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun TextProp(key: String, value: String, label: String, onChange: (String) -> Boolean) {
        // The field keeps what is typed even while it is not yet a valid formula; the widget follows once it is.
        var text by remember(key) { mutableStateOf(value) }
        var ok by remember(key) { mutableStateOf(true) }
        OutlinedTextField(
            value = text, onValueChange = { text = it.take(300); ok = onChange(text) },
            label = { Text(label) }, isError = !ok, modifier = Modifier.fillMaxWidth(),
            supportingText = { if (!ok) Text("Not applied yet: check the { } parts") },
        )
    }

    @OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
    @Composable
    private fun Choice(title: String, options: Map<String, String>, current: String, pick: (String) -> Unit) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((value, name) in options) FilterChip(current == value, onClick = { pick(value) }, label = { Text(name) })
        }
    }

    @OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
    @Composable
    private fun Swatches(title: String, options: Map<String, Pair<String, @Composable () -> Color>>, current: String, pick: (String) -> Unit) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((value, entry) in options) {
                val (name, color) = entry
                val c = color()
                FilterChip(current == value, onClick = { pick(value) }, label = { Text(name) }, leadingIcon = {
                    Box(Modifier.size(14.dp).background(c, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                })
            }
        }
    }

    @Composable
    private fun Slide(title: String, value: Int, min: Int, max: Int, change: (Int) -> Unit) {
        var v by remember(title, value) { mutableStateOf(value.toFloat()) }
        Text("$title: ${v.toInt()}", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = v, onValueChange = { v = it }, valueRange = min.toFloat()..max.toFloat(), steps = Math.max(0, max - min - 1),
            onValueChangeFinished = { if (v.toInt() != value) change(v.toInt()) },
        )
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun IconPicker(current: String, pick: (String) -> Unit) {
        var query by remember { mutableStateOf("") }
        OutlinedTextField(value = query, onValueChange = { query = it.take(30) }, label = { Text("Find an icon (now: $current)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        val shown = IconNames.ALL.filter { query.isBlank() || query.trim().lowercase() in it }.take(48)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (name in shown) {
                val res = iconRes(name) ?: continue
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (name == current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(44.dp).clickable { pick(name) }.semantics { contentDescription = "Icon $name" },
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(res), contentDescription = null, modifier = Modifier.size(24.dp)) }
                }
            }
        }
    }

    companion object {
        const val JSON = "json"
        const val MINE = "mine"

        /** @param mine the widget is one of the person's own, not a gallery one. */
        fun intent(context: Context, json: String, mine: Boolean = false): Intent =
            Intent(context, EditorActivity::class.java).putExtra(JSON, json).putExtra(MINE, mine)

        private val TYPE_NAMES = mapOf(
            "col" to "Column", "row" to "Row", "box" to "Layer", "list" to "List", "text" to "Text", "icon" to "Icon",
            "image" to "Picture", "progress" to "Progress", "chart" to "Chart", "clock" to "Clock", "timer" to "Timer",
            "button" to "Button", "toggle" to "Switch", "stepper" to "Counter", "check" to "Tick box", "spacer" to "Space", "divider" to "Line",
        )
        private val STYLES = linkedMapOf("caption" to "Small", "label" to "Label", "body" to "Body", "title" to "Title", "headline" to "Large", "display" to "Huge")
        private val CLOCK_FORMATS = linkedMapOf("HH:mm" to "14:05", "h:mm" to "2:05", "EEE d MMM" to "Mon 3 Nov", "EEEE d MMMM" to "Monday 3 November", "d.MM.yyyy" to "3.11.2026")
        private val ALIGN = linkedMapOf("start" to "Start", "center" to "Middle", "end" to "End", "between" to "Spread")
        private val CROSS = linkedMapOf("start" to "Start", "center" to "Middle", "end" to "End")
        private val FROM = linkedMapOf("" to "Any size", "s" to "Small or bigger", "m" to "Medium or bigger", "l" to "Large only")
        private val WIDTHS = linkedMapOf("" to "Its own", "fill" to "Full width")

        private val INKS: Map<String, Pair<String, @Composable () -> Color>> = linkedMapOf(
            "text" to ("Normal" to @Composable { MaterialTheme.colorScheme.onSurface }),
            "muted" to ("Quiet" to @Composable { MaterialTheme.colorScheme.onSurfaceVariant }),
            "primary" to ("Accent" to @Composable { MaterialTheme.colorScheme.primary }),
            "secondary" to ("Second" to @Composable { MaterialTheme.colorScheme.secondary }),
            "tertiary" to ("Third" to @Composable { MaterialTheme.colorScheme.tertiary }),
            "success" to ("Green" to @Composable { Color(0xFF2E7D32) }),
            "warning" to ("Amber" to @Composable { Color(0xFFB26A00) }),
            "error" to ("Red" to @Composable { MaterialTheme.colorScheme.error }),
        )
        private val FILLS: Map<String, Pair<String, @Composable () -> Color>> = linkedMapOf(
            "none" to ("None" to @Composable { Color.Transparent }),
            "surface" to ("Plain" to @Composable { MaterialTheme.colorScheme.surface }),
            "surfaceVariant" to ("Grey" to @Composable { MaterialTheme.colorScheme.surfaceVariant }),
            "primaryContainer" to ("Accent card" to @Composable { MaterialTheme.colorScheme.primaryContainer }),
            "secondaryContainer" to ("Second card" to @Composable { MaterialTheme.colorScheme.secondaryContainer }),
            "tertiaryContainer" to ("Third card" to @Composable { MaterialTheme.colorScheme.tertiaryContainer }),
            "primary" to ("Strong" to @Composable { MaterialTheme.colorScheme.primary }),
        )

        /** Ready colour sets for a widget that should not follow the wallpaper. */
        val PALETTES: Map<String, Map<String, String>> = linkedMapOf(
            "Sand" to pal("#FFF4B8", "#3A3000", "#6B5D1F", "#8A6D00", "#FFE07A", "#2A2000", "#F0E3A0"),
            "Mint" to pal("#DFF5E6", "#0F3D22", "#3E6B50", "#1E7A45", "#B6EBC9", "#08321A", "#C9E8D4"),
            "Sky" to pal("#E1EEFF", "#0E2A4F", "#45607F", "#1A63C9", "#C3DAFF", "#082451", "#CFDFF5"),
            "Rose" to pal("#FFE4E8", "#4A1020", "#7A4552", "#B3264A", "#FFC5D1", "#3E0716", "#F5D0D8"),
            "Paper" to pal("#FAF8F4", "#1F1D1A", "#6F6A62", "#3D5A80", "#DCE6F3", "#10233D", "#ECE8E0"),
            "Night" to pal("#1C1E24", "#ECEDF2", "#A3A7B3", "#9CC2FF", "#2E4A73", "#D6E4FF", "#2A2D35"),
        )

        private fun pal(bg: String, text: String, muted: String, primary: String, container: String, onContainer: String, variant: String) =
            linkedMapOf(
                "bg" to bg, "surface" to bg, "text" to text, "muted" to muted, "primary" to primary,
                "primaryContainer" to container, "onPrimaryContainer" to onContainer, "surfaceVariant" to variant,
            )
    }
}
