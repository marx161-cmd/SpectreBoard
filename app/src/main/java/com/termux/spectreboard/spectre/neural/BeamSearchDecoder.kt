package com.termux.spectreboard.spectre.neural

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.PriorityQueue
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

class BeamSearchDecoder(
    private val ortEnv: OrtEnvironment,
    private val decoderSession: OrtSession,
    private val vocabTrie: VocabularyTrie?
) {
    data class Candidate(val word: String, val confidence: Float, val score: Float)

    private data class BeamState(
        val tokens: ArrayList<Int>,
        var score: Float,
        var finished: Boolean
    ) {
        constructor(startToken: Int, startScore: Float) : this(
            tokens = ArrayList(listOf(startToken)), score = startScore, finished = false
        )
        constructor(other: BeamState) : this(
            tokens = ArrayList(other.tokens), score = other.score, finished = other.finished
        )
    }

    fun search(memory: OnnxTensor, actualSrcLength: Int): List<Candidate> {
        val beamWidth = NeuralConfig.BEAM_WIDTH
        val maxLength = NeuralConfig.MAX_LENGTH
        val beams = ArrayList<BeamState>()
        beams.add(BeamState(NeuralConfig.SOS_IDX.toInt(), 0.0f))
        var step = 0

        while (step < maxLength) {
            val activeBeams = beams.filter { !it.finished }
            if (activeBeams.isEmpty()) break

            val candidates = ArrayList<BeamState>()
            candidates.addAll(beams.filter { it.finished }.map { BeamState(it) })

            val nextBeams = if (activeBeams.size > 1) {
                processBatched(activeBeams, memory, actualSrcLength)
            } else {
                processSingle(activeBeams[0], memory, actualSrcLength)
            }
            candidates.addAll(nextBeams)

            candidates.sortBy {
                val len = it.tokens.size.toFloat()
                val normFactor = (5.0f + len).pow(NeuralConfig.BEAM_ALPHA) / 6.0f.pow(NeuralConfig.BEAM_ALPHA)
                it.score / normFactor
            }

            if (step >= 2) candidates.removeIf { exp(-it.score) < 1e-6f }

            beams.clear()
            val seen = HashSet<List<Int>>()
            for (c in candidates) {
                if (beams.size >= beamWidth) break
                if (seen.add(c.tokens.toList())) beams.add(c)
            }

            if (beams.all { it.finished }) break
            step++
        }

        return beams.mapNotNull { toCandidate(it) }
    }

    private fun processSingle(beam: BeamState, memory: OnnxTensor, srcLen: Int): List<BeamState> {
        val candidates = ArrayList<BeamState>()
        val tgtTokens = IntArray(NeuralConfig.DECODER_SEQ_LEN) { NeuralConfig.PAD_IDX.toInt() }
        val len = min(beam.tokens.size, NeuralConfig.DECODER_SEQ_LEN)
        for (i in 0 until len) tgtTokens[i] = beam.tokens[i]

        val logits = runSingle(tgtTokens, memory, srcLen)
        val currentPos = beam.tokens.size - 1
        if (currentPos in 0 until NeuralConfig.DECODER_SEQ_LEN) {
            expandBeam(beam, logits[currentPos], candidates)
        }
        return candidates
    }

    private fun processBatched(beams: List<BeamState>, memory: OnnxTensor, srcLen: Int): List<BeamState> {
        val candidates = ArrayList<BeamState>()
        val numBeams = beams.size
        val seqLen = NeuralConfig.DECODER_SEQ_LEN
        val vocabSize = NeuralConfig.VOCAB_SIZE

        val flatTokens = IntArray(numBeams * seqLen) { NeuralConfig.PAD_IDX.toInt() }
        for (b in beams.indices) {
            val beam = beams[b]
            val len = min(beam.tokens.size, seqLen)
            for (i in 0 until len) flatTokens[b * seqLen + i] = beam.tokens[i]
        }

        val logits = runBatched(flatTokens, numBeams, memory, srcLen, seqLen, vocabSize)
        for (b in beams.indices) {
            val beam = beams[b]
            val currentPos = beam.tokens.size - 1
            if (currentPos in 0 until seqLen) {
                val row = FloatArray(vocabSize)
                val offset = (b * seqLen + currentPos) * vocabSize
                for (i in 0 until vocabSize) row[i] = logits[offset + i]
                expandBeam(beam, row, candidates)
            }
        }
        return candidates
    }

    private fun runSingle(tokens: IntArray, memory: OnnxTensor, srcLen: Int): Array<FloatArray> {
        val seqLen = NeuralConfig.DECODER_SEQ_LEN
        val bb = ByteBuffer.allocateDirect(tokens.size * 4).apply { order(ByteOrder.nativeOrder()) }
        bb.asIntBuffer().put(tokens)
        val tgtTensor = OnnxTensor.createTensor(ortEnv, bb.asIntBuffer(), longArrayOf(1, seqLen.toLong()))
        val srcLenTensor = OnnxTensor.createTensor(ortEnv, intArrayOf(srcLen))

        return try {
            val result = decoderSession.run(mapOf(
                "memory" to memory,
                "target_tokens" to tgtTensor,
                "actual_src_length" to srcLenTensor
            ))
            val array = parseSingleOutput(result.first().value, seqLen)
            tgtTensor.close(); srcLenTensor.close(); result.close()
            array
        } catch (e: Exception) {
            tgtTensor.close(); srcLenTensor.close()
            throw e
        }
    }

    private fun runBatched(
        flatTokens: IntArray, numBeams: Int, memory: OnnxTensor, srcLen: Int, seqLen: Int, vocabSize: Int
    ): FloatArray {
        val bb = ByteBuffer.allocateDirect(flatTokens.size * 4).apply { order(ByteOrder.nativeOrder()) }
        bb.asIntBuffer().put(flatTokens)
        val tgtTensor = OnnxTensor.createTensor(ortEnv, bb.asIntBuffer(), longArrayOf(numBeams.toLong(), seqLen.toLong()))
        val srcLenTensor = OnnxTensor.createTensor(ortEnv, intArrayOf(srcLen))

        return try {
            val result = decoderSession.run(mapOf(
                "memory" to memory,
                "target_tokens" to tgtTensor,
                "actual_src_length" to srcLenTensor
            ))
            val flat = parseBatchedOutput(result.first().value, numBeams, seqLen, vocabSize)
            tgtTensor.close(); srcLenTensor.close(); result.close()
            flat
        } catch (e: Exception) {
            tgtTensor.close(); srcLenTensor.close()
            throw e
        }
    }

    private fun parseSingleOutput(output: Any, seqLen: Int): Array<FloatArray> {
        return when (output) {
            is Array<*> -> {
                val first = output.firstOrNull()
                when (first) {
                    is FloatArray -> Array(output.size) { i -> (output[i] as FloatArray).copyOf() }
                    is Array<*> -> Array(seqLen) { i -> (first[i] as FloatArray).copyOf() }
                    else -> error("Unsupported decoder output row: ${first?.javaClass}")
                }
            }
            else -> error("Unsupported decoder output: ${output.javaClass}")
        }
    }

    private fun parseBatchedOutput(output: Any, numBeams: Int, seqLen: Int, vocabSize: Int): FloatArray {
        val flat = FloatArray(numBeams * seqLen * vocabSize)
        when (output) {
            is FloatArray -> output.copyInto(flat, endIndex = min(output.size, flat.size))
            is Array<*> -> {
                var idx = 0
                for (b in 0 until min(numBeams, output.size)) {
                    val beam = output[b]
                    when (beam) {
                        is FloatArray -> {
                            beam.copyInto(flat, idx, endIndex = min(beam.size, flat.size - idx))
                            idx += beam.size
                        }
                        is Array<*> -> {
                            for (s in 0 until min(seqLen, beam.size)) {
                                val row = beam[s] as FloatArray
                                row.copyInto(flat, idx, endIndex = min(row.size, flat.size - idx))
                                idx += vocabSize
                            }
                        }
                        else -> error("Unsupported batched decoder beam: ${beam?.javaClass}")
                    }
                }
            }
            else -> error("Unsupported batched decoder output: ${output.javaClass}")
        }
        return flat
    }

    private fun expandBeam(beam: BeamState, logProbs: FloatArray, candidates: MutableList<BeamState>) {
        applyTrieMasking(beam, logProbs)
        val normalized = logSoftmax(logProbs)
        val topIndices = getTopK(normalized, NeuralConfig.BEAM_WIDTH)

        for (idx in topIndices) {
            if (idx == NeuralConfig.SOS_IDX.toInt() || idx == NeuralConfig.PAD_IDX.toInt()) continue
            val newBeam = BeamState(beam)
            newBeam.tokens.add(idx)
            newBeam.score += -normalized[idx]
            if (idx == NeuralConfig.EOS_IDX.toInt()) newBeam.finished = true
            candidates.add(newBeam)
        }
    }

    private fun applyTrieMasking(beam: BeamState, logits: FloatArray) {
        if (vocabTrie == null || vocabTrie.size == 0) return
        val partial = StringBuilder()
        for (token in beam.tokens) {
            if (token > NeuralConfig.EOS_IDX) {
                Tokenizer.tokenToChar(token.toLong())?.let { partial.append(it) }
            }
        }
        val prefix = partial.toString()
        val allowed = vocabTrie.getAllowedNextChars(prefix)
        val isWord = vocabTrie.isWord(prefix)

        for (i in logits.indices) {
            if (i == NeuralConfig.SOS_IDX.toInt() || i == NeuralConfig.PAD_IDX.toInt()) {
                logits[i] = Float.NEGATIVE_INFINITY; continue
            }
            if (i == NeuralConfig.EOS_IDX.toInt()) {
                if (!isWord) logits[i] = Float.NEGATIVE_INFINITY; continue
            }
            val c = Tokenizer.tokenToChar(i.toLong())
            if (c == null || !allowed.contains(c)) logits[i] = Float.NEGATIVE_INFINITY
        }
    }

    private fun logSoftmax(logits: FloatArray): FloatArray {
        val t = NeuralConfig.TEMPERATURE
        val scaled = if (t != 1.0f) FloatArray(logits.size) { i -> logits[i] / t } else logits
        var maxLogit = Float.NEGATIVE_INFINITY
        for (v in scaled) if (v > maxLogit) maxLogit = v
        var sumExp = 0.0f
        for (v in scaled) sumExp += exp(v - maxLogit)
        val logSumExp = maxLogit + ln(sumExp)
        return FloatArray(scaled.size) { i -> scaled[i] - logSumExp }
    }

    private fun getTopK(array: FloatArray, k: Int): IntArray {
        val actualK = min(k, array.size)
        val pq = PriorityQueue<Int>(actualK + 1) { a, b -> array[a].compareTo(array[b]) }
        for (i in array.indices) {
            if (array[i] == Float.NEGATIVE_INFINITY) continue
            pq.offer(i)
            if (pq.size > actualK) pq.poll()
        }
        val result = IntArray(pq.size)
        for (i in result.indices.reversed()) result[i] = pq.poll() ?: 0
        return result
    }

    private fun toCandidate(beam: BeamState): Candidate? {
        val word = StringBuilder()
        for (token in beam.tokens) {
            if (token <= NeuralConfig.EOS_IDX) continue
            Tokenizer.tokenToChar(token.toLong())?.let { word.append(it) }
        }
        val wordStr = word.toString()
        if (wordStr.isEmpty()) return null
        val len = wordStr.length.toFloat()
        val normFactor = (5.0f + len).pow(NeuralConfig.BEAM_ALPHA) / 6.0f.pow(NeuralConfig.BEAM_ALPHA)
        val confidence = exp(-(beam.score / normFactor))
        if (confidence < NeuralConfig.CONFIDENCE_THRESHOLD) return null
        return Candidate(wordStr, confidence, beam.score)
    }
}
