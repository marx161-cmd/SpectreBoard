// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.settings.screens
import com.termux.spectreboard.latin.R

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.termux.spectreboard.keyboard.KeyboardSwitcher
import com.termux.spectreboard.latin.settings.Defaults
import com.termux.spectreboard.latin.settings.Settings
import com.termux.spectreboard.latin.utils.GestureDataGatheringSettings.filterBackgroundGatheringToolbarKeys
import com.termux.spectreboard.latin.utils.Log
import com.termux.spectreboard.latin.utils.ToolbarMode
import com.termux.spectreboard.latin.utils.getActivity
import com.termux.spectreboard.latin.utils.getStringResourceOrName
import com.termux.spectreboard.latin.utils.prefs
import com.termux.spectreboard.settings.SearchSettingsScreen
import com.termux.spectreboard.settings.Setting
import com.termux.spectreboard.settings.SettingsActivity
import com.termux.spectreboard.latin.utils.Theme
import com.termux.spectreboard.settings.dialogs.ToolbarKeysCustomizer
import com.termux.spectreboard.settings.initPreview
import com.termux.spectreboard.settings.preferences.ListPreference
import com.termux.spectreboard.settings.preferences.Preference
import com.termux.spectreboard.settings.preferences.ReorderSwitchPreference
import com.termux.spectreboard.settings.preferences.SwitchPreference
import com.termux.spectreboard.latin.utils.previewDark

@Composable
fun ToolbarScreen(
    onClickBack: () -> Unit,
) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val toolbarMode = Settings.readToolbarMode(prefs)
    val clipboardToolbarVisible = toolbarMode != ToolbarMode.HIDDEN
        || !prefs.getBoolean(Settings.PREF_TOOLBAR_HIDING_GLOBAL, Defaults.PREF_TOOLBAR_HIDING_GLOBAL)
    val items = listOf(
        Settings.PREF_TOOLBAR_MODE,
        if (toolbarMode == ToolbarMode.HIDDEN) Settings.PREF_TOOLBAR_HIDING_GLOBAL else null,
        if (toolbarMode != ToolbarMode.HIDDEN) Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE else null,
        when (toolbarMode) {
             ToolbarMode.EXPANDABLE, ToolbarMode.TOOLBAR_KEYS -> Settings.PREF_TOOLBAR_KEYS
             else -> null
        },
        when (toolbarMode) {
            ToolbarMode.EXPANDABLE, ToolbarMode.SUGGESTION_STRIP -> Settings.PREF_PINNED_TOOLBAR_KEYS
            else -> null
        },
        if (clipboardToolbarVisible) Settings.PREF_CLIPBOARD_TOOLBAR_KEYS else null,
        if (clipboardToolbarVisible) Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES else null,
        if (toolbarMode == ToolbarMode.EXPANDABLE) Settings.PREF_QUICK_PIN_TOOLBAR_KEYS else null,
        if (toolbarMode == ToolbarMode.EXPANDABLE) Settings.PREF_AUTO_SHOW_TOOLBAR else null,
        if (toolbarMode == ToolbarMode.EXPANDABLE) Settings.PREF_AUTO_HIDE_TOOLBAR else null,
        if (toolbarMode != ToolbarMode.HIDDEN) Settings.PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD else null,
        if (toolbarMode != ToolbarMode.HIDDEN) Settings.PREF_VARIABLE_TOOLBAR_DIRECTION else null,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_toolbar),
        settings = items
    )
}

fun createToolbarSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_TOOLBAR_MODE, R.string.toolbar_mode) { setting ->
        val ctx = LocalContext.current
        val items =
            ToolbarMode.entries.map { it.name.lowercase().getStringResourceOrName("toolbar_mode_", ctx) to it.name }
        ListPreference(
            setting,
            items,
            Defaults.PREF_TOOLBAR_MODE
        ) {
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_TOOLBAR_HIDING_GLOBAL, R.string.toolbar_hiding_global) {
        SwitchPreference(it, Defaults.PREF_TOOLBAR_HIDING_GLOBAL) {
            KeyboardSwitcher.getInstance().setThemeNeedsReload()
        }
    },
    Setting(context, Settings.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE, R.string.toolbar_swipe_down_to_hide, R.string.toolbar_swipe_down_to_hide_summary) {
        SwitchPreference(it, Defaults.PREF_TOOLBAR_SWIPE_DOWN_TO_HIDE)
    },
    Setting(context, Settings.PREF_TOOLBAR_KEYS, R.string.toolbar_keys) {
        val keys = Defaults.PREF_TOOLBAR_KEYS.filterBackgroundGatheringToolbarKeys(LocalContext.current.prefs())
        ReorderSwitchPreference(it, keys)
    },
    Setting(context, Settings.PREF_PINNED_TOOLBAR_KEYS, R.string.pinned_toolbar_keys) {
        val keys = Defaults.PREF_PINNED_TOOLBAR_KEYS.filterBackgroundGatheringToolbarKeys(LocalContext.current.prefs())
        ReorderSwitchPreference(it, keys)
    },
    Setting(context, Settings.PREF_CLIPBOARD_TOOLBAR_KEYS, R.string.clipboard_toolbar_keys) {
        val keys = Defaults.PREF_CLIPBOARD_TOOLBAR_KEYS.filterBackgroundGatheringToolbarKeys(LocalContext.current.prefs())
        ReorderSwitchPreference(it, keys)
    },
    Setting(context, Settings.PREF_TOOLBAR_CUSTOM_KEY_CODES, R.string.customize_toolbar_key_codes) {
        var showDialog by rememberSaveable { mutableStateOf(false) }
        Preference(
            name = it.title,
            onClick = { showDialog = true },
        )
        if (showDialog)
            ToolbarKeysCustomizer(
                key = it.key,
                onDismissRequest = { showDialog = false }
            )
    },
    Setting(context, Settings.PREF_QUICK_PIN_TOOLBAR_KEYS,
        R.string.quick_pin_toolbar_keys, R.string.quick_pin_toolbar_keys_summary)
    {
        SwitchPreference(it, Defaults.PREF_QUICK_PIN_TOOLBAR_KEYS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_AUTO_SHOW_TOOLBAR, R.string.auto_show_toolbar, R.string.auto_show_toolbar_summary)
    {
        SwitchPreference(it, Defaults.PREF_AUTO_SHOW_TOOLBAR)
    },
    Setting(context, Settings.PREF_AUTO_HIDE_TOOLBAR, R.string.auto_hide_toolbar, R.string.auto_hide_toolbar_summary)
    {
        SwitchPreference(it, Defaults.PREF_AUTO_HIDE_TOOLBAR)
    },
    Setting(context, Settings.PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD,
        R.string.toolbar_only_with_hw_keyboard, R.string.toolbar_only_with_hw_keyboard_summary)
    {
        SwitchPreference(it, Defaults.PREF_SHOW_ONLY_TOOLBAR_WITH_HARDWARE_KEYBOARD) {
            KeyboardSwitcher.getInstance().setThemeNeedsReload() // necessary for updating insets
        }
    },
    Setting(context, Settings.PREF_VARIABLE_TOOLBAR_DIRECTION,
        R.string.var_toolbar_direction, R.string.var_toolbar_direction_summary)
    {
        SwitchPreference(it, Defaults.PREF_VARIABLE_TOOLBAR_DIRECTION)
    }
)

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            ToolbarScreen { }
        }
    }
}
