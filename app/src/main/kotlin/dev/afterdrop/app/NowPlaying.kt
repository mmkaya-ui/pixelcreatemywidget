package dev.afterdrop.app

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import dev.afterdrop.app.widget.AfterdropWidget
import dev.afterdrop.formula.Value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * What is playing right now, in any app that shows media controls: song, artist, album, cover art.
 *
 * Android only shares other apps' media sessions with an app that has notification access, so this
 * works once the person turns that on for Afterdrop. Afterdrop never reads, keeps or sends the
 * notifications themselves; it only asks the system for the active media sessions.
 */
object NowPlaying {
    class Track(val pkg: String, val title: String?, val artist: String?, val album: String?, val playing: Boolean, val art: File?)

    fun component(context: Context) = ComponentName(context, NowPlayingListener::class.java)

    /** True once notification access is on for Afterdrop. */
    fun allowed(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component(context)) == true

    /** The settings page where the person turns notification access on for Afterdrop. */
    fun settingsIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The app's own info page, where "Allow restricted settings" lives for apps not installed from a store. */
    fun appInfoIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun controllers(context: Context): List<MediaController> {
        if (!allowed(context)) return emptyList()
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        return try {
            manager.getActiveSessions(component(context))
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /** The session that is playing, or else the most recent one (a paused song still shows). */
    fun current(context: Context): Track? {
        val list = controllers(context)
        val c = list.firstOrNull { isPlaying(it) } ?: list.firstOrNull { it.metadata != null } ?: return null
        val m = c.metadata
        fun text(vararg keys: String) = keys.firstNotNullOfOrNull { m?.getString(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }?.take(200)
        val title = text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val album = text(MediaMetadata.METADATA_KEY_ALBUM)
        return Track(c.packageName, title, artist, album, isPlaying(c), art(context, c, title, artist, album))
    }

    fun value(context: Context): Value? {
        if (!allowed(context)) return null
        val t = current(context) ?: return Value.Obj(mapOf("playing" to Value.Bool(false)))
        fun str(s: String?) = s?.let { Value.Str(it) } ?: Value.Null
        return Value.Obj(
            mapOf(
                "title" to str(t.title),
                "artist" to str(t.artist),
                "album" to str(t.album),
                "art" to str(t.art?.path),
                "playing" to Value.Bool(t.playing),
                "app" to str(label(context, t.pkg)),
            )
        )
    }

    /** Opens the app that is playing, so a tap on the widget leads to its controls. */
    fun launchIntent(context: Context): Intent? =
        current(context)?.let { context.packageManager.getLaunchIntentForPackage(it.pkg) }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun isPlaying(c: MediaController) = c.playbackState?.state.let {
        it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING
    }

    private fun label(context: Context, pkg: String): String? = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    /**
     * The cover art as a small file in the app's private storage, written once per track. The widget
     * draws from the file, so a redraw never has to ask the playing app again.
     */
    private fun art(context: Context, c: MediaController, title: String?, artist: String?, album: String?): File? {
        val dir = File(context.filesDir, "art").apply { mkdirs() }
        val file = File(dir, Integer.toHexString(listOf(c.packageName, title, artist, album).joinToString("|").hashCode()) + ".png")
        if (file.length() > 0) return file
        val m = c.metadata ?: return null
        val bitmap: Bitmap = m.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                .firstNotNullOfOrNull { key -> m.getString(key)?.let { fromUri(context, it) } }
            ?: return null
        return runCatching {
            val small = Images.shrink(bitmap, ART_PX)
            file.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // Keep only the last few covers.
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
            file
        }.getOrNull()
    }

    private fun fromUri(context: Context, uri: String): Bitmap? = runCatching {
        if (!uri.startsWith("content://") && !uri.startsWith("android.resource://")) return null
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private const val ART_PX = 256
    private const val KEEP = 8
}

/**
 * Bound by the system while notification access is on. It listens only for media sessions starting,
 * stopping or changing track, and redraws widgets that show what is playing. It ignores notifications.
 */
class NowPlayingListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val watched = HashMap<MediaController, MediaController.Callback>()
    private var pending: Job? = null
    private var shown: String? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        watch(list ?: emptyList())
        poke()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val manager = getSystemService(MediaSessionManager::class.java) ?: return
        runCatching {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, NowPlaying.component(this), main)
            watch(manager.getActiveSessions(NowPlaying.component(this)))
        }
        poke()
    }

    override fun onListenerDisconnected() {
        runCatching { getSystemService(MediaSessionManager::class.java)?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        unwatchAll()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        unwatchAll()
        scope.cancel()
        super.onDestroy()
    }

    private fun watch(list: List<MediaController>) {
        unwatchAll()
        for (c in list) {
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = poke()
                override fun onPlaybackStateChanged(state: PlaybackState?) = poke()
                override fun onSessionDestroyed() = poke()
            }
            c.registerCallback(callback, main)
            watched[c] = callback
        }
    }

    private fun unwatchAll() {
        for ((c, cb) in watched) runCatching { c.unregisterCallback(cb) }
        watched.clear()
    }

    /**
     * Players report their position often; the widget only redraws when the track or play/pause
     * changes, and at most about once a second.
     */
    private fun poke() {
        pending?.cancel()
        pending = scope.launch {
            delay(800)
            val t = NowPlaying.current(this@NowPlayingListener)
            val now = listOf(t?.pkg, t?.title, t?.artist, t?.playing, t?.art?.path).joinToString("|")
            if (now == shown) return@launch
            shown = now
            if (Sources.anyPlacedUses(this@NowPlayingListener, "media")) runCatching { AfterdropWidget.refreshAll(this@NowPlayingListener) }
        }
    }
}
