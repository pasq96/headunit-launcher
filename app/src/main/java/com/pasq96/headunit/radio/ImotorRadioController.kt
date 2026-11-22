package com.pasq96.headunit.radio

import kotlinx.coroutines.flow.StateFlow

enum class Band(val label: String) { FM1("FM1"), FM2("FM2"), AM("AM") }

data class RadioState(
    val connected: Boolean = false,     // true once the radio has reported at least once
    val live: Boolean = false,          // true while updates keep arriving, i.e. the radio is the active source
    val band: Band = Band.FM1,
    val freqKhz: Int = 0,               // 0 = unknown
    val ps: String? = null,             // RDS programme service name
    val rt: String? = null,             // RDS radio text
    val muted: Boolean = false,
    val stereo: Boolean = false,
    val af: Boolean = false,
    val ta: Boolean = false,
    val stockPresets: List<Int> = emptyList(), // kHz, as reported by the radio's own memory
    val loc: Boolean = false,           // local (reduced sensitivity) mode
    val stereoOn: Boolean = true,       // stereo decoding enabled (false = forced mono)
    val scanning: Boolean = false,      // auto-scan in progress
)

/** Commands to / state from the stock radio. Implementations must never take audio focus: the stock app owns the sound. */
interface ImotorRadioController {
    val state: StateFlow<RadioState>
    /** False when the radio only accepts next/previous station and band switch (no direct tuning, presets, AF/TA, mute). */
    val canTune: Boolean get() = true
    fun start()
    fun stop()
    fun nextStation()
    fun previousStation()
    fun seekUp()
    fun seekDown()
    fun step(up: Boolean, stepKhz: Int)
    fun setFrequency(khz: Int)
    fun setBand(band: Band)
    fun toggleBand()
    fun toggleMute()
    fun toggleAf()
    fun toggleTa()
    fun toggleStereo()
    fun toggleLoc()
    fun autoScan()
    fun stopSeek()
    /** Opens the original radio app; false when none is installed. */
    fun openStockApp(): Boolean
}
