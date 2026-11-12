package dev.afterdrop.ai

import dev.afterdrop.spec.JArray
import dev.afterdrop.spec.JNumber
import dev.afterdrop.spec.JObject
import dev.afterdrop.spec.JString
import dev.afterdrop.spec.Json
import dev.afterdrop.spec.JsonError

class HttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String?)
class HttpResponse(val status: Int, val body: String)

/** Sends one HTTPS request. The app supplies the real one; tests supply a script. Throws on network failure. */
fun interface Transport {
    fun send(request: HttpRequest): HttpResponse
}

/**
 * The Gemini API with the person's own key, over plain REST: no SDK, no Google Play services.
 * The key travels only in the x-goog-api-key header to generativelanguage.googleapis.com.
 */
class Gemini(private val transport: Transport, private val apiKey: String, private var model: String? = null) : TextModel {

    /** The model in use, chosen from the account's model list on first use when none was given. */
    val modelName: String? get() = model

    /** Models that rejected the thinking setting. */
    private val plain = HashSet<String>()

    /** Models still to try, best first. Filled from the account's model list when no model was given. */
    private var queue: MutableList<String>? = null

    /** The models this key could still fall back to, best first. Empty until the first call. */
    fun candidates(): List<String> = queue?.toList() ?: emptyList()

    override fun complete(prompt: String): Reply {
        if (model == null) {
            when (val picked = pickModel()) {
                is Reply.Text -> model = picked.text
                is Reply.Failed -> return picked
            }
        }
        var last: Reply = Reply.Failed("this key has no usable Gemini model")
        repeat(MAX_MODELS) {
            val name = model ?: return last
            last = ask(name, prompt)
            // A newer model can need paid credits while an older or lighter one is free: move on and remember.
            // The same goes for a model that is overloaded or too slow right now: a lighter one usually answers.
            val blocked = last is Reply.Failed && (last as Reply.Failed).reason.let {
                "needs paid credits" in it || "was not found" in it || "took too long" in it || "having trouble" in it || "quota" in it
            }
            if (!blocked) return last
            model = queue?.let { q -> q.remove(name); q.firstOrNull() }
        }
        return last
    }

    private fun ask(name: String, prompt: String): Reply {
        fun body(light: Boolean): String {
            val config = LinkedHashMap<String, dev.afterdrop.spec.JsonValue>()
            config["responseMimeType"] = JString("application/json")
            config["temperature"] = JNumber(0.4)
            // A widget needs no long deliberation; without this some models think for over a minute.
            if (light) config["thinkingConfig"] = JObject(mapOf("thinkingLevel" to JString("low")))
            return Json.write(
                JObject(
                    mapOf(
                        "contents" to JArray(listOf(JObject(mapOf("parts" to JArray(listOf(JObject(mapOf("text" to JString(prompt))))))))),
                        "generationConfig" to JObject(config),
                    )
                )
            )
        }
        var slow = false
        fun post(light: Boolean): HttpResponse? = try {
            transport.send(HttpRequest("POST", "$BASE/models/$name:generateContent", headers(), body(light)))
        } catch (_: java.net.SocketTimeoutException) {
            slow = true
            null
        } catch (_: Exception) {
            null
        }
        fun unreachable() = if (slow) Reply.Failed("the model \"$name\" took too long to answer", retryable = true)
        else Reply.Failed("could not reach the Gemini API; check the internet connection", retryable = false)
        var response = (if (name in plain) post(false) else post(true)) ?: return unreachable()
        if (response.status == 400 && name !in plain && "think" in response.body.lowercase()) {
            // An older model that does not know the thinking setting: ask again without it, and remember.
            plain += name
            response = post(false) ?: return unreachable()
        }
        if (response.status != 200) return failure(response)
        val root = parse(response.body) ?: return Reply.Failed("the Gemini API sent an answer that could not be read", retryable = true)
        val candidate = ((root["candidates"] as? JArray)?.items?.firstOrNull() as? JObject)
        val parts = ((candidate?.get("content") as? JObject)?.get("parts") as? JArray)?.items ?: emptyList()
        val text = parts.mapNotNull { ((it as? JObject)?.get("text") as? JString)?.value }.joinToString("")
        if (text.isBlank()) {
            val why = ((root["promptFeedback"] as? JObject)?.get("blockReason") as? JString)?.value
                ?: (candidate?.get("finishReason") as? JString)?.value
            return Reply.Failed("the model returned nothing" + (why?.let { " ($it)" } ?: ""), retryable = why == null)
        }
        return Reply.Text(text)
    }

    /** Asks the API which models this key can use and picks the newest general Flash model. */
    fun pickModel(): Reply {
        val response = call(HttpRequest("GET", "$BASE/models?pageSize=200", headers(), null))
            ?: return Reply.Failed("could not reach the Gemini API; check the internet connection")
        if (response.status != 200) return failure(response)
        val models = (parse(response.body)?.get("models") as? JArray)?.items ?: emptyList()
        val names = models.mapNotNull { m ->
            val o = m as? JObject ?: return@mapNotNull null
            val methods = (o["supportedGenerationMethods"] as? JArray)?.items?.mapNotNull { (it as? JString)?.value } ?: emptyList()
            (o["name"] as? JString)?.value?.removePrefix("models/")?.takeIf { "generateContent" in methods }
        }
        val order = rank(names)
        queue = order.toMutableList()
        return order.firstOrNull()?.let { Reply.Text(it) } ?: Reply.Failed("this key has no Gemini Flash model available")
    }

    private fun headers() = mapOf("x-goog-api-key" to apiKey, "Content-Type" to "application/json")

    private fun call(request: HttpRequest): HttpResponse? = try {
        transport.send(request)
    } catch (_: Exception) {
        null
    }

    private fun parse(body: String): JObject? = try {
        Json.parse(body) as? JObject
    } catch (_: JsonError) {
        null
    }

    private fun failure(r: HttpResponse): Reply.Failed {
        val message = ((parse(r.body)?.get("error") as? JObject)?.get("message") as? JString)?.value?.take(160)
        return when (r.status) {
            400, 401, 403 -> Reply.Failed("the Gemini API refused the key" + (message?.let { ": $it" } ?: ""))
            402 -> Reply.Failed("the model \"$model\" needs paid credits on this key" + (message?.let { ": $it" } ?: ""))
            404 -> Reply.Failed("the model \"$model\" was not found; clear the model name to pick one automatically")
            // Each model has its own free quota, so the next model may still answer.
            429 -> Reply.Failed("the free quota for \"$model\" is used up for now; try again in a minute")
            in 500..599 -> Reply.Failed("the Gemini API is having trouble (${r.status}); try again shortly", retryable = true)
            else -> Reply.Failed("the Gemini API answered ${r.status}" + (message?.let { ": $it" } ?: ""))
        }
    }

    companion object {
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private val SKIP = listOf("lite", "image", "tts", "live", "audio", "embedding", "preview", "exp", "omni", "native", "thinking", "8b")
        private val VERSION = Regex("gemini-(\\d+(?:\\.\\d+)?)-flash(?:-(\\d{3}))?$")

        private const val MAX_MODELS = 5

        /** Every usable Flash model, best first: stable general ones newest first, then the lighter ones. */
        fun rank(names: List<String>): List<String> {
            val best = choose(names) ?: return emptyList()
            fun version(n: String) = Regex("gemini-(\\d+(?:\\.\\d+)?)-").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val general = names.filter { n -> "flash" in n && SKIP.none { it in n } && VERSION.containsMatchIn(n) && n != best }
                .sortedWith(compareByDescending<String> { version(it) }.thenBy { it.length })
            val lite = names.filter { n -> "flash-lite" in n && listOf("image", "tts", "live", "audio", "preview", "exp").none { it in n } }
                .sortedWith(compareByDescending<String> { version(it) }.thenBy { it.length })
            return (listOf(best) + general + lite).distinct()
        }

        /** The newest stable general-purpose Flash model in [names], else any Flash model, else null. */
        fun choose(names: List<String>): String? {
            val flash = names.filter { "flash" in it }
            val stable = flash.filter { n -> SKIP.none { it in n } }
            val versioned = stable.mapNotNull { n -> VERSION.find(n)?.let { n to it.groupValues[1].toDouble() } }
            return versioned.maxWithOrNull(compareBy<Pair<String, Double>> { it.second }.thenBy { -it.first.length })?.first
                ?: stable.firstOrNull { it == "gemini-flash-latest" }
                ?: stable.firstOrNull()
                ?: flash.firstOrNull { "lite" !in it && "image" !in it && "tts" !in it && "live" !in it && "audio" !in it }
        }
    }
}
