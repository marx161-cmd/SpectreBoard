// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.system.Os
import android.system.OsConstants
import com.termux.spectreboard.latin.utils.prefs
import java.nio.charset.StandardCharsets

/**
 * Native click/clutch/gyro controls for the Cybersyn desktop-trackpad relay
 * (cybersyn-hid-relay.py on comrade), driven directly from numpad keys.
 *
 * Transport: these used to open a hand-rolled MQTT socket straight to the broker — a second
 * always-on connection. They now write into Cybersyn's ingest FIFO (`cybersyn-pub.fifo`,
 * the same doorway `cybersyn-send` uses), so every publish rides Cybersyn's ONE persistent
 * MQTT connection. No socket, no keepalive, no last-will here anymore.
 *
 * The relay's own topic names don't match this keyboard's key labels, which is a trap if you
 * go by topic name alone:
 *  - "click" (a real single click) has no dedicated hold state -> momentary [pulseClick],
 *    published on the newer cybersyn/hid/click topic ("left").
 *  - "clutch" (click-and-HOLD, for dragging) is the relay's `android/click` topic
 *    (set_click_state -> HOLDING/RELEASED). Nothing to do with the android/clutch topic
 *    despite the name similarity.
 *  - "gyro" (enable/disable tilt-driven cursor movement) is the relay's `android/clutch`
 *    topic (set_clutch_state gates whether android/sensor moves the pointer at all). This is
 *    also what Cybersyn's own examples/gyro-clutch.yaml calls "Gyro: on/off/toggle".
 *
 * Clutch and gyro are hard on/off switches (not blind toggles): both relay topics accept
 * explicit "ON"/"OFF", so local state can never drift from what the relay thinks.
 */
object CybersynControl {

    private const val PREF_HOLD_ON = "spectre_cybersyn_hold_on"
    private const val PREF_GYRO_ON = "spectre_cybersyn_gyro_on"

    private const val TOPIC_PULSE_CLICK = "cybersyn/hid/click"
    private const val TOPIC_HOLD = "android/click"
    private const val TOPIC_GYRO_GATE = "android/clutch"

    /** click-and-hold / drag state (relay topic android/click) */
    @Volatile
    var holdOn: Boolean = false
        private set

    /** gyro-driven cursor movement gate (relay topic android/clutch) */
    @Volatile
    var gyroOn: Boolean = false
        private set

    fun init(context: Context) {
        val p = context.prefs()
        holdOn = p.getBoolean(PREF_HOLD_ON, false)
        gyroOn = p.getBoolean(PREF_GYRO_ON, false)
    }

    /** A real single left click. Momentary - no state to track. */
    fun pulseClick() {
        publish(TOPIC_PULSE_CLICK, "left")
    }

    fun toggleHold(context: Context): Boolean {
        holdOn = !holdOn
        context.prefs().edit().putBoolean(PREF_HOLD_ON, holdOn).apply()
        publish(TOPIC_HOLD, if (holdOn) "ON" else "OFF")
        return holdOn
    }

    fun toggleGyro(context: Context): Boolean {
        gyroOn = !gyroOn
        context.prefs().edit().putBoolean(PREF_GYRO_ON, gyroOn).apply()
        publish(TOPIC_GYRO_GATE, if (gyroOn) "ON" else "OFF")
        return gyroOn
    }

    /**
     * Force both hold and gyro-gate off on the relay, regardless of what our local toggle
     * state currently says. Local state and the relay's real state can only ever be synced by
     * messages (QoS 0, no ack), so this is the panic button when they might have drifted.
     */
    fun forceReleaseAll(context: Context) {
        holdOn = false
        gyroOn = false
        context.prefs().edit()
            .putBoolean(PREF_HOLD_ON, false)
            .putBoolean(PREF_GYRO_ON, false)
            .apply()
        publish(TOPIC_HOLD, "OFF")
        publish(TOPIC_GYRO_GATE, "OFF")
    }

    private fun publish(topic: String, payload: String) = FifoPublisher.publish(topic, payload)

    /**
     * Writes `topic\tpayload\n` into Cybersyn's ingest FIFO. Runs on a HandlerThread because
     * publishes fire from the keyboard's key-handling path, which must never do file I/O.
     */
    private object FifoPublisher {
        private const val FIFO_PATH = "/data/data/com.termux/files/usr/tmp/cybersyn-pub.fifo"

        private val thread = HandlerThread("spectreboard-cybersyn-fifo").apply { start() }
        private val handler = Handler(thread.looper)

        fun publish(topic: String, payload: String) {
            handler.post {
                try {
                    // O_RDWR so open() never blocks, even while Cybersyn's reader is
                    // respawning; we only ever write on this fd. If the FIFO is absent
                    // (Cybersyn down) open throws ENOENT and we drop it — click/gyro are
                    // momentary commands, nothing to retry.
                    val fd = Os.open(FIFO_PATH, OsConstants.O_RDWR, 0)
                    try {
                        val line = "$topic\t$payload\n".toByteArray(StandardCharsets.UTF_8)
                        Os.write(fd, line, 0, line.size)
                    } finally {
                        Os.close(fd)
                    }
                } catch (_: Exception) {
                    // reader not up / FIFO absent — drop.
                }
            }
        }
    }
}
