package dev.afterdrop.ai

import dev.afterdrop.spec.Json
import java.net.URL
import java.time.LocalDate
import javax.net.ssl.HttpsURLConnection

/**
 * A real conversation with the Gemini API, run by CI when the repository has a GEMINI_API_KEY secret.
 * Without the secret it does nothing. The key is read from the environment and never printed.
 */
fun main() {
    val key = System.getenv("GEMINI_API_KEY")?.trim().orEmpty()
    if (key.isEmpty()) {
        println("LIVE GEMINI: skipped, no GEMINI_API_KEY secret is set")
        return
    }
    val transport = Transport { r ->
        val c = URL(r.url).openConnection() as HttpsURLConnection
        c.requestMethod = r.method
        c.connectTimeout = 15_000
        c.readTimeout = 60_000 // the same limit the app uses
        for ((k, v) in r.headers) c.setRequestProperty(k, v)
        r.body?.let { body ->
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = c.responseCode
        HttpResponse(status, (if (status in 200..299) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8) ?: "")
    }
    val gemini = Gemini(transport, key)
    val asks = listOf(
        "countdown to 24 December",
        "track 8 glasses of water a day with a ring",
        "wind and rain for my bike ride, with a small chart of rain chance",
        "a big clock for Tokyo with the date under it",
        "my battery level, and a warning line when it is under 20 percent",
    )
    var good = 0
    var last: dev.afterdrop.spec.WidgetSpec? = null
    for (ask in asks) {
        val started = System.currentTimeMillis()
        val out = Generator.generate(ask, gemini, LocalDate.now())
        val secs = (System.currentTimeMillis() - started) / 1000.0
        when (out) {
            is Outcome.Ok -> {
                if (out.by == "model") good++
                last = out.spec
                println("LIVE GEMINI: \"$ask\" -> \"${out.spec.name}\" by ${out.by}, ${out.attempts} call(s), ${secs}s, ${out.spec.root.all().count()} nodes" + (out.note?.let { " [$it]" } ?: ""))
            }
            is Outcome.Failed -> println("LIVE GEMINI: \"$ask\" FAILED after ${secs}s: ${out.reason}")
        }
        Thread.sleep(4_500) // stay well under the free tier's requests-per-minute limit
    }
    println("LIVE GEMINI: model in use: ${gemini.modelName}; candidates left: ${gemini.candidates()}")
    last?.let { spec ->
        when (val out = Generator.edit(spec, "make the main text bold and use the secondary colour", gemini)) {
            is Outcome.Ok -> println("LIVE GEMINI: edit ok in ${out.attempts} call(s); changed=${Json.write(out.spec.json) != Json.write(spec.json)}")
            is Outcome.Failed -> println("LIVE GEMINI: edit FAILED: ${out.reason}")
        }
    }
    println("LIVE GEMINI: $good of ${asks.size} widgets came straight from the model")
    // A key or billing problem is the account's, not the code's: report it without failing the build.
    if (good == 0) println("LIVE GEMINI: WARNING no widget came from the model; see the reasons above")
}
