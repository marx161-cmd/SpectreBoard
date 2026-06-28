package com.termux.spectreboard.spectre

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class WhisperG5WorkerClient(private val context: Context) {
    private var process: Process? = null
    private var writer: PrintWriter? = null
    private var stdoutQueue = LinkedBlockingQueue<String>()

    @Volatile
    var isReady = false
        private set

    fun start() {
        if (isReady && process?.isAlive == true) return

        stop()
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeLibDir, "libwhisper_g5_worker.so")
        val model = File(MODEL_PATH)
        if (!binary.canExecute()) {
            error("worker is not executable: ${binary.absolutePath}")
        }
        if (!model.canRead()) {
            error("G5 encoder model is not readable: ${model.absolutePath}")
        }

        Log.i(TAG, "starting ${binary.absolutePath}")
        val pb = ProcessBuilder(
            binary.absolutePath,
            "--model_path=${model.absolutePath}",
            "--dispatch_lib_dir=$nativeLibDir",
        )
        pb.environment()["LD_LIBRARY_PATH"] = "$nativeLibDir:/vendor/lib64"
        pb.redirectErrorStream(false)

        process = pb.start()
        val current = process ?: error("worker process was not created")

        Thread {
            BufferedReader(InputStreamReader(current.errorStream)).forEachLine { line ->
                Log.v(TAG_ERR, line)
            }
        }.also {
            it.isDaemon = true
            it.name = "WhisperG5Worker-err"
            it.start()
        }

        writer = PrintWriter(current.outputStream.bufferedWriter())
        stdoutQueue = LinkedBlockingQueue()
        Thread {
            BufferedReader(InputStreamReader(current.inputStream)).forEachLine { line ->
                stdoutQueue.offer(line)
            }
        }.also {
            it.isDaemon = true
            it.name = "WhisperG5Worker-out"
            it.start()
        }

        while (true) {
            val line = stdoutQueue.poll(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: error("worker did not become ready")
            Log.d(TAG, "startup: $line")
            if (line.contains("WHISPER_G5_READY")) {
                isReady = true
                return
            }
            if (line.contains("WHISPER_G5_ERROR")) {
                error(line)
            }
        }
    }

    @Synchronized
    fun run(mel: FloatBuffer): FloatArray {
        if (!isReady || process?.isAlive != true) {
            start()
        }

        val dir = File(context.cacheDir, "whisper-g5").also { it.mkdirs() }
        val stamp = System.nanoTime()
        val input = File(dir, "mel_$stamp.bin")
        val output = File(dir, "enc_$stamp.bin")
        try {
            writeMel(input, mel)
            val w = writer ?: error("worker stdin is closed")
            w.println("${input.absolutePath} ${output.absolutePath}")
            w.flush()

            val line = readStatusLine()
            if (!line.startsWith("WHISPER_G5_OK")) {
                error(line)
            }
            return readOutput(output)
        } finally {
            input.delete()
            output.delete()
        }
    }

    fun stop() {
        isReady = false
        runCatching {
            writer?.println("QUIT")
            writer?.flush()
        }
        runCatching { writer?.close() }
        process?.let { proc ->
            runCatching {
                if (!proc.waitFor(250, TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly()
                }
            }
        }
        process = null
        writer = null
        stdoutQueue.clear()
    }

    private fun readStatusLine(): String {
        val deadline = System.currentTimeMillis() + RUN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            val line = stdoutQueue.poll(remaining.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                ?: error("worker request timed out")
            if (line.startsWith("WHISPER_G5_OK") || line.startsWith("WHISPER_G5_ERROR")) {
                Log.i(TAG, line)
                return line
            }
            Log.d(TAG, "worker: $line")
        }
        error("worker request timed out")
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
        private const val TAG_ERR = "WhisperG5Worker-err"
        private const val MODEL_PATH = "/data/local/tmp/whisper_base_encoder_fp32_g5.tflite"
        private const val INPUT_FLOATS = 80 * 3000
        private const val OUTPUT_FLOATS = 1500 * 512
        private const val START_TIMEOUT_MS = 15_000L
        private const val RUN_TIMEOUT_MS = 15_000L
    }
}
