package com.pasq96.headunit.ui

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pasq96.headunit.data.Lang
import com.pasq96.headunit.data.ThemeMode
import java.util.Locale

@Immutable
class Palette(val bg: Color, val surface: Color, val surface2: Color, val hi: Color, val lo: Color)

val DarkPalette = Palette(Color(0xFF0B0F14), Color(0xFF151B23), Color(0xFF212A36), Color(0xFFEAF0F6), Color(0xFF8A97A6))
val LightPalette = Palette(Color(0xFFF2F5F9), Color(0xFFFFFFFF), Color(0xFFE1E7EE), Color(0xFF10151B), Color(0xFF5B6773))

val LocalPalette = staticCompositionLocalOf { DarkPalette }
private val LocalL10n = staticCompositionLocalOf<Context> { error("HeadUnitTheme missing") }

val Bg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.bg
val Surface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
val Surface2: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface2
val TextHi: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.hi
val TextLo: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.lo

/** Text/glyph colour on top of an accent fill: same in both themes. */
val OnAccent = Color(0xFF0B0F14)
val Warn = Color(0xFFFF5252)

/** Palette + language for one Compose root (activity, dock window, panels window): each root reads the same hub state. */
@Composable
fun HeadUnitTheme(hub: LauncherHub, content: @Composable () -> Unit) {
    val p by hub.prefs.collectAsStateWithLifecycle()
    val lit by hub.lit.collectAsStateWithLifecycle()
    // AUTO: LEDs on = night = dark; unknown signal stays dark (safer when driving at night).
    val dark = when (p.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.AUTO -> lit ?: true }
    val base = LocalContext.current
    val l10n = remember(base, p.lang) { localized(base, p.lang) }
    CompositionLocalProvider(LocalPalette provides (if (dark) DarkPalette else LightPalette), LocalL10n provides l10n) { content() }
}

private fun localized(base: Context, lang: Lang): Context {
    val cfg = Configuration(base.resources.configuration).apply { setLocale(Locale(lang.tag)) }
    return base.createConfigurationContext(cfg)
}

/** String from XML in the language chosen in the settings (switches live, no restart). */
@Composable
@ReadOnlyComposable
fun str(@StringRes id: Int, vararg args: Any): String = LocalL10n.current.getString(id, *args)
