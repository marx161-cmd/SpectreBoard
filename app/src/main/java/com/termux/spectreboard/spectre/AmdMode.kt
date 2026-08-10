// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre

import android.content.Context
import android.view.KeyEvent
import com.termux.spectreboard.keyboard.internal.keyboard_parser.floris.KeyCode
import com.termux.spectreboard.latin.utils.prefs
import java.io.File

/**
 * AMD-mode toolbar toggle. When on, SpectreBoard forwards input to comrade instead of the
 * local app, over Cybersyn's one MQTT connection ([CybersynFifo]) via the receiver's two
 * lanes: printable/glided text → `cybersyn/hid/text` (xdotool type), and everything with a
 * key identity (specials, F-keys, Ctrl/Alt/Meta combos) → `cybersyn/hid/key` (xdotool key).
 *
 * The keycode→keysym mapping mirrors Artemis/moonlight's complete KeyboardTranslator, but
 * emits X keysym names for our xdotool receiver instead of Windows VK codes for GameStream —
 * so special keys, sticky modifiers and F-keys all come along without hand-wiring each one.
 *
 * When off, the input path is unchanged — every branch is gated on [enabled], so a bug here
 * can never affect ordinary typing.
 */
object AmdMode {

    const val PREF_KEY = "spectre_amd_mode"

    // Shared-UID mode file read by Cybersyn's KeyHijackController: "amd" | "android".
    private const val MODE_FILE = "/data/data/com.termux/files/usr/tmp/cybersyn-hidmode"

    private const val TOPIC_TEXT = "cybersyn/hid/text"
    private const val TOPIC_KEY = "cybersyn/hid/key"

    @Volatile
    var enabled: Boolean = false
        private set

    fun init(context: Context) {
        enabled = context.prefs().getBoolean(PREF_KEY, false)
        writeModeFile()
    }

    fun toggle(context: Context) {
        enabled = !enabled
        context.prefs().edit().putBoolean(PREF_KEY, enabled).apply()
        writeModeFile()
    }

    private fun writeModeFile() {
        try {
            File(MODE_FILE).writeText(if (enabled) "amd" else "android")
        } catch (_: Exception) {
            // best-effort; the vol-key gate simply stays in its last state if this fails.
        }
    }

    /** Forward committed/glided text to comrade. Sanitises the FIFO line separators. */
    fun forwardText(text: CharSequence?) {
        if (text.isNullOrEmpty()) return
        val clean = text.toString().replace('\t', ' ').replace('\n', ' ')
        if (clean.isEmpty()) return
        CybersynFifo.publish(TOPIC_TEXT, clean)
    }

    /** Forward a key spec to comrade (xdotool key syntax, e.g. "Return", "ctrl+c", "F7"). */
    fun forwardKey(spec: String) {
        if (spec.isEmpty()) return
        CybersynFifo.publish(TOPIC_KEY, spec)
    }

    // ---- keycode → X keysym (base keys get live modifiers applied; composites are fixed) ----

    private val BASE: Map<Int, String> = mapOf(
        KeyCode.DELETE to "BackSpace",
        KeyCode.ARROW_LEFT to "Left", KeyCode.ARROW_RIGHT to "Right",
        KeyCode.ARROW_UP to "Up", KeyCode.ARROW_DOWN to "Down",
        KeyCode.MOVE_START_OF_LINE to "Home", KeyCode.MOVE_END_OF_LINE to "End",
        KeyCode.PAGE_UP to "Prior", KeyCode.PAGE_DOWN to "Next",
        KeyCode.TAB to "Tab", KeyCode.ESCAPE to "Escape", KeyCode.INSERT to "Insert",
        KeyCode.F1 to "F1", KeyCode.F2 to "F2", KeyCode.F3 to "F3", KeyCode.F4 to "F4",
        KeyCode.F5 to "F5", KeyCode.F6 to "F6", KeyCode.F7 to "F7", KeyCode.F8 to "F8",
        KeyCode.F9 to "F9", KeyCode.F10 to "F10", KeyCode.F11 to "F11", KeyCode.F12 to "F12",
    )

    // Semantic composites — already carry their modifier; live modifiers are not re-applied.
    private val COMPOSITE: Map<Int, String> = mapOf(
        KeyCode.WORD_LEFT to "ctrl+Left", KeyCode.WORD_RIGHT to "ctrl+Right",
        KeyCode.MOVE_START_OF_PAGE to "ctrl+Home", KeyCode.MOVE_END_OF_PAGE to "ctrl+End",
        KeyCode.SHIFT_ENTER to "shift+Return",
    )

    private fun mods(metaState: Int, includeShift: Boolean): String {
        val sb = StringBuilder()
        if (metaState and KeyEvent.META_CTRL_ON != 0) sb.append("ctrl+")
        if (metaState and KeyEvent.META_ALT_ON != 0) sb.append("alt+")
        if (metaState and KeyEvent.META_META_ON != 0) sb.append("super+")
        if (includeShift && metaState and KeyEvent.META_SHIFT_ON != 0) sb.append("shift+")
        return sb.toString()
    }

    private fun hasChordMod(metaState: Int) =
        metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON) != 0

    /**
     * Route one keypress in AMD mode. Returns true if it was forwarded/consumed, false if the
     * caller should handle it locally (functional keys: toolbar, layout, settings, and the
     * modifier keys themselves — those must update metaState locally so the NEXT forwarded key
     * carries the sticky Ctrl/Alt/Meta prefix).
     */
    fun handleCode(primaryCode: Int, metaState: Int): Boolean {
        if (!enabled) return false

        COMPOSITE[primaryCode]?.let { forwardKey(it); return true }
        BASE[primaryCode]?.let { forwardKey(mods(metaState, includeShift = true) + it); return true }

        if (primaryCode > 0) {
            when (primaryCode) {
                '\n'.code -> { forwardKey(mods(metaState, true) + "Return"); return true }
                '\t'.code -> { forwardKey(mods(metaState, true) + "Tab"); return true }
                '\b'.code -> { forwardKey(mods(metaState, true) + "BackSpace"); return true }
            }
            if (!Character.isValidCodePoint(primaryCode)) return false
            val ch = String(Character.toChars(primaryCode))
            if (hasChordMod(metaState)) {
                // Ctrl/Alt/Meta chord → key lane. Alnum keysyms only (xdotool + relay regex);
                // case lives in metaState's shift, not the char, for a chord.
                if (ch.length == 1 && Character.isLetterOrDigit(ch[0])) {
                    forwardKey(mods(metaState, includeShift = true) + ch.lowercase())
                    return true
                }
                // Non-alnum chord (rare) — best effort as plain text.
                forwardText(ch); return true
            }
            forwardText(ch); return true
        }

        // Functional / modifier / unmapped keycode → handle locally.
        return false
    }
}
