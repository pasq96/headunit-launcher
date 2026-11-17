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

enum class G { GRID, GAUGE, GEAR, SPLIT, SOLO_L, SOLO_R, PLAY, PAUSE, NEXT, PREV, CLOSE, SEEK_UP, SEEK_DOWN }

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
        G.CLOSE -> { drawLine(c, Offset(w * 0.25f, h * 0.25f), Offset(w * 0.75f, h * 0.75f), sw, StrokeCap.Round); drawLine(c, Offset(w * 0.75f, h * 0.25f), Offset(w * 0.25f, h * 0.75f), sw, StrokeCap.Round) }
    }
}

// ---- controls (touch-sized: 48dp+ targets) ----

@Composable
fun GlyphButton(g: G, tint: Color, onClick: () -> Unit, size: Dp = 56.dp, glyph: Dp = 32.dp) =
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick), Alignment.Center) { Glyph(g, tint, glyph) }

@Composable
fun Chip(label: String, selected: Boolean, accent: Color, height: Dp = 48.dp, onClick: () -> Unit) =
    Box(
        Modifier.height(height).clip(RoundedCornerShape(24.dp)).background(if (selected) accent else Surface2)
            .clickable(onClick = onClick).padding(horizontal = 20.dp),
        Alignment.Center,
    ) { Txt(label, 16, if (selected) OnAccent else TextHi, bold = selected) }

@Composable
fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) =
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(if (selected) TextHi else Surface2).clickable(onClick = onClick), Alignment.Center) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(color))
    }

@Composable
fun ToggleRow(label: String, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().height(56.dp).clickable { onChange(!checked) }, Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Txt(label, 17)
        Box(
            Modifier.width(56.dp).height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (checked) accent else Surface2).padding(3.dp),
            if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) { Box(Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(TextHi)) }
    }

@Composable
fun StepRow(label: String, value: String, accent: Color, onMinus: () -> Unit, onPlus: () -> Unit) =
    Row(Modifier.fillMaxWidth().height(56.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Txt(label, 17)
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
fun Section(title: String, accent: Color, content: @Composable () -> Unit) = Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
    Txt(title.uppercase(), 13, accent, bold = true)
    Spacer(Modifier.height(6.dp))
    content()
}

/** tap + long-press helper (combinedClickable kept in one place). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.androidxCombined(onClick: () -> Unit, onLong: () -> Unit): Modifier =
    this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLong))
