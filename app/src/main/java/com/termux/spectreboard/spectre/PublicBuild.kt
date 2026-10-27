// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre

import android.content.Context
import android.util.Log
import java.io.File
import android.widget.Toast
import com.termux.spectreboard.keyboard.internal.keyboard_parser.floris.KeyCode
import com.termux.spectreboard.latin.BuildConfig
import com.termux.spectreboard.spectre.exec.MacroManager

/**
 * Public test build (`public` build type, scope.md 2026-10-27). These keys only work inside the
 * author's homelab (comrade over Tailscale, Cybersyn, Termux, UDTTSR), so the public build shows
 * a toast instead of firing them at hosts and apps the tester doesn't have.
 */
object PublicBuild {
    @JvmField val enabled: Boolean = BuildConfig.PUBLIC_BUILD

    private val homelabOnly = setOf(
        KeyCode.VOICE_INPUT, KeyCode.WHISPER_MIC, KeyCode.READ_ALOUD,
        KeyCode.AMD_MODE, KeyCode.AMD_CONTROL,
        KeyCode.CYBERSYN_CLICK, KeyCode.CYBERSYN_CLUTCH, KeyCode.CYBERSYN_GYRO,
        KeyCode.EXEC_LOCAL, KeyCode.EXEC_REMOTE,
    )

    fun isHomelabOnly(code: Int): Boolean =
        code in homelabOnly || code in MacroManager.MACRO_CODE_MIN..MacroManager.MACRO_CODE_MAX ||
            I3Numpad.handles(code)

    /**
     * The personal build gets its scorer models pushed into filesDir by hand; the public APK
     * carries them as assets (src/public/assets: the filtered public KenLM + the GRU) and copies
     * each one over on first use. No-op outside the public build or when the file is present.
     */
    fun ensureBundledModel(context: Context, name: String) {
        if (!enabled) return
        val dest = File(context.filesDir, name)
        if (dest.exists()) return
        try {
            val tmp = File(context.filesDir, "$name.part")
            context.assets.open(name).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(dest)
        } catch (e: Exception) {
            Log.w("PublicBuild", "couldn't install bundled model $name", e)
        }
    }

    fun showUnavailable(context: Context) {
        Toast.makeText(context, "Not available in the public test build", Toast.LENGTH_SHORT).show()
    }
}
