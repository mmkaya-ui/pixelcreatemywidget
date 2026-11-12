package dev.afterdrop.composer

import dev.afterdrop.formula.Library
import dev.afterdrop.spec.Vocabulary

/**
 * A ready-made prompt for any chat assistant. The person pastes it into the assistant they already
 * use, then pastes the JSON answer back into the app, where it goes through the usual checks.
 * The lists come from the live vocabulary, so the prompt cannot drift from what the app accepts.
 */
object ChatPrompt {
    fun build(description: String): String {
        val ask = description.trim().take(500).ifEmpty { "(describe the widget here)" }
        val props = Vocabulary.PROPS.entries.joinToString("\n") { (type, p) ->
            "- $type: " + (p - setOf("t", "id", "grow", "w", "h", "bg", "radius", "from", "if", "tap", "pad")).joinToString(", ").ifEmpty { "(no own properties)" }
        }
        return """
You write home-screen widget descriptions as JSON in the "WidgetSpec v1" format. Reply with one JSON object only: no explanation, no code fence.

THE WIDGET I WANT
$ask

FORMAT
Top level: "v": 1, "name" (1-40 chars), optional "about", "size": {"cols": 1-5, "rows": 1-5}, optional "theme", "sources", "vars", "calc", and "root" (one node).
- "sources": up to 6, each name: {"use": one of ${Vocabulary.SOURCE_KINDS.joinToString(", ")}, optional "with": {...}, optional "every": one of ${Vocabulary.INTERVALS.joinToString(", ")}}.
- "vars": up to 8, each name: {"type": int|num|bool|text, "init": value, optional "reset": never|daily|weekly}.
- "calc": up to 12 named formulas.
Names are short camelCase (like wx, stepsToday) and unique across sources, vars and calc.

NODES
Every node has "t" (its type). Any node may also have: id, grow (0-4), w and h ("wrap", "fill" or dp 8-400), bg (a colour role or "none"), radius (0-28), pad (0-24), from ("xs", "s", "m" or "l": show only at that widget size or larger), if (a formula), tap (an action).
Own properties by type:
$props
Rules: col, row and box need "kids" (at most 10). list needs "of" (a formula giving a list) and "item" (one node, where the current element is called it); a list cannot contain a list. text needs "text". button needs "label" and "tap". toggle and check need a bool "var"; stepper needs a number "var". chart "kind" is spark or bars; button "kind" is filled, tonal or plain. At most 5 levels deep and 60 nodes.
Values: gap 0-24; align start|center|end|between; cross start|center|end; style ${Vocabulary.TEXT_STYLES.joinToString("|")}; lines 1-6; shape bar|ring; mode down|up; iconSize 12-64; limit 1-20; "icon" is one of the names under ICONS.
Colours are roles, never hex: ${Vocabulary.ROLES.joinToString(", ")}.

SOURCES AND THEIR FIELDS
Read a source by its name, for example {wx.now.temp}. Only these fields exist; any other field is refused.
${dev.afterdrop.spec.SourceShapes.describe()}
Times are epoch milliseconds: show one with fmt(t, 'HH:mm'). A list field marked [] needs [index], first(), pluck(list, 'field') or a "list" node.

ICONS
${dev.afterdrop.spec.IconNames.ALL.joinToString(" ")}

TEXT AND FORMULAS
"text", "label" and "empty" are templates: plain text with {formula} parts. Write {{ and }} for literal braces.
"if", "value", "values", "to" and "of" are formulas. Operators: + - * / % == != < <= > >= && || ! and a ? b : c. Strings use single quotes. Read data with dots and [index].
Functions (nothing else exists): ${Library.names.joinToString(", ")}.
Times are epoch milliseconds: now(), today(), date('2026-12-24'), days(a, b), fmt(t, 'HH:mm').
A "clock" node shows the live time or date by itself: {"t": "clock", "format": "HH:mm"} or {"t": "clock", "format": "EEE d MMM"}. For another city give it "zone", for example "Asia/Tokyo"; now() and fmt() always use the phone's own zone, so show a date or time in another zone with a clock node, never with text.
Round numbers you show: {num(wx.now.temp, 0)}.

ACTIONS (for "tap")
{"do": "refresh"} | {"do": "edit"} | {"do": "open_url", "url": "https://..."} | {"do": "open_app", "pkg": "com.example.app"} | {"do": "open_source", "source": name} | {"do": "set", "var": name, "to": formula} | {"do": "ask", "var": name, "title": "..."}

PRIVACY RULE
Parameters of http, feed and ai sources must not use values from calendar, health, notes or media sources.

EXAMPLE
{"v":1,"name":"Water today","size":{"cols":2,"rows":2},"vars":{"glasses":{"type":"int","init":0,"reset":"daily"}},"calc":{"goal":"8","done":"clamp(glasses / goal, 0, 1)"},"root":{"t":"col","pad":12,"gap":8,"cross":"center","kids":[{"t":"progress","shape":"ring","value":"done","color":"primary","grow":1},{"t":"text","text":"{glasses} of {goal} glasses","style":"title"},{"t":"stepper","var":"glasses","step":1,"min":0,"max":20,"label":"Glasses"}]}}
""".trim()
    }
}
