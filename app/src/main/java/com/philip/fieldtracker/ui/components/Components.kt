package com.philip.fieldtracker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.philip.fieldtracker.data.Sport
import com.philip.fieldtracker.ui.field.drawFieldMarkings
import com.philip.fieldtracker.ui.field.templateSize
import com.philip.fieldtracker.ui.theme.LocalPalette
import com.philip.fieldtracker.ui.theme.Type

val Shape4 = RoundedCornerShape(4.dp)
val Shape8 = RoundedCornerShape(8.dp)
val Shape12 = RoundedCornerShape(12.dp)

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    elevated: Boolean = false,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(Shape12)
            .background(if (elevated) p.elevated else p.surface)
            .border(BorderStroke(1.dp, accent ?: p.border), Shape12)
            .padding(padding),
        content = content,
    )
}

@Composable
fun Label(text: String, color: Color = LocalPalette.current.muted, style: TextStyle = Type.label, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = style, color = color, modifier = modifier, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
fun SectionTitle(title: String, trailing: String? = null, trailingColor: Color = LocalPalette.current.primary) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.h2, color = p.text, modifier = Modifier.weight(1f))
        if (trailing != null) Label(trailing, color = trailingColor)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp, glow: Boolean = false) {
    Box(
        Modifier
            .size(size)
            .then(if (glow) Modifier.shadow(6.dp, CircleShape, ambientColor = color, spotColor = color) else Modifier)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    val p = LocalPalette.current
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color.copy(alpha = 0.18f) else p.elevated)
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(color, 7.dp)
        Text(text, style = Type.caption, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) p.primary.copy(alpha = 0.12f) else p.surface)
            .border(1.dp, if (active) p.primary else p.border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, style = Type.label, color = if (active) p.primary else p.text, maxLines = 1)
    }
}

@Composable
fun BigButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = LocalPalette.current.primary,
    textColor: Color = LocalPalette.current.onPrimary,
    enabled: Boolean = true,
    height: Dp = 64.dp,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    val bg = if (enabled) color else p.elevated
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .then(if (enabled) Modifier.shadow(10.dp, Shape12, ambientColor = color, spotColor = color) else Modifier)
            .clip(Shape12)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) textColor else p.muted, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text.uppercase(), style = Type.h2, color = if (enabled) textColor else p.muted, textAlign = TextAlign.Center)
    }
}

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = LocalPalette.current.text,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Row(
        modifier
            .clip(Shape8)
            .background(p.surface)
            .border(1.dp, p.border, Shape8)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text.uppercase(), style = Type.label, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = LocalPalette.current.text,
    footer: String? = null,
    footerColor: Color = LocalPalette.current.muted,
    big: Boolean = false,
) {
    val p = LocalPalette.current
    Column(
        modifier
            .clip(Shape8)
            .background(p.surface)
            .border(1.dp, p.border, Shape8)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Label(label, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = if (big) Type.metricXl else Type.metric, color = color, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Text(unit, style = Type.label, color = p.muted, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (footer != null) {
            Spacer(Modifier.height(6.dp))
            Text(footer, style = Type.caption, color = footerColor, maxLines = 2)
        }
    }
}

@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val p = LocalPalette.current
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(p.elevated)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

/**
 * Dessine un terrain (fond + lignes) au bon format et laisse dessiner une
 * surcouche dans le rectangle du terrain ([overlay] reçoit ce rectangle).
 */
@Composable
fun FieldCanvas(
    sport: Sport,
    modifier: Modifier = Modifier,
    inset: Dp = 18.dp,
    lineColor: Color = LocalPalette.current.fieldLine,
    background: Color = LocalPalette.current.fieldBg,
    strokeDp: Float = 1.6f,
    overlay: DrawScope.(Rect) -> Unit = {},
) {
    val (tl, tw) = templateSize(sport.layout, sport.lengthM, sport.widthM)
    val ratio = (tl / tw).toFloat().coerceIn(0.8f, 2.4f)
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(ratio * 1.0f)
            .clip(Shape8)
            .background(background)
    ) {
        val ins = inset.toPx()
        val r = Rect(Offset(ins, ins), Size(size.width - 2 * ins, size.height - 2 * ins))
        // grille de points discrète
        val step = 22.dp.toPx()
        var x = step / 2
        while (x < size.width) {
            var y = step / 2
            while (y < size.height) {
                drawCircle(lineColor.copy(alpha = 0.18f), 1.2f, Offset(x, y)); y += step
            }
            x += step
        }
        drawFieldMarkings(sport.layout, r, sport.lengthM, sport.widthM, lineColor, strokeDp * density)
        overlay(r)
    }
}

/** Petite ligne titre/valeur. */
@Composable
fun KeyValue(key: String, value: String, valueColor: Color = LocalPalette.current.text) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(key, style = Type.body, color = p.muted, modifier = Modifier.weight(1f))
        Text(value, style = Type.label, color = valueColor)
    }
}

@Composable
fun IconSquare(icon: ImageVector, color: Color, onClick: (() -> Unit)? = null) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(44.dp)
            .clip(Shape8)
            .background(p.elevated)
            .border(1.dp, p.border, Shape8)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = color, modifier = Modifier.size(22.dp)) }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
}

fun formatKm(m: Double): String = if (m < 1000) String.format("%.0f", m) else String.format("%.2f", m / 1000)
fun unitKm(m: Double): String = if (m < 1000) "m" else "km"
