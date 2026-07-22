package com.termux.spectreboard.spectre

import android.speech.RecognitionService

class SpectreRecognitionService : RecognitionService() {

    override fun onStartListening(intent: android.content.Intent?, callback: Callback?) {
        if (callback != null) callback.error(5 /* ERROR_CLIENT */)
    }

    override fun onCancel(callback: Callback?) {}

    override fun onStopListening(callback: Callback?) {}
}
