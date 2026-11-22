package com.pasq96.headunit.radio

import android.content.Context
import android.util.Log
import java.lang.reflect.Method

/**
 * Reflection wrapper around the platform class the stock radio itself uses (com.imotor.framework.radio.RadioManager,
 * present in the firmware, not in the SDK). Method names follow the stock app's own log lines; every call is
 * best-effort and logged under HURadio so a wrong name is visible with `logcat -d -s HURadio`.
 */
internal class FrameworkRadio(private val ctx: Context) {
    @Volatile private var mgr: Any? = null
    private var methods: List<Method> = emptyList()

    val ready get() = mgr != null
    fun has(vararg names: String) = methods.any { m -> names.any { it.equals(m.name, true) } }

    fun connect() {
        if (mgr != null) return
        val found = runCatching { locate() }.onFailure { Log.w(TAG, "locate failed: $it") }.getOrNull()
        mgr = found
        methods = found?.javaClass?.methods?.toList().orEmpty()
        Log.w(TAG, "manager=${found?.javaClass?.name}")
        methods.filter { it.declaringClass != Any::class.java }.sortedBy { it.name }
            .forEach { Log.w(TAG, "api ${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }}): ${it.returnType.simpleName}") }
    }

    private fun locate(): Any? {
        val cls = runCatching { Class.forName(CLS) }.getOrNull()
        if (cls != null) {
            for (n in listOf("getInstance", "getDefault", "get")) {
                runCatching { cls.getMethod(n, Context::class.java).invoke(null, ctx) }.getOrNull()?.let { return it }
                runCatching { cls.getMethod(n).invoke(null) }.getOrNull()?.let { return it }
            }
        }
        for (n in listOf("radio", "imotor_radio", "imradio", "radio_manager")) {
            ctx.getSystemService(n)?.takeIf { it.javaClass.name.contains("Radio", true) }?.let { return it }
        }
        return runCatching { Class.forName(LOCAL).getConstructor(Context::class.java).newInstance(ctx) }.getOrNull()
    }

    /** Calls the first method matching any of [names] with [args]; true when one of them ran without throwing. */
    fun call(names: List<String>, vararg args: Any?): Boolean {
        val m = mgr ?: return false
        for (me in methods) {
            if (names.none { it.equals(me.name, true) } || me.parameterCount != args.size) continue
            val conv = args.indices.map { coerce(args[it], me.parameterTypes[it]) }
            if (conv.any { it === BAD }) continue
            val r = runCatching { me.invoke(m, *conv.toTypedArray()) }
            Log.w(TAG, "${me.name}(${args.joinToString()}) -> ${r.getOrNull()} ${r.exceptionOrNull()?.cause ?: r.exceptionOrNull() ?: ""}")
            if (r.isSuccess) return true
        }
        return false
    }

    private fun coerce(v: Any?, t: Class<*>): Any? = when (t) {
        java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> when (v) { is Boolean -> v; is Int -> v != 0; else -> BAD }
        Integer.TYPE, Integer::class.java -> when (v) { is Int -> v; is Boolean -> if (v) 1 else 0; else -> BAD }
        java.lang.Long.TYPE, java.lang.Long::class.java -> when (v) { is Int -> v.toLong(); is Boolean -> if (v) 1L else 0L; else -> BAD }
        else -> BAD
    }

    private companion object {
        const val TAG = "HURadio"
        const val CLS = "com.imotor.framework.radio.RadioManager"
        const val LOCAL = "com.imotor.framework.radio.LocalRadioManager"
        val BAD = Any()
    }
}
