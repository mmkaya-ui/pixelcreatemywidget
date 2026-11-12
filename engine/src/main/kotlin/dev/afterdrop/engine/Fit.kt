package dev.afterdrop.engine

/** A tree that fits its widget, and how much the renderer should shrink text and padding. */
class Fitted(val root: RNode, val scale: Int) {
    val textFactor: Double get() = Fit.TEXT[scale]
    val padFactor: Double get() = Fit.PAD[scale]
}

/**
 * Keeps a widget from overflowing its height. A widget cannot scroll and the launcher clips what
 * does not fit, so half a row or an unreachable button would show. This pass estimates heights and,
 * when the tree is too tall, first shrinks text and padding, then drops whole nodes: decoration
 * first, then plain text, and controls last, so what remains is complete and usable.
 * Estimates are deliberately a little generous; the emulator tests check the real result.
 */
object Fit {
    val TEXT = doubleArrayOf(1.0, 0.8, 0.65)
    val PAD = doubleArrayOf(1.0, 0.75, 0.5)
    const val MIN_EDGE = 8
    const val BUTTON = 48.0
    const val CONTROL = 40.0
    private val DECOR = setOf("progress", "chart", "image", "divider", "spacer", "icon")
    private val CONTROLS = setOf("button", "toggle", "check", "stepper")

    /** Real text runs a little taller than these estimates on some fonts; leave this much spare. */
    private const val SLACK = 3

    /**
     * @param fontScale the system font size setting; larger text needs more room.
     */
    fun fit(root: RNode, widthDp: Int, heightDp: Int, fontScale: Double = 1.0): Fitted {
        val fs = fontScale.coerceIn(0.8, 2.0)
        val narrowed = narrow(root, widthDp.toDouble(), fs, true)
        val room = heightDp - SLACK
        val w = widthDp.toDouble()
        for (s in 0..2) if (height(narrowed, s, true, fs, w) <= room) return Fitted(narrowed, s)
        val pruned = shrink(narrowed, room.toDouble(), 2, true, fs, w)
        val scale = (0..2).firstOrNull { height(pruned, it, true, fs, w) <= room } ?: 2
        return Fitted(pruned, scale)
    }

    fun pad(n: RNode, scale: Int, isRoot: Boolean): Double {
        val p = (n.node.int("pad") ?: 0) * PAD[scale]
        return if (isRoot) Math.max(p, MIN_EDGE.toDouble()) else p
    }

    private fun sp(style: String?): Double = when (style) {
        "display" -> 36.0
        "headline" -> 24.0
        "title" -> 18.0
        "label" -> 12.0
        "caption" -> 11.0
        else -> 14.0
    }

    /** One line of text is about 1.35 times its font size. */
    private fun line(style: String?, scale: Int, fs: Double): Double = sp(style) * 1.35 * TEXT[scale] * fs

    /** Text without a stated limit may wrap onto this many lines. The renderer uses the same number. */
    const val DEFAULT_LINES = 2

    /**
     * Estimated height in dp.
     * @param w the width this node has, so wrapped text is counted as more than one line.
     */
    fun height(n: RNode, scale: Int, isRoot: Boolean = false, fs: Double = 1.0, w: Double = Double.MAX_VALUE): Double {
        n.node.int("h")?.let { return it.toDouble() }
        val grows = (n.node.int("grow") ?: 0) > 0
        val gap = n.node.int("gap") ?: 0
        val inside = w - 2 * pad(n, scale, isRoot)
        val inner = when (n.type) {
            "text" -> {
                val long = (n.text?.length ?: 0) * sp(n.node.text("style")) * 0.55 * fs * TEXT[scale]
                val lines = Math.ceil(long / Math.max(inside, 28.0)).toInt().coerceIn(1, n.node.int("lines") ?: DEFAULT_LINES)
                lines * line(n.node.text("style"), scale, fs)
            }
            "clock", "timer" -> line(n.node.text("style"), scale, fs)
            "icon" -> (n.node.int("iconSize") ?: 24) * 1.1 * TEXT[scale] * fs
            "progress" -> if (n.node.text("shape") == "ring") (if (grows) 32.0 else 48.0) else 6.0
            "chart" -> if (grows) 24.0 else 40.0
            "button" -> BUTTON
            "toggle", "check", "stepper" -> CONTROL
            "divider" -> 1.0
            "list" -> when {
                n.kids.isEmpty() -> line("body", scale, fs)
                grows -> height(n.kids[0], scale, false, fs, inside)
                else -> n.kids.sumOf { height(it, scale, false, fs, inside) } + gap * (n.kids.size - 1)
            }
            "col" -> n.kids.sumOf { height(it, scale, false, fs, inside) } + gap * Math.max(0, n.kids.size - 1)
            "box" -> n.kids.maxOfOrNull { height(it, scale, false, fs, inside) } ?: 0.0
            "row" -> {
                // Each child gets what its siblings leave over.
                val widths = n.kids.map { width(it, fs) }
                val spare = inside - widths.sum() - gap * Math.max(0, n.kids.size - 1)
                n.kids.indices.maxOfOrNull { height(n.kids[it], scale, false, fs, Math.max(widths[it] + Math.max(spare, 0.0), 28.0)) } ?: 0.0
            }
            else -> 0.0
        }
        return inner + 2 * pad(n, scale, isRoot)
    }

    private fun interactive(n: RNode): Boolean = n.all().any { it.type in CONTROLS }

    private val ROUNDS: List<(RNode) -> Boolean> = listOf({ it.type in DECOR }, { !interactive(it) }, { true })

    /** Estimated least width in dp. Text can wrap or shorten, so it never asks for more than 28. */
    fun width(n: RNode, fs: Double, isRoot: Boolean = false): Double {
        n.node.int("w")?.let { return it.toDouble() }
        val grows = (n.node.int("grow") ?: 0) > 0
        val gap = n.node.int("gap") ?: 0
        fun chars(text: String?, size: Double) = (text?.length ?: 0) * size * 0.55 * fs
        val inner = when (n.type) {
            "text", "clock", "timer" -> Math.min(chars(n.text ?: n.node.text("format") ?: "00:00", sp(n.node.text("style"))), 28.0)
            "icon" -> (n.node.int("iconSize") ?: 24) * 1.1 * fs
            "progress" -> if (n.node.text("shape") == "ring") (if (grows) 32.0 else 48.0) else 24.0
            "chart" -> 40.0
            // A label can shorten, so a control never asks for its full label width.
            "button" -> Math.max(48.0, Math.min(chars(n.text, 14.0) + 32, 96.0))
            // A switch or checkbox keeps its whole label and squeezes its neighbours instead.
            "toggle", "check" -> 52 + chars(n.text, 14.0)
            "stepper" -> 136.0
            "list" -> 56.0
            "row" -> n.kids.sumOf { width(it, fs) } + gap * Math.max(0, n.kids.size - 1)
            "col", "box" -> n.kids.maxOfOrNull { width(it, fs) } ?: 0.0
            else -> 0.0
        }
        return inner + 2 * pad(n, 0, isRoot)
    }

    /** Drops children of rows that cannot fit side by side, so nothing is squeezed to zero width. */
    private fun narrow(n: RNode, available: Double, fs: Double, isRoot: Boolean): RNode {
        if (n.kids.isEmpty() || n.type == "list") return n
        val inside = available - 2 * pad(n, 0, isRoot)
        if (n.type != "row") {
            // In a column, a child wider than the widget would be cut off; keep the ones that fit.
            val narrowed = n.kids.map { narrow(it, inside, fs, false) }
            val fits = narrowed.filter { width(it, fs) <= inside || (interactive(it) && it.type != "stepper" && inside >= 48) }
            return n.withKids(if (fits.isNotEmpty()) fits else narrowed.take(1))
        }
        val kids = n.kids.toMutableList()
        val gap = n.node.int("gap") ?: 0
        fun total() = kids.sumOf { width(it, fs) } + gap * Math.max(0, kids.size - 1)
        for (droppable in ROUNDS) {
            while (kids.size > 1 && total() > inside) {
                val i = kids.indexOfLast(droppable)
                if (i < 0) break
                kids.removeAt(i)
            }
        }
        val share = if (kids.size == 1) inside else inside / kids.size
        return n.withKids(kids.map { narrow(it, Math.max(share, width(it, fs)), fs, false) })
    }

    private fun shrink(n: RNode, available: Double, scale: Int, isRoot: Boolean, fs: Double, w: Double): RNode {
        if (height(n, scale, isRoot, fs, w) <= available) return n
        val inside = available - 2 * pad(n, scale, isRoot)
        val wide = w - 2 * pad(n, scale, isRoot)
        return when (n.type) {
            "row", "box" -> n.withKids(n.kids.map { shrink(it, inside, scale, false, fs, wide) })
            "col" -> {
                val kids = n.kids.toMutableList()
                val gap = n.node.int("gap") ?: 0
                fun total() = kids.sumOf { height(it, scale, false, fs, wide) } + gap * Math.max(0, kids.size - 1)
                for (droppable in ROUNDS) {
                    while (kids.size > 1 && total() > inside) {
                        val i = kids.indexOfLast(droppable)
                        if (i < 0) break
                        kids.removeAt(i)
                    }
                }
                if (kids.size == 1 && total() > inside) kids[0] = shrink(kids[0], inside, scale, false, fs, wide)
                n.withKids(kids)
            }
            else -> n
        }
    }
}
