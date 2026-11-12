package dev.afterdrop.app

import dev.afterdrop.ai.HttpRequest
import dev.afterdrop.ai.HttpResponse
import dev.afterdrop.ai.Transport
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Plain https GET for data sources. Follows at most three redirects, https only, reads at most 1 MB,
 * sends no cookies and no identifying headers. Returns null on any failure.
 */
object HttpsFetcher : dev.afterdrop.data.Fetcher {
    private const val MAX = 1024 * 1024

    override fun get(url: String): String? = runCatching { fetch(url, 3) }.getOrNull()

    private fun fetch(address: String, redirects: Int): String? {
        val url = URL(address)
        if (url.protocol != "https" || url.userInfo != null) return null
        val c = url.openConnection() as HttpsURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("Accept", "application/json, application/rss+xml, application/atom+xml, text/xml;q=0.9, */*;q=0.5")
            c.setRequestProperty("User-Agent", "Afterdrop")
            val status = c.responseCode
            if (status in 301..308 && redirects > 0) {
                val next = c.getHeaderField("Location") ?: return null
                return fetch(URL(url, next).toString(), redirects - 1)
            }
            if (status != 200) return null
            return c.inputStream.use { String(it.readNBytes(MAX), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }
}

/** The path to the Gemini API. It talks HTTPS to the Gemini API host and refuses anything else. */
object GeminiTransport : Transport {
    private const val HOST = "generativelanguage.googleapis.com"
    private const val MAX_RESPONSE = 512 * 1024

    override fun send(request: HttpRequest): HttpResponse {
        val url = URL(request.url)
        require(url.protocol == "https" && url.host == HOST) { "unexpected host" }
        val c = url.openConnection() as HttpsURLConnection
        try {
            c.requestMethod = request.method
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = false
            for ((k, v) in request.headers) c.setRequestProperty(k, v)
            request.body?.let { body ->
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { it.readNBytes(MAX_RESPONSE) } ?: ByteArray(0)
            return HttpResponse(status, String(bytes, Charsets.UTF_8))
        } finally {
            c.disconnect()
        }
    }
}
