// Ported from Outspoke (github.com/minburg/outspoke, GPLv3) inference/EngineState.kt.
package com.termux.spectreboard.spectre.parakeet

/** Loading / runtime state of the [ParakeetEngine] inside [InferenceService]. */
sealed class EngineState {
    /** Model files are absent; the user needs to stage them. */
    object Unloaded : EngineState()

    /** Engine is currently loading ONNX sessions into memory. */
    object Loading : EngineState()

    /** All sessions are ready; transcription can begin. */
    object Ready : EngineState()

    /** Loading failed. [message] is suitable for display. */
    data class Error(val message: String) : EngineState()
}
