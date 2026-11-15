package dev.afterdrop.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.datastore.preferences.core.emptyPreferences
import androidx.glance.appwidget.compose
import dev.afterdrop.app.widget.AfterdropWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The widget drawn inside the app by the very same code that draws it on the home screen, with the
 * phone's real data, so what you see before adding is what you get.
 */
object Preview {
    /** A cell on a Pixel home screen is roughly this size; previews use it to turn cells into dp. */
    const val CELL_DP = 92

    fun sizeOf(cols: Int, rows: Int): DpSize = DpSize((cols * CELL_DP).dp, (rows * CELL_DP - 4).dp)

    /** A context in dark or light mode, whatever the phone is set to. */
    fun themed(context: Context, dark: Boolean?): Context {
        if (dark == null) return context
        val config = Configuration(context.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        return context.createConfigurationContext(config)
    }

    /** Runs the widget once for [json] at [size]. Null if it could not be drawn. */
    suspend fun render(context: Context, json: String, size: DpSize): RemoteViews? = runCatching {
        AfterdropWidget().compose(
            context,
            options = Bundle().apply { putString(AfterdropWidget.PREVIEW, json) },
            size = size,
            state = emptyPreferences(),
        )
    }.getOrNull()
}

/** Holds a drawn widget; touches pass to the screen around it, so a preview cannot fire the widget's actions. */
@SuppressLint("ViewConstructor")
private class PreviewFrame(context: Context) : FrameLayout(context) {
    override fun onInterceptTouchEvent(ev: MotionEvent?) = true
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = false
}

/**
 * Shows [json] as a widget of [cols] x [rows] cells. When that is wider than the room here, it is
 * scaled down as a whole, like a thumbnail, rather than laid out differently.
 * @param refresh change it to draw again, for example after new data arrived.
 */
@Composable
fun WidgetPreview(json: String, cols: Int, rows: Int, modifier: Modifier = Modifier, dark: Boolean? = null, limitHeight: Dp = Dp.Unspecified, refresh: Int = 0) {
    val context = LocalContext.current
    val size = Preview.sizeOf(cols, rows)
    var views by remember(json, cols, rows, dark) { mutableStateOf<RemoteViews?>(null) }
    LaunchedEffect(json, cols, rows, dark, refresh) {
        views = withContext(Dispatchers.Default) { Preview.render(Preview.themed(context, dark), json, size) }
    }
    BoxWithConstraints(modifier) {
        val byWidth = if (maxWidth < size.width) maxWidth / size.width else 1f
        val byHeight = if (limitHeight != Dp.Unspecified && limitHeight < size.height) limitHeight / size.height else 1f
        val scale = Math.min(byWidth, byHeight)
        Box(Modifier.size(size.width * scale, size.height * scale).semantics { contentDescription = "Widget preview" }) {
            AndroidView(
                factory = { PreviewFrame(it) },
                modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).size(size.width, size.height).graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                },
                update = { frame ->
                    frame.removeAllViews()
                    val rv = views ?: return@AndroidView
                    val ctx = Preview.themed(frame.context, dark)
                    runCatching { frame.addView(rv.apply(ctx, frame), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }
                },
            )
        }
    }
}
