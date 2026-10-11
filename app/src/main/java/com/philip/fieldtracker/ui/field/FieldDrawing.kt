package com.philip.fieldtracker.ui.field

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.philip.fieldtracker.data.FieldLayout
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.sqrt

/** Dimensions du gabarit de dessin (en mètres) pour chaque tracé. */
fun templateSize(layout: FieldLayout, fallbackL: Double, fallbackW: Double): Pair<Double, Double> = when (layout) {
    FieldLayout.SOCCER -> 105.0 to 68.0
    FieldLayout.FUTSAL, FieldLayout.HANDBALL -> 40.0 to 20.0
    FieldLayout.RUGBY -> 120.0 to 70.0
    FieldLayout.AMERICAN -> 109.7 to 48.8
    FieldLayout.GAELIC -> 140.0 to 85.0
    FieldLayout.FIELD_HOCKEY -> 91.4 to 55.0
    FieldLayout.ICE_HOCKEY -> 60.0 to 30.0
    FieldLayout.BASKETBALL -> 28.0 to 15.0
    FieldLayout.BASKET_HALF -> 15.0 to 11.0
    FieldLayout.NETBALL -> 30.5 to 15.25
    FieldLayout.TENNIS -> 23.77 to 10.97
    FieldLayout.PADEL -> 20.0 to 10.0
    FieldLayout.BADMINTON -> 13.4 to 6.1
    FieldLayout.PICKLEBALL -> 13.41 to 6.1
    FieldLayout.VOLLEY -> 18.0 to 9.0
    FieldLayout.TABLE_TENNIS -> 2.74 to 1.525
    FieldLayout.SQUASH -> 9.75 to 6.4
    FieldLayout.BASEBALL -> 120.0 to 120.0
    FieldLayout.CRICKET -> 150.0 to 130.0
    FieldLayout.OVAL -> 165.0 to 135.0
    FieldLayout.TRACK -> 176.9 to 92.5
    FieldLayout.ULTIMATE -> 100.0 to 37.0
    FieldLayout.GENERIC, FieldLayout.GENERIC_THIRDS -> fallbackL to fallbackW
}

/** Petit traceur : coordonnées du gabarit (m) -> pixels dans [rect]. */
private class Pen(
    val ds: DrawScope,
    val rect: Rect,
    val tL: Double,
    val tW: Double,
    val color: Color,
    val stroke: Float,
) {
    val sx = rect.width / tL.toFloat()
    val sy = rect.height / tW.toFloat()

    fun x(m: Double) = rect.left + (m * sx).toFloat()
    fun y(m: Double) = rect.top + (m * sy).toFloat()
    fun p(mx: Double, my: Double) = Offset(x(mx), y(my))

    fun line(x1: Double, y1: Double, x2: Double, y2: Double, w: Float = stroke, dashed: Boolean = false, c: Color = color) {
        ds.drawLine(
            c, p(x1, y1), p(x2, y2), strokeWidth = w,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(w * 4, w * 3)) else null
        )
    }

    fun rect(x1: Double, y1: Double, x2: Double, y2: Double, w: Float = stroke) {
        ds.drawRect(color, topLeft = p(x1, y1), size = Size(x(x2) - x(x1), y(y2) - y(y1)), style = Stroke(w))
    }

    fun circle(cx: Double, cy: Double, r: Double, w: Float = stroke, dashed: Boolean = false) {
        ds.drawOval(
            color,
            topLeft = Offset(x(cx - r), y(cy - r)),
            size = Size((2 * r * sx).toFloat(), (2 * r * sy).toFloat()),
            style = Stroke(w, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(w * 4, w * 3)) else null)
        )
    }

    fun dot(cx: Double, cy: Double, rPx: Float = stroke * 1.6f) {
        ds.drawCircle(color, rPx, p(cx, cy))
    }

    /** Arc de cercle ; angles en degrés, 0 = vers la droite, sens horaire (axe y vers le bas). */
    fun arc(cx: Double, cy: Double, r: Double, start: Float, sweep: Float, w: Float = stroke, dashed: Boolean = false) {
        ds.drawArc(
            color, start, sweep, useCenter = false,
            topLeft = Offset(x(cx - r), y(cy - r)),
            size = Size((2 * r * sx).toFloat(), (2 * r * sy).toFloat()),
            style = Stroke(w, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(w * 4, w * 3)) else null)
        )
    }

    /** Zone "en D" (futsal, handball, hockey) : deux quarts de cercle depuis les poteaux + segment. */
    fun dArea(left: Boolean, r: Double, postHalf: Double, dashed: Boolean = false) {
        val cy = tW / 2
        if (left) {
            arc(0.0, cy - postHalf, r, -90f, 90f, dashed = dashed)
            arc(0.0, cy + postHalf, r, 0f, 90f, dashed = dashed)
            line(r, cy - postHalf, r, cy + postHalf, dashed = dashed)
        } else {
            arc(tL, cy - postHalf, r, 180f, 90f, dashed = dashed)
            arc(tL, cy + postHalf, r, 90f, 90f, dashed = dashed)
            line(tL - r, cy - postHalf, tL - r, cy + postHalf, dashed = dashed)
        }
    }

    /** Contour en forme de piste (deux demi-cercles + lignes droites) centré dans le gabarit. */
    fun stadium(radius: Double, straight: Double, w: Float = stroke) {
        val cy = tW / 2
        val x1 = (tL - straight) / 2
        val x2 = x1 + straight
        line(x1, cy - radius, x2, cy - radius, w)
        line(x1, cy + radius, x2, cy + radius, w)
        arc(x1, cy, radius, 90f, 180f, w)
        arc(x2, cy, radius, -90f, 180f, w)
    }
}

/**
 * Dessine les lignes du terrain dans [rect].
 * Les positions (u, v) dans [0..1] se placent ensuite directement sur [rect].
 */
fun DrawScope.drawFieldMarkings(
    layout: FieldLayout,
    rect: Rect,
    lengthM: Double,
    widthM: Double,
    color: Color,
    strokePx: Float,
) {
    val (tL, tW) = templateSize(layout, lengthM, widthM)
    val pen = Pen(this, rect, tL, tW, color, strokePx)
    val cx = tL / 2
    val cy = tW / 2
    val thin = strokePx * 0.7f

    val roundOutline = layout == FieldLayout.OVAL || layout == FieldLayout.CRICKET ||
        layout == FieldLayout.TRACK || layout == FieldLayout.BASEBALL || layout == FieldLayout.ICE_HOCKEY

    // Contour (rectangle balisé)
    if (roundOutline) {
        drawRect(color.copy(alpha = color.alpha * 0.35f), rect.topLeft, rect.size, style = Stroke(thin, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
    } else {
        drawRect(color, rect.topLeft, rect.size, style = Stroke(strokePx * 1.2f))
    }

    when (layout) {
        FieldLayout.SOCCER -> {
            pen.line(cx, 0.0, cx, tW)
            pen.circle(cx, cy, 9.15)
            pen.dot(cx, cy)
            pen.rect(0.0, cy - 20.16, 16.5, cy + 20.16)
            pen.rect(tL - 16.5, cy - 20.16, tL, cy + 20.16)
            pen.rect(0.0, cy - 9.16, 5.5, cy + 9.16)
            pen.rect(tL - 5.5, cy - 9.16, tL, cy + 9.16)
            pen.dot(11.0, cy); pen.dot(tL - 11.0, cy)
            val a = Math.toDegrees(acos(5.5 / 9.15)).toFloat()
            pen.arc(11.0, cy, 9.15, -a, 2 * a)
            pen.arc(tL - 11.0, cy, 9.15, 180f - a, 2 * a)
            pen.arc(0.0, 0.0, 1.0, 0f, 90f)
            pen.arc(tL, 0.0, 1.0, 90f, 90f)
            pen.arc(tL, tW, 1.0, 180f, 90f)
            pen.arc(0.0, tW, 1.0, 270f, 90f)
        }

        FieldLayout.FUTSAL -> {
            pen.line(cx, 0.0, cx, tW)
            pen.circle(cx, cy, 3.0)
            pen.dot(cx, cy)
            pen.dArea(true, 6.0, 1.58); pen.dArea(false, 6.0, 1.58)
            pen.dot(6.0, cy); pen.dot(tL - 6.0, cy)
            pen.dot(10.0, cy); pen.dot(tL - 10.0, cy)
        }

        FieldLayout.HANDBALL -> {
            pen.line(cx, 0.0, cx, tW)
            pen.circle(cx, cy, 3.0)
            pen.dArea(true, 6.0, 1.5); pen.dArea(false, 6.0, 1.5)
            pen.dArea(true, 9.0, 1.5, dashed = true); pen.dArea(false, 9.0, 1.5, dashed = true)
            pen.line(7.0, cy - 0.5, 7.0, cy + 0.5); pen.line(tL - 7.0, cy - 0.5, tL - 7.0, cy + 0.5)
        }

        FieldLayout.RUGBY -> {
            pen.line(10.0, 0.0, 10.0, tW, strokePx * 1.4f)
            pen.line(tL - 10.0, 0.0, tL - 10.0, tW, strokePx * 1.4f)
            pen.line(cx, 0.0, cx, tW)
            pen.line(32.0, 0.0, 32.0, tW)
            pen.line(tL - 32.0, 0.0, tL - 32.0, tW)
            pen.line(cx - 10.0, 0.0, cx - 10.0, tW, thin, dashed = true)
            pen.line(cx + 10.0, 0.0, cx + 10.0, tW, thin, dashed = true)
            pen.line(15.0, 0.0, 15.0, tW, thin, dashed = true)
            pen.line(tL - 15.0, 0.0, tL - 15.0, tW, thin, dashed = true)
            pen.line(10.0, 5.0, tL - 10.0, 5.0, thin, dashed = true)
            pen.line(10.0, tW - 5.0, tL - 10.0, tW - 5.0, thin, dashed = true)
        }

        FieldLayout.AMERICAN -> {
            val yd = tL / 120.0
            pen.line(10 * yd, 0.0, 10 * yd, tW, strokePx * 1.4f)
            pen.line(110 * yd, 0.0, 110 * yd, tW, strokePx * 1.4f)
            var k = 15
            while (k < 110) {
                pen.line(k * yd, 0.0, k * yd, tW, if (k % 10 == 0) strokePx else thin)
                k += 5
            }
            pen.line(cx, 0.0, cx, tW, strokePx * 1.3f)
        }

        FieldLayout.GAELIC -> {
            for (d in listOf(13.0, 20.0, 45.0)) {
                pen.line(d, 0.0, d, tW, if (d == 20.0) strokePx else thin)
                pen.line(tL - d, 0.0, tL - d, tW, if (d == 20.0) strokePx else thin)
            }
            pen.line(cx, 0.0, cx, tW)
            pen.rect(0.0, cy - 7.0, 4.5, cy + 7.0)
            pen.rect(tL - 4.5, cy - 7.0, tL, cy + 7.0)
            pen.rect(0.0, cy - 9.5, 13.0, cy + 9.5)
            pen.rect(tL - 13.0, cy - 9.5, tL, cy + 9.5)
            pen.arc(20.0, cy, 13.0, -90f, 180f)
            pen.arc(tL - 20.0, cy, 13.0, 90f, 180f)
        }

        FieldLayout.FIELD_HOCKEY -> {
            pen.line(cx, 0.0, cx, tW)
            pen.line(22.9, 0.0, 22.9, tW)
            pen.line(tL - 22.9, 0.0, tL - 22.9, tW)
            pen.dArea(true, 14.63, 1.83); pen.dArea(false, 14.63, 1.83)
            pen.dArea(true, 19.63, 1.83, dashed = true); pen.dArea(false, 19.63, 1.83, dashed = true)
            pen.dot(6.475, cy); pen.dot(tL - 6.475, cy)
        }

        FieldLayout.ICE_HOCKEY -> {
            drawRoundRect(
                color, rect.topLeft, rect.size,
                cornerRadius = CornerRadius(8.5f * pen.sx, 8.5f * pen.sy),
                style = Stroke(strokePx * 1.2f)
            )
            pen.line(cx, 0.0, cx, tW, strokePx * 1.6f, c = Color(0xFFE5484D).copy(alpha = color.alpha))
            pen.line(tL * 0.375, 0.0, tL * 0.375, tW, strokePx * 1.6f, c = Color(0xFF3B82F6).copy(alpha = color.alpha))
            pen.line(tL * 0.625, 0.0, tL * 0.625, tW, strokePx * 1.6f, c = Color(0xFF3B82F6).copy(alpha = color.alpha))
            pen.line(tL * 0.067, tW * 0.08, tL * 0.067, tW * 0.92, thin)
            pen.line(tL * 0.933, tW * 0.08, tL * 0.933, tW * 0.92, thin)
            pen.circle(cx, cy, tW * 0.15)
            for (fx in listOf(tL * 0.167, tL * 0.833)) for (fy in listOf(tW * 0.25, tW * 0.75)) pen.circle(fx, fy, tW * 0.15, thin)
        }

        FieldLayout.BASKETBALL -> {
            pen.line(cx, 0.0, cx, tW)
            pen.circle(cx, cy, 1.8)
            for (left in listOf(true, false)) {
                val bx = if (left) 1.575 else tL - 1.575
                val keyEnd = if (left) 5.8 else tL - 5.8
                pen.rect(minOf(if (left) 0.0 else tL, keyEnd), cy - 2.45, maxOf(if (left) 0.0 else tL, keyEnd), cy + 2.45)
                pen.circle(keyEnd, cy, 1.8, thin)
                pen.circle(bx, cy, 0.23, thin)
                val a = Math.toDegrees(asin(6.6 / 6.75)).toFloat()
                val lineEnd = 1.575 + sqrt(6.75 * 6.75 - 6.6 * 6.6)
                if (left) {
                    pen.arc(bx, cy, 6.75, -a, 2 * a)
                    pen.line(0.0, 0.9, lineEnd, 0.9); pen.line(0.0, tW - 0.9, lineEnd, tW - 0.9)
                } else {
                    pen.arc(bx, cy, 6.75, 180f - a, 2 * a)
                    pen.line(tL, 0.9, tL - lineEnd, 0.9); pen.line(tL, tW - 0.9, tL - lineEnd, tW - 0.9)
                }
            }
        }

        FieldLayout.BASKET_HALF -> {
            val hx = cx
            val hy = 1.575
            pen.rect(hx - 2.45, 0.0, hx + 2.45, 5.8)
            pen.circle(hx, 5.8, 1.8, thin)
            pen.circle(hx, hy, 0.23, thin)
            val a = Math.toDegrees(acos(6.6 / 6.75)).toFloat()
            val lineEnd = hy + sqrt(6.75 * 6.75 - 6.6 * 6.6)
            pen.arc(hx, hy, 6.75, a, 180f - 2 * a)
            pen.line(hx - 6.6, 0.0, hx - 6.6, lineEnd)
            pen.line(hx + 6.6, 0.0, hx + 6.6, lineEnd)
        }

        FieldLayout.NETBALL -> {
            pen.line(tL / 3, 0.0, tL / 3, tW)
            pen.line(2 * tL / 3, 0.0, 2 * tL / 3, tW)
            pen.circle(cx, cy, 0.45)
            pen.arc(0.0, cy, 4.9, -90f, 180f)
            pen.arc(tL, cy, 4.9, 90f, 180f)
        }

        FieldLayout.TENNIS -> {
            pen.line(0.0, 1.37, tL, 1.37, thin)
            pen.line(0.0, tW - 1.37, tL, tW - 1.37, thin)
            pen.line(cx - 6.40, 1.37, cx - 6.40, tW - 1.37)
            pen.line(cx + 6.40, 1.37, cx + 6.40, tW - 1.37)
            pen.line(cx - 6.40, cy, cx + 6.40, cy)
            pen.line(cx, -0.5, cx, tW + 0.5, strokePx * 2f)
            pen.line(0.0, cy, 0.3, cy); pen.line(tL - 0.3, cy, tL, cy)
        }

        FieldLayout.PADEL -> {
            pen.line(cx, 0.0, cx, tW, strokePx * 2f)
            pen.line(cx - 6.95, 0.0, cx - 6.95, tW)
            pen.line(cx + 6.95, 0.0, cx + 6.95, tW)
            pen.line(cx - 6.95, cy, cx + 6.95, cy)
        }

        FieldLayout.BADMINTON -> {
            pen.line(cx, 0.0, cx, tW, strokePx * 2f)
            pen.line(cx - 1.98, 0.0, cx - 1.98, tW)
            pen.line(cx + 1.98, 0.0, cx + 1.98, tW)
            pen.line(0.76, 0.0, 0.76, tW, thin)
            pen.line(tL - 0.76, 0.0, tL - 0.76, tW, thin)
            pen.line(0.0, 0.46, tL, 0.46, thin)
            pen.line(0.0, tW - 0.46, tL, tW - 0.46, thin)
            pen.line(0.0, cy, cx - 1.98, cy)
            pen.line(cx + 1.98, cy, tL, cy)
        }

        FieldLayout.PICKLEBALL -> {
            pen.line(cx, 0.0, cx, tW, strokePx * 2f)
            pen.line(cx - 2.13, 0.0, cx - 2.13, tW)
            pen.line(cx + 2.13, 0.0, cx + 2.13, tW)
            pen.line(0.0, cy, cx - 2.13, cy)
            pen.line(cx + 2.13, cy, tL, cy)
        }

        FieldLayout.VOLLEY -> {
            pen.line(cx, 0.0, cx, tW, strokePx * 2f)
            pen.line(cx - 3.0, 0.0, cx - 3.0, tW)
            pen.line(cx + 3.0, 0.0, cx + 3.0, tW)
        }

        FieldLayout.TABLE_TENNIS -> {
            drawRect(color.copy(alpha = color.alpha * 0.15f), rect.topLeft, rect.size)
            pen.line(0.0, cy, tL, cy, thin)
            pen.line(cx, -0.15, cx, tW + 0.15, strokePx * 2.5f)
        }

        FieldLayout.SQUASH -> {
            pen.line(0.0, 0.0, 0.0, tW, strokePx * 3f)
            pen.line(5.44, 0.0, 5.44, tW)
            pen.line(5.44, cy, tL, cy)
            pen.rect(5.44, 0.0, 7.04, 1.6)
            pen.rect(5.44, tW - 1.6, 7.04, tW)
        }

        FieldLayout.BASEBALL -> {
            val s = tL / 120.0
            val hx = cx
            val hy = tW - 10 * s
            val b = 27.43 * s / sqrt(2.0)
            // Lignes de fausse balle
            pen.line(hx, hy, 0.0, hy - hx)
            pen.line(hx, hy, tL, hy - hx)
            // Losange
            pen.line(hx, hy, hx + b, hy - b); pen.line(hx + b, hy - b, hx, hy - 2 * b)
            pen.line(hx, hy - 2 * b, hx - b, hy - b); pen.line(hx - b, hy - b, hx, hy)
            pen.circle(hx, hy - 18.44 * s, 2.7 * s, thin)
            // Champ extérieur
            pen.arc(hx, hy, 84 * s, 225f, 90f)
            pen.arc(hx, hy - 18.44 * s, 29 * s, 200f, 140f, thin)
        }

        FieldLayout.CRICKET -> {
            drawOval(color, rect.topLeft, rect.size, style = Stroke(strokePx * 1.2f))
            val inset = 0.22f
            drawOval(
                color, Offset(rect.left + rect.width * inset, rect.top + rect.height * inset),
                Size(rect.width * (1 - 2 * inset), rect.height * (1 - 2 * inset)),
                style = Stroke(thin, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            )
            pen.rect(cx - 10.06, cy - 1.525, cx + 10.06, cy + 1.525)
        }

        FieldLayout.OVAL -> {
            drawOval(color, rect.topLeft, rect.size, style = Stroke(strokePx * 1.2f))
            pen.rect(cx - 25.0, cy - 25.0, cx + 25.0, cy + 25.0)
            pen.circle(cx, cy, 5.0); pen.circle(cx, cy, 1.5, thin)
            pen.arc(0.0, cy, 50.0, -60f, 120f)
            pen.arc(tL, cy, 50.0, 120f, 120f)
            pen.rect(0.0, cy - 3.2, 9.0, cy + 3.2)
            pen.rect(tL - 9.0, cy - 3.2, tL, cy + 3.2)
        }

        FieldLayout.TRACK -> {
            val straight = 84.39
            for (k in 0..8) {
                pen.stadium(36.5 + k * 1.22, straight, if (k == 0 || k == 8) strokePx else thin * 0.7f)
            }
            pen.line((tL - straight) / 2, cy - 36.5 - 9.76, (tL - straight) / 2, cy - 36.5, thin)
        }

        FieldLayout.ULTIMATE -> {
            pen.line(18.0, 0.0, 18.0, tW)
            pen.line(tL - 18.0, 0.0, tL - 18.0, tW)
            pen.dot(36.0, cy); pen.dot(tL - 36.0, cy)
        }

        FieldLayout.GENERIC -> {
            pen.line(cx, 0.0, cx, tW)
            pen.circle(cx, cy, minOf(tW, tL) * 0.13)
            pen.dot(cx, cy)
        }

        FieldLayout.GENERIC_THIRDS -> {
            pen.line(tL / 3, 0.0, tL / 3, tW, thin)
            pen.line(2 * tL / 3, 0.0, 2 * tL / 3, tW, thin)
            pen.line(cx, 0.0, cx, tW)
            pen.circle(cx, cy, minOf(tW, tL) * 0.12)
        }
    }
}

/** Petite aide : chemin passant par une liste de points. */
fun pathOf(points: List<Offset>): Path = Path().apply {
    points.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
}

/** Rectangle de la zone de jeu (terrain + espace autour) à partir du rectangle du terrain. */
fun outerRect(r: Rect, sport: com.philip.fieldtracker.data.Sport): Rect {
    val mu = (sport.marginLM / sport.lengthM).toFloat()
    val mv = (sport.marginWM / sport.widthM).toFloat()
    return Rect(r.left - mu * r.width, r.top - mv * r.height, r.right + mu * r.width, r.bottom + mv * r.height)
}
