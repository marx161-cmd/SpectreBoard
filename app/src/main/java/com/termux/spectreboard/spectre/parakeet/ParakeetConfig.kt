// SpectreBoard on-device Parakeet dictation — hardcoded configuration (scope.md 2026-09-02).
package com.termux.spectreboard.spectre.parakeet

import android.content.Context
import java.io.File

/**
 * Hardcoded settings for the on-device Parakeet-TDT dictation pipeline.
 *
 * No settings UI, no DataStore — these are the values the user fixed in scope.md:
 *   VAD on, post-processing on, raw mic off, tap-to-toggle, English hardcoded.
 *
 * The model files are staged (pushed) to [modelDir] externally — no in-app download,
 * no readiness checking.
 */
object ParakeetConfig {

    /** Silero VAD (neural) enabled; falls back to energy VAD if the model can't load. */
    const val VAD_ENABLED = true

    /** Raw microphone (no platform echo cancellation / noise suppression) — off. */
    const val RAW_MIC = false

    /** Full transcript post-processing (fillers, stutters, phrase dedup, etc.) — on. */
    const val POSTPROCESSING_ENABLED = true

    /** Normalise number words ("twenty one" -> "21"). */
    const val FORMAT_NUMBERS_AS_DIGITS = true

    /** BCP-47 tag used for post-processing (filler removal, number normalisation). */
    const val LANGUAGE = "en"

    private const val MODEL_DIR_NAME = "models/parakeet-v3"

    private val REQUIRED_FILES = listOf(
        "encoder-model.int8.onnx",
        "decoder_joint-model.int8.onnx",
        "nemo128.onnx",
        "vocab.txt",
        "config.json",
    )

    /** Directory holding the Parakeet-TDT ONNX files inside the app's private storage. */
    fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR_NAME)

    /** True when every required model file is present and non-empty. */
    fun isModelReady(context: Context): Boolean {
        val dir = modelDir(context)
        return REQUIRED_FILES.all { name ->
            val f = File(dir, name)
            f.exists() && f.length() > 0
        }
    }
}
