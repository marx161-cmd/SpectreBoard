package com.termux.spectreboard.spectre

import android.content.Context
import android.util.Log
import java.io.File

class LiteRtEncoder(private val context: Context) {
    private var handle: Long = 0L

    fun init(modelFile: File): Boolean {
        if (handle != 0L) return true
        // Look for the dispatch .so in the app's files dir (placed there manually via root/adb).
        // The APK's nativeLibDir is NOT used because the Bazel-built dispatch .so is
        // firmware-incompatible and crashes the process on load.
        val filesDir = File(context.applicationInfo.dataDir, "files")
        val dispatchSo = File(filesDir, "libLiteRtDispatch_GoogleTensor.so")
        if (!dispatchSo.exists()) {
            Log.i(TAG, "LiteRtEncoder: dispatch .so not in files dir, skipping G5 path")
            return false
        }
        handle = nativeInit(modelFile.absolutePath, filesDir.absolutePath)
        if (handle == 0L) {
            Log.w(TAG, "LiteRtEncoder: init failed (model=${modelFile.name})")
            return false
        }
        Log.i(TAG, "LiteRtEncoder: ready (model=${modelFile.name}, dispatchDir=${filesDir.absolutePath})")
        return true
    }

    // input: flat float array [1 * 80 * 3000] = 240000 floats (mel spectrogram)
    // returns: flat float array [1 * 1500 * 384] = 576000 floats, or null on error
    fun run(input: FloatArray): FloatArray? {
        if (handle == 0L) return null
        return nativeRun(handle, input)
    }

    fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    private external fun nativeInit(modelPath: String, dispatchLibDir: String): Long
    private external fun nativeRun(handle: Long, input: FloatArray): FloatArray?
    private external fun nativeClose(handle: Long)

    companion object {
        private const val TAG = "LiteRtEncoder"

        init {
            System.loadLibrary("litert_encoder_jni")
        }
    }
}
