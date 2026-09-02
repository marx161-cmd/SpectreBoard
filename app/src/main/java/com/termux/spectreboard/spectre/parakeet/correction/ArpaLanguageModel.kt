// Ported from Outspoke (github.com/minburg/outspoke, GPLv3) ime/correction/ArpaLanguageModel.kt.
package com.termux.spectreboard.spectre.parakeet.correction

import android.util.Log
import com.termux.spectreboard.spectre.parakeet.correction.ArpaLanguageModel.Companion.EOS
import com.termux.spectreboard.spectre.parakeet.correction.ArpaLanguageModel.Companion.SOS
import java.io.File

private const val TAG = "ArpaLanguageModel"

/**
 * Pure-Kotlin reader and scorer for ARPA-format n-gram language models (up to trigrams).
 *
 * Reads from a [File] on internal storage (downloaded by [SuggestionFileDownloader]).
 *
 * [scoreInContext] returns the **raw log10** probability of [candidate] given up to two
 * words of left context, with standard ARPA backoff:
 *   - trigram `w1 w2 candidate` when both context words are present,
 *   - else bigram `w2 candidate` (plus the bigram's backoff weight when the trigram is
 *     missing but the bigram context is known),
 *   - else unigram (plus accumulated backoff weights).
 * Unknown words receive a small constant penalty so they remain comparable. Callers must
 * NOT rescale the result (the old `(lp + 5) / 5` clamp is gone — it collapsed the dynamic
 * range and made the LM barely differentiate candidates).
 */
class ArpaLanguageModel(
    private val lmFile: File,
    private val language: String,   // "de" or "en"
) {

    // Unigram: word → (log10_prob, log10_backoff)
    private val unigrams = HashMap<String, FloatArray>(50_000)

    // Bigram: "w1 w2" → (log10_prob, log10_backoff)
    private val bigrams = HashMap<String, FloatArray>(300_000)

    // Trigram: "w1 w2 w3" → (log10_prob, log10_backoff). Empty for bigram-only packs.
    private val trigrams = HashMap<String, FloatArray>()

    @Volatile
    var isReady = false
        private set

    companion object {
        private const val UNK_LOG_PROB = -4f
        private const val UNK_BACKOFF = 0f
        private const val SOS = "<s>"
        private const val EOS = "</s>"
    }

    /**
     * Loads the ARPA file from internal storage.  Must be called from a background thread.
     * Safe to call multiple times; subsequent calls are no-ops once loaded.
     */
    fun load() {
        if (isReady) return
        if (!lmFile.exists()) {
            Log.w(TAG, "[$language] LM file not found: ${lmFile.absolutePath}")
            return
        }
        try {
            // 0=header, 1=unigrams, 2=bigrams, 3=trigrams, 4=done.
            var section = 0
            lmFile.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.forEachLine { rawLine ->
                    val line = rawLine.trim()
                    when {
                        line.isEmpty() || line.startsWith("\\data\\") -> Unit
                        line.startsWith("ngram ") -> Unit
                        line == "\\1-grams:" -> section = 1
                        line == "\\2-grams:" -> section = 2
                        line == "\\3-grams:" -> section = 3
                        line.startsWith("\\") -> section = 4
                        section == 1 -> parseUnigram(line)
                        section == 2 -> parseBigram(line)
                        section == 3 -> parseTrigram(line)
                    }
                }
            }
            isReady = true
            Log.d(
                TAG,
                "[$language] Loaded ${unigrams.size} unigrams, ${bigrams.size} bigrams, ${trigrams.size} trigrams"
            )
        } catch (e: Exception) {
            Log.e(TAG, "[$language] Failed to load ${lmFile.absolutePath}", e)
        }
    }

    private fun parseUnigram(line: String) {
        val parts = line.split('\t')
        if (parts.size < 2) return
        val lp = parts[0].toFloatOrNull() ?: return
        val word = parts[1]
        val bow = if (parts.size >= 3) parts[2].toFloatOrNull() ?: 0f else 0f
        unigrams[word] = floatArrayOf(lp, bow)
    }

    private fun parseBigram(line: String) {
        val parts = line.split('\t')
        if (parts.size < 2) return
        val lp = parts[0].toFloatOrNull() ?: return
        val key = parts[1]  // "w1 w2" stored as single tab-field
        val bow = if (parts.size >= 3) parts[2].toFloatOrNull() ?: 0f else 0f
        bigrams[key] = floatArrayOf(lp, bow)
    }

    private fun parseTrigram(line: String) {
        val parts = line.split('\t')
        if (parts.size < 2) return
        val lp = parts[0].toFloatOrNull() ?: return
        val key = parts[1]  // "w1 w2 w3" stored as single tab-field
        val bow = if (parts.size >= 3) parts[2].toFloatOrNull() ?: 0f else 0f
        trigrams[key] = floatArrayOf(lp, bow)
    }

    /**
     * Scores [candidate] in the context of [leftContext] (up to the 2 words preceding the
     * candidate in the text; the last element is the immediately preceding word).
     *
     * Returns the **raw log10 probability**; higher is better. Unknown words receive a
     * small constant penalty so they are still comparable.
     */
    fun scoreInContext(candidate: String, leftContext: List<String>): Float {
        if (!isReady) return 0f
        return try {
            val w2 = leftContext.lastOrNull()
            val w1 = if (leftContext.size >= 2) leftContext[leftContext.size - 2] else null

            // Trigram path: P(c | w1 w2), backing off to the bigram when the trigram is
            // unobserved (the bigram's backoff weight is the trigram-level discount).
            if (w1 != null && w2 != null) {
                val tri = trigrams["$w1 $w2 $candidate"]
                if (tri != null) return tri[0]
                val bow2 = bigrams["$w1 $w2"]?.get(1) ?: UNK_BACKOFF
                val bi = bigrams["$w2 $candidate"]
                if (bi != null) return bow2 + bi[0]
                val bow1 = unigrams[w2]?.get(1) ?: UNK_BACKOFF
                val uni = unigrams[candidate]
                return bow2 + bow1 + (uni?.get(0) ?: UNK_LOG_PROB)
            }

            // Bigram path: P(c | w2), backing off to the unigram.
            if (w2 != null) {
                val bi = bigrams["$w2 $candidate"]
                if (bi != null) return bi[0]
                val bow1 = unigrams[w2]?.get(1) ?: UNK_BACKOFF
                val uni = unigrams[candidate]
                return bow1 + (uni?.get(0) ?: UNK_LOG_PROB)
            }

            // Unigram: P(c).
            unigrams[candidate]?.get(0) ?: UNK_LOG_PROB
        } catch (e: Exception) {
            Log.e(TAG, "scoreInContext failed", e)
            UNK_LOG_PROB
        }
    }

    /**
     * Returns the sum of n-gram log10 probabilities for the token sequence
     * [SOS] + [words] + [EOS], normalised by sequence length.
     */
    fun scoreSequence(words: List<String>): Float {
        if (!isReady || words.isEmpty()) return UNK_LOG_PROB
        return try {
            var total = 0f
            val tokens = listOf(SOS) + words + listOf(EOS)
            for (i in 1 until tokens.size) {
                total += scoreInContext(tokens[i], tokens.subList(0, i))
            }
            total / words.size
        } catch (e: Exception) {
            Log.e(TAG, "scoreSequence failed", e)
            UNK_LOG_PROB
        }
    }
}
