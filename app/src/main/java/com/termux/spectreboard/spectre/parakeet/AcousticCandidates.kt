// Ported from Outspoke (github.com/minburg/outspoke, GPLv3) inference/AcousticCandidates.kt.
package com.termux.spectreboard.spectre.parakeet

/**
 * One acoustic alternative for a decoded word: a candidate [word] together with its
 * length-normalised acoustic log-probability (log domain; higher is better, i.e. closer
 * to zero).
 *
 * Produced at decode time by [ParakeetEngine] while the encoder features are still in
 * memory — via top-K token swaps at each emission and/or a bounded local beam over the
 * word's frame range — and consumed later by the word-correction layer, which rescores
 * the alternatives with the ARPA language model and the surrounding sentence context.
 *
 * The acoustic log-prob is the grounded "what the mic heard" signal: it is the
 * model's own probability for the candidate token sequence, normalised by token count
 * so long words are not penalised.
 */
data class WordAlternative(val word: String, val acousticLogProb: Float)

/**
 * Bounded, thread-safe cache of per-word acoustic alternatives.
 *
 * Written by the inference worker during decode (one [put] per decoded word that
 * produced alternatives) and read by the IME threads when the user places the cursor on
 * a word ([get] is a single O(1) map lookup). The cache is process-local to
 * [InferenceRepository] (the long-lived bound inference service), so it persists across
 * keyboard hide/show cycles for the lifetime of the session and is cleared at the start
 * of each new recording session.
 *
 * Keys are lower-cased on both [put] and [get] so a cursor word's capitalisation
 * ("Whether") matches the model's casing ("whether") and vice versa. The candidate
 * [WordAlternative.word] text keeps the model's original casing.
 *
 * Bounded to [capacity] distinct words (oldest evicted first) so RAM stays flat:
 * 200 words × ≤ 5 alternatives × (String + Float) is a few tens of KB.
 */
class AcousticCandidateCache(capacity: Int = DEFAULT_CAPACITY) {

    private val limit = capacity.coerceAtLeast(1)

    // word (lowercase) → alternatives; insertion order, oldest first (eviction order)
    private val entries = LinkedHashMap<String, List<WordAlternative>>()

    /**
     * Records [alternatives] for [word]. Re-putting an existing word replaces its
     * alternatives (e.g. a local-beam result superseding an earlier token-swap result).
     * Evicts the oldest entries once [limit] distinct words are held.
     *
     * Called from the inference worker thread during decode.
     */
    fun put(word: String, alternatives: List<WordAlternative>) {
        if (word.isEmpty() || alternatives.isEmpty()) return
        val key = word.lowercase()
        synchronized(entries) {
            entries.remove(key)
            entries[key] = alternatives
            while (entries.size > limit) {
                entries.remove(entries.keys.first())
            }
        }
    }

    /**
     * Returns the cached alternatives for [word], or an empty list when absent (the
     * caller falls back to dictionary candidates).
     *
     * Safe to call from any thread (IME main / Default); a single lock-protected map
     * read with no contention worth worrying about.
     */
    fun get(word: String): List<WordAlternative> =
        synchronized(entries) { entries[word.lowercase()] ?: EMPTY }

    /** Drops all entries — called at the start of a new recording session. */
    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
        private val EMPTY: List<WordAlternative> = emptyList()
    }
}
