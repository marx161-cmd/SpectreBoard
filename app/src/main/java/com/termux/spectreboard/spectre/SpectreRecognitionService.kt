package com.termux.spectreboard.spectre

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * System voice-input engine that streams the mic to comrade's sherpa-onnx
 * streaming dictation server (see [StreamDictation], ws://100.108.8.60:8770).
 *
 * Registered in the manifest with BIND_RECOGNITION_SERVICE, so it can be picked
 * as the device speech engine (Settings > System > Languages & input > Voice
 * input / "on-device recognition"). Then the keyboard mic, nav-bar mic, and any
 * app's voice input route here. Compute is entirely on comrade (sherpa Zipformer,
 * ~21x realtime); the phone only streams audio.
 *
 * Delivers live partials as words arrive; on the first server-side endpoint
 * (a natural pause) it delivers the final result and ends the turn — the standard
 * one-recognition-per-onStartListening RecognitionService contract.
 */
class SpectreRecognitionService : RecognitionService() {

    override fun onStartListening(intent: Intent?, callback: Callback?) {
        callback ?: return
        try { callback.readyForSpeech(Bundle()) } catch (_: Exception) {}
        StreamDictation.start(
            context = this,
            onAppend = { text -> deliver(callback, text, final = false) },
            onFinal = { text ->
                try { callback.endOfSpeech() } catch (_: Exception) {}
                deliver(callback, text, final = true)
                StreamDictation.stop {}   // one utterance per turn
            },
            onRevise = { _, _ -> },
            onStateChange = {},
        )
    }

    override fun onStopListening(callback: Callback?) {
        StreamDictation.stop {}
    }

    override fun onCancel(callback: Callback?) {
        StreamDictation.stop {}
    }

    private fun deliver(callback: Callback, text: String, final: Boolean) {
        if (text.isBlank() && final) { StreamDictation.stop {}; return }
        val b = Bundle()
        b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        b.putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1.0f))
        try {
            if (final) callback.results(b) else callback.partialResults(b)
        } catch (_: Exception) {}
    }
}
