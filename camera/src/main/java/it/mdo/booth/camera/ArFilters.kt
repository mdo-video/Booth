package it.mdo.booth.camera

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.atan2
import kotlin.math.hypot

/** Un volto, con coordinate da 0 a 1 rispetto all'immagine dritta. Gli occhi sono ordinati da sinistra a destra. */
class ArFace(
    val cx: Float, val cy: Float, val w: Float, val h: Float,
    val lx: Float, val ly: Float, val rx: Float, val ry: Float,
) {
    fun lerp(o: ArFace, f: Float) = ArFace(
        cx + (o.cx - cx) * f, cy + (o.cy - cy) * f, w + (o.w - w) * f, h + (o.h - h) * f,
        lx + (o.lx - lx) * f, ly + (o.ly - ly) * f, rx + (o.rx - rx) * f, ry + (o.ry - ry) * f,
    )

    fun distTo(o: ArFace) = hypot(cx - o.cx, cy - o.cy)
}

/** Volti rilevati in un certo momento della registrazione (ms dall'inizio). */
class ArTrackPt(val tMs: Long, val faces: List<ArFace>)

/**
 * Filtri AR disegnati nel codice (niente immagini): ogni filtro è definito in un riferimento locale
 * con l'origine a metà degli occhi, l'asse x lungo la linea degli occhi e l'unità = distanza tra gli occhi.
 * Così si adatta da solo a grandezza e inclinazione della testa.
 */
object ArFilters {
    val ids = listOf("ar_crown", "ar_glasses", "ar_mustache", "ar_bunny", "ar_hearts")

    fun isAr(id: String) = id.startsWith("ar_")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Segue i volti di un'immagine alla successiva, ammorbidendo i salti. */
    fun smooth(prev: List<ArFace>, cur: List<ArFace>, k: Float = 0.65f): List<ArFace> {
        if (prev.isEmpty()) return cur
        return cur.map { c ->
            val p = prev.minByOrNull { it.distTo(c) }
            if (p != null && p.distTo(c) < 0.15f) p.lerp(c, k) else c
        }
    }

    /** Volti al tempo t, interpolando tra due rilevamenti vicini. Vuoto se non ce ne sono (meno di mezzo secondo). */
    fun at(track: List<ArTrackPt>, tMs: Long): List<ArFace> {
        if (track.isEmpty()) return emptyList()
        var lo = 0
        var hi = track.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (track[mid].tMs <= tMs) lo = mid else hi = mid - 1
        }
        val a = track[lo]
        val b = track.getOrNull(lo + 1)
        if (tMs < a.tMs && lo == 0) return if (a.tMs - tMs < 400) a.faces else emptyList()
        if (b == null || b.tMs - a.tMs > 500) return if (tMs - a.tMs < 400) a.faces else emptyList()
        val f = ((tMs - a.tMs).toFloat() / (b.tMs - a.tMs).coerceAtLeast(1)).coerceIn(0f, 1f)
        return a.faces.map { fa ->
            val fb = b.faces.minByOrNull { it.distTo(fa) }
            if (fb != null && fb.distTo(fa) < 0.15f) fa.lerp(fb, f) else fa
        }
    }

    fun draw(canvas: Canvas, id: String, faces: List<ArFace>, w: Int, h: Int) {
        for (f in faces) {
            val lx = f.lx * w
            val ly = f.ly * h
            val rx = f.rx * w
            val ry = f.ry * h
            val d = hypot(rx - lx, ry - ly)
            if (d < 6f) continue
            val deg = Math.toDegrees(atan2((ry - ly).toDouble(), (rx - lx).toDouble())).toFloat()
            canvas.save()
            canvas.translate((lx + rx) / 2f, (ly + ry) / 2f)
            canvas.rotate(deg)
            canvas.scale(d, d)
            when (id) {
                "ar_crown" -> crown(canvas)
                "ar_glasses" -> glasses(canvas)
                "ar_mustache" -> mustache(canvas)
                "ar_bunny" -> bunny(canvas)
                "ar_hearts" -> hearts(canvas)
            }
            canvas.restore()
        }
    }

    private fun fillPath(c: Canvas, p: Path, color: Int) {
        fill.color = color
        c.drawPath(p, fill)
    }

    private fun strokePath(c: Canvas, p: Path, color: Int, width: Float) {
        line.color = color
        line.strokeWidth = width
        c.drawPath(p, line)
    }

    private fun crown(c: Canvas) {
        val p = Path().apply {
            moveTo(-0.80f, -1.15f)
            lineTo(-0.85f, -1.80f)
            lineTo(-0.42f, -1.45f)
            lineTo(0f, -2.05f)
            lineTo(0.42f, -1.45f)
            lineTo(0.85f, -1.80f)
            lineTo(0.80f, -1.15f)
            close()
        }
        fillPath(c, p, 0xFFFFC107.toInt())
        strokePath(c, p, 0xFFB8860B.toInt(), 0.05f)
        // fascia
        val band = Path().apply {
            moveTo(-0.81f, -1.30f); lineTo(0.81f, -1.30f); lineTo(0.80f, -1.15f); lineTo(-0.80f, -1.15f); close()
        }
        fillPath(c, band, 0xFFE0A100.toInt())
        // gemme
        fill.color = 0xFFE53935.toInt(); c.drawCircle(0f, -1.22f, 0.065f, fill)
        fill.color = 0xFF1E88E5.toInt(); c.drawCircle(-0.42f, -1.22f, 0.05f, fill)
        fill.color = 0xFF1E88E5.toInt(); c.drawCircle(0.42f, -1.22f, 0.05f, fill)
        fill.color = 0xFFFFF59D.toInt()
        c.drawCircle(0f, -2.05f, 0.06f, fill)
        c.drawCircle(-0.85f, -1.80f, 0.055f, fill)
        c.drawCircle(0.85f, -1.80f, 0.055f, fill)
    }

    private fun glasses(c: Canvas) {
        val lens = 0xF0141414.toInt()
        for (s in listOf(-1f, 1f)) {
            val r = RectF(if (s < 0) -0.95f else 0.07f, -0.30f, if (s < 0) -0.07f else 0.95f, 0.32f)
            fill.color = lens
            c.drawRoundRect(r, 0.22f, 0.22f, fill)
            // riflesso
            line.color = 0x66FFFFFF
            line.strokeWidth = 0.05f
            val ox = if (s < 0) -0.80f else 0.22f
            c.drawLine(ox, -0.15f, ox + 0.18f, -0.22f, line)
            // bordo
            line.color = 0xFF000000.toInt()
            line.strokeWidth = 0.045f
            val rr = Path().apply { addRoundRect(r, 0.22f, 0.22f, Path.Direction.CW) }
            c.drawPath(rr, line)
        }
        line.color = 0xFF000000.toInt()
        line.strokeWidth = 0.07f
        c.drawLine(-0.07f, -0.12f, 0.07f, -0.12f, line)            // ponte
        c.drawLine(-0.95f, -0.18f, -1.30f, -0.12f, line)           // stanghette
        c.drawLine(0.95f, -0.18f, 1.30f, -0.12f, line)
    }

    private fun mustache(c: Canvas) {
        val p = Path().apply {
            moveTo(0f, 0.90f)
            cubicTo(0.20f, 0.80f, 0.58f, 0.82f, 0.90f, 1.14f)
            cubicTo(0.72f, 1.34f, 0.40f, 1.22f, 0f, 1.08f)
            cubicTo(-0.40f, 1.22f, -0.72f, 1.34f, -0.90f, 1.14f)
            cubicTo(-0.58f, 0.82f, -0.20f, 0.80f, 0f, 0.90f)
            close()
        }
        fillPath(c, p, 0xFF2B1B12.toInt())
    }

    private fun bunny(c: Canvas) {
        for (s in listOf(-1f, 1f)) {
            c.save()
            c.translate(0.55f * s, -2.15f)
            c.rotate(12f * s)
            fill.color = 0xFFF7F7F7.toInt()
            c.drawOval(RectF(-0.33f, -0.90f, 0.33f, 0.90f), fill)
            line.color = 0xFFBDBDBD.toInt()
            line.strokeWidth = 0.04f
            c.drawOval(RectF(-0.33f, -0.90f, 0.33f, 0.90f), line)
            fill.color = 0xFFF8BBD0.toInt()
            c.drawOval(RectF(-0.17f, -0.68f, 0.17f, 0.68f), fill)
            c.restore()
        }
    }

    private fun heart(c: Canvas, x: Float, y: Float, s: Float) {
        val p = Path().apply {
            moveTo(x, y + 0.9f * s)
            cubicTo(x - 1.4f * s, y - 0.1f * s, x - 0.7f * s, y - 1.0f * s, x, y - 0.35f * s)
            cubicTo(x + 0.7f * s, y - 1.0f * s, x + 1.4f * s, y - 0.1f * s, x, y + 0.9f * s)
            close()
        }
        fillPath(c, p, 0xFFE91E63.toInt())
        strokePath(c, p, 0xFFAD1457.toInt(), 0.03f)
        fill.color = 0x88FFFFFF.toInt()
        c.drawCircle(x - 0.45f * s, y - 0.35f * s, 0.14f * s, fill)
    }

    private fun hearts(c: Canvas) {
        heart(c, -0.5f, 0f, 0.36f)
        heart(c, 0.5f, 0f, 0.36f)
    }
}
