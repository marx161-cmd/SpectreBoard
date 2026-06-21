// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.settings.preferences
import com.termux.spectreboard.latin.R

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.termux.spectreboard.keyboard.KeyboardSwitcher
import com.termux.spectreboard.latin.utils.prefs
import com.termux.spectreboard.settings.Setting
import com.termux.spectreboard.settings.dialogs.TextInputDialog
import androidx.core.content.edit

@Composable
fun TextInputPreference(setting: Setting, default: String, info: String? = null, checkTextValid: (String) -> Boolean = { true }) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val prefs = LocalContext.current.prefs()
    Preference(
        name = setting.title,
        onClick = { showDialog = true },
        description = prefs.getString(setting.key, default)?.takeIf { it.isNotEmpty() }
    )
    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = {
                prefs.edit { putString(setting.key, it) }
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            initialText = prefs.getString(setting.key, default) ?: "",
            title = { Text(setting.title) },
            description = if (info == null) null else { { Text(info) } },
            checkTextValid = checkTextValid,
            onNeutral = { prefs.edit { remove(setting.key) }; showDialog = false },
            neutralButtonText = stringResource(R.string.button_default)
        )
    }
}
