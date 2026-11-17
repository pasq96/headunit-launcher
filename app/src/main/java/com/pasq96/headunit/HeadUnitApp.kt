package com.pasq96.headunit

import android.app.Application
import com.pasq96.headunit.ui.LauncherHub

class HeadUnitApp : Application() {
    val hub by lazy { LauncherHub(this) }
}
