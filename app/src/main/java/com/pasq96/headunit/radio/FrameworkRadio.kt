package com.pasq96.headunit.radio

import android.content.Context
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Reflection wrapper around com.imotor.framework.radio.RadioManager, the firmware class the stock radio drives
 * (static getInstance(Context); seekToNext/Prev, fineTuneNext/Prev, scanAS, stopWork, switchAf/Ta/Stereo, toggleLOC,
 * switchWaveband(int), play(band, freq, index), getCurrentStation, getRadioStatus). Methods are resolved once.
 */
internal class FrameworkRadio(private val ctx: Context) {
    @Volatile private var mgr: Any? = null
    private val cache = ConcurrentHashMap<String, Method>()
    private var failures = 0

    val ready get() = mgr != null

    @Synchronized
    fun connect() {
        if (mgr != null) return
        mgr = CLASSES.firstNotNullOfOrNull { n ->
            runCatching { Class.forName(n).getMethod("getInstance", Context::class.java).invoke(null, ctx) }
                .recoverCatching { Class.forName(n, true, FrameworkJars.loader()).getMethod("getInstance", Context::class.java).invoke(null, ctx) }
                .onFailure { Log.w(TAG, "$n.getInstance: ${it.cause ?: it}") }.getOrNull()
        }
        mgr?.let { m -> Log.i(TAG, "manager ${m.javaClass.name}: " + m.javaClass.methods.map { it.name }.distinct().sorted().joinToString(",")) }
    }

    /** Invokes [name] on the manager; true when it ran without throwing. Failures are logged (first ones only). */
    fun call(name: String, vararg args: Any?): Boolean {
        val m = mgr ?: return false
        return runCatching { invoke(m, name, args) }.onFailure { if (failures++ < MAX_LOGGED) Log.w(TAG, "$name(${args.joinToString()}): ${it.cause ?: it}") }.isSuccess
    }

    /** Reads a no-arg getter chain, e.g. get("getCurrentStation", "getFreq"); null on any failure. */
    fun get(vararg chain: String): Any? {
        var o: Any? = mgr ?: return null
        for (n in chain) o = runCatching { invoke(o!!, n, NONE) }.getOrNull() ?: return null
        return o
    }

    private fun invoke(target: Any, name: String, args: Array<out Any?>): Any? {
        val m = cache.getOrPut("${target.javaClass.name}#$name/${args.size}") {
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size } ?: throw NoSuchMethodException(name)
        }
        val conv = Array(args.size) { i ->
            val t = m.parameterTypes[i]
            if (t == Int::class.javaPrimitiveType || t == Int::class.javaObjectType) (args[i] as Number).toInt() else args[i]
        }
        return m.invoke(target, *conv)
    }

    private companion object {
        const val TAG = "HURadio"
        const val MAX_LOGGED = 20
        val NONE = emptyArray<Any?>()
        val CLASSES = listOf("com.imotor.framework.radio.RadioManager", "com.imotor.framework.radio.LocalRadioManager")
    }
}

/** Vendor framework jars outside the boot classpath: one lazy loader over every jar in the framework dirs. */
internal object FrameworkJars {
    private val loader by lazy {
        val jars = listOf("/system/framework", "/system_ext/framework", "/vendor/framework", "/product/framework", "/oem/framework")
            .flatMap { d -> java.io.File(d).listFiles { f -> f.name.endsWith(".jar") }?.toList().orEmpty() }
        dalvik.system.PathClassLoader(jars.joinToString(":") { it.path }, FrameworkJars::class.java.classLoader)
    }
    fun loader(): ClassLoader = loader
}
