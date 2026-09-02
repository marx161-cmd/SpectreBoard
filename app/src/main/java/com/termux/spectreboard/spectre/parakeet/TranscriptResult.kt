// Ported from Outspoke (github.com/minburg/outspoke, GPLv3) inference/TranscriptResult.kt,
// commit as cloned 2026-09-02. SpectreBoard (HeliBoard fork) is also GPLv3 -- this is a
// license-compatible wholesale port, not a rewrite.
package com.termux.spectreboard.spectre.parakeet

/** Represents the outcome of a single transcription attempt. */
sealed class TranscriptResult {

    /**
     * In-progress transcript from streaming inference.
     * The keyboard should show [text] as composing (underlined) text.
     *
     * [confidence] is the engine's per-token geometric-mean probability in [0.0, 1.0]
     * (1.0 when the engine does not surface per-token log-probs).
     */
    data class Partial(val text: String, val confidence: Float = 1.0f) : TranscriptResult()

    /**
     * Final, confirmed transcript for a completed utterance.
     * The keyboard should commit [text] to the active input field.
     *
     * [isUtteranceBoundary] is true when this Final was emitted mid-session by the VAD
     * silence-boundary handler. The recording session is still active and the keyboard
     * must NOT tear down capture state - only commit the text and continue listening.
     * When false (the default), this is a true session-ending Final.
     */
    data class Final(
        val text: String,
        val isUtteranceBoundary: Boolean = false,
        val confidence: Float = 1.0f,
    ) : TranscriptResult()

    /** Inference failed. [cause] carries the underlying exception for logging/display. */
    data class Failure(val cause: Throwable) : TranscriptResult()

    /**
     * The rolling audio window was trimmed to prevent attention drift.
     * The text injector must reset its committed-word tracking when it receives this so
     * that suffix-overlap alignment does not fail for every stride after the trim.
     *
     * [stableWords] carries the confirmed-stable leading words from the partial that
     * triggered the trim. Defaults to an empty list for force-trims and silence-trims.
     */
    data class WindowTrimmed(val stableWords: List<String> = emptyList()) : TranscriptResult()

    /**
     * The model saw audio but could not resolve a word - typically a short utterance
     * that fails the decode confidence gate. No text is committed.
     */
    object NoSpeech : TranscriptResult()
}
