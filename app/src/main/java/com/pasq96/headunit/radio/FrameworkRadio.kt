package com.pasq96.headunit.radio

import android.content.Context
import android.util.Log
import java.lang.reflect.Method

/**
 * Reflection wrapper around com.imotor.framework.radio.RadioManager, the firmware class the stock radio itself drives
 * (static getInstance(Context); seekToNext/Prev, fineTuneNext/Prev, scanAS, stopWork, switchAf/Ta/Stereo, toggleLOC,
 * switchWaveband(int), play(band, freq, index), getCurrentStation, getRadioStatus). Every call is logged under HURadio.
 */
internal class FrameworkRadio(private val ctx: Context) {
    @Volatile private var mgr: Any? = null

    val ready get() = mgr != null

    fun connect() {
        if (mgr != null) return
        mgr = CLASSES.firstNotNullOfOrNull { n ->
            runCatching { Class.forName(n).getMethod("getInstance", Context::class.java).invoke(null, ctx) }
                .onFailure { Log.w(TAG, "$n.getInstance: ${it.cause ?: it}") }.getOrNull()
        }
        Log.w(TAG, "manager=${mgr?.javaClass?.name}")
    }

    /** Invokes [name] on the manager; true when it ran without throwing. */
    fun call(name: String, vararg args: Any?): Boolean {
        val m = mgr ?: return false
        val r = runCatching { invoke(m, name, args) }
        Log.w(TAG, "$name(${args.joinToString()}) -> ${r.getOrNull()} ${r.exceptionOrNull()?.let { it.cause ?: it } ?: ""}")
        return r.isSuccess
    }

    /** Reads a no-arg getter chain, e.g. get("getCurrentStation", "getFreq"); null on any failure. */
    fun get(vararg chain: String): Any? {
        var o: Any? = mgr ?: return null
        for (n in chain) o = runCatching { invoke(o!!, n, emptyArray()) }.getOrNull() ?: return null
        return o
    }

    private fun invoke(target: Any, name: String, args: Array<out Any?>): Any? {
        val m: Method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: throw NoSuchMethodException(name)
        val conv = args.mapIndexed { i, v ->
            when (m.parameterTypes[i]) { Int::class.javaPrimitiveType, Int::class.javaObjectType -> (v as Number).toInt(); else -> v }
        }
        return m.invoke(target, *conv.toTypedArray())
    }

    private companion object {
        const val TAG = "HURadio"
        val CLASSES = listOf("com.imotor.framework.radio.RadioManager", "com.imotor.framework.radio.LocalRadioManager")
    }
}
