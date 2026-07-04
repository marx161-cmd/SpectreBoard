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

    private const val BEGINNING_OF_SENTENCE_TAG = "<S>"

    private const val ONNX_FILENAME = "gru_cifg.onnx"
    private const val VOCAB_ASSET = "gru_vocab.txt"
    private const val SEQ_LEN = 6
    private const val CONTEXT_DELIMITER = "\u001F"

    private const val SCORE_BAND = 50
    private const val MIN_SCORE_DELTA = 0.05f

    private val lock = Any()
    @Volatile private var session: OrtSession? = null
    @Volatile private var wordToIdx: Map<String, Int> = emptyMap()
    @Volatile private var loading = false

    // One-entry logits cache — ngramContext doesn't change between keystrokes within the same
    // composing word, so skip inference when the context key is unchanged.
    private var cachedContextKey: String? = null
    private var cachedLogits: FloatArray? = null

    /**
     * Lock-free check — returns true while the model is loading or unloaded,
     * so the suggestion path never blocks on the long-running start() call.
     */
    fun isEmpty(): Boolean = session == null

    /**
     * Build vocab and ORT session asynchronously (called from a background
     * thread in LatinIME.loadSettings).  The slow work (vocab load, session
     * construction) happens outside [lock] so the suggestion thread can pass
     * through isEmpty() without blocking while the model loads.
     */
    fun start(context: Context) {
        if (session != null || loading) return
        loading = true
        try {
            val lines = context.assets.open(VOCAB_ASSET).bufferedReader().readLines()
            wordToIdx = buildMap(lines.size) {
                lines.forEachIndexed { idx, w -> put(w.lowercase(), idx) }
            }
            val modelPath = "${context.filesDir.absolutePath}/$ONNX_FILENAME"
            val options = OrtSession.SessionOptions().apply { addXnnpack(mapOf()) }
            val newSession = OrtEnvironment.getEnvironment().createSession(modelPath, options)
            synchronized(lock) {
                session = newSession
            }
        } catch (_: Exception) {
            wordToIdx = emptyMap()
        } finally {
            loading = false
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
            tokens[0][offset + i] = (wordToIdx[w.lowercase()] ?: 1).toLong()
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

    /**
     * Score every candidate with the GRU-CIFG neural LM.
     *
     * Out-of-vocabulary candidates are mapped to null (not the [UNK] token score).
     * The combined comparator treats null as -∞, so OOV candidates lose to any
     * in-vocab candidate within their dict band.  This avoids spuriously promoting
     * OOV words with a high-frequency [UNK] logit, but note that correct-in-context
     * rare words outside the 30k vocab will also demote — KenLM's opinion on them
     * only matters when both candidates are OOV.
     *
     * The <S> beginning-of-sentence tag is stripped from the context — the model
     * is left-padded with zeros (index 0) for short contexts.
     */
    fun scoreAll(candidates: List<SuggestedWordInfo>,
                 ngramContext: NgramContext): Map<SuggestedWordInfo, Float?> {
        val vocab = wordToIdx
        if (vocab.isEmpty()) return emptyMap()

        val rawContext = ngramContext.extractPrevWordsContextArray()
        val context = rawContext.filter { it != BEGINNING_OF_SENTENCE_TAG }.toTypedArray()
        val logits = inferLogits(context) ?: return emptyMap()

        val scores = HashMap<SuggestedWordInfo, Float?>(candidates.size)
        for (candidate in candidates) {
            val tokenId = vocab[candidate.mWord.toString().lowercase()]
            scores[candidate] = if (tokenId != null) logits.getOrNull(tokenId) else null
        }
        return scores
    }

    /**
     * Re-order [suggestions] in place using GRU logits as a tiebreaker within
     * each SCORE_BAND of dictionary scores.  Prefer [scoreAll] + combined rerank
     * when calling alongside other scorers to avoid redundant sort passes.
     */
    fun rerank(suggestions: MutableList<SuggestedWordInfo>, ngramContext: NgramContext) {
        if (suggestions.size < 2) return
        val scores = scoreAll(suggestions, ngramContext)
        if (scores.isEmpty()) return

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
