package com.pasq96.headunit.tools

import android.content.Context
import android.util.Log
import com.pasq96.headunit.radio.FrameworkJars
import java.lang.reflect.Method

/**
 * Car battery rail from the firmware VehicleManager (BatteryManager only reports the 3.3 V backup cell).
 * Which getter carries the 12 V is not documented, so every no-arg numeric getter whose name contains volt or batt is probed;
 * the method list goes to logcat (tag HUVehicle) to refine the match from a real device.
 */
internal class CarVoltage(private val ctx: Context) {
    private var mgr: Any? = null
    private var getters: List<Method> = emptyList()
    private var tried = false

    fun read(): Float? {
        if (!tried) connect()
        val fromMcu = mcuVolts()
        val m = mgr ?: return fromMcu
        val probed = getters.mapNotNull { g -> runCatching { norm((g.invoke(m) as Number).toDouble()) }.getOrNull() }.maxOrNull()?.toFloat()
        return listOfNotNull(fromMcu, probed).maxOrNull()
    }

    // ---- MCU service: the car power rail (android.mcu.IMcuManager#getPowerVoltage, binder "im_mcu_service") ----

    private var mcu: Any? = null
    private var mcuTried = false

    private fun mcuVolts(): Float? {
        if (!mcuTried) {
            mcuTried = true
            mcu = runCatching {
                val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "im_mcu_service") as android.os.IBinder
                Class.forName("android.mcu.IMcuManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, b)
            }.onFailure { Log.w(TAG, "im_mcu_service: ${it.cause ?: it}") }.getOrNull()
            mcu?.let { Log.i(TAG, "mcu methods: " + it.javaClass.methods.map { m -> "${m.name}/${m.parameterCount}" }.distinct().sorted().joinToString(",")) }
        }
        val m = mcu ?: return null
        return runCatching { m.javaClass.getMethod("getPowerVoltage").invoke(m) }
            .onSuccess { if (!mcuLogged) { mcuLogged = true; Log.i(TAG, "getPowerVoltage = $it") } }
            .onFailure { if (!mcuLogged) { mcuLogged = true; Log.w(TAG, "getPowerVoltage: ${it.cause ?: it}") } }
            .getOrNull()?.let { (it as? Number)?.toDouble()?.let { v -> norm(v) }?.toFloat() }
    }

    private var mcuLogged = false

    private fun connect() {
        tried = true
        val cls = "com.imotor.framework.vehicle.VehicleManager"
        mgr = runCatching { Class.forName(cls).getMethod("getInstance", Context::class.java).invoke(null, ctx) }
            .recoverCatching { Class.forName(cls, true, FrameworkJars.loader()).getMethod("getInstance", Context::class.java).invoke(null, ctx) }
            .onFailure { Log.w(TAG, "VehicleManager: ${it.cause ?: it}") }.getOrNull()
        val c = mgr?.javaClass ?: return
        Log.i(TAG, "methods: " + c.methods.map { "${it.name}/${it.parameterCount}" }.distinct().sorted().joinToString(","))
        getters = c.methods.filter { it.parameterCount == 0 && NAME.containsMatchIn(it.name) && Number::class.javaObjectType.isAssignableFrom(wrap(it.returnType)) }
        Log.i(TAG, "probing: " + getters.joinToString { it.name })
    }

    private fun wrap(t: Class<*>): Class<*> = when (t) {
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        else -> t
    }

    /** Raw value in V, mV or 0.1 V; null when it is not a plausible 12/24 V rail. */
    private fun norm(v: Double): Double? = listOf(v, v / 10, v / 1000).firstOrNull { it in 6.0..32.0 }

    private companion object {
        const val TAG = "HUVehicle"
        val NAME = Regex("volt|batt|vbat", RegexOption.IGNORE_CASE)
    }
}
