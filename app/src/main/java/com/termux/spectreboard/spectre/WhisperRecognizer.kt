package com.termux.spectreboard.spectre

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

object WhisperRecognizer {

    private const val TAG = "WhisperRecognizer"
    const val SAMPLE_RATE = 16000
    private const val MAX_SECONDS = 30
    private const val N_MELS = 80
    private const val N_FFT = 400
    private const val HOP_LENGTH = 160
    private const val N_FRAMES = 3000   // 30s / 10ms
    private const val CHUNK_SAMPLES = SAMPLE_RATE * MAX_SECONDS

    // ORT special tokens (filled at load time)
    private var SOT = 50258
    private var EOT = 50257
    private var EN  = 50259
    private var TRANSCRIBE = 50359
    private var NO_TIMESTAMPS = 50363

    private val ortEnv by lazy { OrtEnvironment.getEnvironment() }
    private var decSess: OrtSession? = null
    private var g5Encoder: WhisperG5WorkerClient? = null
    private var vocab: Array<String> = emptyArray()

    private val lock = Any()
    private var audioRecord: AudioRecord? = null
    private var job: Job? = null
    private var sessionDir: File? = null
    private var snippetIdx = 0

    var isRecording = false
        private set

    // called once from LatinIME (loadSettings); safe to call repeatedly — re-entrant guard at top
    fun init(context: Context): Unit = synchronized(lock) {
        if (decSess != null && g5Encoder?.isReady == true) return   // already initialised
        // ApplicationInfo.dataDir is always CE (/data/data/...) regardless of context protection mode
        val dir = File(context.applicationInfo.dataDir, "files")
        val decFile   = File(dir, "whisper_base_decoder.onnx")
        val vocabFile = File(dir, "whisper_base_vocab.txt")
        val tokFile   = File(dir, "whisper_base_special_tokens.json")
        if (!decFile.exists() || !vocabFile.exists()) {
            Log.w(TAG, "init: missing decoder or vocab at $dir")
            return
        }

        // --- Encoder: strict Tensor G5 worker ---
        try {
            g5Encoder = WhisperG5WorkerClient(context.applicationContext).also { it.start() }
            Log.i(TAG, "encoder: Tensor G5 worker")
        } catch (e: Exception) {
            Log.e(TAG, "G5 encoder worker failed", e)
            g5Encoder = null
            return
        }

        // --- Decoder: ORT int8 always ---
        try {
            decSess = ortEnv.createSession(decFile.absolutePath,
                OrtSession.SessionOptions().apply { addXnnpack(mapOf()) })
            vocab = vocabFile.readLines().toTypedArray()
            if (tokFile.exists()) {
                val json = tokFile.readText()
                SOT           = parseJsonInt(json, "sot")          ?: SOT
                EOT           = parseJsonInt(json, "eot")          ?: EOT
                EN            = parseJsonInt(json, "en")           ?: EN
                TRANSCRIBE    = parseJsonInt(json, "transcribe")   ?: TRANSCRIBE
                NO_TIMESTAMPS = parseJsonInt(json, "notimestamps") ?: NO_TIMESTAMPS
            }
            Log.i(TAG, "init OK encoder=G5 worker vocab=${vocab.size}")
        } catch (e: Exception) {
            Log.e(TAG, "ORT decoder failed", e)
            decSess = null
            g5Encoder?.stop()
            g5Encoder = null
        }
    }

    fun isAvailable() = synchronized(lock) { g5Encoder?.isReady == true && decSess != null }

    /** Toggle: first call starts recording, second call stops and transcribes. */
    fun toggle(context: Context, onResult: (String) -> Unit, onStateChange: () -> Unit) {
        Log.d(TAG, "toggle isRecording=$isRecording available=${isAvailable()}")
        if (isRecording) stop(context, onResult, onStateChange) else start(context, onResult, onStateChange)
    }

    private fun start(context: Context, onResult: (String) -> Unit, onStateChange: () -> Unit) {
        if (!isAvailable()) { Log.w(TAG, "start: not available (models not loaded)"); return }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
            maxOf(minBuf, SAMPLE_RATE * 2 * 4))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }

        val sessionTs = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val samplesDir = File(context.getExternalFilesDir(null), "whisper-samples")
        sessionDir = File(samplesDir, "session_$sessionTs").also { it.mkdirs() }
        snippetIdx = 0

        audioRecord = rec
        isRecording = true
        onStateChange()
        vibrate(context, VibrationEffect.EFFECT_CLICK)

        job = CoroutineScope(Dispatchers.IO).launch {
            val buf = FloatArray(CHUNK_SAMPLES)
            try {
                rec.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "startRecording failed", e)
                stopRec(rec)
                isRecording = false
                withContext(Dispatchers.Main) { onStateChange() }
                return@launch
            }
            val read = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            stopRec(rec)

            isRecording = false
            withContext(Dispatchers.Main) { onStateChange() }

            if (read > 0) {
                val samples = buf.copyOf(read)
                val text = try {
                    transcribe(samples)
                } catch (e: Exception) {
                    val msg = "Whisper G5 failed: ${e.message ?: e.javaClass.simpleName}"
                    Log.e(TAG, msg, e)
                    saveSnippet(samples, "ERROR: $msg")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                saveSnippet(samples, text)
                withContext(Dispatchers.Main) {
                    vibrate(context, VibrationEffect.EFFECT_DOUBLE_CLICK)
                    onResult(text)
                }
            }
        }

        // auto-stop at 30s — grab and null the reference so only one caller stops
        CoroutineScope(Dispatchers.IO).launch {
            delay(MAX_SECONDS * 1000L)
            if (isRecording) stopRec(takeRec())
        }
    }

    private fun stop(context: Context, onResult: (String) -> Unit, onStateChange: () -> Unit) {
        stopRec(takeRec())
        // Recording job unblocks from read(), sees isRecording=false, finishes naturally
    }

    /** Atomically take the AudioRecord reference, leaving audioRecord=null. */
    private fun takeRec(): AudioRecord? = synchronized(lock) {
        val r = audioRecord; audioRecord = null; r
    }

    /** Stop and release an AudioRecord, ignoring double-stop IllegalStateException. */
    private fun stopRec(rec: AudioRecord?) {
        rec ?: return
        try { rec.stop() } catch (_: Exception) {}
        try { rec.release() } catch (_: Exception) {}
    }

    private fun transcribe(samples: FloatArray): String {
        val dec = decSess ?: return ""
        val mel = computeMelSpectrogram(samples)                   // [1, 80, 3000]
        val encOut = runEncoder(mel)                               // flat [1500 * 384]
        return greedyDecode(dec, encOut)
    }

    private fun runEncoder(mel: FloatBuffer): FloatArray =
        (g5Encoder ?: error("G5 encoder worker is not initialized")).run(mel)

    private fun greedyDecode(sess: OrtSession, encoderOut: FloatArray): String {
        val encShape  = longArrayOf(1, 1500, 512)
        val encBuf    = FloatBuffer.wrap(encoderOut)
        val encTensor = OnnxTensor.createTensor(ortEnv, encBuf, encShape)

        val prompt = intArrayOf(SOT, EN, TRANSCRIBE, NO_TIMESTAMPS)
        val tokens = prompt.toMutableList()

        try {
            var done = false
            repeat(224) {
                if (done) return@repeat
                val idsBuf = ByteBuffer.allocateDirect(tokens.size * 8).order(ByteOrder.nativeOrder()).asLongBuffer()
                tokens.forEach { tok -> idsBuf.put(tok.toLong()) }
                idsBuf.rewind()
                val idsShape  = longArrayOf(1, tokens.size.toLong())
                val idsTensor = OnnxTensor.createTensor(ortEnv, idsBuf, idsShape)
                try {
                    val out = sess.run(mapOf(
                        "input_ids" to idsTensor,
                        "encoder_hidden_states" to encTensor,
                    ))
                    val logits = out[0].value as Array<*>          // [1][seq][vocab]
                    val lastStep = (logits[0] as Array<*>).last() as FloatArray
                    val nextTok = lastStep.indices.maxByOrNull { lastStep[it] } ?: EOT
                    if (nextTok == EOT) { done = true } else { tokens.add(nextTok) }
                } finally { idsTensor.close() }
            }
        } finally { encTensor.close() }

        // strip prompt tokens, BPE decode
        val textTokens = tokens.drop(prompt.size)
        return bpeDecode(textTokens)
    }

    private fun bpeDecode(tokens: List<Int>): String {
        val sb = StringBuilder()
        for (tok in tokens) {
            if (tok < 0 || tok >= vocab.size) continue
            val word = vocab[tok]
            when {
                word.startsWith("Ġ") -> { sb.append(' '); sb.append(word.substring(1)) }
                word.startsWith("Ċ") -> sb.append('\n')
                else -> sb.append(word)
            }
        }
        return sb.toString().trim()
    }

    private fun saveSnippet(samples: FloatArray, text: String) {
        val dir = sessionDir ?: return
        val name = "snippet_%03d".format(snippetIdx++)
        try {
            writeWav(File(dir, "$name.wav"), samples)
            File(dir, "$name.hyp.txt").writeText(text)
        } catch (_: Exception) {}
    }

    private fun writeWav(file: File, samples: FloatArray) {
        val pcm = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
        val dataBytes = pcm.size * 2
        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataBytes)
            header.put("WAVEfmt ".toByteArray())
            header.putInt(16); header.putShort(1); header.putShort(1)
            header.putInt(SAMPLE_RATE); header.putInt(SAMPLE_RATE * 2)
            header.putShort(2); header.putShort(16)
            header.put("data".toByteArray())
            header.putInt(dataBytes)
            fos.write(header.array())
            val pcmBuf = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { pcmBuf.putShort(it) }
            fos.write(pcmBuf.array())
        }
    }

    // -------------------------------------------------------------------------
    // Mel spectrogram (Whisper-compatible)
    // -------------------------------------------------------------------------

    private fun computeMelSpectrogram(samples: FloatArray): FloatBuffer {
        // pad / trim to CHUNK_SAMPLES
        val audio = FloatArray(CHUNK_SAMPLES)
        samples.copyInto(audio, 0, 0, minOf(samples.size, CHUNK_SAMPLES))

        val filterbank = buildMelFilterbank()
        val result = Array(N_MELS) { FloatArray(N_FRAMES) }

        val windowedBuf = FloatArray(N_FFT)
        val hannWindow  = FloatArray(N_FFT) { 0.5f * (1f - cos(2f * PI.toFloat() * it / N_FFT)) }
        val fftReal     = FloatArray(512)    // padded to next pow2
        val fftImag     = FloatArray(512)

        for (frame in 0 until N_FRAMES) {
            val offset = frame * HOP_LENGTH
            for (i in 0 until N_FFT) {
                windowedBuf[i] = if (offset + i < audio.size) audio[offset + i] * hannWindow[i] else 0f
            }
            fftReal.fill(0f); fftImag.fill(0f)
            for (i in 0 until N_FFT) fftReal[i] = windowedBuf[i]
            fft(fftReal, fftImag, 512)

            // power spectrum (first 201 bins)
            val ps = FloatArray(201) { i -> fftReal[i].pow(2) + fftImag[i].pow(2) }

            for (m in 0 until N_MELS) {
                var dot = 0f
                for (k in 0..200) dot += filterbank[m][k] * ps[k]
                // Whisper uses log10, not natural log
                result[m][frame] = log10(maxOf(dot, 1e-10f))
            }
        }

        // Whisper normalization: clamp to (max - 8), then (x + 4) / 4
        var maxVal = Float.NEGATIVE_INFINITY
        for (m in 0 until N_MELS) for (t in 0 until N_FRAMES) if (result[m][t] > maxVal) maxVal = result[m][t]
        val minVal = maxVal - 8f
        val flat = FloatBuffer.allocate(N_MELS * N_FRAMES)
        for (m in 0 until N_MELS) for (t in 0 until N_FRAMES) {
            flat.put((maxOf(result[m][t], minVal) + 4f) / 4f)
        }
        flat.rewind()
        return flat
    }

    private fun buildMelFilterbank(): Array<FloatArray> {
        val fMin = 0.0; val fMax = 8000.0
        val nFreqs = 201  // N_FFT/2 + 1
        // Bin k corresponds to frequency k * sr/N_FFT (window size, not padded FFT size)
        val freqs = DoubleArray(nFreqs) { it * SAMPLE_RATE.toDouble() / N_FFT.toDouble() }

        fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        val melMin = hzToMel(fMin); val melMax = hzToMel(fMax)
        val melPoints = DoubleArray(N_MELS + 2) { melMin + it * (melMax - melMin) / (N_MELS + 1) }
        val hzPoints  = DoubleArray(N_MELS + 2) { melToHz(melPoints[it]) }

        // Slaney-style area normalization (matches librosa default used by Whisper)
        return Array(N_MELS) { m ->
            val norm = 2.0 / (hzPoints[m + 2] - hzPoints[m])
            FloatArray(nFreqs) { k ->
                val f = freqs[k]
                val lower = (f - hzPoints[m])   / (hzPoints[m+1] - hzPoints[m])
                val upper = (hzPoints[m+2] - f) / (hzPoints[m+2] - hzPoints[m+1])
                (maxOf(0.0, minOf(lower, upper)) * norm).toFloat()
            }
        }
    }

    /** Cooley-Tukey in-place FFT, size must be power of 2. */
    private fun fft(re: FloatArray, im: FloatArray, n: Int) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat(); val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f; var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i+k]; val uIm = im[i+k]
                    val vRe = re[i+k+len/2] * curRe - im[i+k+len/2] * curIm
                    val vIm = re[i+k+len/2] * curIm + im[i+k+len/2] * curRe
                    re[i+k] = uRe + vRe; im[i+k] = uIm + vIm
                    re[i+k+len/2] = uRe - vRe; im[i+k+len/2] = uIm - vIm
                    val newRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe; curRe = newRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(context: Context, effectId: Int) {
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(VibrationEffect.createPredefined(effectId))
        } else {
            v.vibrate(if (effectId == VibrationEffect.EFFECT_DOUBLE_CLICK) 120L else 50L)
        }
    }

    private fun parseJsonInt(json: String, key: String): Int? {
        val re = Regex(""""$key"\s*:\s*(\d+)""")
        return re.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun stop() = synchronized(lock) {
        g5Encoder?.stop(); g5Encoder = null
        decSess?.close(); decSess = null
    }
}
