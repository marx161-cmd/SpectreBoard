// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.termux.spectreboard.latin.NgramContext
import com.termux.spectreboard.latin.SuggestedWords.SuggestedWordInfo

/**
 * GRU-CIFG next-word scorer for candidate reranking.
 *
 * Model: ONNX (opset 17), input "tokens" [1, 6] LongTensor, output "logits" [1, 30000] FloatTensor.
 * Vocab: gru_vocab.txt in assets — one word per line, line index == token ID.
 *
 * The ONNX model is loaded from filesDir (pushed separately via adb, not bundled in APK).
 * Called from the suggestion background thread — all methods are synchronized on [lock].
 */
object GruScorer {

    private const val ONNX_FILENAME = "gru_cifg.onnx"
    private const val VOCAB_ASSET = "gru_vocab.txt"
    private const val SEQ_LEN = 6
    private const val OOV_IDX = 1  // [UNK] token

    // Rerank within this many dictionary score points — same philosophy as KenLmScorer.
    private const val SCORE_BAND = 50
    private const val MIN_SCORE_DELTA = 0.05f

    private val lock = Any()
    private var session: OrtSession? = null
    private var wordToIdx: Map<String, Int> = emptyMap()

    fun isEmpty(): Boolean = synchronized(lock) { session == null }

    fun start(context: Context) = synchronized(lock) {
        if (session != null) return@synchronized
        try {
            val lines = context.assets.open(VOCAB_ASSET).bufferedReader().readLines()
            wordToIdx = buildMap(lines.size) {
                lines.forEachIndexed { idx, w -> put(w.lowercase(), idx) }
            }
            val modelPath = "${context.filesDir.absolutePath}/$ONNX_FILENAME"
            session = OrtEnvironment.getEnvironment().createSession(modelPath)
        } catch (_: Exception) {
            session = null
            wordToIdx = emptyMap()
        }
    }

    fun stop() = synchronized(lock) {
        session?.close()
        session = null
        wordToIdx = emptyMap()
    }

    private fun score(contextWords: Array<String>, candidate: String): Float? = synchronized(lock) {
        val sess = session ?: return@synchronized null
        val env = OrtEnvironment.getEnvironment()
        val candidateIdx = wordToIdx[candidate.lowercase()] ?: OOV_IDX

        // Build [1, SEQ_LEN] token array, left-padded with 0 when fewer than SEQ_LEN context words.
        val ctx = contextWords.takeLast(SEQ_LEN)
        val tokens = Array(1) { LongArray(SEQ_LEN) }
        val offset = SEQ_LEN - ctx.size
        for ((i, w) in ctx.withIndex()) {
            tokens[0][offset + i] = (wordToIdx[w.lowercase()] ?: OOV_IDX).toLong()
        }

        return@synchronized try {
            val inputTensor = OnnxTensor.createTensor(env, tokens)
            val output = sess.run(mapOf("tokens" to inputTensor))
            inputTensor.close()
            @Suppress("UNCHECKED_CAST")
            val logits = (output[0].value as Array<FloatArray>)[0]
            output.close()
            logits[candidateIdx]
        } catch (_: Exception) {
            null
        }
    }

    fun rerank(suggestions: MutableList<SuggestedWordInfo>, ngramContext: NgramContext) {
        if (isEmpty()) return
        val context = ngramContext.extractPrevWordsContextArray()

        val scores = HashMap<SuggestedWordInfo, Float>(suggestions.size)
        for (s in suggestions) {
            val sc = score(context, s.mWord.toString()) ?: continue
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
                        delta > MIN_SCORE_DELTA -> 1
                        delta < -MIN_SCORE_DELTA -> -1
                        else -> dictDiff
                    }
                }
            }
        }
    }
}
