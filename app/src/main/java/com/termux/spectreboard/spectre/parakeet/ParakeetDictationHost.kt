// Ported from Outspoke (github.com/minburg/outspoke, GPLv3) ime/OutspokeInputMethodService.kt
// (non-UI glue: InferenceService binding + TextInjector lifecycle + capture driving), with the
// InputMethodService shell replaced by SpectreBoard's LatinIME as the host.
package com.termux.spectreboard.spectre.parakeet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.termux.spectreboard.latin.R
import com.termux.spectreboard.spectre.AcousticSuggestions
import com.termux.spectreboard.spectre.parakeet.correction.SuggestionFileManager
import com.termux.spectreboard.spectre.parakeet.preferences.AppPreferences
import com.termux.spectreboard.spectre.parakeet.ui.KeyboardUiState
import com.termux.spectreboard.spectre.parakeet.ui.KeyboardViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "ParakeetHost"
private const val NOTIF_ID = 0xD1C8
private const val CHANNEL = "parakeet_dictation"

/**
 * Hosts the ported Outspoke dictation plumbing inside SpectreBoard's [LatinIME] (which plays
 * the role Outspoke's own IME played): binds [InferenceService], drives the [KeyboardViewModel]
 * capture pipeline, and creates a [TextInjector] per input field.
 */
class ParakeetDictationHost(private val context: Context) {

    private val audioCaptureManager = AudioCaptureManager(context)
    private val appPreferences = AppPreferences(context)
    private val wordSuggestionProvider = WordSuggestionProvider(context)
    val viewModel = KeyboardViewModel(audioCaptureManager, appPreferences, wordSuggestionProvider)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var inferenceBinder: InferenceService.InferenceBinder? = null
    private var isBound = false
    private var engineStateCollector: Job? = null

    /** Called whenever recording toggles, so LatinIME can refresh the toolbar button state. */
    var onStateChange: (() -> Unit)? = null

    /**
     * True while Parakeet is actively writing to the input field: capturing audio, waiting
     * on a partial, or still finalising the last utterance after the mic was released
     * ([KeyboardUiState.Transcribing] — a slow decode can take several seconds after
     * capture stops). [TextInjector] drives the field directly via the raw
     * [android.view.inputmethod.InputConnection], bypassing [InputLogic]'s own
     * `RichInputConnection` cursor tracking, so [InputLogic]'s cursor-position cache can go
     * stale for the entire span this is true. Callers that would otherwise act on that
     * cached position (e.g. resuming suggestions on "the word touched by cursor") must bail
     * out while this is true, or risk operating on a stale offset — see
     * `InputLogic.restartSuggestionsOnWordTouchedByCursor`.
     */
    val isDictationActive: Boolean
        get() = when (viewModel.uiState.value) {
            is KeyboardUiState.Listening,
            is KeyboardUiState.Processing,
            is KeyboardUiState.Transcribing -> true
            else -> false
        }

    private fun detachFromBinder() {
        engineStateCollector?.cancel()
        engineStateCollector = null
        inferenceBinder = null
        viewModel.setInferenceRepository(null)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val b = binder as InferenceService.InferenceBinder
            inferenceBinder = b
            engineStateCollector?.cancel()
            engineStateCollector = scope.launch {
                b.getEngineState().collect { state ->
                    viewModel.setEngineState(state)
                    viewModel.setInferenceRepository(if (state == EngineState.Ready) b.getRepository() else null)
                }
            }
            viewModel.setInferenceRepository(b.getRepository())
            Log.d(TAG, "InferenceService connected - engine state: ${b.getEngineState().value}")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "InferenceService disconnected unexpectedly - rebinding")
            detachFromBinder()
            viewModel.setEngineState(EngineState.Loading)
            context.bindService(
                Intent(context, InferenceService::class.java), this, Context.BIND_AUTO_CREATE
            )
        }
    }

    fun bind() {
        if (isBound) return
        wordSuggestionProvider.acousticLookup = { word ->
            inferenceBinder?.getRepository()?.getAcousticAlternatives(word) ?: emptyList()
        }
        viewModel.onMissingRepo = {
            inferenceBinder?.reloadIfNeeded()
            if (!isBound) bind()
        }
        context.bindService(
            Intent(context, InferenceService::class.java), serviceConnection, Context.BIND_AUTO_CREATE
        )
        isBound = true
        Log.d(TAG, "InferenceService bind requested")
    }

    /**
     * Kicks off a background engine load if it isn't already warm — call from
     * [android.inputmethodservice.InputMethodService.onWindowShown] so the ~700 MB Parakeet
     * engine is reloading (or already loaded) by the time the user reaches for the mic
     * button, instead of only starting to reload reactively on the first post-unload
     * record-press ([viewModel]'s `onMissingRepo`, still kept as a safety net below).
     *
     * A no-op when the engine is already loaded/loading and not memory-unloaded — see
     * [InferenceService.InferenceBinder.reloadIfNeeded]. Safe to call on every window show;
     * cheap when there's nothing to do.
     */
    fun preloadIfNeeded() {
        if (!isBound) bind()
        inferenceBinder?.reloadIfNeeded()
    }

    fun onInputStart(connection: InputConnection, editorInfo: EditorInfo) {
        viewModel.setTextInjector(
            TextInjector(
                connection,
                editorInfo,
                displayCleanFn = { text, isSentenceStart ->
                    text.cleanTranscript(
                        isContinuation = !isSentenceStart,
                        language = viewModel.currentLanguage.value,
                        skipSpuriousPeriods = true,
                    )
                },
            )
        )
    }

    fun onInputFinish() {
        viewModel.commitPartialAndStop()
        viewModel.setTextInjector(null)
        AcousticSuggestions.armed = false
    }

    fun onFieldCleared() {
        viewModel.onFieldCleared()
        AcousticSuggestions.armed = false
    }

    private var isRecording = false

    /**
     * Loads the English word-corrector (dict + ARPA LM, both already staged on-device —
     * see scope.md; no in-app downloader for v1) and arms [AcousticSuggestions] so
     * SpectreBoard's native suggestion strip can surface acoustic candidates for words this
     * session dictates. Idempotent — [WordSuggestionProvider.setActiveLanguages]/`open()`
     * are both safe to call repeatedly, and re-arming is cheap.
     *
     * Called lazily, on the first record-start rather than from [bind], because
     * [InferenceService] has no `android:process` of its own — the corrector's ~50 MB
     * resident cost (a 55k-word dictionary + a 275k-bigram ARPA model, both preallocated as
     * hash maps) lands in the same process as the ~700 MB Parakeet models, so it's only
     * worth paying for users who actually dictate.
     */
    private fun armAcousticSuggestionsIfReady() {
        if (!SuggestionFileManager.isLanguageReady(context, "en")) return
        wordSuggestionProvider.setActiveLanguages(setOf("en"))
        wordSuggestionProvider.open()
        AcousticSuggestions.lookup = { word, leftContext ->
            wordSuggestionProvider.acousticSuggestionsFor(word, leftContext)
        }
        AcousticSuggestions.armed = true
    }

    fun onRecordToggle() {
        if (isRecording) {
            isRecording = false
            viewModel.onRecordStop()
            cancelNotification()
            onStateChange?.invoke()
        } else {
            isRecording = true
            armAcousticSuggestionsIfReady()
            viewModel.onRecordStart()
            showNotification()
            onStateChange?.invoke()
        }
    }

    fun unbind() {
        detachFromBinder()
        if (isBound) {
            try { context.unbindService(serviceConnection) } catch (_: Exception) {}
            isBound = false
        }
        wordSuggestionProvider.close()
        AcousticSuggestions.armed = false
        scope.cancel()
    }

    private val main = Handler(Looper.getMainLooper())
    private var notifProgress = 0
    private val notifTick = Runnable { tickNotification() }

    private fun tickNotification() {
        if (!isRecording) return
        // Fill 0..100 over ~10 s (100 ms tick), then wrap to 0 and fill again.
        notifProgress = (notifProgress + 1) % 101
        buildAndNotify(notifProgress)
        main.postDelayed(notifTick, 100)
    }

    private fun buildAndNotify(progress: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.sym_keyboard_voice_rounded)
            .setContentTitle("Dictating…")
            .setContentText("Tap mic again to stop")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(Color.RED)
            .setColorized(true)
            .setProgress(100, progress, false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    private fun showNotification() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Dictation", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                setSound(null, null)
            }
        )
        notifProgress = 0
        buildAndNotify(0)
        main.postDelayed(notifTick, 100)
    }

    private fun cancelNotification() {
        main.removeCallbacks(notifTick)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIF_ID)
    }
}
