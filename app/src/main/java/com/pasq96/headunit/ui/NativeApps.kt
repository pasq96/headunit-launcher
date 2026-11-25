package com.pasq96.headunit.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.pasq96.headunit.R

/** Built-in screens standing in for the stock iMotor apps (own drawer entry). */
enum class NativeApp(val label: Int, val glyph: G, val stock: Set<String>) {
    MUSIC(R.string.app_music, G.MUSIC, setOf("com.imotor.music")),
    BT(R.string.app_btmusic, G.BT, setOf("com.imotor.btmusic")),
    FILES(R.string.app_files, G.FOLDER, setOf("com.imotor.filemanager")),
    EQ(R.string.app_eq, G.EQ, setOf("com.imotor.soundequalizer")),
}

/** Screens with a drawer entry; their stock twins are hidden when "hide stock apps" is on. */
val NATIVE_READY: List<NativeApp> = NativeApp.entries.toList()

val NATIVE_STOCK_PKGS: Set<String> = NATIVE_READY.flatMapTo(HashSet()) { it.stock }

@Composable
internal fun NativeScreen(vm: LauncherHub, app: NativeApp, accent: Color) {
    when (app) {
        NativeApp.EQ -> EqualizerScreen(vm, accent)
        NativeApp.MUSIC -> MusicScreen(vm, accent)
        NativeApp.BT -> BtMusicScreen(vm, accent)
        NativeApp.FILES -> FilesScreen(vm, accent)
    }
}
