// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre

import android.content.Context
import com.termux.spectreboard.latin.utils.prefs
import java.io.File

/**
 * AMD-mode toolbar toggle. When on, SpectreBoard forwards input to comrade instead of the
 * local app: committed/glided text → `cybersyn/hid/text`, special keys → `cybersyn/hid/key`,
 * all over Cybersyn's one MQTT connection ([CybersynFifo]). When off, the keyboard behaves
 * normally on-device — every AMD-mode branch in the input path is gated on [enabled], so a
 * bug here can never affect ordinary typing.
 *
 * [enabled] is also mirrored to a shared-UID file [MODE_FILE] so Cybersyn's KeyHijackController
 * can gate the volume-key hijack on the same toggle (replacing the old spectreboard/ime_shown
 * signal). This is the single source of truth the volume-key override reads.
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

    /** Forward a special key as an xdotool key spec (e.g. "Return", "BackSpace", "ctrl+c"). */
    fun forwardKey(spec: String) {
        if (spec.isEmpty()) return
        CybersynFifo.publish(TOPIC_KEY, spec)
    }
}
