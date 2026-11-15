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
}
