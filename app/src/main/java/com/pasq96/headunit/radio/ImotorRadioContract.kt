package com.pasq96.headunit.radio

import kotlin.math.roundToInt

/**
 * Single place that knows how the stock iMotor radio (`com.imotor.*`) is spoken to.
 *
 * !! Every string below is a PLACEHOLDER, not verified against a real YMT08-3326 firmware. After inspecting the stock APK
 * (apktool on the radio app, or `adb shell dumpsys package <pkg>` / `logcat` while using the radio) fix actions, extras
 * and units HERE: nothing else in the project hardcodes them.
 */
object ImotorRadioContract {
    const val PACKAGE_PREFIX = "com.imotor"

    /** Words that identify the stock radio app among the launchable `com.imotor.*` packages (package name or label). */
    val STOCK_APP_HINTS = listOf("radio", "fm", "tuner")

    /** Restrict outgoing broadcasts to one package once known (e.g. "com.imotor.radio"); null = implicit broadcast. */
    val TARGET_PACKAGE: String? = null

    object Action {
        // radio -> launcher
        const val STATE = "com.imotor.radio.action.STATE"             // freq / band / ps / rt / mute / af / ta
        const val PRESETS = "com.imotor.radio.action.PRESETS"         // stock preset list
        // launcher -> radio
        const val QUERY = "com.imotor.radio.action.QUERY"             // ask the radio to re-send STATE and PRESETS
        const val NEXT = "com.imotor.radio.action.NEXT"
        const val PREV = "com.imotor.radio.action.PREV"
        const val SEEK_UP = "com.imotor.radio.action.SEEK_UP"
        const val SEEK_DOWN = "com.imotor.radio.action.SEEK_DOWN"
        const val SET_FREQ = "com.imotor.radio.action.SET_FREQ"
        const val TOGGLE_BAND = "com.imotor.radio.action.TOGGLE_BAND"
        const val SET_BAND = "com.imotor.radio.action.SET_BAND"
        const val MUTE = "com.imotor.radio.action.MUTE"
        const val TOGGLE_AF = "com.imotor.radio.action.TOGGLE_AF"
        const val TOGGLE_TA = "com.imotor.radio.action.TOGGLE_TA"
    }

    object Extra {
        const val FREQ = "freq"
        const val BAND = "band"
        const val PS = "ps"
        const val RT = "rt"
        const val MUTE = "mute"
        const val AF = "af"
        const val TA = "ta"
        const val PRESETS = "presets"
    }

    /** How a frequency travels in SET_FREQ. Incoming values are auto-detected (see [decodeFreqKhz]). */
    enum class WireUnit { KHZ, TEN_KHZ, MHZ_FLOAT }
    val FREQ_WIRE_UNIT = WireUnit.KHZ

    fun encodeFreq(khz: Int): Any = when (FREQ_WIRE_UNIT) {
        WireUnit.KHZ -> khz
        WireUnit.TEN_KHZ -> khz / 10
        WireUnit.MHZ_FLOAT -> khz / 1000f
    }

    fun encodeBand(b: Band): String = b.name

    fun decodeBand(raw: Any?): Band? = when (raw) {
        is Number -> Band.entries.getOrNull(raw.toInt())
        is String -> Band.entries.firstOrNull { it.name.equals(raw.replace(" ", ""), true) }
        else -> null
    }

    /** MHz float (<200), AM kHz (200..1999), FM in 10 kHz units (5000..19999) or kHz (>=20000) → kHz. 0 = unknown. */
    fun decodeFreqKhz(raw: Any?): Int {
        val v = when (raw) { is Number -> raw.toDouble(); is String -> raw.toDoubleOrNull(); else -> null } ?: return 0
        return when {
            v <= 0 -> 0
            v < 200 -> (v * 1000).roundToInt()
            v < 5000 -> v.roundToInt()
            v < 20000 -> (v * 10).roundToInt()
            else -> v.roundToInt()
        }
    }

    fun decodeBool(raw: Any?): Boolean? = when (raw) {
        is Boolean -> raw
        is Number -> raw.toInt() != 0
        is String -> raw.equals("true", true) || raw == "1"
        else -> null
    }

    fun decodePresets(raw: Any?): List<Int> = when (raw) {
        is IntArray -> raw.toList()
        is FloatArray -> raw.map { it }
        is DoubleArray -> raw.map { it }
        is Collection<*> -> raw
        is String -> raw.split(',', ';', ' ').filter { it.isNotBlank() }
        else -> emptyList<Any?>()
    }.let { l -> l.map { decodeFreqKhz(it) }.filter { it > 0 } }
}
