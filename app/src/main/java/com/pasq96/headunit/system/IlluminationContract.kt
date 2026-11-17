package com.pasq96.headunit.system

/**
 * How the head unit announces its button LEDs ("illumination", the headlight-linked backlight).
 *
 * !! PLACEHOLDERS, not verified on a real YMT08-3326. Android has no standard API for this: find the real signal
 * (logcat while toggling the lights, `adb shell settings list system|global|secure`, `ls /sys/class/leds`, the stock
 * APK's broadcasts) and fix the values HERE. The monitor listens to all three channels; Settings can pin one.
 */
object IlluminationContract {
    /** Broadcast sent by the stock firmware/app when the lights change. */
    const val ACTION_ILLUMINATION = "com.imotor.action.ILLUMINATION"
    const val EXTRA_ON = "on"

    /** Keys looked up in Settings.System, Settings.Global and Settings.Secure (first non-null wins). */
    val SETTING_KEYS = listOf("imotor_illumination", "illumination", "button_light")

    /** Files whose content is > 0 when the button LEDs are lit (polled, only while an AUTO mode needs it). */
    val SYSFS_PATHS = listOf("/sys/class/leds/button-backlight/brightness", "/sys/class/leds/keyboard-backlight/brightness")
    const val POLL_MS = 3_000L

    /** true = LEDs on; null = not a recognisable value. */
    fun parse(raw: Any?): Boolean? = when (raw) {
        is Boolean -> raw
        is Number -> raw.toDouble() > 0
        is String -> when (raw.trim().lowercase()) {
            "1", "true", "on", "yes" -> true
            "0", "false", "off", "no" -> false
            else -> raw.trim().toDoubleOrNull()?.let { it > 0 }
        }
        else -> null
    }
}
