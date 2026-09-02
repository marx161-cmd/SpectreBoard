// Ported from Outspoke (github.com/minburg/outspoke, GPLv3) inference/SpeechEngineFactory.kt.
package com.termux.spectreboard.spectre.parakeet

import com.termux.spectreboard.spectre.parakeet.model.ModelId

/**
 * Creates the appropriate [SpeechEngine] implementation for the given [ModelId].
 *
 * Only Parakeet is shipped in this build — the Whisper/Voxtral engines are not ported
 * (they are unused alternatives, commented out of ModelRegistry upstream).
 */
object SpeechEngineFactory {
    fun create(modelId: ModelId): SpeechEngine = when (modelId) {
        ModelId.PARAKEET_V3 -> ParakeetEngine()
        else -> error("Model $modelId is not available in this build")
    }
}
