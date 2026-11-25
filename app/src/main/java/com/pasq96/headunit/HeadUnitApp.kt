package com.pasq96.headunit

import android.app.Application
import com.pasq96.headunit.ui.LauncherHub

class HeadUnitApp : Application() {
    override fun onCreate() { super.onCreate(); com.pasq96.headunit.embed.Embedder.ensureFreeform(this) }

    val hub by lazy { LauncherHub(this) }
}
