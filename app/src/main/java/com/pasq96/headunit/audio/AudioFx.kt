package com.pasq96.headunit.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class EqInfo(val hzs: List<String>, val min: Int, val max: Int, val styles: List<Int>, val titles: List<String>)

/**
 * Firmware audio effects (com.imotor.framework.media.IMAudioManager, boot classpath), same calls as the stock
 * SoundEqualizer app: per-style band values, loudness, subwoofer and the 2-D balance point. Reflection only.
 */
class AudioFx(ctx: Context) {
    private val app = ctx.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val pending = LinkedHashMap<String, () -> Unit>()
    private var running = false
    private var mgr: Any? = null
    private var hw: Any? = null
    private var logged = 0
    @Volatile private var err: String? = null

    /** Last firmware call that failed (cleared by the next success), shown on the equalizer screen. */
    fun error(): String? = err

    private fun manager(): Any? = mgr ?: synchronized(this) {
        mgr ?: runCatching { Class.forName(AUDIO).getMethod("getInstance", Context::class.java).invoke(null, app) }
            .onFailure { Log.w(TAG, "IMAudioManager: ${it.cause ?: it}"); err = "IMAudioManager: ${(it.cause ?: it).javaClass.simpleName} ${(it.cause ?: it).message}" }.getOrNull()?.also { fixIdentity(it) }.also { mgr = it }
    }

    /**
     * The firmware manager may keep a system Context (package "android"), so its binder calls are rejected for our uid
     * ("Package android does not belong to ..."): every Context / package-name field is pointed back at this app.
     */
    private fun fixIdentity(o: Any, depth: Int = 0) {
        var c: Class<*>? = o.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) runCatching {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers) && f.type != Context::class.java) return@runCatching
                f.isAccessible = true
                val v = f.get(o)
                if (depth == 0) Log.i(TAG, "field ${c!!.simpleName}.${f.name}:${f.type.simpleName}=${if (v is String || v is Context) v else v?.javaClass?.simpleName}")
                when {
                    v is String && v == "android" -> f.set(o, app.packageName)
                    v is Context && v.packageName != app.packageName -> f.set(o, app)
                    depth == 0 && v != null && !f.type.isPrimitive && v.javaClass.name.startsWith("com.imotor") -> fixIdentity(v, 1)
                }
            }
            c = c.superclass
        }
    }

    private fun call(name: String, vararg a: Any?): Any? {
        val o = manager() ?: return null
        return runCatching {
            o.javaClass.methods.first { it.name == name && it.parameterCount == a.size }.invoke(o, *a)
        }.recoverCatching {
            // the service applies the values first and only then broadcasts ACTION_EQ_CHANGED as the system context while
            // still running under our uid, which AppOps rejects: the DSP already has the change, so this one is not a failure
            if (((it.cause ?: it).message ?: "").contains("does not belong to")) { if (logged++ < 5) Log.i(TAG, "$name applied, broadcast refused: ${(it.cause ?: it).message}"); Unit }
            else throw it
        }.onFailure { if (logged++ < 20) Log.w(TAG, "$name: ${it.cause ?: it}"); err = "$name: ${(it.cause ?: it).javaClass.simpleName} ${(it.cause ?: it).message}" }
            .onSuccess { err = null }.getOrNull()
    }

    private fun ints(o: Any?): IntArray? = o as? IntArray

    /** Static description of the equalizer; null when the firmware API is not there. */
    fun info(): EqInfo? {
        val hzs = (call("getEqItemHzs") as? Array<*>)?.map { it.toString() } ?: return null
        val r = call("getEqItemRange")
        fun bound(n: String, def: Int) = r?.let { runCatching { it.javaClass.getMethod(n).invoke(it) as Int }.getOrNull() } ?: def
        val styles = ints(call("getEqStyles"))?.toList().orEmpty()
        return EqInfo(hzs, bound("min", -15), bound("max", 15), styles, styleTitles(styles))
    }

    /** Style names come from the stock app's own array, parallel to getEqStyles(). */
    private fun styleTitles(styles: List<Int>): List<String> {
        val names = runCatching {
            val res = app.packageManager.getResourcesForApplication(STOCK_EQ)
            res.getStringArray(res.getIdentifier("effect_title", "array", STOCK_EQ)).toList()
        }.getOrDefault(emptyList())
        return styles.mapIndexed { i, s -> names.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "EQ $s" }
    }

    fun currentStyle(): Int = (call("getCurrentEqStyle") as? Int) ?: CUSTOM
    fun values(style: Int): IntArray? = ints(call("getEqValues", style))?.copyOf()

    /** Selecting a style = writing its own values back, as the stock title buttons do. */
    fun select(style: Int): IntArray? = values(style)?.also { call("setEqValues", style, it) }

    fun reset(): IntArray? {
        val flat = values(FLAT) ?: return null
        call("setEqValues", CUSTOM, flat)
        return values(CUSTOM)
    }

    /** Slider drags arrive faster than the DSP wants them: only the latest value per key goes out, every 40 ms. */
    private fun queue(key: String, block: () -> Unit) {
        synchronized(lock) { pending[key] = block; if (running) return; running = true }
        scope.launch {
            while (true) {
                delay(40)
                val batch = synchronized(lock) { val l = pending.values.toList(); pending.clear(); if (l.isEmpty()) running = false; l }
                if (batch.isEmpty()) return@launch
                batch.forEach { runCatching(it) }
            }
        }
    }

    fun setValues(style: Int, v: IntArray) = queue("eq") { call("setEqValues", style, v) }

    fun loudness(): Boolean = call("isLoudnessOn") == true
    fun setLoudness(on: Boolean) { scope.launch { call("setLoudnessOn", on) } }

    fun subSupported(): Boolean = runCatching {
        val h = hw ?: Class.forName(HARDWARE).getMethod("getInstance", Context::class.java).invoke(null, app).also { hw = it }
        h!!.javaClass.getMethod("supportSubwooferMute").invoke(h) == true
    }.getOrDefault(false)
    fun sub(): Boolean = call("isSubwooferEnabled") == true
    fun setSub(on: Boolean) { scope.launch { call("setSubwooferEnabled", on) } }

    /** Size of the balance field, in the firmware's own units. */
    fun balanceRect(): Pair<Int, Int> = ints(call("getBalanceRectWidthHeight"))?.takeIf { it.size >= 2 }?.let { it[0] to it[1] } ?: (100 to 100)
    fun balancePos(): Pair<Int, Int> = ints(call("getBalancePos"))?.takeIf { it.size >= 2 }?.let { it[0] to it[1] } ?: balanceRect().let { it.first / 2 to it.second / 2 }
    fun setBalance(leftRight: Int, frontBack: Int) = queue("bal") {
        val r = call("setBalance", leftRight, frontBack)
        if (balLogged++ < 12) Log.i(TAG, "setBalance($leftRight, $frontBack) -> ${(r as? Array<*>)?.joinToString() ?: r}")
    }
    private var balLogged = 0

    companion object {
        const val CUSTOM = 0
        const val FLAT = 1
        const val ACTION_CHANGED = "imotor.intent.action.ACTION_EQ_CHANGED"
        private const val TAG = "HUAudio"
        private const val AUDIO = "com.imotor.framework.media.IMAudioManager"
        private const val HARDWARE = "com.imotor.framework.hardware.IMHardwareManager"
        private const val STOCK_EQ = "com.imotor.soundequalizer"
    }
}
