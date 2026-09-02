# On-device Parakeet dictation for SpectreBoard — handoff for DeepSeek

Written 2026-09-02 by Claude after a scoping conversation with the user. This is
an execution handoff, not a design doc — the architecture decisions below were
made *with* the user; don't re-litigate them, just implement. If something here
turns out to be wrong once you're in the code, stop and flag it rather than
silently deciding differently (that's the standing rule for this project — see
`scope.md`, append-only, dated entries).

Read `scope.md` in this repo root first for the "why" (G5 NPU was tried and
genuinely abandoned — real capability ceiling in Google's beta compiler, not a
missing flag; see that file for the full evidence trail). This document is the
"what to actually build."

## 0. Repo state right now

- `git status --short` shows uncommitted changes in `KeyboardActionListenerImpl.kt`,
  `LatinIME.java`, `InputLogic.java`, `StreamDictation.kt`, plus untracked
  `scope.md` and `app/src/main/java/com/termux/spectreboard/spectre/parakeet/`.
- **`InputLogic.java` / `KeyboardActionListenerImpl.kt` / `StreamDictation.kt`
  changes are a real, live-verified bugfix from earlier tonight** (backspace
  refusing to delete dictated text, select+replace deleting everything —
  root cause: dictation left text in a live *composing* region that
  `WordComposer`/recorrection got confused by; fix: dictation now commits
  text directly, never composes). **Do not revert or lose this** while wiring
  the new mic-button routing into `KeyboardActionListenerImpl.kt` (§2b) — it's
  live and user-confirmed working. Full writeup: homelab memory
  `project_spectreboard.md`, dated entry "2026-09-01 — Dictation was
  corrupting typed text afterward."
- `LatinIME.java`'s diff is unrelated pre-existing uncommitted work (a
  Cybersyn/Diana keyboard-visibility publish fix, not from tonight, not part
  of this port). Leave it alone; commit separately whenever makes sense.
- `app/src/main/java/com/termux/spectreboard/spectre/parakeet/` currently has
  4 files Claude started porting **before** the scope conversation below
  happened, and **3 of them were trimmed down for a "v1 without
  InferenceRepository" plan that the user explicitly rejected** — see
  §2 below. `ParakeetEngine.kt` itself was NOT trimmed (full wholesale port,
  keep it, just diff it against source to be sure). The other three
  (`AudioChunk.kt`, `TranscriptResult.kt`, `SpeechEngine.kt`) need to be
  re-ported in full since `InferenceRepository` needs the fields that were
  cut.

## 1. Source material

- Outspoke (GPLv3, github.com/minburg/outspoke), already cloned locally at
  `~/builds/android/outspoke/`, package `dev.brgr.outspoke`.
- SpectreBoard is also GPLv3 (HeliBoard fork) — **confirmed license-compatible,
  this is a wholesale port, not a clean-room rewrite.** Keep a source
  attribution comment at the top of each ported file (see the existing
  `ParakeetEngine.kt` for the comment style already used).
- Model files (Parakeet-TDT-0.6B-v3, ONNX, int8, ORT-native dynamic
  quantization — `DynamicQuantizeLSTM`/`DynamicQuantizeLinear`, which ORT
  handles natively) already pulled and verified byte-identical to what
  Outspoke itself ships, at
  `~/homelab/iree-stack/GoogleBeta/outspoke-parakeet-v3/`:
  `encoder-model.int8.onnx` (652MB), `decoder_joint-model.int8.onnx` (18MB),
  `nemo128.onnx` (140KB, mel featurizer), `vocab.txt`, `config.json`.
  ~700MB total. **Do not re-download or re-derive these — they're already
  verified correct.**

## 2. Scope decisions (already made with the user — implement, don't re-decide)

### 2a. Port `InferenceRepository` fully, wholesale.
`~/builds/android/outspoke/app/src/main/kotlin/outspoke/inference/InferenceRepository.kt`
(2544 lines). This is most of why Outspoke feels as good as it does — VAD-driven
chunking, LSTM `TdtState` carry-over across chunks, per-utterance confidence
accumulation, acoustic word-alternative capture via `ParakeetEngine.localWordBeam`,
short-utterance decode-context retry, and an 8-step text cleanup pipeline
(filler removal, stutter collapse, number normalization, phrase dedup,
spurious-period filter, sentence caps, etc — `cleanTranscript`). User's explicit
call: port it fully, do not trim, do not defer to "a second pass." The
`GrammarCorrector`/`NoOpGrammarCorrector` dependency it takes — check what that
interface actually does; if it's a no-op-by-default hook Outspoke doesn't
currently use for anything real, port the interface but it's fine to keep it
a no-op in SpectreBoard too (don't invent new grammar-correction work that
wasn't asked for).

### 2b. Port the Audio/VAD/IME layer wholesale too — do NOT adapt StreamDictation.kt.
User's words: "this is not runnable with the per key autocorrect of the typing
engine because it doesn't type. we port the whole thing and glue it behind
spectreboard's normal mic button." Concretely:
- `audio/AudioCaptureManager.kt` — 16kHz mono PCM, 480-sample (30ms) chunks,
  `AudioRecord` DEFAULT source, cold `Flow<AudioChunk>`, silence-boundary
  sentinels.
- `audio/VadFilter.kt` + `SileroVadFilter.kt` (Silero v4 ONNX, RNN state
  carried across chunks) + `RMSVadFilter.kt` (energy fallback).
- `ime/TextInjector.kt` + `TranscriptAligner.kt` — composing-span management
  (last 6 words mutable), three-layer overlap alignment, `WindowTrimmed`
  re-anchoring.

This runs as its **own separate pipeline** alongside SpectreBoard's existing
`StreamDictation.kt` (the comrade-Whisper path) — not a replacement, not a
shared/merged code path. Two independent mic-capture pipelines is the
accepted tradeoff for "as good as Outspoke actually is" over one shared but
compromised pipeline. `AudioChunk`/`TranscriptResult`/`SpeechEngine` in the
existing `spectre/parakeet/` dir were trimmed for a plan that's now rejected —
re-port them in full (with `timestampMs`/`isSilenceBoundary` on `AudioChunk`,
`WindowTrimmed`/`NoSpeech` on `TranscriptResult`, since `InferenceRepository`
needs them) rather than extending the trimmed versions.

**UI entry point:** the existing SpectreBoard toolbar mic button (the one that
currently triggers `StreamDictation.toggle()` for the comrade path — see
`KeyboardActionListenerImpl.toggleDictation()`). Needs a way to route to
*either* the comrade-Whisper path or the new on-device Parakeet path — exact
UX (a setting? a long-press to switch? a second button?) wasn't decided in
the scoping conversation; use judgement or ask, don't just silently pick one
and wire over the existing button's meaning.

### 2c. Register SpectreBoard as a system voice-input provider using Parakeet — like Gboard does.
This is **not** a new concept for this repo — `SpectreRecognitionService.kt`
(`app/src/main/java/com/termux/spectreboard/spectre/SpectreRecognitionService.kt`,
57 lines) already exists and is already registered in the manifest with
`BIND_RECOGNITION_SERVICE`, already selectable via Settings → Voice input,
currently routes to `StreamDictation`/comrade-Whisper. Wire the new on-device
Parakeet `InferenceRepository` in there too (as an alternative recognition
path, same service or a sibling one — your call on the cleanest shape) so
SpectreBoard's voice input is available system-wide (any app's mic button),
not just inside SpectreBoard's own keyboard. This is exactly what "register
as dictation tool same way Gboard does" means in Android terms — Gboard's
voice typing is *also* just a `RecognitionService` registration, there's no
separate special OS mechanism.

### 2d. Hot-loading / keeping the ~700MB engine resident — needs real investigation, not a copy-paste of Outspoke's pattern.
Outspoke (a normal third-party app) solved this with `InferenceService.kt`
(`~/builds/android/outspoke/app/src/main/kotlin/outspoke/inference/InferenceService.kt`,
423 lines): a foreground `LifecycleService` + persistent low-priority
notification (keeps the process alive), stays bound across keyboard
hide/show and brief app switches, cooperatively closes the engine only on
real OS memory pressure (`TRIM_MEMORY_RUNNING_LOW`/`RUNNING_CRITICAL`/
`MODERATE`/`COMPLETE` — deliberately *not* on `UI_HIDDEN`/`BACKGROUND`,
which fire on every keyboard hide and would defeat the point), reloads
lazily on next `onWindowShown`. `ModelStorageManager`/`FileObserver` watches
for model files appearing/disappearing.

**The user's specific concern: SpectreBoard is a system-signed app
(`android.uid.system`, platform-signed) and Android's process-importance/LMK
treatment of system apps is genuinely different from a normal third-party
app's.** Confirmed tonight: SpectreBoard's manifest currently declares
`android:persistent="true"`. Do not assume — investigate before deciding:
1. What importance level does Android actually give the *currently selected
   IME's* process while it's the active input method (independent of the
   keyboard view being visible)? If it's already protected at a
   comparable-or-better level than a foreground service + notification would
   give a normal app, Outspoke's whole apparatus may be partially redundant.
2. Is `android:persistent="true"` (system-app-only, unavailable to Outspoke,
   available to SpectreBoard) a cleaner, more reliable answer than
   replicating the foreground-service dance? What are its actual downsides
   (e.g., does it prevent the OS from ever reclaiming memory even under
   real pressure, risking a full-device OOM instead of a cooperative
   model-unload)?
3. Whatever the answer, the *cooperative unload under real memory pressure*
   behavior (via `ComponentCallbacks2`/`onTrimMemory`) is worth keeping
   regardless of the persistence mechanism chosen — a 700MB model has no
   business refusing to ever let go under `RUNNING_CRITICAL`.

Don't guess on this — read how Android's `ActivityManagerService` treats
`persistent` + current-IME process importance, or test it empirically on
the actual Pixel 10 Pro, before committing to an architecture. Report back
what you find before or alongside implementing it.

## 3. Build integration

- `onnxruntime-android:1.20.0` already a SpectreBoard dependency
  (`app/build.gradle.kts:164`) — Outspoke uses 1.29.0, a minor gap. Don't
  bump unless something in the ported code actually needs a newer API;
  `GruScorer`/`KenLmScorer` already depend on the existing ORT version,
  don't destabilize those.
- Model files (~700MB) are **not bundled in the APK** — pushed to the app's
  private storage at runtime, matching both Outspoke's own
  download-on-first-use pattern and SpectreBoard's existing DE/CE
  model-storage conventions (see homelab memory `project-spectreboard.md`'s
  "DE-vs-CE trap" notes — this app is
  `DEFAULT_TO_DEVICE_PROTECTED_STORAGE`, models must land in
  `/data/user_de/0/com.termux.spectreboard/files/`, not the CE path, chmod
  644 after any root cp). For now (dev/test), push the already-downloaded
  files from comrade via `adb push` / the `su -c base64` pattern used
  tonight (plain `adb push` triggered LF→CRLF corruption on this device's
  root path even through `exec-out` — confirmed empirically tonight, use
  base64 round-trip or plain `adb push` to a location the app can read
  without `su`, verify with `sha256sum` after). A real in-app download flow
  (`ModelDownloadManager`-equivalent, SHA-256 verified from HF) is out of
  scope for a first working version — ask before building that out.

## 4. What "done" looks like for a first real milestone

Not full feature parity with Outspoke on day one — but per the user's stated
bar ("I want it to actually be as good as it is now in that app"), the
target is the *whole* pipeline working end-to-end on-device: mic → VAD →
streaming Parakeet decode → cleaned-up text → composing/committed into
SpectreBoard's input connection, accessible from both the toolbar mic button
and system-wide voice input, with the engine staying warm across normal
keyboard use. Confidence gating, word-alternative correction, and the text
cleanup pipeline should all be live, not stubbed — that's the whole point of
2a above.

## 5. Questions to bring back rather than silently resolve

- Exact UX for routing between the comrade-Whisper path and the new
  on-device Parakeet path (§2b).
- The persistent-process investigation result and resulting architecture
  choice (§2d) — this is a real design decision, report findings before
  committing.
- Whether `GrammarCorrector` needs any real implementation or stays a no-op
  (§2a).
