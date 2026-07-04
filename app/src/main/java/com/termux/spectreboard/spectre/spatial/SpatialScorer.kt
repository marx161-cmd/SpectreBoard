// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre.spatial

import android.content.Context
import com.termux.spectreboard.latin.SuggestedWords.SuggestedWordInfo
import com.termux.spectreboard.latin.common.ComposedData

object SpatialScorer {
    // Keyed by Char, not String: scoreTaps() looks up one key per character per
    // candidate per keystroke, and Char lookups avoid a String allocation each time.
    // Multi-char key values from the builder are dropped at the conversion boundary —
    // the scoring path only ever looks up single characters, so they were unreachable.
    @Volatile private var model: Map<Char, PerKeyGaussian> = emptyMap()

    private const val LENGTH_MISMATCH_PENALTY = 2.0

    /** Load persisted model from SharedPreferences into memory. Call once at IME startup. */
    fun loadFromStore(context: Context) {
        val loaded = SpatialModelStore.load(context)
        if (loaded.isNotEmpty()) model = toCharKeys(loaded)
    }

    /** Called by SpatialModelWorker when a fresh model is built. Thread-safe via @Volatile. */
    internal fun updateModel(newModel: Map<String, PerKeyGaussian>) {
        model = toCharKeys(newModel)
    }

    private fun toCharKeys(m: Map<String, PerKeyGaussian>): Map<Char, PerKeyGaussian> {
        val out = HashMap<Char, PerKeyGaussian>(m.size)
        for ((k, v) in m) {
            if (k.length == 1) out[k[0]] = v
        }
        return out
    }

    fun isEmpty(): Boolean = model.isEmpty()

    /**
     * Score every candidate with the spatial Gaussian model.
     *
     * Returns log-densities keyed by candidate.  Characters not yet in the model
     * (below minSamplesPerKey threshold) are skipped rather than penalised.
     */
    fun scoreAll(candidates: List<SuggestedWordInfo>,
                 composedData: ComposedData): Map<SuggestedWordInfo, Double> {
        if (model.isEmpty()) return emptyMap()
        val pointers = composedData.mInputPointers
        val tapCount = pointers.pointerSize
        if (tapCount < 2) return emptyMap()

        val xs = pointers.xCoordinates
        val ys = pointers.yCoordinates

        val scores = HashMap<SuggestedWordInfo, Double>(candidates.size)
        for (candidate in candidates) {
            scores[candidate] = scoreTaps(candidate.mWord.toString(), xs, ys, tapCount)
        }
        return scores
    }

    /**
     * Sum log-densities for the first min(word.length, tapCount) characters.
     * Characters not yet in the model (below minSamplesPerKey threshold) are skipped
     * rather than penalised — not enough data to be confident about those keys yet.
     */
    private fun scoreTaps(word: String, xs: IntArray, ys: IntArray, tapCount: Int): Double {
        var score = 0.0
        val n = minOf(word.length, tapCount)
        val lc = word.lowercase()
        for (i in 0 until n) {
            val gaussian = model[lc[i]] ?: continue
            score += gaussian.logDensity(xs[i].toDouble(), ys[i].toDouble())
        }
        return score - kotlin.math.abs(word.length - tapCount) * LENGTH_MISMATCH_PENALTY
    }
}
