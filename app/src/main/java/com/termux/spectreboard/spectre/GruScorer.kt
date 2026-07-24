// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.termux.spectreboard.latin.NgramContext
import com.termux.spectreboard.latin.SuggestedWords.SuggestedWordInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GRU-CIFG next-word scorer for candidate reranking.
 *
 * Model: ONNX (opset 17), input "tokens" [1, 6] LongTensor, output "logits" [1, 30000] FloatTensor.
 * Vocab: gru_vocab.txt in assets — one word per line, line index == token ID.
 *
 * inferLogits() runs ONNX exactly once per scoreAll() call regardless of candidate count.
 * All candidates are scored by indexing into the shared logits array.
 */
object GruScorer {

    private const val BEGINNING_OF_SENTENCE_TAG = "<S>"

    private const val ONNX_FILENAME = "gru_cifg_int8.onnx"
    private const val VOCAB_ASSET = "gru_vocab.txt"
    private const val SEQ_LEN = 6
    private const val CONTEXT_DELIMITER = "\u001F"

    private val lock = Any()
    @Volatile private var session: OrtSession? = null
    @Volatile private var wordToIdx: Map<String, Int> = emptyMap()
    private val loading = AtomicBoolean(false)

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
        if (session != null) return
        // CAS, not check-then-set: loadSettings() spawns a fresh loader thread on
        // every call, and two racing start() calls would each build an ORT session
        // and leak one natively.
        if (!loading.compareAndSet(false, true)) return
        try {
            if (session != null) return
            val lines = context.assets.open(VOCAB_ASSET).bufferedReader().readLines()
            wordToIdx = buildMap(lines.size) {
                lines.forEachIndexed { idx, w -> put(w.lowercase(), idx) }
            }
            val modelPath = "${context.filesDir.absolutePath}/$ONNX_FILENAME"
            val options = OrtSession.SessionOptions().apply {
                addXnnpack(mapOf())
                setCPUArenaAllocator(true)
            }
            val newSession = OrtEnvironment.getEnvironment().createSession(modelPath, options)
            options.close()
            synchronized(lock) {
                session = newSession
            }
        } catch (_: Exception) {
            wordToIdx = emptyMap()
        } finally {
            loading.set(false)
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
}
