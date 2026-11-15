package dev.afterdrop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What Afterdrop reads and where it goes. Health Connect opens this when the person asks why the app
 * wants step data, and Android requires an app that asks for health data to have such a page.
 */
class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)) {
                Scaffold(Modifier.fillMaxSize()) { inner ->
                    LazyColumn(
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = inner.calculateTopPadding() + 20.dp, bottom = inner.calculateBottomPadding() + 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { Text("Privacy", style = MaterialTheme.typography.headlineMedium) }
                        items(POINTS) { (title, body) ->
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    companion object {
        val POINTS = listOf(
            "No account, no tracking" to "Afterdrop has no account, no analytics, no ads and no crash reporting. Everything it keeps stays in its own storage on this phone.",
            "Steps" to "Read from Health Connect on this phone to show today's steps and the last seven days in your widgets. Only step counts are read. They are never sent anywhere, and widgets that send data to the internet cannot use them. You can take the access back at any time in Health Connect.",
            "What is playing" to "With notification access, Afterdrop asks Android which song is playing in apps that show media controls. It does not read, keep or send your notifications. Cover art is kept as a small picture in Afterdrop's own storage.",
            "Calendar" to "Read on this phone to list your next events. Never sent anywhere.",
            "Notes" to "What you write in a note widget, or share to Afterdrop, is kept on this phone only.",
            "Internet data" to "Weather, air quality and exchange rates come from open-meteo.com and frankfurter.dev. Feeds and web data come only from addresses you allowed. Pictures that such data points to are downloaded from where it says.",
            "Gemini" to "Only if you add your own Gemini API key: your widget descriptions, and the prompts of AI text widgets, go to Google's Gemini API. Private data (steps, calendar, notes, music) is never put into them.",
        )
    }
}
