// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre.spatial

import android.content.Context
import com.termux.spectreboard.latin.SuggestedWords.SuggestedWordInfo
import com.termux.spectreboard.latin.common.ComposedData

object SpatialScorer {
    @Volatile private var model: Map<String, PerKeyGaussian> = emptyMap()

    // Dictionary score band within which spatial can break ties.
    // Candidates within this band of each other are re-ordered by spatial score.
    // Candidates outside the band keep their dictionary ordering unchanged.
    // Tune up to let spatial have more influence, down to make it more conservative.
    private const val SCORE_BAND = 20

    /** Load persisted model from SharedPreferences into memory. Call once at IME startup. */
    fun loadFromStore(context: Context) {
        val loaded = SpatialModelStore.load(context)
        if (loaded.isNotEmpty()) model = loaded
    }

    /** Called by SpatialModelWorker when a fresh model is built. Thread-safe via @Volatile. */
    internal fun updateModel(newModel: Map<String, PerKeyGaussian>) {
        model = newModel
    }

    fun isEmpty(): Boolean = model.isEmpty()

    /**
     * Re-order [suggestions] in place using the spatial model as a tiebreaker.
     *
     * Only candidates within SCORE_BAND of each other swap positions — candidates
     * with a clearly stronger dictionary score are never pushed down by spatial.
     * This keeps the model conservative until we have enough data and confidence.
     *
     * [composedData] carries the tap trajectory for the current word.
     */
    fun rerank(suggestions: MutableList<SuggestedWordInfo>, composedData: ComposedData) {
        if (model.isEmpty()) return
        val pointers = composedData.mInputPointers
        val tapCount = pointers.pointerSize
        if (tapCount == 0) return

        val xs = pointers.xCoordinates
        val ys = pointers.yCoordinates

        // Pre-compute spatial score for every candidate.
        val spatialScores = HashMap<SuggestedWordInfo, Double>(suggestions.size)
        for (candidate in suggestions) {
            spatialScores[candidate] = scoreTaps(candidate.mWord, xs, ys, tapCount)
        }

        suggestions.sortWith { a, b ->
            val dictDiff = b.mScore - a.mScore
            if (kotlin.math.abs(dictDiff) > SCORE_BAND) {
                // Dictionary score is decisive — don't touch the order.
                dictDiff
            } else {
                // Scores are close enough: let spatial break the tie.
                val spatialDiff = (spatialScores[b] ?: 0.0) - (spatialScores[a] ?: 0.0)
                when {
                    spatialDiff > 0.0 -> 1
                    spatialDiff < 0.0 -> -1
                    else -> dictDiff
                }
            }
        }
    }

    /**
     * Sum log-densities for the first min(word.length, tapCount) characters.
     * Characters not yet in the model (below minSamplesPerKey threshold) are skipped
     * rather than penalized — not enough data to be confident about those keys yet.
     */
    private fun scoreTaps(word: String, xs: IntArray, ys: IntArray, tapCount: Int): Double {
        var score = 0.0
        val n = minOf(word.length, tapCount)
        for (i in 0 until n) {
            val key = word[i].lowercaseChar().toString()
            val gaussian = model[key] ?: continue
            score += gaussian.logDensity(xs[i].toDouble(), ys[i].toDouble())
        }
        return score
    }
}
