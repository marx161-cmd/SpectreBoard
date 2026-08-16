package com.termux.spectreboard.spectre

import android.content.Context
import android.util.Log
import com.termux.spectreboard.npud.NpudClient
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class WhisperG5WorkerClient(private val context: Context) {
    @Volatile
    private var hasProbed = false

    val isReady: Boolean
        get() = hasProbed && NpudClient.isAvailable()

    /**
     * Kept for callers that probe readiness. npud owns the worker lifecycle,
     * so this only checks the daemon is reachable rather than spawning a
     * second whisper worker to compete with it.
     */
    fun start() {
        hasProbed = true
        if (!NpudClient.isAvailable()) {
            Log.w(TAG, "npud not reachable at ${NpudClient.SOCKET_PATH}; whisper will fail")
        }
    }

    /**
     * Encode one mel window through npud instead of a worker this IME owns.
     *
     * npud keeps the whisper worker warm across calls and shares it with the
     * other com.termux.* apps, so dictation no longer pays a model load on
     * first use and the NPU isn't holding three near-identical workers. The
     * request line and the temp-file handoff are unchanged — npud passes the
     * payload through verbatim, and the worker reads/writes these paths
     * directly because every com.termux.* app shares Termux's UID.
     *
     * Deliberately no fallback to a locally spawned worker: that would hide a
     * broken npud path rather than surface it.
     */
    fun run(mel: FloatBuffer): FloatArray {
        val dir = File(context.cacheDir, "whisper-g5").also { it.mkdirs() }
        val stamp = System.nanoTime()
        val input = File(dir, "mel_$stamp.bin")
        val output = File(dir, "enc_$stamp.bin")
        try {
            writeMel(input, mel)
            NpudClient.generate(
                NpudClient.KIND_ASR,
                NPUD_MODEL,
                "${input.absolutePath} ${output.absolutePath}",
            )
            return readOutput(output)
        } finally {
            input.delete()
            output.delete()
        }
    }

    /** No-op beyond local state: the worker outlives this IME by design. */
    fun stop() {
        hasProbed = false
    }

    private fun writeMel(file: File, mel: FloatBuffer) {
        val src = mel.duplicate()
        src.rewind()
        if (src.remaining() != INPUT_FLOATS) {
            error("bad mel size: ${src.remaining()} floats, expected $INPUT_FLOATS")
        }
        val bytes = ByteBuffer.allocate(INPUT_FLOATS * java.lang.Float.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        while (src.hasRemaining()) bytes.putFloat(src.get())
        file.writeBytes(bytes.array())
    }

    private fun readOutput(file: File): FloatArray {
        val bytes = file.readBytes()
        if (bytes.size != OUTPUT_FLOATS * java.lang.Float.BYTES) {
            error("bad encoder output size: ${bytes.size} bytes")
        }
        val floats = FloatArray(OUTPUT_FLOATS)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        if (floats.any { !it.isFinite() }) {
            error("G5 encoder returned non-finite output")
        }
        return floats
    }

    companion object {
        private const val TAG = "WhisperG5Worker"
        /** Model name as npud exposes it: the stem under <model-dir>/asr/. */
        const val NPUD_MODEL = "whisper_base_encoder_fp32_g5"
        private const val INPUT_FLOATS = 80 * 3000
        private const val OUTPUT_FLOATS = 1500 * 512
    }
}
