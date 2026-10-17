# SpectreBoard on-device Parakeet dictation — scope

Append-only. New dated entries when decisions change; never edit/delete old entries.

## 2026-09-02 — Initial scope

**Why:** Comrade's server-side Whisper dictation (see homelab memory
`project_whisper_dictation_ffmpeg`) works and is fixed, but round-trips audio
over the network to comrade and back. User found `Outspoke` (github
minburg/outspoke, GPLv3), an open-source Android keyboard doing **fully
on-device** streaming ASR via NVIDIA Parakeet-TDT-0.6B-v3 (FastConformer
encoder + TDT decoder/joint, ONNX Runtime Mobile) and reported it as good as
or better than the comrade Whisper pipeline, zero corrections needed, on
speech alone (no network round trip).

**G5 NPU offload explored and abandoned this session:** spent significant
effort trying to get Parakeet's encoder running on the Pixel's Tensor G5 NPU
via the `~/homelab/iree-stack/GoogleBeta` LiteRT AOT pipeline (`litert_torch`
export from the real `nvidia/parakeet-tdt-0.6b-v3` HF checkpoint, then
`aot_compile_g5_tflite.py`). The `litert_torch` conversion itself succeeded
cleanly (real proven Google toolchain, handles >2GB models fine, unlike
generic `onnx2tf` which hit hard FlatBuffers/protobuf 2GB ceilings on this
model — documented separately in homelab memory
`project_g5_qwen_cache_bug`-adjacent notes). But the actual G5 NPU AOT
*compile* step crashes with a generic `INTERNAL` error from Google's
closed-source beta compiler on this architecture, reproducibly, at every
granularity tried: full encoder (3324 ops), and 4-way chunked splits down to
~840 ops each (both the subsampling-containing first chunk and pure
repeated-Conformer-layer middle chunks). Partitioning always selects 100% of
ops as NPU-eligible; the crash is in the actual native compile pass, not an
op-support gap. Conclusion: this beta compiler cannot currently compile this
Conformer architecture at all, at any of the granularities tried — treated as
a real capability ceiling of the beta SDK, not a missing flag. Decoder/joint
was separately ruled out for NPU regardless of this finding — DeepSeek's
architecture review of Outspoke found `decodeChunk` runs the TDT joint
network **one encoder frame at a time**, carrying LSTM state across
thousands of tiny sequential inference calls per utterance — not a batchable,
NPU-friendly workload by nature.

**Decision: on-device Parakeet via ONNX Runtime (CPU), not NPU.** SpectreBoard
already depends on `onnxruntime-android` (used by `GruScorer`) and already has
prior on-device Whisper-via-ORT work as precedent. The int8 ONNX files
Outspoke ships (`encoder-model.int8.onnx`, `decoder_joint-model.int8.onnx`,
`nemo128.onnx`, `vocab.txt`, `config.json`) are pulled from the phone,
verified byte-identical to the canonical `istupakov/parakeet-tdt-0.6b-v3-onnx`
HF repo, and already contain ORT-native dynamic-quantization ops (
`DynamicQuantizeLSTM`/`DynamicQuantizeLinear`) that ONNX Runtime handles
natively — these are exactly the ops that blocked TFLite/G5 conversion, and
are a non-issue here. Zero re-conversion needed; these are the files Outspoke
itself runs today.

**Port plan:** `ParakeetEngine.kt` (Outspoke, GPLv3 — SpectreBoard/HeliBoard is
also GPLv3, so this is a clean license-compatible port, not a rewrite) is a
complete, self-contained `SpeechEngine`/`ChunkStreamingEngine` implementation:
ONNX session management, the full greedy TDT decode loop with LSTM state
carry-over, detokenization, blank-ID resolution, word-beam alternatives — all
in one ~1100-line file depending only on `ai.onnxruntime.*` and a plain
`AudioChunk` data holder. Porting this wholesale (per the project's own
"port wholesale, don't hand-roll" lesson from the artemisd daemon) rather than
reimplementing the TDT streaming contract from scratch. `InferenceRepository`
(2544 lines — VAD/chunking/confidence-gating/post-processing orchestration
around the engine) is NOT ported in this first pass; a first working version
wires `ParakeetEngine` directly into the existing toggle-to-record dictation
UX (`StreamDictation`-equivalent trigger in `KeyboardActionListenerImpl`),
buffering the whole utterance and running one `encode` + greedy `decode` pass
on stop, same shape as `ParakeetEngine.transcribe()`. True incremental
streaming (partial results while still speaking, matching the current
comrade-Whisper UX) is a later iteration once the basic on-device path is
proven correct on-device.

**Model storage:** models (~700MB total, encoder int8 dominates at 622MB) are
pushed to the app's private storage at runtime (matching Outspoke's own
download-on-first-use pattern and SpectreBoard's existing DE/CE model-storage
conventions — see homelab memory `project-spectreboard`'s DE-vs-CE trap
notes), NOT bundled into the APK. Comrade already has the exact files at
`~/homelab/iree-stack/GoogleBeta/outspoke-parakeet-v3/` (encoder-model.int8.onnx,
decoder_joint-model.int8.onnx, nemo128.onnx, vocab.txt, config.json).

**Relationship to comrade Whisper dictation:** not a replacement (yet) — the
existing `StreamDictation`/comrade-Whisper toolbar-mic path stays as-is. This
is a new, separate on-device engine, likely wired to a different toolbar key
or toggle initially, so the working comrade path is never put at risk while
this is being built out.

---

# Personal adaptation pipeline — scope

Separate subsystem from the Parakeet entry above, recorded in the same file
(same append-only rule applies).

## 2026-10-02 — Initial scope

**Why:** single-user keyboard on owned hardware, so none of the commercial
constraints (federated learning, differential privacy, frozen base models)
apply. Idea came from the user probing it with Gemini; reviewed against the
real code before agreeing. Goal: autocorrect that learns from the user's own
typing and corrections via an offline loop on comrade (GPU/CPU there, not the
phone), with trained artifacts pushed back to the device.

**Agreed decisions:**

1. **Step 0/1 — persist training signal first.** Before this, the only
   signal was `Log.i("CorrectionOverride", …)` in `InputLogic.commitChosenWord`
   (logcat ring buffer, effectively nothing retained) and the write-only
   `CorrectionHistory` (8 entries, never read). Persist to app storage:
   - manual picks from the strip (typed X → picked Y),
   - **backspace reverts of an autocorrect** (strongest negative label),
   - accepted autocorrects / typed-word commits (weak positive + the
     committed-text stream that becomes the personal corpus),
   - with n-gram context, and the per-candidate scorer features at commit
     time (dict / GRU / KenLM / spatial) so the reranker can be trained
     offline without re-deriving them.
   - Skip password / no-learning fields (reuse `mIncognitoModeEnabled`).
     Not for privacy — they're noise.
   - Pulled to comrade over Tailscale.
2. **Step 2 — nightly KenLM rebuild on comrade** from base corpus
   (`corpus_for_gru.txt`) + personal committed text, quantized like
   `spectre_q8.blm`, gated by a held-out eval, pushed to the DE path
   (`/data/user_de/0/com.termux.spectreboard/files/`, chmod 644) with a
   reload mechanism (scorers currently load once per IME process).
3. **Step 3 — learned reranker weights.** Pick/revert pairs are *ranking*
   decisions, so they train a small model over the four scorer features to
   replace the fixed lexicographic order in `Suggest.rerankCombined()`
   (dict band → GRU → KenLM → spatial). Not DPO on the GRU (too few pairs/day,
   overfits).
4. **Step 4 — spatial label hygiene.** `SpatialModelWorker` already rebuilds
   per-key Gaussians from GESTURE_DATA; fix is excluding taps from reverted
   (mislabelled) words, not faster updates.
5. **Step 5 — occasional GRU continued training** on personal + base text
   mix. Lowest priority.

**Hard constraints:**
- **Every rebuilt artifact must beat the current one on a held-out slice of
  the user's own typing before it's pushed.** Learning from accepted
  autocorrects is a self-reinforcing loop; the eval gate + revert signal are
  what stop it drifting.
- **Personally-trained models never go to the public repo or the public HF
  repo `marx161-cmd/spectreboard-models`** — n-gram/LM models memorise exact
  strings. Base models there stay as-is.

## 2026-10-02 (later) — Save everything, always; privacy filters removed

**Changed:** the initial entry said to skip password / no-learning fields
"because they're noise". User overrode that: **save everything, always** —
single-user device, user audits everything, privacy is irrelevant here.

Found while debugging why the spatial tier was dead (`sp: null` on every
candidate): `GESTURE_DATA` had 0 rows since the 2026-07-25 data wipe because
of three stacked inherited-from-HeliBoard privacy gates:
1. "Discard by default" (`gesture_data_background_gathering_manual_save=true`)
   — taps dropped at field exit unless the toolbar save key was pressed.
2. App allowlist with nothing in it (include-by-default unset → `false`)
   → every app forbidden.
3. `isSavingOk` only kept words from a hash-matched *official HeliBoard*
   main dictionary (SpectreBoard's custom dict never matches), plus contacts /
   excluded-word / password / email / incognito / non-TYPE_CLASS_TEXT
   (terminal) filters, and redacted all non-target alternatives.

**Decision:** the discard option is removed (setting + UI row; the toolbar
gathering key now saves instead of discarding); apps are included by default;
all field/app/incognito/dictionary/word filters are removed; alternatives are
kept unredacted (they're the reranker's negatives). `AdaptationLog` also
logs in incognito/password fields now. What remains: the master
background-gathering switch (on), SPACE_AWARE_GESTURE skip, shortcut-only
skip.

**Consequence carried into step 2+:** passwords/OTPs will now be in the
raw logs. They're not filtered at collection; if they should not surface as
suggestions, filter at training time on comrade — never at collection.

## 2026-10-02 (evening) — KenLM v2 live; emoji deliberately not modelled

- KenLM is now rebuilt on comrade by `~/kenlm/kenlm_rebuild.py` from ChatGPT +
  Gemini + Claude exports + replayed personal typing, **normalised the way the
  phone queries it** (lowercase, punctuation split off, sentence per line). The
  old model was trained on raw cased text with punctuation attached, which
  `KenLmScorer` (lowercased context + candidates) could never match.
  `spectre_v2_q8.blm` pushed 15:56 via tmp+rename hot-reload (c04120bd).
- The normaliser keeps only letters/digits/internal apostrophes, so **emoji are
  not in the model**. User decision: that's fine — emoji suggestions aren't
  wanted from KenLM. Don't "fix" this; the nightly rebuild may keep dropping them.

## 2026-10-17 — Dictation redesign: chunked whole-utterance Whisper on comrade; Parakeet dropped

**Changed:** the 2026-09-02 on-device Parakeet port is out. User verdict: it
fails to do anything about half the time, in this port *and* in Outspoke
itself. User dislikes Parakeet and explicitly does **not** want it fixed.
Google voice typing and FUTO Voice Input were rejected as worse than Parakeet.
The old comrade Whisper streaming path (`StreamDictation.kt` → :8770) was
also rejected, for two reasons the user hit in real use:
1. **Garbage text.** af_whisper cut the audio every ~3 s at VAD pauses and
   transcribed each piece with no context. That gave doubled, dropped and
   invented words at every join.
2. **Lost or deleted text.** Blocks either never arrived, or arrived in a
   state where any edit deleted them (composing-region / revise handling).
The user also speaks for longer than Whisper's 30 s window.

**Decided design (agreed with user 2026-10-17):**
- **Phone records Opus**, the format Android records natively. Five minutes
  of speech is under 1 MB.
- **Chunked upload while speaking.** The phone cuts at a pause near ~25 s,
  never mid-word, and uploads each piece immediately, so comrade transcribes
  while the user is still talking. When the user stops, only the last piece
  is left. The user's point: whole-file-at-the-end wastes idle time.
- **Context carried between pieces.** Each piece is transcribed with the
  previous text as the Whisper prompt, limited to the last sentence or two.
  Passing everything risks error propagation and repetition loops.
- **Text is typed once, at the end, as plain committed text.** It never goes
  into a composing span, and no revise or delete is ever sent. Pieces collect
  in the background. A small "N pieces done" indicator on the keyboard shows
  progress.
- **Nothing gets lost.** Each recording gets a unique ID and each piece a
  sequence number. The phone keeps every piece until comrade acknowledges it,
  and retries are idempotent: a retry returns the cached result and never
  re-transcribes or duplicates text. If comrade is unreachable, the keyboard
  says so and keeps the recording for a later retry.
- **Transport:** plain HTTP over Tailscale to comrade (100.108.8.60).
- **Model / hardware:** whisper.cpp **large-v3-turbo**
  (`~/src/whisper.cpp/models/ggml-large-v3-turbo.bin`, sha256 matches HF) on
  the **iGPU** (Vulkan device 1, Radeon 760M), keeping the RX 9060 XT free
  for the shader backdrop. A whole-file test took 5.9 s for a 45 s clip, with
  no joins and no GPU reset. The same whole-file run on the 9060 XT crashed
  twice with Vulkan DeviceLost, and the kernel reset a compute ring each time.
  The suspected cause is a driver timeout under backdrop load, not proven.
  **Don't use device 0 for this without asking.**
- **Server binary:** ffmpeg's whisper filter has no prompt option, so it
  can't carry context. Use whisper.cpp's own `whisper-server`, built at
  `~/src/whisper.cpp/build-vulkan` with its `prompt` field. That it can
  target the iGPU is **not yet verified**.

**Build order:** (1) this entry, (2) verify whisper-server on the iGPU with
turbo, (3) comrade chunk service, (4) SpectreBoard phone side behind the
normal mic button. Each step is stoppable.

**Open:** what happens to the Parakeet code in `spectre/parakeet/` and to
the old `StreamDictation` / :8770 path. Not decided; leave them in place
until the new path works.

## 2026-10-17 (later) — Transport changed: ssh pipe instead of HTTP

**Changed:** "plain HTTP over Tailscale" from the entry above is replaced by
the user's suggestion: **pipe each piece over ssh**. SpectreBoard shares
Termux's UID, so it can run Termux's own `ssh` with the existing key. Phone to
comrade ssh was verified working 2026-10-17, at 0.9 s for a fresh connection.
- One ssh connection per recording, kept open with ControlMaster, so only the
  first piece pays the setup cost.
- Per piece: the audio goes to stdin of a small comrade script, and the text
  comes back on stdout.
- Comrade keeps one text file per recording ID. It supplies the context
  prompt and makes retries idempotent, because a repeated sequence number
  returns the saved text.
- `whisper-server` (turbo, iGPU) stays resident on comrade, bound to
  **127.0.0.1 only**. ssh is the only way in, so no new network listener.
- Verified 2026-10-17: whisper-server on `-dev 1` with turbo did a 45 s clip
  in 4.6 s and accepted the `prompt` field. Its plain-text reply wraps lines
  mid-word, so the script must use the JSON reply and join the segments.

Everything else in the entry above stands: Opus, pieces cut at pauses near
25 s, context limited to the last sentence or two, text typed once at the
end as plain committed text, nothing lost.

## 2026-10-17 (night) — Parakeet removed entirely; its plumbing is reused

**Decided (user):** the Parakeet code goes completely. That's `ParakeetEngine`,
`InferenceRepository`, the live-rewrite `TextInjector` / `TranscriptAligner`,
the `correction/` and `model/` packages, and acoustic suggestions. It costs
space for nothing and SpectreBoard is big enough already. This answers the
"Open" question two entries up.

**Kept and rewired to the comrade Whisper path:** the mic toggle and UI
states (`KeyboardViewModel` / `KeyboardUiState`), the foreground
`InferenceService` with its persistent notification and haptics, and
`AudioCaptureManager` plus the VAD filters. The VAD supplies the pause points
for cutting pieces near 25 s.

**Constraint found:** the kept files import `InferenceRepository`,
`TextInjector` and others directly, so the deletion can't be done on its own
without breaking the build. **Removal and rewiring land together** in the
phone-side step, with a new small component replacing `InferenceRepository`
behind the same `transcribe(audio) -> Flow<TranscriptResult>` call. It emits a
single final result and no partials.

**Done now:**
- Safety checkpoint: annotated tag `pre-parakeet-removal` on 7ad7d4d4. Restore
  with `git checkout pre-parakeet-removal -- <path>`.
- Phone model folder `/data/user_de/0/com.termux.spectreboard/files/models/parakeet-v3`
  (640 MB) deleted. An identical-size copy stays on comrade at
  `~/homelab/iree-stack/GoogleBeta/outspoke-parakeet-v3/`. The phone was at
  97% full on /data (7.2 GB free) afterwards.
- Until the phone-side step ships, the installed SpectreBoard's Parakeet mic
  path has no model and won't work. That's accepted, since it was already
  unreliable.

## 2026-10-17 (late night) — Phone side built and installed (untested by voice)

Implemented as `spectre/PieceDictation.kt`. The controls are taken over from
`StreamDictation.kt`: one buzz on start, two on stop, the animated progress
notification, and one `isRecording` flag that drives the toolbar light. The
light used to read `StreamDictation.isRecording` while the button toggled
Parakeet, so it never lit during Parakeet dictation.

- Pieces are cut at a 300 ms pause once ~20 s long (adaptive noise floor),
  or at the quietest point of the last 5 s if no pause comes by 30 s. A last
  piece under 0.45 s, or all quiet, is dropped.
- Each piece is encoded as Opus in Ogg via MediaCodec/MediaMuxer (WAV
  fallback), saved in `cacheDir/dictation/<id>/`, and piped through Termux's
  `ssh` (ControlMaster) to `dictate-piece`. Retries back off over ~90 s.
- Text is typed once at the end with `commitText(text + " ")`.
- Parakeet deleted: the `spectre/parakeet/` folder, `AcousticSuggestions`, the
  Suggest.kt acoustic block, the InputLogic guard, the `InferenceService`
  manifest entry, `silero_vad_v4.onnx`, the DataStore dependency and the
  Parakeet strings.

**Differences from the entries above, flagged to the user:**
- The "N pieces done" progress shows in the **notification text**, not on the
  keyboard itself.
- **New behaviour I added, needs the user's OK:** if the field changed
  between the stop tap and the text arriving, or typing fails, the text goes
  to the **clipboard** with a toast, not into the field. A recording that
  couldn't reach comrade is kept, and on the next dictation it's sent and its
  text is recovered to the clipboard.
- `StreamDictation.kt` and `SpectreRecognitionService` (old :8770 path, the
  system voice-input service, not currently selected) are left in place,
  unchanged.

## 2026-10-17 (23:40) — User confirmed it works; notification retuned

- **User verified by voice:** long rants dictate fine, and the text arrives
  after the stop tap.
- **Stop is now unmistakable (user ask):** after the stop tap the
  notification switches to grey "Stopped — transcribing" with an
  indeterminate bar until the text arrives. It used to keep the red
  advancing bar, so it wasn't clear whether stop had registered.
- **30 s countdown (user ask):** while recording, the bar drains from full to
  empty over 30 s and repeats, and the text shows the seconds left
  ("23s · 2 pieces done"). It's for judging Whisper's 30 s window by eye. The
  cycle is a plain timer and does not reset when a piece is cut.
- Still open: the user's OK on the clipboard fallback (previous entry).

## 2026-10-17 (23:50) — Clipboard fallback approved

User OK on the clipboard fallback (field changed, typing failed, or recovered
recording -> clipboard + toast). Dictation is verified working, so the work
is pushed to GitHub.
