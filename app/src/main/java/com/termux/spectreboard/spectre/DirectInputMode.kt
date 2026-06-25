// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre

import android.content.Context
import com.termux.spectreboard.latin.utils.prefs

/**
 * Toggle that makes every keypress commit its character immediately, bypassing word
 * composition, autocorrect, and suggestions entirely.
 *
 * Useful when typing into a terminal or any app where composing spans break input.
 *
 * [enabled] is a volatile in-memory flag read on the hot path (every keystroke).
 * The preference is written alongside so toolbar button state survives across reloads.
 */
object DirectInputMode {

    const val PREF_KEY = "spectre_direct_input"

    @Volatile
    var enabled: Boolean = false
        private set

    fun init(context: Context) {
        enabled = context.prefs().getBoolean(PREF_KEY, false)
    }

    fun toggle(context: Context) {
        enabled = !enabled
        context.prefs().edit().putBoolean(PREF_KEY, enabled).apply()
    }
}
