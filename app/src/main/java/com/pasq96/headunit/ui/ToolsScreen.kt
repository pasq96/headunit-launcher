package com.pasq96.headunit.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pasq96.headunit.R
import com.pasq96.headunit.tools.CarTools
import com.pasq96.headunit.tools.SysTop
import com.pasq96.headunit.tools.TopSnap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

private const val HISTORY = 90

/** Rolling window of samples behind a chart. */
private class Series { val v = mutableStateListOf<Float>(); fun add(x: Float?) { if (x == null) return; v.add(x); if (v.size > HISTORY) v.removeAt(0) } }

/** Full-screen car + head unit dashboard: battery, GPS, CPU, RAM, storage, processes and the screen level. */
@Composable
internal fun ToolsScreen(vm: LauncherHub, accent: Color) {
    val ctx = LocalContext.current
    val p by vm.prefs.collectAsStateWithLifecycle()
    val close = { vm.screen = null }
    val gps = remember(vm.gpsTick) { ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED }
    val st by remember(p.toolsIntervalSec) { CarTools.stats(ctx, p.toolsIntervalSec * 1000L) }.collectAsStateWithLifecycle(null)
    val speed by remember(gps) { if (gps) CarTools.speedKmh(ctx) else flowOf(null) }.collectAsStateWithLifecycle(null)
    val speedNow by rememberUpdatedState(speed)
    val battery = remember { Series() }; val temp = remember { Series() }; val ram = remember { Series() }
    val load = remember { Series() }; val spd = remember { Series() }
    var top by remember { mutableStateOf<TopSnap?>(null) }
    var topTried by remember { mutableStateOf(false) }

    LaunchedEffect(st) { st?.let { s -> battery.add(s.battV); temp.add(s.cpuC); ram.add((s.ramTotalMb - s.ramFreeMb).toFloat()) } }
    LaunchedEffect(gps) { while (true) { spd.add(speedNow); delay(p.toolsIntervalSec * 1000L) } }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { CarTools.probeScreen(ctx) }
        while (true) {
            top = withContext(Dispatchers.IO) { SysTop.snapshot(ctx) }
            topTried = true
            load.add(top?.load)
            delay(3_000)
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Labeled(str(R.string.tools_title), G.GAUGE, TextHi, 26, bold = true, glyph = 32.dp)
                GlyphButton(G.CLOSE, TextHi, close)
            }
            Row(Modifier.fillMaxSize(), Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), Arrangement.spacedBy(12.dp)) {
                    val v = st?.battV
                    Metric(str(R.string.stat_battery), v?.let { "%.1f V".format(it) } ?: str(R.string.stat_na), G.POWER, battery.v, accent, lo = null)
                    if (gps) Metric(str(R.string.stat_gps_speed), speed?.let { "${it.roundToInt()} km/h" } ?: str(R.string.stat_no_fix), G.NAV, spd.v, accent, lo = 0f)
                    else Card { Chip(str(R.string.gps_enable), false, accent, icon = G.NAV) { vm.requestLocation() } }
                    ScreenCard(vm, accent)
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), Arrangement.spacedBy(12.dp)) {
                    val t = st?.cpuC
                    Metric(str(R.string.stat_cpu_temp), t?.let { "${it.roundToInt()} °C" } ?: str(R.string.stat_na), G.GAUGE, temp.v, accent, if (t != null && t >= p.tempWarnC) Warn else TextHi)
                    Metric(str(R.string.tools_cpu_load), top?.load?.let { "${it.roundToInt()} %" } ?: str(R.string.stat_na), G.DATA, load.v, accent, lo = 0f, hi = 100f)
                    val total = st?.ramTotalMb ?: 0L; val used = total - (st?.ramFreeMb ?: 0L)
                    Metric(str(R.string.tools_ram), if (total > 0) "$used / $total MB" else "…", G.DATA, ram.v, accent, lo = 0f, hi = total.toFloat().takeIf { it > 0 }, bar = if (total > 0) used.toFloat() / total else null)
                    Storage(accent)
                    Uptime(st)
                }
                Card(Modifier.weight(1.15f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Labeled(str(R.string.tools_processes), G.DATA, TextLo, 15)
                        top?.tasks?.let { Txt(str(R.string.tools_tasks, it), 14, TextLo) }
                    }
                    val rows = top?.rows.orEmpty()
                    if (rows.isEmpty()) Txt(str(if (!topTried) R.string.stat_na else if (SysTop.failure != null) R.string.tools_top_adb else R.string.tools_top_na), 14, TextLo, maxLines = 3, modifier = Modifier.padding(top = 10.dp))
                    LazyColumn(Modifier.fillMaxSize().padding(top = 6.dp)) {
                        itemsIndexed(rows, key = { i, r -> "$i-${r.name}" }) { _, r ->
                            Row(Modifier.fillMaxWidth().height(40.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { Txt(r.name, 15, TextHi) }
                                Txt("%.0f%%".format(r.cpu), 14, if (r.cpu >= 50f) Warn else TextLo, bold = true)
                                if (r.ramMb > 0) Txt("${r.ramMb} MB", 14, TextLo)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface2).padding(14.dp)) { content() }

/** Title + big value + optional fill bar + sparkline. */
@Composable
private fun Metric(
    title: String, value: String, icon: G, data: List<Float>, accent: Color, valueColor: Color = TextHi,
    lo: Float? = null, hi: Float? = null, bar: Float? = null,
) = Card {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Labeled(title, icon, TextLo, 15)
        Txt(value, 26, valueColor, bold = true)
    }
    if (bar != null) Bar(bar, accent, Modifier.padding(top = 8.dp))
    Spark(data, accent, Modifier.fillMaxWidth().padding(top = 8.dp).height(54.dp), lo, hi)
}

@Composable
private fun Bar(frac: Float, accent: Color, modifier: Modifier = Modifier, h: Dp = 10.dp) =
    Box(modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(h / 2)).background(Surface)) {
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().background(accent))
    }

@Composable
private fun Spark(data: List<Float>, accent: Color, modifier: Modifier, lo: Float?, hi: Float?) {
    Canvas(modifier) {
        if (data.size < 2) return@Canvas
        val mn = lo ?: (data.minOrNull() ?: 0f)
        val top = hi ?: (data.maxOrNull() ?: 1f)
        val mx = if (top - mn < 1e-3f) mn + 1f else top
        val pts = data.mapIndexed { i, v -> Offset(size.width * i / (data.size - 1), size.height * (1f - ((v - mn) / (mx - mn)).coerceIn(0f, 1f))) }
        val line = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y) }
        val fill = Path().apply { addPath(line); lineTo(pts.last().x, size.height); lineTo(pts[0].x, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), Color.Transparent)))
        drawPath(line, accent, style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(accent, 6f, pts.last())
    }
}

@Composable
private fun Storage(accent: Color) {
    val (free, total) = remember {
        runCatching { StatFs(Environment.getDataDirectory().path).let { it.availableBytes to it.totalBytes } }.getOrDefault(0L to 0L)
    }
    Card {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Labeled(str(R.string.tools_storage), G.FOLDER, TextLo, 15)
            Txt(if (total > 0) "%.1f / %.1f GB".format((total - free) / 1e9, total / 1e9) else str(R.string.stat_na), 20, TextHi, bold = true)
        }
        if (total > 0) Bar((total - free).toFloat() / total, accent, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Uptime(st: Any?) {
    val up = remember(st) { SystemClock.elapsedRealtime() / 60_000 }
    Card {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Labeled(str(R.string.tools_uptime), G.CLOCK, TextLo, 15)
            Txt("%d:%02d".format(up / 60, up % 60), 20, TextHi, bold = true)
        }
    }
}

/** Brightness as a share of the panel's own range; applied while dragging (throttled) and checked after writing. */
@Composable
private fun ScreenCard(vm: LauncherHub, accent: Color) {
    val ctx = LocalContext.current
    val canWrite = CarTools.canWriteSettings(ctx)
    var level by remember { mutableFloatStateOf(CarTools.brightnessFraction(ctx)) }
    var applied by remember { mutableFloatStateOf(level) }
    var failed by remember { mutableStateOf(false) }
    val commit = { f: Float -> applied = f; vm.brightOverride = f.coerceAtLeast(0.03f); failed = !CarTools.setBrightnessFraction(ctx, f) }
    Card {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Labeled(str(R.string.sec_screen), G.SUN, TextLo, 15)
            if (canWrite) Txt("${(level * 100).roundToInt()} %", 22, TextHi, bold = true)
        }
        if (!canWrite) Box(Modifier.padding(top = 8.dp)) {
            Chip(str(R.string.write_grant), false, accent, icon = G.SUN) {
                ctx.openOutside(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }
        } else {
            SimpleSlider(level, accent, { level = it; if (abs(it - applied) > 0.03f) commit(it) }, onDone = { commit(level) })
            
        }
    }
}
