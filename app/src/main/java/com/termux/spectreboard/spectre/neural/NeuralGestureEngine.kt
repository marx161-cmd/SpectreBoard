package com.termux.spectreboard.spectre.neural

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.PointF
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

object NeuralGestureEngine {
    private const val TAG = "NeuralGestureEngine"
    private lateinit var context: Context

    @Volatile private var ortEnv: OrtEnvironment? = null
    @Volatile private var encoderSession: OrtSession? = null
    @Volatile private var decoderSession: OrtSession? = null
    @Volatile private var beamDecoder: BeamSearchDecoder? = null
    val vocabTrie = VocabularyTrie()

    @Volatile var initialized: Boolean = false
        private set

    private val nativeAvailable = try {
        System.loadLibrary("onnxruntime")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "ONNX Runtime native library unavailable", t)
        false
    }

    private val lock = Any()

    fun initialize(context: Context): Boolean {
        if (!nativeAvailable) return false
        synchronized(lock) {
            if (initialized) return true
            this.context = context.applicationContext
            try {
                ortEnv = OrtEnvironment.getEnvironment()
                encoderSession = loadSession(NeuralConfig.ENCODER_MODEL)
                decoderSession = loadSession(NeuralConfig.DECODER_MODEL)

                val tokenizerJson = this.context.assets.open(NeuralConfig.TOKENIZER_JSON)
                    .bufferedReader().use { it.readText() }
                Tokenizer.loadFromJson(tokenizerJson)

                vocabTrie.loadFromAssets(this.context, NeuralConfig.DICTIONARY)
                Log.i(TAG, "Loaded ${vocabTrie.size} words into vocabulary")
                beamDecoder = BeamSearchDecoder(
                    ortEnv = ortEnv!!,
                    decoderSession = decoderSession!!,
                    vocabTrie = if (vocabTrie.size > 0) vocabTrie else null
                )
                initialized = true
                Log.i(TAG, "NeuralGestureEngine initialized")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize", e)
                cleanup()
                return false
            }
        }
    }

    private fun loadSession(assetPath: String): OrtSession {
        val bytes = this.context.assets.open(assetPath).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(NeuralConfig.ONNX_THREADS)
        }
        return ortEnv!!.createSession(bytes, options)
    }

    fun predict(
        coords: List<PointF>,
        timestamps: List<Long>,
        keyboardWidth: Float,
        keyboardHeight: Float
    ): List<Pair<String, Float>> {
        if (!nativeAvailable || !initialized || coords.size < 3) return emptyList()

        val sesh = encoderSession ?: return emptyList()
        val env = ortEnv ?: return emptyList()
        val decoder = beamDecoder ?: return emptyList()

        val features = TrajectoryFeatureExtractor.extractFeatures(coords, timestamps, keyboardWidth, keyboardHeight)
        if (features.actualLength == 0) return emptyList()

        var trajTensor: OnnxTensor? = null
        var keysTensor: OnnxTensor? = null
        var lenTensor: OnnxTensor? = null

        return try {
            trajTensor = createTrajectoryTensor(env, features)
            keysTensor = createNearestKeysTensor(env, features)
            lenTensor = OnnxTensor.createTensor(env, intArrayOf(features.actualLength))

            val inputs = mapOf(
                "trajectory_features" to trajTensor,
                "nearest_keys" to keysTensor,
                "actual_length" to lenTensor
            )

            val result = sesh.run(inputs)
            try {
                val memory = result.get(0) as OnnxTensor
                val candidates = decoder.search(memory, features.actualLength)
                candidates.map { Pair(it.word, it.confidence) }
            } finally {
                result.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Prediction failed", e)
            emptyList()
        } finally {
            trajTensor?.close()
            keysTensor?.close()
            lenTensor?.close()
        }
    }

    private fun createTrajectoryTensor(env: OrtEnvironment, features: TrajectoryFeatures): OnnxTensor {
        val maxSeq = NeuralConfig.MAX_SEQ_LENGTH
        val numFeats = NeuralConfig.TRAJECTORY_FEATURES
        val bb = ByteBuffer.allocateDirect(maxSeq * numFeats * 4).apply { order(ByteOrder.nativeOrder()) }
        val fb = bb.asFloatBuffer()
        for (i in 0 until maxSeq) {
            if (i < features.normalizedPoints.size) {
                val pt = features.normalizedPoints[i]
                fb.put(pt.x); fb.put(pt.y); fb.put(pt.vx)
                fb.put(pt.vy); fb.put(pt.ax); fb.put(pt.ay)
            } else {
                repeat(numFeats) { fb.put(0f) }
            }
        }
        fb.rewind()
        return OnnxTensor.createTensor(env, fb, longArrayOf(1, maxSeq.toLong(), numFeats.toLong()))
    }

    private fun createNearestKeysTensor(env: OrtEnvironment, features: TrajectoryFeatures): OnnxTensor {
        val maxSeq = NeuralConfig.MAX_SEQ_LENGTH
        val bb = ByteBuffer.allocateDirect(maxSeq * 4).apply { order(ByteOrder.nativeOrder()) }
        val ib = bb.asIntBuffer()
        for (i in 0 until maxSeq) {
            ib.put(if (i < features.nearestKeys.size) features.nearestKeys[i] else NeuralConfig.PAD_IDX.toInt())
        }
        ib.rewind()
        return OnnxTensor.createTensor(env, ib, longArrayOf(1, maxSeq.toLong()))
    }

    fun cleanup() {
        synchronized(lock) {
            decoderSession?.close(); encoderSession?.close()
            decoderSession = null; encoderSession = null; ortEnv = null
            beamDecoder = null; initialized = false
        }
    }
}
