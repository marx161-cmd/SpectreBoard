// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre.neural

import android.graphics.PointF
import android.os.Process
import com.termux.spectreboard.keyboard.Keyboard
import com.termux.spectreboard.spectre.PublicBuild
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shadow mode (scope.md 2026-10-27): the neural engine guesses every recorded swipe in the
 * background, after Google's suggestions are out, and the guess is stored next to the swipe in
 * GESTURE_DATA (`neural` field). Nothing is shown — the keyboard types exactly as before; the
 * stored guesses vs targetWord are the accuracy curve for training on comrade.
 */
object NeuralShadow {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "neural-shadow")
            .apply { isDaemon = true }
    }
    private val inFlight = AtomicInteger(0)

    /** [onResult] gets the top-3 words, or an empty list if the engine failed on this swipe. */
    fun submit(coords: List<PointF>, times: List<Long>, keyboard: Keyboard, onResult: (List<String>) -> Unit) {
        if (PublicBuild.enabled || !NeuralGestureEngine.initialized || coords.size < 3) return
        if (inFlight.get() >= 2) return // busy: skip this swipe rather than queue work up
        val width = keyboard.mOccupiedWidth.toFloat()
        val (top, height) = qwertyBand(keyboard)
        inFlight.incrementAndGet()
        executor.execute {
            try {
                onResult(NeuralGestureEngine.predict(coords, times, width, height, top).take(3).map { it.first })
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /** Letter-row band (top, height) the model's y is normalised over, from the q and m key centres
     *  (CleverKeys CoordinateNormalizer); the whole keyboard if either key is missing. */
    fun qwertyBand(keyboard: Keyboard): Pair<Float, Float> {
        val q = keyboard.sortedKeys.firstOrNull { it.code == 'q'.code || it.code == 'Q'.code } // shifted layout
        val m = keyboard.sortedKeys.firstOrNull { it.code == 'm'.code || it.code == 'M'.code }
            ?: return 0f to keyboard.mOccupiedHeight.toFloat()
        if (q == null) return 0f to keyboard.mOccupiedHeight.toFloat()
        val rowHeight = ((m.y + m.height / 2f) - (q.y + q.height / 2f)) / 2f
        if (rowHeight <= 0f) return 0f to keyboard.mOccupiedHeight.toFloat()
        val top = q.y + q.height / 2f - rowHeight / 2f
        return if (top < 0f) 0f to 3f * rowHeight + top else top to 3f * rowHeight
    }
}
