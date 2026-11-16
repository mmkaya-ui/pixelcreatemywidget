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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource

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
                        item { Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.headlineMedium) }
                        items(POINTS) { (title, body) ->
                            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Each point's title and text, as string resources. */
        val POINTS: List<Pair<Int, Int>> = listOf(
            R.string.privacy_1_t to R.string.privacy_1_b, R.string.privacy_2_t to R.string.privacy_2_b,
            R.string.privacy_3_t to R.string.privacy_3_b, R.string.privacy_4_t to R.string.privacy_4_b,
            R.string.privacy_5_t to R.string.privacy_5_b, R.string.privacy_6_t to R.string.privacy_6_b,
            R.string.privacy_7_t to R.string.privacy_7_b,
        )
    }
}
