// Ported from Outspoke (github.com/minburg/outspoke, GPLv3) inference/ParakeetEngine.kt,
// commit as cloned 2026-09-02. SpectreBoard (HeliBoard fork) is also GPLv3 -- this is a
// license-compatible wholesale port, not a rewrite (see scope.md for why: the TDT streaming
// decode contract -- LSTM state carry-over, frame-by-frame joint calls, SentencePiece
// detokenization with NeMo's digit-fill quirk -- is exactly the kind of thing that should be
// ported from a proven implementation, not hand-rolled).
//
// Trimmed from the original: multi-language conditioning (setLanguage/currentLanguage) removed
// -- the ONNX export has no language input tensor upstream either, Outspoke only used it for
// post-processing this port doesn't include yet. WordAlternative (originally in a separate
// AcousticCandidates.kt) inlined here since that file wasn't ported.
package com.termux.spectreboard.spectre.parakeet

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Debug
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

private const val TAG = "ParakeetEngine"
private const val FALLBACK_BLANK_ID = 1024

/**
 * Output of [ParakeetEngine.greedyDecode]: the decoded text plus a per-token geometric-mean
 * confidence in [0.0, 1.0]. The confidence is exp(mean(log_softmax(argmax))) over all
 * non-blank emissions -- a principled score that low-values hallucinations (flat / uncertain
 * token distributions on cold strides or noise) and high-values clean speech.
 */
private data class DecodeResult(val text: String, val confidence: Float)

/**
 * The TDT decoder's carry-over state between streaming chunks.
 *
 * The Parakeet TDT decoder is a temporal-difference transducer: each encoder frame is
 * decoded against the previous token and an LSTM state. When audio is processed in
 * chunks (the streaming path), this state is carried from the end of one chunk to the
 * start of the next so the decoder continues exactly where it left off -- no re-emission,
 * no duplication.
 *
 *  - [lstmState1] / [lstmState2]: the decoder LSTM hidden / cell, each `[2, 1, 640]`
 *    (1280 floats) as produced by `decoder_joint`'s `output_states_1/2`.
 *  - [prevToken]: the last emitted token ID (the predictor embedding input for the next
 *    frame). The blank token for a fresh start (see [ParakeetEngine.initialTdtState]).
 *  - [frameDelta]: how far the decoder's frame position overshot the range end when the
 *    previous chunk's decode loop exited.
 */
data class TdtState(
    val lstmState1: FloatArray,
    val lstmState2: FloatArray,
    val prevToken: Int,
    val frameDelta: Int = 0,
)

/**
 * A single token candidate at one [TokenEmission]: the token ID and its log-softmax
 * probability over the token portion `[0..blankId]`.
 */
data class EmissionToken(val token: Int, val logProb: Double)

/** One non-blank token emission from the TDT decode loop. */
data class TokenEmission(
    val token: Int,
    val frame: Int,
    val logProb: Double,
    val topTokens: List<EmissionToken>,
)

/** The TDT decoder's state just before the joint model call at [frame]. */
data class FrameState(
    val frame: Int,
    val lstmState1: FloatArray,
    val lstmState2: FloatArray,
    val prevToken: Int,
)

/** Result of decoding a frame range. */
private data class DecodeRangeResult(
    val tokens: List<Int>,
    val state: TdtState,
    val confidence: Float,
    val logProbSum: Double,
    val emissionCount: Int,
    val emissions: List<TokenEmission>,
    val stateSnapshots: List<FrameState>,
)

private object Names {
    // nemo128.onnx
    const val PREP_IN_AUDIO = "waveforms"
    const val PREP_IN_LENGTH = "waveforms_lens"

    // encoder-model.int8.onnx
    const val ENC_IN_SIGNAL = "audio_signal"   // FLOAT [-1, 128, -1]
    const val ENC_IN_LENGTH = "length"          // INT64 [-1]
    const val ENC_OUT_SIGNAL = "outputs"          // FLOAT [-1, 1024, -1]  (B, D, T)
    const val ENC_OUT_LEN = "encoded_lengths"  // INT64 [-1]

    // decoder_joint-model.int8.onnx
    const val DEC_IN_ENC_OUT = "encoder_outputs"  // FLOAT [-1, 1024, -1]
    const val DEC_IN_TARGETS = "targets"           // INT32 [-1, -1]
    const val DEC_IN_TARGET_LEN = "target_length"     // INT32 [-1]
    const val DEC_IN_STATES_1 = "input_states_1"    // FLOAT [2, -1, 640]
    const val DEC_IN_STATES_2 = "input_states_2"    // FLOAT [2, -1, 640]
    // Outputs by index: 0=outputs 1=prednet_lengths 2=output_states_1 3=output_states_2
}

/** Stateful decode primitives for a chunked/streaming TDT decode. */
interface ChunkStreamingEngine {
    fun initialTdtState(): TdtState
    fun encodeBuffer(samples: FloatArray): Pair<OnnxTensor, Int>
    fun decodeChunk(
        encoderOut: OnnxTensor,
        totalLength: Int,
        frameStart: Int,
        frameEnd: Int,
        state: TdtState,
    ): ChunkDecodeResult
    fun detokenizeTokens(tokens: List<Int>): String
    fun tokenStartsWord(tokenId: Int): Boolean
    fun localWordBeam(
        encoderOut: OnnxTensor,
        totalLength: Int,
        startFrame: Int,
        endFrame: Int,
        initialState: FrameState,
        beamWidth: Int = WordBeamTuning.BEAM_WIDTH,
        topK: Int = WordBeamTuning.TOP_K_TOKENS,
        maxSteps: Int = WordBeamTuning.MAX_BEAM_STEPS,
        maxAlternatives: Int = WordBeamTuning.MAX_ALTERNATIVES,
    ): List<WordAlternative>
}

object WordBeamTuning {
    const val TOP_K_TOKENS = 3
    const val BEAM_WIDTH = 8
    const val MAX_BEAM_STEPS = 200
    const val MAX_ALTERNATIVES = 5
}

data class ChunkDecodeResult(
    val tokens: List<Int>,
    val state: TdtState,
    val logProbSum: Double,
    val emissionCount: Int,
    val emissions: List<TokenEmission>,
    val stateSnapshots: List<FrameState>,
)

/**
 * Wraps the three Parakeet-V3 ONNX sessions.
 *
 * Pipeline:
 *  1. Normalise PCM  ->  float32 in [-1, 1]
 *  2. nemo128.onnx   ->  log-mel features  [1, 128, T']
 *  3. encoder        ->  encoded features  [1, 1024, T_enc]   <- (B, D, T) format!
 *  4. greedy TDT     ->  token IDs via decoder_joint with LSTM state carry-over
 *  5. detokenise     ->  string via vocab.txt
 */
class ParakeetEngine : SpeechEngine, ChunkStreamingEngine {

    private var env: OrtEnvironment? = null
    private var prepSession: OrtSession? = null
    private var encSession: OrtSession? = null
    private var decSession: OrtSession? = null

    private var vocabulary: Array<String> = emptyArray()
    private var blankId: Int = FALLBACK_BLANK_ID
    private var numDurations: Int = 0

    @Volatile
    override var isLoaded: Boolean = false
        private set

    /**
     * BCP-47 language tag used for post-processing (filler removal, number normalisation).
     * `null` means auto-detect; the post-processing pipeline defaults to "en" in that case.
     * The current Parakeet TDT ONNX export exposes no language input tensor, so this is
     * consumed only by the post-processing layer.
     */
    @Volatile
    private var forcedLanguage: String? = null

    /** Returns the active language tag for post-processing, defaulting to "en" for auto-detect. */
    override val currentLanguage: String get() = forcedLanguage ?: "en"

    /** Implements [SpeechEngine.setLanguage]; stores [tag] for post-processing use. */
    override fun setLanguage(tag: String) {
        forcedLanguage = if (tag == "auto") null else tag
    }

    override fun load(modelDir: File) {
        val startTime = System.currentTimeMillis()
        val modelSizeMB = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
        Log.i(TAG, "ParakeetEngine loading from ${modelDir.path}, size=${modelSizeMB}MB")
        if (modelSizeMB > 500) Log.w(TAG, "Parakeet model is very large (${modelSizeMB}MB) - may require high RAM")

        check(!isLoaded) { "Already loaded; call close() before reloading" }

        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }

        env = OrtEnvironment.getEnvironment()
        val e = env!!

        val prepFile = File(modelDir, "nemo128.onnx")
        if (prepFile.exists()) {
            prepSession = e.createSession(prepFile.absolutePath, opts)
            logSession("nemo128 (preprocessor)", prepSession!!)
        } else {
            Log.w(TAG, "nemo128.onnx absent - will forward raw audio to encoder (shapes will mismatch)")
        }

        encSession = e.createSession(File(modelDir, "encoder-model.int8.onnx").absolutePath, opts)
        logSession("encoder", encSession!!)

        decSession = e.createSession(
            File(modelDir, "decoder_joint-model.int8.onnx").absolutePath, opts
        )
        logSession("decoder_joint", decSession!!)

        // vocab.txt format: "<token_text> <id>" (e.g. "▁like 2656").
        vocabulary = File(modelDir, "vocab.txt").readLines()
            .map { line -> line.trim().split(Regex("\\s+")).firstOrNull().orEmpty() }
            .toTypedArray()
        Log.d(TAG, "Vocabulary: ${vocabulary.size} tokens")

        blankId = scanVocabForBlankId()
            ?: parseBlankId(File(modelDir, "config.json"))
                    ?: run {
                val fallback = (vocabulary.size - 1).coerceAtLeast(0)
                Log.w(TAG, "blank_id not found in vocab or config.json - using vocabulary.size-1=$fallback")
                fallback
            }
        val blankLabel = vocabulary.getOrNull(blankId) ?: "<out-of-range>"
        Log.d(TAG, "Blank id: $blankId  ('$blankLabel')")

        val decOutNames = decSession!!.outputNames.toList()
        val jointOutInfo = decSession!!.outputInfo[decOutNames[0]]
        val jointDim = (jointOutInfo?.info as? ai.onnxruntime.TensorInfo)?.shape?.last()?.toInt() ?: 0
        numDurations = if (jointDim > blankId + 1) jointDim - (blankId + 1) else 0
        Log.d(TAG, "Joint output dim: $jointDim  numDurations: $numDurations")

        opts.close()
        isLoaded = true
        Log.d(TAG, "ParakeetEngine ready (modelDir=${modelDir.path})")
        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "ParakeetEngine loaded in ${elapsed}ms")
        logMemoryUsage()
    }

    /**
     * Transcribes [chunk] synchronously and returns a [TranscriptResult].
     * This is a **blocking** call - always dispatch off the main thread.
     */
    override fun transcribe(chunk: AudioChunk): TranscriptResult {
        if (!isLoaded) return TranscriptResult.Failure(IllegalStateException("Engine not loaded"))
        return try {
            val e = env!!
            val enc = encSession!!
            val dec = decSession!!

            val samples = normalizePcm(chunk.samples)
            val (feats, featLen) = preprocess(e, samples)
            val (encOut, encLen) = encode(e, enc, feats, featLen)
            feats.close()

            val decode = decodeRange(e, dec, encOut, encLen, 0, encLen, initialTdtState())
            encOut.close()

            val text = detokenize(decode.tokens)
            if (text.isBlank()) TranscriptResult.Partial("") else TranscriptResult.Final(text, confidence = decode.confidence)
        } catch (ex: Exception) {
            Log.e(TAG, "transcribe() failed", ex)
            TranscriptResult.Failure(ex)
        }
    }

    companion object {
        const val TOP_K_TOKENS = WordBeamTuning.TOP_K_TOKENS
        const val BEAM_WIDTH = WordBeamTuning.BEAM_WIDTH
        const val MAX_BEAM_STEPS = WordBeamTuning.MAX_BEAM_STEPS
        const val MAX_ALTERNATIVES = WordBeamTuning.MAX_ALTERNATIVES
    }

    override fun initialTdtState(): TdtState = TdtState(FloatArray(2 * 640), FloatArray(2 * 640), blankId)

    override fun encodeBuffer(samples: FloatArray): Pair<OnnxTensor, Int> {
        check(isLoaded) { "Engine not loaded; call load() first" }
        val e = env!!
        val (feats, featLen) = preprocess(e, samples)
        val (encOut, encLen) = encode(e, encSession!!, feats, featLen)
        feats.close()
        return encOut to encLen
    }

    override fun decodeChunk(
        encoderOut: OnnxTensor,
        totalLength: Int,
        frameStart: Int,
        frameEnd: Int,
        state: TdtState,
    ): ChunkDecodeResult {
        check(isLoaded) { "Engine not loaded; call load() first" }
        val result = decodeRange(env!!, decSession!!, encoderOut, totalLength, frameStart, frameEnd, state)
        return ChunkDecodeResult(
            result.tokens, result.state, result.logProbSum, result.emissionCount,
            result.emissions, result.stateSnapshots,
        )
    }

    override fun detokenizeTokens(tokens: List<Int>): String = detokenize(tokens)

    override fun tokenStartsWord(tokenId: Int): Boolean {
        if (tokenId < 0 || tokenId >= vocabulary.size || tokenId == blankId) return false
        return vocabulary[tokenId].startsWith("▁")
    }

    override fun localWordBeam(
        encoderOut: OnnxTensor,
        totalLength: Int,
        startFrame: Int,
        endFrame: Int,
        initialState: FrameState,
        beamWidth: Int,
        topK: Int,
        maxSteps: Int,
        maxAlternatives: Int,
    ): List<WordAlternative> {
        check(isLoaded) { "Engine not loaded; call load() first" }
        val e = env!!
        val session = decSession!!

        val encShape = encoderOut.info.shape
        val encDim = encShape[1].toInt()
        val encData = FloatArray(encoderOut.floatBuffer.remaining())
        encoderOut.floatBuffer.rewind()
        encoderOut.floatBuffer.get(encData)

        val stateShape = longArrayOf(2L, 1L, 640L)
        val decOutputNames = session.outputNames.toList()

        class BeamState(
            val frame: Int,
            val prevToken: Int,
            val lstm1: FloatArray,
            val lstm2: FloatArray,
            val tokens: IntArray,
            val logProb: Double,
        )

        val start = BeamState(startFrame, initialState.prevToken,
            initialState.lstmState1, initialState.lstmState2, IntArray(0), 0.0)
        var live = listOf(start)
        val finished = HashMap<String, Double>()
        var steps = 0

        class JointStep(
            val topTokens: List<EmissionToken>,
            val duration: Int,
            val lstm1: FloatArray,
            val lstm2: FloatArray,
        )

        fun jointCall(frame: Int, prevToken: Int, lstm1: FloatArray, lstm2: FloatArray): JointStep {
            val frameData = FloatArray(encDim) { d -> encData[d * totalLength + frame] }
            val frameTensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(frameData), longArrayOf(1L, encDim.toLong(), 1L))
            val targetTensor = OnnxTensor.createTensor(e, IntBuffer.wrap(intArrayOf(prevToken)), longArrayOf(1L, 1L))
            val targetLenTensor = OnnxTensor.createTensor(e, IntBuffer.wrap(intArrayOf(1)), longArrayOf(1L))
            val statesTensor1 = OnnxTensor.createTensor(e, FloatBuffer.wrap(lstm1), stateShape)
            val statesTensor2 = OnnxTensor.createTensor(e, FloatBuffer.wrap(lstm2), stateShape)
            val inputs = mapOf(
                Names.DEC_IN_ENC_OUT to frameTensor,
                Names.DEC_IN_TARGETS to targetTensor,
                Names.DEC_IN_TARGET_LEN to targetLenTensor,
                Names.DEC_IN_STATES_1 to statesTensor1,
                Names.DEC_IN_STATES_2 to statesTensor2,
            )
            try {
                session.run(inputs).use { result ->
                    val logitsTensor = result.get(decOutputNames[0]).get() as OnnxTensor
                    val logits = FloatArray(logitsTensor.floatBuffer.remaining())
                    logitsTensor.floatBuffer.get(logits)

                    val maxLogit = (0..blankId).maxOf { logits[it] }
                    var expSum = 0.0
                    val topIdx = IntArray(topK)
                    val topVal = DoubleArray(topK) { Double.NEGATIVE_INFINITY }
                    for (k in 0..blankId) {
                        val v = logits[k].toDouble()
                        expSum += Math.exp(v - maxLogit)
                        if (v > topVal[topK - 1]) {
                            var j = topK - 1
                            while (j > 0 && v > topVal[j - 1]) {
                                topVal[j] = topVal[j - 1]
                                topIdx[j] = topIdx[j - 1]
                                j--
                            }
                            topVal[j] = v
                            topIdx[j] = k
                        }
                    }
                    val logExpSum = Math.log(expSum)
                    val top = (0 until topK)
                        .map { i -> EmissionToken(topIdx[i], topVal[i] - maxLogit - logExpSum) }

                    val nDur = numDurations
                    val duration = if (nDur > 0) {
                        val durBase = blankId + 1
                        (0 until nDur.coerceAtLeast(1)).maxByOrNull { logits[durBase + it] } ?: 0
                    } else 0

                    val s1 = result.get(decOutputNames[2]).get() as OnnxTensor
                    val lstm1Out = FloatArray(s1.floatBuffer.remaining()).also { s1.floatBuffer.get(it) }
                    val s2 = result.get(decOutputNames[3]).get() as OnnxTensor
                    val lstm2Out = FloatArray(s2.floatBuffer.remaining()).also { s2.floatBuffer.get(it) }

                    return JointStep(top, duration, lstm1Out, lstm2Out)
                }
            } finally {
                frameTensor.close()
                targetTensor.close()
                targetLenTensor.close()
                statesTensor1.close()
                statesTensor2.close()
            }
        }

        while (live.isNotEmpty() && steps < maxSteps) {
            val next = ArrayList<BeamState>(beamWidth * 2)
            for (bs in live) {
                if (bs.frame > endFrame) continue
                if (++steps >= maxSteps) break
                val step = jointCall(bs.frame, bs.prevToken, bs.lstm1, bs.lstm2)

                if (bs.tokens.isNotEmpty()) {
                    val word = detokenize(bs.tokens.toList())
                    if (word.isNotBlank()) {
                        val norm = bs.logProb / bs.tokens.size
                        if (norm > finished.getOrDefault(word, Double.NEGATIVE_INFINITY)) {
                            finished[word] = norm
                        }
                    }
                }

                for (tok in step.topTokens) {
                    if (tok.token == blankId) continue
                    val newFrame = if (step.duration > 0) bs.frame + step.duration else bs.frame
                    if (newFrame > endFrame) continue
                    val newTokens = bs.tokens.copyOf(bs.tokens.size + 1).also { it[bs.tokens.size] = tok.token }
                    next.add(
                        BeamState(newFrame, tok.token, step.lstm1, step.lstm2, newTokens,
                            bs.logProb + tok.logProb)
                    )
                }
            }
            live = next.sortedByDescending { it.logProb }.take(beamWidth)
        }

        return finished.entries
            .sortedByDescending { it.value }
            .take(maxAlternatives)
            .map { (word, lp) -> WordAlternative(word, lp.toFloat()) }
    }

    override fun close() {
        prepSession?.close(); prepSession = null
        encSession?.close(); encSession = null
        decSession?.close(); decSession = null
        env?.close(); env = null
        isLoaded = false
        Log.d(TAG, "ParakeetEngine closed")
        logMemoryUsage()
    }

    private fun normalizePcm(pcm: ShortArray): FloatArray {
        val out = FloatArray(pcm.size)
        for (i in pcm.indices) out[i] = pcm[i] / 32_768f
        return out
    }

    private fun preprocess(env: OrtEnvironment, samples: FloatArray): Pair<OnnxTensor, Long> {
        val audioLen = samples.size.toLong()
        val prep = prepSession ?: run {
            Log.w(TAG, "No preprocessor - forwarding raw audio (shapes will mismatch)")
            return OnnxTensor.createTensor(
                env, FloatBuffer.wrap(samples), longArrayOf(1L, audioLen)
            ) to audioLen
        }

        val prepOutputNames = prep.outputNames.toList()
        val audioTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(samples), longArrayOf(1L, audioLen)
        )
        val lenTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(audioLen)), longArrayOf(1L)
        )
        val inputs = mapOf(Names.PREP_IN_AUDIO to audioTensor, Names.PREP_IN_LENGTH to lenTensor)

        return prep.run(inputs).use { result ->
            audioTensor.close(); lenTensor.close()
            val featTensor = result.get(prepOutputNames[0]).get() as OnnxTensor
            val featLen = featTensor.info.shape[2]
            cloneTensor(env, featTensor) to featLen
        }
    }

    private fun encode(
        env: OrtEnvironment,
        session: OrtSession,
        features: OnnxTensor,
        featLen: Long,
    ): Pair<OnnxTensor, Int> {
        val lenTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(featLen)), longArrayOf(1L)
        )
        val inputs = mapOf(Names.ENC_IN_SIGNAL to features, Names.ENC_IN_LENGTH to lenTensor)

        return session.run(inputs).use { result ->
            lenTensor.close()
            val outTensor = result.get(Names.ENC_OUT_SIGNAL)
                .orElseThrow { RuntimeException("Encoder output '${Names.ENC_OUT_SIGNAL}' not found") }
                    as OnnxTensor
            val lenOut = result.get(Names.ENC_OUT_LEN)
                .orElseThrow { RuntimeException("Encoder output '${Names.ENC_OUT_LEN}' not found") }
                    as OnnxTensor
            val encLen = lenOut.longBuffer[0].toInt()
            cloneTensor(env, outTensor) to encLen
        }
    }

    /**
     * Greedy TDT decoder.
     *
     * Encoder output layout: [batch, enc_dim=1024, enc_time]  <- (B, D, T) NOT (B, T, D)
     *
     * TDT advance rule:
     *   blank:     t += max(1, predictedDuration)
     *   non-blank: emit token; t += predictedDuration (0 = stay at same frame)
     */
    private fun decodeRange(
        env: OrtEnvironment,
        session: OrtSession,
        encoderOut: OnnxTensor,
        totalLength: Int,
        frameStart: Int,
        frameEnd: Int,
        state: TdtState,
    ): DecodeRangeResult {
        val encShape = encoderOut.info.shape
        val encDim = encShape[1].toInt()

        val encData = FloatArray(encoderOut.floatBuffer.remaining())
        encoderOut.floatBuffer.rewind()
        encoderOut.floatBuffer.get(encData)

        val stateShape = longArrayOf(2L, 1L, 640L)
        var lstmState1 = state.lstmState1.copyOf()
        var lstmState2 = state.lstmState2.copyOf()

        val decOutputNames = session.outputNames.toList()

        val hypothesis = mutableListOf<Int>()
        val emissions = mutableListOf<TokenEmission>()
        val stateSnapshots = mutableListOf<FrameState>()
        var logProbSum = 0.0
        var nonBlankEmissions = 0
        var prevToken = state.prevToken
        var t = frameStart
        var maxIter = (frameEnd - frameStart) * 20 + 50
        var tokensAtFrame = 0
        val maxTokensPerFrame = 30
        val maxHypothesis = 2000

        while (t < frameEnd && maxIter-- > 0 && hypothesis.size < maxHypothesis) {
            val preLstm1 = lstmState1
            val preLstm2 = lstmState2
            val preToken = prevToken
            val frameData = FloatArray(encDim) { d -> encData[d * totalLength + t] }

            val frameTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(frameData), longArrayOf(1L, encDim.toLong(), 1L)
            )
            val targetTensor = OnnxTensor.createTensor(
                env, IntBuffer.wrap(intArrayOf(prevToken)), longArrayOf(1L, 1L)
            )
            val targetLenTensor = OnnxTensor.createTensor(
                env, IntBuffer.wrap(intArrayOf(1)), longArrayOf(1L)
            )
            val statesTensor1 = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(lstmState1), stateShape
            )
            val statesTensor2 = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(lstmState2), stateShape
            )

            val inputs = mapOf(
                Names.DEC_IN_ENC_OUT to frameTensor,
                Names.DEC_IN_TARGETS to targetTensor,
                Names.DEC_IN_TARGET_LEN to targetLenTensor,
                Names.DEC_IN_STATES_1 to statesTensor1,
                Names.DEC_IN_STATES_2 to statesTensor2,
            )

            try {
                session.run(inputs).use { result ->
                    val logitsTensor = result.get(decOutputNames[0]).get() as OnnxTensor
                    val logits = FloatArray(logitsTensor.floatBuffer.remaining())
                    logitsTensor.floatBuffer.get(logits)

                    val predictedToken = (0..blankId).maxByOrNull { logits[it] } ?: blankId

                    if (predictedToken != blankId) {
                        val maxLogit = (0..blankId).maxOf { logits[it] }
                        var expSum = 0.0
                        val topIdx = IntArray(TOP_K_TOKENS)
                        val topVal = DoubleArray(TOP_K_TOKENS) { Double.NEGATIVE_INFINITY }
                        for (k in 0..blankId) {
                            val v = logits[k].toDouble()
                            expSum += Math.exp(v - maxLogit)
                            if (v > topVal[TOP_K_TOKENS - 1]) {
                                var j = TOP_K_TOKENS - 1
                                while (j > 0 && v > topVal[j - 1]) {
                                    topVal[j] = topVal[j - 1]
                                    topIdx[j] = topIdx[j - 1]
                                    j--
                                }
                                topVal[j] = v
                                topIdx[j] = k
                            }
                        }
                        val logExpSum = Math.log(expSum)
                        val topTokens = (0 until TOP_K_TOKENS)
                            .map { i -> EmissionToken(topIdx[i], topVal[i] - maxLogit - logExpSum) }
                        val logSoftmaxArgmax = topVal[0] - maxLogit - logExpSum
                        logProbSum += logSoftmaxArgmax
                        nonBlankEmissions++
                        emissions.add(TokenEmission(predictedToken, t, logSoftmaxArgmax, topTokens))
                        stateSnapshots.add(FrameState(t, preLstm1, preLstm2, preToken))
                    }

                    val nDur = numDurations
                    val predictedDur = if (nDur > 0) {
                        val durBase = blankId + 1
                        (0 until nDur.coerceAtLeast(1)).maxByOrNull { logits[durBase + it] } ?: 0
                    } else 0

                    if (predictedToken != blankId) {
                        if (decOutputNames.size > 2) {
                            val s1 = result.get(decOutputNames[2]).get() as OnnxTensor
                            lstmState1 = FloatArray(s1.floatBuffer.remaining()).also { s1.floatBuffer.get(it) }
                        }
                        if (decOutputNames.size > 3) {
                            val s2 = result.get(decOutputNames[3]).get() as OnnxTensor
                            lstmState2 = FloatArray(s2.floatBuffer.remaining()).also { s2.floatBuffer.get(it) }
                        }
                    }

                    if (predictedToken == blankId) {
                        t += maxOf(1, predictedDur)
                        tokensAtFrame = 0
                    } else {
                        hypothesis.add(predictedToken)
                        prevToken = predictedToken
                        tokensAtFrame++

                        if (predictedDur > 0) {
                            t += predictedDur
                            tokensAtFrame = 0
                        } else if (tokensAtFrame >= maxTokensPerFrame) {
                            Log.w(TAG, "decodeRange: stuck at frame $t - forcing advance")
                            t++
                            tokensAtFrame = 0
                        }
                    }
                }
            } finally {
                frameTensor.close()
                targetTensor.close()
                targetLenTensor.close()
                statesTensor1.close()
                statesTensor2.close()
            }
        }

        val confidence = if (nonBlankEmissions > 0) {
            Math.exp(logProbSum / nonBlankEmissions).toFloat().coerceIn(0f, 1f)
        } else 1.0f
        val frameDelta = t - frameEnd
        if (frameDelta < 0) {
            Log.w(TAG, "decodeRange: terminated $frameDelta frame(s) before frameEnd=$frameEnd (t=$t) - safety cap hit?")
        }
        return DecodeRangeResult(
            hypothesis,
            TdtState(lstmState1, lstmState2, prevToken, frameDelta),
            confidence,
            logProbSum,
            nonBlankEmissions,
            emissions,
            stateSnapshots,
        )
    }

    /**
     * Converts token IDs to a string using [vocabulary]. Handles SentencePiece word-boundary
     * markers (U+2581 ▁) and NeMo's habit of filling unused vocab slots with their own index
     * as the token string (e.g. "7883", "▁1980ess") -- strips any leading digit run.
     */
    private fun detokenize(tokenIds: List<Int>): String {
        if (tokenIds.isEmpty()) return ""
        val raw = buildString {
            for (id in tokenIds) {
                if (id < 0 || id >= vocabulary.size || id == blankId) continue
                val token = vocabulary[id]
                val bare = token.removePrefix("▁")
                val effective = bare.dropWhile { it.isDigit() }
                if (effective.isBlank()) continue
                when {
                    token.startsWith("▁") -> {
                        if (isNotEmpty()) append(' ')
                        append(effective)
                    }
                    else -> append(effective)
                }
            }
        }
        return raw
            .replace(Regex(" ([.,!?;:])"), "$1")
            .replace(Regex(" {2,}"), " ")
            .trim()
    }

    private fun cloneTensor(env: OrtEnvironment, source: OnnxTensor): OnnxTensor {
        val shape = source.info.shape
        val data = FloatArray(source.floatBuffer.remaining())
        source.floatBuffer.rewind()
        source.floatBuffer.get(data)
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
    }

    private fun logSession(label: String, session: OrtSession) {
        Log.d(TAG, "=== $label inputs ===")
        session.inputNames.forEach { n -> Log.d(TAG, "  [$n] ${session.inputInfo[n]}") }
        Log.d(TAG, "=== $label outputs ===")
        session.outputNames.forEach { n -> Log.d(TAG, "  [$n] ${session.outputInfo[n]}") }
    }

    private fun scanVocabForBlankId(): Int? {
        val blankLabels = setOf("<blk>", "<blank>", "[blank]", "<eps>")
        vocabulary.forEachIndexed { idx, token ->
            if (token in blankLabels) {
                Log.d(TAG, "Detected blank token '$token' at index $idx from vocabulary scan")
                return idx
            }
        }
        return null
    }

    private fun parseBlankId(configFile: File): Int? {
        if (!configFile.exists()) return null
        return runCatching {
            val json = JSONObject(configFile.readText())
            if (json.has("blank_id")) return@runCatching json.getInt("blank_id")
            json.optJSONObject("model_defaults")?.let {
                if (it.has("blank_id")) return@runCatching it.getInt("blank_id")
            }
            json.optJSONObject("decoder")?.let {
                if (it.has("blank_id")) return@runCatching it.getInt("blank_id")
            }
            json.optJSONObject("tokenizer")?.let {
                if (it.has("blank_id")) return@runCatching it.getInt("blank_id")
            }
            null
        }.getOrNull()
    }

    private fun logMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val usedMemMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMemMB = runtime.maxMemory() / (1024 * 1024)
        Log.i(TAG, "ParakeetEngine memory: used=${usedMemMB}MB, max=${maxMemMB}MB")
        val debugMem = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugMem)
        Log.i(
            TAG,
            "Debug memory: dalvik=${debugMem.dalvikPrivateDirty}KB, native=${debugMem.nativePrivateDirty}KB, totalPss=${debugMem.totalPss}KB"
        )
    }
}
