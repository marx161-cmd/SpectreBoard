package com.termux.spectreboard.spectre.neural

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

object GestureAbLogger {
    private const val TAG = "GestureAbLogger"
    private const val FILENAME = "gesture_ab_log.jsonl"
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, FILENAME)
    }

    fun log(googleTop: String, neuralTop: String, neuralTop3: String, googleAll: List<String>, trajectoryPoints: Int) {
        val f = file ?: return
        try {
            val entry = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("google_top", googleTop)
                put("neural_top", neuralTop)
                put("neural_top3", neuralTop3)
                put("google_all", JSONArray(googleAll))
                put("trajectory_points", trajectoryPoints)
                put("match", googleTop.equals(neuralTop, ignoreCase = true))
            }
            FileWriter(f, true).use { w ->
                w.write(entry.toString())
                w.write("\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to log AB entry", e)
        }
    }

    fun filePath(): String = file?.absolutePath ?: ""
}
