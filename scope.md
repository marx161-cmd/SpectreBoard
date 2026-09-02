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
