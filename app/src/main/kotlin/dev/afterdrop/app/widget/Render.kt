package dev.afterdrop.app.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.AlarmClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.Switch
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentHeight
import androidx.glance.layout.wrapContentWidth
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.afterdrop.app.MainActivity
import dev.afterdrop.app.R
import dev.afterdrop.app.Store
import dev.afterdrop.engine.Engine
import dev.afterdrop.engine.Fit
import dev.afterdrop.engine.RNode
import dev.afterdrop.engine.SizeClass
import dev.afterdrop.engine.Tap
import dev.afterdrop.formula.Value
import dev.afterdrop.formula.display
import dev.afterdrop.formula.truthy
import dev.afterdrop.spec.JString

/** Per-render facts the node renderer needs. */
class Env(
    val context: Context,
    val appWidgetId: Int,
    val nowMillis: Long,
    /** Colour roles overridden by a spec with a custom palette. */
    val custom: Map<String, Color>,
    /** From the fit pass: under 1 when the widget is short and text and padding must shrink. */
    val textFactor: Float = 1f,
    val padFactor: Float = 1f,
    /** The system font size setting. */
    val fontScale: Float = 1f,
)

/** Content never sits closer than this to the widget edge, so rounded corners cannot clip it. */
private const val MIN_EDGE_DP = 8

@Composable
fun WidgetContent(appWidgetId: Int) {
    val context = LocalContext.current
    currentState(AfterdropWidget.TICK) // read so that a refresh recomposes this
    val size = LocalSize.current
    val frame = GlanceModifier.fillMaxSize().appWidgetBackground()
        .background(GlanceTheme.colors.widgetBackground)
        .cornerRadius(android.R.dimen.system_app_widget_background_radius)
    val spec = AfterdropWidget.readSpec(context, appWidgetId)
    if (spec == null) {
        val message = if (Store(context).spec(appWidgetId) == null) R.string.choose_widget else R.string.broken_widget
        Box(
            frame.padding(12.dp).clickable(actionStartActivity(MainActivity.bindIntent(context, appWidgetId))),
            contentAlignment = Alignment.Center,
        ) {
            Text(context.getString(message), style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
        }
        return
    }
    val input = AfterdropWidget.input(context, appWidgetId, spec, SizeClass.of(size.width.value.toInt(), size.height.value.toInt()))
    val fontScale = context.resources.configuration.fontScale
    val fitted = Fit.fit(Engine.resolve(input), size.width.value.toInt(), size.height.value.toInt(), fontScale.toDouble())
    val root = fitted.root
    val custom = HashMap<String, Color>()
    if ((spec.theme["palette"] as? JString)?.value == "custom") {
        (spec.theme["colors"] as? dev.afterdrop.spec.JObject)?.fields?.forEach { (role, hex) ->
            runCatching { custom[role] = Color(android.graphics.Color.parseColor((hex as JString).value)) }
        }
    }
    val env = Env(context, appWidgetId, input.nowMillis, custom, fitted.textFactor.toFloat(), fitted.padFactor.toFloat(), fontScale)
    Box(frame) { NodeView(root, GlanceModifier.fillMaxSize(), env, root = true) }
}

@Composable
private fun role(name: String?, env: Env): ColorProvider {
    env.custom[name]?.let { return ColorProvider(it) }
    val c = GlanceTheme.colors
    return when (name) {
        "bg" -> c.widgetBackground
        "surface" -> c.surface
        "surfaceVariant" -> c.surfaceVariant
        "primary" -> c.primary
        "onPrimary" -> c.onPrimary
        "secondary" -> c.secondary
        "tertiary" -> c.tertiary
        "muted" -> c.onSurfaceVariant
        "outline" -> c.outline
        "error" -> c.error
        // Darker on light backgrounds, lighter on dark ones, so both stay readable.
        "success" -> androidx.glance.color.ColorProvider(day = Color(0xFF2E7D32), night = Color(0xFF81C784))
        "warning" -> androidx.glance.color.ColorProvider(day = Color(0xFFB26A00), night = Color(0xFFFFB74D))
        else -> c.onSurface
    }
}

private fun fontSize(style: String?) = when (style) {
    "display" -> 36
    "headline" -> 24
    "title" -> 18
    "label" -> 12
    "caption" -> 11
    else -> 14
}

private val ICONS = mapOf(
    "air" to "💨", "rainy" to "🌧", "event" to "📅", "refresh" to "🔄",
    "timer" to "⏱", "alarm" to "⏰", "music_note" to "🎵", "monitor_weight" to "⚖",
    "sunny" to "☀", "cloud" to "☁", "battery_full" to "🔋", "check" to "✔",
    "star" to "⭐", "favorite" to "❤", "home" to "🏠", "water_drop" to "💧",
    "directions_bike" to "🚴", "directions_walk" to "🚶", "note" to "📝",
)

private val FILLS_COLUMN = setOf("row", "list", "divider", "chart")

private fun run(key: String, op: String): Action =
    actionRunCallback<WidgetCallback>(actionParametersOf(AfterdropWidget.KEY to key, AfterdropWidget.OP to op))

private fun tapAction(n: RNode, env: Env): Action? = when (val t = n.tap) {
    null -> null
    is Tap.OpenUrl -> actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    is Tap.OpenApp -> env.context.packageManager.getLaunchIntentForPackage(t.pkg)?.let { actionStartActivity(it) }
    is Tap.OpenSource -> actionStartActivity(
        when (t.use) {
            "calendar" -> Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/${env.nowMillis}"))
            "alarm" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            "battery" -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            else -> MainActivity.bindIntent(env.context, env.appWidgetId)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    Tap.Edit, is Tap.Ask -> actionStartActivity(MainActivity.bindIntent(env.context, env.appWidgetId))
    Tap.Refresh -> run(n.key, "refresh")
    Tap.SetVar -> run(n.key, "tap")
}

/** A clock or timer is an embedded view; without a stated height it takes all the room and hides what follows. */
private fun lineBox(mod: GlanceModifier, hasHeight: Boolean, style: String?, env: Env): GlanceModifier =
    if (hasHeight) mod else mod.height((fontSize(style) * 1.35f * env.textFactor * env.fontScale).dp)

private fun horizontal(s: String?) = when (s) {
    "center" -> Alignment.Horizontal.CenterHorizontally
    "end" -> Alignment.Horizontal.End
    else -> Alignment.Horizontal.Start
}

private fun vertical(s: String?) = when (s) {
    "center" -> Alignment.Vertical.CenterVertically
    "end" -> Alignment.Vertical.Bottom
    else -> Alignment.Vertical.Top
}

@Composable
private fun NodeView(n: RNode, base: GlanceModifier, env: Env, root: Boolean = false) {
    val node = n.node
    var mod = base
    when (val w = node.text("w")) {
        "fill" -> mod = mod.fillMaxWidth()
        "wrap" -> mod = mod.wrapContentWidth()
        null -> node.int("w")?.let { mod = mod.width(it.dp) }
    }
    when (val h = node.text("h")) {
        "fill" -> mod = mod.fillMaxHeight()
        "wrap" -> mod = mod.wrapContentHeight()
        null -> node.int("h")?.let { mod = mod.height(it.dp) }
    }
    val pad = Math.round((node.int("pad") ?: 0) * env.padFactor)
    val edge = if (root) Math.max(pad, MIN_EDGE_DP) else pad
    if (edge > 0) mod = mod.padding(edge.dp)
    node.text("bg")?.let { if (it != "none") mod = mod.background(role(it, env)) }
    node.int("radius")?.let { mod = mod.cornerRadius(it.dp) }
    tapAction(n, env)?.let { if (n.type != "button") mod = mod.clickable(it) }

    val gap = node.int("gap") ?: 0
    val align = node.text("align")
    val between = align == "between" && n.kids.size in 2..5
    val color = role(node.text("color") ?: if (n.type in setOf("progress", "chart")) "primary" else "text", env)
    val textStyle = TextStyle(
        color = color,
        fontSize = (fontSize(node.text("style")) * env.textFactor).sp,
        fontWeight = if (node.flag("bold")) FontWeight.Bold else FontWeight.Normal,
    )

    when (n.type) {
        // A gap is a real spacer where the 10-child limit allows; padding on a button would only pad its inside.
        "col" -> Column(mod, verticalAlignment = vertical(align), horizontalAlignment = horizontal(node.text("cross"))) {
            val spacers = !between && gap > 0 && n.kids.size * 2 - 1 <= 10
            n.kids.forEachIndexed { i, kid ->
                if (between && i > 0) Spacer(GlanceModifier.defaultWeight())
                if (spacers && i > 0) Spacer(GlanceModifier.height(gap.dp))
                var km: GlanceModifier = GlanceModifier
                if ((kid.node.int("grow") ?: 0) > 0) km = km.defaultWeight()
                if (!between && !spacers && i > 0 && gap > 0) km = km.padding(top = gap.dp)
                // Rows and lists span the column, so "between" and growing children have room to work.
                if (kid.type in FILLS_COLUMN && !kid.node.has("w")) km = km.fillMaxWidth()
                NodeView(kid, km, env)
            }
        }
        "row" -> Row(mod, horizontalAlignment = horizontal(align), verticalAlignment = vertical(node.text("cross"))) {
            val spacers = !between && gap > 0 && n.kids.size * 2 - 1 <= 10
            n.kids.forEachIndexed { i, kid ->
                if (between && i > 0) Spacer(GlanceModifier.defaultWeight())
                if (spacers && i > 0) Spacer(GlanceModifier.width(gap.dp))
                var km: GlanceModifier = GlanceModifier
                // An embedded clock or timer would otherwise take the whole row and push its neighbours out.
                val embedded = kid.type == "clock" || kid.type == "timer"
                if ((kid.node.int("grow") ?: 0) > 0 || (embedded && n.kids.size > 1 && !kid.node.has("w"))) km = km.defaultWeight()
                if (!between && !spacers && i > 0 && gap > 0) km = km.padding(start = gap.dp)
                NodeView(kid, km, env)
            }
        }
        "box" -> Box(mod, contentAlignment = Alignment(horizontal(align), vertical(node.text("cross")))) {
            for (kid in n.kids) NodeView(kid, GlanceModifier, env)
        }
        "list" -> if (n.kids.isEmpty()) {
            Text(n.text ?: "", modifier = mod, style = TextStyle(color = role("muted", env), fontSize = 14.sp))
        } else {
            LazyColumn(mod) {
                items(n.kids.size) { i ->
                    NodeView(n.kids[i], if (i > 0 && gap > 0) GlanceModifier.padding(top = gap.dp) else GlanceModifier, env)
                }
            }
        }
        "text" -> Text(n.text ?: "", modifier = mod, style = textStyle, maxLines = node.int("lines") ?: Fit.DEFAULT_LINES)
        "icon" -> Text(
            ICONS[node.text("icon")] ?: "•", modifier = mod,
            style = TextStyle(color = color, fontSize = ((node.int("iconSize") ?: 24) * 0.8f * env.textFactor).sp), maxLines = 1,
        )
        "image" -> Spacer(mod) // images arrive with the sources that supply them
        "progress" -> {
            val fraction = (n.fraction ?: 0.0).toFloat()
            val track = role("surfaceVariant", env)
            if (node.text("shape") == "ring") {
                val sized = node.has("w") || node.has("h") || (node.int("grow") ?: 0) > 0
                Image(
                    provider = ImageProvider(
                        Painter.ring(fraction, color.getColor(env.context).toArgb(), track.getColor(env.context).toArgb())
                    ),
                    contentDescription = "${Math.round(fraction * 100)}%",
                    modifier = if (sized) mod else mod.size(48.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                LinearProgressIndicator(
                    progress = fraction,
                    modifier = if (node.has("w")) mod else mod.fillMaxWidth(),
                    color = color, backgroundColor = track,
                )
            }
        }
        "chart" -> {
            val sizedH = node.has("h") || (node.int("grow") ?: 0) > 0
            var m = if (node.has("w")) mod else mod.fillMaxWidth()
            if (!sizedH) m = m.height(40.dp)
            Image(
                provider = ImageProvider(
                    Painter.chart(
                        node.text("kind") ?: "spark", n.series ?: emptyList(), node.number("min"), node.number("max"),
                        color.getColor(env.context).toArgb(), role("surfaceVariant", env).getColor(env.context).toArgb(),
                    )
                ),
                contentDescription = null, modifier = m, contentScale = ContentScale.FillBounds,
            )
        }
        "clock" -> {
            // The launcher keeps a TextClock ticking by itself: no app wake-ups for the time.
            val rv = RemoteViews(env.context.packageName, R.layout.clock)
            val format: CharSequence = SpannableString(node.text("format") ?: "HH:mm").apply {
                if (node.flag("bold")) setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            }
            rv.setCharSequence(R.id.clock, "setFormat12Hour", format)
            rv.setCharSequence(R.id.clock, "setFormat24Hour", format)
            node.text("zone")?.let { rv.setString(R.id.clock, "setTimeZone", it) }
            rv.setTextColor(R.id.clock, color.getColor(env.context).toArgb())
            rv.setTextViewTextSize(R.id.clock, TypedValue.COMPLEX_UNIT_SP, fontSize(node.text("style")) * env.textFactor)
            AndroidRemoteViews(rv, lineBox(mod, node.has("h"), node.text("style"), env))
        }
        "timer" -> {
            val rv = RemoteViews(env.context.packageName, R.layout.timer)
            val target = n.instant ?: env.nowMillis.toDouble()
            rv.setChronometer(R.id.timer, SystemClock.elapsedRealtime() + (target - env.nowMillis).toLong(), null, true)
            rv.setChronometerCountDown(R.id.timer, node.text("mode") != "up")
            rv.setTextColor(R.id.timer, color.getColor(env.context).toArgb())
            rv.setTextViewTextSize(R.id.timer, TypedValue.COMPLEX_UNIT_SP, fontSize(node.text("style")) * env.textFactor)
            AndroidRemoteViews(rv, lineBox(mod, node.has("h"), node.text("style"), env))
        }
        // 48 dp is the smallest comfortable touch target; Glance's own default is about half that.
        "button" -> Button(
            text = n.text ?: "", onClick = tapAction(n, env) ?: run(n.key, "refresh"),
            modifier = if (node.has("h")) mod else mod.height(Fit.BUTTON.dp), maxLines = 1,
        )
        "toggle" -> Switch(
            checked = n.state?.truthy() ?: false, onCheckedChange = run(n.key, "toggle"),
            modifier = if (node.has("h")) mod else mod.height(Fit.CONTROL.dp), text = n.text ?: "",
        )
        // A bare checkbox is 32 dp tall. The surrounding box is the 40 dp touch target and toggles too.
        "check" -> Box(
            (if (node.has("h")) mod else mod.height(Fit.CONTROL.dp)).clickable(run(n.key, "toggle")),
            contentAlignment = Alignment.CenterStart,
        ) {
            CheckBox(checked = n.state?.truthy() ?: false, onCheckedChange = run(n.key, "toggle"), text = n.text ?: "")
        }
        "stepper" -> Row(mod, verticalAlignment = Alignment.Vertical.CenterVertically) {
            Button(text = "−", onClick = run(n.key, "dec"), modifier = GlanceModifier.size(48.dp, Fit.CONTROL.dp), maxLines = 1)
            Text(
                (n.state ?: Value.Null).display(),
                modifier = GlanceModifier.padding(horizontal = 12.dp),
                style = TextStyle(color = role("text", env), fontSize = 18.sp, fontWeight = FontWeight.Bold), maxLines = 1,
            )
            Button(text = "+", onClick = run(n.key, "inc"), modifier = GlanceModifier.size(48.dp, Fit.CONTROL.dp), maxLines = 1)
        }
        "divider" -> Box((if (node.has("w")) mod else mod.fillMaxWidth()).height(1.dp).background(role(node.text("color") ?: "outline", env))) {}
        else -> Spacer(mod)
    }
}
