package com.pasq96.headunit.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pasq96.headunit.R
import com.pasq96.headunit.audio.AudioFx

private val Scrim = Color(0xCC000000)

@Composable
internal fun EqualizerScreen(vm: LauncherHub, accent: Color) {
    val fx = vm.audio
    val ctx = LocalContext.current
    val close = { vm.screen = null }
    val info = remember { fx.info() }
    var tab by remember { mutableIntStateOf(0) }
    var style by remember { mutableIntStateOf(fx.currentStyle()) }
    val vals = remember { mutableStateListOf<Int>().also { l -> fx.values(style)?.let { l.addAll(it.toList()) } } }
    var loud by remember { mutableStateOf(fx.loudness()) }
    var sub by remember { mutableStateOf(fx.sub()) }
    val subOk = remember { fx.subSupported() }
    var edited by remember { mutableStateOf(0L) }

    fun load(s: Int, v: IntArray?) { style = s; if (v != null) { vals.clear(); vals.addAll(v.toList()) } }

    // follow changes made elsewhere (steering wheel, stock app); ignore echoes of our own edits
    DisposableEffect(Unit) {
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (System.currentTimeMillis() - edited < 800) return
                val s = fx.currentStyle(); load(s, fx.values(s)); loud = fx.loudness(); sub = fx.sub()
            }
        }
        ContextCompat.registerReceiver(ctx, rx, IntentFilter(AudioFx.ACTION_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(rx) } }
    }

    val setBand = { i: Int, v: Int ->
        if (i in vals.indices && vals[i] != v) {
            vals[i] = v; style = AudioFx.CUSTOM; edited = System.currentTimeMillis()
            fx.setValues(AudioFx.CUSTOM, vals.toIntArray())
        }
    }

    Box(Modifier.fillMaxSize().background(Scrim).pointerInput(Unit) { detectTapGestures { close() } }) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth(0.94f).fillMaxHeight(0.88f).clip(RoundedCornerShape(22.dp)).background(Surface)
                .pointerInput(Unit) { detectTapGestures { } }.padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
                Txt(str(R.string.app_eq), 24, bold = true, modifier = Modifier.padding(end = 10.dp))
                Chip("EQ", tab == 0, accent, icon = G.EQ) { tab = 0 }
                Chip(str(R.string.eq_balance), tab == 1, accent, icon = G.SPLIT) { tab = 1 }
                Box(Modifier.weight(1f))
                Chip(str(R.string.eq_loudness), loud, accent) { loud = !loud; fx.setLoudness(loud) }
                if (subOk) Chip(str(R.string.eq_sub), sub, accent) { sub = !sub; fx.setSub(sub) }
                GlyphButton(G.CLOSE, TextHi, close)
            }
            if (info == null) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { Txt(str(R.string.eq_unavailable), 18, TextLo, align = TextAlign.Center) }
            } else if (tab == 0) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()), Arrangement.spacedBy(8.dp)) {
                    info.styles.forEachIndexed { i, s ->
                        Chip(info.titles.getOrElse(i) { "EQ $s" }, s == style, accent) { load(s, fx.select(s)) }
                    }
                    Chip(str(R.string.eq_reset), false, accent, icon = G.REFRESH) { fx.reset()?.let { load(AudioFx.FLAT, it) } }
                }
                Bands(info.hzs, vals, info.min, info.max, accent, setBand)
            } else {
                BalancePad(fx, accent)
            }
        }
    }
}

@Composable
private fun Bands(hzs: List<String>, vals: List<Int>, min: Int, max: Int, accent: Color, onSet: (Int, Int) -> Unit) {
    val n = hzs.size.coerceAtLeast(1)
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = 10.dp)) {
        val bandW = maxOf(58.dp, maxWidth / n)
        val span = (max - min).coerceAtLeast(1)
        Box(Modifier.fillMaxHeight().horizontalScroll(rememberScrollState())) {
            Box(Modifier.width(bandW * n).fillMaxHeight()) {
                val grid = TextLo.copy(alpha = 0.25f)
                // response curve behind the sliders, same x spacing so knobs sit on it
                Canvas(Modifier.fillMaxSize().padding(top = 26.dp, bottom = 34.dp)) {
                    val w = size.width; val h = size.height
                    drawLine(grid, Offset(0f, h / 2), Offset(w, h / 2), 2f)
                    if (vals.size < 2) return@Canvas
                    val pts = vals.mapIndexed { i, v -> Offset((i + 0.5f) / n * w, (max - v).toFloat() / span * h) }
                    val line = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 0 until pts.lastIndex) {
                            val p0 = pts[maxOf(i - 1, 0)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[minOf(i + 2, pts.lastIndex)]
                            cubicTo(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f, p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f, p2.x, p2.y)
                        }
                    }
                    val fill = Path().apply { addPath(line); lineTo(pts.last().x, h); lineTo(pts[0].x, h); close() }
                    drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.30f), Color.Transparent)))
                    drawPath(line, accent, style = Stroke(5f, cap = StrokeCap.Round))
                }
                Row(Modifier.fillMaxSize()) {
                    for (i in 0 until n) Band(hzs[i], vals.getOrElse(i) { 0 }, min, max, accent, Modifier.width(bandW)) { onSet(i, it) }
                }
            }
        }
    }
}

@Composable
private fun Band(label: String, v: Int, min: Int, max: Int, accent: Color, modifier: Modifier, onSet: (Int) -> Unit) {
    Column(modifier.fillMaxHeight(), Arrangement.SpaceBetween, Alignment.CenterHorizontally) {
        Txt(if (v > 0) "+$v" else "$v", 15, if (v == 0) TextLo else TextHi, bold = true, modifier = Modifier.height(26.dp))
        Canvas(
            Modifier.weight(1f).width(bandWidthTouch)
                .pointerInput(min, max) {
                    awaitEachGesture {
                        val d = awaitFirstDown()
                        fun at(y: Float) = (max - (y / size.height).coerceIn(0f, 1f) * (max - min)).let { Math.round(it) }.coerceIn(min, max)
                        onSet(at(d.position.y)); d.consume()
                        do {
                            val c = awaitPointerEvent().changes.first()
                            if (c.pressed) { onSet(at(c.position.y)); c.consume() }
                        } while (c.pressed)
                    }
                },
        ) {
            val cx = size.width / 2; val ky = (max - v).toFloat() / (max - min).coerceAtLeast(1) * size.height
            drawLine(Color.White.copy(alpha = 0.12f), Offset(cx, 0f), Offset(cx, size.height), 8f, StrokeCap.Round)
            drawLine(accent, Offset(cx, size.height / 2), Offset(cx, ky), 8f, StrokeCap.Round)
            drawCircle(Color.Black.copy(alpha = 0.35f), 19f, Offset(cx, ky + 2f))
            drawCircle(accent, 17f, Offset(cx, ky))
            drawCircle(Color.White.copy(alpha = 0.9f), 6f, Offset(cx, ky))
        }
        Txt(label, 13, TextLo, modifier = Modifier.height(34.dp).padding(top = 10.dp))
    }
}

private val bandWidthTouch = 44.dp

@Composable
private fun BalancePad(fx: AudioFx, accent: Color) {
    val (rw, rh) = remember { fx.balanceRect() }
    var pos by remember { mutableStateOf(fx.balancePos()) }
    val apply = { x: Int, y: Int -> pos = x to y; fx.setBalance(x, y) }
    Row(Modifier.fillMaxSize().padding(top = 14.dp), Arrangement.spacedBy(20.dp)) {
        Box(
            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(Surface2)
                .pointerInput(rw, rh) {
                    awaitEachGesture {
                        val d = awaitFirstDown()
                        fun go(o: Offset) = apply(
                            Math.round((o.x / size.width).coerceIn(0f, 1f) * rw),
                            Math.round((o.y / size.height).coerceIn(0f, 1f) * rh),
                        )
                        go(d.position); d.consume()
                        do {
                            val c = awaitPointerEvent().changes.first()
                            if (c.pressed) { go(c.position); c.consume() }
                        } while (c.pressed)
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val g = Color.White.copy(alpha = 0.12f)
                for (i in 1..3) {
                    drawLine(g, Offset(size.width * i / 4, 0f), Offset(size.width * i / 4, size.height), 2f)
                    drawLine(g, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 2f)
                }
                val p = Offset(pos.first.toFloat() / rw.coerceAtLeast(1) * size.width, pos.second.toFloat() / rh.coerceAtLeast(1) * size.height)
                drawLine(accent.copy(alpha = 0.45f), Offset(p.x, 0f), Offset(p.x, size.height), 3f)
                drawLine(accent.copy(alpha = 0.45f), Offset(0f, p.y), Offset(size.width, p.y), 3f)
                drawCircle(accent.copy(alpha = 0.25f), 46f, p)
                drawCircle(accent, 24f, p)
                drawCircle(Color.White.copy(alpha = 0.9f), 8f, p)
            }
            Txt("L", 20, TextLo, bold = true, modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp))
            Txt("R", 20, TextLo, bold = true, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp))
            Txt("F", 20, TextLo, bold = true, modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
            Txt("B", 20, TextLo, bold = true, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
        }
        Column(Modifier.width(180.dp).fillMaxHeight(), Arrangement.Center, Alignment.CenterHorizontally) {
            Chip(str(R.string.bal_center), false, accent, height = 52.dp, icon = G.SPLIT) { apply(rw / 2, rh / 2) }
        }
    }
}
