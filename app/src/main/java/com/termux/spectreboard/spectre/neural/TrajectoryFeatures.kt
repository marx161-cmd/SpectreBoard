package com.termux.spectreboard.spectre.neural

import android.graphics.PointF
import kotlin.math.max
import kotlin.math.min

data class TrajectoryPoint(
    val x: Float, val y: Float,
    val vx: Float, val vy: Float,
    val ax: Float, val ay: Float
)

data class TrajectoryFeatures(
    val normalizedPoints: List<TrajectoryPoint>,
    val nearestKeys: List<Int>,
    val actualLength: Int
)

object TrajectoryFeatureExtractor {

    private val qwertyLayout = mapOf(
        'q' to Pair(0.05f, 0.0f),  'w' to Pair(0.15f, 0.0f),  'e' to Pair(0.25f, 0.0f),
        'r' to Pair(0.35f, 0.0f),  't' to Pair(0.45f, 0.0f),  'y' to Pair(0.55f, 0.0f),
        'u' to Pair(0.65f, 0.0f),  'i' to Pair(0.75f, 0.0f),  'o' to Pair(0.85f, 0.0f),
        'p' to Pair(0.95f, 0.0f),
        'a' to Pair(0.10f, 0.5f),  's' to Pair(0.20f, 0.5f),  'd' to Pair(0.30f, 0.5f),
        'f' to Pair(0.40f, 0.5f),  'g' to Pair(0.50f, 0.5f),  'h' to Pair(0.60f, 0.5f),
        'j' to Pair(0.70f, 0.5f),  'k' to Pair(0.80f, 0.5f),  'l' to Pair(0.90f, 0.5f),
        'z' to Pair(0.15f, 1.0f),  'x' to Pair(0.25f, 1.0f),  'c' to Pair(0.35f, 1.0f),
        'v' to Pair(0.45f, 1.0f),  'b' to Pair(0.55f, 1.0f),  'n' to Pair(0.65f, 1.0f),
        'm' to Pair(0.75f, 1.0f)
    )

    fun extractFeatures(
        coords: List<PointF>,
        timestamps: List<Long>,
        keyboardWidth: Float,
        keyboardHeight: Float
    ): TrajectoryFeatures {
        val count = min(coords.size, timestamps.size)
        if (count < 3) return TrajectoryFeatures(emptyList(), emptyList(), 0)

        val normalized = ArrayList<PointF>(count)
        for (i in 0 until count) {
            normalized.add(PointF(
                (coords[i].x / keyboardWidth).coerceIn(0f, 1f),
                (coords[i].y / keyboardHeight).coerceIn(0f, 1f)
            ))
        }

        val smoothed = smoothTrajectory(normalized, count)
        val features = calculateFeatures(smoothed, timestamps.take(count))
        val nearestKeys = detectNearestKeys(smoothed)

        val maxSeqLen = NeuralConfig.MAX_SEQ_LENGTH
        val actualLen = min(features.size, maxSeqLen)
        val zeroPoint = TrajectoryPoint(0f, 0f, 0f, 0f, 0f, 0f)

        val paddedPoints = if (features.size >= maxSeqLen) {
            features.take(maxSeqLen)
        } else {
            features + List(maxSeqLen - features.size) { zeroPoint }
        }

        val paddedKeys = if (nearestKeys.size >= maxSeqLen) {
            nearestKeys.take(maxSeqLen)
        } else {
            nearestKeys + List(maxSeqLen - nearestKeys.size) { nearestKeys.lastOrNull() ?: 0 }
        }

        return TrajectoryFeatures(paddedPoints, paddedKeys, actualLen)
    }

    private fun smoothTrajectory(coords: List<PointF>, count: Int): List<PointF> {
        val window = NeuralConfig.SMOOTHING_WINDOW
        if (count < window) return coords
        val result = ArrayList<PointF>(count)
        for (i in 0 until count) {
            val st = max(0, i - window / 2)
            val en = min(count - 1, i + window / 2)
            var sx = 0f; var sy = 0f; var n = 0
            for (j in st..en) { sx += coords[j].x; sy += coords[j].y; n++ }
            result.add(PointF(sx / n, sy / n))
        }
        return result
    }

    private fun calculateFeatures(coords: List<PointF>, timestamps: List<Long>): List<TrajectoryPoint> {
        val n = coords.size
        val xs = FloatArray(n) { coords[it].x }
        val ys = FloatArray(n) { coords[it].y }

        val dt = FloatArray(n)
        dt[0] = 0f
        for (i in 1 until n) dt[i] = max((timestamps[i] - timestamps[i - 1]).toFloat(), 1e-6f)

        val vx = FloatArray(n); val vy = FloatArray(n)
        for (i in 1 until n) {
            vx[i] = (xs[i] - xs[i - 1]) / dt[i]
            vy[i] = (ys[i] - ys[i - 1]) / dt[i]
        }
        val ax = FloatArray(n); val ay = FloatArray(n)
        for (i in 1 until n) {
            ax[i] = (vx[i] - vx[i - 1]) / dt[i]
            ay[i] = (vy[i] - vy[i - 1]) / dt[i]
        }
        for (i in 0 until n) {
            vx[i] = vx[i].coerceIn(-10f, 10f)
            vy[i] = vy[i].coerceIn(-10f, 10f)
            ax[i] = ax[i].coerceIn(-10f, 10f)
            ay[i] = ay[i].coerceIn(-10f, 10f)
        }
        return List(n) { i -> TrajectoryPoint(xs[i], ys[i], vx[i], vy[i], ax[i], ay[i]) }
    }

    private fun detectNearestKeys(coords: List<PointF>): List<Int> {
        return coords.map { pt ->
            var best = 'e'; var bestDist = Float.MAX_VALUE
            for ((ch, pos) in qwertyLayout) {
                val dx = pt.x - pos.first; val dy = pt.y - pos.second
                val dist = dx * dx + dy * dy
                if (dist < bestDist) { bestDist = dist; best = ch }
            }
            Tokenizer.charToToken(best).toInt()
        }
    }
}
