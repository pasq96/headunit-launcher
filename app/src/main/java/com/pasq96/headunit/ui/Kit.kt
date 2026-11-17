package com.pasq96.headunit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun Txt(
    text: String, size: Int = 16, color: Color = TextHi, bold: Boolean = false,
    modifier: Modifier = Modifier, maxLines: Int = 1, align: TextAlign = TextAlign.Unspecified,
) = BasicText(
    text, modifier,
    TextStyle(color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = align),
    overflow = TextOverflow.Ellipsis, maxLines = maxLines,
)

// ---- icons drawn on Canvas: no icon library, no font glyph dependency ----

enum class G { GRID, GAUGE, GEAR, SPLIT, SOLO_L, SOLO_R, PLAY, PAUSE, NEXT, PREV, CLOSE, SEEK_UP, SEEK_DOWN,
    INFO, TRASH, STOP, DATA, REFRESH, PIN, NAV, MUSIC, THEME, GLOBE, LED, DOCK, RADIO, CLOCK, POWER, SHIELD, SUN, WARN, CHECK, SIZE, SWAP }

@Composable
fun Glyph(g: G, color: Color, size: Dp = 32.dp) = Canvas(Modifier.size(size)) { drawGlyph(g, color) }

private fun DrawScope.drawGlyph(g: G, c: Color) {
    val w = size.width; val h = size.height; val sw = w * 0.09f
    when (g) {
        G.GRID -> for (i in 0..2) for (j in 0..2) drawCircle(c, w * 0.09f, Offset(w * (0.2f + 0.3f * i), h * (0.2f + 0.3f * j)))
        G.GAUGE -> {
            drawArc(c, 180f, 180f, false, Offset(w * 0.1f, h * 0.2f), Size(w * 0.8f, h * 0.8f), style = Stroke(sw, cap = StrokeCap.Round))
            drawLine(c, Offset(w * 0.5f, h * 0.6f), Offset(w * 0.72f, h * 0.38f), sw, StrokeCap.Round)
        }
        G.GEAR -> {
            drawCircle(c, w * 0.2f, style = Stroke(sw))
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0); val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
                drawLine(c, Offset(w / 2 + ca * w * 0.3f, h / 2 + sa * h * 0.3f), Offset(w / 2 + ca * w * 0.42f, h / 2 + sa * h * 0.42f), sw * 1.2f, StrokeCap.Round)
            }
        }
        G.SPLIT, G.SOLO_L, G.SOLO_R -> {
            val r = CornerRadius(w * 0.06f)
            val l = Offset(w * 0.08f, h * 0.22f); val rt = Offset(w * 0.54f, h * 0.22f); val s = Size(w * 0.38f, h * 0.56f)
            val fillL = g != G.SOLO_R; val fillR = g != G.SOLO_L
            if (fillL) drawRoundRect(c, l, s, r) else drawRoundRect(c, l, s, r, style = Stroke(sw * 0.6f))
            if (fillR) drawRoundRect(c, rt, s, r) else drawRoundRect(c, rt, s, r, style = Stroke(sw * 0.6f))
        }
        G.PLAY -> drawPath(Path().apply { moveTo(w * 0.28f, h * 0.18f); lineTo(w * 0.82f, h * 0.5f); lineTo(w * 0.28f, h * 0.82f); close() }, c)
        G.PAUSE -> { drawRect(c, Offset(w * 0.25f, h * 0.18f), Size(w * 0.17f, h * 0.64f)); drawRect(c, Offset(w * 0.58f, h * 0.18f), Size(w * 0.17f, h * 0.64f)) }
        G.NEXT -> { drawPath(Path().apply { moveTo(w * 0.2f, h * 0.2f); lineTo(w * 0.7f, h * 0.5f); lineTo(w * 0.2f, h * 0.8f); close() }, c); drawRect(c, Offset(w * 0.74f, h * 0.2f), Size(w * 0.09f, h * 0.6f)) }
        G.PREV -> { drawPath(Path().apply { moveTo(w * 0.8f, h * 0.2f); lineTo(w * 0.3f, h * 0.5f); lineTo(w * 0.8f, h * 0.8f); close() }, c); drawRect(c, Offset(w * 0.17f, h * 0.2f), Size(w * 0.09f, h * 0.6f)) }
        G.SEEK_UP -> for (x in listOf(0.12f, 0.46f)) drawPath(Path().apply { moveTo(w * x, h * 0.22f); lineTo(w * (x + 0.4f), h * 0.5f); lineTo(w * x, h * 0.78f); close() }, c)
        G.SEEK_DOWN -> for (x in listOf(0.48f, 0.14f)) drawPath(Path().apply { moveTo(w * (x + 0.4f), h * 0.22f); lineTo(w * x, h * 0.5f); lineTo(w * (x + 0.4f), h * 0.78f); close() }, c)
        G.INFO -> { drawCircle(c, w * 0.4f, style = Stroke(sw)); drawCircle(c, w * 0.05f, Offset(w * 0.5f, h * 0.32f)); drawLine(c, Offset(w * 0.5f, h * 0.46f), Offset(w * 0.5f, h * 0.7f), sw, StrokeCap.Round) }
        G.TRASH -> {
            drawLine(c, Offset(w * 0.18f, h * 0.27f), Offset(w * 0.82f, h * 0.27f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.4f, h * 0.17f), Offset(w * 0.6f, h * 0.17f), sw, StrokeCap.Round)
            drawPath(Path().apply { moveTo(w * 0.26f, h * 0.36f); lineTo(w * 0.31f, h * 0.84f); lineTo(w * 0.69f, h * 0.84f); lineTo(w * 0.74f, h * 0.36f) }, c, style = Stroke(sw, cap = StrokeCap.Round))
        }
        G.STOP -> { drawCircle(c, w * 0.4f, style = Stroke(sw)); drawRoundRect(c, Offset(w * 0.35f, h * 0.35f), Size(w * 0.3f, h * 0.3f), CornerRadius(w * 0.04f)) }
        G.DATA -> {
            drawOval(c, Offset(w * 0.22f, h * 0.14f), Size(w * 0.56f, h * 0.22f), style = Stroke(sw))
            drawLine(c, Offset(w * 0.22f, h * 0.25f), Offset(w * 0.22f, h * 0.75f), sw); drawLine(c, Offset(w * 0.78f, h * 0.25f), Offset(w * 0.78f, h * 0.75f), sw)
            drawArc(c, 0f, 180f, false, Offset(w * 0.22f, h * 0.64f), Size(w * 0.56f, h * 0.22f), style = Stroke(sw))
        }
        G.REFRESH -> {
            drawArc(c, -60f, 280f, false, Offset(w * 0.18f, h * 0.18f), Size(w * 0.64f, h * 0.64f), style = Stroke(sw, cap = StrokeCap.Round))
            drawPath(Path().apply { moveTo(w * 0.62f, h * 0.1f); lineTo(w * 0.86f, h * 0.2f); lineTo(w * 0.66f, h * 0.36f); close() }, c)
        }
        G.PIN -> { drawCircle(c, w * 0.17f, Offset(w * 0.5f, h * 0.3f)); drawLine(c, Offset(w * 0.5f, h * 0.45f), Offset(w * 0.5f, h * 0.86f), sw, StrokeCap.Round); drawLine(c, Offset(w * 0.3f, h * 0.5f), Offset(w * 0.7f, h * 0.5f), sw, StrokeCap.Round) }
        G.NAV -> drawPath(Path().apply { moveTo(w * 0.5f, h * 0.1f); lineTo(w * 0.8f, h * 0.88f); lineTo(w * 0.5f, h * 0.68f); lineTo(w * 0.2f, h * 0.88f); close() }, c)
        G.MUSIC -> { drawCircle(c, w * 0.15f, Offset(w * 0.34f, h * 0.76f)); drawLine(c, Offset(w * 0.48f, h * 0.76f), Offset(w * 0.48f, h * 0.16f), sw, StrokeCap.Round); drawLine(c, Offset(w * 0.48f, h * 0.16f), Offset(w * 0.76f, h * 0.3f), sw * 1.3f, StrokeCap.Round) }
        G.THEME -> { drawCircle(c, w * 0.38f, style = Stroke(sw)); drawArc(c, 90f, 180f, true, Offset(w * 0.12f, h * 0.12f), Size(w * 0.76f, h * 0.76f)) }
        G.GLOBE -> {
            drawCircle(c, w * 0.4f, style = Stroke(sw)); drawOval(c, Offset(w * 0.34f, h * 0.1f), Size(w * 0.32f, h * 0.8f), style = Stroke(sw * 0.8f))
            drawLine(c, Offset(w * 0.1f, h * 0.5f), Offset(w * 0.9f, h * 0.5f), sw * 0.8f)
        }
        G.LED -> { drawRoundRect(c, Offset(w * 0.08f, h * 0.3f), Size(w * 0.84f, h * 0.4f), CornerRadius(w * 0.08f), style = Stroke(sw)); for (i in 0..2) drawCircle(c, w * 0.06f, Offset(w * (0.28f + 0.22f * i), h * 0.5f)) }
        G.DOCK -> { drawRoundRect(c, Offset(w * 0.1f, h * 0.15f), Size(w * 0.8f, h * 0.7f), CornerRadius(w * 0.06f), style = Stroke(sw)); drawRect(c, Offset(w * 0.1f, h * 0.65f), Size(w * 0.8f, h * 0.2f)) }
        G.RADIO -> {
            drawRoundRect(c, Offset(w * 0.1f, h * 0.34f), Size(w * 0.8f, h * 0.5f), CornerRadius(w * 0.08f), style = Stroke(sw))
            drawCircle(c, w * 0.11f, Offset(w * 0.34f, h * 0.59f)); drawLine(c, Offset(w * 0.55f, h * 0.52f), Offset(w * 0.78f, h * 0.52f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.55f, h * 0.66f), Offset(w * 0.78f, h * 0.66f), sw, StrokeCap.Round); drawLine(c, Offset(w * 0.3f, h * 0.34f), Offset(w * 0.75f, h * 0.12f), sw, StrokeCap.Round)
        }
        G.CLOCK -> { drawCircle(c, w * 0.4f, style = Stroke(sw)); drawLine(c, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.5f, h * 0.26f), sw, StrokeCap.Round); drawLine(c, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.68f, h * 0.6f), sw, StrokeCap.Round) }
        G.POWER -> { drawArc(c, -60f, 300f, false, Offset(w * 0.18f, h * 0.2f), Size(w * 0.64f, h * 0.64f), style = Stroke(sw, cap = StrokeCap.Round)); drawLine(c, Offset(w * 0.5f, h * 0.1f), Offset(w * 0.5f, h * 0.5f), sw, StrokeCap.Round) }
        G.SHIELD -> drawPath(Path().apply { moveTo(w * 0.5f, h * 0.1f); lineTo(w * 0.82f, h * 0.22f); lineTo(w * 0.78f, h * 0.55f); quadraticTo(w * 0.72f, h * 0.78f, w * 0.5f, h * 0.9f); quadraticTo(w * 0.28f, h * 0.78f, w * 0.22f, h * 0.55f); lineTo(w * 0.18f, h * 0.22f); close() }, c, style = Stroke(sw, cap = StrokeCap.Round))
        G.SUN -> {
            drawCircle(c, w * 0.17f)
            for (k in 0 until 8) { val a = Math.toRadians(k * 45.0); val ca = cos(a).toFloat(); val sa = sin(a).toFloat(); drawLine(c, Offset(w / 2 + ca * w * 0.28f, h / 2 + sa * h * 0.28f), Offset(w / 2 + ca * w * 0.4f, h / 2 + sa * h * 0.4f), sw, StrokeCap.Round) }
        }
        G.WARN -> { drawPath(Path().apply { moveTo(w * 0.5f, h * 0.12f); lineTo(w * 0.9f, h * 0.82f); lineTo(w * 0.1f, h * 0.82f); close() }, c, style = Stroke(sw, cap = StrokeCap.Round)); drawLine(c, Offset(w * 0.5f, h * 0.4f), Offset(w * 0.5f, h * 0.6f), sw, StrokeCap.Round); drawCircle(c, w * 0.045f, Offset(w * 0.5f, h * 0.72f)) }
        G.CHECK -> drawPath(Path().apply { moveTo(w * 0.2f, h * 0.52f); lineTo(w * 0.42f, h * 0.74f); lineTo(w * 0.8f, h * 0.28f) }, c, style = Stroke(sw * 1.2f, cap = StrokeCap.Round))
        G.SIZE -> { drawRoundRect(c, Offset(w * 0.12f, h * 0.12f), Size(w * 0.76f, h * 0.76f), CornerRadius(w * 0.06f), style = Stroke(sw * 0.8f)); drawLine(c, Offset(w * 0.3f, h * 0.7f), Offset(w * 0.7f, h * 0.3f), sw, StrokeCap.Round); drawLine(c, Offset(w * 0.5f, h * 0.3f), Offset(w * 0.7f, h * 0.3f), sw, StrokeCap.Round); drawLine(c, Offset(w * 0.7f, h * 0.3f), Offset(w * 0.7f, h * 0.5f), sw, StrokeCap.Round) }
        G.SWAP -> {
            drawLine(c, Offset(w * 0.15f, h * 0.35f), Offset(w * 0.85f, h * 0.35f), sw, StrokeCap.Round); drawLine(c, Offset(w * 0.65f, h * 0.18f), Offset(w * 0.85f, h * 0.35f), sw, StrokeCap.Round)
            drawLine(c, Offset(w * 0.85f, h * 0.65f), Offset(w * 0.15f, h * 0.65f), sw, StrokeCap.Round); drawLine(c, Offset(w * 0.35f, h * 0.82f), Offset(w * 0.15f, h * 0.65f), sw, StrokeCap.Round)
        }
        G.CLOSE -> { drawLine(c, Offset(w * 0.25f, h * 0.25f), Offset(w * 0.75f, h * 0.75f), sw, StrokeCap.Round); drawLine(c, Offset(w * 0.75f, h * 0.25f), Offset(w * 0.25f, h * 0.75f), sw, StrokeCap.Round) }
    }
}

/** Label with an optional leading glyph, tinted like the text. */
@Composable
fun Labeled(label: String, icon: G?, color: Color, size: Int = 17, bold: Boolean = false, glyph: Dp = 26.dp, modifier: Modifier = Modifier) =
    Row(modifier, Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
        if (icon != null) Glyph(icon, color, glyph)
        Txt(label, size, color, bold)
    }

// ---- controls (touch-sized: 48dp+ targets) ----

@Composable
fun GlyphButton(g: G, tint: Color, onClick: () -> Unit, size: Dp = 56.dp, glyph: Dp = 32.dp) =
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick), Alignment.Center) { Glyph(g, tint, glyph) }

@Composable
fun Chip(label: String, selected: Boolean, accent: Color, height: Dp = 48.dp, icon: G? = null, onClick: () -> Unit) =
    Box(
        Modifier.height(height).clip(RoundedCornerShape(24.dp)).background(if (selected) accent else Surface2)
            .clickable(onClick = onClick).padding(horizontal = 20.dp),
        Alignment.Center,
    ) { Labeled(label, icon, if (selected) OnAccent else TextHi, 16, selected, 22.dp) }

@Composable
fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) =
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(if (selected) TextHi else Surface2).clickable(onClick = onClick), Alignment.Center) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(color))
    }

@Composable
fun ToggleRow(label: String, checked: Boolean, accent: Color, icon: G? = null, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().height(56.dp).clickable { onChange(!checked) }, Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Labeled(label, icon, TextHi, 17)
        Box(
            Modifier.width(56.dp).height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (checked) accent else Surface2).padding(3.dp),
            if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(TextHi)) }
    }

@Composable
fun StepRow(label: String, value: String, accent: Color, onMinus: () -> Unit, onPlus: () -> Unit, icon: G? = null) =
    Row(Modifier.fillMaxWidth().height(56.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Labeled(label, icon, TextHi, 17)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepBtn("−", onMinus)
            Txt(value, 18, accent, bold = true, modifier = Modifier.width(88.dp), align = TextAlign.Center)
            StepBtn("+", onPlus)
        }
    }

@Composable
private fun StepBtn(s: String, onClick: () -> Unit) =
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Surface2).clickable(onClick = onClick), Alignment.Center) { Txt(s, 24, bold = true) }

@Composable
fun SimpleSlider(value: Float, accent: Color, onChange: (Float) -> Unit, onDone: () -> Unit = {}) {
    var w by remember { mutableIntStateOf(1) }
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    Box(
        Modifier.fillMaxWidth().height(48.dp).onSizeChanged { w = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures { change((it.x / w).coerceIn(0f, 1f)); done() } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = { done() }) { c, _ -> c.consume(); change((c.position.x / w).coerceIn(0f, 1f)) }
            },
    ) {
        val track = Surface2
        Canvas(Modifier.fillMaxWidth().height(48.dp)) {
            val y = size.height / 2; val x = size.width * value.coerceIn(0f, 1f)
            drawLine(track, Offset(0f, y), Offset(size.width, y), 10f, StrokeCap.Round)
            drawLine(accent, Offset(0f, y), Offset(x, y), 10f, StrokeCap.Round)
            drawCircle(accent, 16f, Offset(x, y))
        }
    }
}

@Composable
fun Section(title: String, accent: Color, icon: G? = null, content: @Composable () -> Unit) = Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
    Labeled(title.uppercase(), icon, accent, 13, true, 20.dp)
    Spacer(Modifier.height(6.dp))
    content()
}

/** tap + long-press helper (combinedClickable kept in one place). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.androidxCombined(onClick: () -> Unit, onLong: () -> Unit): Modifier =
    this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLong))
