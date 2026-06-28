// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre.spatial

import android.content.Context
import com.termux.spectreboard.latin.utils.GestureDataDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object SpatialModelWorker {
    // Rebuild only when at least this many new rows have been collected since the last build.
    // Keeps the background pass cheap during early data accumulation.
    private const val MIN_NEW_ROWS = 50

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rebuildInProgress = AtomicBoolean(false)

    /**
     * Trigger an async model rebuild if enough new tap rows have accumulated.
     * Safe to call on every loadSettings() — it no-ops when the row count hasn't grown enough.
     * Called from Java as SpatialModelWorker.INSTANCE.maybeRebuild(context).
     */
    fun maybeRebuild(context: Context) {
        val dao = GestureDataDao.getInstance(context) ?: return
        val currentRowCount = dao.count()
        val lastBuilt = SpatialModelStore.lastBuiltRowCount(context)
        if (currentRowCount - lastBuilt < MIN_NEW_ROWS) return
        if (!rebuildInProgress.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        scope.launch {
            try {
                val builder = SpatialModelBuilder()
                for (json in dao.getAllJsonData(appContext)) {
                    builder.ingestRow(json, sourceIsTap = true)
                }
                val model = builder.build()
                if (model.isEmpty()) return@launch
                SpatialModelStore.save(appContext, model, currentRowCount)
                SpatialScorer.updateModel(model)
            } finally {
                rebuildInProgress.set(false)
            }
        }
    }
}
