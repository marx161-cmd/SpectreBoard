// Ported from Outspoke (github.com/minburg/outspoke, GPLv3) ime/correction/WordCorrector.kt.
// correctAcousticOnly() added for SpectreBoard's native suggestion strip - see AcousticSuggestions.kt.
package com.termux.spectreboard.spectre.parakeet.correction

import android.util.Log
import com.termux.spectreboard.spectre.parakeet.WordAlternative
import java.io.File

private const val TAG = "WordCorrector"

/**
 * Orchestrates the full correction pipeline for a single ASR-emitted word.
 *
 * **Primary source — acoustic alternatives.** [correct] receives [acoustic]: the ASR
 * model's own word-level alternatives captured at decode time (top-K token swaps +
 * bounded local beam, see [com.termux.spectreboard.spectre.parakeet.InferenceRepository]). These are
 * acoustically grounded — they are what the model actually *heard* — and each carries a
 * length-normalised acoustic log-prob (natural log). They are rescored in the log domain,
 * the standard ASR n-best re-ranking combination:
 *
 * ```
 * score(candidate) = acousticLogProb + λ · ln(10) · lmLog10(candidate | context)
 * ```
 *
 * The acoustic term dominates (it is the grounded signal); the LM (weight λ = 0.5) breaks
 * ties and pulls the result toward the word that fits the surrounding sentence. The LM
 * score is used as the **raw log10** probability (no clamping — the old `(lp + 5) / 5`
 * compression collapsed the dynamic range and let the frequency term win) and converted
 * to the natural-log scale of the acoustic term via ln(10).
 *
 * **Lexicon filter.** The TDT decoder is lexicon-unconstrained, so its beam can emit
 * acoustically plausible strings that are not words in the language. Acoustic
 * alternatives are therefore filtered to genuine dictionary words (case-insensitive,
 * deduplicated case-insensitively with the highest-scoring casing kept) before
 * rescoring; when nothing survives, the dictionary fallback below applies.
 *
 * **Fallback — dictionary.** When there is no usable acoustic evidence (none, or all
 * filtered as non-words — e.g. the word was decoded before capture was active, evicted,
 * or manually typed) the candidates come from [CandidateGenerator] (phonetic + edit
 * distance) with a fixed acoustic prior [DICT_ACOUSTIC_PRIOR] so they can still surface
 * but are systematically ranked below genuinely acoustic candidates.
 *
 * Loaded from files on internal storage (downloaded by [SuggestionFileDownloader]).
 */
class WordCorrector(
    dictFile: File,
    lmFile: File,
    private val language: String,
) {

    companion object {
        const val TOP_K = 5
        /** LM weight λ — the acoustic term dominates; the LM breaks ties / fits context. */
        const val LM_WEIGHT = 0.5f
        /**
         * Acoustic prior for dictionary-fallback candidates (no acoustic evidence):
         * "plausible but not acoustically confirmed".
         */
        const val DICT_ACOUSTIC_PRIOR = -2.0f
        /** Converts the LM's log10 scores to the natural-log scale of the acoustic scores. */
        private const val LN10 = 2.302585093f
    }

    private val candidateGen = CandidateGenerator(dictFile, language)
    private val lm = ArpaLanguageModel(lmFile, language)

    @Volatile
    var isReady = false
        private set

    /**
     * Loads both the dictionary and the language model from internal storage.
     * Must be called from a background thread; blocks until both are loaded.
     */
    fun load() {
        candidateGen.load()
        lm.load()
        isReady = candidateGen.isReady && lm.isReady
        if (isReady) Log.d(TAG, "[$language] WordCorrector ready")
    }

    /**
     * Returns up to [TOP_K] correction candidates for [word] in [leftContext], ranked by
     * `acousticLogProb + λ · ln(10) · lmLog10` (see class docs).
     *
     * @param acoustic the ASR model's own alternatives for [word] (empty → dictionary
     *   fallback with the fixed acoustic prior).
     */
    fun correct(
        word: String,
        leftContext: List<String>,
        acoustic: List<WordAlternative> = emptyList(),
    ): List<String> {
        if (!isReady || word.length < 2) return emptyList()
        return try {
            runCorrection(word, leftContext, acoustic)
        } catch (e: Exception) {
            Log.e(TAG, "correct() failed unexpectedly", e)
            emptyList()
        }
    }

    /**
     * Synchronous, fast-path-only variant of [correct]: returns up to [TOP_K] acoustic
     * candidates for [word], ranked the same way as [correct] (dictionary-filtered +
     * LM-rescored), but returns an empty list instead of falling through to
     * [CandidateGenerator.getCandidates] when [acoustic] yields nothing usable.
     *
     * [CandidateGenerator.getCandidates] does a full linear scan over the whole loaded
     * dictionary (tens of thousands of entries) with an [EditDistance] computation per
     * candidate — tens of milliseconds, allocation-heavy. [correct] can afford that because
     * its only caller ([com.termux.spectreboard.spectre.parakeet.WordSuggestionProvider.getSuggestions])
     * runs on a background coroutine. This method exists for a caller that must stay
     * synchronous and cheap — [com.termux.spectreboard.latin.Suggest] builds its
     * suggestion strip on `InputLogicHandler`'s worker thread inside the
     * `GET_SUGGESTED_WORDS_TIMEOUT` budget, so entering the dictionary-scan path there
     * would risk blowing that budget. The acoustic-only branch below is pure hash-map
     * lookups (isKnownWord + LM rescore), sub-millisecond regardless of dictionary size.
     */
    fun correctAcousticOnly(
        word: String,
        leftContext: List<String>,
        acoustic: List<WordAlternative>,
    ): List<String> {
        if (!isReady || word.length < 2 || acoustic.isEmpty()) return emptyList()
        return try {
            val query = word.lowercase()
            val byLower = HashMap<String, Pair<String, Float>>()
            for (alt in acoustic) {
                val w = alt.word
                val lower = w.lowercase()
                if (lower == query) continue            // never suggest the word itself
                if (!candidateGen.isKnownWord(w)) continue
                val score = rescore(w, alt.acousticLogProb, leftContext)
                val existing = byLower[lower]
                if (existing == null || score > existing.second) byLower[lower] = w to score
            }
            byLower.values
                .sortedByDescending { it.second }
                .take(TOP_K)
                .map { it.first }
        } catch (e: Exception) {
            Log.e(TAG, "correctAcousticOnly() failed unexpectedly", e)
            emptyList()
        }
    }

    private fun runCorrection(
        word: String,
        leftContext: List<String>,
        acoustic: List<WordAlternative>,
    ): List<String> {
        val query = word.lowercase()

        // The ASR model's alternatives are lexicon-unconstrained — the beam can emit
        // acoustically plausible strings that are not words ("Sppielzeug"). Keep only
        // genuine dictionary words, deduplicated case-insensitively (highest-scoring
        // casing wins). If nothing survives, treat it as no usable acoustic evidence
        // and fall back to dictionary candidates below.
        val acousticScored: List<Pair<String, Float>> = if (acoustic.isEmpty()) emptyList()
        else {
            val byLower = HashMap<String, Pair<String, Float>>()
            for (alt in acoustic) {
                val w = alt.word
                val lower = w.lowercase()
                if (lower == query) continue            // never suggest the word itself
                if (!candidateGen.isKnownWord(w)) continue
                val score = rescore(w, alt.acousticLogProb, leftContext)
                val existing = byLower[lower]
                if (existing == null || score > existing.second) byLower[lower] = w to score
            }
            byLower.values.toList()
        }

        // Candidates as (word → combined log-domain score), in source priority order.
        val scored: List<Pair<String, Float>> = if (acousticScored.isNotEmpty()) {
            acousticScored
        } else {
            candidateGen.getCandidates(query)
                .filter { it != query }
                .map { it to rescore(it, DICT_ACOUSTIC_PRIOR, leftContext) }
        }
        if (scored.isEmpty()) return emptyList()

        return scored
            .sortedByDescending { it.second }
            .take(TOP_K)
            .map { it.first }
            .also { Log.d(TAG, "correct(ctx=${leftContext.size} words, acoustic=${acousticScored.size}) → ${it.size} candidate(s)") }
    }

    /**
     * The log-domain combination: acoustic (natural log, length-normalised) + λ × LM
     * (log10 → natural log). No clamping — the full dynamic range is preserved so the
     * acoustic term dominates and the LM differentiates.
     */
    private fun rescore(candidate: String, acousticLogProb: Float, leftContext: List<String>): Float =
        acousticLogProb + LM_WEIGHT * (lm.scoreInContext(candidate, leftContext) * LN10)
}
