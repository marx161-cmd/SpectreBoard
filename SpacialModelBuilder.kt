package com.termux.spectreboard.spectre.spatial

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * SpatialModelBuilder
 *
 * Consumes rows from the GESTURE_DATA table (populated by the widened
 * BackgroundGatheringCache pipeline) and produces a per-key bivariate Gaussian
 * describing where the user *actually* taps when they *intend* a given key.
 *
 * The output (PerKeyGaussian) is what a touch-correction layer consumes: instead
 * of treating a tap at (x,y) as "nearest key center", you score each candidate
 * key by the probability density of that tap under the key's learned Gaussian.
 *
 * DESIGN STANCE: this class does NOT mutate anything and does NOT touch the
 * native TouchPositionCorrection path. It only *computes* the model. Wiring the
 * model into ProximityInfo / suggest is a separate, deliberate step so a bad
 * model can never silently corrupt live typing — you can dump and eyeball the
 * Gaussians first.
 *
 * ============================ ASSUMPTIONS TO VERIFY ============================
 * Each of these, if wrong, produces a model that looks plausible but is subtly
 * garbage. Verify each against the actual HeliBoard source before trusting output.
 *
 * [A1] COORDINATE SPACE. We assume PointerData.x / .y and KeyboardInfo key
 *      geometry (left/top/width/height) are in the SAME coordinate space and
 *      SAME units (pixels, origin top-left). If gesture-gathering stores touch
 *      coords in a normalized or keyboard-relative space while key geometry is
 *      in absolute pixels (or vice-versa), every offset is meaningless. CHECK
 *      this first — it's the most likely silent killer.
 *
 * [A2] WHICH POINTER SAMPLE REPRESENTS A TAP. A tap is logged as a PointerData
 *      trajectory too (it has duration). For a tap, the *intended* point is best
 *      represented by the touch-DOWN sample (first sample), not the centroid or
 *      the up-sample, because finger roll/lift drifts the later samples. We use
 *      the FIRST sample per pointer. If the gatherer already reduces a tap to a
 *      single point, this still works (n=1 trajectory).
 *
 * [A3] TARGET→KEY MAPPING. We need to know which key each tap was *intended* for.
 *      The honest ground truth is per-character, not per-word. We reconstruct it
 *      by aligning the committed targetWord's characters to the sequence of tap
 *      pointers in the same gesture record. This alignment is the SECOND most
 *      likely silent killer (see alignTapsToChars). It is conservative: if counts
 *      don't line up, the whole record is DROPPED, not guessed.
 *
 * [A4] KEY IDENTITY. We key the model by the key's `value` string (the committed
 *      character/label). Layouts with the same physical key emitting different
 *      values per shift-state will be merged under their committed value. That is
 *      actually what we want for touch correction (we care about the glass
 *      location the finger hit for the char it produced), but be aware multi-layout
 *      sessions mix data — DictInfo.language is carried so you can partition later.
 *
 * [A5] SWIPE RECORDS. Gesture (swipe) records do NOT have a clean per-char tap
 *      location — the trajectory is continuous. We DO NOT feed swipe records into
 *      the per-key tap Gaussians. They're filtered out by `source`/style. Only
 *      tap-sourced records contribute. (Swipe data is still useful, but for a
 *      different model; mixing it here corrupts the tap Gaussians.)
 * ==============================================================================
 */

// ---------- Output types ----------

/**
 * A bivariate Gaussian for one key, in the coordinate space of [A1].
 * meanX/meanY: the centroid of where taps land for this key.
 * The key's declared center is [centerX]/[centerY]; (meanX-centerX, meanY-centerY)
 * is the systematic offset (e.g. "user lands 3.1px left of P's center").
 */
data class PerKeyGaussian(
    val keyValue: String,
    val centerX: Double,
    val centerY: Double,
    val meanX: Double,
    val meanY: Double,
    val varX: Double,
    val varY: Double,
    val covXY: Double,
    val sampleCount: Int
) {
    val offsetX: Double get() = meanX - centerX
    val offsetY: Double get() = meanY - centerY

    /**
     * Log-probability density of a tap at (x,y) under this Gaussian.
     * Returns a LOG density (unnormalized constant dropped is fine for ranking,
     * but we keep the full term so densities are comparable across keys with
     * different covariance — important when ensembling with LM scores).
     *
     * Guarded against degenerate (near-singular) covariance: see [regularize].
     */
    fun logDensity(x: Double, y: Double): Double {
        // 2x2 covariance [[varX, covXY],[covXY, varY]]
        val det = varX * varY - covXY * covXY
        // det <= 0 should be impossible after regularization, but guard anyway.
        if (det <= 0.0) return Double.NEGATIVE_INFINITY
        val invXX = varY / det
        val invYY = varX / det
        val invXY = -covXY / det
        val dx = x - meanX
        val dy = y - meanY
        val mahalanobis = invXX * dx * dx + 2.0 * invXY * dx * dy + invYY * dy * dy
        // log( 1 / (2π sqrt(det)) ) - 0.5 * mahalanobis
        return -(ln(2.0 * Math.PI) + 0.5 * ln(det)) - 0.5 * mahalanobis
    }
}

// ---------- Raw parsed record ----------

private data class KeyGeom(
    val value: String,
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double
) {
    val centerX get() = left + width / 2.0
    val centerY get() = top + height / 2.0
}

private data class TapPoint(val x: Double, val y: Double)

// ---------- Builder ----------

class SpatialModelBuilder(
    /**
     * Minimum samples before a key's Gaussian is considered trustworthy.
     * Below this, callers should fall back to the static key center (offset 0)
     * rather than a noisy estimate. 30 is a conservative default; tune later.
     */
    private val minSamplesPerKey: Int = 30,

    /**
     * Variance floor as a FRACTION of key size, applied per-axis. Prevents a
     * tight cluster of near-identical taps from producing a near-singular,
     * overconfident Gaussian that then violently rejects slightly-off taps.
     * 0.15 => sigma floor is 15% of key width/height. This is the single most
     * important safety knob; too low and the model becomes brittle.
     */
    private val varianceFloorFraction: Double = 0.15
) {

    // keyValue -> accumulator
    private val acc = HashMap<String, Accumulator>()

    /**
     * Welford online accumulator for mean + covariance. Numerically stable;
     * does NOT accumulate sum-of-squares naively (which loses precision and is
     * a classic silent-failure source on large n).
     */
    private class Accumulator(val center: KeyGeom) {
        var n = 0L
        var meanX = 0.0
        var meanY = 0.0
        var m2xx = 0.0  // sum of squares of dx
        var m2yy = 0.0
        var m2xy = 0.0  // sum of dx*dy

        fun add(x: Double, y: Double) {
            n += 1
            val dX = x - meanX
            val dY = y - meanY
            meanX += dX / n
            meanY += dY / n
            val dX2 = x - meanX  // uses updated mean (Welford)
            val dY2 = y - meanY
            m2xx += dX * dX2
            m2yy += dY * dY2
            m2xy += dX * dY2
        }
    }

    /**
     * Feed one GESTURE_DATA row's JSON blob.
     *
     * @param json the TEXT JSON column from one GESTURE_DATA row.
     * @param sourceIsTap true if this row came from INPUT_STYLE_TYPING (tap).
     *        Per [A5], swipe rows must NOT be passed with sourceIsTap=true.
     * @return number of (char,tap) pairs successfully ingested from this row
     *         (0 means the row was dropped — alignment failed or it was a swipe).
     */
    fun ingestRow(json: String, sourceIsTap: Boolean): Int {
        if (!sourceIsTap) return 0  // [A5] tap Gaussians only

        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return 0  // malformed row, drop silently-but-counted (caller sees 0)
        }

        val targetWord = root.optString("targetWord", "")
        if (targetWord.isEmpty()) return 0

        val keyGeom = parseKeyboardInfo(root) ?: return 0
        val taps = parseTaps(root) ?: return 0

        // [A3] align committed chars to tap points. Conservative: drop on mismatch.
        val pairs = alignTapsToChars(targetWord, taps) ?: return 0

        var ingested = 0
        for ((ch, tap) in pairs) {
            val geom = keyGeom[ch] ?: continue  // char has no key in this layout snapshot
            val a = acc.getOrPut(ch) { Accumulator(geom) }
            a.add(tap.x, tap.y)
            ingested++
        }
        return ingested
    }

    /**
     * Finalize into per-key Gaussians. Keys below [minSamplesPerKey] are omitted
     * (caller falls back to static center for those). Applies the variance floor.
     */
    fun build(): Map<String, PerKeyGaussian> {
        val out = HashMap<String, PerKeyGaussian>()
        for ((value, a) in acc) {
            if (a.n < minSamplesPerKey) continue
            val nD = a.n.toDouble()
            // sample covariance (divide by n-1)
            var varX = a.m2xx / (nD - 1.0)
            var varY = a.m2yy / (nD - 1.0)
            var covXY = a.m2xy / (nD - 1.0)

            // Variance floor per [A5 safety]. Floor is fraction of key dimension, squared.
            val floorX = (a.center.width * varianceFloorFraction).let { it * it }
            val floorY = (a.center.height * varianceFloorFraction).let { it * it }
            if (varX < floorX) varX = floorX
            if (varY < floorY) varY = floorY

            // Clamp covariance so the matrix stays positive-definite after flooring.
            // |cov| must be < sqrt(varX*varY); shrink toward 0 if it isn't.
            val maxCov = 0.99 * sqrt(varX * varY)
            if (covXY > maxCov) covXY = maxCov
            if (covXY < -maxCov) covXY = -maxCov

            out[value] = PerKeyGaussian(
                keyValue = value,
                centerX = a.center.centerX,
                centerY = a.center.centerY,
                meanX = a.meanX,
                meanY = a.meanY,
                varX = varX,
                varY = varY,
                covXY = covXY,
                sampleCount = a.n.toInt()
            )
        }
        return out
    }

    // ---------- parsing helpers ----------

    /**
     * [A4] Build value -> geometry map from KeyboardInfo.keys[].
     * If the same value appears on multiple keys (rare; e.g. duplicate punctuation),
     * the LAST one wins. Flagged because it's a quiet ambiguity — log if it matters.
     */
    private fun parseKeyboardInfo(root: JSONObject): Map<String, KeyGeom>? {
        val kb = root.optJSONObject("keyboardInfo") ?: return null
        val keys = kb.optJSONArray("keys") ?: return null
        val map = HashMap<String, KeyGeom>()
        for (i in 0 until keys.length()) {
            val k = keys.optJSONObject(i) ?: continue
            val value = k.optString("value", "")
            if (value.isEmpty()) continue
            // ASSUMPTION: field names. Adjust if the real blob uses left/x, top/y, etc.
            val left = k.optDouble("left", Double.NaN)
            val top = k.optDouble("top", Double.NaN)
            val width = k.optDouble("width", Double.NaN)
            val height = k.optDouble("height", Double.NaN)
            if (left.isNaN() || top.isNaN() || width.isNaN() || height.isNaN()) continue
            map[value] = KeyGeom(value, left, top, width, height)
        }
        return if (map.isEmpty()) null else map
    }

    /**
     * Parse the tap points. Per [A2] we take the samples in array order —
     * for tap data each entry corresponds to one keystroke, in typing sequence.
     *
     * The actual JSON field is "gesture" (the @Serializable name on
     * GestureData.gesture: List<PointerData>). Each element is
     * {id, x, y, millis}. For taps, all have id=0 and millis=0, so
     * array-position order IS keystroke order.
     *
     * NOTE: We intentionally do NOT group by id here. For tap data
     * (INPUT_STYLE_TYPING), every sample shares id=0, so grouping would
     * collapse N keystrokes into 1 point and silently break alignment.
     */
    private fun parseTaps(root: JSONObject): List<TapPoint>? {
        val arr = root.optJSONArray("gesture") ?: return null
        val points = ArrayList<TapPoint>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val x = o.optDouble("x", Double.NaN)
            val y = o.optDouble("y", Double.NaN)
            if (x.isNaN() || y.isNaN()) continue
            points.add(TapPoint(x, y))
        }
        return if (points.isEmpty()) null else points
    }

    /**
     * [A3] THE DANGEROUS PART. Align the committed word's characters to the tap
     * points. This is intentionally strict:
     *
     *   - We only align when the number of taps EQUALS the number of characters
     *     in targetWord (after the same normalization the keyboard would apply).
     *   - Any mismatch (autocorrect changed length, double-tap, backspace,
     *     long-press alt, predicted word from partial input) => DROP the whole
     *     record and return null. We would rather throw away good data than
     *     poison the model with misaligned (char, location) pairs.
     *
     * This is conservative on purpose: a wrong alignment doesn't fail loudly, it
     * just teaches the model that (say) 't' is where 'h' was tapped. Dropping is
     * the only safe response to ambiguity here.
     *
     * NOTE: we lowercase for matching key values, but DO NOT change which key the
     * char maps to beyond that. If your layout's key `value` is uppercase, adjust.
     */
    private fun alignTapsToChars(targetWord: String, taps: List<TapPoint>): List<Pair<String, TapPoint>>? {
        // Normalize: the committed word may contain chars that don't correspond to
        // a single tap (we can't know). Strict 1:1 only.
        val chars = targetWord.map { it.toString() }
        if (chars.size != taps.size) return null
        // Reject if any char is whitespace (space taps are real but their geometry
        // is huge and skews nothing useful here; safer to skip for v1).
        if (chars.any { it.isBlank() }) return null
        return chars.zip(taps)
    }
}
