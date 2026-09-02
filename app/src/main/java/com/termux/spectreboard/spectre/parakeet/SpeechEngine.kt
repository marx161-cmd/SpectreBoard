// Ported from Outspoke (github.com/minburg/outspoke, GPLv3) inference/SpeechEngine.kt,
// commit as cloned 2026-09-02. SpectreBoard (HeliBoard fork) is also GPLv3 -- this is a
// license-compatible wholesale port, not a rewrite.
package com.termux.spectreboard.spectre.parakeet

import java.io.File

/**
 * Abstraction over any on-device speech recognition engine.
 *
 * Implementing this interface allows the inference service and repository layers to remain
 * model-agnostic - swapping to another engine requires only a new class that implements this
 * contract, with zero changes to the consumers.
 */
interface SpeechEngine {

    /** `true` once [load] has completed successfully and before [close] is called. */
    val isLoaded: Boolean

    /**
     * Initialises all inference sessions from [modelDir].
     * Must be called on a background thread - loading takes 1-3 s on first run.
     *
     * @throws IllegalStateException if called while already loaded.
     * @throws Exception if any required model file is missing or corrupt.
     */
    fun load(modelDir: File)

    /**
     * Runs inference on [chunk] and returns the recognised text.
     * Must be called only after [load] has returned without error.
     */
    fun transcribe(chunk: AudioChunk): TranscriptResult

    /**
     * The active BCP-47 language tag used by post-processing (filler removal, number
     * normalisation, script-hallucination detection). Returns `"en"` when the engine is
     * running in auto-detect mode or does not support language selection.
     */
    val currentLanguage: String get() = "en"

    /**
     * Sets the active language for inference.
     * [tag] is a BCP-47 language tag (e.g. `"en"`, `"de"`) or `"auto"` for auto-detect.
     * Engines that do not support language selection may leave this as a no-op.
     */
    fun setLanguage(tag: String) { /* no-op by default */
    }

    /**
     * Restricts automatic language detection to a subset of BCP-47 tags.
     * No-op for engines that do not support language selection.
     */
    fun setLanguageConstraints(tags: List<String>) { /* no-op by default */
    }

    /**
     * Releases all native ONNX sessions and frees memory.
     * Safe to call even if [load] was never invoked.
     */
    fun close()
}
