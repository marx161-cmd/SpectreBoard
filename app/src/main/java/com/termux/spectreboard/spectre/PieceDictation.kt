package com.termux.spectreboard.spectre

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import com.termux.spectreboard.latin.R
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dictation in pieces, transcribed on comrade (design: scope.md, entries dated 2026-10-17).
 *
 *  - Tap the mic: one buzz, toolbar light on, progress notification. Audio is recorded on
 *    the phone and cut into pieces at a pause once a piece is ~20 s long (hard cut at the
 *    quietest point if no pause comes by 30 s).
 *  - Each piece is Opus-encoded (WAV fallback), written to cacheDir, and piped over Termux's
 *    own ssh to `~/bin/dictate-piece <id> <seq>` on comrade, which returns the text. Pieces are
 *    sent while the user keeps talking, one at a time, so piece N always has piece N-1's text
 *    as its Whisper prompt on comrade.
 *  - Tap again: two buzzes. When the last piece is back, the whole text is typed ONCE as
 *    plain committed text (never a composing region, never edited afterwards).
 *  - Nothing is lost: piece files stay on disk until their text has arrived. Retries are
 *    safe (comrade returns saved text for a repeated id+seq). If comrade stays unreachable,
 *    the recording is kept and recovered to the clipboard on the next dictation.
 *
 * Controls (buzz patterns, notification, single isRecording flag driving the toolbar light)
 * are taken over from [StreamDictation], the old streaming path.
 */
object PieceDictation {
    private const val TAG = "PieceDictation"

    private const val PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val REMOTE = "comrade@100.108.8.60"
    private const val REMOTE_CMD = "/home/comrade/bin/dictate-piece"

    private const val SR = 16_000
    private const val FRAME = 480                     // 30 ms
    private const val MIN_PIECE_FRAMES = 20_000 / 30  // start looking for a pause after ~20 s
    private const val MAX_PIECE_FRAMES = 30_000 / 30  // hard cut by 30 s (Whisper's window)
    private const val PAUSE_FRAMES = 10               // 300 ms of quiet = a pause
    private const val QUIET_ABOVE_FLOOR_DB = 12f      // "quiet" = within 12 dB of the noise floor
    private const val MIN_FINAL_FRAMES = 15           // drop a last piece shorter than 0.45 s
    private val RETRY_DELAYS_S = longArrayOf(1, 2, 4, 8, 15, 30, 30)

    private const val CHAN = "dictation"
    private const val NOTIF_ID = 0xD1C7
    private const val FAIL_NOTIF_ID = 0xD1C9

    private val main = Handler(Looper.getMainLooper())

    /** Single source of truth for the toolbar light AND the toggle decision. */
    @Volatile var isRecording = false
        private set

    /** True from the stop tap until the text is typed (or saved for recovery). */
    @Volatile var isFinishing = false
        private set

    /**
     * Bumped by LatinIME.onStartInput. If it changes between the stop tap and the text being
     * ready, the user has moved to another field, so the text goes to the clipboard instead
     * of being typed somewhere unexpected.
     */
    @Volatile var fieldGeneration = 0

    private var appContext: Context? = null
    private var onState: (() -> Unit)? = null
    private var session: Session? = null

    private var notifProgress = 0
    private val notifTick = Runnable { tickNotification() }

    private class Piece(val seq: Int, val file: File)

    private class Session(val id: String, val dir: File) {
        val queue = LinkedBlockingQueue<Piece>()   // seq = -1 marks the end of the recording
        val texts = sortedMapOf<Int, String>()
        @Volatile var piecesCut = 0
        @Volatile var piecesDone = 0
        @Volatile var failed = false
        var recorder: AudioRecord? = null
        var capture: Thread? = null
        var stopGeneration = 0
    }

    // ---------------------------------------------------------------- public API

    fun toggle(context: Context, commit: (String) -> Unit, onStateChange: () -> Unit) {
        when {
            isRecording -> stop()
            isFinishing -> toast(context, "Still transcribing the last dictation…")
            else -> start(context, commit, onStateChange)
        }
    }

    // ---------------------------------------------------------------- start / stop

    private fun start(context: Context, commit: (String) -> Unit, onStateChange: () -> Unit) {
        val ctx = context.applicationContext
        appContext = ctx
        onState = onStateChange

        val root = File(ctx.cacheDir, "dictation").apply { mkdirs() }
        val id = "sb" + System.currentTimeMillis().toString(36) + (1000..9999).random()
        val s = Session(id, File(root, id).apply { mkdirs() })
        session = s

        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SR * 2))
        } catch (e: Exception) { Log.e(TAG, "AudioRecord ctor failed", e); null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            s.dir.deleteRecursively()
            session = null
            toast(ctx, "Mic unavailable — retry")
            vibrate(ctx, longArrayOf(0, 45, 90, 45))
            return
        }
        s.recorder = rec
        rec.startRecording()
        isRecording = true
        onStateChange()
        showRecordingNotification(ctx)
        vibrate(ctx, longArrayOf(0, 45))            // single buzz = started
        Log.i(TAG, "session ${s.id} start")

        s.capture = thread(name = "piece-capture") { captureLoop(s, rec) }
        thread(name = "piece-upload") { uploadLoop(ctx, s, commit) }
    }

    private fun stop() {
        val s = session ?: run { isRecording = false; return }
        Log.i(TAG, "session ${s.id} stop")
        s.stopGeneration = fieldGeneration
        isRecording = false                          // capture loop drains, cuts the last piece
        isFinishing = true
        try { s.recorder?.stop() } catch (_: Exception) {}
        vibrate(appContext, longArrayOf(0, 45, 90, 45)) // double buzz = stopped
        updateNotificationText("Transcribing…")
        onState?.invoke()
    }

    // ---------------------------------------------------------------- capture + cutting

    private fun captureLoop(s: Session, rec: AudioRecord) {
        val frame = ShortArray(FRAME)
        var piece = ShortArray(MAX_PIECE_FRAMES * FRAME + FRAME)
        var len = 0                                   // samples in the current piece
        val frameDb = FloatArray(MAX_PIECE_FRAMES + 2)
        var frames = 0
        var floorDb = -50f
        var quietRun = 0
        var seq = 0

        fun cut(samples: Int) {
            val pcm = piece.copyOf(samples)
            val f = File(s.dir, "$seq.piece")
            if (!encodeOpus(pcm, f)) writeWav(pcm, f)
            s.queue.put(Piece(seq, f))
            s.piecesCut = seq + 1
            seq++
        }

        try {
            while (true) {
                val n = rec.read(frame, 0, FRAME)
                if (n <= 0) { if (!isRecording) break else continue }
                System.arraycopy(frame, 0, piece, len, n)
                len += n
                val db = dbfs(frame, n)
                frameDb[frames] = db
                frames++
                // noise floor: drops instantly, rises slowly
                floorDb = if (db < floorDb) db else floorDb + 0.02f
                quietRun = if (db < floorDb + QUIET_ABOVE_FLOOR_DB) quietRun + 1 else 0

                if (frames >= MIN_PIECE_FRAMES && quietRun >= PAUSE_FRAMES) {
                    cut(len); len = 0; frames = 0; quietRun = 0
                } else if (frames >= MAX_PIECE_FRAMES) {
                    // no pause by 30 s: cut at the quietest frame of the last 5 s
                    val from = frames - 5_000 / 30
                    var best = frames - 1
                    for (i in from until frames) if (frameDb[i] < frameDb[best]) best = i
                    val cutAt = (best + 1) * FRAME
                    val rest = len - cutAt
                    cut(cutAt)
                    val next = ShortArray(piece.size)
                    System.arraycopy(piece, cutAt, next, 0, rest)
                    piece = next
                    len = rest
                    val restFrames = frames - (best + 1)
                    System.arraycopy(frameDb, best + 1, frameDb, 0, restFrames)
                    frames = restFrames
                    quietRun = 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture loop error", e)
        } finally {
            try { rec.release() } catch (_: Exception) {}
        }
        // last piece: skip if it's tiny or nothing but quiet
        var loud = false
        for (i in 0 until frames) if (frameDb[i] >= floorDb + QUIET_ABOVE_FLOOR_DB) { loud = true; break }
        if (frames >= MIN_FINAL_FRAMES && loud) cut(len)
        s.queue.put(Piece(-1, File("")))
        Log.i(TAG, "session ${s.id} capture done, ${s.piecesCut} pieces")
    }

    private fun dbfs(buf: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
        val rms = sqrt(sum / n) / Short.MAX_VALUE
        return if (rms <= 1e-6) -120f else (20 * log10(rms)).toFloat()
    }

    // ---------------------------------------------------------------- upload

    private fun uploadLoop(ctx: Context, s: Session, commit: (String) -> Unit) {
        while (true) {
            val p = s.queue.take()
            if (p.seq < 0) break
            if (s.failed) continue                   // keep remaining files for recovery
            val text = sendWithRetry(s.id, p)
            if (text == null) { s.failed = true; continue }
            synchronized(s.texts) { s.texts[p.seq] = text }
            p.file.delete()
            s.piecesDone++
            if (isRecording) updateNotificationText("Listening — ${s.piecesDone} piece${if (s.piecesDone == 1) "" else "s"} done")
        }
        val full = synchronized(s.texts) { s.texts.values.filter { it.isNotBlank() }.joinToString(" ").trim() }
        main.post { finish(ctx, s, full, commit) }
        // Only after this dictation is typed, so a down comrade can't stall it.
        if (!s.failed) recoverOldSessions(ctx, except = s.id)
    }

    private fun finish(ctx: Context, s: Session, full: String, commit: (String) -> Unit) {
        isFinishing = false
        cancelRecordingNotification()
        onState?.invoke()
        if (s.failed) {
            failNotification(ctx, "Couldn't reach comrade — dictation saved. It'll be recovered to the clipboard next time you dictate.")
            vibrate(ctx, longArrayOf(0, 300))
            return                                   // session dir stays for recovery
        }
        s.dir.deleteRecursively()
        if (full.isEmpty()) { toast(ctx, "No speech heard"); return }
        if (fieldGeneration != s.stopGeneration) {
            copyToClipboard(ctx, full)
            toast(ctx, "Field changed — dictation copied to clipboard")
            return
        }
        try { commit("$full ") } catch (e: Exception) {
            Log.e(TAG, "commit failed", e)
            copyToClipboard(ctx, full)
            toast(ctx, "Couldn't type it — dictation copied to clipboard")
        }
    }

    /** Runs dictate-piece over ssh with retries. Null = gave up (comrade unreachable/failing). */
    private fun sendWithRetry(id: String, p: Piece): String? {
        for (attempt in 0..RETRY_DELAYS_S.size) {
            val r = runRemote(listOf(id, p.seq.toString()), p.file)
            if (r.first == 0) return r.second
            if (r.first == 2) { Log.e(TAG, "piece ${p.seq} rejected by comrade"); return "" }
            if (attempt == RETRY_DELAYS_S.size) break
            Log.w(TAG, "piece ${p.seq} attempt ${attempt + 1} failed (rc=${r.first}), retrying")
            Thread.sleep(RETRY_DELAYS_S[attempt] * 1000)
        }
        return null
    }

    /**
     * Termux's own ssh, run directly (same UID and SELinux domain as Termux, like the macro
     * keys). stdout/stderr go to files, not pipes: a ControlPersist master forks into the
     * background and could otherwise hold a pipe open forever.
     */
    private fun runRemote(args: List<String>, stdin: File?): Pair<Int, String> {
        val tmp = File("$PREFIX/tmp").apply { mkdirs() }
        val out = File.createTempFile("dictate", ".out", tmp)
        val err = File.createTempFile("dictate", ".err", tmp)
        try {
            val pb = ProcessBuilder(
                "$PREFIX/bin/ssh",
                "-o", "BatchMode=yes", "-o", "ConnectTimeout=8",
                "-o", "ServerAliveInterval=10", "-o", "ServerAliveCountMax=3",
                "-o", "ControlMaster=auto", "-o", "ControlPath=$PREFIX/tmp/dictate-ssh-%C",
                "-o", "ControlPersist=120",
                REMOTE, (listOf(REMOTE_CMD) + args).joinToString(" "),
            )
            pb.environment().apply {
                put("HOME", TERMUX_HOME); put("PREFIX", PREFIX); put("TMPDIR", "$PREFIX/tmp")
                put("PATH", "$PREFIX/bin"); put("LD_LIBRARY_PATH", "$PREFIX/lib")
            }
            if (stdin != null) pb.redirectInput(stdin) else pb.redirectInput(File("/dev/null"))
            pb.redirectOutput(out)
            pb.redirectError(err)
            val proc = pb.start()
            if (!proc.waitFor(180, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return 255 to ""
            }
            val rc = proc.exitValue()
            if (rc != 0) Log.w(TAG, "ssh rc=$rc: ${err.readText().take(300)}")
            return rc to out.readText().trim()
        } catch (e: Exception) {
            Log.e(TAG, "ssh launch failed", e)
            return 255 to ""
        } finally {
            out.delete(); err.delete()
        }
    }

    /** Leftover sessions (comrade was unreachable, or the keyboard died mid-dictation). */
    private fun recoverOldSessions(ctx: Context, except: String) {
        val root = File(ctx.cacheDir, "dictation")
        val old = root.listFiles()?.filter { it.isDirectory && it.name != except } ?: return
        for (dir in old) {
            val pieces = dir.listFiles { f -> f.name.endsWith(".piece") }
                ?.sortedBy { it.name.substringBefore('.').toIntOrNull() ?: 0 } ?: emptyList()
            var ok = true
            for (f in pieces) {
                val seq = f.name.substringBefore('.').toIntOrNull() ?: continue
                if (sendWithRetry(dir.name, Piece(seq, f)) == null) { ok = false; break }
                f.delete()
            }
            if (!ok) continue
            val r = runRemote(listOf(dir.name, "--full"), null)
            if (r.first != 0) continue
            dir.deleteRecursively()
            if (r.second.isNotBlank()) {
                main.post {
                    copyToClipboard(ctx, r.second)
                    toast(ctx, "Recovered an earlier dictation — it's on the clipboard")
                    cancelFailNotification(ctx)
                }
            }
        }
    }

    // ---------------------------------------------------------------- encoding

    /** Opus in Ogg via the platform encoder. False on any failure (caller falls back to WAV). */
    private fun encodeOpus(pcm: ShortArray, out: File): Boolean {
        if (pcm.isEmpty()) return false
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (v in pcm) bytes.putShort(v)
            val raw = bytes.array()
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SR, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            var track = -1
            var inPos = 0
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + 20_000
            while (!outputDone) {
                if (System.currentTimeMillis() > deadline) throw IllegalStateException("opus encode timeout")
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        val n = min(buf.remaining(), raw.size - inPos) and 1.inv()
                        buf.put(raw, inPos, n)
                        val ptsUs = (inPos / 2).toLong() * 1_000_000L / SR
                        inPos += n
                        if (inPos >= raw.size) {
                            codec.queueInputBuffer(i, 0, n, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ptsUs, 0)
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                } else if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && track >= 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            muxer.stop()
            out.length() > 0
        } catch (e: Exception) {
            Log.w(TAG, "opus encode failed, using WAV", e)
            false
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun writeWav(pcm: ShortArray, out: File) {
        val dataLen = pcm.size * 2
        RandomAccessFile(out, "rw").use { f ->
            f.setLength(0)
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(36 + dataLen); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
            h.putInt(SR); h.putInt(SR * 2); h.putShort(2); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(dataLen)
            f.write(h.array())
            val b = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
            for (v in pcm) b.putShort(v)
            f.write(b.array())
        }
    }

    // ---------------------------------------------------------------- feedback

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHAN) == null) {
            val ch = NotificationChannel(CHAN, "Dictation", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            ch.setSound(null, null)
            nm.createNotificationChannel(ch)
        }
    }

    @Volatile private var notifText = "Listening — tap mic again to stop"

    private fun buildAndNotify(ctx: Context, progress: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        val n = Notification.Builder(ctx, CHAN)
            .setSmallIcon(R.drawable.sym_keyboard_voice_rounded)
            .setContentTitle("Dictating…")
            .setContentText(notifText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(Color.RED)
            .setColorized(true)
            .setProgress(100, progress, false)       // determinate, capped <100 → punch-hole ring
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    private fun tickNotification() {
        val ctx = appContext ?: return
        if (!isRecording && !isFinishing) return
        notifProgress = (notifProgress + 12) % 84
        buildAndNotify(ctx, 12 + notifProgress)
        main.postDelayed(notifTick, 450)
    }

    private fun showRecordingNotification(ctx: Context) {
        notifText = "Listening — tap mic again to stop"
        notifProgress = 0
        main.removeCallbacks(notifTick)
        buildAndNotify(ctx, 12)
        main.postDelayed(notifTick, 450)
    }

    private fun updateNotificationText(text: String) { notifText = text }

    private fun cancelRecordingNotification() {
        main.removeCallbacks(notifTick)
        val c = appContext ?: return
        try { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) } catch (_: Exception) {}
    }

    private fun failNotification(ctx: Context, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        nm.notify(FAIL_NOTIF_ID, Notification.Builder(ctx, CHAN)
            .setSmallIcon(R.drawable.sym_keyboard_voice_rounded)
            .setContentTitle("Dictation not typed")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .build())
    }

    private fun cancelFailNotification(ctx: Context) {
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(FAIL_NOTIF_ID) } catch (_: Exception) {}
    }

    /** Tactile indicator: one buzz on start, two on stop, one long on failure. */
    private fun vibrate(ctx: Context?, pattern: LongArray) {
        val c = ctx ?: return
        try {
            val v = c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {}
    }

    private fun toast(ctx: Context, msg: String) {
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun copyToClipboard(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Dictation", text))
    }
}
