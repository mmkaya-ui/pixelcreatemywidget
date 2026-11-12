package dev.afterdrop.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Small bitmaps for the two things Glance cannot draw: a progress ring and a chart. */
object Painter {
    /** 160 px square, about 100 KB: far under the RemoteViews bitmap budget. */
    fun ring(fraction: Float, color: Int, track: Int): Bitmap {
        val size = 160
        val stroke = 18f
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        val r = RectF(stroke / 2, stroke / 2, size - stroke / 2, size - stroke / 2)
        p.color = track
        c.drawArc(r, 0f, 360f, false, p)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) {
            p.color = color
            c.drawArc(r, -90f, 360f * f, false, p)
        }
        return bmp
    }

    /** 320 x 120 px. [kind] is "bars" or "spark". An empty or flat series still draws a baseline. */
    fun chart(kind: String, values: List<Double>, min: Double?, max: Double?, color: Int, track: Int): Bitmap {
        val w = 320
        val h = 120
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val data = values.filter { !it.isNaN() && !it.isInfinite() }.takeLast(48)
        p.color = track
        c.drawRect(0f, h - 2f, w.toFloat(), h.toFloat(), p)
        if (data.isEmpty()) return bmp
        val lo = min ?: Math.min(data.min(), if (kind == "bars") 0.0 else data.min())
        var hi = max ?: data.max()
        if (hi <= lo) hi = lo + 1.0
        fun y(v: Double): Float = (h - 4 - ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0) * (h - 8)).toFloat()
        p.color = color
        if (kind == "bars") {
            val slot = w.toFloat() / data.size
            val gap = Math.min(6f, slot * 0.2f)
            for ((i, v) in data.withIndex()) {
                val top = Math.min(y(v), h - 6f)
                c.drawRoundRect(i * slot + gap / 2, top, (i + 1) * slot - gap / 2, h.toFloat(), 4f, 4f, p)
            }
        } else {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 5f
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            if (data.size == 1) {
                c.drawLine(0f, y(data[0]), w.toFloat(), y(data[0]), p)
            } else {
                val path = Path()
                val step = (w - 6f) / (data.size - 1)
                for ((i, v) in data.withIndex()) {
                    if (i == 0) path.moveTo(3f, y(v)) else path.lineTo(3f + i * step, y(v))
                }
                c.drawPath(path, p)
            }
        }
        return bmp
    }
}
