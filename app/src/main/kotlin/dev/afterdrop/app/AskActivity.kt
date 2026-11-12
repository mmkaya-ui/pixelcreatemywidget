package dev.afterdrop.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import dev.afterdrop.app.widget.AfterdropWidget
import dev.afterdrop.engine.StoredVar
import dev.afterdrop.engine.Vars
import dev.afterdrop.formula.Value
import dev.afterdrop.formula.display
import kotlinx.coroutines.launch
import java.time.ZoneId

/** A small dialog over the home screen for a widget's "ask" tap: type a value, and the widget keeps it. */
class AskActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val name = intent.getStringExtra(VAR)
        val spec = AfterdropWidget.readSpec(this, id)
        val variable = spec?.vars?.get(name)
        if (spec == null || name == null || variable == null) {
            finish()
            return
        }
        val number = variable.type == "int" || variable.type == "num"
        val store = Store(this)
        val current = Vars.current(spec, store.vars(id), System.currentTimeMillis(), ZoneId.systemDefault())[name]
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)) {
                var text by remember { mutableStateOf(current?.takeIf { it != Vars.initial(variable) }?.display() ?: "") }
                var error by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text(intent.getStringExtra(TITLE)?.take(40) ?: name) },
                    text = {
                        OutlinedTextField(
                            value = text, onValueChange = { text = it.take(if (number) 20 else 500); error = "" },
                            singleLine = number, isError = error.isNotEmpty(),
                            supportingText = { if (error.isNotEmpty()) Text(error) },
                            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val typed: Value? = if (number) text.trim().replace(',', '.').toDoubleOrNull()?.let { Value.Num(it) } else Value.Str(text)
                            val value = typed?.let { Vars.coerce(variable, it) }
                            if (value == null) error = "Enter a number"
                            else {
                                val stored = HashMap(store.vars(id))
                                stored[name] = StoredVar(value, System.currentTimeMillis())
                                store.saveVars(id, stored)
                                lifecycleScope.launch {
                                    runCatching { AfterdropWidget.refresh(this@AskActivity, GlanceAppWidgetManager(this@AskActivity).getGlanceIdBy(id)) }
                                    finish()
                                }
                            }
                        }) { Text("Save") }
                    },
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Cancel") } },
                )
            }
        }
    }

    companion object {
        const val VAR = "var"
        const val TITLE = "title"

        fun intent(context: Context, appWidgetId: Int, variable: String, title: String): Intent =
            Intent(context, AskActivity::class.java)
                .setData(Uri.parse("afterdrop://ask/$appWidgetId/$variable"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(VAR, variable)
                .putExtra(TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
