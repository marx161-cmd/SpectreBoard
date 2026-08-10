// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre

import com.termux.spectreboard.keyboard.internal.keyboard_parser.floris.KeyCode

/**
 * i3 window-management keys for the numpad. Each key publishes an i3 command to
 * `cybersyn/hid/i3` over Cybersyn's one MQTT connection ([CybersynFifo]); the comrade relay
 * runs it via `i3-msg`. These act on comrade regardless of the AMD-mode toggle — they are
 * dedicated remote-control keys, not local input.
 *
 * Commands are derived from comrade's actual i3 config (2026-08-10). Edit here to retarget.
 */
object I3Numpad {

    private const val TOPIC = "cybersyn/hid/i3"

    private val COMMANDS: Map<Int, String> = mapOf(
        KeyCode.I3_TERM to "exec --no-startup-id /home/comrade/bin/i3-launch-terminal.sh",
        KeyCode.I3_LAUNCH to "exec /home/comrade/bin/rofi-nocaps -show drun",
        KeyCode.I3_CLOSE to "kill",
        KeyCode.I3_FSCRN to "fullscreen toggle",
        KeyCode.I3_FLOAT to "floating toggle",
        KeyCode.I3_RESIZE to "mode \"resize\"",
        KeyCode.I3_W1 to "workspace number 1",
        KeyCode.I3_W2 to "workspace number 2",
        KeyCode.I3_W3 to "workspace number 3",
        KeyCode.I3_W4 to "workspace number 4",
        KeyCode.I3_QUICK to "exec --no-startup-id /home/comrade/bin/i3-quick-actions.sh",
        KeyCode.I3_FOCUS_PARENT to "focus parent",
        KeyCode.I3_OVERLAY to "exec --no-startup-id /home/comrade/bin/mod-overlay-toggle.sh",
        KeyCode.I3_OVERLAY_HIDE to "exec --no-startup-id /home/comrade/bin/mod-overlay-hide.sh",
    )

    /** True if [code] is one of our i3 keys (it was dispatched). */
    fun dispatch(code: Int): Boolean {
        val cmd = COMMANDS[code] ?: return false
        CybersynFifo.publish(TOPIC, cmd)
        return true
    }
}
