// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre

import android.content.Context
import com.termux.spectreboard.latin.NgramContext
import java.util.concurrent.atomic.AtomicBoolean

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

    private val lock = Any()
    @Volatile private var loaded = false
    private val loading = AtomicBoolean(false)
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

    /**
     * Lock-free check — returns true while the model is loading or unloaded,
     * so the suggestion path never blocks behind a start() in progress.
     */
    fun isEmpty(): Boolean = !loaded

    /**
     * Load the binary model (LAZY mmap, so this is fast).  Guarded by a CAS on
     * [loading] so concurrent start() calls from repeated loadSettings() are
     * no-ops; initNative runs outside [lock] so scoreAll() callers are never
     * blocked behind it.
     */
    fun start(context: Context) {
        if (loaded || !nativeAvailable) return
        if (!loading.compareAndSet(false, true)) return
        try {
            val modelPath = "${context.filesDir.absolutePath}/$MODEL_FILENAME"
            val ok = initNative(modelPath)
            synchronized(lock) { loaded = ok }
        } finally {
            loading.set(false)
        }
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
            if (!loaded) return@synchronized null
            scoreAllNative(contextStr, candidates, isBOS)
        }
    }
}
