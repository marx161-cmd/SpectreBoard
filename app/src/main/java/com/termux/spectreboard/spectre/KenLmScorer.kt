// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre

import android.content.Context
import com.termux.spectreboard.latin.NgramContext
import com.termux.spectreboard.latin.SuggestedWords.SuggestedWordInfo
import java.io.BufferedReader
import java.io.BufferedWriter

/**
 * Wraps the spectre_score subprocess (KenLM trie model) for candidate reranking.
 *
 * IPC protocol: one line in → one float out.
 *   stdin:  "prevWord1 prevWord2 candidate\n"   (chronological order, last = candidate)
 *   stdout: "-2.34\n"                            (log10 P(candidate | context))
 *
 * The process is kept alive across keystrokes. If it dies (OOM, crash), the next
 * scoring call silently restarts it — returning null for that call so the candidate
 * order falls back to the dictionary score unchanged.
 *
 * Called from the suggestion background thread — no ANR risk.
 */
object KenLmScorer {

    private const val LIB_NAME = "libspectre_score.so"
    private const val MODEL_FILENAME = "spectre.blm"

    // Rerank within this many dictionary score points.
    // Wider than SpatialScorer's band (20) because n-gram context is a strong signal.
    private const val SCORE_BAND = 50

    // Minimum log10-probability difference to swap two candidates.
    // Prevents thrashing on nearly-identical scores.
    private const val MIN_SCORE_DELTA = 0.05f

    private val lock = Any()
    private var binaryPath: String? = null
    private var modelPath: String? = null
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    fun isEmpty(): Boolean = synchronized(lock) { process?.isAlive != true }

    /** Start the scorer subprocess. Safe to call multiple times — no-ops if already running. */
    fun start(context: Context) = synchronized(lock) {
        binaryPath = "${context.applicationInfo.nativeLibraryDir}/$LIB_NAME"
        modelPath = "${context.filesDir.absolutePath}/$MODEL_FILENAME"
        if (process?.isAlive == true) return@synchronized
        tryStart()
    }

    fun stop() = synchronized(lock) {
        process?.destroy()
        process = null; writer = null; reader = null
    }

    private fun tryStart() {
        val bin = binaryPath ?: return
        val model = modelPath ?: return
        try {
            process = ProcessBuilder(bin, model).start()
            writer = process!!.outputStream.bufferedWriter()
            reader = process!!.inputStream.bufferedReader()
        } catch (_: Exception) {
            process = null; writer = null; reader = null
        }
    }

    /**
     * Score [candidate] given [contextWords] (oldest-first array from NgramContext).
     * Returns null if the process is unavailable — caller treats null as neutral (no rerank).
     */
    private fun score(contextWords: Array<String>, candidate: String): Float? =
        synchronized(lock) {
            if (process?.isAlive != true) tryStart()
            val w = writer ?: return@synchronized null
            val r = reader ?: return@synchronized null
            try {
                val query = if (contextWords.isEmpty()) candidate
                            else contextWords.joinToString(" ") + " " + candidate
                w.write(query)
                w.newLine()
                w.flush()
                r.readLine()?.toFloatOrNull()
            } catch (_: Exception) {
                process?.destroy()
                process = null; writer = null; reader = null
                null
            }
        }

    /**
     * Rerank [suggestions] in place using KenLM log-probabilities as a tiebreaker.
     *
     * Candidates whose dictionary scores differ by more than SCORE_BAND are left
     * in their original order — KenLM only resolves close calls.
     */
    fun rerank(suggestions: MutableList<SuggestedWordInfo>, ngramContext: NgramContext) {
        if (isEmpty()) return
        val context = ngramContext.extractPrevWordsContextArray()

        val scores = HashMap<SuggestedWordInfo, Float>(suggestions.size)
        for (s in suggestions) {
            val sc = score(context, s.mWord.toString().lowercase()) ?: continue
            scores[s] = sc
        }
        if (scores.isEmpty()) return

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
