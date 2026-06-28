# SpectreBoard — Build Status

**Built:** 2026-06-28
**APK:** `app/build/outputs/apk/release/SpectreBoard_4.0-alpha2-release.apk` (~52MB)
**Installed on:** Pixel 10 Pro (`blazer`)
**Signed with:** Termux suite key (`com.termux.*` ecosystem)

## What's done

### Project identity
- Forked from HeliBoard v4.0-alpha2 (`ec00934d`)
- Renamed to SpectreBoard (display name, APK output, all translations)
- Package: `com.termux.spectreboard` (application ID)
- Namespace: `com.termux.spectreboard.latin`

### Phase 1 — Data collection (complete)
- `GestureDataGathering.kt` — widened to accept `INPUT_STYLE_TYPING` alongside `INPUT_STYLE_TAIL_BATCH`
- `Suggest.kt:337` — same gate widened
- Tap + swipe data now flows into `GESTURE_DATA` SQLite table

### Custom dictionary pipeline (complete)
- `~/homelab/kenlm/corpus2combined.py` — smart-quote tokenizer, artifact filtering, sentence-aware bigrams
- Combined corpus: ~2.1M user words + ~17.6M combined words (Google Takeout + ChatGPT exports)
- `main_en_US.dict` (2.4MB, 80K words + 500K bigrams) bundled in assets

### Three-tier autocorrect reranking (complete)
- **Tier 1 — Spatial Gaussian scorer** (`SpatialScorer.kt`): proximity-weighted key-distance reranking
- **Tier 2 — KenLM 4-gram scorer** (`KenLmScorer.kt`): via JNI (`libspectre_score.so`), built with NDK arm64, O3+thin LTO
  - Model: `spectre.blm` (54 MB) — push to `/data/data/com.termux.spectreboard/files/`
- **Tier 3 — GRU-CIFG LM** (`GruScorer.kt`): CIFG-LSTM via ONNX Runtime, in-process
  - Model: `gru_cifg.onnx` (58 MB) — push to `/data/data/com.termux.spectreboard/files/`
  - Logits cache: skips ONNX inference when composing context is unchanged between keystrokes

### Whisper speech-to-text IME (complete — 2026-06-28)
- **`WhisperRecognizer.kt`** (`spectre/WhisperRecognizer.kt`): tap-to-toggle speech input
- **`WhisperG5WorkerClient.kt`** (`spectre/WhisperG5WorkerClient.kt`): strict native G5 worker encoder client
- Toolbar key `WHISPER_MIC` (KeyCode `-10057`) — add via SpectreBoard Settings -> Toolbar Keys
- Encoder: `whisper_base_encoder_fp32_g5.tflite` (47MB) via packaged `libwhisper_g5_worker.so` + `libLiteRtDispatch_GoogleTensor.so`
- Decoder: `whisper_base_decoder.onnx` (300MB FP32) via ONNX Runtime + XNNPACK
- Push the decoder and vocab files to `/data/data/com.termux.spectreboard/files/`
- Push the G5 encoder model to `/data/local/tmp/whisper_base_encoder_fp32_g5.tflite`
- No ORT encoder fallback: G5 worker failures are logged, surfaced, and saved as `ERROR: ...` in the `.hyp.txt`
- Mel spectrogram: pure Kotlin Cooley-Tukey FFT (512-pt), 80-band mel filterbank with Slaney area normalization and correct Whisper log10 normalization
- Greedy decode: `[SOT=50258, EN=50259, TRANSCRIBE=50359, NOTIMESTAMPS=50363]`, max 224 tokens
- Haptics: `EFFECT_CLICK` on start, `EFFECT_DOUBLE_CLICK` on done (API 29+)
- WAV + `.hyp.txt` saved to `externalFiles/whisper-samples/session_DATE/snippet_NNN.*`
- RECORD_AUDIO permission in manifest
- Models: `~/homelab/iree-stack/whisper-tflite/out_base/` (whisper-base, source on comrade)
- Encoder dimension: 512-dim hidden (1500 frames * 512 = 768,000 output floats, 343/343 ops offloaded to G5 NPU)

### Toolbar executor (complete)
- `SpectreBoardExecutor.kt`: `@local`, `@comrade` SSH, `!bg`, `!nonotify`, `!tag=` prefixes
- Routes via Termux `RUN_COMMAND` intent → `spectreboard` tmux session; results via Android notifications
- Requires: Termux `allow-external-apps=true` + `POST_NOTIFICATIONS` granted

### Direct Input Mode (complete)
- Toolbar button (padlock icon, KeyCode `-10056`) — bypasses composing/autocorrect, commits each character immediately
- State persisted in prefs; suggestions strip returns empty when active

### Build infrastructure
- Release signing via `~/.gradle/gradle.properties` (`TERMUX_KEYSTORE`, `TERMUX_STORE_PASSWORD`, `TERMUX_KEY_ALIAS`, `TERMUX_KEY_PASSWORD`)
- KenLM JNI built by Gradle task `buildKenLmJni` (NDK CMake, O3, thin LTO, no Boost/bzip2/lzma)
- R8 full mode enabled (`android.enableR8.fullMode=true`)

## Model files needed on device

Push these to `/data/data/com.termux.spectreboard/files/` after install:

| File | Size | Source |
|---|---|---|
| `spectre.blm` | 54 MB | [HF: spectreboard-models](https://huggingface.co/marx161-cmd/spectreboard-models) |
| `gru_cifg.onnx` | 58 MB | [HF: spectreboard-models](https://huggingface.co/marx161-cmd/spectreboard-models) |
| `whisper_base_decoder.onnx` | 300 MB | `~/homelab/iree-stack/whisper-tflite/out_base/` |
| `whisper_base_vocab.txt` | ~450 KB | `~/homelab/iree-stack/whisper-tflite/out_base/` |
| `whisper_base_special_tokens.json` | ~97 B | `~/homelab/iree-stack/whisper-tflite/out_base/` |

Also push this encoder model to `/data/local/tmp/`:

| File | Size | Source |
|---|---|---|
| `whisper_base_encoder_fp32_g5.tflite` | 47 MB | `~/homelab/iree-stack/whisper-tflite/out_base/` |

## Key paths

| What | Where |
|---|---|
| APK | `app/build/outputs/apk/release/SpectreBoard_4.0-alpha2-release.apk` |
| KenLM scorer source | `scorer/cpp/` (kenlm submodule at `scorer/kenlm/`) |
| English dict | `app/src/main/assets/dicts/main_en_US.dict` |
| Theme | `app/src/main/assets/themes/pixelteal.json` |
| Overview doc | `SpectreBoard-Overview.md` |

## G5 NPU note (2026-06-28)

The in-process LiteRT JNI G5 path was blocked by Android app sandbox dispatch startup failures, so SpectreBoard now launches a packaged native worker process instead. The worker runs under the app UID, loads the packaged Tensor dispatch library from `jniLibs`, and uses `/data/local/tmp/whisper_base_encoder_fp32_g5.tflite`.

The older 16MB int8-compiled G5 whisper-tiny encoder produced NaN output on real floor-heavy Whisper mel tensors. Recompiling from raw FP32 TFLite with the current GoogleBeta AOT compile path produced working models for both whisper-tiny (22MB, 384-dim) and whisper-base (47MB, 512-dim). All 343/343 ops offloaded to G5 NPU.

Skipping int8 quantization for the decoder as well — the int8 lowering layer on Tensor G5 is prone to catastrophic underflows. The FP32 decoder (300MB) runs on CPU via ONNX Runtime + XNNPACK.

## Perf fix (2026-06-28): unnecessary layout pass on background gathering icon

`KeyboardSwitcher.java:632` was calling `setLayoutParams(getLayoutParams())` on every
`setBackgroundGatheringIndicatorPosition()` call, which triggered a full `requestLayout()` →
`performMeasure()` pass even when `topMargin` hadn't changed. This caused the main thread to
recompute suggestion-strip text layout (`StaticLayout.generate` → `LineBreaker.computeLineBreaks`
in native minikin) on every background gathering icon refresh.

**Fix:** guard `setLayoutParams()` behind a `topMargin` equality check so layout is only
invalidated when the position actually changes.
