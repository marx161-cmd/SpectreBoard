package com.termux.spectreboard.spectre

/**
 * Bridge from Parakeet's on-device dictation acoustic-alternative plumbing
 * ([com.termux.spectreboard.spectre.parakeet.AcousticCandidateCache],
 * [com.termux.spectreboard.spectre.parakeet.WordSuggestionProvider]) into SpectreBoard's
 * own native suggestion strip ([com.termux.spectreboard.latin.Suggest]).
 *
 * Kept in `spectre/`, not `spectre/parakeet/`, so `latin/Suggest.kt` — which already
 * imports sibling `spectre.*` scorers ([GruScorer], [KenLmScorer], [PhoneticExpander]) —
 * never needs to import anything from the `parakeet` package. [lookup] is installed by
 * [com.termux.spectreboard.spectre.parakeet.ParakeetDictationHost], which is the only
 * writer.
 *
 * [Suggest] reads this from `InputLogicHandler`'s background thread (see
 * `InputLogic.getSuggestedWords`), while [ParakeetDictationHost] writes it from the main
 * thread — both fields are `@Volatile` so no separate locking is needed for these simple
 * single-writer/many-reader reads.
 */
object AcousticSuggestions {

    /**
     * Word + left-context (1-2 preceding words, lowercased) -> ranked acoustic correction
     * candidates (dictionary-filtered, LM-rescored — see
     * `WordCorrector.correctAcousticOnly`). Null when no dictation session has armed this
     * yet (e.g. app just started, or the user has only ever typed).
     */
    @Volatile
    var lookup: ((word: String, leftContext: List<String>) -> List<String>)? = null

    /**
     * True only while it makes sense to surface acoustic candidates for the word under the
     * cursor: a Parakeet dictation session wrote into the current field this session, and
     * the English corrector has finished loading. Armed/disarmed by
     * [com.termux.spectreboard.spectre.parakeet.ParakeetDictationHost] around each
     * recording session — see that class for the exact lifecycle.
     */
    @Volatile
    var armed: Boolean = false

    val isActive: Boolean get() = armed && lookup != null

    /**
     * Returns ranked acoustic candidates for [word] in [leftContext], or an empty list when
     * inactive, [word] is too short to have been scored (see
     * `InferenceRepository.captureAcousticCandidates`'s own 2-character floor), or the word
     * has no acoustic evidence in the current session's cache.
     */
    fun candidatesFor(word: String, leftContext: List<String> = emptyList()): List<String> {
        if (!isActive || word.length < 2) return emptyList()
        return lookup?.invoke(word, leftContext) ?: emptyList()
    }
}
