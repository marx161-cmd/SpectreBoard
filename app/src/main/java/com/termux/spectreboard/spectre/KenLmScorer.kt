// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre

import android.content.Context
import com.termux.spectreboard.latin.NgramContext
import com.termux.spectreboard.latin.SuggestedWords.SuggestedWordInfo

/**
 * KenLM n-gram scorer — in-process JNI, no subprocess.
 *
 * initNative() loads the binary model once via LAZY mmap. scoreAllNative() builds
 * the LM context state once and scores every candidate in a single JNI call,
 * eliminating the per-candidate pipe round-trips of the old subprocess design.
 */
object KenLmScorer {

    private const val BEGINNING_OF_SENTENCE_TAG = "<S>"

    private const val MODEL_FILENAME = "spectre_q8.blm"

    private const val SCORE_BAND = 50
    private const val MIN_SCORE_DELTA = 0.05f

    private val lock = Any()
    private var loaded = false
    @Volatile private var nativeAvailable = false

    // ---- JNI -------------------------------------------------------------------

    init {
        try {
            System.loadLibrary("spectre_score")
            nativeAvailable = true
        } catch (_: Throwable) {
            nativeAvailable = false
        }
    }

    private external fun initNative(modelPath: String): Boolean
    private external fun scoreAllNative(context: String, candidates: Array<String>,
                                        isBeginOfSentence: Boolean): FloatArray?
    private external fun closeNative()

    // ---- public API ------------------------------------------------------------

    fun isEmpty(): Boolean = synchronized(lock) { !loaded }

    fun start(context: Context) = synchronized(lock) {
        if (loaded || !nativeAvailable) return@synchronized
        val modelPath = "${context.filesDir.absolutePath}/$MODEL_FILENAME"
        loaded = initNative(modelPath)
    }

    fun stop() = synchronized(lock) {
        if (!loaded) return@synchronized
        closeNative()
        loaded = false
    }

    /**
     * Score every candidate with the KenLM n-gram model.
     *
     * Context words are lowercased (to match lowercased candidate words and a
     * lowercased training corpus).  The <S> beginning-of-sentence tag is stripped
     * from the context — the JNI layer calls BeginSentenceWrite when the context
     * is BOS, so the literal tag would only become an <unk> lookup.
     *
     * @param candidates  parallel to the suggestions list, lowercased
     * @return log10 probabilities parallel to [candidates], or null on error
     */
    fun scoreAll(candidates: Array<String>, ngramContext: NgramContext): FloatArray? {
        if (isEmpty()) return null
        val rawContext = ngramContext.extractPrevWordsContextArray()
        val isBOS = rawContext.isNotEmpty() && rawContext[0] == BEGINNING_OF_SENTENCE_TAG

        val contextWords = rawContext
            .filter { it != BEGINNING_OF_SENTENCE_TAG }
            .map { it.lowercase() }
        val contextStr = contextWords.joinToString(" ")

        return synchronized(lock) {
            if (!loaded || !nativeAvailable) return@synchronized null
            scoreAllNative(contextStr, candidates, isBOS)
        }
    }

    /**
     * Re-order [suggestions] in place using KenLM scores as a tiebreaker within
     * each SCORE_BAND of dictionary scores.  Prefer [scoreAll] + combined rerank
     * when calling alongside other scorers to avoid redundant sort passes.
     */
    fun rerank(suggestions: MutableList<SuggestedWordInfo>, ngramContext: NgramContext) {
        if (suggestions.size < 2) return
        val candidates = Array(suggestions.size) { suggestions[it].mWord.toString().lowercase() }
        val rawScores = scoreAll(candidates, ngramContext) ?: return

        val scores = HashMap<SuggestedWordInfo, Float>(suggestions.size)
        for (i in suggestions.indices) {
            if (i < rawScores.size) scores[suggestions[i]] = rawScores[i]
        }

        suggestions.sortWith { a, b ->
            val dictDiff = Integer.compare(b.mScore, a.mScore)
            if (kotlin.math.abs(b.mScore - a.mScore) > SCORE_BAND) return@sortWith dictDiff

            val sa = scores[a]
            val sb = scores[b]
            when {
                sa == null && sb == null -> dictDiff
                sa == null -> 1
                sb == null -> -1
                else -> {
                    val delta = sb - sa
                    when {
                        delta >  MIN_SCORE_DELTA -> 1
                        delta < -MIN_SCORE_DELTA -> -1
                        else -> dictDiff
                    }
                }
            }
        }
    }
}
