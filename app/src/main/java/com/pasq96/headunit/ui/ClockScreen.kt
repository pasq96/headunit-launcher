package com.pasq96.headunit.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pasq96.headunit.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** Flip clock + big date + month calendar, opened by tapping the clock in the dock. */
@Composable
internal fun ClockScreen(vm: LauncherHub, accent: Color) {
    val p by vm.prefs.collectAsStateWithLifecycle()
    val close = { vm.clockScreen = false }
    val loc = remember(p.lang) { Locale.forLanguageTag(p.lang.tag) }
    val now by produceState(Date()) {
        while (true) { value = Date(); delay(60_000L - System.currentTimeMillis() % 60_000L + 50L) }
    }
    val hh = remember(now, p.clock24h) { SimpleDateFormat(if (p.clock24h) "HH" else "hh", loc).format(now) }
    val mm = remember(now) { SimpleDateFormat("mm", loc).format(now) }
    val day = remember(now, loc) { SimpleDateFormat("EEEE", loc).format(now).cap() }
    val full = remember(now, loc) { SimpleDateFormat("d MMMM yyyy", loc).format(now) }
    val ampm = remember(now, loc) { SimpleDateFormat("a", loc).format(now) }

    Box(Modifier.fillMaxSize().background(Bg).pointerInput(Unit) { detectTapGestures { } }) {
        Row(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp), Arrangement.spacedBy(28.dp)) {
            BoxWithConstraints(Modifier.weight(1.15f).fillMaxHeight()) {
                val w = minOf((maxWidth - 70.dp) / 4.4f, (maxHeight * 0.44f) / 1.4f, 130.dp)
                val h = w * 1.4f
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FlipDigit(hh.substring(0, 1), w, h); FlipDigit(hh.substring(1, 2), w, h)
                        Txt(":", (h.value * 0.6f).toInt(), accent, bold = true, modifier = Modifier.width(w * 0.3f), align = TextAlign.Center)
                        FlipDigit(mm.substring(0, 1), w, h); FlipDigit(mm.substring(1, 2), w, h)
                        if (!p.clock24h) Txt(ampm, 20, TextLo, bold = true, modifier = Modifier.padding(start = 6.dp))
                    }
                    Txt(day, (h.value * 0.34f).toInt(), accent, bold = true, modifier = Modifier.padding(top = 28.dp))
                    Txt(full, (h.value * 0.22f).toInt(), TextHi)
                }
            }
            MonthCalendar(loc, accent, Modifier.weight(1f).fillMaxHeight().padding(top = 52.dp))
        }
        Box(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 22.dp)) { GlyphButton(G.CLOSE, TextHi, close) }
    }
}

private fun String.cap() = replaceFirstChar { it.uppercase() }

// ---- split-flap digit: the old digit's top half folds down, then the new digit's bottom half falls into place ----

@Composable
private fun FlipDigit(d: String, w: Dp, h: Dp) {
    var cur by remember { mutableStateOf(d) }
    var old by remember { mutableStateOf(d) }
    val t = remember { Animatable(1f) }
    LaunchedEffect(d) {
        if (d != cur) { old = cur; cur = d; t.snapTo(0f); t.animateTo(1f, tween(650)) }
    }
    val p = t.value
    val cam = 14f * androidx.compose.ui.platform.LocalDensity.current.density
    Box(Modifier.size(w, h)) {
        Column {
            Half(cur, true, w, h)
            Half(if (p < 1f) old else cur, false, w, h)
        }
        if (p < 0.5f) {
            Half(old, true, w, h, Modifier.align(Alignment.TopCenter).graphicsLayer {
                rotationX = -180f * p; transformOrigin = TransformOrigin(0.5f, 1f); cameraDistance = cam
            })
        } else if (p < 1f) {
            Half(cur, false, w, h, Modifier.align(Alignment.BottomCenter).graphicsLayer {
                rotationX = 180f * (1f - p); transformOrigin = TransformOrigin(0.5f, 0f); cameraDistance = cam
            })
        }
        Box(Modifier.fillMaxWidth().height(2.dp).align(Alignment.Center).background(Color.Black.copy(alpha = 0.55f)))
    }
}

@Composable
private fun Half(d: String, top: Boolean, w: Dp, h: Dp, mod: Modifier = Modifier) {
    val r = 14.dp
    val shape = if (top) RoundedCornerShape(topStart = r, topEnd = r) else RoundedCornerShape(bottomStart = r, bottomEnd = r)
    val bg = if (top) Surface2 else lerp(Surface2, Color.Black, 0.14f)
    Box(mod.size(w, h / 2).clip(shape).background(bg)) {
        Box(Modifier.requiredSize(w, h).offset(y = if (top) h / 4 else -(h / 4)), Alignment.Center) {
            Txt(d, (h.value * 0.72f).toInt(), TextHi, bold = true)
        }
    }
}

// ---- month grid, first weekday follows the locale ----

@Composable
private fun MonthCalendar(loc: Locale, accent: Color, modifier: Modifier) {
    var off by remember { mutableIntStateOf(0) }
    val today = remember { Calendar.getInstance() }
    val cal = remember(off, loc) { Calendar.getInstance(loc).apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, off) } }
    val first = cal.firstDayOfWeek
    val lead = (cal.get(Calendar.DAY_OF_WEEK) - first + 7) % 7
    val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val title = remember(cal.timeInMillis, loc) { SimpleDateFormat("LLLL yyyy", loc).format(cal.time).cap() }
    val names = remember(loc) {
        val f = SimpleDateFormat("EEEEE", loc)
        List(7) { i -> f.format(Calendar.getInstance(loc).apply { set(Calendar.DAY_OF_WEEK, (first - 1 + i) % 7 + 1) }.time).uppercase() }
    }
    val isNow = off == 0
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Surface2).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp), Alignment.CenterVertically) {
            Txt(title, 22, bold = true, modifier = Modifier.weight(1f))
            if (!isNow) Chip(str(R.string.clock_today), false, accent, height = 40.dp) { off = 0 }
            GlyphButton(G.PREV, TextHi, { off-- })
            GlyphButton(G.NEXT, TextHi, { off++ })
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
            names.forEach { Txt(it, 14, TextLo, bold = true, modifier = Modifier.weight(1f), align = TextAlign.Center) }
        }
        for (r in 0 until 6) Row(Modifier.fillMaxWidth().weight(1f)) {
            for (c in 0 until 7) {
                val n = r * 7 + c - lead + 1
                val mark = isNow && n == today.get(Calendar.DAY_OF_MONTH)
                Box(Modifier.weight(1f).fillMaxHeight(), Alignment.Center) {
                    if (n in 1..days) {
                        Box(Modifier.size(42.dp).clip(CircleShape).background(if (mark) accent else Color.Transparent), Alignment.Center) {
                            Txt("$n", 19, if (mark) onAccent(accent) else TextHi, bold = mark)
                        }
                    }
                }
            }
        }
    }
}
