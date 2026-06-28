// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre

import android.content.Context
import com.termux.spectreboard.latin.NgramContext
import com.termux.spectreboard.latin.SuggestedWords.SuggestedWordInfo

/**
 * KenLM n-gram scorer — in-process JNI, no subprocess.
 *
 * initNative() loads the binary model once. scoreAllNative() builds the LM
 * context state once and scores every candidate in a single JNI call,
 * eliminating the per-candidate pipe round-trips of the old subprocess design.
 */
object KenLmScorer {

    private const val MODEL_FILENAME = "spectre.blm"

    private const val SCORE_BAND = 50
    private const val MIN_SCORE_DELTA = 0.05f

    private val lock = Any()
    private var loaded = false

    // ---- JNI -------------------------------------------------------------------

    init { System.loadLibrary("spectre_score") }

    private external fun initNative(modelPath: String): Boolean
    private external fun scoreAllNative(context: String, candidates: Array<String>): FloatArray?
    private external fun closeNative()

    // ---- public API ------------------------------------------------------------

    fun isEmpty(): Boolean = synchronized(lock) { !loaded }

    fun start(context: Context) = synchronized(lock) {
        if (loaded) return@synchronized
        val modelPath = "${context.filesDir.absolutePath}/$MODEL_FILENAME"
        loaded = initNative(modelPath)
    }

    fun stop() = synchronized(lock) {
        if (!loaded) return@synchronized
        closeNative()
        loaded = false
    }

    fun rerank(suggestions: MutableList<SuggestedWordInfo>, ngramContext: NgramContext) {
        if (suggestions.size < 2) return
        if (isEmpty()) return

        val contextWords = ngramContext.extractPrevWordsContextArray()
        val contextStr = contextWords.joinToString(" ")
        val candidates = Array(suggestions.size) { suggestions[it].mWord.toString().lowercase() }

        val rawScores: FloatArray = synchronized(lock) {
            if (!loaded) return
            scoreAllNative(contextStr, candidates)
        } ?: return

        // Build identity map before sorting — indices shift once sortWith starts.
        val scores = HashMap<SuggestedWordInfo, Float>(suggestions.size)
        for (i in suggestions.indices) {
            if (i < rawScores.size) scores[suggestions[i]] = rawScores[i]
        }

        suggestions.sortWith { a, b ->
            val dictDiff = b.mScore - a.mScore
            if (kotlin.math.abs(dictDiff) > SCORE_BAND) return@sortWith dictDiff

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
