// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.settings

import android.content.Context
import com.termux.spectreboard.keyboard.internal.KeyboardIconsSet
import com.termux.spectreboard.latin.settings.Settings
import com.termux.spectreboard.latin.utils.SubtypeSettings

// file is meant for making compose previews work

fun initPreview(context: Context) {
    Settings.init(context)
    SubtypeSettings.init(context)
    Settings.getInstance().loadSettings(context)
    SettingsActivity.settingsContainer = SettingsContainer(context)
    KeyboardIconsSet.instance.loadIcons(context)
}
