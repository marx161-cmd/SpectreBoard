// Ported from Outspoke (github.com/minburg/outspoke, GPLv3) audio/AudioCaptureManager.kt.
// Trimmed: mic-calibration (setPreferredDevice) dropped - not wired in SpectreBoard (scope.md).
package com.termux.spectreboard.spectre.parakeet

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import com.termux.spectreboard.latin.R
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.isActive
import java.util.concurrent.Executors

private const val TAG = "AudioCaptureManager"

/** 16 kHz mono - matches Parakeet V3's expected input format. */
private const val SAMPLE_RATE = 16_000

/** 30 ms window at 16 kHz = 480 samples per chunk. Matches Silero VAD's required frame size. */
private const val CHUNK_SAMPLES = 480

/**
 * Maximum silence frames pumped through the VAD during the trailing drain phase.
 * 20 frames x 30 ms = 600 ms - generously above [RMSVadFilter]'s 15-frame / 450 ms hangover.
 */
private const val HANGOVER_DRAIN_SAFETY_FRAMES = 20

/** dBFS level at which the waveform bar reads 0.0. */
private const val NOISE_FLOOR_DB = -60f

/** Display gain applied to the normalised waveform amplitude after the dB mapping. */
private const val WAVEFORM_DISPLAY_GAIN = 1.5f

/**
 * Dedicated single OS thread for the [AudioCaptureManager.startCapture] read loop, created
 * once and reused across recording sessions rather than borrowed from `Dispatchers.IO`'s
 * shared elastic pool.
 *
 * A shared-pool thread can resume this coroutine on a *different* thread after any
 * suspension point (e.g. inside `send()`), which would silently undo a one-time
 * [Process.setThreadPriority] call made at the top of the flow. A genuinely dedicated
 * thread guarantees every resumption lands back on the same, already-elevated thread —
 * see [Process.THREAD_PRIORITY_URGENT_AUDIO] at the top of `startCapture`'s `channelFlow`.
 */
private val AUDIO_CAPTURE_DISPATCHER =
    Executors.newSingleThreadExecutor { Thread(it, "ParakeetAudioCapture") }.asCoroutineDispatcher()

/**
 * Manages a single [AudioRecord] session and emits captured audio as a cold
 * [Flow]<[AudioChunk]>. Runs entirely on [Dispatchers.IO].
 */
class AudioCaptureManager(private val context: Context) {

    private val _amplitude = MutableStateFlow(0f)

    /**
     * Set to `true` by [stopCapture] to end the read loop on the next iteration.
     * Using a flag instead of coroutine cancellation lets the [Flow] complete normally
     * so that the inference layer can emit a final result before tearing down.
     */
    @Volatile
    private var stopRequested: Boolean = false

    /** Signals the active [startCapture] flow to exit its read loop and complete normally. */
    fun stopCapture() {
        stopRequested = true
    }

    /**
     * Normalised RMS amplitude of the most recently captured chunk, in the range [0.0, 1.0].
     * Resets to 0.0 when capture stops.
     */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    /**
     * Starts microphone capture and emits each 30 ms [AudioChunk] downstream.
     *
     * The flow is cold - a new [AudioRecord] is created per collection. The [AudioRecord]
     * is always released in the `finally` block, even if the collector cancels mid-stream.
     *
     * @throws SecurityException if [android.Manifest.permission.RECORD_AUDIO] is not granted.
     * @throws IllegalStateException if [AudioRecord] fails to initialise.
     */
    @SuppressLint("MissingPermission")
    fun startCapture(vadEnabled: Boolean = true, rawSource: Boolean = false): Flow<AudioChunk> =
        channelFlow {
            // Elevate this thread above the CPU-bound TDT decode work (Dispatchers.Default)
            // that runs concurrently while recording, so the read loop below never falls far
            // enough behind real-time to overrun AudioRecord's hardware ring buffer. Safe to
            // call every session: AUDIO_CAPTURE_DISPATCHER is one dedicated thread reused
            // across sessions, so this is a cheap no-op after the first call.
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (e: Exception) {
                Log.w(TAG, "setThreadPriority(URGENT_AUDIO) failed - continuing at default priority", e)
            }

            if (!PermissionHelper.hasRecordPermission(context)) {
                throw SecurityException(
                    "RECORD_AUDIO permission is not granted."
                )
            }

            stopRequested = false   // reset for this capture session

            // Only create a filter when VAD is enabled; null means pass-through (VAD disabled).
            val vad: VadFilter? = if (vadEnabled) {
                try {
                    val sileroBytes = context.resources.openRawResource(R.raw.silero_vad_v4).readBytes()
                    SileroVadFilter(modelBytes = sileroBytes, threshold = 0.3f)
                } catch (e: Exception) {
                    Log.w(TAG, "Silero VAD model not found in raw resources. Falling back to Energy VAD.", e)
                    RMSVadFilter(0.4f)
                }
            } else null.also { Log.d(TAG, "VAD disabled") }

            val minBufferBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            // Headroom against the read loop falling behind real-time under CPU contention
            // from the concurrent TDT decode (see AUDIO_CAPTURE_DISPATCHER/thread-priority
            // above) -- 16x the previous 2x-chunk headroom, ~1s of slack at this sample
            // rate/format instead of ~60ms, so an occasional scheduling delay drains from
            // this buffer instead of overrunning it and silently dropping audio.
            val bufferBytes = maxOf(minBufferBytes, CHUNK_SAMPLES * 2 * 2 * 16)

            val source = if (rawSource) MediaRecorder.AudioSource.UNPROCESSED
            else MediaRecorder.AudioSource.DEFAULT

            val recorder = AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )

            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord failed to initialise (state=${recorder.state})"
            }

            val buffer = ShortArray(CHUNK_SAMPLES)

            try {
                recorder.startRecording()
                Log.d(TAG, "AudioRecord started - chunk=$CHUNK_SAMPLES samples, buf=$bufferBytes bytes")

                while (currentCoroutineContext().isActive && !stopRequested) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    when {
                        read > 0 -> {
                            val samples = buffer.copyOf(read)
                            val chunk = AudioChunk(samples = samples)
                            val rms = calculateRms(chunk.samples)
                            _amplitude.value = normaliseAmplitude(rms)

                            val toSend = vad?.process(chunk, rms) ?: listOf(chunk)
                            for (c in toSend) {
                                send(c)
                            }
                        }

                        read == AudioRecord.ERROR_DEAD_OBJECT -> {
                            Log.e(TAG, "AudioRecord ERROR_DEAD_OBJECT - stopping capture")
                            return@channelFlow
                        }

                        read < 0 -> {
                            Log.w(TAG, "AudioRecord read returned error code: $read")
                        }
                        // read == 0: no data yet; continue the loop
                    }
                }

                // --> Trailing Audio Drain
                // When the user releases the record key, two sources of audio are still pending:
                //   1. Samples sitting in the AudioRecord hardware ring buffer not yet read.
                //   2. The VAD hangover window (up to 450 ms) that has not yet expired.
                // Draining both ensures the last word is never cut off mid-phoneme.

                if (stopRequested && currentCoroutineContext().isActive) {
                    // Phase 1: drain any remaining hardware buffer data (non-blocking reads).
                    var drained: Int
                    do {
                        drained = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                        if (drained > 0) {
                            val chunk = AudioChunk(samples = buffer.copyOf(drained))
                            val rms = calculateRms(chunk.samples)
                            val toSend = vad?.process(chunk, rms) ?: listOf(chunk)
                            for (c in toSend) {
                                send(c)
                            }
                        }
                    } while (drained > 0)
                    Log.d(TAG, "AudioRecord hardware buffer drained")

                    // Phase 2: if VAD is still in hangover/speech, feed silence frames until
                    // the hangover counter expires and VAD transitions back to SILENCE.
                    if (vad != null && vad.isSpeechActive) {
                        val silence = AudioChunk(ShortArray(CHUNK_SAMPLES))
                        var safetyFrames = HANGOVER_DRAIN_SAFETY_FRAMES
                        while (vad.isSpeechActive && safetyFrames-- > 0) {
                            val toSend = vad.process(silence, 0f)
                            for (c in toSend) {
                                send(c)
                            }
                        }
                        Log.d(TAG, "VAD hangover drain complete (framesLeft=$safetyFrames)")
                    }
                }

            } finally {
                vad?.flush()
                vad?.close()
                recorder.stop()
                recorder.release()
                _amplitude.value = 0f
                Log.d(TAG, "AudioRecord stopped and released")
            }
        }.flowOn(AUDIO_CAPTURE_DISPATCHER)

    /**
     * Computes the RMS amplitude of [samples] and normalises it to [0.0, 1.0] relative to
     * [Short.MAX_VALUE] (32 767).
     */
    private fun calculateRms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        val sumOfSquares = samples.fold(0.0) { acc, s -> acc + s.toDouble() * s.toDouble() }
        val rms = sqrt(sumOfSquares / samples.size)
        return (rms / Short.MAX_VALUE.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Returns the RMS for the waveform display, clamped to [0.0, 1.0].
     */
    private fun normaliseAmplitude(rms: Float): Float {
        if (rms <= 0f) return 0f
        val db = 20f * log10(rms)
        return ((db - NOISE_FLOOR_DB) / (0f - NOISE_FLOOR_DB) * WAVEFORM_DISPLAY_GAIN).coerceIn(0f, 1f)
    }

}
