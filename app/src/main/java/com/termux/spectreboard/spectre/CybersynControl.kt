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

    /**
     * LatinIME.onWindowShown/onWindowHidden call this directly - no polling needed.
     *
     * RETAINED, unlike every other topic here: this is a *state*, not an event. Cybersyn
     * gates its volume-key routing on it, and a subscriber that connects while the
     * keyboard is already up (app restart, phone reboot, broker restart) must learn the
     * current value immediately instead of waiting for the next show/hide. Without the
     * retain flag that subscriber sits in the wrong mode - volume keys go to the system
     * volume slider instead of the gyro clutch - until the user happens to toggle the
     * keyboard, which is exactly the desync that cost hours on 2026-08-02.
     *
     * The click/gyro topics stay non-retained on purpose: those are momentary commands to
     * the relay, and replaying a stale "ON" at reconnect would strand a held click.
     */
    fun publishImeShown(shown: Boolean) {
        publish(TOPIC_IME_SHOWN, if (shown) "ON" else "OFF", retain = true)
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

    private fun publish(topic: String, payload: String, retain: Boolean = false) =
        MqttPublisher.publish(topic, payload, retain)

    // Minimal MQTT 3.1.1 CONNECT+PUBLISH client, same hand-rolled wire encoding as
    // cybersyn-stub1's TrackpadStubActivity. All socket I/O runs on a HandlerThread since these
    // toggles fire from the keyboard's key-handling path, never the socket-owning thread.
    private object MqttPublisher {
        private const val KEEPALIVE_SECONDS = 30
        private val PING_INTERVAL_MS = (KEEPALIVE_SECONDS * 1000L) / 2
        private const val CONNECT_TIMEOUT_MS = 5_000

        private val thread = HandlerThread("spectreboard-cybersyn-mqtt").apply { start() }
        private val handler = Handler(thread.looper)
        private var socket: Socket? = null
        private var out: java.io.OutputStream? = null

        /**
         * Bumped on every close so a drain thread outliving its socket can't tear down the
         * replacement connection.
         */
        private var generation = 0

        private val pingRunnable = object : Runnable {
            override fun run() {
                val o = out
                if (o == null) return
                try {
                    // PINGREQ: the broker advertised a 30s keepalive at CONNECT and will
                    // disconnect a client that goes quiet for 1.5x that. Without this the
                    // connection died ~45s after every publish burst, and because a
                    // broker-closed socket still reports isConnected=true, the next
                    // ime_shown transition was written into a dead socket and silently
                    // lost -- the real source of "the volume keys stopped following the
                    // keyboard".
                    o.write(0xC0)
                    o.write(0x00)
                    o.flush()
                    handler.postDelayed(this, PING_INTERVAL_MS)
                } catch (_: Exception) {
                    closeSocket()
                }
            }
        }

        fun publish(topic: String, payload: String, retain: Boolean) {
            handler.post { doPublish(topic, payload, retain) }
        }

        private fun doPublish(topic: String, payload: String, retain: Boolean) {
            try {
                ensureConnected()
                val topicBytes = topic.toByteArray(StandardCharsets.UTF_8)
                val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)
                val remaining = 2 + topicBytes.size + payloadBytes.size
                val o = out ?: return
                // PUBLISH, QoS 0; bit 0 is RETAIN.
                o.write(if (retain) 0x31 else 0x30)
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
            newSocket.soTimeout = CONNECT_TIMEOUT_MS
            val newOut = newSocket.getOutputStream()
            val protocol = "MQTT".toByteArray(StandardCharsets.UTF_8)
            val clientId = ("spectreboard-${android.os.Process.myPid()}").toByteArray(StandardCharsets.UTF_8)
            // Last Will: a retained ime_shown is only safe with one. If this process dies
            // with the keyboard up (crash, force-stop, low-memory kill) the retained value
            // stays "ON" forever and every future subscriber starts in gyro mode with no
            // keyboard on screen. The will makes the broker publish a retained "OFF" on
            // ungraceful disconnect, so the stuck state self-heals.
            val willTopic = TOPIC_IME_SHOWN.toByteArray(StandardCharsets.UTF_8)
            val willMessage = "OFF".toByteArray(StandardCharsets.UTF_8)
            // clean session (0x02) | will flag (0x04) | will retain (0x20), will QoS 0.
            val connectFlags = 0x02 or 0x04 or 0x20
            val remaining = 2 + protocol.size + 1 + 1 + 2 +
                2 + clientId.size + 2 + willTopic.size + 2 + willMessage.size
            newOut.write(0x10)
            writeRemainingLength(newOut, remaining)
            writeUtf8(newOut, protocol)
            newOut.write(4)
            newOut.write(connectFlags)
            newOut.write(0)
            newOut.write(KEEPALIVE_SECONDS)
            writeUtf8(newOut, clientId)
            writeUtf8(newOut, willTopic)
            writeUtf8(newOut, willMessage)
            newOut.flush()
            newSocket.getInputStream().read(ByteArray(4))
            newSocket.soTimeout = 0
            socket = newSocket
            out = newOut

            val myGeneration = generation
            // Drain PINGRESP (and anything else) so the receive buffer can't fill, and
            // notice a broker-side close: isConnected/isClosed only describe THIS end, so
            // reading EOF is the only way to learn the connection is gone before the next
            // write silently disappears into it.
            Thread({
                runCatching {
                    val input = newSocket.getInputStream()
                    val buf = ByteArray(64)
                    while (input.read(buf) >= 0) { /* discard */ }
                }
                handler.post { if (generation == myGeneration) closeSocket() }
            }, "spectreboard-cybersyn-mqtt-drain").apply { isDaemon = true }.start()

            handler.removeCallbacks(pingRunnable)
            handler.postDelayed(pingRunnable, PING_INTERVAL_MS)
        }

        private fun closeSocket() {
            generation++
            handler.removeCallbacks(pingRunnable)
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
