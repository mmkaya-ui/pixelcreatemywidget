package dev.afterdrop.composer

import dev.afterdrop.spec.SpecReader
import dev.afterdrop.spec.SpecResult
import dev.afterdrop.testkit.eq
import dev.afterdrop.testkit.goldenDir
import dev.afterdrop.testkit.suite
import dev.afterdrop.testkit.yes
import java.io.File

private fun golden(name: String) = File(goldenDir(), "valid/$name.json").readText()

private fun valid(doc: Map<String, Any?>) = SpecReader.read(SpecEdit.write(doc)) is SpecResult.Ok

internal val editing = suite("visual editor edits") {
    test("parts are listed depth first with readable labels") {
        val parts = SpecEdit.parts(SpecEdit.parse(golden("battery")))
        eq(listOf("row", "progress", "col", "text", "text", "text"), parts.map { it.type })
        eq(listOf(0, 1, 1, 2, 2, 2), parts.map { it.depth })
        eq(listOf("1", "0"), parts[3].path)
        eq("{num(bat.level, 0)}%", parts[3].label)
        eq("Ring", parts[1].label)
        yes(parts[0].container && !parts[1].container)
    }
    test("a list item is reached through its item step") {
        val parts = SpecEdit.parts(SpecEdit.parse(golden("headlines")))
        val item = parts.first { it.path.contains("item") }
        eq(listOf("1", "item"), item.path)
        eq("row", item.type)
        yes(SpecEdit.remove(SpecEdit.parse(golden("headlines")), item.path) == null, "a list's item cannot be removed")
    }
    test("setting, removing and the original left alone") {
        val doc = SpecEdit.parse(golden("battery"))
        val bigger = SpecEdit.set(doc, listOf("1", "0"), "style", "display")!!
        eq("display", SpecEdit.node(bigger, listOf("1", "0"))!!["style"])
        eq("headline", SpecEdit.node(doc, listOf("1", "0"))!!["style"])
        val plain = SpecEdit.set(bigger, listOf("1", "0"), "bold", null)!!
        eq(null, SpecEdit.node(plain, listOf("1", "0"))!!["bold"])
        yes(valid(plain))
        eq(null, SpecEdit.set(doc, listOf("9"), "style", "title"))
        eq(null, SpecEdit.set(doc, emptyList(), "t", "col"), "the type is not a property")
    }
    test("move, duplicate and remove keep the widget valid") {
        val doc = SpecEdit.parse(golden("weekly-chores"))
        val moved = SpecEdit.move(doc, listOf("1"), 1)!!
        eq("Bins out", SpecEdit.node(moved, listOf("1"))!!["label"])
        eq("Laundry", SpecEdit.node(moved, listOf("2"))!!["label"])
        eq(listOf("2"), SpecEdit.moved(listOf("1"), 1))
        eq(null, SpecEdit.move(doc, listOf("0"), -1))
        eq(null, SpecEdit.move(doc, emptyList(), 1))
        val twice = SpecEdit.duplicate(doc, listOf("1"))!!
        eq(6, SpecEdit.parts(twice).size - 1)
        val fewer = SpecEdit.remove(doc, listOf("4"))!!
        eq(4, SpecEdit.parts(fewer).size - 1)
        yes(valid(moved) && valid(twice) && valid(fewer))
        eq(null, SpecEdit.remove(doc, emptyList()), "the root stays")
    }
    test("adding goes inside a container, or after a leaf") {
        val doc = SpecEdit.parse(golden("battery"))
        val (inside, at) = SpecEdit.add(doc, listOf("1"), SpecEdit.NEW_PARTS.getValue("Text")())!!
        eq(listOf("1", "3"), at)
        eq("New text", SpecEdit.node(inside, at)!!["text"])
        val (after, at2) = SpecEdit.add(doc, listOf("0"), SpecEdit.NEW_PARTS.getValue("Icon")())!!
        eq(listOf("1"), at2)
        eq("icon", SpecEdit.node(after, at2)!!["t"])
        yes(valid(inside) && valid(after))
        for ((name, make) in SpecEdit.NEW_PARTS) {
            val (d, _) = SpecEdit.add(doc, emptyList(), make())!!
            yes(valid(d), "adding $name breaks the widget")
        }
    }
    test("name and theme are set at the top") {
        val doc = SpecEdit.parse(golden("battery"))
        val named = SpecEdit.setTop(doc, "name", "My battery")
        eq("My battery", named["name"])
        val themed = SpecEdit.setTop(named, "theme.palette", "custom")
        @Suppress("UNCHECKED_CAST")
        eq("custom", (themed["theme"] as Map<String, Any?>)["palette"])
        eq(null, SpecEdit.setTop(themed, "theme.palette", null)["theme"])
    }
    test("the new tonal colours are accepted") {
        val doc = SpecEdit.set(SpecEdit.parse(golden("battery")), emptyList(), "bg", "secondaryContainer")!!
        yes(valid(doc))
    }
    test("parts carry a stable kind and the person's own words, for translated screens") {
        val parts = SpecEdit.parts(SpecEdit.parse(golden("battery")))
        eq("row", parts[0].kind)
        eq("ring", parts[1].kind)
        eq(null, parts[1].text)
        eq(listOf("text", "text", "text"), parts.drop(3).map { it.kind })
        eq("{num(bat.level, 0)}%", parts[3].text)
        val chores = SpecEdit.parts(SpecEdit.parse(golden("weekly-chores")))
        eq("check", chores[2].kind)
        eq("Laundry", chores[2].text)
        val news = SpecEdit.parts(SpecEdit.parse(golden("headlines")))
        yes(news.any { it.kind == "list" && it.detail.isNotEmpty() }, "a list names its source")
        val dash = SpecEdit.parts(SpecEdit.parse(golden("morning-dashboard")))
        yes(dash.any { it.kind == "icon" && it.detail.isNotEmpty() }, "an icon carries its name")
        yes(dash.any { it.kind == "divider" })
        val card = SpecEdit.set(SpecEdit.parse(golden("battery")), listOf("1"), "bg", "primaryContainer")!!
        eq("card", SpecEdit.parts(card)[2].kind)
    }
    test("a dragged part lands among its own siblings") {
        val doc = SpecEdit.parse(golden("weekly-chores"))
        val parts = SpecEdit.parts(doc)
        // rows: 0 root, 1 {done} (path 0), 2 Laundry (1), 3 Bins out (2), 4 Water plants (3), 5 All done (4)
        eq(3, SpecEdit.dropIndex(parts, listOf("1"), 4), "dropped on Water plants")
        eq(0, SpecEdit.dropIndex(parts, listOf("1"), 0), "above all the siblings means first")
        eq(4, SpecEdit.dropIndex(parts, listOf("1"), 5), "the last row is the last place")
        eq(null, SpecEdit.dropIndex(parts, emptyList(), 2), "the root has no siblings")
        eq(4, SpecEdit.dropIndex(parts, listOf("1"), 99), "far below is the last place")
        val moved = SpecEdit.moveTo(doc, listOf("1"), 3)!!
        eq("Bins out", SpecEdit.node(moved, listOf("1"))!!["label"])
        eq("Water plants", SpecEdit.node(moved, listOf("2"))!!["label"])
        eq("Laundry", SpecEdit.node(moved, listOf("3"))!!["label"])
        yes(valid(moved))
        eq(null, SpecEdit.moveTo(doc, listOf("1"), 9), "past the end")
        eq(null, SpecEdit.moveTo(doc, emptyList(), 1), "the root stays")
    }
    test("dropping on a row inside another part means that part's place") {
        val doc = SpecEdit.parse(golden("morning-dashboard"))
        val parts = SpecEdit.parts(doc)
        // rows: 0 root, 1 row, 2 icon, 3 text, 4 divider, 5 row, 6 text, 7 button, 8 button
        eq(2, SpecEdit.dropIndex(parts, listOf("0"), 7), "a row inside the third part is the third place")
        eq(0, SpecEdit.dropIndex(parts, listOf("2"), 3))
        eq(1, SpecEdit.dropIndex(parts, listOf("2"), 4))
        // Inside one row, a button moves among that row's own kids only.
        eq(0, SpecEdit.dropIndex(parts, listOf("2", "1"), 1), "above the row's kids means first")
        eq(2, SpecEdit.dropIndex(parts, listOf("2", "1"), 8))
        val swapped = SpecEdit.moveTo(doc, listOf("2", "1"), 2)!!
        eq("Maps", SpecEdit.node(swapped, listOf("2", "1"))!!["label"])
        yes(valid(swapped))
    }
}
