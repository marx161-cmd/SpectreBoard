// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard

import android.graphics.PointF
import com.termux.spectreboard.spectre.neural.TrajectoryFeatureExtractor
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * The swipe model is trained on comrade from features computed by adaptation/swipe/features.py.
 * They must equal what the keyboard computes live, or training teaches the wrong inputs.
 * Golden file: adaptation/swipe/make_golden.py (scope.md 2026-10-27).
 */
@RunWith(RobolectricTestRunner::class)
class NeuralFeatureParityTest {
    @Test
    fun pythonPortMatchesKotlinExtractor() {
        val json = javaClass.classLoader!!.getResource("swipe_features_golden.json").readText()
        val cases = JSONArray(json)
        for (c in 0 until cases.length()) {
            val case = cases.getJSONObject(c)
            val xs = case.getJSONArray("xs"); val ys = case.getJSONArray("ys"); val ts = case.getJSONArray("ts")
            val coords = List(xs.length()) { PointF(xs.getInt(it).toFloat(), ys.getInt(it).toFloat()) }
            val times = List(ts.length()) { ts.getLong(it) }
            val f = TrajectoryFeatureExtractor.extractFeatures(coords, times,
                case.getDouble("width").toFloat(), case.getDouble("height").toFloat(), case.optDouble("top", 0.0).toFloat())
            val word = case.getString("word")
            assertEquals("length $word", case.getInt("length"), f.actualLength)
            val keys = case.getJSONArray("keys")
            for (i in 0 until keys.length()) assertEquals("key $i of $word", keys.getInt(i), f.nearestKeys[i])
            val traj = case.getJSONArray("traj")
            var worst = 0.0
            for (i in f.normalizedPoints.indices) {
                val p = f.normalizedPoints[i]
                val got = floatArrayOf(p.x, p.y, p.vx, p.vy, p.ax, p.ay)
                for (k in 0 until 6) worst = maxOf(worst, abs(got[k] - traj.getDouble(i * 6 + k)))
            }
            assert(worst < 1e-5) { "features of $word differ by $worst" }
        }
    }
}
