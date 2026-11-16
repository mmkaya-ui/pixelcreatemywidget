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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.lifecycle.lifecycleScope
import dev.afterdrop.app.widget.AfterdropWidget
import dev.afterdrop.app.widget.iconRes
import dev.afterdrop.composer.SpecEdit
import dev.afterdrop.spec.IconNames
import dev.afterdrop.spec.SpecReader
import dev.afterdrop.spec.SpecResult
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The visual editor: the widget drawn live at the top, its parts listed below. Pick a part to change
 * its text, size, colour and spacing, move it, copy it, remove it, or add a new part. Drag the handle at
 * the end of a row to move a part among the others in its group. The "Look" tab changes the widget's
 * name, colours and starting size. Every change is checked like any widget, so the editor cannot save
 * something broken; undo goes back one step at a time.
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
        val listState = rememberLazyListState()

        // Dragging a part by its handle: which part, how far the finger has moved, and the row it is over.
        var dragging by remember { mutableStateOf<List<String>?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        var dragStartCenter by remember { mutableFloatStateOf(0f) }
        var dropRow by remember { mutableIntStateOf(-1) }
        val latestParts by rememberUpdatedState(parts)
        val latestDoc by rememberUpdatedState(doc)

        /** Takes an edit only if the result is still a valid widget. */
        fun commit(next: Map<String, Any?>?, field: String = "", why: String = getString(R.string.cannot_change)): Boolean {
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
                    message = getString(R.string.not_saved, r.problems.firstOrNull()?.message ?: getString(R.string.would_not_be_valid))
                    false
                }
            }
        }

        /** The row of the list the dragged part's centre is over now, or -1. */
        fun rowUnder(key: String): Int {
            val items = listState.layoutInfo.visibleItemsInfo
            val centre = dragStartCenter + dragOffset
            return items.firstOrNull { centre >= it.offset && centre <= it.offset + it.size }?.index
                ?: if (items.isNotEmpty() && centre < items.first().offset) items.first().index else if (items.isNotEmpty()) items.last().index else -1
        }

        fun finishDrag() {
            val path = dragging
            if (path != null && dropRow >= 0) {
                val to = SpecEdit.dropIndex(latestParts, path, dropRow)
                val from = path.last().toIntOrNull()
                if (to != null && from != null && to != from && commit(SpecEdit.moveTo(latestDoc, path, to))) selected = SpecEdit.moved(path, to - from)
            }
            dragging = null
            dragOffset = 0f
            dropRow = -1
        }

        Scaffold(Modifier.fillMaxSize().imePadding()) { inner ->
            Column(Modifier.padding(top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding()).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { finish() }) { Text(stringResource(R.string.cancel)) }
                    Text(stringResource(R.string.editor_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(enabled = undo.isNotEmpty(), onClick = {
                        json = undo.last()
                        undo = undo.dropLast(1)
                        lastField = ""
                        selected = selected?.takeIf { SpecEdit.node(SpecEdit.parse(json), it) != null }
                    }) { Text(stringResource(R.string.undo)) }
                    Button(onClick = { save(json, originalName) }) { Text(stringResource(R.string.save)) }
                }
                // The widget itself, on a backdrop like a wallpaper.
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(Color(0xFF35506B), RoundedCornerShape(20.dp)).padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (spec != null) WidgetPreview(json, spec.cols, spec.rows, dark = dark, limitHeight = 220.dp)
                }
                FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(tab == "parts", onClick = { tab = "parts" }, label = { Text(stringResource(R.string.tab_parts)) })
                    FilterChip(tab == "look", onClick = { tab = "look" }, label = { Text(stringResource(R.string.tab_look)) })
                    Spacer(Modifier.width(12.dp))
                    FilterChip(dark == null, onClick = { dark = null }, label = { Text(stringResource(R.string.phone)) })
                    FilterChip(dark == false, onClick = { dark = false }, label = { Text(stringResource(R.string.light)) })
                    FilterChip(dark == true, onClick = { dark = true }, label = { Text(stringResource(R.string.dark)) })
                }
                if (message.isNotEmpty()) {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (tab == "look") item { Look(doc, ::commit) }
                    else items(parts, key = { it.path.joinToString("/") + "|" + it.type }) { part ->
                        val isSelected = part.path == selected
                        val rowKey = part.path.joinToString("/") + "|" + part.type
                        val beingDragged = dragging == part.path
                        // The place the dragged part would land: marked on the part it would take the place of.
                        val dropTarget = dragging?.let { d ->
                            val to = if (dropRow >= 0) SpecEdit.dropIndex(parts, d, dropRow) else null
                            to != null && part.path.size > d.size - 1 && part.path.take(d.size - 1) == d.dropLast(1) &&
                                part.path.getOrNull(d.size - 1)?.toIntOrNull() == to && part.path != d && part.path.size == d.size
                        } == true
                        val name = partName(part)
                        val typeName = TYPE_NAMES[part.type]?.let { stringResource(it) } ?: part.type
                        val moveText = stringResource(R.string.drag_desc, name)
                        Row(
                            Modifier.fillMaxWidth()
                                .zIndex(if (beingDragged) 1f else 0f)
                                .graphicsLayer { translationY = if (beingDragged) dragOffset else 0f }
                                .background(
                                    when {
                                        beingDragged -> MaterialTheme.colorScheme.secondaryContainer
                                        dropTarget -> MaterialTheme.colorScheme.tertiaryContainer
                                        isSelected -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> Color.Transparent
                                    },
                                    RoundedCornerShape(12.dp),
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier.weight(1f)
                                    .clickable { selected = if (isSelected) null else part.path; lastField = "" }
                                    .padding(start = (8 + part.depth * 16).dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            ) {
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                                    modifier = Modifier.semantics { contentDescription = getString(R.string.part_desc, name) },
                                )
                                if (typeName != name) Text(typeName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (isSelected) {
                                    val node = SpecEdit.node(doc, part.path)
                                    if (node != null) {
                                        Spacer(Modifier.height(8.dp))
                                        Actions(doc, part, ::commit, onSelect = { selected = it })
                                        Properties(doc, part.path, node, ::commit)
                                    }
                                }
                            }
                            // The root and a list's item have no siblings to be moved among, so no handle.
                            if (part.path.isNotEmpty() && part.path.last() != "item") {
                                Box(
                                    Modifier.size(48.dp)
                                        .semantics { contentDescription = moveText }
                                        .pointerInput(part.path) {
                                            detectVerticalDragGestures(
                                                onDragStart = {
                                                    dragging = part.path
                                                    dragOffset = 0f
                                                    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == rowKey }
                                                    dragStartCenter = if (info != null) info.offset + info.size / 2f else 0f
                                                    dropRow = info?.index ?: -1
                                                },
                                                onVerticalDrag = { change, dy ->
                                                    change.consume()
                                                    dragOffset += dy
                                                    dropRow = rowUnder(rowKey)
                                                },
                                                onDragEnd = { finishDrag() },
                                                onDragCancel = {
                                                    dragging = null
                                                    dragOffset = 0f
                                                    dropRow = -1
                                                },
                                            )
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        repeat(3) { Box(Modifier.size(20.dp, 2.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(1.dp))) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** What a part is called in the list: the person's own words, or its kind. */
    @Composable
    private fun partName(part: SpecEdit.Part): String {
        part.text?.takeIf { it.isNotBlank() }?.let { return it }
        return when (part.kind) {
            "card" -> stringResource(R.string.kind_card)
            "column" -> stringResource(R.string.kind_column)
            "row" -> stringResource(R.string.kind_row)
            "layer" -> stringResource(R.string.kind_layer)
            "space" -> stringResource(R.string.kind_space)
            "picture" -> stringResource(R.string.kind_picture)
            "ring" -> stringResource(R.string.kind_ring)
            "bar" -> stringResource(R.string.kind_bar)
            "divider" -> stringResource(R.string.kind_divider)
            "list" -> stringResource(R.string.kind_list_of, part.detail)
            "icon" -> stringResource(R.string.kind_icon_named, part.detail)
            else -> TYPE_NAMES[part.type]?.let { stringResource(it) } ?: part.type
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Actions(doc: Map<String, Any?>, part: SpecEdit.Part, commit: (Map<String, Any?>?, String, String) -> Boolean, onSelect: (List<String>?) -> Unit) {
        var adding by remember { mutableStateOf(false) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { if (commit(SpecEdit.move(doc, part.path, -1), "", getString(R.string.already_first))) onSelect(SpecEdit.moved(part.path, -1)) }) { Text(stringResource(R.string.up)) }
            OutlinedButton(onClick = { if (commit(SpecEdit.move(doc, part.path, 1), "", getString(R.string.already_last))) onSelect(SpecEdit.moved(part.path, 1)) }) { Text(stringResource(R.string.down)) }
            OutlinedButton(onClick = { commit(SpecEdit.duplicate(doc, part.path), "", getString(R.string.cannot_copy)) }) { Text(stringResource(R.string.copy)) }
            OutlinedButton(onClick = { if (commit(SpecEdit.remove(doc, part.path), "", getString(R.string.cannot_remove))) onSelect(null) }) { Text(stringResource(R.string.remove)) }
            Box {
                Button(onClick = { adding = true }) { Text(stringResource(if (part.container) R.string.add_inside else R.string.add_after)) }
                DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                    for ((name, make) in SpecEdit.NEW_PARTS) {
                        DropdownMenuItem(text = { Text(NEW_PART_NAMES[name]?.let { stringResource(it) } ?: name) }, onClick = {
                            adding = false
                            val added = SpecEdit.add(doc, part.path, make())
                            if (commit(added?.first, "", getString(R.string.nothing_added))) onSelect(added?.second)
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
        val cannot = stringResource(R.string.cannot_change)
        fun set(prop: String, value: Any?) = commit(SpecEdit.set(doc, path, prop, value), "", cannot)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val textKey = when (type) {
                "text" -> "text"
                "button", "toggle", "check", "stepper" -> "label"
                else -> null
            }
            if (textKey != null) TextProp(key + textKey, (node[textKey] as? String) ?: "", stringResource(if (textKey == "text") R.string.prop_text else R.string.prop_label)) {
                commit(SpecEdit.set(doc, path, textKey, it), key + textKey, "")
            }
            if (type in setOf("text", "clock", "timer")) {
                Choice(stringResource(R.string.prop_size), STYLES.named(), node["style"] as? String ?: "body") { set("style", it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.prop_bold), Modifier.weight(1f))
                    Switch(checked = node["bold"] == true, onCheckedChange = { set("bold", if (it) true else null) })
                }
            }
            if (type == "clock") Choice(stringResource(R.string.prop_shows), clockFormats(), node["format"] as? String ?: "HH:mm") { set("format", it) }
            if (type == "text") Slide(stringResource(R.string.prop_lines), (node["lines"] as? Number)?.toInt() ?: 2, 1, 6) { set("lines", it) }
            if (type in setOf("text", "clock", "timer", "icon", "progress", "chart", "divider")) {
                Swatches(stringResource(R.string.prop_colour), INKS, node["color"] as? String ?: if (type in setOf("progress", "chart", "icon")) "primary" else "text") { set("color", it) }
            }
            if (type == "icon") {
                Slide(stringResource(R.string.prop_icon_size), (node["iconSize"] as? Number)?.toInt() ?: 24, 12, 64) { set("iconSize", it) }
                IconPicker(node["icon"] as? String ?: "star") { set("icon", it) }
            }
            if (type == "progress") Choice(stringResource(R.string.prop_shape), linkedMapOf("bar" to stringResource(R.string.opt_bar), "ring" to stringResource(R.string.opt_ring)), node["shape"] as? String ?: "bar") { set("shape", it) }
            if (type == "button") Choice(
                stringResource(R.string.prop_style),
                linkedMapOf("filled" to stringResource(R.string.opt_filled), "tonal" to stringResource(R.string.opt_tonal), "plain" to stringResource(R.string.opt_plain)),
                node["kind"] as? String ?: "filled",
            ) { set("kind", it) }
            if (type == "image") Choice(
                stringResource(R.string.prop_fit),
                linkedMapOf("cover" to stringResource(R.string.opt_cover), "contain" to stringResource(R.string.opt_contain)),
                node["fit"] as? String ?: "cover",
            ) { set("fit", it) }
            if (type in SpecEdit.CONTAINERS || type == "list") {
                Swatches(stringResource(R.string.prop_fill), FILLS, node["bg"] as? String ?: "none") { set("bg", if (it == "none") null else it) }
                Slide(stringResource(R.string.prop_radius), (node["radius"] as? Number)?.toInt() ?: 0, 0, 28) { set("radius", if (it == 0) null else it) }
                Slide(stringResource(R.string.prop_pad), (node["pad"] as? Number)?.toInt() ?: 0, 0, 24) { set("pad", if (it == 0) null else it) }
            }
            if (type in setOf("col", "row", "list")) Slide(stringResource(R.string.prop_gap), (node["gap"] as? Number)?.toInt() ?: 0, 0, 24) { set("gap", if (it == 0) null else it) }
            if (type in setOf("col", "row", "box")) {
                Choice(stringResource(if (type == "row") R.string.prop_across else R.string.prop_down), ALIGN.named(), node["align"] as? String ?: "start") { set("align", if (it == "start") null else it) }
                Choice(stringResource(if (type == "row") R.string.prop_up_down else R.string.prop_side), CROSS.named(), node["cross"] as? String ?: "start") { set("cross", if (it == "start") null else it) }
            }
            // For every part: when it shows, and how much room it takes.
            Choice(stringResource(R.string.prop_show_when), FROM.named(), node["from"] as? String ?: "") { set("from", it.ifEmpty { null }) }
            Choice(stringResource(R.string.prop_width), WIDTHS.named(), (node["w"] as? String) ?: "") { set("w", it.ifEmpty { null }) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.prop_spare), Modifier.weight(1f))
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
            TextProp("name", doc["name"] as? String ?: "", stringResource(R.string.look_name)) { commit(SpecEdit.setTop(doc, "name", it.take(40)), "name", "") }
            Text(stringResource(R.string.look_colours), style = MaterialTheme.typography.titleSmall)
            val current = if (theme["palette"] == "custom") PALETTES.entries.firstOrNull { it.value == theme["colors"] }?.key ?: "own" else "phone"
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(current == "phone", onClick = {
                    commit(SpecEdit.setTop(SpecEdit.setTop(doc, "theme.palette", null), "theme.colors", null), "", "")
                }, label = { Text(stringResource(R.string.palette_phone)) })
                for ((name, colors) in PALETTES) {
                    FilterChip(current == name, onClick = {
                        commit(SpecEdit.setTop(SpecEdit.setTop(doc, "theme.palette", "custom"), "theme.colors", colors), "", "")
                    }, label = { Text(PALETTE_NAMES[name]?.let { stringResource(it) } ?: name) }, leadingIcon = {
                        Box(Modifier.size(16.dp).background(Color(android.graphics.Color.parseColor(colors.getValue("bg"))), CircleShape).border(1.dp, Color.Gray, CircleShape))
                    })
                }
                if (current == "own") FilterChip(true, onClick = {}, label = { Text(stringResource(R.string.palette_own)) })
            }
            Text(stringResource(R.string.look_phone_note), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.look_start_size), style = MaterialTheme.typography.titleSmall)
            Slide(stringResource(R.string.look_columns), (size["cols"] as? Number)?.toInt() ?: 2, 1, 5) { commit(SpecEdit.setTop(doc, "size", linkedMapOf("cols" to it, "rows" to ((size["rows"] as? Number)?.toInt() ?: 2))), "", "") }
            Slide(stringResource(R.string.look_rows), (size["rows"] as? Number)?.toInt() ?: 2, 1, 5) { commit(SpecEdit.setTop(doc, "size", linkedMapOf("cols" to ((size["cols"] as? Number)?.toInt() ?: 2), "rows" to it)), "", "") }
            Text(stringResource(R.string.look_resize_note), style = MaterialTheme.typography.bodySmall)
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
        )
        if (!ok) Text(stringResource(R.string.not_applied), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
    private fun Swatches(title: String, options: Map<String, Pair<Int, @Composable () -> Color>>, current: String, pick: (String) -> Unit) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((value, entry) in options) {
                val (name, color) = entry
                val c = color()
                FilterChip(current == value, onClick = { pick(value) }, label = { Text(stringResource(name)) }, leadingIcon = {
                    Box(Modifier.size(14.dp).background(c, CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                })
            }
        }
    }

    @Composable
    private fun Slide(title: String, value: Int, min: Int, max: Int, change: (Int) -> Unit) {
        var v by remember(title, value) { mutableStateOf(value.toFloat()) }
        Text(stringResource(R.string.slide_label, title, v.toInt()), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = v, onValueChange = { v = it }, valueRange = min.toFloat()..max.toFloat(), steps = Math.max(0, max - min - 1),
            onValueChangeFinished = { if (v.toInt() != value) change(v.toInt()) },
        )
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun IconPicker(current: String, pick: (String) -> Unit) {
        var query by remember { mutableStateOf("") }
        OutlinedTextField(value = query, onValueChange = { query = it.take(30) }, label = { Text(stringResource(R.string.find_icon, current)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        val shown = IconNames.ALL.filter { query.isBlank() || query.trim().lowercase() in it }.take(48)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (name in shown) {
                val res = iconRes(name) ?: continue
                val description = stringResource(R.string.icon_desc, name)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (name == current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(44.dp).clickable { pick(name) }.semantics { contentDescription = description },
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(painterResource(res), contentDescription = null, modifier = Modifier.size(24.dp)) }
                }
            }
        }
    }

    /** Choices written as their translated names. */
    @Composable
    private fun Map<String, Int>.named(): Map<String, String> = entries.associateTo(LinkedHashMap()) { it.key to stringResource(it.value) }

    /** The clock styles, each shown as a sample of today's date in the phone's language. */
    @Composable
    private fun clockFormats(): Map<String, String> = remember {
        val sample = LocalDateTime.of(2026, 11, 3, 14, 5)
        CLOCK_FORMATS.associateWithTo(LinkedHashMap()) { p -> runCatching { DateTimeFormatter.ofPattern(p, Locale.getDefault()).format(sample) }.getOrDefault(p) }
    }

    companion object {
        const val JSON = "json"
        const val MINE = "mine"

        /** @param mine the widget is one of the person's own, not a gallery one. */
        fun intent(context: Context, json: String, mine: Boolean = false): Intent =
            Intent(context, EditorActivity::class.java).putExtra(JSON, json).putExtra(MINE, mine)

        private val TYPE_NAMES = mapOf(
            "col" to R.string.type_col, "row" to R.string.type_row, "box" to R.string.type_box, "list" to R.string.type_list,
            "text" to R.string.type_text, "icon" to R.string.type_icon, "image" to R.string.type_image, "progress" to R.string.type_progress,
            "chart" to R.string.type_chart, "clock" to R.string.type_clock, "timer" to R.string.type_timer, "button" to R.string.type_button,
            "toggle" to R.string.type_toggle, "stepper" to R.string.type_stepper, "check" to R.string.type_check,
            "spacer" to R.string.type_spacer, "divider" to R.string.type_divider,
        )
        private val NEW_PART_NAMES = mapOf(
            "Text" to R.string.new_Text, "Big number" to R.string.new_Big_number, "Icon" to R.string.new_Icon, "Card" to R.string.new_Card,
            "Row" to R.string.new_Row, "Column" to R.string.new_Column, "Divider" to R.string.new_Divider, "Space" to R.string.new_Space,
        )
        private val PALETTE_NAMES = mapOf(
            "Sand" to R.string.palette_Sand, "Mint" to R.string.palette_Mint, "Sky" to R.string.palette_Sky,
            "Rose" to R.string.palette_Rose, "Paper" to R.string.palette_Paper, "Night" to R.string.palette_Night,
        )
        private val STYLES: Map<String, Int> = linkedMapOf(
            "caption" to R.string.size_caption, "label" to R.string.size_label, "body" to R.string.size_body,
            "title" to R.string.size_title, "headline" to R.string.size_headline, "display" to R.string.size_display,
        )
        private val CLOCK_FORMATS = listOf("HH:mm", "h:mm", "EEE d MMM", "EEEE d MMMM", "d.MM.yyyy")
        private val ALIGN: Map<String, Int> = linkedMapOf("start" to R.string.align_start, "center" to R.string.align_center, "end" to R.string.align_end, "between" to R.string.align_between)
        private val CROSS: Map<String, Int> = linkedMapOf("start" to R.string.align_start, "center" to R.string.align_center, "end" to R.string.align_end)
        private val FROM: Map<String, Int> = linkedMapOf("" to R.string.from_any, "s" to R.string.from_s, "m" to R.string.from_m, "l" to R.string.from_l)
        private val WIDTHS: Map<String, Int> = linkedMapOf("" to R.string.width_own, "fill" to R.string.width_fill)

        private val INKS: Map<String, Pair<Int, @Composable () -> Color>> = linkedMapOf(
            "text" to (R.string.ink_text to @Composable { MaterialTheme.colorScheme.onSurface }),
            "muted" to (R.string.ink_muted to @Composable { MaterialTheme.colorScheme.onSurfaceVariant }),
            "primary" to (R.string.ink_primary to @Composable { MaterialTheme.colorScheme.primary }),
            "secondary" to (R.string.ink_secondary to @Composable { MaterialTheme.colorScheme.secondary }),
            "tertiary" to (R.string.ink_tertiary to @Composable { MaterialTheme.colorScheme.tertiary }),
            "success" to (R.string.ink_success to @Composable { Color(0xFF2E7D32) }),
            "warning" to (R.string.ink_warning to @Composable { Color(0xFFB26A00) }),
            "error" to (R.string.ink_error to @Composable { MaterialTheme.colorScheme.error }),
        )
        private val FILLS: Map<String, Pair<Int, @Composable () -> Color>> = linkedMapOf(
            "none" to (R.string.fill_none to @Composable { Color.Transparent }),
            "surface" to (R.string.fill_surface to @Composable { MaterialTheme.colorScheme.surface }),
            "surfaceVariant" to (R.string.fill_surfaceVariant to @Composable { MaterialTheme.colorScheme.surfaceVariant }),
            "primaryContainer" to (R.string.fill_primaryContainer to @Composable { MaterialTheme.colorScheme.primaryContainer }),
            "secondaryContainer" to (R.string.fill_secondaryContainer to @Composable { MaterialTheme.colorScheme.secondaryContainer }),
            "tertiaryContainer" to (R.string.fill_tertiaryContainer to @Composable { MaterialTheme.colorScheme.tertiaryContainer }),
            "primary" to (R.string.fill_primary to @Composable { MaterialTheme.colorScheme.primary }),
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
