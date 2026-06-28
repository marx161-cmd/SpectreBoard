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
 * inferLogits() runs ONNX exactly once per rerank() call regardless of candidate count.
 * All candidates are scored by indexing into the shared logits array.
 */
object GruScorer {

    private const val ONNX_FILENAME = "gru_cifg.onnx"
    private const val VOCAB_ASSET = "gru_vocab.txt"
    private const val SEQ_LEN = 6
    private const val OOV_IDX = 1  // [UNK] token
    private const val CONTEXT_DELIMITER = "\u001F"

    private const val SCORE_BAND = 50
    private const val MIN_SCORE_DELTA = 0.05f

    private val lock = Any()
    private var session: OrtSession? = null
    @Volatile private var wordToIdx: Map<String, Int> = emptyMap()

    // One-entry logits cache — ngramContext doesn't change between keystrokes within the same
    // composing word, so skip inference when the context key is unchanged.
    private var cachedContextKey: String? = null
    private var cachedLogits: FloatArray? = null

    fun isEmpty(): Boolean = synchronized(lock) { session == null }

    fun start(context: Context) = synchronized(lock) {
        if (session != null) return@synchronized
        try {
            val lines = context.assets.open(VOCAB_ASSET).bufferedReader().readLines()
            wordToIdx = buildMap(lines.size) {
                lines.forEachIndexed { idx, w -> put(w.lowercase(), idx) }
            }
            val modelPath = "${context.filesDir.absolutePath}/$ONNX_FILENAME"
            val options = OrtSession.SessionOptions().apply { addXnnpack(mapOf()) }
            session = OrtEnvironment.getEnvironment().createSession(modelPath, options)
        } catch (_: Exception) {
            session = null
            wordToIdx = emptyMap()
        }
    }

    fun stop() = synchronized(lock) {
        session?.close()
        session = null
        wordToIdx = emptyMap()
        cachedContextKey = null
        cachedLogits = null
    }

    // Run ONNX once for the current context; returns all 30k logits.
    // Results are cached by context key — callers with the same previous-word context
    // (i.e., every keystroke within the same composing word) skip inference entirely.
    private fun inferLogits(contextWords: Array<String>): FloatArray? = synchronized(lock) {
        val key = contextWords.joinToString(CONTEXT_DELIMITER)
        cachedLogits?.let { if (key == cachedContextKey) return@synchronized it }

        val sess = session ?: return@synchronized null
        val env = OrtEnvironment.getEnvironment()
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
            cachedContextKey = key
            cachedLogits = logits
            logits
        } catch (_: Exception) {
            null
        }
    }

    fun rerank(suggestions: MutableList<SuggestedWordInfo>, ngramContext: NgramContext) {
        if (suggestions.size < 2) return
        val vocab = wordToIdx
        if (vocab.isEmpty()) return
        val context = ngramContext.extractPrevWordsContextArray()
        val logits = inferLogits(context) ?: return

        val scored = suggestions.mapIndexed { idx, s ->
            val tokenId = vocab[s.mWord.toString().lowercase()] ?: OOV_IDX
            Triple(idx, logits.getOrNull(tokenId), s)
        }

        val ordered = scored.sortedWith { (_, sa, a), (_, sb, b) ->
            val dictDiff = b.mScore - a.mScore
            if (kotlin.math.abs(dictDiff) > SCORE_BAND) return@sortedWith dictDiff
            when {
                sa == null && sb == null -> 0
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

        suggestions.clear()
        suggestions.addAll(ordered.map { (_, _, s) -> s })
    }
}
