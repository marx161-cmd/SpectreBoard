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
