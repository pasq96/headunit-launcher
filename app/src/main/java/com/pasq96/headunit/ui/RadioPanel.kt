package com.pasq96.headunit.ui

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.pasq96.headunit.data.Prefs
import com.pasq96.headunit.radio.Band
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil

/** 102500 → "102.5" (two decimals only when needed); AM stays in kHz. */
internal fun fmtFreq(khz: Int, unit: Boolean): String {
    if (khz <= 0) return "--.-"
    if (khz < 2000) return if (unit) "$khz kHz" else "$khz"
    val mhz = BigDecimal(khz).movePointLeft(3).let { if (khz % 100 == 0) it.setScale(1, RoundingMode.HALF_UP) else it.setScale(2, RoundingMode.HALF_UP) }
    return if (unit) "$mhz MHz" else mhz.toPlainString()
}

private fun stepLabel(khz: Int) = if (khz < 100 && khz != 50) "$khz kHz" else BigDecimal(khz).movePointLeft(3).stripTrailingZeros().toPlainString()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RadioPanel(vm: LauncherHub, p: Prefs, accent: Color, modifier: Modifier) {
    val r by vm.radio.state.collectAsStateWithLifecycle()
    val am = r.band == Band.AM
    val step = if (am) p.amStepKhz else p.fmStepKhz
    val label = stepLabel(step)
    val tune = vm.radio.canTune

    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Surface).padding(12.dp), Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
            if (tune) {
                Band.entries.forEach { b -> Chip(b.label, r.band == b, accent, height = 56.dp) { vm.radio.setBand(b) } }
                Chip("AF", r.af, accent, height = 56.dp) { vm.radio.toggleAf() }
                Chip("TA", r.ta, accent, height = 56.dp) { vm.radio.toggleTa() }
            } else Chip(if (r.band == Band.AM) "AM" else "FM", true, accent, height = 56.dp) { vm.radio.toggleBand() }
            Box(Modifier.weight(1f), Alignment.CenterEnd) {
                Txt(str(R.string.radio_stock), 13, TextLo, modifier = Modifier.clickable { vm.radio.openStockApp() }.padding(12.dp))
            }
        }

        Column(Modifier.fillMaxWidth().weight(1f), Arrangement.Center, Alignment.CenterHorizontally) {
            Txt(fmtFreq(r.freqKhz, unit = true), 52, bold = true, align = TextAlign.Center)
            Txt(r.ps ?: "", 26, accent, bold = true, align = TextAlign.Center)
            // Radio text scrolls only when wider than the panel.
            BasicText(
                r.rt ?: "", Modifier.fillMaxWidth().padding(top = 4.dp).basicMarquee(iterations = Int.MAX_VALUE),
                TextStyle(color = TextLo, fontSize = 17.sp, textAlign = TextAlign.Center), softWrap = false, maxLines = 1,
            )
            if (!r.connected) Txt(str(R.string.radio_no_data), 12, Warn, align = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
        }

        if (tune) TunerBar(r.freqKhz, r.band, step, accent) { vm.radio.setFrequency(it) } else PositionBar(r.freqKhz, r.band, accent)

        if (!tune) Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp)) {
            RadioBtn(Modifier.weight(1f), false, accent, { vm.radio.previousStation() }) { Glyph(G.SEEK_DOWN, TextHi, 40.dp) }
            RadioBtn(Modifier.weight(1f), false, accent, { vm.radio.nextStation() }) { Glyph(G.SEEK_UP, TextHi, 40.dp) }
        } else Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp)) {
            RadioBtn(Modifier.weight(1f), false, accent, { vm.radio.seekDown() }) { Glyph(G.SEEK_DOWN, TextHi, 36.dp) }
            RadioBtn(Modifier.weight(1f), false, accent, { vm.radio.step(false, step) }) { Txt("−$label", 17, bold = true) }
            RadioBtn(Modifier.weight(1f), true, accent, { vm.radio.toggleMute() }) { Glyph(if (r.muted) G.PLAY else G.PAUSE, OnAccent, 36.dp) }
            RadioBtn(Modifier.weight(1f), false, accent, { vm.radio.step(true, step) }) { Txt("+$label", 17, bold = true) }
            RadioBtn(Modifier.weight(1f), false, accent, { vm.radio.seekUp() }) { Glyph(G.SEEK_UP, TextHi, 36.dp) }
        }

        if (tune) p.presets.chunked(4).forEachIndexed { row, slots ->
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp)) {
                slots.forEachIndexed { i, f ->
                    val slot = row * 4 + i
                    Box(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (f > 0 && f == r.freqKhz) accent else Surface2)
                            .androidxCombined(onClick = { vm.recallPreset(slot) }, onLong = { vm.togglePreset(slot) }),
                        Alignment.Center,
                    ) { Txt(if (f > 0) fmtFreq(f, unit = false) else "+", 18, if (f > 0 && f == r.freqKhz) OnAccent else if (f > 0) TextHi else TextLo, bold = true) }
                }
            }
        }
        if (tune) Txt(str(R.string.radio_hint), 12, TextLo, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
    }
}

/** Read-only position of the tuned frequency inside the band. */
@Composable
private fun PositionBar(freqKhz: Int, band: Band, accent: Color) {
    val (lo, hi) = if (band == Band.AM) 522 to 1710 else 87_500 to 108_000
    val f = ((freqKhz - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Surface2)) {
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(accent))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), Arrangement.SpaceBetween) {
            Txt(fmtFreq(lo, unit = false), 12, TextLo); Txt(fmtFreq(hi, unit = false), 12, TextLo)
        }
    }
}

@Composable
private fun RadioBtn(modifier: Modifier, primary: Boolean, accent: Color, onClick: () -> Unit, content: @Composable () -> Unit) =
    Box(modifier.height(64.dp).clip(RoundedCornerShape(14.dp)).background(if (primary) accent else Surface2).clickable(onClick = onClick), Alignment.Center) { content() }

/**
 * Scrollable tuning ruler: drag left to go up in frequency, right to go down. The ruler follows the finger; the new
 * frequency is sent while dragging (throttled) and once more on release.
 */
@Composable
private fun TunerBar(freqKhz: Int, band: Band, stepKhz: Int, accent: Color, onTune: (Int) -> Unit) {
    val measurer = rememberTextMeasurer()
    val (lo, hi) = if (band == Band.AM) 522 to 1710 else 87_500 to 108_000
    val pxPerStep = with(LocalDensity.current) { 22.dp.toPx() }
    var drag by remember { mutableIntStateOf(-1) }       // frequency under the finger, -1 = not dragging
    var shift by remember { mutableFloatStateOf(0f) }     // sub-step scroll offset in px
    val sentAt = remember { longArrayOf(0L) }
    val freq by rememberUpdatedState(freqKhz)
    val tune by rememberUpdatedState(onTune)
    val shown = if (drag >= 0) drag else freqKhz
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
        if (shown <= 0) return@Canvas
        val cx = size.width / 2
        val n = ceil(cx / pxPerStep).toInt() + 1
        for (i in -n..n) {
            val f = shown + i * stepKhz
            if (f < lo || f > hi) continue
            val x = cx + i * pxPerStep - shift
            val major = if (band == Band.AM) ((f - lo) / stepKhz) % 10 == 0 else f % 1000 == 0
            val h = if (major) size.height * 0.45f else size.height * 0.22f
            drawLine(tick, Offset(x, size.height - h), Offset(x, size.height), if (major) 3f else 2f)
            if (major) {
                val text = if (band == Band.AM) "$f" else "${f / 1000}"
                val layout = measurer.measure(text, labelStyle)
                drawText(layout, topLeft = Offset(x - layout.size.width / 2f, 4f))
            }
        }
        drawLine(accent, Offset(cx, 0f), Offset(cx, size.height), 4f)
        drawPath(Path().apply { moveTo(cx - 8f, 0f); lineTo(cx + 8f, 0f); lineTo(cx, 12f); close() }, accent)
    }
}
