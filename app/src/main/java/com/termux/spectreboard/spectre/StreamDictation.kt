package com.termux.spectreboard.spectre

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import com.termux.spectreboard.latin.R
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Streaming dictation over WebSocket to comrade's sherpa-onnx server
 * (ws://100.108.8.60:8770). Captures 16kHz mono PCM16 from the mic, streams it,
 * and surfaces what comes back: live "partial" as composing text, "final"
 * (server-side endpoint / pause) committed with a trailing space.
 *
 * Toggle model (Google-voice-typing style): first tap starts a continuous
 * session (button lights up), each pause finalizes a segment, second tap stops.
 * Compute is entirely on comrade — the phone only streams audio + inserts text.
 *
 * State discipline: isRecording is the single source of truth for the button
 * light AND the toggle decision. It MUST return to false on every exit path —
 * user stop, mic failure, AND far-side close (server endpoint restart, Tailscale
 * hiccup). The far-side path is what used to hang after 2 uses: onClose only
 * logged, isRecording stayed true, the light lied, and the next tap fell into
 * stop() as a no-op. onClose/onError now drive a real teardown on the main thread.
 */
object StreamDictation {
    private const val TAG = "StreamDictation"
    private const val SERVER = "ws://100.108.8.60:8770"
    private const val SR = 16000
    private const val CHAN = "dictation"
    private const val NOTIF_ID = 0xD1C7
    private val main = Handler(Looper.getMainLooper())

    @Volatile var isRecording = false
        private set

    // true only during the async connect+init window; blocks re-entrant toggles
    @Volatile private var starting = false

    private var rec: AudioRecord? = null
    private var ws: WebSocketClient? = null
    private var pump: Thread? = null
    private var onState: (() -> Unit)? = null   // remembered so remote-close teardown can refresh the light
    private var appContext: Context? = null     // app context, so stop()/remote-teardown can cancel the notification
    private var notifProgress = 0
    private val notifTick = Runnable { tickNotification() }

    /**
     * Ongoing, animated determinate-progress notification while dictating — the primary
     * "recording now" signal, visible everywhere (unlike the toolbar key, which is hidden
     * when the toolbar row is collapsed). The Pixel punch-hole "download" indicator surfaces
     * notifications that look like a moving progress bar, so this uses a DETERMINATE bar that
     * keeps advancing but is capped below 100% — a download that never finishes, exactly the
     * look the old Whisper "downloading" notice had. Small icon is SpectreBoard's own 24dp
     * monochrome mic vector (a framework full-color asset renders as a white blob in the bar).
     */
    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHAN) == null) {
            val ch = NotificationChannel(CHAN, "Dictation", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildAndNotify(ctx: Context, progress: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        val n = Notification.Builder(ctx, CHAN)
            .setSmallIcon(R.drawable.sym_keyboard_voice_rounded)
            .setContentTitle("Dictating…")
            .setContentText("Listening — tap mic again to stop")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(Color.RED)
            .setColorized(true)
            .setProgress(100, progress, false)       // determinate, capped <100 → punch-hole download ring
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    private fun tickNotification() {
        val ctx = appContext ?: return
        if (!isRecording) return
        notifProgress = (notifProgress + 12) % 84    // cycles 0..83, +12 base below → never reaches 100
        buildAndNotify(ctx, 12 + notifProgress)
        main.postDelayed(notifTick, 450)
    }

    private fun showRecordingNotification(ctx: Context) {
        notifProgress = 0
        buildAndNotify(ctx, 12)
        main.postDelayed(notifTick, 450)
    }

    private fun cancelRecordingNotification() {
        main.removeCallbacks(notifTick)
        val c = appContext ?: return
        try { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) } catch (_: Exception) {}
    }

    /** Tactile fallback indicator: one buzz on start, two on stop — always works, no screen needed. */
    private fun vibrate(ctx: Context?, pattern: LongArray) {
        val c = ctx ?: return
        try {
            val v = c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {}
    }

    fun toggle(context: Context,
                onAppend: (String) -> Unit,
                onFinal: (String) -> Unit,
                onRevise: (Int, String) -> Unit,
                onStateChange: () -> Unit) {
        if (starting) { Log.i(TAG, "toggle ignored (connect in flight)"); return }
        if (isRecording) stop(onStateChange) else start(context, onAppend, onFinal, onRevise, onStateChange)
    }

    /** Tear down any leftover session so a new one always starts from a clean slate. */
    private fun cleanupResidue() {
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        rec = null
        val old = ws; ws = null
        try { old?.close() } catch (_: Exception) {}
    }

    /**
     * Called from the socket callbacks (onClose/onError) when the session dies
     * from the far side. Runs on the main thread. Reuses the normal stop() path
     * so isRecording resets and the light clears.
     */
    private fun teardownFromRemote(reason: String) {
        main.post {
            if (isRecording) {
                Log.w(TAG, "remote teardown: $reason")
                stop(onState ?: {})
            }
        }
    }

    fun start(context: Context,
              onAppend: (String) -> Unit,
              onFinal: (String) -> Unit,
              onRevise: (Int, String) -> Unit,
              onStateChange: () -> Unit) {
        if (isRecording || starting) return
        starting = true
        onState = onStateChange
        appContext = context.applicationContext
        cleanupResidue()

        // Connect + AudioRecord init are blocking; do them off the IME/main thread
        // so a slow link can't freeze the keyboard (up to 2s connect timeout).
        pump = thread(name = "stream-dictation") {
            val client = object : WebSocketClient(URI(SERVER)) {
                override fun onOpen(h: ServerHandshake?) { Log.i(TAG, "ws open") }
                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.i(TAG, "ws close code=$code remote=$remote reason=$reason recording=$isRecording")
                    teardownFromRemote("ws close code=$code remote=$remote")
                }
                override fun onError(ex: Exception?) {
                    Log.e(TAG, "ws error", ex)
                    teardownFromRemote("ws error ${ex?.message}")
                }
                override fun onMessage(message: String?) {
                    val m = message ?: return
                    try {
                        val o = JSONObject(m)
                        val type = o.optString("type")
                        when (type) {
                            "append" -> { val t = o.getString("text"); main.post { onAppend(t) } }
                            "final"  -> { val t = o.optString("text", ""); main.post { onFinal(t) } }
                            "revise" -> {
                                val pop = o.getInt("pop_chars")
                                val repl = o.getString("replace_with")
                                main.post { onRevise(pop, repl) }
                            }
                            // Legacy protocol (pre-server-upgrade): fall back to partial/final
                            else -> when {
                                o.has("final")   -> { val t = o.getString("final"); main.post { onFinal(t) } }
                                o.has("partial") -> { val t = o.getString("partial"); main.post { onAppend(t) } }
                            }
                        }
                    } catch (e: Exception) { Log.w(TAG, "bad msg $m", e) }
                }
            }
            ws = client

            val connected = try {
                client.connectBlocking(2, TimeUnit.SECONDS)
            } catch (e: Exception) { Log.e(TAG, "ws connect failed", e); false }
            if (!connected) {
                Log.e(TAG, "ws connect timeout/failed to $SERVER")
                ws = null; starting = false
                main.post { Toast.makeText(context, "Dictation server unreachable (comrade:8770)", Toast.LENGTH_SHORT).show() }
                return@thread
            }

            val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SR * 2),
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized (state=${r.state})")
                r.release()
                val c = ws; ws = null; try { c?.close() } catch (_: Exception) {}
                starting = false
                main.post { Toast.makeText(context, "Mic unavailable — retry", Toast.LENGTH_SHORT).show() }
                return@thread
            }
            rec = r
            r.startRecording()
            isRecording = true
            starting = false
            main.post { onStateChange(); showRecordingNotification(context.applicationContext) }
            vibrate(appContext, longArrayOf(0, 45))            // single buzz = started
            Log.i(TAG, "session start")

            val buf = ShortArray(1600) // 0.1s frames
            var dropped = 0
            while (isRecording) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    val c = ws
                    if (c != null && c.isOpen) {
                        val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until n) bb.putShort(buf[i])
                        try { c.send(bb.array()) } catch (e: Exception) { Log.w(TAG, "send failed", e) }
                    } else if (dropped++ % 20 == 0) {
                        Log.w(TAG, "ws not open mid-session — audio dropping (recording=$isRecording)")
                    }
                }
            }
            Log.i(TAG, "pump exit")
        }
    }

    fun stop(onStateChange: () -> Unit) {
        Log.i(TAG, "session stop")
        isRecording = false
        starting = false
        try { rec?.stop() } catch (_: Exception) {}          // unblocks the pump's read
        // Only join the pump if we're NOT already on it (remote teardown posts to main, so we're not)
        try { if (Thread.currentThread() !== pump) pump?.join(600) } catch (_: Exception) {}
        pump = null
        try { rec?.release() } catch (_: Exception) {}
        rec = null
        val closing = ws                                     // capture THIS client
        ws = null                                            // detach now so a new start is clean
        try { closing?.send("EOF") } catch (_: Exception) {}
        main.postDelayed({ try { closing?.close() } catch (_: Exception) {} }, 400)
        cancelRecordingNotification()
        vibrate(appContext, longArrayOf(0, 45, 90, 45))       // double buzz = stopped
        onState = null
        onStateChange()
    }
}
