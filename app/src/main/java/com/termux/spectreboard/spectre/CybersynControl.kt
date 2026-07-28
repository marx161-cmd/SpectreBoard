// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.termux.spectreboard.latin.utils.prefs
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Native click/clutch/gyro controls for the Cybersyn desktop-trackpad relay
 * (cybersyn-hid-relay.py on comrade), driven directly from numpad keys instead of
 * round-tripping through the separate Cybersyn app.
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

    private const val BROKER = "100.108.8.60"
    private const val PORT = 1883
    private const val TOPIC_PULSE_CLICK = "cybersyn/hid/click"
    private const val TOPIC_HOLD = "android/click"
    private const val TOPIC_GYRO_GATE = "android/clutch"
    // Real-time IME visibility, for Cybersyn's volume-key daemon to gate its input-device
    // grab on - no dumpsys polling needed, LatinIME already knows exactly when its own
    // window shows/hides.
    private const val TOPIC_IME_SHOWN = "spectreboard/ime_shown"

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

    /** LatinIME.onWindowShown/onWindowHidden call this directly - no polling needed. */
    fun publishImeShown(shown: Boolean) {
        publish(TOPIC_IME_SHOWN, if (shown) "ON" else "OFF")
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

    private fun publish(topic: String, payload: String) = MqttPublisher.publish(topic, payload)

    // Minimal MQTT 3.1.1 CONNECT+PUBLISH client, same hand-rolled wire encoding as
    // cybersyn-stub1's TrackpadStubActivity. All socket I/O runs on a HandlerThread since these
    // toggles fire from the keyboard's key-handling path, never the socket-owning thread.
    private object MqttPublisher {
        private val thread = HandlerThread("spectreboard-cybersyn-mqtt").apply { start() }
        private val handler = Handler(thread.looper)
        private var socket: Socket? = null
        private var out: java.io.OutputStream? = null

        fun publish(topic: String, payload: String) {
            handler.post { doPublish(topic, payload) }
        }

        private fun doPublish(topic: String, payload: String) {
            try {
                ensureConnected()
                val topicBytes = topic.toByteArray(StandardCharsets.UTF_8)
                val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)
                val remaining = 2 + topicBytes.size + payloadBytes.size
                val o = out ?: return
                o.write(0x30)
                writeRemainingLength(o, remaining)
                writeUtf8(o, topicBytes)
                o.write(payloadBytes)
                o.flush()
            } catch (_: Exception) {
                closeSocket()
            }
        }

        private fun ensureConnected() {
            val s = socket
            if (s != null && s.isConnected && !s.isClosed) return
            val newSocket = Socket(BROKER, PORT)
            newSocket.tcpNoDelay = true
            val newOut = newSocket.getOutputStream()
            val protocol = "MQTT".toByteArray(StandardCharsets.UTF_8)
            val clientId = ("spectreboard-${android.os.Process.myPid()}").toByteArray(StandardCharsets.UTF_8)
            val remaining = 2 + protocol.size + 1 + 1 + 2 + 2 + clientId.size
            newOut.write(0x10)
            writeRemainingLength(newOut, remaining)
            writeUtf8(newOut, protocol)
            newOut.write(4)
            newOut.write(2)
            newOut.write(0)
            newOut.write(30)
            writeUtf8(newOut, clientId)
            newOut.flush()
            newSocket.getInputStream().read(ByteArray(4))
            socket = newSocket
            out = newOut
        }

        private fun closeSocket() {
            try { out?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            out = null
            socket = null
        }

        private fun writeUtf8(o: java.io.OutputStream, bytes: ByteArray) {
            o.write((bytes.size shr 8) and 0xff)
            o.write(bytes.size and 0xff)
            o.write(bytes)
        }

        private fun writeRemainingLength(o: java.io.OutputStream, valueIn: Int) {
            var value = valueIn
            do {
                var encoded = value % 128
                value /= 128
                if (value > 0) encoded = encoded or 128
                o.write(encoded)
            } while (value > 0)
        }
    }
}
