# SpectreBoard — Build Status

**Built:** 2026-06-27  
**APK:** `app/build/outputs/apk/release/SpectreBoard_4.0-alpha2-release.apk` (~24MB)  
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

### Whisper speech-to-text IME (complete — 2026-06-27)
- **`WhisperRecognizer.kt`** (`spectre/WhisperRecognizer.kt`): tap-to-toggle speech input
- Toolbar key `WHISPER_MIC` (KeyCode `-10057`) — add via SpectreBoard Settings → Toolbar Keys
- Encoder: `whisper_tiny_encoder_int8.onnx` (32MB) via ONNX Runtime + XNNPACK
- Decoder: `whisper_tiny_decoder_int8.onnx` (47MB) via ONNX Runtime + XNNPACK
- Push both to `/data/data/com.termux.spectreboard/files/` alongside vocab files
- Mel spectrogram: pure Kotlin Cooley-Tukey FFT (512-pt), 80-band mel filterbank with Slaney area normalization and correct Whisper log10 normalization
- Greedy decode: `[SOT=50258, EN=50259, TRANSCRIBE=50359, NOTIMESTAMPS=50363]`, max 224 tokens
- Haptics: `EFFECT_CLICK` on start, `EFFECT_DOUBLE_CLICK` on done (API 29+)
- WAV + `.hyp.txt` saved to `externalFiles/whisper-samples/session_DATE/snippet_NNN.*`
- RECORD_AUDIO permission in manifest
- Models: `~/homelab/iree-stack/whisper-tflite/out/` (source on comrade)

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
| `whisper_tiny_encoder_int8.onnx` | 32 MB | `~/homelab/iree-stack/whisper-tflite/out/` |
| `whisper_tiny_decoder_int8.onnx` | 47 MB | `~/homelab/iree-stack/whisper-tflite/out/` |
| `whisper_tiny_vocab.txt` | ~300 KB | `~/homelab/iree-stack/whisper-tflite/out/` |
| `whisper_tiny_special_tokens.json` | ~2 KB | `~/homelab/iree-stack/whisper-tflite/out/` |

## Key paths

| What | Where |
|---|---|
| APK | `app/build/outputs/apk/release/SpectreBoard_4.0-alpha2-release.apk` |
| KenLM scorer source | `scorer/cpp/` (kenlm submodule at `scorer/kenlm/`) |
| English dict | `app/src/main/assets/dicts/main_en_US.dict` |
| Theme | `app/src/main/assets/themes/pixelteal.json` |
| Overview doc | `SpectreBoard-Overview.md` |

## G5 NPU note (2026-06-27)

LiteRT CompiledModel G5 NPU path (`LiteRtEncoder.kt` — not wired in) is blocked for untrusted Android apps. The SouthBound firmware dispatch init calls `LITERT_FATAL` → `abort()` (non-recoverable) for any process running in the Android app sandbox. Requires system-app privileges or IPC to a privileged native process to resolve. ORT+XNNPACK is the active encoder path.
