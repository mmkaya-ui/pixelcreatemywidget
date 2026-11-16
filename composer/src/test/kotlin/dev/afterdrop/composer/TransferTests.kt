package dev.afterdrop.composer

import dev.afterdrop.spec.Json
import dev.afterdrop.spec.SpecReader
import dev.afterdrop.spec.SpecResult
import dev.afterdrop.testkit.eq
import dev.afterdrop.testkit.goldenDir
import dev.afterdrop.testkit.suite
import dev.afterdrop.testkit.yes
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.Deflater

private fun allGolden(): Map<String, String> =
    File(goldenDir(), "valid").listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.associate { it.name to it.readText() }

private fun nameOf(json: String) = (SpecReader.read(json) as SpecResult.Ok).spec.name

private fun ok(d: Transfer.Decoded) = d as? Transfer.Decoded.Ok ?: throw AssertionError("expected a widget but: " + (d as Transfer.Decoded.Bad).reason)

private fun bad(d: Transfer.Decoded) = (d as? Transfer.Decoded.Bad)?.reason ?: throw AssertionError("expected a refusal")

/** A code for raw bytes, as a hostile sender could make one. */
private fun codeFor(bytes: ByteArray): String {
    val d = Deflater(Deflater.BEST_COMPRESSION, true)
    d.setInput(bytes)
    d.finish()
    val out = ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (!d.finished()) out.write(buf, 0, d.deflate(buf))
    d.end()
    return Transfer.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
}

internal val transfer = suite("share codes and backups") {
    test("every gallery widget survives a round trip through a code") {
        for ((file, json) in allGolden()) {
            val code = Transfer.encode(json)
            yes(code.startsWith("afterdrop1:") && code.none { it.isWhitespace() }, "$file: the code must be one unbroken line")
            val back = ok(Transfer.decode(code))
            eq(1, back.widgets.size, file)
            eq(nameOf(json), back.widgets[0].name, file)
            eq(Json.write((SpecReader.read(json) as SpecResult.Ok).spec.json), back.widgets[0].json, file)
        }
    }
    test("a code is short enough for a chat message") {
        val longest = allGolden().maxOf { Transfer.encode(it.value).length }
        yes(longest < 4_000, "the longest code is $longest characters")
    }
    test("the code is found inside a message, with text and punctuation around it") {
        val json = allGolden().getValue("battery.json")
        val code = Transfer.encode(json)
        val message = Transfer.share("Afterdrop widget: Battery", json, "Get the app: https://example.org/afterdrop")
        eq(code, Transfer.find(message))
        eq("Battery", ok(Transfer.decode(message)).widgets[0].name)
        eq("Battery", ok(Transfer.decode("look at this -> $code.\nthanks!")).widgets[0].name)
        eq("Battery", ok(Transfer.decode("  \n$code  \n")).widgets[0].name)
        eq(null, Transfer.find("no code here, but afterdrop1: alone is nothing"))
    }
    test("a cut-off or changed code is refused with advice, not a crash") {
        val code = Transfer.encode(allGolden().getValue("steps.json"))
        for (cut in listOf(code.length - 1, code.length / 2, 14, 12)) bad(Transfer.decode(code.substring(0, cut)))
        val changed = code.substring(0, 40) + (if (code[40] == 'A') 'B' else 'A') + code.substring(41)
        bad(Transfer.decode(changed))
        yes("cut off" in bad(Transfer.decode(code.dropLast(3))) || "does not hold" in bad(Transfer.decode(code.dropLast(3))) || "cannot be used" in bad(Transfer.decode(code.dropLast(3))))
    }
    test("a plain widget description is accepted too, even in a code fence") {
        val json = allGolden().getValue("rates.json")
        eq(nameOf(json), ok(Transfer.decode(json)).widgets[0].name)
        eq(nameOf(json), ok(Transfer.decode("Here you go:\n```json\n$json\n```")).widgets[0].name)
    }
    test("nonsense and empty input are refused") {
        bad(Transfer.decode(""))
        bad(Transfer.decode("   \n "))
        bad(Transfer.decode("hello"))
        bad(Transfer.decode("[1,2,3]"))
        bad(Transfer.decode("{\"hello\":1}"))
    }
    test("a code that holds an invalid widget is refused with the reason") {
        val reason = bad(Transfer.decode(codeFor("{\"v\":1,\"name\":\"x\",\"root\":{\"t\":\"nonsense\"}}".toByteArray())))
        yes("cannot be used" in reason, reason)
    }
    test("a code that inflates to something enormous is refused") {
        val bomb = ByteArray(20 * 1024 * 1024) // 20 MB of zeros squeezes to about 20 KB
        val code = codeFor(bomb)
        yes(code.length < 120_000, "the test bomb must pass the length check, so the inflate limit is what stops it")
        bad(Transfer.decode(code))
    }
    test("a code with a huge payload is refused before it is unpacked") {
        bad(Transfer.decode("afterdrop1:" + "A".repeat(200_000)))
    }
    test("a backup brings back every widget and note") {
        val golden = allGolden()
        val widgets = listOf("battery.json", "steps.json", "shopping-list.json").associate { nameOf(golden.getValue(it)) to golden.getValue(it) }
        val notes = mapOf("pinned" to "milk\neggs", "Shopping" to "tea", "empty" to "")
        val text = Transfer.backup(widgets, notes)
        val back = ok(Transfer.decode(text))
        eq(widgets.keys.toList(), back.widgets.map { it.name })
        eq(mapOf("pinned" to "milk\neggs", "Shopping" to "tea"), back.notes, "empty notes are not kept")
        eq(emptyList<String>(), back.skipped)
    }
    test("a backup is read from a file's text with a byte-order mark or stray spaces") {
        val text = Transfer.backup(mapOf("Battery" to allGolden().getValue("battery.json")))
        eq(1, ok(Transfer.decode("\n  $text  \n")).widgets.size)
    }
    test("a backup with one bad widget keeps the good ones and says what was skipped") {
        val good = allGolden().getValue("battery.json")
        val text = "{\"afterdrop\":1,\"widgets\":[$good,{\"v\":1,\"name\":\"Broken\",\"root\":{\"t\":\"nonsense\"}},$good],\"notes\":{}}"
        val back = ok(Transfer.decode(text))
        eq(1, back.widgets.size)
        eq(2, back.skipped.size)
        yes("Widget 2" in back.skipped[0] && "used twice" in back.skipped[1], back.skipped.toString())
    }
    test("a backup of a newer version, with too many widgets, or with nothing usable, is refused") {
        yes("newer" in bad(Transfer.decode("{\"afterdrop\":2,\"widgets\":[]}")))
        val good = allGolden().getValue("battery.json")
        bad(Transfer.decode("{\"afterdrop\":1,\"widgets\":[" + List(Transfer.MAX_WIDGETS + 1) { good }.joinToString(",") + "]}"))
        bad(Transfer.decode("{\"afterdrop\":1,\"widgets\":[]}"))
        bad(Transfer.decode("{\"afterdrop\":1,\"widgets\":[{\"v\":1,\"root\":{\"t\":\"nonsense\"}}]}"))
        bad(Transfer.decode("{\"afterdrop\":1}"))
    }
    test("notes are cut to the app's limit and limited in number") {
        val long = "x".repeat(Transfer.MAX_NOTE + 500)
        val many = (1..80).joinToString(",") { "\"n$it\":\"t\"" }
        val good = allGolden().getValue("battery.json")
        val back = ok(Transfer.decode("{\"afterdrop\":1,\"widgets\":[$good],\"notes\":{\"long\":\"$long\",$many}}"))
        eq(Transfer.MAX_NOTE, back.notes.getValue("long").length)
        yes(back.notes.size <= 50, "${back.notes.size} notes")
    }
    test("what is restored is the reader's own output, so unknown extras cannot ride along") {
        val good = allGolden().getValue("battery.json")
        val sneaky = good.replaceFirst("{", "{\"evil\":\"x\",")
        // The reader refuses unknown fields outright, so the widget is not imported at all.
        bad(Transfer.decode("{\"afterdrop\":1,\"widgets\":[$sneaky]}"))
    }
}
