package com.pasq96.headunit.tools

import android.content.Context
import com.pasq96.headunit.system.InternalAdb

class TopRow(val name: String, val cpu: Float, val ramMb: Int)
class TopSnap(val load: Float?, val tasks: Int?, val rows: List<TopRow>)

/** Process list and CPU load: apps cannot read /proc on Android 11, so `top` runs over the internal adb channel (one session kept). */
object SysTop {
    @Volatile private var session: InternalAdb.Session? = null

    @Synchronized
    fun snapshot(ctx: Context): TopSnap? {
        repeat(2) {
            val s = session ?: runCatching { InternalAdb.open(ctx) }.getOrNull()?.also { session = it } ?: return null
            val out = runCatching { String(s.exec("top -b -n 1 -m 14")) }.getOrNull()
            if (!out.isNullOrBlank()) return parse(out)
            runCatching { s.close() }; session = null
        }
        return null
    }

    private val CPU_TOTAL = Regex("(\\d+)%cpu")
    private val CPU_IDLE = Regex("(\\d+)%idle")
    private val TASKS = Regex("Tasks:\\s*(\\d+)")
    private val SIZE = Regex("^([\\d.]+)([KMGkmg]?)$")

    internal fun parse(out: String): TopSnap {
        val total = CPU_TOTAL.find(out)?.groupValues?.get(1)?.toFloatOrNull()
        val idle = CPU_IDLE.find(out)?.groupValues?.get(1)?.toFloatOrNull()
        val load = if (total != null && idle != null && total > 0f) (100f * (1f - idle / total)).coerceIn(0f, 100f) else null
        val rows = out.lineSequence().mapNotNull { line ->
            // PID USER PR NI VIRT RES SHR S %CPU %MEM TIME+ ARGS
            val t = line.trim().split(Regex("\\s+"))
            if (t.size < 12 || t[0].toIntOrNull() == null) return@mapNotNull null
            val cpu = t[8].toFloatOrNull() ?: return@mapNotNull null
            val name = t.drop(11).joinToString(" ").substringBefore(' ')
            if (name == "top") null else TopRow(name, cpu, mb(t[5]))
        }.toList()
        return TopSnap(load, TASKS.find(out)?.groupValues?.get(1)?.toIntOrNull(), rows)
    }

    private fun mb(s: String): Int {
        val m = SIZE.find(s) ?: return 0
        val v = m.groupValues[1].toFloatOrNull() ?: return 0
        return when (m.groupValues[2].lowercase()) { "g" -> v * 1024; "m" -> v; else -> v / 1024 }.toInt()
    }
}
