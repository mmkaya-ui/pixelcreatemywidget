package dev.afterdrop.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.afterdrop.engine.Engine
import dev.afterdrop.engine.RenderInput
import dev.afterdrop.engine.SizeClass
import dev.afterdrop.engine.Vars
import dev.afterdrop.spec.Json
import dev.afterdrop.spec.Vocabulary
import dev.afterdrop.spec.WidgetSpec
import java.io.File
import java.time.ZoneId
import java.util.Locale
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Pictures for image nodes: cover art the phone already has, and pictures that widget data points to,
 * such as a feed item's thumbnail. Web pictures are downloaded in the background, kept small in the
 * app's cache, and drawn from there, so drawing a widget never waits on the network.
 */
object Images {
    /** Largest side a widget picture is kept at, in pixels. Home-screen images are small. */
    const val MAX_PX = 320
    private const val MAX_BYTES = 3 * 1024 * 1024
    private const val KEEP = 60
    private const val PER_RUN = 24

    private fun dir(context: Context) = File(context.cacheDir, "img").apply { mkdirs() }

    fun cacheFile(context: Context, url: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(context), digest.take(32) + ".webp")
    }

    fun isWeb(ref: String) = ref.startsWith("https://")

    /**
     * The picture a node refers to, scaled to at most [maxPx] on its longer side, or null when there is
     * none yet. Only files inside the app's own storage are read, whatever the data says.
     */
    fun load(context: Context, ref: String, maxPx: Int = MAX_PX): Bitmap? {
        val file = when {
            isWeb(ref) -> cacheFile(context, ref)
            ref.startsWith("/") -> File(ref).canonicalFile.takeIf { f ->
                listOf(context.filesDir, context.cacheDir).any { f.path.startsWith(it.canonicalPath + File.separator) }
            }
            else -> null
        } ?: return null
        if (!file.isFile || file.length() == 0L) return null
        return decode(file.readBytes(), maxPx)
    }

    /** Downloads a web picture into the cache. Call off the main thread. Returns true when it is ready. */
    fun fetch(context: Context, url: String): Boolean {
        if (!isWeb(url)) return false
        val file = cacheFile(context, url)
        if (file.length() > 0) return true
        val bytes = download(url, 3) ?: return false
        val bitmap = decode(bytes, MAX_PX) ?: return false
        return runCatching {
            val tmp = File(file.path + ".part")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
            tmp.renameTo(file)
            dir(context).listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
            true
        }.getOrDefault(false)
    }

    /**
     * Downloads the web pictures these widgets show, such as feed thumbnails, a few per run. Pictures
     * come from internet data, so the phone's private sources are not read for this.
     * @return how many pictures are ready.
     */
    fun prefetch(context: Context, specs: List<WidgetSpec>): Int {
        var budget = PER_RUN
        var ready = 0
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        for (spec in specs) {
            if (budget <= 0) break
            if (!Json.write(spec.json).contains("\"image\"")) continue
            val data = Sources.collect(context, spec, skip = Vocabulary.PRIVATE_SOURCES)
            val input = RenderInput(spec, data, Vars.current(spec, emptyMap(), now, zone), now, zone, Locale.getDefault(), SizeClass.L)
            for (url in Engine.resolve(input).all().filter { it.type == "image" }.mapNotNull { it.text }.filter { isWeb(it) }.distinct()) {
                if (budget-- <= 0) break
                if (fetch(context, url)) ready++
            }
        }
        return ready
    }

    fun shrink(bitmap: Bitmap, maxPx: Int): Bitmap {
        val longest = Math.max(bitmap.width, bitmap.height)
        if (longest <= maxPx) return bitmap
        val scale = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, Math.max(1, (bitmap.width * scale).toInt()), Math.max(1, (bitmap.height * scale).toInt()), true)
    }

    private fun decode(bytes: ByteArray, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        bitmap?.let { shrink(it, maxPx) }
    }.getOrNull()

    /** https only, at most three redirects and 3 MB, no cookies, and only answers that say they are images. */
    private fun download(address: String, redirects: Int): ByteArray? = runCatching {
        val url = URL(address)
        if (url.protocol != "https" || url.userInfo != null) return null
        val c = url.openConnection() as HttpsURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("User-Agent", "Afterdrop")
            c.setRequestProperty("Accept", "image/*")
            val status = c.responseCode
            if (status in 301..308 && redirects > 0) {
                val next = c.getHeaderField("Location") ?: return null
                return download(URL(url, next).toString(), redirects - 1)
            }
            if (status != 200) return null
            if (c.contentType?.startsWith("image/") == false) return null
            if (c.contentLengthLong > MAX_BYTES) return null
            c.inputStream.use { input ->
                val bytes = input.readNBytes(MAX_BYTES + 1)
                if (bytes.size > MAX_BYTES) null else bytes
            }
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}
