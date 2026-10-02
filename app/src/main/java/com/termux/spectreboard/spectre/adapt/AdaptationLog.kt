package com.termux.spectreboard.spectre.adapt

import android.content.Context
import android.util.Log
import com.termux.spectreboard.latin.NgramContext
import com.termux.spectreboard.latin.SuggestedWords.SuggestedWordInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Persistent training-signal log for the personal adaptation pipeline (see scope.md,
 * "Personal adaptation pipeline", 2026-10-02).
 *
 * Appends one JSON object per line to `filesDir/adapt/events-YYYY-MM-DD.jsonl`
 * (DE storage on this ROM). comrade pulls these and builds the personal corpus,
 * reranker training pairs, and eval set from them.
 *
 * Events:
 *  - `commit`: every word commit (type = typed|pick|auto|cancel), with n-gram context,
 *    separator, and — when available — the per-candidate scorer features from the last
 *    rerank of that word, in strip order.
 *  - `revert`: backspace undid an autocorrect (committed → typed). Join offline with the
 *    preceding `commit` event of the same pair.
 *
 * Callers must skip incognito/password fields (SettingsValues.mIncognitoModeEnabled).
 */
object AdaptationLog {
    private const val TAG = "AdaptationLog"
    private const val DIR_NAME = "adapt"
    private const val MAX_FEATURE_ROWS = 10

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "spectre-adapt-log").apply { isDaemon = true }
    }
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    @Volatile private var dir: File? = null
    @Volatile private var lastFeatures: FeatureSnapshot? = null

    private class FeatureRow(
        val word: String,
        val dictScore: Int,
        val kind: Int,
        val gru: Float?,
        val kenlm: Float?,
        val spatial: Double?
    )

    private class FeatureSnapshot(val context: String, val batch: Boolean, val rows: List<FeatureRow>)

    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.filesDir, DIR_NAME)
    }

    /** Called from Suggest.rerankCombined after sorting; keeps only the latest snapshot. */
    fun recordFeatures(
        sorted: List<SuggestedWordInfo>,
        ngramContext: NgramContext,
        batch: Boolean,
        spatial: Map<SuggestedWordInfo, Double>?,
        kenlm: Map<SuggestedWordInfo, Float?>?,
        gru: Map<SuggestedWordInfo, Float?>?
    ) {
        if (dir == null) return
        val n = minOf(sorted.size, MAX_FEATURE_ROWS)
        val rows = ArrayList<FeatureRow>(n)
        for (i in 0 until n) {
            val s = sorted[i]
            rows.add(FeatureRow(s.mWord, s.mScore, s.kind, gru?.get(s), kenlm?.get(s), spatial?.get(s)))
        }
        lastFeatures = FeatureSnapshot(ngramContext.extractPrevWordsContext(), batch, rows)
    }

    fun logCommit(
        commitType: Int,
        typed: String?,
        committed: String,
        separator: String?,
        ngramContext: NgramContext,
        batch: Boolean
    ) {
        if (dir == null) return
        val ctx = ngramContext.extractPrevWordsContext()
        val features = lastFeatures?.takeIf { snap ->
            snap.context == ctx && snap.rows.any {
                it.word.equals(committed, ignoreCase = true) ||
                    (typed != null && it.word.equals(typed, ignoreCase = true))
            }
        }
        lastFeatures = null
        val ev = JSONObject()
            .put("ev", "commit")
            .put("t", System.currentTimeMillis())
            .put("type", commitTypeName(commitType))
            .put("typed", typed ?: "")
            .put("committed", committed)
            .put("sep", separator ?: "")
            .put("ctx", ctx)
            .put("batch", batch)
        if (features != null) {
            val arr = JSONArray()
            for (r in features.rows) {
                arr.put(JSONObject()
                    .put("w", r.word)
                    .put("dict", r.dictScore)
                    .put("kind", r.kind)
                    .put("gru", r.gru ?: JSONObject.NULL)
                    .put("ken", r.kenlm ?: JSONObject.NULL)
                    .put("sp", r.spatial?.takeIf { it.isFinite() } ?: JSONObject.NULL))
            }
            ev.put("cands", arr)
        }
        append(ev)
    }

    fun logRevert(typed: String, committed: String, ngramContext: NgramContext) {
        if (dir == null) return
        append(JSONObject()
            .put("ev", "revert")
            .put("t", System.currentTimeMillis())
            .put("typed", typed)
            .put("committed", committed)
            .put("ctx", ngramContext.extractPrevWordsContext()))
    }

    private fun commitTypeName(type: Int): String = when (type) {
        0 -> "typed"   // COMMIT_TYPE_USER_TYPED_WORD
        1 -> "pick"    // COMMIT_TYPE_MANUAL_PICK
        2 -> "auto"    // COMMIT_TYPE_DECIDED_WORD
        3 -> "cancel"  // COMMIT_TYPE_CANCEL_AUTO_CORRECT
        else -> "t$type"
    }

    private fun append(ev: JSONObject) {
        val d = dir ?: return
        val line = ev.toString()
        val ts = ev.optLong("t")
        writer.execute {
            try {
                if (!d.isDirectory && !d.mkdirs()) return@execute
                val day = synchronized(dayFormat) { dayFormat.format(Date(ts)) }
                FileWriter(File(d, "events-$day.jsonl"), true).use {
                    it.write(line)
                    it.write("\n")
                }
            } catch (e: Exception) {
                Log.w(TAG, "append failed", e)
            }
        }
    }
}
