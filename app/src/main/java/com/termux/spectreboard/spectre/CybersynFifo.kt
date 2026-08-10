// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre

import android.os.Handler
import android.os.HandlerThread
import android.system.Os
import android.system.OsConstants
import java.nio.charset.StandardCharsets

/**
 * Shared writer into Cybersyn's ingest FIFO (`cybersyn-pub.fifo`) — the Kotlin equivalent of
 * the `cybersyn-send` wrapper. Every publish rides Cybersyn's ONE persistent MQTT connection
 * (MqttIngest → MqttBridge); nothing here opens its own broker connection.
 *
 * All writes run on one HandlerThread so callers on the key-handling path never do file I/O.
 */
object CybersynFifo {
    private const val FIFO_PATH = "/data/data/com.termux/files/usr/tmp/cybersyn-pub.fifo"

    private val thread = HandlerThread("spectreboard-cybersyn-fifo").apply { start() }
    private val handler = Handler(thread.looper)

    fun publish(topic: String, payload: String) {
        handler.post {
            try {
                // O_RDWR so open() never blocks even while Cybersyn's reader is respawning; we
                // only ever write. If the FIFO is absent (Cybersyn down) open throws ENOENT and
                // we drop — these are momentary commands, nothing to retry.
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
