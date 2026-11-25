package com.pasq96.headunit.ui

import kotlinx.coroutines.launch
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.Animatable
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pasq96.headunit.R
import com.pasq96.headunit.radio.Band
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil

/** 97500 → "97.50" (FM always shows two decimals, as the stock radio does); AM stays in kHz. */
internal fun fmtFreq(khz: Int, unit: Boolean): String {
    if (khz <= 0) return "--.-"
    if (khz < 2000) return if (unit) "$khz kHz" else "$khz"
    val mhz = BigDecimal(khz).movePointLeft(3).setScale(2, RoundingMode.HALF_UP)
    return if (unit) "$mhz MHz" else mhz.toPlainString()
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RadioPanel(vm: LauncherHub, accent: Color, modifier: Modifier) {
    val r by vm.radio.state.collectAsStateWithLifecycle()
    val am = r.band == Band.AM

    // Tile = glance view: frequency, station name and prev/next. Everything else lives in the full radio section (tap).
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Surface).padding(12.dp), Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().weight(1f).clickable { vm.radioScreen = true }, Arrangement.Center, Alignment.CenterHorizontally) {
            Txt(if (am) "AM" else "FM", 15, TextLo, bold = true, align = TextAlign.Center)
            Txt(fmtFreq(r.freqKhz, unit = true), 52, bold = true, align = TextAlign.Center)
            Txt(r.ps ?: "", 26, accent, bold = true, align = TextAlign.Center)
            // Radio text scrolls only when wider than the panel.
            BasicText(
                r.rt ?: "", Modifier.fillMaxWidth().padding(top = 4.dp).basicMarquee(iterations = Int.MAX_VALUE),
                TextStyle(color = TextLo, fontSize = 17.sp, textAlign = TextAlign.Center), softWrap = false, maxLines = 1,
            )
            if (!r.connected) Txt(str(R.string.radio_no_data), 12, Warn, align = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
        }
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp)) {
            RadioBtn(Modifier.weight(1f), false, accent, { vm.radio.seekDown() }) { Glyph(G.SEEK_DOWN, TextHi, 40.dp) }
            RadioBtn(Modifier.weight(1f), false, accent, { vm.radio.seekUp() }) { Glyph(G.SEEK_UP, TextHi, 40.dp) }
        }
    }
}

/** Full radio section, opened from the radio tile: same data as the tile at full size, plus the shortcut to the stock app. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RadioScreen(vm: LauncherHub, accent: Color) {
    val r by vm.radio.state.collectAsStateWithLifecycle()
    val p by vm.prefs.collectAsStateWithLifecycle()
    val tune = vm.radio.canTune
    val am = r.band == Band.AM
    val step = if (am) p.amStepKhz else p.fmStepKhz
    val close = { vm.radioScreen = false }
    val drawerW = 280.dp
    val drawerPx = with(LocalDensity.current) { drawerW.toPx() + 8.dp.toPx() }
    val prog = remember { Animatable(0f) }          // 0 = hidden, 1 = shown; follows the finger while dragging
    val scope = rememberCoroutineScope()
    val settle = { to: Float -> scope.launch { prog.animateTo(to, tween(240)) }; Unit }
    val open = prog.value > 0.5f
    Box(Modifier.fillMaxSize().background(Bg)) {
    // the radio's own memory when it reports one (like the stock app), otherwise the launcher's own list
    val stock = r.stockPresets.take(p.presetCount)
    Row(Modifier.fillMaxSize().padding(start = 52.dp, top = 24.dp, end = 24.dp, bottom = 24.dp).pointerInput(Unit) { detectTapGestures { if (prog.value > 0.5f) settle(0f) } }, Arrangement.spacedBy(20.dp)) {
    Column(Modifier.weight(1f).fillMaxHeight(), Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
            if (tune) Band.entries.forEach { b -> Chip(b.label, r.band == b, accent, height = 56.dp) { vm.radio.setBand(b) } }
            else Chip(if (am) "AM" else "FM", true, accent, height = 56.dp) { vm.radio.toggleBand() }
            Box(Modifier.width(14.dp))
            Chip("ST", r.stereoOn, accent, height = 56.dp) { vm.radio.toggleStereo() }
            if (tune) {
                Chip("LOC", r.loc, accent, height = 56.dp) { vm.radio.toggleLoc() }
                Chip("AF", r.af, accent, height = 56.dp) { vm.radio.toggleAf() }
                Chip("TA", r.ta, accent, height = 56.dp) { vm.radio.toggleTa() }
            }
            Box(Modifier.weight(1f))
            GlyphButton(G.RADIO, TextHi, { close(); vm.radio.openStockApp() }, glyph = 30.dp)
            GlyphButton(G.CLOSE, TextHi, close)
        }
        Column(Modifier.fillMaxWidth().weight(1f), Arrangement.Center, Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(fmtFreq(r.freqKhz, unit = false), 104, bold = true, align = TextAlign.Center)
                Txt(if (r.band == Band.AM) "kHz" else "MHz", 30, TextLo, bold = true, modifier = Modifier.padding(bottom = 18.dp))
            }
            Txt(r.ps ?: "", 34, accent, bold = true, align = TextAlign.Center)
            BasicText(
                r.rt ?: "", Modifier.fillMaxWidth().padding(top = 6.dp).basicMarquee(iterations = Int.MAX_VALUE),
                TextStyle(color = TextLo, fontSize = 20.sp, textAlign = TextAlign.Center), softWrap = false, maxLines = 1,
            )
            if (!r.connected) Txt(str(R.string.radio_no_data), 14, Warn, align = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
        }
        if (tune) TunerBar(r.freqKhz, r.band, step, accent, if (stock.isNotEmpty()) stock else p.presets.filter { it > 0 }) { vm.radio.setFrequency(it) } else PositionBar(r.freqKhz, r.band, accent)
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
            RadioBtn(Modifier.weight(1f), false, accent, { vm.radio.seekDown() }) { Glyph(G.SEEK_DOWN, TextHi, 44.dp) }
            if (tune) {
                RadioBtn(Modifier.weight(1f), false, accent, { vm.radio.step(false, step) }) { Glyph(G.STEP_DOWN, TextHi, 40.dp) }
                RadioBtn(Modifier.weight(1f), r.scanning, accent, { if (r.scanning) vm.radio.stopSeek() else vm.radio.autoScan() }) { Txt(if (r.scanning) "■" else "SCAN", 20, if (r.scanning) onAccent(accent) else TextHi, bold = true) }
                RadioBtn(Modifier.weight(1f), false, accent, { vm.radio.step(true, step) }) { Glyph(G.STEP_UP, TextHi, 40.dp) }
            }
            RadioBtn(Modifier.weight(1f), false, accent, { vm.radio.seekUp() }) { Glyph(G.SEEK_UP, TextHi, 44.dp) }
        }
    }
    }
    // slide-out saved stations: pull the tab (or tap it) to show, tap the radio or pull back to hide
    Row(
        Modifier.align(Alignment.CenterStart).offset { IntOffset((-(1f - prog.value) * drawerPx).roundToInt(), 0) }.fillMaxHeight().padding(vertical = 16.dp).padding(start = 8.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = { settle(if (prog.value > 0.5f) 1f else 0f) }, onDragCancel = { settle(if (prog.value > 0.5f) 1f else 0f) }) { c, d ->
                    c.consume(); scope.launch { prog.snapTo((prog.value + d / drawerPx).coerceIn(0f, 1f)) }
                }
            },
    ) {
        val list = if (stock.isNotEmpty()) stock.mapIndexed { i, f -> i to f } else p.presets.withIndex().filter { it.index < p.presetCount && it.value > 0 }.map { it.index to it.value }
        PresetColumn(list, r.freqKhz, accent, Modifier.width(drawerW).fillMaxHeight(), if (stock.isEmpty()) ({ vm.saveCurrentPreset() }) else null, { vm.radio.setFrequency(it) }, if (stock.isEmpty()) ({ vm.clearPreset(it) }) else null)
        Box(
            Modifier.align(Alignment.CenterVertically).width(40.dp).height(104.dp).clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)).background(if (open) accent else Surface)
                .clickable { settle(if (prog.value > 0.5f) 0f else 1f) },
            Alignment.Center,
        ) { Glyph(if (open) G.CHEV_L else G.CHEV_R, if (open) onAccent(accent) else TextHi, 30.dp) }
    }
    }
}

/** Saved stations as a scrollable list: tap tunes, "Save" stores the tuned frequency, the cross removes an entry. */
@Composable
private fun PresetColumn(entries: List<Pair<Int, Int>>, tuned: Int, accent: Color, modifier: Modifier, onSave: (() -> Unit)?, onTap: (Int) -> Unit, onDelete: ((Int) -> Unit)?) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Surface).padding(12.dp), Arrangement.spacedBy(8.dp)) {
        Txt(str(R.string.radio_presets), 16, TextLo, bold = true, modifier = Modifier.padding(start = 4.dp))
        if (onSave != null) Box(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(14.dp)).background(accent).clickable(onClick = onSave), Alignment.Center) {
            Txt("+ " + str(R.string.radio_save), 18, onAccent(accent), bold = true)
        }
        if (entries.isEmpty()) Txt(str(R.string.radio_presets_empty), 14, TextLo, maxLines = 3, modifier = Modifier.padding(4.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.first }) { (slot, f) ->
                val on = f == tuned
                Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(14.dp)).background(if (on) accent else Surface2), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).fillMaxHeight().clickable { onTap(f) }.padding(start = 16.dp), Alignment.CenterStart) {
                        Txt(fmtFreq(f, unit = false), 26, if (on) onAccent(accent) else TextHi, bold = true)
                    }
                    if (onDelete != null) Box(Modifier.size(64.dp).clickable { onDelete(slot) }, Alignment.Center) { Glyph(G.CLOSE, if (on) onAccent(accent) else TextLo, 22.dp) }
                }
            }
        }
    }
}

/** Read-only ruler like the stock one: a tick per step across the band, the needle on the tuned frequency. */
@Composable
private fun PositionBar(freqKhz: Int, band: Band, accent: Color) {
    val am = band == Band.AM
    val (lo, hi) = if (am) 522 to 1710 else 87_500 to 108_000
    val step = if (am) 9 else 100
    val f = ((freqKhz - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
    val minor = TextLo; val major = TextHi
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(48.dp)) {
            val n = (hi - lo) / step
            for (i in 0..n) {
                val x = size.width * i / n
                val big = i % 10 == 0
                drawLine(if (big) major else minor, Offset(x, size.height * (if (big) 0.1f else 0.4f)), Offset(x, size.height * 0.9f), if (big) 3f else 2f)
            }
            val nx = size.width * f
            drawLine(accent, Offset(nx, 0f), Offset(nx, size.height), 8f)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), Arrangement.SpaceBetween) {
            Txt(fmtFreq(lo, unit = false), 12, TextLo); Txt(fmtFreq(hi, unit = false), 12, TextLo)
        }
    }
}

@Composable
private fun RadioBtn(modifier: Modifier, primary: Boolean, accent: Color, onClick: () -> Unit, content: @Composable () -> Unit) =
    Box(modifier.height(72.dp).clip(RoundedCornerShape(16.dp)).background(if (primary) accent else Surface2).clickable(onClick = onClick), Alignment.Center) { content() }

/**
 * Scrollable tuning ruler: drag left to go up in frequency, right to go down. The ruler follows the finger; the new
 * frequency is sent while dragging (throttled) and once more on release. Without a finger it glides to every new
 * frequency (scan, seek), and saved stations show as dots on the scale.
 */
@Composable
private fun TunerBar(freqKhz: Int, band: Band, stepKhz: Int, accent: Color, marks: List<Int>, onTune: (Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    val (lo, hi) = if (band == Band.AM) 522 to 1710 else 87_500 to 108_000
    val pxPerStep = with(LocalDensity.current) { 22.dp.toPx() }
    var drag by remember { mutableIntStateOf(-1) }       // frequency under the finger, -1 = not dragging
    var shift by remember { mutableFloatStateOf(0f) }     // sub-step scroll offset in px
    val sentAt = remember { longArrayOf(0L) }
    val freq by rememberUpdatedState(freqKhz)
    val tune by rememberUpdatedState(onTune)
    val glide by animateFloatAsState(freqKhz.toFloat(), tween(320), label = "tuner")
    val tick = TextLo
    val labelStyle = TextStyle(color = tick, fontSize = 11.sp)

    Canvas(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(12.dp)).background(Surface2)
            .pointerInput(band, stepKhz) {
                detectHorizontalDragGestures(
                    onDragStart = { if (freq > 0) { drag = freq; shift = 0f } },
                    onDragEnd = { if (drag >= 0) tune(drag); drag = -1; shift = 0f },
                    onDragCancel = { drag = -1; shift = 0f },
                ) { change, dx ->
                    if (drag < 0) return@detectHorizontalDragGestures
                    change.consume()
                    shift -= dx
                    val steps = (shift / pxPerStep).toInt()
                    if (steps != 0) {
                        shift -= steps * pxPerStep
                        drag = (drag + steps * stepKhz).coerceIn(lo, hi)
                        val now = System.currentTimeMillis()
                        if (now - sentAt[0] > 200) { tune(drag); sentAt[0] = now }
                    }
                }
            },
    ) {
        val anchor = if (drag >= 0) drag else freqKhz
        if (anchor <= 0) return@Canvas
        // value under the needle: the finger position while dragging, the gliding frequency otherwise
        val centre = if (drag >= 0) drag + shift / pxPerStep * stepKhz else glide
        val cx = size.width / 2
        val n = ceil(cx / pxPerStep).toInt() + 2
        val mid = ((centre - anchor) / stepKhz).roundToInt()
        fun xOf(f: Float) = cx + (f - centre) / stepKhz * pxPerStep
        for (i in (mid - n)..(mid + n)) {
            val f = anchor + i * stepKhz
            if (f < lo || f > hi) continue
            val x = xOf(f.toFloat())
            val major = if (band == Band.AM) ((f - lo) / stepKhz) % 10 == 0 else f % 1000 == 0
            val h = if (major) size.height * 0.45f else size.height * 0.22f
            drawLine(tick, Offset(x, size.height - h), Offset(x, size.height), if (major) 3f else 2f)
            if (major) {
                val text = if (band == Band.AM) "$f" else "${f / 1000}"
                val layout = measurer.measure(text, labelStyle)
                drawText(layout, topLeft = Offset(x - layout.size.width / 2f, 4f))
            }
        }
        marks.forEach { m ->
            val x = xOf(m.toFloat())
            if (x in 0f..size.width) drawCircle(accent, 5f, Offset(x, size.height - 8f))
        }
        drawLine(accent, Offset(cx, 0f), Offset(cx, size.height), 4f)
        drawPath(Path().apply { moveTo(cx - 8f, 0f); lineTo(cx + 8f, 0f); lineTo(cx, 12f); close() }, accent)
    }
}
