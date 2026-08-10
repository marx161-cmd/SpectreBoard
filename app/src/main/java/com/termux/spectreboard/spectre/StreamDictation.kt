package com.termux.spectreboard.spectre

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Streaming dictation over WebSocket to comrade's sherpa-onnx server
 * (ws://100.108.8.60:8770). Captures 16kHz mono PCM16 from the mic, streams it,
 * and inserts what comes back: live "partial" as composing text, "final"
 * (server-side endpoint / pause) committed with a trailing space.
 *
 * Replaces the on-device Whisper path for the mic button. CPU-side compute is
 * entirely on comrade (sherpa Zipformer, ~21x realtime); the phone only streams
 * audio and inserts text — no model, no NPU, no battery cost.
 */
object StreamDictation {
    private const val TAG = "StreamDictation"
    private const val SERVER = "ws://100.108.8.60:8770"
    private const val SR = 16000
    private val main = Handler(Looper.getMainLooper())

    @Volatile var isRecording = false
        private set

    private var rec: AudioRecord? = null
    private var ws: WebSocketClient? = null
    private var pump: Thread? = null

    fun toggle(context: Context, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onStateChange: () -> Unit) {
        if (isRecording) stop(onStateChange) else start(context, onPartial, onFinal, onStateChange)
    }

    fun start(context: Context, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onStateChange: () -> Unit) {
        val client = object : WebSocketClient(URI(SERVER)) {
            override fun onOpen(h: ServerHandshake?) { Log.i(TAG, "ws open") }
            override fun onClose(code: Int, reason: String?, remote: Boolean) { Log.i(TAG, "ws close $code $reason") }
            override fun onError(ex: Exception?) { Log.e(TAG, "ws error", ex) }
            override fun onMessage(message: String?) {
                val m = message ?: return
                try {
                    val o = JSONObject(m)
                    when {
                        o.has("final")   -> { val t = o.getString("final");   main.post { onFinal(t) } }
                        o.has("partial") -> { val t = o.getString("partial"); main.post { onPartial(t) } }
                    }
                } catch (e: Exception) { Log.w(TAG, "bad msg $m", e) }
            }
        }
        ws = client
        try {
            if (!client.connectBlocking(2, java.util.concurrent.TimeUnit.SECONDS)) {
                Toast.makeText(context, "Dictation server unreachable (comrade:8770)", Toast.LENGTH_SHORT).show()
                ws = null; return
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Dictation connect failed", Toast.LENGTH_SHORT).show()
            ws = null; return
        }

        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SR * 2),
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release(); ws?.close(); ws = null
            Toast.makeText(context, "Mic unavailable", Toast.LENGTH_SHORT).show(); return
        }
        rec = r
        r.startRecording()
        isRecording = true
        onStateChange()

        pump = thread(name = "stream-dictation") {
            val buf = ShortArray(1600) // 0.1s frames
            while (isRecording) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until n) bb.putShort(buf[i])
                    try { ws?.takeIf { it.isOpen }?.send(bb.array()) } catch (_: Exception) {}
                }
            }
        }
    }

    fun stop(onStateChange: () -> Unit) {
        isRecording = false
        try { pump?.join(500) } catch (_: Exception) {}
        pump = null
        try { rec?.stop() } catch (_: Exception) {}
        rec?.release(); rec = null
        try { ws?.send("EOF") } catch (_: Exception) {}
        // brief grace for the trailing final, then close
        main.postDelayed({ try { ws?.close() } catch (_: Exception) {}; ws = null }, 600)
        onStateChange()
    }
}
