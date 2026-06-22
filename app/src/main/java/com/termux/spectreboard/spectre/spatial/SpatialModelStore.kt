// SPDX-License-Identifier: GPL-3.0-only
package com.termux.spectreboard.spectre.spatial

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object SpatialModelStore {
    private const val PREFS = "spectre_spatial_model"
    private const val KEY = "per_key_gaussians_v1"
    private const val KEY_ROW_COUNT = "last_built_row_count"

    fun save(context: Context, model: Map<String, PerKeyGaussian>, builtFromRowCount: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, Json.encodeToString(model))
            .putInt(KEY_ROW_COUNT, builtFromRowCount)
            .apply()
    }

    fun load(context: Context): Map<String, PerKeyGaussian> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyMap()
        return try {
            Json.decodeFromString(json)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun lastBuiltRowCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_ROW_COUNT, 0)
}
