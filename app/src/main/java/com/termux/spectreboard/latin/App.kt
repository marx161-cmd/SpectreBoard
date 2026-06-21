// SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
package com.termux.spectreboard.latin
import com.termux.spectreboard.latin.BuildConfig

import android.app.Application
import android.os.Build
import com.termux.spectreboard.keyboard.emoji.SupportedEmojis
import com.termux.spectreboard.latin.define.DebugFlags
import com.termux.spectreboard.latin.settings.Defaults
import com.termux.spectreboard.latin.settings.Settings
import com.termux.spectreboard.latin.utils.FoldableUtils
import com.termux.spectreboard.latin.utils.LayoutUtilsCustom
import com.termux.spectreboard.latin.utils.Log
import com.termux.spectreboard.latin.utils.SubtypeSettings
import com.termux.spectreboard.latin.utils.prefs
import com.termux.spectreboard.latin.utils.upgradeToolbarPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugFlags.init(this)
        FoldableUtils.init(this)
        Settings.init(this)
        SubtypeSettings.init(this)

        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { // do some uncritical work in background for faster startup
            SupportedEmojis.load(this@App)
            LayoutUtilsCustom.removeMissingLayouts(this@App)
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            Log.i(
                "startup", "Starting ${applicationInfo.processName} version ${packageInfo.versionName} (${
                    packageInfo.versionCode
                }) on Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
            )
        }

        RichInputMethodManager.init(this)
        checkVersionUpgrade(this)
        if (BuildConfig.DEBUG) // do this on every debug apk start because we may work on adding a new toolbar key
            upgradeToolbarPrefs(prefs())
        transferOldPinnedClips(this) // todo: remove in a few months, maybe end 2026
        app = this
        Defaults.initDynamicDefaults(this)
    }

    companion object {
        // used so JniUtils can access application once
        private var app: App? = null
        fun getApp(): App? {
            val application = app
            app = null
            return application
        }
    }
}
