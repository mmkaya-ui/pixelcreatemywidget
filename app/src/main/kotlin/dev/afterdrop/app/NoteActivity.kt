package dev.afterdrop.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.afterdrop.app.widget.AfterdropWidget
import dev.afterdrop.spec.SpecReader
import dev.afterdrop.spec.SpecResult
import kotlinx.coroutines.launch

/**
 * Writes a note widget's text, over the home screen. Opened by tapping a note widget, or by sharing
 * text from any app to Afterdrop: the shared text is added to the end of the note, ready to edit.
 */
class NoteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        val shared = if (intent?.action == Intent.ACTION_SEND) {
            listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
        } else null
        if (shared != null && shared.isEmpty()) {
            finish()
            return
        }
        // The notes placed widgets show; a share can go to any of them, and starts at the one written last.
        val names = (listOfNotNull(intent?.getStringExtra(NOTE)) + noteNames(store).sortedByDescending { store.note(it)?.second ?: 0L })
            .distinct().ifEmpty { listOf("pinned") }
        fun startText(name: String): String {
            val old = store.note(name)?.first ?: ""
            return if (shared == null) old else listOf(old.trimEnd(), shared).filter { it.isNotEmpty() }.joinToString("\n")
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)) {
                var name by remember { mutableStateOf(names.first()) }
                var text by remember { mutableStateOf(startText(names.first()).take(Store.MAX_NOTE)) }
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text(if (shared != null) "Add to note" else "Note") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (names.size > 1) NoteChoice(names, name) {
                                name = it
                                text = startText(it).take(Store.MAX_NOTE)
                            }
                            OutlinedTextField(
                                value = text, onValueChange = { text = it.take(Store.MAX_NOTE) },
                                modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 10,
                                supportingText = { Text("Kept on this phone only. ${text.length} of ${Store.MAX_NOTE}") },
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            store.saveNote(name, text)
                            lifecycleScope.launch {
                                runCatching { AfterdropWidget.refreshAll(this@NoteActivity) }
                                finish()
                            }
                        }) { Text("Save") }
                    },
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Cancel") } },
                )
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun NoteChoice(names: List<String>, chosen: String, choose: (String) -> Unit) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in names) FilterChip(selected = n == chosen, onClick = { choose(n) }, label = { Text(n) })
        }
    }

    companion object {
        const val NOTE = "note"

        fun noteNames(store: Store): List<String> = store.placedSpecs()
            .mapNotNull { (SpecReader.read(it) as? SpecResult.Ok)?.spec }
            .flatMap { s -> s.sources.values.filter { it.use == "notes" }.map { Sources.noteName(it) } }
            .distinct()

        fun intent(context: Context, note: String): Intent =
            Intent(context, NoteActivity::class.java)
                .setData(Uri.parse("afterdrop://note/" + Uri.encode(note)))
                .putExtra(NOTE, note)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
